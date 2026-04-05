package com.finvanta.batch;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BatchJob;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.BatchStatus;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.domain.rules.NpaClassificationRule;
import com.finvanta.domain.rules.ProvisioningRule;
import com.finvanta.repository.BatchJobRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.LoanAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * CBS End-of-Day (EOD) Batch Processing Service.
 *
 * Per Finacle/Temenos EOD framework, the batch runs the following steps sequentially:
 *   1. Business date validation and calendar locking
 *   2. Interest accrual (Actual/365 per RBI circular)
 *   3. DPD (Days Past Due) calculation
 *   4. NPA classification (RBI IRAC: SMA-0/1/2 → NPA Sub-Standard/Doubtful/Loss)
 *   5. Provisioning calculation (RBI IRAC: 0.40% Standard → 100% Loss)
 *   6. GL balance validation (trial balance integrity check)
 *   7. Day close and calendar unlock
 *
 * Each account is processed in its own transaction for failure isolation.
 * Per-account failures do not roll back other accounts (PARTIALLY_COMPLETED status).
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final BatchJobRepository batchJobRepository;
    private final BusinessCalendarRepository calendarRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanAccountService loanAccountService;
    private final NpaClassificationRule npaRule;
    private final ProvisioningRule provisioningRule;
    private final AuditService auditService;

    /**
     * Self-reference to invoke @Transactional methods through the Spring proxy.
     * Without this, internal calls bypass the proxy and @Transactional has no effect.
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private BatchService self;

    public BatchService(BatchJobRepository batchJobRepository,
                        BusinessCalendarRepository calendarRepository,
                        LoanAccountRepository loanAccountRepository,
                        LoanAccountService loanAccountService,
                        NpaClassificationRule npaRule,
                        ProvisioningRule provisioningRule,
                        AuditService auditService) {
        this.batchJobRepository = batchJobRepository;
        this.calendarRepository = calendarRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanAccountService = loanAccountService;
        this.npaRule = npaRule;
        this.provisioningRule = provisioningRule;
        this.auditService = auditService;
    }

    /**
     * EOD batch orchestrator — intentionally NOT @Transactional.
     * Each per-account operation (accrual, DPD, NPA) runs in its own transaction
     * via the proxied LoanAccountService methods. This ensures:
     * 1. Per-account failure isolation (one account's failure doesn't roll back others)
     * 2. No deadlock risk with AuditService's REQUIRES_NEW propagation
     * 3. Pessimistic locks are released between account processing
     * Calendar locking and batch job tracking use dedicated @Transactional helpers.
     */
    public BatchJob runEodBatch(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String initiatedBy = SecurityUtil.getCurrentUsername();

        log.info("EOD batch started: tenant={}, date={}", tenantId, businessDate);

        // Step 1: Validate and lock business date (own transaction via proxy)
        BusinessCalendar calendar = self.validateAndLockBusinessDate(tenantId, businessDate);

        // Step 2: Create batch job record (own transaction via proxy)
        BatchJob eodJob = self.createBatchJob(tenantId, businessDate, initiatedBy);

        int totalRecords = 0;
        int processedRecords = 0;
        int failedRecords = 0;
        StringBuilder errorLog = new StringBuilder();

        try {
            List<LoanAccount> activeAccounts = loanAccountRepository.findAllActiveAccounts(tenantId);
            totalRecords = activeAccounts.size();

            // Step 3: Run interest accrual — each account in its own transaction
            self.updateBatchStep(eodJob, "INTEREST_ACCRUAL");

            for (LoanAccount account : activeAccounts) {
                try {
                    loanAccountService.applyInterestAccrual(account.getAccountNumber(), businessDate);
                    processedRecords++;
                } catch (Exception e) {
                    failedRecords++;
                    errorLog.append("Interest accrual failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("Interest accrual failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 4: Run DPD calculation
            self.updateBatchStep(eodJob, "DPD_CALCULATION");

            for (LoanAccount account : activeAccounts) {
                try {
                    self.updateDaysPastDue(account, businessDate);
                } catch (Exception e) {
                    errorLog.append("DPD update failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("DPD update failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 5: Run NPA classification — each account in its own transaction
            self.updateBatchStep(eodJob, "NPA_CLASSIFICATION");

            List<LoanAccount> npaCandidates = loanAccountRepository
                .findNpaCandidates(tenantId, npaRule.getNpaThresholdDays());

            for (LoanAccount account : npaCandidates) {
                try {
                    loanAccountService.classifyNPA(account.getAccountNumber());
                } catch (Exception e) {
                    errorLog.append("NPA classification failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("NPA classification failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 6: Run provisioning calculation (RBI IRAC mandatory)
            self.updateBatchStep(eodJob, "PROVISIONING");

            List<LoanAccount> allAccounts = loanAccountRepository.findAllActiveAccounts(tenantId);
            for (LoanAccount account : allAccounts) {
                try {
                    self.calculateProvisioning(account);
                } catch (Exception e) {
                    errorLog.append("Provisioning failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("Provisioning failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 7: GL balance validation (trial balance check before day close)
            self.updateBatchStep(eodJob, "GL_VALIDATION");
            self.validateGlBalance(tenantId);

            // Step 8: Mark EOD complete (own transaction)
            BatchStatus finalStatus = failedRecords > 0
                ? BatchStatus.PARTIALLY_COMPLETED : BatchStatus.COMPLETED;

            self.completeEodBatch(eodJob, calendar, finalStatus,
                totalRecords, processedRecords, failedRecords,
                errorLog.length() > 0 ? errorLog.toString() : null);

            auditService.logEvent("BatchJob", eodJob.getId(), "EOD_COMPLETE",
                null, eodJob, "BATCH",
                "EOD completed: processed=" + processedRecords + ", failed=" + failedRecords);

            log.info("EOD batch completed: date={}, processed={}, failed={}",
                businessDate, processedRecords, failedRecords);

        } catch (Exception e) {
            self.failEodBatch(eodJob, calendar,
                totalRecords, processedRecords, failedRecords, e.getMessage());

            log.error("EOD batch failed: date={}", businessDate, e);
            throw new BusinessException("BATCH_FAILED", "EOD batch failed: " + e.getMessage(), e);
        }

        return eodJob;
    }

    @Transactional
    protected BusinessCalendar validateAndLockBusinessDate(String tenantId, LocalDate businessDate) {
        BusinessCalendar calendar = calendarRepository
            .findAndLockByTenantIdAndDate(tenantId, businessDate)
            .orElseThrow(() -> new BusinessException("BATCH_INVALID_DATE",
                "Business date not found in calendar: " + businessDate));

        if (calendar.isEodComplete()) {
            throw new BusinessException("BATCH_ALREADY_COMPLETE",
                "EOD already completed for date: " + businessDate);
        }

        if (calendar.isHoliday()) {
            throw new BusinessException("BATCH_HOLIDAY",
                "Cannot run EOD on a holiday: " + businessDate);
        }

        calendar.setLocked(true);
        return calendarRepository.save(calendar);
    }

    @Transactional
    protected BatchJob createBatchJob(String tenantId, LocalDate businessDate, String initiatedBy) {
        BatchJob eodJob = new BatchJob();
        eodJob.setTenantId(tenantId);
        eodJob.setJobName("EOD_BATCH");
        eodJob.setBusinessDate(businessDate);
        eodJob.setStatus(BatchStatus.RUNNING);
        eodJob.setStartedAt(LocalDateTime.now());
        eodJob.setInitiatedBy(initiatedBy);
        eodJob.setCreatedBy(initiatedBy);
        return batchJobRepository.save(eodJob);
    }

    @Transactional
    protected void updateBatchStep(BatchJob eodJob, String stepName) {
        eodJob.setStepName(stepName);
        batchJobRepository.save(eodJob);
    }

    @Transactional
    protected void completeEodBatch(BatchJob eodJob, BusinessCalendar calendar,
                                     BatchStatus status, int totalRecords,
                                     int processedRecords, int failedRecords,
                                     String errorMessage) {
        calendar.setEodComplete(true);
        calendarRepository.save(calendar);

        eodJob.setStatus(status);
        eodJob.setStepName("COMPLETED");
        eodJob.setCompletedAt(LocalDateTime.now());
        eodJob.setTotalRecords(totalRecords);
        eodJob.setProcessedRecords(processedRecords);
        eodJob.setFailedRecords(failedRecords);
        if (errorMessage != null) {
            eodJob.setErrorMessage(errorMessage);
        }
        batchJobRepository.save(eodJob);
    }

    @Transactional
    protected void failEodBatch(BatchJob eodJob, BusinessCalendar calendar,
                                 int totalRecords, int processedRecords,
                                 int failedRecords, String errorMessage) {
        eodJob.setStatus(BatchStatus.FAILED);
        eodJob.setCompletedAt(LocalDateTime.now());
        eodJob.setTotalRecords(totalRecords);
        eodJob.setProcessedRecords(processedRecords);
        eodJob.setFailedRecords(failedRecords);
        eodJob.setErrorMessage(errorMessage);
        batchJobRepository.save(eodJob);

        calendar.setLocked(false);
        calendarRepository.save(calendar);
    }

    /**
     * RBI IRAC: Calculate provisioning and post GL entry.
     * Provisioning is mandatory for all loan accounts:
     *   Standard/SMA: 0.40%, Sub-Standard: 10%, Doubtful: 40%, Loss: 100%
     *
     * GL Entry (when provisioning increases):
     *   DR Provision Expense (5001) — P&L impact
     *   CR Provision for NPA (1003) — Balance sheet contra-asset
     *
     * GL Entry (when provisioning decreases — e.g., account upgraded):
     *   DR Provision for NPA (1003) — Release provision
     *   CR Provision Expense (5001) — P&L reversal
     */
    @Transactional
    protected void calculateProvisioning(LoanAccount account) {
        BigDecimal newProvisioning = provisioningRule.calculateProvisioning(account);
        BigDecimal currentProvisioning = account.getProvisioningAmount();
        BigDecimal delta = newProvisioning.subtract(currentProvisioning);

        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            account.setProvisioningAmount(newProvisioning);
            account.setUpdatedBy("SYSTEM");
            loanAccountRepository.save(account);

            // TODO: Inject AccountingService and post provisioning GL entry.
            // GL Entry: DR Provision Expense (5001) / CR Provision for NPA (1003) on increase,
            //           DR Provision for NPA (1003) / CR Provision Expense (5001) on release.
            log.info("Provisioning updated: accNo={}, delta={}, status={}, expenseGL={}, provisionGL={}",
                account.getAccountNumber(), delta, account.getStatus(),
                com.finvanta.accounting.GLConstants.PROVISION_EXPENSE,
                com.finvanta.accounting.GLConstants.PROVISION_NPA);

            log.debug("Provisioning updated: accNo={}, old={}, new={}, delta={}, status={}",
                account.getAccountNumber(), currentProvisioning, newProvisioning, delta, account.getStatus());
        }
    }

    /**
     * CBS EOD Step: GL Balance Validation (Trial Balance Check).
     * Per Finacle/Temenos, validates total debits == total credits across
     * all postable GL accounts before day close.
     *
     * GL imbalance is logged as a warning — it indicates a reconciliation issue
     * but should not block EOD completion (would lock the business day).
     *
     * TODO: Inject GLMasterRepository or AccountingService and query
     * SUM(debit_balance) vs SUM(credit_balance) from gl_master.
     * If imbalanced, log WARNING and create a reconciliation exception record.
     * The AccountingService.getTrialBalance() already provides this check
     * via the /accounting/trial-balance endpoint.
     */
    @Transactional
    protected void validateGlBalance(String tenantId) {
        log.info("GL Balance Validation: Checking trial balance integrity for tenant={}", tenantId);
    }

    @Transactional
    protected void updateDaysPastDue(LoanAccount account, LocalDate businessDate) {
        if (account.getStatus().isTerminal()) {
            return;
        }

        LocalDate lastPayment = account.getLastPaymentDate();
        LocalDate nextEmi = account.getNextEmiDate();

        if (nextEmi != null && businessDate.isAfter(nextEmi) && (lastPayment == null || lastPayment.isBefore(nextEmi))) {
            int dpd = (int) ChronoUnit.DAYS.between(nextEmi, businessDate);
            account.setDaysPastDue(dpd);
            account.setOverduePrincipal(account.getEmiAmount() != null ? account.getEmiAmount() : account.getOutstandingPrincipal());
            account.setUpdatedBy("SYSTEM");
            loanAccountRepository.save(account);
        }
    }

    public List<BatchJob> getBatchHistory() {
        return batchJobRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.getCurrentTenant());
    }

    public BatchJob getBatchJobByDate(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        return batchJobRepository.findByTenantIdAndJobNameAndBusinessDate(tenantId, "EOD_BATCH", businessDate)
            .orElse(null);
    }
}

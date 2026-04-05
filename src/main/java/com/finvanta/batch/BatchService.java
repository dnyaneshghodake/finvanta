package com.finvanta.batch;

import com.finvanta.accounting.AccountingService;
import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.accounting.ReconciliationService;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BatchJob;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.BatchStatus;
import com.finvanta.domain.enums.DebitCredit;
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
    private final ReconciliationService reconciliationService;
    private final AccountingService accountingService;

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
                        AuditService auditService,
                        ReconciliationService reconciliationService,
                        AccountingService accountingService) {
        this.batchJobRepository = batchJobRepository;
        this.calendarRepository = calendarRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanAccountService = loanAccountService;
        this.npaRule = npaRule;
        this.provisioningRule = provisioningRule;
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
        this.accountingService = accountingService;
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

            // Step 4: Run DPD calculation.
            // Re-fetch accounts to avoid stale entity versions after interest accrual
            // modified and saved them with incremented @Version in Step 3.
            self.updateBatchStep(eodJob, "DPD_CALCULATION");

            List<LoanAccount> accountsForDpd = loanAccountRepository.findAllActiveAccounts(tenantId);
            for (LoanAccount account : accountsForDpd) {
                try {
                    self.updateDaysPastDue(account, businessDate);
                } catch (Exception e) {
                    errorLog.append("DPD update failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("DPD update failed: accNo={}", account.getAccountNumber(), e);
                }
            }

            // Step 5: Run SMA/NPA classification — each account in its own transaction.
            // Per RBI IRAC + Early Warning Framework, ALL accounts with DPD >= 1 must be
            // classified: SMA-0 (1-30), SMA-1 (31-60), SMA-2 (61-90), NPA (91+).
            // Using threshold=1 ensures SMA accounts are not missed.
            self.updateBatchStep(eodJob, "NPA_CLASSIFICATION");

            List<LoanAccount> classificationCandidates = loanAccountRepository
                .findNpaCandidates(tenantId, 1);

            for (LoanAccount account : classificationCandidates) {
                try {
                    loanAccountService.classifyNPA(account.getAccountNumber(), businessDate);
                } catch (Exception e) {
                    errorLog.append("SMA/NPA classification failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("SMA/NPA classification failed: accNo={}", account.getAccountNumber(), e);
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

            // Step 7: GL reconciliation (subledger vs GL comparison before day close)
            self.updateBatchStep(eodJob, "GL_RECONCILIATION");
            validateGlBalance(businessDate);

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

    /**
     * Validates and locks the business date for EOD processing.
     * Sets dayStatus to EOD_RUNNING per documented lifecycle:
     *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
     * This prevents new transactions during EOD and signals the system state.
     */
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
        calendar.setDayStatus("EOD_RUNNING");
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

    /**
     * Updates the current batch step name. Re-fetches by ID to avoid
     * OptimisticLockException from stale @Version after prior transactions.
     */
    @Transactional
    protected void updateBatchStep(BatchJob eodJob, String stepName) {
        BatchJob fresh = batchJobRepository.findById(eodJob.getId()).orElse(eodJob);
        fresh.setStepName(stepName);
        batchJobRepository.save(fresh);
    }

    @Transactional
    protected void completeEodBatch(BatchJob eodJob, BusinessCalendar calendar,
                                     BatchStatus status, int totalRecords,
                                     int processedRecords, int failedRecords,
                                     String errorMessage) {
        BusinessCalendar freshCal = calendarRepository.findById(calendar.getId()).orElse(calendar);
        freshCal.setEodComplete(true);
        calendarRepository.save(freshCal);

        BatchJob fresh = batchJobRepository.findById(eodJob.getId()).orElse(eodJob);
        fresh.setStatus(status);
        fresh.setStepName("COMPLETED");
        fresh.setCompletedAt(LocalDateTime.now());
        fresh.setTotalRecords(totalRecords);
        fresh.setProcessedRecords(processedRecords);
        fresh.setFailedRecords(failedRecords);
        if (errorMessage != null) {
            fresh.setErrorMessage(errorMessage);
        }
        batchJobRepository.save(fresh);
    }

    @Transactional
    protected void failEodBatch(BatchJob eodJob, BusinessCalendar calendar,
                                 int totalRecords, int processedRecords,
                                 int failedRecords, String errorMessage) {
        BatchJob fresh = batchJobRepository.findById(eodJob.getId()).orElse(eodJob);
        fresh.setStatus(BatchStatus.FAILED);
        fresh.setCompletedAt(LocalDateTime.now());
        fresh.setTotalRecords(totalRecords);
        fresh.setProcessedRecords(processedRecords);
        fresh.setFailedRecords(failedRecords);
        fresh.setErrorMessage(errorMessage);
        batchJobRepository.save(fresh);

        // Revert calendar: unlock and restore DAY_OPEN so EOD can be retried
        BusinessCalendar freshCal = calendarRepository.findById(calendar.getId()).orElse(calendar);
        freshCal.setLocked(false);
        freshCal.setDayStatus("DAY_OPEN");
        calendarRepository.save(freshCal);
    }

    /**
     * RBI IRAC provisioning calculation. Re-fetches account by ID to ensure
     * fresh @Version after prior EOD steps (accrual, DPD, NPA classification).
     *
     * Provisioning is mandatory for all loan accounts:
     *   Standard/SMA: 0.40%, Restructured: 5%, Sub-Standard: 10%, Doubtful: 40%, Loss: 100%
     *
     * GL Entry (when provisioning increases):
     *   DR Provision Expense (5001) / CR Provision for NPA (1003)
     * GL Entry (when provisioning decreases — e.g., account upgraded):
     *   DR Provision for NPA (1003) / CR Provision Expense (5001)
     */
    @Transactional
    protected void calculateProvisioning(LoanAccount account) {
        LoanAccount fresh = loanAccountRepository.findById(account.getId())
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found during provisioning: " + account.getAccountNumber()));

        BigDecimal newProvisioning = provisioningRule.calculateProvisioning(fresh);
        BigDecimal currentProvisioning = fresh.getProvisioningAmount();
        BigDecimal delta = newProvisioning.subtract(currentProvisioning);

        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            fresh.setProvisioningAmount(newProvisioning);
            fresh.setUpdatedBy("SYSTEM");
            loanAccountRepository.save(fresh);

            // Post provisioning GL entry — delta-based (only the change amount)
            BigDecimal absDelta = delta.abs();
            DebitCredit expenseSide = delta.compareTo(BigDecimal.ZERO) > 0
                ? DebitCredit.DEBIT : DebitCredit.CREDIT;
            DebitCredit provisionSide = delta.compareTo(BigDecimal.ZERO) > 0
                ? DebitCredit.CREDIT : DebitCredit.DEBIT;

            String action = delta.compareTo(BigDecimal.ZERO) > 0 ? "charge" : "release";
            try {
                java.util.List<JournalLineRequest> lines = java.util.List.of(
                    new JournalLineRequest(GLConstants.PROVISION_EXPENSE, expenseSide, absDelta,
                        "Provisioning " + action + " - " + fresh.getAccountNumber()),
                    new JournalLineRequest(GLConstants.PROVISION_NPA, provisionSide, absDelta,
                        "Loan loss provision - " + fresh.getAccountNumber())
                );
                accountingService.postJournalEntry(
                    fresh.getLastInterestAccrualDate() != null
                        ? fresh.getLastInterestAccrualDate() : java.time.LocalDate.now(),
                    "RBI IRAC provisioning " + action + " for " + fresh.getAccountNumber(),
                    "PROVISIONING", fresh.getAccountNumber(),
                    lines
                );
            } catch (Exception e) {
                log.warn("Provisioning GL posting failed for {}: {}", fresh.getAccountNumber(), e.getMessage());
            }

            log.info("Provisioning {}: accNo={}, old={}, new={}, delta={}, status={}",
                action, fresh.getAccountNumber(), currentProvisioning, newProvisioning,
                delta, fresh.getStatus());
        }
    }

    /**
     * CBS EOD Step: GL Reconciliation (Subledger vs GL comparison).
     * Per Finacle/Temenos and RBI audit requirements:
     * 1. Trial Balance: Sum(GL debits) must equal Sum(GL credits)
     * 2. Per-GL: GL balance must match sum of all journal postings to that GL
     *
     * If imbalanced: logs WARNING but does NOT block EOD (to avoid locking the business day).
     * The reconciliation report is available at /reconciliation/report for manual review.
     * Day Close validation (BusinessDateService.closeDay) will block if GL is imbalanced.
     */
    protected void validateGlBalance(LocalDate businessDate) {
        try {
            reconciliationService.validateForDayClose(businessDate);
            log.info("GL Reconciliation PASSED for business date {}", businessDate);
        } catch (Exception e) {
            // Log but don't block EOD — day close will enforce the check
            log.warn("GL Reconciliation WARNING for {}: {}", businessDate, e.getMessage());
        }
    }

    /**
     * DPD calculation per RBI Early Warning Framework.
     * Re-loads account by ID within this transaction to ensure fresh @Version
     * and avoid OptimisticLockException from stale entities in the batch list.
     */
    @Transactional
    protected void updateDaysPastDue(LoanAccount account, LocalDate businessDate) {
        LoanAccount fresh = loanAccountRepository.findById(account.getId())
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found during DPD update: " + account.getAccountNumber()));

        if (fresh.getStatus().isTerminal()) {
            return;
        }

        LocalDate lastPayment = fresh.getLastPaymentDate();
        LocalDate nextEmi = fresh.getNextEmiDate();

        if (nextEmi != null && businessDate.isAfter(nextEmi) && (lastPayment == null || lastPayment.isBefore(nextEmi))) {
            int dpd = (int) ChronoUnit.DAYS.between(nextEmi, businessDate);
            fresh.setDaysPastDue(dpd);
            fresh.setOverduePrincipal(fresh.getEmiAmount() != null ? fresh.getEmiAmount() : fresh.getOutstandingPrincipal());
            fresh.setUpdatedBy("SYSTEM");
            loanAccountRepository.save(fresh);
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

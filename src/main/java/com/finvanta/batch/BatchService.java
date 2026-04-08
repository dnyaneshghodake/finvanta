package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.accounting.AccountingReconciliationEngine;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BatchJob;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.BatchStatus;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.rules.ProvisioningRule;
import com.finvanta.repository.BatchJobRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanScheduleService;
import com.finvanta.service.TransactionBatchService;
import com.finvanta.service.impl.StandingInstructionServiceImpl;
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
    private final ProvisioningRule provisioningRule;
    private final AuditService auditService;
    private final AccountingReconciliationEngine reconciliationService;
    private final TransactionEngine transactionEngine;
    private final LoanScheduleService scheduleService;
    private final TransactionBatchService transactionBatchService;
    private final ProductGLResolver glResolver;
    private final StandingInstructionServiceImpl standingInstructionService;

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
                        ProvisioningRule provisioningRule,
                        AuditService auditService,
                        AccountingReconciliationEngine reconciliationService,
                        TransactionEngine transactionEngine,
                        LoanScheduleService scheduleService,
                        TransactionBatchService transactionBatchService,
                        ProductGLResolver glResolver,
                        StandingInstructionServiceImpl standingInstructionService) {
        this.batchJobRepository = batchJobRepository;
        this.calendarRepository = calendarRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanAccountService = loanAccountService;
        this.provisioningRule = provisioningRule;
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
        this.transactionEngine = transactionEngine;
        this.scheduleService = scheduleService;
        this.transactionBatchService = transactionBatchService;
        this.glResolver = glResolver;
        this.standingInstructionService = standingInstructionService;
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

        // Step 0: Validate all intra-day transaction batches are closed.
        // Per Finacle/Temenos, EOD MUST NOT start if any batch is still OPEN.
        // This is a hard prerequisite — not a soft warning.
        // validateAllBatchesClosed() only throws when openCount > 0 (batches still OPEN).
        // When no batches exist for the date, openCount == 0 and no exception is thrown,
        // so EOD proceeds normally for dates with no intra-day batch activity.
        transactionBatchService.validateAllBatchesClosed(businessDate);

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

            // Step 3b: Run penal interest accrual on overdue accounts
            // Per RBI Fair Lending Code 2023, penal interest is charged on overdue principal.
            // Must run after regular interest accrual but before DPD recalculation.
            self.updateBatchStep(eodJob, "PENAL_INTEREST");

            List<LoanAccount> overdueAccounts = loanAccountRepository.findNpaCandidates(tenantId, 1);
            for (LoanAccount account : overdueAccounts) {
                try {
                    loanAccountService.applyPenalInterest(account.getAccountNumber(), businessDate);
                } catch (Exception e) {
                    errorLog.append("Penal interest failed for ")
                        .append(account.getAccountNumber()).append(": ")
                        .append(e.getMessage()).append("\n");
                    log.error("Penal interest failed: accNo={}", account.getAccountNumber(), e);
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

            // Step 4b: Mark overdue schedule installments
            // Per RBI IRAC, installments past due date that are unpaid become OVERDUE.
            try {
                int overdueMarked = scheduleService.markOverdueInstallments(businessDate);
                log.info("Schedule overdue marking: {} installments marked for date={}", overdueMarked, businessDate);
            } catch (Exception e) {
                errorLog.append("Schedule overdue marking failed: ").append(e.getMessage()).append("\n");
                log.error("Schedule overdue marking failed: date={}", businessDate, e);
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
                    self.calculateProvisioning(account, businessDate);
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

            // Step 7.5: Standing Instruction Execution (per Finacle SI_MASTER)
            // Executes all due SIs: LOAN_EMI auto-debit, recurring transfers, etc.
            // Per Finacle EOD: SI execution runs AFTER interest accrual and NPA classification
            // (so interest is current and loan status is up-to-date before EMI split).
            // Each SI runs in its own REQUIRES_NEW transaction for isolation.
            // CASA debit + loan repayment are ATOMIC within each SI transaction.
            self.updateBatchStep(eodJob, "STANDING_INSTRUCTION_EXECUTION");
            try {
                int[] siResult = standingInstructionService.executeAllDueSIs(businessDate);
                processedRecords += siResult[0];
                if (siResult[1] > 0) {
                    errorLog.append("SI execution: ").append(siResult[1]).append(" failed\n");
                }
                log.info("EOD Step 7.5: SI execution — executed={}, failed={}",
                    siResult[0], siResult[1]);
            } catch (Exception e) {
                errorLog.append("SI execution failed: ").append(e.getMessage()).append("\n");
                log.error("SI execution step failed: date={}", businessDate, e);
            }

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
     *
     * Per Finacle/Temenos Day Control lifecycle:
     *   NOT_OPENED -> DAY_OPEN -> EOD_RUNNING -> DAY_CLOSED
     *
     * EOD can ONLY start from DAY_OPEN status. This prevents:
     * - Running EOD on a day that was never opened (NOT_OPENED)
     * - Running EOD on a day that is already closed (DAY_CLOSED)
     * - Running EOD while another EOD is in progress (EOD_RUNNING)
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

        // CBS Day Control: EOD can only start from DAY_OPEN status.
        // Per Finacle DAYCTRL / Temenos COB: the day must be explicitly opened
        // by ADMIN before any financial operations (including EOD) can proceed.
        if (!calendar.getDayStatus().canStartEod()) {
            throw new BusinessException("DAY_NOT_OPEN",
                "Cannot run EOD for " + businessDate
                    + ". Day status is " + calendar.getDayStatus()
                    + ". The day must be opened first via Business Calendar.");
        }

        calendar.setLocked(true);
        calendar.setDayStatus(DayStatus.EOD_RUNNING);
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

    /**
     * Completes the EOD batch and transitions calendar to DAY_CLOSED.
     *
     * Per Finacle/Temenos Day Control lifecycle:
     *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
     *
     * After EOD completes, the day must be closed automatically. If we leave the
     * calendar in EOD_RUNNING, BusinessDateService.getCurrentBusinessDate() will
     * fail (it looks for DAY_OPEN), and no subsequent operations can proceed.
     *
     * In Finacle, EOD completion automatically triggers day close. The separate
     * closeDay() in BusinessDateService exists for manual day close scenarios
     * (e.g., when EOD is run but day close is deferred for operational reasons).
     * For standard CBS flow, EOD completion = day close.
     */
    @Transactional
    protected void completeEodBatch(BatchJob eodJob, BusinessCalendar calendar,
                                     BatchStatus status, int totalRecords,
                                     int processedRecords, int failedRecords,
                                     String errorMessage) {
        BusinessCalendar freshCal = calendarRepository.findById(calendar.getId()).orElse(calendar);
        freshCal.setEodComplete(true);
        freshCal.setDayStatus(DayStatus.DAY_CLOSED);
        freshCal.setDayClosedBy(SecurityUtil.getCurrentUsername());
        freshCal.setDayClosedAt(LocalDateTime.now());
        freshCal.setLocked(false);
        freshCal.setUpdatedBy(SecurityUtil.getCurrentUsername());
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
        freshCal.setDayStatus(DayStatus.DAY_OPEN);
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
    protected void calculateProvisioning(LoanAccount account, LocalDate businessDate) {
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

            // CBS: ALL financial postings go through TransactionEngine with systemGenerated(true).
            String productType = fresh.getProductType();
            String action = delta.compareTo(BigDecimal.ZERO) > 0 ? "charge" : "release";
            try {
                List<JournalLineRequest> lines = List.of(
                    new JournalLineRequest(glResolver.getProvisionExpenseGL(productType), expenseSide, absDelta,
                        "Provisioning " + action + " - " + fresh.getAccountNumber()),
                    new JournalLineRequest(glResolver.getProvisionNpaGL(productType), provisionSide, absDelta,
                        "Loan loss provision - " + fresh.getAccountNumber())
                );
                transactionEngine.execute(
                    TransactionRequest.builder()
                        .sourceModule("PROVISIONING")
                        .transactionType("PROVISIONING_" + action.toUpperCase())
                        .accountReference(fresh.getAccountNumber())
                        .amount(absDelta)
                        .valueDate(businessDate)
                        .branchCode(fresh.getBranch() != null ? fresh.getBranch().getBranchCode() : null)
                        .productType(productType)
                        .narration("RBI IRAC provisioning " + action + " for " + fresh.getAccountNumber())
                        .journalLines(lines)
                        .systemGenerated(true)
                        .initiatedBy("SYSTEM")
                        .build()
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

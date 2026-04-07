package com.finvanta.batch;

import com.finvanta.domain.entity.BatchJob;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanSchedule;
import com.finvanta.domain.enums.BatchStatus;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.repository.BatchJobRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanScheduleService;
import com.finvanta.audit.AuditService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CBS End-of-Day Batch Orchestrator (Phase 2) per Finacle EOD / Temenos COB.
 *
 * <b>IMPORTANT: This is the Phase 2 EOD orchestrator with inter-branch settlement
 * and clearing validation. The currently active EOD entry point used by
 * {@link com.finvanta.controller.BatchController} is {@link BatchService#runEodBatch(java.time.LocalDate)}.
 * Do NOT wire both into the same controller/scheduler.</b>
 *
 * <p>When Phase 2 migration is complete (inter-branch and clearing modules validated),
 * switch {@code BatchController} to call {@code EodOrchestrator.executeEod()} and
 * deprecate {@code BatchService.runEodBatch()}.
 *
 * <p>Differences from BatchService:
 * <ul>
 *   <li>Adds Step 7.5: Inter-Branch Settlement (per Finacle IB_SETTLEMENT)</li>
 *   <li>Adds Step 7.6: Clearing Suspense Validation (per Finacle CLG_MASTER)</li>
 *   <li>Uses dedicated ProvisioningService (vs inline ProvisioningRule in BatchService)</li>
 *   <li>Schedule-based DPD calculation (vs EMI-date-based in BatchService)</li>
 *   <li>Does NOT auto-close the day (leaves in EOD_RUNNING for manual Day Close)</li>
 * </ul>
 *
 * Uses self-proxy pattern (like BatchService) so that per-account methods
 * get their own transaction boundaries via Spring AOP.
 *
 * EOD Step Sequence:
 *   Step 1: Mark Overdue Installments
 *   Step 2: Update Account DPD (from oldest overdue installment)
 *   Step 3: Interest Accrual (daily on performing accounts)
 *   Step 4: Penal Interest Accrual (on overdue accounts)
 *   Step 5: NPA Classification (DPD-based per RBI IRAC)
 *   Step 6: Provisioning (RBI IRAC percentages)
 *   Step 7: GL Reconciliation (ledger vs GL master)
 *   Step 7.5: Inter-Branch Settlement (per Finacle IB_SETTLEMENT)
 *   Step 7.6: Clearing Suspense Validation (per Finacle CLG_MASTER)
 *   Step 8: CASA Savings Interest Accrual + Quarterly Credit (per RBI directive)
 *   Step 9: CASA Dormancy Classification (per RBI Master Direction on KYC 2016 Sec 38)
 *
 * Day status: DAY_OPEN -> EOD_RUNNING -> (eodComplete=true)
 * Day close is a separate admin action after EOD completes.
 */
@Service
public class EodOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EodOrchestrator.class);

    /** Configurable thread pool size for parallel account processing in Steps 2-5. */
    @Value("${eod.parallel.threads:4}")
    private int parallelThreads;

    private final LoanAccountService loanAccountService;
    private final LoanAccountRepository accountRepository;
    private final LoanScheduleService scheduleService;
    private final ProvisioningService provisioningService;
    private final ReconciliationService reconciliationService;
    private final InterBranchSettlementService settlementService;
    private final ClearingService clearingService;
    private final BusinessCalendarRepository calendarRepository;
    private final BatchJobRepository batchJobRepository;
    private final AuditService auditService;
    private final com.finvanta.service.DepositAccountService depositAccountService;
    private final com.finvanta.repository.DepositAccountRepository depositAccountRepository;

    /** Self-proxy for per-account transaction isolation via Spring AOP. */
    private final EodOrchestrator self;

    public EodOrchestrator(LoanAccountService loanAccountService,
                           LoanAccountRepository accountRepository,
                           LoanScheduleService scheduleService,
                           ProvisioningService provisioningService,
                           ReconciliationService reconciliationService,
                           InterBranchSettlementService settlementService,
                           ClearingService clearingService,
                           BusinessCalendarRepository calendarRepository,
                           BatchJobRepository batchJobRepository,
                           AuditService auditService,
                           com.finvanta.service.DepositAccountService depositAccountService,
                           com.finvanta.repository.DepositAccountRepository depositAccountRepository,
                           @Lazy EodOrchestrator self) {
        this.loanAccountService = loanAccountService;
        this.accountRepository = accountRepository;
        this.scheduleService = scheduleService;
        this.provisioningService = provisioningService;
        this.reconciliationService = reconciliationService;
        this.settlementService = settlementService;
        this.clearingService = clearingService;
        this.calendarRepository = calendarRepository;
        this.batchJobRepository = batchJobRepository;
        this.auditService = auditService;
        this.depositAccountService = depositAccountService;
        this.depositAccountRepository = depositAccountRepository;
        this.self = self;
    }

    /**
     * Main EOD entry point — NOT @Transactional so that per-account operations
     * get their own transaction boundaries via self-proxy calls through Spring AOP.
     * Setup/teardown steps use REQUIRES_NEW to commit independently.
     */
    public BatchJob executeEod(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        BatchJob eodJob = self.initializeEod(tenantId, businessDate);
        log.info("EOD started: date={}, tenant={}", businessDate, tenantId);

        int processedCount = 0;
        int failedCount = 0;
        StringBuilder errors = new StringBuilder();

        try {
        List<LoanAccount> activeAccounts = accountRepository.findAllActiveAccounts(tenantId);
        self.updateJobTotalRecords(eodJob.getId(), activeAccounts.size());

        // Step 1: Mark Overdue Installments
        failedCount += runStep(eodJob, "MARK_OVERDUE", () -> {
            int marked = scheduleService.markOverdueInstallments(businessDate);
            log.info("EOD Step 1: {} installments marked overdue", marked);
        }, errors);

        // CBS Parallel EOD: Steps 2-5 process accounts in parallel using a configurable
        // thread pool (eod.parallel.threads, default 4). Each account has its own
        // REQUIRES_NEW transaction and pessimistic lock, so cross-thread conflicts
        // are prevented. Per Finacle EOD / Temenos COB parallel batch standards.
        ExecutorService eodExecutor = Executors.newFixedThreadPool(parallelThreads);
        String currentTenant = TenantContext.getCurrentTenant();
        try {

        // Step 2: Update Account DPD (parallel)
        self.updateStepName(eodJob.getId(), "UPDATE_DPD");
        int[] step2Result = processAccountsParallel(activeAccounts, eodExecutor, currentTenant,
            "DPD", account -> self.updateAccountDpd(account.getAccountNumber(), businessDate), errors);
        failedCount += step2Result[1];
        log.info("EOD Step 2: DPD updated ({} threads)", parallelThreads);

        // Step 3: Interest Accrual (parallel)
        self.updateStepName(eodJob.getId(), "INTEREST_ACCRUAL");
        int[] step3Result = processAccountsParallel(activeAccounts, eodExecutor, currentTenant,
            "Accrual", account -> loanAccountService.applyInterestAccrual(
                account.getAccountNumber(), businessDate), errors);
        processedCount += step3Result[0];
        failedCount += step3Result[1];
        log.info("EOD Step 3: interest accrued for {} accounts ({} threads)", step3Result[0], parallelThreads);

        // Step 4: Penal Interest Accrual (parallel)
        // CBS: Do NOT guard on in-memory DPD -- applyPenalInterest() re-fetches with
        // pessimistic lock and has its own DPD > 0 guard.
        self.updateStepName(eodJob.getId(), "PENAL_ACCRUAL");
        int[] step4Result = processAccountsParallel(activeAccounts, eodExecutor, currentTenant,
            "Penal", account -> loanAccountService.applyPenalInterest(
                account.getAccountNumber(), businessDate), errors);
        failedCount += step4Result[1];
        log.info("EOD Step 4: penal interest done ({} threads)", parallelThreads);

        // Step 5: NPA Classification (parallel)
        self.updateStepName(eodJob.getId(), "NPA_CLASSIFICATION");
        int[] step5Result = processAccountsParallel(activeAccounts, eodExecutor, currentTenant,
            "NPA", account -> loanAccountService.classifyNPA(
                account.getAccountNumber(), businessDate), errors);
        failedCount += step5Result[1];
        log.info("EOD Step 5: NPA classification done ({} threads)", parallelThreads);

        } finally {
            eodExecutor.shutdown();
        }

        // Step 6: Provisioning
        failedCount += runStep(eodJob, "PROVISIONING", () -> {
            provisioningService.calculateAndPostProvisioning(businessDate);
            log.info("EOD Step 6: provisioning done");
        }, errors);

        // Step 7: GL Reconciliation
        failedCount += runStep(eodJob, "RECONCILIATION", () -> {
            ReconciliationService.ReconciliationResult result =
                reconciliationService.reconcileLedgerVsGL();
            if (!result.isBalanced()) {
                log.warn("EOD Step 7: {} GL discrepancies", result.discrepancyCount());
            } else {
                log.info("EOD Step 7: GL reconciliation balanced");
            }
        }, errors);

        // Step 7.5: Inter-Branch Settlement (per Finacle IB_SETTLEMENT)
        failedCount += runStep(eodJob, "INTER_BRANCH_SETTLEMENT", () -> {
            settlementService.settleInterBranch(businessDate);
            log.info("EOD Step 7.5: inter-branch settlement validated");
        }, errors);

        // Step 7.6: Clearing Suspense Validation (per Finacle CLG_MASTER)
        failedCount += runStep(eodJob, "CLEARING_SUSPENSE", () -> {
            clearingService.validateSuspenseBalance(businessDate);
            log.info("EOD Step 7.6: clearing suspense validated");
        }, errors);

        // === CASA EOD Steps (per Finacle CUSTACCT / RBI Savings Interest Directive) ===

        // Step 8: CASA Savings Interest Accrual (daily product method)
        // Formula: closingBalance * rate / 36500 per RBI directive
        // CBS: Individual account failures are tracked and reported in the error log,
        // not silently swallowed. Per Finacle EOD / Temenos COB: every failure must
        // be visible in the batch job record for operational review.
        self.updateStepName(eodJob.getId(), "CASA_INTEREST_ACCRUAL");
        {
            var savingsAccounts = depositAccountRepository.findActiveSavingsAccounts(tenantId);
            int accrued = 0;
            for (var depAcct : savingsAccounts) {
                try {
                    depositAccountService.accrueInterest(depAcct.getAccountNumber(), businessDate);
                    accrued++;
                } catch (Exception e) {
                    failedCount++;
                    appendError(errors, "CASA_ACCRUAL", depAcct.getAccountNumber(), e);
                }
            }
            // Quarterly credit on quarter-end dates (Mar 31, Jun 30, Sep 30, Dec 31)
            int month = businessDate.getMonthValue();
            int day = businessDate.getDayOfMonth();
            boolean isQuarterEnd = (month == 3 && day == 31) || (month == 6 && day == 30)
                || (month == 9 && day == 30) || (month == 12 && day == 31);
            if (isQuarterEnd) {
                int credited = 0;
                for (var depAcct : savingsAccounts) {
                    try {
                        depositAccountService.creditInterest(depAcct.getAccountNumber(), businessDate);
                        credited++;
                    } catch (Exception e) {
                        failedCount++;
                        appendError(errors, "CASA_CREDIT", depAcct.getAccountNumber(), e);
                    }
                }
                log.info("EOD Step 8: CASA quarterly interest credited for {} accounts", credited);
            }
            log.info("EOD Step 8: CASA interest accrued for {} savings accounts", accrued);
        }

        // Step 9: CASA Dormancy Classification (RBI Master Direction on KYC 2016 Sec 38)
        // Accounts with no customer-initiated txn for 24+ months -> DORMANT
        failedCount += runStep(eodJob, "CASA_DORMANCY", () -> {
            int dormantCount = depositAccountService.markDormantAccounts(businessDate);
            log.info("EOD Step 9: {} CASA accounts marked DORMANT", dormantCount);
        }, errors);

        } catch (Exception e) {
            // CBS: Top-level error handler — prevents calendar stuck in EOD_RUNNING.
            // Per Finacle DAYCTRL / Temenos COB: EOD failure must be recorded and
            // the batch job marked FAILED so that operations can investigate and retry.
            log.error("EOD FATAL: Unrecoverable error during EOD for {}: {}", businessDate, e.getMessage(), e);
            errors.append("FATAL: ").append(e.getMessage()).append("\n");
            failedCount++;
        }

        return self.finalizeEod(eodJob.getId(), tenantId, businessDate,
            processedCount, failedCount, errors);
    }

    /** Initialize EOD: validate day, set EOD_RUNNING, create batch job. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchJob initializeEod(String tenantId, LocalDate businessDate) {
        BusinessCalendar calendar = validateAndLockDay(tenantId, businessDate);

        calendar.setDayStatus(DayStatus.EOD_RUNNING);
        calendar.setLocked(true);
        calendar.setUpdatedBy("SYSTEM");
        calendarRepository.save(calendar);

        return createEodJob(tenantId, businessDate);
    }

    /** Update batch job total records count. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateJobTotalRecords(Long jobId, int totalRecords) {
        BatchJob job = batchJobRepository.findById(jobId).orElseThrow();
        job.setTotalRecords(totalRecords);
        batchJobRepository.save(job);
    }

    /** Update batch job step name for progress tracking. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStepName(Long jobId, String stepName) {
        BatchJob job = batchJobRepository.findById(jobId).orElseThrow();
        job.setStepName(stepName);
        batchJobRepository.save(job);
    }

    private BusinessCalendar validateAndLockDay(String tenantId, LocalDate businessDate) {
        BusinessCalendar calendar = calendarRepository
            .findAndLockByTenantIdAndDate(tenantId, businessDate)
            .orElseThrow(() -> new BusinessException("DATE_NOT_IN_CALENDAR",
                "Business date " + businessDate + " not found in calendar."));

        if (!calendar.getDayStatus().canStartEod()) {
            throw new BusinessException("EOD_NOT_ALLOWED",
                "Cannot start EOD for " + businessDate
                    + ". Day status: " + calendar.getDayStatus());
        }
        if (calendar.isEodComplete()) {
            throw new BusinessException("EOD_ALREADY_COMPLETE",
                "EOD already completed for " + businessDate);
        }
        batchJobRepository.findByTenantIdAndJobNameAndBusinessDate(
            tenantId, "EOD", businessDate).ifPresent(existing -> {
                if (existing.getStatus() == BatchStatus.RUNNING) {
                    throw new BusinessException("EOD_ALREADY_RUNNING",
                        "EOD is already running for " + businessDate);
                }
                if (existing.getStatus() == BatchStatus.COMPLETED) {
                    throw new BusinessException("EOD_ALREADY_COMPLETE",
                        "EOD already completed for " + businessDate);
                }
            });
        return calendar;
    }

    private BatchJob createEodJob(String tenantId, LocalDate businessDate) {
        BatchJob eodJob = new BatchJob();
        eodJob.setTenantId(tenantId);
        eodJob.setJobName("EOD");
        eodJob.setBusinessDate(businessDate);
        eodJob.setStatus(BatchStatus.RUNNING);
        eodJob.setStartedAt(LocalDateTime.now());
        eodJob.setInitiatedBy("SYSTEM");
        eodJob.setCreatedBy("SYSTEM");
        return batchJobRepository.save(eodJob);
    }

    private int runStep(BatchJob eodJob, String stepName,
                        Runnable step, StringBuilder errors) {
        self.updateStepName(eodJob.getId(), stepName);
        try {
            step.run();
            return 0;
        } catch (Exception e) {
            log.error("EOD step {} failed: {}", stepName, e.getMessage(), e);
            errors.append("Step ").append(stepName).append(": ")
                .append(e.getMessage()).append("\n");
            return 1;
        }
    }

    /**
     * Update DPD for a single account in its own transaction.
     * Called via self-proxy to get REQUIRES_NEW transaction boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAccountDpd(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        LoanAccount account = accountRepository.findByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        List<LoanSchedule> overdueList = scheduleService
            .getOverdueInstallments(account.getId(), businessDate);

        if (overdueList.isEmpty()) {
            if (account.getDaysPastDue() > 0) {
                account.setDaysPastDue(0);
                account.setOverduePrincipal(BigDecimal.ZERO);
                account.setOverdueInterest(BigDecimal.ZERO);
                account.setUpdatedBy("SYSTEM");
                accountRepository.save(account);
            }
            return;
        }

        LoanSchedule oldest = overdueList.get(0);
        int dpd = (int) ChronoUnit.DAYS.between(oldest.getDueDate(), businessDate);
        if (dpd < 0) dpd = 0;

        BigDecimal overduePrincipal = BigDecimal.ZERO;
        BigDecimal overdueInterest = BigDecimal.ZERO;
        for (LoanSchedule inst : overdueList) {
            overduePrincipal = overduePrincipal.add(
                inst.getPrincipalAmount().subtract(inst.getPaidPrincipal()));
            overdueInterest = overdueInterest.add(
                inst.getInterestAmount().subtract(inst.getPaidInterest()));
        }

        account.setDaysPastDue(dpd);
        account.setOverduePrincipal(overduePrincipal.max(BigDecimal.ZERO));
        account.setOverdueInterest(overdueInterest.max(BigDecimal.ZERO));
        account.setUpdatedBy("SYSTEM");
        accountRepository.save(account);
    }

    /** Finalize EOD: update batch job status, mark calendar eodComplete. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchJob finalizeEod(Long jobId, String tenantId,
                                LocalDate businessDate,
                                int processedCount, int failedCount,
                                StringBuilder errors) {
        BatchJob eodJob = batchJobRepository.findById(jobId).orElseThrow();
        eodJob.setStepName("COMPLETE");
        eodJob.setProcessedRecords(processedCount);
        eodJob.setFailedRecords(failedCount);
        eodJob.setCompletedAt(LocalDateTime.now());
        eodJob.setUpdatedBy("SYSTEM");
        if (errors.length() > 0) {
            eodJob.setErrorMessage(errors.toString());
        }
        if (failedCount == 0) {
            eodJob.setStatus(BatchStatus.COMPLETED);
        } else if (processedCount > 0) {
            eodJob.setStatus(BatchStatus.PARTIALLY_COMPLETED);
        } else {
            eodJob.setStatus(BatchStatus.FAILED);
        }
        batchJobRepository.save(eodJob);

        BusinessCalendar calendar = calendarRepository
            .findAndLockByTenantIdAndDate(tenantId, businessDate).orElseThrow();
        calendar.setEodComplete(true);
        calendar.setUpdatedBy("SYSTEM");
        calendarRepository.save(calendar);

        auditService.logEvent("BatchJob", eodJob.getId(),
            "EOD_COMPLETE", null, eodJob.getStatus().name(), "EOD",
            "EOD completed: date=" + businessDate
                + ", processed=" + processedCount
                + ", failed=" + failedCount
                + ", status=" + eodJob.getStatus());

        log.info("EOD completed: date={}, processed={}, failed={}, status={}",
            businessDate, processedCount, failedCount, eodJob.getStatus());

        return eodJob;
    }

    /**
     * CBS Parallel Account Processing per Finacle EOD / Temenos COB batch standards.
     *
     * Processes a list of accounts in parallel using the provided ExecutorService.
     * Each account is processed independently with its own try/catch for error isolation.
     * The TenantContext is propagated to each thread (ThreadLocal requires explicit set).
     * Progress is logged every 1000 accounts.
     *
     * @param accounts  List of accounts to process
     * @param executor  Thread pool for parallel execution
     * @param tenantId  Tenant ID to propagate to worker threads
     * @param stepName  Step name for error logging
     * @param action    Per-account action (lambda)
     * @param errors    Shared error log (synchronized append)
     * @return int[2]: [0]=processed count, [1]=failed count
     */
    private int[] processAccountsParallel(List<LoanAccount> accounts,
                                           ExecutorService executor,
                                           String tenantId,
                                           String stepName,
                                           java.util.function.Consumer<LoanAccount> action,
                                           StringBuilder errors) {
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        int total = accounts.size();

        CompletableFuture<?>[] futures = accounts.stream()
            .map(account -> CompletableFuture.runAsync(() -> {
                // CBS: Propagate tenant context to worker thread (ThreadLocal)
                TenantContext.setCurrentTenant(tenantId);
                try {
                    action.accept(account);
                    int done = processed.incrementAndGet();
                    if (done % 1000 == 0) {
                        log.info("EOD {} progress: {}/{} accounts processed",
                            stepName, done, total);
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    synchronized (errors) {
                        appendError(errors, stepName, account.getAccountNumber(), e);
                    }
                } finally {
                    TenantContext.clear();
                }
            }, executor))
            .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
        return new int[]{processed.get(), failed.get()};
    }

    private void appendError(StringBuilder errors, String step,
                              String accountNumber, Exception e) {
        log.error("EOD {} failed for {}: {}", step, accountNumber, e.getMessage());
        errors.append(step).append(" ").append(accountNumber)
            .append(": ").append(e.getMessage()).append("\n");
    }
}

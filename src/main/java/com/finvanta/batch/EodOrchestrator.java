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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * CBS End-of-Day Batch Orchestrator per Finacle EOD / Temenos COB.
 *
 * Executes the nightly batch cycle in the correct step order with per-account
 * error isolation, step-level tracking, and full audit trail.
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
 *
 * Day status: DAY_OPEN -> EOD_RUNNING -> (eodComplete=true)
 * Day close is a separate admin action after EOD completes.
 */
@Service
public class EodOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EodOrchestrator.class);

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
        this.self = self;
    }

    @Transactional
    public BatchJob executeEod(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        BusinessCalendar calendar = validateAndLockDay(tenantId, businessDate);

        calendar.setDayStatus(DayStatus.EOD_RUNNING);
        calendar.setLocked(true);
        calendar.setUpdatedBy("SYSTEM");
        calendarRepository.save(calendar);

        BatchJob eodJob = createEodJob(tenantId, businessDate);
        log.info("EOD started: date={}, tenant={}", businessDate, tenantId);

        List<LoanAccount> activeAccounts = accountRepository.findAllActiveAccounts(tenantId);
        eodJob.setTotalRecords(activeAccounts.size());

        int processedCount = 0;
        int failedCount = 0;
        StringBuilder errors = new StringBuilder();

        // Step 1: Mark Overdue Installments
        failedCount += runStep(eodJob, "MARK_OVERDUE", () -> {
            int marked = scheduleService.markOverdueInstallments(businessDate);
            log.info("EOD Step 1: {} installments marked overdue", marked);
        }, errors);

        // Step 2: Update Account DPD
        eodJob.setStepName("UPDATE_DPD");
        batchJobRepository.save(eodJob);
        for (LoanAccount account : activeAccounts) {
            try {
                updateAccountDpd(account, businessDate);
            } catch (Exception e) {
                failedCount++;
                appendError(errors, "DPD", account.getAccountNumber(), e);
            }
        }
        log.info("EOD Step 2: DPD updated");

        // Step 3: Interest Accrual
        eodJob.setStepName("INTEREST_ACCRUAL");
        batchJobRepository.save(eodJob);
        for (LoanAccount account : activeAccounts) {
            try {
                loanAccountService.applyInterestAccrual(
                    account.getAccountNumber(), businessDate);
                processedCount++;
            } catch (Exception e) {
                failedCount++;
                appendError(errors, "Accrual", account.getAccountNumber(), e);
            }
        }
        log.info("EOD Step 3: interest accrued for {} accounts", processedCount);

        // Step 4: Penal Interest Accrual
        // CBS: Do NOT guard on in-memory DPD — the activeAccounts list was loaded at Step 0
        // and Step 2 (DPD update) may have changed DPD in the DB without refreshing the
        // in-memory objects. applyPenalInterest() re-fetches the account with a pessimistic
        // lock and has its own DPD > 0 guard, so it safely no-ops for non-overdue accounts.
        eodJob.setStepName("PENAL_ACCRUAL");
        batchJobRepository.save(eodJob);
        for (LoanAccount account : activeAccounts) {
            try {
                loanAccountService.applyPenalInterest(
                    account.getAccountNumber(), businessDate);
            } catch (Exception e) {
                failedCount++;
                appendError(errors, "Penal", account.getAccountNumber(), e);
            }
        }
        log.info("EOD Step 4: penal interest done");

        // Step 5: NPA Classification
        eodJob.setStepName("NPA_CLASSIFICATION");
        batchJobRepository.save(eodJob);
        for (LoanAccount account : activeAccounts) {
            try {
                loanAccountService.classifyNPA(
                    account.getAccountNumber(), businessDate);
            } catch (Exception e) {
                failedCount++;
                appendError(errors, "NPA", account.getAccountNumber(), e);
            }
        }
        log.info("EOD Step 5: NPA classification done");

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

        return finalizeEod(eodJob, tenantId, businessDate,
            processedCount, failedCount, errors);
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
        eodJob.setStepName(stepName);
        batchJobRepository.save(eodJob);
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

    private void updateAccountDpd(LoanAccount account, LocalDate businessDate) {
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

    private BatchJob finalizeEod(BatchJob eodJob, String tenantId,
                                  LocalDate businessDate,
                                  int processedCount, int failedCount,
                                  StringBuilder errors) {
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

    private void appendError(StringBuilder errors, String step,
                              String accountNumber, Exception e) {
        log.error("EOD {} failed for {}: {}", step, accountNumber, e.getMessage());
        errors.append(step).append(" ").append(accountNumber)
            .append(": ").append(e.getMessage()).append("\n");
    }
}

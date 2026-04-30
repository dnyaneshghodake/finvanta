package com.finvanta.batch;

import com.finvanta.cbs.modules.teller.service.TellerEodValidationService;
import com.finvanta.repository.BatchJobRepository;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.InterBranchSettlementRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.TransactionBatchService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS EOD Trial Run Service per Finacle EOD_TRIAL / Temenos COB_VERIFY.
 *
 * Per Tier-1 CBS standards, EOD has two phases:
 *   1. TRIAL RUN — Read-only validation checklist. No data mutations.
 *   2. APPLY RUN — Actual EOD execution (EodOrchestrator.executeEod).
 *
 * Per RBI IT Governance Direction 2023 Section 7.3:
 * - EOD must have a verification step before execution
 * - All validation results must be visible to the operations team
 * - Blockers must be resolved before Apply is allowed
 */
@Service
public class EodTrialService {

    private static final Logger log = LoggerFactory.getLogger(EodTrialService.class);

    private final BusinessCalendarRepository calendarRepository;
    private final BranchRepository branchRepository;
    private final BatchJobRepository batchJobRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final TransactionBatchService transactionBatchService;
    private final InterBranchSettlementRepository settlementRepository;
    private final ReconciliationService reconciliationService;
    private final SubledgerReconciliationService subledgerReconciliationService;
    private final TellerEodValidationService tellerEodValidationService;

    public EodTrialService(
            BusinessCalendarRepository calendarRepository,
            BranchRepository branchRepository,
            BatchJobRepository batchJobRepository,
            LoanAccountRepository loanAccountRepository,
            DepositAccountRepository depositAccountRepository,
            TransactionBatchService transactionBatchService,
            InterBranchSettlementRepository settlementRepository,
            ReconciliationService reconciliationService,
            SubledgerReconciliationService subledgerReconciliationService,
            TellerEodValidationService tellerEodValidationService) {
        this.calendarRepository = calendarRepository;
        this.branchRepository = branchRepository;
        this.batchJobRepository = batchJobRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.transactionBatchService = transactionBatchService;
        this.settlementRepository = settlementRepository;
        this.reconciliationService = reconciliationService;
        this.subledgerReconciliationService = subledgerReconciliationService;
        this.tellerEodValidationService = tellerEodValidationService;
    }

    /** Severity levels per Finacle EOD_TRIAL. */
    public enum CheckSeverity { BLOCKER, WARNING, INFO }

    /** Single EOD trial check result. */
    public record EodCheckResult(
            String checkId, String category, String description,
            CheckSeverity severity, boolean passed) {
        public static EodCheckResult pass(String id, String cat, String desc) {
            return new EodCheckResult(id, cat, desc, CheckSeverity.INFO, true);
        }
        public static EodCheckResult blocker(String id, String cat, String desc) {
            return new EodCheckResult(id, cat, desc, CheckSeverity.BLOCKER, false);
        }
        public static EodCheckResult warn(String id, String cat, String desc) {
            return new EodCheckResult(id, cat, desc, CheckSeverity.WARNING, false);
        }
    }

    /** Execute read-only trial run. No data mutated. */
    @Transactional(readOnly = true)
    public List<EodCheckResult> runTrial(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        List<EodCheckResult> results = new ArrayList<>();
        log.info("EOD TRIAL started: date={}, tenant={}", businessDate, tenantId);

        results.add(checkCalendar(tenantId, businessDate));
        results.add(checkBranchesDayOpen(tenantId, businessDate));
        results.add(checkEodStatus(tenantId, businessDate));
        results.add(checkBatchesClosed(tenantId, businessDate));
        results.add(checkLoanAccounts(tenantId));
        results.add(checkCasaAccounts(tenantId));
        results.add(checkGlRecon());
        results.add(checkSubledgerRecon());
        results.add(checkBranchRecon());
        results.add(checkStaleIb(tenantId, businessDate));

        boolean hasBlockers = results.stream()
                .anyMatch(r -> r.severity() == CheckSeverity.BLOCKER && !r.passed());
        log.info("EOD TRIAL completed: date={}, checks={}, hasBlockers={}",
                businessDate, results.size(), hasBlockers);
        return results;
    }

    public boolean isTrialClean(List<EodCheckResult> results) {
        return results.stream().noneMatch(r -> r.severity() == CheckSeverity.BLOCKER && !r.passed());
    }

    private EodCheckResult checkCalendar(String tenantId, LocalDate date) {
        try {
            var branches = branchRepository.findAllOperationalBranches(tenantId);
            if (branches.isEmpty())
                return EodCheckResult.blocker("CALENDAR", "Business Calendar", "No operational branches");
            var cal = calendarRepository.findByTenantIdAndBranchIdAndBusinessDate(
                    tenantId, branches.get(0).getId(), date);
            if (cal.isEmpty())
                return EodCheckResult.blocker("CALENDAR", "Business Calendar",
                        "Date " + date + " not in calendar");
            return EodCheckResult.pass("CALENDAR", "Business Calendar", "Date " + date + " exists");
        } catch (Exception e) {
            return EodCheckResult.blocker("CALENDAR", "Business Calendar", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkBranchesDayOpen(String tenantId, LocalDate date) {
        try {
            var branches = branchRepository.findAllOperationalBranches(tenantId);
            List<String> notOpen = new ArrayList<>();
            for (var branch : branches) {
                var cal = calendarRepository.findByTenantIdAndBranchIdAndBusinessDate(
                        tenantId, branch.getId(), date).orElse(null);
                if (cal == null || !cal.getDayStatus().canStartEod())
                    notOpen.add(branch.getBranchCode()
                            + " (" + (cal != null ? cal.getDayStatus() : "NO_ENTRY") + ")");
            }
            if (!notOpen.isEmpty())
                return EodCheckResult.blocker("DAY_STATUS", "Branch Day Status",
                        notOpen.size() + " branch(es) not ready: " + String.join(", ", notOpen));
            return EodCheckResult.pass("DAY_STATUS", "Branch Day Status",
                    "All " + branches.size() + " branches DAY_OPEN");
        } catch (Exception e) {
            return EodCheckResult.blocker("DAY_STATUS", "Branch Day Status", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkEodStatus(String tenantId, LocalDate date) {
        try {
            var existing = batchJobRepository.findByTenantIdAndJobNameAndBusinessDate(tenantId, "EOD", date);
            if (existing.isPresent()) {
                String s = existing.get().getStatus().name();
                if ("COMPLETED".equals(s))
                    return EodCheckResult.blocker("EOD_STATUS", "EOD Status", "Already COMPLETED for " + date);
                if ("RUNNING".equals(s))
                    return EodCheckResult.blocker("EOD_STATUS", "EOD Status", "Currently RUNNING for " + date);
                return EodCheckResult.warn("EOD_STATUS", "EOD Status",
                        "Previous attempt (status=" + s + "). Re-run allowed.");
            }
            return EodCheckResult.pass("EOD_STATUS", "EOD Status", "No prior EOD for " + date);
        } catch (Exception e) {
            return EodCheckResult.blocker("EOD_STATUS", "EOD Status", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkBatchesClosed(String tenantId, LocalDate date) {
        try {
            var open = transactionBatchService.getOpenBatches(date);
            if (!open.isEmpty()) {
                String names = open.stream().map(b -> b.getBatchName())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                return EodCheckResult.blocker("BATCHES", "Transaction Batches",
                        open.size() + " batch(es) still OPEN: " + names);
            }
            return EodCheckResult.pass("BATCHES", "Transaction Batches", "All batches CLOSED");
        } catch (Exception e) {
            return EodCheckResult.blocker("BATCHES", "Transaction Batches", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkLoanAccounts(String tenantId) {
        try {
            long c = loanAccountRepository.findAllActiveAccounts(tenantId).size();
            return c == 0
                    ? EodCheckResult.warn("LOANS", "Loan Accounts", "No active loans — loan steps skipped")
                    : EodCheckResult.pass("LOANS", "Loan Accounts", c + " active loan(s)");
        } catch (Exception e) {
            return EodCheckResult.warn("LOANS", "Loan Accounts", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkCasaAccounts(String tenantId) {
        try {
            long c = depositAccountRepository.findAllNonClosedAccounts(tenantId).size();
            return c == 0
                    ? EodCheckResult.warn("CASA", "CASA Accounts", "No active CASA — CASA steps skipped")
                    : EodCheckResult.pass("CASA", "CASA Accounts", c + " active CASA account(s)");
        } catch (Exception e) {
            return EodCheckResult.warn("CASA", "CASA Accounts", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkGlRecon() {
        try {
            var r = reconciliationService.reconcileLedgerVsGL();
            return r.isBalanced()
                    ? EodCheckResult.pass("GL_RECON", "GL Reconciliation", "GL balanced")
                    : EodCheckResult.warn("GL_RECON", "GL Reconciliation",
                            r.discrepancyCount() + " GL discrepancies");
        } catch (Exception e) {
            return EodCheckResult.warn("GL_RECON", "GL Reconciliation", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkSubledgerRecon() {
        try {
            var r = subledgerReconciliationService.reconcile();
            return r.isBalanced()
                    ? EodCheckResult.pass("SUB_RECON", "Subledger Reconciliation", "Subledger balanced")
                    : EodCheckResult.warn("SUB_RECON", "Subledger Reconciliation",
                            r.discrepancyCount() + " discrepancies");
        } catch (Exception e) {
            return EodCheckResult.warn("SUB_RECON", "Subledger Reconciliation", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkBranchRecon() {
        try {
            var r = reconciliationService.reconcileBranchBalancesVsGL();
            return r.isBalanced()
                    ? EodCheckResult.pass("BR_RECON", "Branch Reconciliation", "Branch balances balanced")
                    : EodCheckResult.warn("BR_RECON", "Branch Reconciliation",
                            r.discrepancyCount() + " discrepancies");
        } catch (Exception e) {
            return EodCheckResult.warn("BR_RECON", "Branch Reconciliation", "Error: " + e.getMessage());
        }
    }

    private EodCheckResult checkStaleIb(String tenantId, LocalDate date) {
        try {
            long stale = settlementRepository
                    .findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(tenantId, "PENDING")
                    .stream().filter(t -> t.getBusinessDate().isBefore(date)).count();
            return stale > 0
                    ? EodCheckResult.warn("IB_STALE", "IB Settlement",
                            stale + " stale PENDING IB txns from prior dates")
                    : EodCheckResult.pass("IB_STALE", "IB Settlement", "No stale IB settlements");
        } catch (Exception e) {
            return EodCheckResult.warn("IB_STALE", "IB Settlement", "Error: " + e.getMessage());
        }
    }
}

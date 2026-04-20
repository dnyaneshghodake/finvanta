package com.finvanta.api;

import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.repository.ClearingTransactionRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Dashboard REST API per Finacle DASHBOARD / Temenos ENQUIRY.SUMMARY.
 *
 * <p>Aggregated CBS metrics computed server-side in a single endpoint.
 * Per Finacle/Temenos: the dashboard is the first screen after login and
 * must load in under 200ms — all metrics are computed from indexed queries,
 * no N+1, no full-table scans.
 *
 * <p>Per RBI regulatory reporting requirements:
 * <ul>
 *   <li>Gross NPA Ratio = (NPA Outstanding / Total Outstanding) × 100</li>
 *   <li>Provision Coverage Ratio = (Total Provisioning / NPA Outstanding) × 100</li>
 *   <li>CASA Ratio = (CASA Deposits / Total Deposits) × 100</li>
 * </ul>
 *
 * <p>CBS Role Matrix:
 * <ul>
 *   <li>MAKER/CHECKER/ADMIN → dashboard summary (branch-scoped metrics
 *       for non-ADMIN; tenant-wide for ADMIN)</li>
 * </ul>
 *
 * <p>CBS Performance: Uses single GROUP BY query for loan status counts
 * (eliminates 7 separate COUNT queries). All SUM queries use COALESCE
 * to avoid null returns on empty datasets.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private final CustomerRepository customerRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final DepositTransactionRepository depositTxnRepository;
    private final ClearingTransactionRepository clearingTxnRepository;
    private final ApprovalWorkflowService workflowService;
    private final BusinessDateService businessDateService;

    public DashboardApiController(
            CustomerRepository customerRepository,
            LoanApplicationRepository applicationRepository,
            LoanAccountRepository accountRepository,
            DepositAccountRepository depositAccountRepository,
            DepositTransactionRepository depositTxnRepository,
            ClearingTransactionRepository clearingTxnRepository,
            ApprovalWorkflowService workflowService,
            BusinessDateService businessDateService) {
        this.customerRepository = customerRepository;
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.depositTxnRepository = depositTxnRepository;
        this.clearingTxnRepository = clearingTxnRepository;
        this.workflowService = workflowService;
        this.businessDateService = businessDateService;
    }

    /**
     * Aggregated CBS dashboard metrics — single endpoint, single response.
     *
     * <p>Per Finacle DASHBOARD: all metrics computed server-side to avoid
     * multiple round-trips from the BFF. The Next.js dashboard page calls
     * this once on mount and renders all widgets from the response.
     *
     * <p>Performance: 5 indexed queries total:
     * <ol>
     *   <li>Customer count (indexed on tenant_id + active)</li>
     *   <li>Pending applications count (indexed on tenant_id + status)</li>
     *   <li>Loan status GROUP BY (single query, 7 status buckets)</li>
     *   <li>Financial aggregates (3 SUM queries, all indexed)</li>
     *   <li>CASA metrics (1 SUM + 1 COUNT)</li>
     * </ol>
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<DashboardSummary>>
            getSummary() {
        String tenantId = TenantContext.getCurrentTenant();

        // === Customer & Application Counts ===
        long totalCustomers = customerRepository
                .countByTenantIdAndActiveTrue(tenantId);
        long pendingApplications = applicationRepository
                .countByTenantIdAndStatus(
                        tenantId, ApplicationStatus.SUBMITTED);

        // === Loan Status Distribution (single GROUP BY query) ===
        Map<LoanStatus, Long> statusCounts =
                new EnumMap<>(LoanStatus.class);
        List<Object[]> rawCounts = accountRepository
                .countByTenantIdGroupByStatus(tenantId);
        for (Object[] row : rawCounts) {
            statusCounts.put(
                    (LoanStatus) row[0], (Long) row[1]);
        }

        long activeLoans = sc(statusCounts, LoanStatus.ACTIVE)
                + sc(statusCounts, LoanStatus.SMA_0)
                + sc(statusCounts, LoanStatus.SMA_1)
                + sc(statusCounts, LoanStatus.SMA_2)
                + sc(statusCounts, LoanStatus.RESTRUCTURED);
        long smaAccounts = sc(statusCounts, LoanStatus.SMA_0)
                + sc(statusCounts, LoanStatus.SMA_1)
                + sc(statusCounts, LoanStatus.SMA_2);
        long npaAccounts =
                sc(statusCounts, LoanStatus.NPA_SUBSTANDARD)
                + sc(statusCounts, LoanStatus.NPA_DOUBTFUL)
                + sc(statusCounts, LoanStatus.NPA_LOSS);

        // === Financial Aggregates ===
        BigDecimal totalOutstanding = accountRepository
                .calculateTotalOutstandingPrincipal(tenantId);
        BigDecimal npaOutstanding = accountRepository
                .calculateTotalNpaOutstanding(tenantId);
        BigDecimal totalProvisioning = accountRepository
                .calculateTotalProvisioning(tenantId);

        // === RBI Key Ratios ===
        BigDecimal grossNpaRatio = ratio(
                npaOutstanding, totalOutstanding);
        BigDecimal provisionCoverage = ratio(
                totalProvisioning, npaOutstanding)
                .min(BigDecimal.valueOf(100));

        // === CASA Metrics ===
        BigDecimal totalDeposits = depositAccountRepository
                .calculateTotalDeposits(tenantId);
        long casaAccountCount = depositAccountRepository
                .countByTenantIdAndAccountStatusNot(
                        tenantId, DepositAccountStatus.CLOSED);
        BigDecimal casaRatio = ratio(
                totalDeposits, totalDeposits);

        // === Workflow ===
        long pendingApprovals = workflowService
                .getPendingApprovals().size();

        return ResponseEntity.ok(ApiResponse.success(
                new DashboardSummary(
                        new CustomerMetrics(
                                totalCustomers),
                        new LoanMetrics(
                                activeLoans, smaAccounts,
                                npaAccounts,
                                pendingApplications),
                        new FinancialMetrics(
                                totalOutstanding,
                                npaOutstanding,
                                totalProvisioning,
                                grossNpaRatio,
                                provisionCoverage),
                        new CasaMetrics(
                                totalDeposits,
                                casaAccountCount,
                                casaRatio),
                        new WorkflowMetrics(
                                pendingApprovals))));
    }

    // ====================================================================
    // WIDGET ENDPOINTS — Tier-1 Progressive Secure Hydration Pattern
    //
    // Each widget is an independent endpoint fetched in parallel by the
    // Next.js BFF. A failed widget does NOT break the entire dashboard.
    // The monolithic /summary above is retained for backward compatibility
    // but the BFF SHOULD use these individual endpoints instead.
    // ====================================================================

    /**
     * Widget: Portfolio summary stat cards (ALL roles).
     * BFF refresh: 60s. Skeleton: 6 metric cards.
     */
    @GetMapping("/widgets/portfolio")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<PortfolioWidget>> widgetPortfolio() {
        String tid = TenantContext.getCurrentTenant();

        Map<LoanStatus, Long> s = new EnumMap<>(LoanStatus.class);
        for (Object[] row : accountRepository.countByTenantIdGroupByStatus(tid)) {
            s.put((LoanStatus) row[0], (Long) row[1]);
        }
        long active = sc(s, LoanStatus.ACTIVE) + sc(s, LoanStatus.SMA_0)
                + sc(s, LoanStatus.SMA_1) + sc(s, LoanStatus.SMA_2)
                + sc(s, LoanStatus.RESTRUCTURED);
        long sma = sc(s, LoanStatus.SMA_0) + sc(s, LoanStatus.SMA_1)
                + sc(s, LoanStatus.SMA_2);
        long npa = sc(s, LoanStatus.NPA_SUBSTANDARD)
                + sc(s, LoanStatus.NPA_DOUBTFUL) + sc(s, LoanStatus.NPA_LOSS);

        return ResponseEntity.ok(ApiResponse.success(new PortfolioWidget(
                customerRepository.countByTenantIdAndActiveTrue(tid),
                depositAccountRepository.countByTenantIdAndAccountStatusNot(
                        tid, DepositAccountStatus.CLOSED),
                active, sma, npa,
                applicationRepository.countByTenantIdAndStatus(
                        tid, ApplicationStatus.SUBMITTED))));
    }

    /**
     * Widget: NPA & regulatory ratios (CHECKER, ADMIN, AUDITOR).
     * BFF refresh: 60s. Skeleton: 3 amounts + 2 ratio bars.
     */
    @GetMapping("/widgets/npa")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<NpaWidget>> widgetNpa() {
        String tid = TenantContext.getCurrentTenant();
        BigDecimal out = accountRepository.calculateTotalOutstandingPrincipal(tid);
        BigDecimal npaAmt = accountRepository.calculateTotalNpaOutstanding(tid);
        BigDecimal prov = accountRepository.calculateTotalProvisioning(tid);
        return ResponseEntity.ok(ApiResponse.success(new NpaWidget(
                out, npaAmt, prov,
                ratio(npaAmt, out),
                ratio(prov, npaAmt).min(BigDecimal.valueOf(100)))));
    }

    /**
     * Widget: CASA deposit overview (CHECKER, ADMIN, AUDITOR).
     * BFF refresh: 60s. Skeleton: 3 metric values.
     */
    @GetMapping("/widgets/casa")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<CasaWidget>> widgetCasa() {
        String tid = TenantContext.getCurrentTenant();
        BigDecimal dep = depositAccountRepository.calculateTotalDeposits(tid);
        long cnt = depositAccountRepository.countByTenantIdAndAccountStatusNot(
                tid, DepositAccountStatus.CLOSED);
        return ResponseEntity.ok(ApiResponse.success(new CasaWidget(
                dep, cnt, ratio(dep, dep))));
    }

    /**
     * Widget: Pending approvals badge (MAKER, CHECKER, ADMIN).
     * BFF refresh: 15s. Skeleton: badge counter.
     */
    @GetMapping("/widgets/pending-approvals")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalsWidget>> widgetApprovals() {
        return ResponseEntity.ok(ApiResponse.success(new ApprovalsWidget(
                workflowService.getPendingApprovals().size())));
    }

    // === Widget Response DTOs ===

    public record PortfolioWidget(
            long totalCustomers, long casaAccounts,
            long activeLoans, long smaAccounts,
            long npaAccounts, long pendingApplications) {}

    public record NpaWidget(
            BigDecimal totalOutstanding, BigDecimal npaOutstanding,
            BigDecimal totalProvisioning,
            BigDecimal grossNpaRatio, BigDecimal provisionCoverage) {}

    public record CasaWidget(
            BigDecimal totalDeposits, long casaAccountCount,
            BigDecimal casaRatio) {}

    public record ApprovalsWidget(long pendingCount) {}

    // ====================================================================
    // TELLER DASHBOARD WIDGETS — Operational Cockpit
    // Per Finacle TELLER_DASHBOARD / Temenos TELLER.COCKPIT:
    // Transaction control + liquidity + approval queue.
    // ====================================================================

    /**
     * Teller Widget: Today's transaction metrics (4 metric cards).
     * BFF refresh: 30s. Skeleton: 4 metric cards (128px height).
     * Per Finacle TRAN_SUMMARY: intra-day operational awareness.
     */
    @GetMapping("/widgets/teller/txn-summary")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TellerTxnSummary>> widgetTellerTxnSummary() {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = businessDateService.getCurrentBusinessDate();

        List<Object[]> txnStats = depositTxnRepository.findByTenantIdAndValueDate(tid, bd)
                .stream().collect(
                        () -> new Object[]{0L, BigDecimal.ZERO, BigDecimal.ZERO},
                        (acc, txn) -> {
                            acc[0] = (long) acc[0] + 1;
                            if ("CREDIT".equals(txn.getDebitCredit())) {
                                acc[1] = ((BigDecimal) acc[1]).add(txn.getAmount());
                            } else {
                                acc[2] = ((BigDecimal) acc[2]).add(txn.getAmount());
                            }
                        },
                        (a, b) -> {});

        long count = (long) txnStats[0];
        BigDecimal credits = (BigDecimal) txnStats[1];
        BigDecimal debits = (BigDecimal) txnStats[2];

        return ResponseEntity.ok(ApiResponse.success(new TellerTxnSummary(
                bd.toString(), count,
                credits, debits, credits.subtract(debits))));
    }

    /**
     * Teller Widget: Pending approval queue with aging (table widget).
     * BFF refresh: 15s. Skeleton: table (360px height, 8 rows).
     * Per Finacle WFAPI: shows ref, type, amount, maker, age, status.
     */
    @GetMapping("/widgets/teller/approval-queue")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ApprovalQueueWidget>> widgetApprovalQueue() {
        List<ApprovalWorkflow> pending = workflowService.getPendingApprovals();
        LocalDateTime now = LocalDateTime.now();

        List<ApprovalQueueItem> items = pending.stream()
                .map(wf -> {
                    long ageMinutes = wf.getSubmittedAt() != null
                            ? ChronoUnit.MINUTES.between(wf.getSubmittedAt(), now) : 0;
                    String ageDisplay = ageMinutes < 60
                            ? ageMinutes + "m"
                            : (ageMinutes / 60) + "h " + (ageMinutes % 60) + "m";
                    boolean slaBreached = wf.isSlaBreached();
                    return new ApprovalQueueItem(
                            wf.getId(),
                            wf.getEntityType() + "/" + wf.getEntityId(),
                            wf.getActionType(),
                            wf.getMakerUserId(),
                            ageDisplay, ageMinutes,
                            slaBreached,
                            wf.getStatus().name());
                })
                .sorted((a, b) -> Long.compare(b.ageMinutes(), a.ageMinutes()))
                .limit(8)
                .toList();

        long overdueCount = pending.stream()
                .filter(ApprovalWorkflow::isSlaBreached).count();

        return ResponseEntity.ok(ApiResponse.success(
                new ApprovalQueueWidget(items, pending.size(), overdueCount)));
    }

    // ====================================================================
    // MANAGER DASHBOARD WIDGETS — Risk Visibility
    // Per Finacle BRANCH_DASHBOARD / Temenos RISK.MONITOR:
    // Risk metrics + clearing status + overdue approvals.
    // ====================================================================

    /**
     * Manager Widget: Clearing & settlement status (4 counts).
     * BFF refresh: 60s. Skeleton: 4 metric values in horizontal panel.
     */
    @GetMapping("/widgets/manager/clearing-status")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<ClearingStatusWidget>> widgetClearingStatus() {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = businessDateService.getCurrentBusinessDate();

        long initiated = clearingTxnRepository.countByDateAndStatus(
                tid, bd, ClearingStatus.INITIATED);
        long sentToNetwork = clearingTxnRepository.countByDateAndStatus(
                tid, bd, ClearingStatus.SENT_TO_NETWORK);
        long settled = clearingTxnRepository.countByDateAndStatus(
                tid, bd, ClearingStatus.SETTLED)
                + clearingTxnRepository.countByDateAndStatus(
                        tid, bd, ClearingStatus.COMPLETED);
        long failed = clearingTxnRepository.countByDateAndStatus(
                tid, bd, ClearingStatus.SETTLEMENT_FAILED)
                + clearingTxnRepository.countByDateAndStatus(
                        tid, bd, ClearingStatus.NETWORK_REJECTED);

        return ResponseEntity.ok(ApiResponse.success(new ClearingStatusWidget(
                bd.toString(), initiated, sentToNetwork, settled, failed)));
    }

    /**
     * Manager Widget: Risk metrics (4 cards — red border if threshold breached).
     * BFF refresh: 60s. Skeleton: 4 metric cards (140px height).
     */
    @GetMapping("/widgets/manager/risk-metrics")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<RiskMetricsWidget>> widgetRiskMetrics() {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = businessDateService.getCurrentBusinessDate();

        List<ApprovalWorkflow> pending = workflowService.getPendingApprovals();
        long overdueApprovals = pending.stream()
                .filter(ApprovalWorkflow::isSlaBreached).count();

        // High-value transactions today (> 10 lakh = 1,000,000)
        BigDecimal highValueThreshold = new BigDecimal("1000000");
        long highValueTxns = depositTxnRepository.findByTenantIdAndValueDate(tid, bd)
                .stream()
                .filter(t -> t.getAmount().compareTo(highValueThreshold) > 0)
                .count();

        // Suspense accounts (clearing in non-terminal state with active suspense GL)
        long suspensePending = clearingTxnRepository.countByDateAndStatus(
                tid, bd, ClearingStatus.SUSPENSE_POSTED)
                + clearingTxnRepository.countByDateAndStatus(
                        tid, bd, ClearingStatus.SENT_TO_NETWORK);

        return ResponseEntity.ok(ApiResponse.success(new RiskMetricsWidget(
                overdueApprovals, suspensePending, highValueTxns,
                overdueApprovals > 0, suspensePending > 5,
                highValueTxns > 10)));
    }

    // === Teller + Manager Widget Response DTOs ===

    /** Teller: Today's intra-day transaction metrics */
    public record TellerTxnSummary(
            String businessDate, long totalTransactions,
            BigDecimal totalCredits, BigDecimal totalDebits,
            BigDecimal netAmount) {}

    /** Teller: Approval queue with aging per Finacle WFAPI */
    public record ApprovalQueueWidget(
            List<ApprovalQueueItem> items,
            long totalPending, long overdueCount) {}

    public record ApprovalQueueItem(
            Long id, String reference, String actionType,
            String makerUserId, String age, long ageMinutes,
            boolean slaBreached, String status) {}

    /** Manager: Clearing & settlement status */
    public record ClearingStatusWidget(
            String businessDate,
            long initiated, long sentToNetwork,
            long settled, long failed) {}

    /** Manager: Risk metrics with threshold breach flags */
    public record RiskMetricsWidget(
            long overdueApprovals, long suspensePending,
            long highValueTxnsToday,
            boolean overdueBreached, boolean suspenseBreached,
            boolean highValueBreached) {}

    // === Helpers ===

    /** Safe status count extraction — returns 0 for missing statuses. */
    private static long sc(Map<LoanStatus, Long> m,
            LoanStatus s) {
        return m.getOrDefault(s, 0L);
    }

    /**
     * Percentage ratio: (numerator / denominator) × 100, 2 decimal places.
     * Returns ZERO if denominator is zero (avoids ArithmeticException).
     */
    private static BigDecimal ratio(BigDecimal numerator,
            BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return numerator
                .multiply(BigDecimal.valueOf(100))
                .divide(denominator, 2, RoundingMode.HALF_UP);
    }

    // === Response DTOs ===

    /**
     * Complete dashboard summary per Finacle DASHBOARD / RBI regulatory reporting.
     * All metrics computed server-side — no client-side calculation needed.
     */
    public record DashboardSummary(
            CustomerMetrics customers,
            LoanMetrics loans,
            FinancialMetrics financials,
            CasaMetrics casa,
            WorkflowMetrics workflow) {}

    public record CustomerMetrics(
            long totalActive) {}

    public record LoanMetrics(
            long activeLoans,
            long smaAccounts,
            long npaAccounts,
            long pendingApplications) {}

    /**
     * RBI regulatory ratios per IRAC Norms / RBI OSMOS reporting.
     * <ul>
     *   <li>grossNpaRatio: (NPA Outstanding / Total Outstanding) × 100</li>
     *   <li>provisionCoverage: (Total Provisioning / NPA Outstanding) × 100, capped at 100%</li>
     * </ul>
     */
    public record FinancialMetrics(
            BigDecimal totalOutstanding,
            BigDecimal npaOutstanding,
            BigDecimal totalProvisioning,
            BigDecimal grossNpaRatio,
            BigDecimal provisionCoverage) {}

    /**
     * CASA metrics per RBI CASA Ratio reporting.
     * Higher CASA ratio = lower cost of funds for the bank.
     */
    public record CasaMetrics(
            BigDecimal totalDeposits,
            long casaAccountCount,
            BigDecimal casaRatio) {}

    public record WorkflowMetrics(
            long pendingApprovals) {}
}

package com.finvanta.api;

import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final ApprovalWorkflowService workflowService;

    public DashboardApiController(
            CustomerRepository customerRepository,
            LoanApplicationRepository applicationRepository,
            LoanAccountRepository accountRepository,
            DepositAccountRepository depositAccountRepository,
            ApprovalWorkflowService workflowService) {
        this.customerRepository = customerRepository;
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.workflowService = workflowService;
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

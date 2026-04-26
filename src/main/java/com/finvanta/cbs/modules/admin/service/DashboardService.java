package com.finvanta.cbs.modules.admin.service;

/**
 * CBS Dashboard Service Interface.
 *
 * <p>REFACTORED: Extracts dashboard aggregation logic from
 * {@code DashboardApiController} into a proper service layer.
 *
 * <p>Per Tier-1 CBS standards: the controller must NOT directly call
 * repositories. All data aggregation, computation, and business logic
 * belongs in the service layer.
 *
 * <p>This interface defines the contract for dashboard metrics computation.
 * The implementation will inject the required repositories and compute
 * all dashboard metrics (NPA ratios, CASA ratios, provision coverage, etc.)
 * within a single read-only transaction.
 */
public interface DashboardService {

    /**
     * Computes aggregated CBS dashboard metrics for the current tenant.
     * Per Finacle DASHBOARD: all metrics computed server-side in a single
     * read-only transaction to ensure consistency.
     *
     * @return complete dashboard summary with all computed metrics
     */
    DashboardSummaryDto getSummary();

    /**
     * Dashboard summary DTO containing all CBS metrics.
     * Per RBI regulatory reporting: includes NPA ratio, provision coverage,
     * CASA ratio, and pipeline counts.
     */
    record DashboardSummaryDto(
        long totalCustomers,
        long pendingApplications,
        long activeLoans,
        long npaAccounts,
        java.math.BigDecimal totalLoanOutstanding,
        java.math.BigDecimal npaOutstanding,
        java.math.BigDecimal grossNpaPercent,
        java.math.BigDecimal totalProvisioning,
        java.math.BigDecimal provisionCoveragePercent,
        java.math.BigDecimal totalDeposits,
        long totalDepositAccounts,
        java.math.BigDecimal casaRatio,
        long pendingWorkflows,
        long todayTransactionCount,
        java.math.BigDecimal todayTransactionVolume
    ) {
    }
}

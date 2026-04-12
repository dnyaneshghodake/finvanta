package com.finvanta.repository;

import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.PaymentRail;

import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Clearing Transaction Repository per Finacle CLG_MASTER / RBI Payment Systems.
 *
 * Provides queries for:
 * - Idempotency check (duplicate external reference detection)
 * - Clearing cycle aggregation (NEFT batch netting)
 * - Settlement confirmation processing
 * - EOD suspense reconciliation
 * - Per-rail, per-direction transaction lookups
 */
@Repository
public interface ClearingTransactionRepository extends JpaRepository<ClearingTransaction, Long> {

    // === Idempotency ===

    /** Idempotency: find by external reference (unique per tenant) */
    Optional<ClearingTransaction> findByTenantIdAndExternalRefNo(String tenantId, String externalRefNo);

    /** Check if external reference already exists (fast existence check) */
    boolean existsByTenantIdAndExternalRefNo(String tenantId, String externalRefNo);

    // === Status-Based Queries ===

    /** Find transactions by status for a specific rail and direction */
    List<ClearingTransaction> findByTenantIdAndPaymentRailAndDirectionAndStatusOrderByInitiatedAtAsc(
            String tenantId, PaymentRail rail, ClearingDirection direction, ClearingStatus status);

    /** Find all in-flight transactions for a business date (EOD check) */
    @Query("SELECT ct FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.valueDate = :valueDate AND ct.status NOT IN :terminalStatuses "
            + "ORDER BY ct.initiatedAt ASC")
    List<ClearingTransaction> findInFlightByDate(
            @Param("tenantId") String tenantId,
            @Param("valueDate") LocalDate valueDate,
            @Param("terminalStatuses") List<ClearingStatus> terminalStatuses);

    /** Find transactions with active suspense (needs clearing at EOD) */
    @Query("SELECT ct FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.status IN :activeStatuses "
            + "ORDER BY ct.initiatedAt ASC")
    List<ClearingTransaction> findWithActiveSuspense(
            @Param("tenantId") String tenantId,
            @Param("activeStatuses") List<ClearingStatus> activeStatuses);

    /** Find and lock for settlement processing */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ct FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.status = :status AND ct.paymentRail = :rail "
            + "ORDER BY ct.initiatedAt ASC")
    List<ClearingTransaction> findAndLockByRailAndStatus(
            @Param("tenantId") String tenantId,
            @Param("rail") PaymentRail rail,
            @Param("status") ClearingStatus status);

    // === Clearing Cycle Queries (NEFT batch netting) ===

    /** Find transactions in a specific clearing cycle */
    List<ClearingTransaction> findByTenantIdAndClearingCycleIdOrderByInitiatedAtAsc(
            String tenantId, Long clearingCycleId);

    // === Aggregation Queries ===

    /** Sum amount by rail, direction, and status for a specific date (date-scoped reporting) */
    @Query("SELECT COALESCE(SUM(ct.amount), 0) FROM ClearingTransaction ct "
            + "WHERE ct.tenantId = :tenantId AND ct.paymentRail = :rail "
            + "AND ct.direction = :direction AND ct.status = :status "
            + "AND ct.valueDate = :valueDate")
    BigDecimal sumAmountByRailDirectionStatus(
            @Param("tenantId") String tenantId,
            @Param("rail") PaymentRail rail,
            @Param("direction") ClearingDirection direction,
            @Param("status") ClearingStatus status,
            @Param("valueDate") LocalDate valueDate);

    /**
     * Sum amount by rail, direction, and status across ALL dates (GL reconciliation).
     *
     * Per Finacle CLG_RECON: this query matches the scope of GLMaster running totals
     * which are cumulative across all dates. The date-filtered variant above would
     * miss active suspense from prior business dates (e.g., NEFT submitted at 5:30 PM
     * yesterday, settled this morning) causing false reconciliation mismatches.
     *
     * Consistent with countActiveSuspenseByRail which also has no date filter.
     */
    @Query("SELECT COALESCE(SUM(ct.amount), 0) FROM ClearingTransaction ct "
            + "WHERE ct.tenantId = :tenantId AND ct.paymentRail = :rail "
            + "AND ct.direction = :direction AND ct.status = :status")
    BigDecimal sumActiveSuspenseByRailAndDirection(
            @Param("tenantId") String tenantId,
            @Param("rail") PaymentRail rail,
            @Param("direction") ClearingDirection direction,
            @Param("status") ClearingStatus status);

    /** Count transactions by status for a date (dashboard/monitoring) */
    @Query("SELECT COUNT(ct) FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.valueDate = :valueDate AND ct.status = :status")
    long countByDateAndStatus(
            @Param("tenantId") String tenantId,
            @Param("valueDate") LocalDate valueDate,
            @Param("status") ClearingStatus status);

    /** Count active suspense transactions per rail (EOD reconciliation) */
    @Query("SELECT COUNT(ct) FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.paymentRail = :rail AND ct.status IN :activeStatuses")
    long countActiveSuspenseByRail(
            @Param("tenantId") String tenantId,
            @Param("rail") PaymentRail rail,
            @Param("activeStatuses") List<ClearingStatus> activeStatuses);

    // === Network Timeout / Stuck Transaction Detection ===

    /** Find outward transactions stuck in SENT_TO_NETWORK beyond timeout threshold */
    @Query("SELECT ct FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.status = :status AND ct.sentToNetworkAt < :cutoff "
            + "ORDER BY ct.sentToNetworkAt ASC")
    List<ClearingTransaction> findStuckInNetworkBefore(
            @Param("tenantId") String tenantId,
            @Param("status") ClearingStatus status,
            @Param("cutoff") LocalDateTime cutoff);

    /** Sum daily outward amount per rail for a customer account (limit enforcement) */
    @Query("SELECT COALESCE(SUM(ct.amount), 0) FROM ClearingTransaction ct "
            + "WHERE ct.tenantId = :tenantId "
            + "AND ct.customerAccountRef = :accountRef "
            + "AND ct.paymentRail = :rail "
            + "AND ct.direction = :direction "
            + "AND ct.valueDate = :valueDate "
            + "AND ct.status NOT IN :excludedStatuses")
    BigDecimal sumDailyAmountByAccountAndRail(
            @Param("tenantId") String tenantId,
            @Param("accountRef") String accountRef,
            @Param("rail") PaymentRail rail,
            @Param("direction") ClearingDirection direction,
            @Param("valueDate") LocalDate valueDate,
            @Param("excludedStatuses") List<ClearingStatus> excludedStatuses);

    // === Branch-Scoped Queries ===

    /** Find transactions by branch for branch-level reconciliation */
    List<ClearingTransaction> findByTenantIdAndBranchIdAndValueDateOrderByInitiatedAtAsc(
            String tenantId, Long branchId, LocalDate valueDate);
}

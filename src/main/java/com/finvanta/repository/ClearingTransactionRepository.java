package com.finvanta.repository;

import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.PaymentRail;

import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.time.LocalDate;
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
            + "AND ct.valueDate = :valueDate AND ct.status NOT IN ('COMPLETED', 'REVERSED', "
            + "'RETURNED', 'VALIDATION_FAILED', 'NETWORK_REJECTED') "
            + "ORDER BY ct.initiatedAt ASC")
    List<ClearingTransaction> findInFlightByDate(
            @Param("tenantId") String tenantId, @Param("valueDate") LocalDate valueDate);

    /** Find transactions with active suspense (needs clearing at EOD) */
    @Query("SELECT ct FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.status IN ('SUSPENSE_POSTED', 'SENT_TO_NETWORK', 'SETTLED') "
            + "ORDER BY ct.initiatedAt ASC")
    List<ClearingTransaction> findWithActiveSuspense(@Param("tenantId") String tenantId);

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

    /** Sum amount by rail, direction, and status for a date (reconciliation) */
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

    /** Count transactions by status for a date (dashboard/monitoring) */
    @Query("SELECT COUNT(ct) FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.valueDate = :valueDate AND ct.status = :status")
    long countByDateAndStatus(
            @Param("tenantId") String tenantId,
            @Param("valueDate") LocalDate valueDate,
            @Param("status") ClearingStatus status);

    /** Count active suspense transactions per rail (EOD reconciliation) */
    @Query("SELECT COUNT(ct) FROM ClearingTransaction ct WHERE ct.tenantId = :tenantId "
            + "AND ct.paymentRail = :rail AND ct.status IN ('SUSPENSE_POSTED', 'SENT_TO_NETWORK', 'SETTLED')")
    long countActiveSuspenseByRail(
            @Param("tenantId") String tenantId, @Param("rail") PaymentRail rail);

    // === Branch-Scoped Queries ===

    /** Find transactions by branch for branch-level reconciliation */
    List<ClearingTransaction> findByTenantIdAndBranchIdAndValueDateOrderByInitiatedAtAsc(
            String tenantId, Long branchId, LocalDate valueDate);
}

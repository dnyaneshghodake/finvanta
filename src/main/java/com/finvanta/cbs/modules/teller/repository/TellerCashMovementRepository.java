package com.finvanta.cbs.modules.teller.repository;

import com.finvanta.cbs.modules.teller.domain.TellerCashMovement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Teller Cash Movement Repository per CBS CASH_MOVE standard.
 *
 * <p>Read paths for the vault custodian dashboard (PENDING movements
 * awaiting approval), the teller's movement history, and the branch
 * cash-flow report.
 */
@Repository
public interface TellerCashMovementRepository extends JpaRepository<TellerCashMovement, Long> {

    /** Lookup by permanent movement reference. */
    @Query("SELECT m FROM TellerCashMovement m JOIN FETCH m.branch "
            + "WHERE m.tenantId = :tenantId AND m.movementRef = :movementRef")
    Optional<TellerCashMovement> findByMovementRef(
            @Param("tenantId") String tenantId,
            @Param("movementRef") String movementRef);

    /**
     * Pending movements at a branch awaiting vault custodian approval.
     * Used by the vault custodian dashboard.
     */
    @Query("SELECT m FROM TellerCashMovement m JOIN FETCH m.branch "
            + "WHERE m.tenantId = :tenantId "
            + "AND m.branch.id = :branchId "
            + "AND m.businessDate = :businessDate "
            + "AND m.status = 'PENDING' "
            + "ORDER BY m.requestedAt ASC")
    List<TellerCashMovement> findPendingByBranchAndDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("businessDate") LocalDate businessDate);

    /**
     * All movements for a specific till on a business date (approved +
     * pending + rejected). Used by the till's movement history panel and
     * by the till-close reconciliation step.
     */
    @Query("SELECT m FROM TellerCashMovement m JOIN FETCH m.branch "
            + "WHERE m.tenantId = :tenantId "
            + "AND m.tillId = :tillId "
            + "AND m.businessDate = :businessDate "
            + "ORDER BY m.requestedAt ASC")
    List<TellerCashMovement> findByTillAndDate(
            @Param("tenantId") String tenantId,
            @Param("tillId") Long tillId,
            @Param("businessDate") LocalDate businessDate);
}

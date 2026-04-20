package com.finvanta.repository;

import com.finvanta.domain.entity.ClearingCycle;
import com.finvanta.domain.enums.ClearingCycleStatus;
import com.finvanta.domain.enums.PaymentRail;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Clearing Cycle Repository per Finacle CLG_CYCLE / RBI NEFT Settlement Windows.
 */
@Repository
public interface ClearingCycleRepository extends JpaRepository<ClearingCycle, Long> {

    /** Find the current OPEN cycle for a rail on a date (for adding transactions) */
    @Query("SELECT cc FROM ClearingCycle cc WHERE cc.tenantId = :tenantId "
            + "AND cc.railType = :rail AND cc.cycleDate = :cycleDate AND cc.status = :openStatus "
            + "ORDER BY cc.cycleNumber DESC LIMIT 1")
    Optional<ClearingCycle> findOpenCycle(
            @Param("tenantId") String tenantId,
            @Param("rail") PaymentRail rail,
            @Param("cycleDate") LocalDate cycleDate,
            @Param("openStatus") ClearingCycleStatus openStatus);

    /** Find and lock OPEN cycle for atomic transaction addition.
     *  30s lock timeout per Finacle CLG_LOCK standard. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT cc FROM ClearingCycle cc WHERE cc.id = :id")
    Optional<ClearingCycle> findAndLockById(@Param("id") Long id);

    /** Find all cycles for a date and rail (for EOD reconciliation) */
    List<ClearingCycle> findByTenantIdAndRailTypeAndCycleDateOrderByCycleNumberAsc(
            String tenantId, PaymentRail railType, LocalDate cycleDate);

    /** Find unsettled cycles for a date (EOD check) */
    @Query("SELECT cc FROM ClearingCycle cc WHERE cc.tenantId = :tenantId "
            + "AND cc.cycleDate = :cycleDate AND cc.status != :settledStatus "
            + "ORDER BY cc.railType, cc.cycleNumber")
    List<ClearingCycle> findUnsettledByDate(
            @Param("tenantId") String tenantId,
            @Param("cycleDate") LocalDate cycleDate,
            @Param("settledStatus") ClearingCycleStatus settledStatus);

    /** Get next cycle number for a rail on a date */
    @Query("SELECT COALESCE(MAX(cc.cycleNumber), 0) + 1 FROM ClearingCycle cc "
            + "WHERE cc.tenantId = :tenantId AND cc.railType = :rail AND cc.cycleDate = :cycleDate")
    int getNextCycleNumber(
            @Param("tenantId") String tenantId,
            @Param("rail") PaymentRail rail,
            @Param("cycleDate") LocalDate cycleDate);
}

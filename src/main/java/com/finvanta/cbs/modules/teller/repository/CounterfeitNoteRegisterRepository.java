package com.finvanta.cbs.modules.teller.repository;

import com.finvanta.cbs.modules.teller.domain.CounterfeitNoteRegister;
import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Counterfeit Note Register Repository per RBI FICN Master Direction.
 *
 * <p>Read-mostly: the only writer is
 * {@code TellerServiceImpl.recordFicnDetection(...)}. Read paths support:
 * <ul>
 *   <li>Receipt re-print: lookup by {@code register_ref}.</li>
 *   <li>Branch FICN report: list rows by branch + date range for the
 *       supervisor / RBI on-site inspection screen.</li>
 *   <li>Currency-chest dispatch dashboard: rows in {@code PENDING} status
 *       so the cash officer can mark them dispatched.</li>
 *   <li>RBI quarterly FICN return: aggregated counts and face value per
 *       denomination per quarter (driven by report module).</li>
 * </ul>
 *
 * <p>JOIN FETCH branch on the read paths so the BFF / JSP layer can render
 * branch metadata without LazyInitializationException once OSIV is disabled.
 */
@Repository
public interface CounterfeitNoteRegisterRepository extends JpaRepository<CounterfeitNoteRegister, Long> {

    /** Receipt re-print / drill-down by the permanent register reference. */
    @Query("SELECT r FROM CounterfeitNoteRegister r JOIN FETCH r.branch "
            + "WHERE r.tenantId = :tenantId AND r.registerRef = :registerRef")
    Optional<CounterfeitNoteRegister> findByRegisterRef(
            @Param("tenantId") String tenantId,
            @Param("registerRef") String registerRef);

    /**
     * Branch-level FICN report for a date range. Used by the supervisor view
     * (last 30 days by default) and the RBI inspection drill-down.
     */
    @Query("SELECT r FROM CounterfeitNoteRegister r JOIN FETCH r.branch "
            + "WHERE r.tenantId = :tenantId "
            + "AND r.branch.id = :branchId "
            + "AND r.detectionDate BETWEEN :fromDate AND :toDate "
            + "ORDER BY r.detectionTimestamp DESC")
    List<CounterfeitNoteRegister> findByBranchAndDateRange(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Pending currency-chest dispatch queue. Cash officer / chest manager
     * sees rows that have been recorded but not yet remitted.
     */
    @Query("SELECT r FROM CounterfeitNoteRegister r JOIN FETCH r.branch "
            + "WHERE r.tenantId = :tenantId "
            + "AND r.chestDispatchStatus = 'PENDING' "
            + "ORDER BY r.detectionDate ASC")
    List<CounterfeitNoteRegister> findPendingDispatch(
            @Param("tenantId") String tenantId);

    /**
     * Quarterly per-denomination aggregate for the RBI FICN return.
     * Returns {@code [denomination, totalCount, totalFaceValue]} tuples.
     */
    @Query("SELECT r.denomination, SUM(r.counterfeitCount), SUM(r.totalFaceValue) "
            + "FROM CounterfeitNoteRegister r "
            + "WHERE r.tenantId = :tenantId "
            + "AND r.detectionDate BETWEEN :fromDate AND :toDate "
            + "GROUP BY r.denomination")
    List<Object[]> aggregateByDenomination(
            @Param("tenantId") String tenantId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Total face value of impounded counterfeits at a branch for a given
     * business date. Used by the FICN suspense-GL reconciliation: the GL
     * suspense balance for the day must equal this aggregate.
     */
    @Query("SELECT COALESCE(SUM(r.totalFaceValue), 0) "
            + "FROM CounterfeitNoteRegister r "
            + "WHERE r.tenantId = :tenantId "
            + "AND r.branch.id = :branchId "
            + "AND r.detectionDate = :detectionDate")
    BigDecimal sumFaceValueForBranchDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("detectionDate") LocalDate detectionDate);

    /**
     * Sequence helper for the {@code register_ref} format
     * {@code FICN/{branchCode}/{YYYYMMDD}/{seq}}. Returns the count of
     * register entries already minted at a branch on a date so the next
     * sequence number is {@code count + 1}.
     */
    @Query("SELECT COUNT(r) FROM CounterfeitNoteRegister r "
            + "WHERE r.tenantId = :tenantId "
            + "AND r.branch.id = :branchId "
            + "AND r.detectionDate = :detectionDate")
    long countByBranchAndDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("detectionDate") LocalDate detectionDate);

    /**
     * Per-denomination gross face-value lookup (used by alerting when one
     * denomination's daily counterfeit volume crosses a configurable threshold,
     * e.g. {@code IndianCurrencyDenomination.NOTE_2000} above INR 20,000 in
     * a single business day at one branch).
     */
    @Query("SELECT COALESCE(SUM(r.totalFaceValue), 0) "
            + "FROM CounterfeitNoteRegister r "
            + "WHERE r.tenantId = :tenantId "
            + "AND r.denomination = :denomination "
            + "AND r.detectionDate = :detectionDate")
    BigDecimal sumGrossForDenominationOnDate(
            @Param("tenantId") String tenantId,
            @Param("denomination") IndianCurrencyDenomination denomination,
            @Param("detectionDate") LocalDate detectionDate);
}

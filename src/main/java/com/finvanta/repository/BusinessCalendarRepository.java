package com.finvanta.repository;

import com.finvanta.domain.entity.BusinessCalendar;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Business Calendar Repository per Finacle DAYCTRL / Temenos COB.
 *
 * <h3>Tier-1 Branch-Level Day Control:</h3>
 * All calendar queries are branch-scoped. Per Finacle SOL architecture,
 * each branch has its own independent day lifecycle:
 *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
 *
 * Legacy tenant-wide methods are retained for backward compatibility
 * during migration but are marked as deprecated. New code must use
 * branch-scoped methods exclusively.
 */
@Repository
public interface BusinessCalendarRepository extends JpaRepository<BusinessCalendar, Long> {

    // ================================================================
    // BRANCH-SCOPED QUERIES (Tier-1 Standard — use these)
    // ================================================================

    /**
     * Find calendar entry for a specific branch on a specific date.
     * Primary lookup method for all branch-level day control operations.
     */
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.branch.id = :branchId AND bc.businessDate = :date")
    Optional<BusinessCalendar> findByTenantIdAndBranchIdAndBusinessDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("date") LocalDate date);

    /**
     * Find and lock calendar entry for a specific branch on a specific date.
     * PESSIMISTIC_WRITE lock prevents concurrent day status transitions.
     * Used by EOD orchestrator and day open/close operations.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.branch.id = :branchId AND bc.businessDate = :date")
    Optional<BusinessCalendar> findAndLockByTenantIdAndBranchIdAndDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("date") LocalDate date);

    /**
     * Find the currently OPEN business day for a specific branch.
     * Per Finacle DAYCTRL: only one day can be DAY_OPEN per branch at a time.
     */
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.branch.id = :branchId AND bc.dayStatus = 'DAY_OPEN'")
    Optional<BusinessCalendar> findOpenDayByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /**
     * Find the last completed EOD date for a specific branch.
     * Used for previous-day validation before opening a new day.
     */
    @Query("SELECT MAX(bc.businessDate) FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.branch.id = :branchId AND bc.eodComplete = true")
    Optional<LocalDate> findLastCompletedEodDateByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /**
     * Find the current business date for a specific branch.
     * Returns the latest non-holiday date where EOD is not yet complete.
     */
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.branch.id = :branchId AND bc.businessDate = "
            + "(SELECT MAX(bc2.businessDate) FROM BusinessCalendar bc2 WHERE bc2.tenantId = :tenantId "
            + "AND bc2.branch.id = :branchId AND bc2.eodComplete = false AND bc2.holiday = false)")
    Optional<BusinessCalendar> findCurrentBusinessDateByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /**
     * All calendar entries for a specific branch, ordered by date descending.
     * Used for branch calendar management UI.
     */
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.branch.id = :branchId ORDER BY bc.businessDate DESC")
    List<BusinessCalendar> findByTenantIdAndBranchIdOrderByBusinessDateDesc(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /**
     * Check if all branches have completed EOD for a specific date.
     * Used by HO consolidation to determine if tenant-level EOD can proceed.
     * Returns the count of branches that have NOT completed EOD.
     */
    @Query("SELECT COUNT(bc) FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId "
            + "AND bc.businessDate = :date AND bc.eodComplete = false AND bc.holiday = false")
    long countBranchesWithPendingEod(
            @Param("tenantId") String tenantId, @Param("date") LocalDate date);

    /**
     * CBS SI Holiday Handling (branch-scoped): Find the next business day
     * on or after a given date for a specific branch.
     * Per Finacle SI_MASTER / RBI Payment Systems: if SI execution date falls on a
     * holiday at the branch, execution shifts to the next available business day.
     */
    @Query("SELECT MIN(bc.businessDate) FROM BusinessCalendar bc "
            + "WHERE bc.tenantId = :tenantId AND bc.branch.id = :branchId "
            + "AND bc.businessDate >= :targetDate AND bc.holiday = false")
    Optional<LocalDate> findNextBusinessDayOnOrAfterByBranch(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("targetDate") LocalDate targetDate);

    /**
     * CBS SI: Check if a specific date is a holiday at a specific branch.
     */
    @Query("SELECT CASE WHEN COUNT(bc) > 0 THEN true ELSE false END FROM BusinessCalendar bc "
            + "WHERE bc.tenantId = :tenantId AND bc.branch.id = :branchId "
            + "AND bc.businessDate = :date AND bc.holiday = true")
    boolean isHolidayAtBranch(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("date") LocalDate date);

    // ================================================================
    // LEGACY TENANT-WIDE QUERIES (backward compatibility — migrate away)
    // ================================================================

    /**
     * @deprecated Use {@link #findByTenantIdAndBranchIdAndBusinessDate} instead.
     * Retained for backward compatibility during branch-level migration.
     * Returns the FIRST matching calendar entry across all branches.
     */
    @Deprecated(forRemoval = true)
    Optional<BusinessCalendar> findByTenantIdAndBusinessDate(String tenantId, LocalDate businessDate);

    /**
     * @deprecated Use {@link #findAndLockByTenantIdAndBranchIdAndDate} instead.
     */
    @Deprecated(forRemoval = true)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.businessDate = :date")
    Optional<BusinessCalendar> findAndLockByTenantIdAndDate(
            @Param("tenantId") String tenantId, @Param("date") LocalDate date);

    /**
     * @deprecated Use {@link #findLastCompletedEodDateByBranch} instead.
     */
    @Deprecated(forRemoval = true)
    @Query("SELECT MAX(bc.businessDate) FROM BusinessCalendar bc "
            + "WHERE bc.tenantId = :tenantId AND bc.eodComplete = true")
    Optional<LocalDate> findLastCompletedEodDate(@Param("tenantId") String tenantId);

    /**
     * @deprecated Use {@link #findCurrentBusinessDateByBranch} instead.
     */
    @Deprecated(forRemoval = true)
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.businessDate = "
            + "(SELECT MAX(bc2.businessDate) FROM BusinessCalendar bc2 WHERE bc2.tenantId = :tenantId "
            + "AND bc2.eodComplete = false AND bc2.holiday = false)")
    Optional<BusinessCalendar> findCurrentBusinessDate(@Param("tenantId") String tenantId);

    /**
     * @deprecated Use {@link #findByTenantIdAndBranchIdOrderByBusinessDateDesc} instead.
     */
    @Deprecated(forRemoval = true)
    List<BusinessCalendar> findByTenantIdOrderByBusinessDateDesc(String tenantId);

    /**
     * @deprecated Use {@link #findOpenDayByBranch} instead.
     */
    @Deprecated(forRemoval = true)
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.dayStatus = 'DAY_OPEN'")
    Optional<BusinessCalendar> findOpenDay(@Param("tenantId") String tenantId);

    /**
     * @deprecated Use {@link #findNextBusinessDayOnOrAfterByBranch} instead.
     */
    @Deprecated(forRemoval = true)
    @Query("SELECT MIN(bc.businessDate) FROM BusinessCalendar bc "
            + "WHERE bc.tenantId = :tenantId AND bc.businessDate >= :targetDate AND bc.holiday = false")
    Optional<LocalDate> findNextBusinessDayOnOrAfter(
            @Param("tenantId") String tenantId, @Param("targetDate") LocalDate targetDate);

    /**
     * @deprecated Use {@link #isHolidayAtBranch} instead.
     */
    @Deprecated(forRemoval = true)
    @Query("SELECT CASE WHEN COUNT(bc) > 0 THEN true ELSE false END FROM BusinessCalendar bc "
            + "WHERE bc.tenantId = :tenantId AND bc.businessDate = :date AND bc.holiday = true")
    boolean isHoliday(@Param("tenantId") String tenantId, @Param("date") LocalDate date);
}

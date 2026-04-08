package com.finvanta.repository;

import com.finvanta.domain.entity.BusinessCalendar;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessCalendarRepository extends JpaRepository<BusinessCalendar, Long> {

    Optional<BusinessCalendar> findByTenantIdAndBusinessDate(String tenantId, LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.businessDate = :date")
    Optional<BusinessCalendar> findAndLockByTenantIdAndDate(
        @Param("tenantId") String tenantId,
        @Param("date") LocalDate date
    );

    @Query("SELECT MAX(bc.businessDate) FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.eodComplete = true")
    Optional<LocalDate> findLastCompletedEodDate(@Param("tenantId") String tenantId);

    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.businessDate = " +
           "(SELECT MAX(bc2.businessDate) FROM BusinessCalendar bc2 WHERE bc2.tenantId = :tenantId AND bc2.eodComplete = false AND bc2.holiday = false)")
    Optional<BusinessCalendar> findCurrentBusinessDate(@Param("tenantId") String tenantId);

    List<BusinessCalendar> findByTenantIdOrderByBusinessDateDesc(String tenantId);

    /** CBS Day Control: Find the currently OPEN business day (only one allowed at a time) */
    @Query("SELECT bc FROM BusinessCalendar bc WHERE bc.tenantId = :tenantId AND bc.dayStatus = 'DAY_OPEN'")
    Optional<BusinessCalendar> findOpenDay(@Param("tenantId") String tenantId);

    /**
     * CBS SI Holiday Handling: Find the next business day on or after a given date.
     * Per Finacle SI_MASTER / RBI Payment Systems: if SI execution date falls on a
     * holiday or weekend, execution shifts to the next available business day.
     * Returns the earliest date >= targetDate that is NOT a holiday.
     * If targetDate itself is a business day, returns targetDate.
     */
    @Query("SELECT MIN(bc.businessDate) FROM BusinessCalendar bc " +
           "WHERE bc.tenantId = :tenantId AND bc.businessDate >= :targetDate AND bc.holiday = false")
    Optional<LocalDate> findNextBusinessDayOnOrAfter(
        @Param("tenantId") String tenantId,
        @Param("targetDate") LocalDate targetDate);

    /**
     * CBS SI: Check if a specific date is a holiday.
     * Used by SI engine to determine if execution date needs adjustment.
     */
    @Query("SELECT CASE WHEN COUNT(bc) > 0 THEN true ELSE false END FROM BusinessCalendar bc " +
           "WHERE bc.tenantId = :tenantId AND bc.businessDate = :date AND bc.holiday = true")
    boolean isHoliday(@Param("tenantId") String tenantId, @Param("date") LocalDate date);
}

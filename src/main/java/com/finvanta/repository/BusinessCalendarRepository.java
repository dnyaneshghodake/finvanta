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
}

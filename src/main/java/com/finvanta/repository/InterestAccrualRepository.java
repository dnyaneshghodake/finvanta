package com.finvanta.repository;

import com.finvanta.domain.entity.InterestAccrual;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for InterestAccrual — Audit-grade per-day accrual records.
 */
@Repository
public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, Long> {

    /**
     * Find all accrual records for an account within a date range.
     */
    List<InterestAccrual> findByTenantIdAndAccountIdAndAccrualDateBetweenOrderByAccrualDateAsc(
            String tenantId, Long accountId, LocalDate fromDate, LocalDate toDate);

    /**
     * Find unposted accruals (for retry/reconciliation).
     */
    List<InterestAccrual> findByTenantIdAndAccountIdAndPostedFlagFalseOrderByAccrualDateAsc(
            String tenantId, Long accountId);

    /**
     * Sum accrued interest by account and type (for reconciliation).
     */
    @Query("SELECT SUM(a.accruedAmount) FROM InterestAccrual a "
            + "WHERE a.tenantId = :tenantId AND a.accountId = :accountId AND a.accrualType = :accrualType "
            + "AND a.postedFlag = true")
    BigDecimal sumAccruedByAccountAndType(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("accrualType") String accrualType);

    /**
     * Find accruals for a business date (for EOD processing).
     */
    List<InterestAccrual> findByTenantIdAndBusinessDateAndPostedFlagFalseOrderByAccountIdAsc(
            String tenantId, LocalDate businessDate);

    /**
     * Check if an account has any accruals on a specific date.
     */
    boolean existsByTenantIdAndAccountIdAndAccrualDate(String tenantId, Long accountId, LocalDate accrualDate);
}

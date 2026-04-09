package com.finvanta.repository;

import com.finvanta.domain.entity.DailyBalanceSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Daily Balance Snapshot Repository per Finacle ACCT_BAL_HIST.
 *
 * Provides queries for:
 * - Minimum daily balance (for RBI-compliant interest calculation)
 * - Average daily balance (for CRR/SLR regulatory reporting)
 * - Balance on a specific date (for customer dispute resolution)
 */
@Repository
public interface DailyBalanceSnapshotRepository extends JpaRepository<DailyBalanceSnapshot, Long> {

    /** Check if snapshot already exists for an account on a date (idempotency) */
    boolean existsByTenantIdAndAccountIdAndBusinessDate(String tenantId, Long accountId, LocalDate businessDate);

    /** Get snapshot for a specific account on a specific date */
    Optional<DailyBalanceSnapshot> findByTenantIdAndAccountIdAndBusinessDate(
            String tenantId, Long accountId, LocalDate businessDate);

    /**
     * Minimum closing balance for an account in a date range.
     * Used for RBI-compliant minimum daily balance interest calculation.
     * Per RBI: savings interest = minDailyBalance * rate / 36500
     */
    @Query("SELECT COALESCE(MIN(dbs.closingBalance), 0) FROM DailyBalanceSnapshot dbs "
            + "WHERE dbs.tenantId = :tenantId AND dbs.accountId = :accountId "
            + "AND dbs.businessDate BETWEEN :fromDate AND :toDate")
    BigDecimal findMinBalanceInPeriod(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Average closing balance for an account in a date range.
     * Used for CRR/SLR regulatory reporting and average balance method interest.
     */
    @Query("SELECT COALESCE(AVG(dbs.closingBalance), 0) FROM DailyBalanceSnapshot dbs "
            + "WHERE dbs.tenantId = :tenantId AND dbs.accountId = :accountId "
            + "AND dbs.businessDate BETWEEN :fromDate AND :toDate")
    BigDecimal findAvgBalanceInPeriod(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * Count of snapshot days in a period (for average calculation denominator).
     */
    @Query("SELECT COUNT(dbs) FROM DailyBalanceSnapshot dbs "
            + "WHERE dbs.tenantId = :tenantId AND dbs.accountId = :accountId "
            + "AND dbs.businessDate BETWEEN :fromDate AND :toDate")
    long countSnapshotsInPeriod(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}

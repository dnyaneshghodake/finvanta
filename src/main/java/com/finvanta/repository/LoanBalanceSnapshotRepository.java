package com.finvanta.repository;

import com.finvanta.domain.entity.LoanBalanceSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Loan Balance Snapshot Repository per Finacle ACCT_BAL_HIST.
 *
 * Provides queries for:
 * - Balance on a specific date (customer dispute resolution)
 * - Sectoral exposure reporting (RBI CRILC)
 * - NPA trend analysis (DPD progression over time)
 * - Provisioning adequacy audit
 */
@Repository
public interface LoanBalanceSnapshotRepository extends JpaRepository<LoanBalanceSnapshot, Long> {

    /** Check if snapshot already exists for a loan account on a date (idempotency) */
    boolean existsByTenantIdAndAccountIdAndBusinessDate(String tenantId, Long accountId, LocalDate businessDate);

    /** Get snapshot for a specific loan account on a specific date */
    Optional<LoanBalanceSnapshot> findByTenantIdAndAccountIdAndBusinessDate(
            String tenantId, Long accountId, LocalDate businessDate);

    /**
     * Total outstanding principal across all loan accounts on a specific date.
     * Used for sectoral exposure reporting per RBI CRILC.
     */
    @Query("SELECT COALESCE(SUM(lbs.outstandingPrincipal), 0) FROM LoanBalanceSnapshot lbs "
            + "WHERE lbs.tenantId = :tenantId AND lbs.businessDate = :businessDate")
    BigDecimal totalOutstandingPrincipalOnDate(
            @Param("tenantId") String tenantId, @Param("businessDate") LocalDate businessDate);

    /**
     * Total NPA outstanding on a specific date (for historical NPA ratio computation).
     */
    @Query("SELECT COALESCE(SUM(lbs.outstandingPrincipal), 0) FROM LoanBalanceSnapshot lbs "
            + "WHERE lbs.tenantId = :tenantId AND lbs.businessDate = :businessDate "
            + "AND lbs.loanStatus IN ('NPA_SUBSTANDARD', 'NPA_DOUBTFUL', 'NPA_LOSS')")
    BigDecimal totalNpaOutstandingOnDate(
            @Param("tenantId") String tenantId, @Param("businessDate") LocalDate businessDate);

    /** Count snapshots for a date (for batch progress tracking) */
    long countByTenantIdAndBusinessDate(String tenantId, LocalDate businessDate);
}

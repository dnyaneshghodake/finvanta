package com.finvanta.repository;

import com.finvanta.domain.entity.FixedDeposit;
import com.finvanta.domain.enums.FdStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Fixed Deposit Repository per Finacle TD_MASTER / Temenos FIXED.DEPOSIT.
 */
@Repository
public interface FixedDepositRepository
        extends JpaRepository<FixedDeposit, Long> {

    Optional<FixedDeposit> findByTenantIdAndFdAccountNumber(
            String tenantId, String fdAccountNumber);

    List<FixedDeposit> findByTenantIdAndCustomerId(
            String tenantId, Long customerId);

    List<FixedDeposit> findByTenantIdAndStatus(
            String tenantId, FdStatus status);

    List<FixedDeposit> findByTenantIdAndBranchIdAndStatusNot(
            String tenantId, Long branchId,
            FdStatus excludedStatus);

    /** FDs maturing on a specific date (EOD maturity processing) */
    @Query("SELECT fd FROM FixedDeposit fd "
            + "WHERE fd.tenantId = :tenantId "
            + "AND fd.maturityDate <= :bizDate "
            + "AND fd.status = :activeStatus")
    List<FixedDeposit> findMaturingOnOrBefore(
            @Param("tenantId") String tenantId,
            @Param("bizDate") LocalDate bizDate,
            @Param("activeStatus") FdStatus activeStatus);

    /** Active FDs for daily interest accrual (EOD) */
    @Query("SELECT fd FROM FixedDeposit fd "
            + "WHERE fd.tenantId = :tenantId "
            + "AND fd.status = 'ACTIVE'")
    List<FixedDeposit> findActiveForAccrual(
            @Param("tenantId") String tenantId);

    /** FDs with interest payout due (monthly/quarterly) */
    @Query("SELECT fd FROM FixedDeposit fd "
            + "WHERE fd.tenantId = :tenantId "
            + "AND fd.status = 'ACTIVE' "
            + "AND fd.interestPayoutMode = :payoutMode "
            + "AND fd.accruedInterest > 0")
    List<FixedDeposit> findPayoutDue(
            @Param("tenantId") String tenantId,
            @Param("payoutMode") String payoutMode);

    /** Total FD deposits for dashboard */
    @Query("SELECT COALESCE(SUM(fd.currentPrincipal), 0) "
            + "FROM FixedDeposit fd "
            + "WHERE fd.tenantId = :tenantId "
            + "AND fd.status = 'ACTIVE'")
    BigDecimal calculateTotalFdDeposits(
            @Param("tenantId") String tenantId);

    /** Count active FDs */
    long countByTenantIdAndStatus(
            String tenantId, FdStatus status);
}

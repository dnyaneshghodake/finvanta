package com.finvanta.repository;

import com.finvanta.domain.entity.RecurringDeposit;
import com.finvanta.domain.enums.RdStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CBS Recurring Deposit Repository per Finacle RD_MASTER.
 *
 * Per RBI: RD installments must be auto-debited from linked CASA on due date.
 * This repository provides queries for installment processing, maturity
 * identification, and defaulted account detection.
 */
public interface RecurringDepositRepository extends JpaRepository<RecurringDeposit, Long> {

    Optional<RecurringDeposit> findByTenantIdAndRdAccountNumber(
            String tenantId, String rdAccountNumber);

    List<RecurringDeposit> findByTenantIdAndCustomerId(
            String tenantId, Long customerId);

    /** Active RDs with installment due on or before the given date */
    @Query("SELECT rd FROM RecurringDeposit rd "
            + "WHERE rd.tenantId = :tenantId AND rd.status = 'ACTIVE' "
            + "AND rd.nextInstallmentDate <= :businessDate "
            + "ORDER BY rd.nextInstallmentDate ASC")
    List<RecurringDeposit> findDueInstallments(
            @Param("tenantId") String tenantId,
            @Param("businessDate") LocalDate businessDate);

    /** Active RDs that have reached maturity date */
    @Query("SELECT rd FROM RecurringDeposit rd "
            + "WHERE rd.tenantId = :tenantId AND rd.status = 'ACTIVE' "
            + "AND rd.maturityDate <= :businessDate "
            + "ORDER BY rd.maturityDate ASC")
    List<RecurringDeposit> findMaturedRds(
            @Param("tenantId") String tenantId,
            @Param("businessDate") LocalDate businessDate);

    /** Active RDs for daily interest accrual */
    @Query("SELECT rd FROM RecurringDeposit rd "
            + "WHERE rd.tenantId = :tenantId AND rd.status = 'ACTIVE' "
            + "AND rd.cumulativeDeposit > 0")
    List<RecurringDeposit> findActiveForAccrual(
            @Param("tenantId") String tenantId);

    /** RDs with 3+ consecutive missed installments */
    @Query("SELECT rd FROM RecurringDeposit rd "
            + "WHERE rd.tenantId = :tenantId AND rd.status = 'ACTIVE' "
            + "AND rd.missedInstallments >= 3")
    List<RecurringDeposit> findDefaultCandidates(
            @Param("tenantId") String tenantId);

    long countByTenantIdAndStatus(String tenantId, RdStatus status);
}

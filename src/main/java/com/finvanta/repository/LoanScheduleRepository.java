package com.finvanta.repository;

import com.finvanta.domain.entity.LoanSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * CBS Loan Schedule Repository per Finacle/Temenos amortization standards.
 */
@Repository
public interface LoanScheduleRepository extends JpaRepository<LoanSchedule, Long> {

    /** Full schedule for a loan account, ordered by installment number */
    List<LoanSchedule> findByTenantIdAndLoanAccountIdOrderByInstallmentNumberAsc(
        String tenantId, Long loanAccountId);

    /** Next unpaid installment (for repayment allocation) */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId " +
           "AND ls.loanAccount.id = :accountId AND ls.status != 'PAID' " +
           "ORDER BY ls.installmentNumber ASC")
    List<LoanSchedule> findUnpaidInstallments(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId);

    /** Overdue installments as of a business date (for DPD/NPA calculation) */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId " +
           "AND ls.loanAccount.id = :accountId AND ls.dueDate < :businessDate " +
           "AND ls.status != 'PAID' ORDER BY ls.installmentNumber ASC")
    List<LoanSchedule> findOverdueInstallments(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId,
        @Param("businessDate") LocalDate businessDate);

    /** All installments due on or before a date across all accounts (for EOD batch) */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId " +
           "AND ls.dueDate <= :businessDate AND ls.status = 'SCHEDULED'")
    List<LoanSchedule> findScheduledInstallmentsDueBy(
        @Param("tenantId") String tenantId,
        @Param("businessDate") LocalDate businessDate);

    /** Count overdue installments for a loan (for DPD calculation) */
    @Query("SELECT COUNT(ls) FROM LoanSchedule ls WHERE ls.tenantId = :tenantId " +
           "AND ls.loanAccount.id = :accountId AND ls.dueDate < :businessDate " +
           "AND ls.status != 'PAID'")
    long countOverdueInstallments(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId,
        @Param("businessDate") LocalDate businessDate);

    /** Check if schedule already exists for a loan account */
    boolean existsByTenantIdAndLoanAccountId(String tenantId, Long loanAccountId);
}
package com.finvanta.repository;

import com.finvanta.domain.entity.LoanSchedule;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Loan Schedule Repository per Finacle/Temenos amortization standards.
 */
@Repository
public interface LoanScheduleRepository extends JpaRepository<LoanSchedule, Long> {

    /** Full schedule for a loan account, ordered by installment number */
    List<LoanSchedule> findByTenantIdAndLoanAccountIdOrderByInstallmentNumberAsc(String tenantId, Long loanAccountId);

    /** Next unpaid installment (for repayment allocation) */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId "
            + "AND ls.loanAccount.id = :accountId AND ls.status != 'PAID' "
            + "ORDER BY ls.installmentNumber ASC")
    List<LoanSchedule> findUnpaidInstallments(@Param("tenantId") String tenantId, @Param("accountId") Long accountId);

    /** Overdue installments as of a business date (for DPD/NPA calculation) */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId "
            + "AND ls.loanAccount.id = :accountId AND ls.dueDate < :businessDate "
            + "AND ls.status != 'PAID' ORDER BY ls.installmentNumber ASC")
    List<LoanSchedule> findOverdueInstallments(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("businessDate") LocalDate businessDate);

    /** All installments due on or before a date across all accounts (for EOD batch) */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId "
            + "AND ls.dueDate <= :businessDate AND ls.status = 'SCHEDULED'")
    List<LoanSchedule> findScheduledInstallmentsDueBy(
            @Param("tenantId") String tenantId, @Param("businessDate") LocalDate businessDate);

    /** Count overdue installments for a loan (for DPD calculation) */
    @Query("SELECT COUNT(ls) FROM LoanSchedule ls WHERE ls.tenantId = :tenantId "
            + "AND ls.loanAccount.id = :accountId AND ls.dueDate < :businessDate "
            + "AND ls.status != 'PAID'")
    long countOverdueInstallments(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("businessDate") LocalDate businessDate);

    /** Check if schedule already exists for a loan account */
    boolean existsByTenantIdAndLoanAccountId(String tenantId, Long loanAccountId);

    /**
     * Future unpaid installments for restructuring schedule regeneration.
     * Returns SCHEDULED and OVERDUE installments with due date on or after the given date.
     * PAID and PARTIALLY_PAID installments are preserved (historical record).
     * CANCELLED installments are excluded (already processed by prior restructuring).
     */
    @Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId "
            + "AND ls.loanAccount.id = :accountId AND ls.dueDate >= :fromDate "
            + "AND ls.status IN ('SCHEDULED', 'OVERDUE') "
            + "ORDER BY ls.installmentNumber ASC")
    List<LoanSchedule> findFutureUnpaidInstallments(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("fromDate") LocalDate fromDate);

    /**
     * Maximum installment number for a loan account.
     * Used by regenerateSchedule() to continue numbering after cancelled installments.
     * Returns 0 if no installments exist.
     */
    @Query("SELECT COALESCE(MAX(ls.installmentNumber), 0) FROM LoanSchedule ls "
            + "WHERE ls.tenantId = :tenantId AND ls.loanAccount.id = :accountId")
    int findMaxInstallmentNumber(@Param("tenantId") String tenantId, @Param("accountId") Long accountId);
}

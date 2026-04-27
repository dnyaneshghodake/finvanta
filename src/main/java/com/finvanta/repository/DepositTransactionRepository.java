package com.finvanta.repository;

import com.finvanta.domain.entity.DepositTransaction;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Deposit Transaction Repository per Finacle TRAN_DETAIL / Temenos STMT.ENTRY.
 */
@Repository
public interface DepositTransactionRepository extends JpaRepository<DepositTransaction, Long> {

    Optional<DepositTransaction> findByTenantIdAndTransactionRef(String tenantId, String transactionRef);

    /**
     * CBS Reversal Safety: acquire PESSIMISTIC_WRITE lock on the transaction row before
     * evaluating the isReversed flag. Closes the TOCTOU window where two concurrent
     * reversal requests could both observe isReversed=false and double-reverse.
     * 30s lock timeout per CBS TRAN_LOCK standard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT dt FROM DepositTransaction dt WHERE dt.tenantId = :tenantId "
            + "AND dt.transactionRef = :transactionRef")
    Optional<DepositTransaction> findAndLockByTenantIdAndTransactionRef(
            @Param("tenantId") String tenantId, @Param("transactionRef") String transactionRef);

    List<DepositTransaction> findByTenantIdAndDepositAccountIdOrderByPostingDateDesc(
            String tenantId, Long depositAccountId);

    /**
     * Mini statement: last N transactions.
     *
     * <p>JOIN FETCH dt.depositAccount so downstream mappers (e.g. {@code AccountMapper.toTxnResponse})
     * can read the account number without triggering LazyInitializationException once the
     * read-only service transaction closes. OSIV is disabled cluster-wide; every query whose
     * result is surfaced to a controller MUST eager-fetch the relations the mapper touches.
     */
    @Query("SELECT dt FROM DepositTransaction dt JOIN FETCH dt.depositAccount "
            + "WHERE dt.tenantId = :tenantId "
            + "AND dt.depositAccount.id = :accountId ORDER BY dt.postingDate DESC")
    List<DepositTransaction> findRecentTransactions(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            org.springframework.data.domain.Pageable pageable);

    /** Statement for date range */
    @Query("SELECT dt FROM DepositTransaction dt WHERE dt.tenantId = :tenantId "
            + "AND dt.depositAccount.id = :accountId "
            + "AND dt.valueDate BETWEEN :fromDate AND :toDate "
            + "ORDER BY dt.postingDate ASC")
    List<DepositTransaction> findByDateRange(
            @Param("tenantId") String tenantId,
            @Param("accountId") Long accountId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** Daily debit sum for daily withdrawal limit check */
    @Query("SELECT COALESCE(SUM(dt.amount), 0) FROM DepositTransaction dt "
            + "WHERE dt.tenantId = :tenantId AND dt.depositAccount.id = :accountId "
            + "AND dt.valueDate = :date AND dt.debitCredit = 'DEBIT' AND dt.reversed = false")
    BigDecimal sumDailyDebits(
            @Param("tenantId") String tenantId, @Param("accountId") Long accountId, @Param("date") LocalDate date);

    /**
     * Daily transfer debit sum for daily transfer limit check.
     * Per Finacle ACCTLIMIT: transfer limits are independent of withdrawal limits.
     * Only counts TRANSFER_DEBIT transactions (not CASH_WITHDRAWAL, CHARGE_DEBIT, etc.).
     * Reversed transfers are excluded to prevent limit inflation from reversal cycles.
     */
    @Query("SELECT COALESCE(SUM(dt.amount), 0) FROM DepositTransaction dt "
            + "WHERE dt.tenantId = :tenantId AND dt.depositAccount.id = :accountId "
            + "AND dt.valueDate = :date AND dt.transactionType = 'TRANSFER_DEBIT' AND dt.reversed = false")
    BigDecimal sumDailyTransferDebits(
            @Param("tenantId") String tenantId, @Param("accountId") Long accountId, @Param("date") LocalDate date);

    /**
     * CBS Daily Aggregate: sum of all non-reversed deposit transactions by a user on a date.
     * Used by TransactionLimitService for cross-module daily aggregate limit validation.
     * Per Finacle TRAN_AUTH / RBI Internal Controls: daily aggregate limits must span
     * ALL financial modules (Loan + Deposit) to prevent limit bypass via module splitting.
     */
    @Query("SELECT COALESCE(SUM(dt.amount), 0) FROM DepositTransaction dt "
            + "WHERE dt.tenantId = :tenantId AND dt.createdBy = :username "
            + "AND dt.valueDate = :valueDate AND dt.reversed = false")
    BigDecimal sumDailyAmountByUser(
            @Param("tenantId") String tenantId,
            @Param("username") String username,
            @Param("valueDate") LocalDate valueDate);

    /** Idempotency check */
    Optional<DepositTransaction> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    /**
     * CBS Transaction 360: lookup by voucher number (VCH/...).
     * Returns List (not Optional) because fund transfers create TWO deposit transactions
     * (TRANSFER_DEBIT + TRANSFER_CREDIT) sharing the same voucherNumber from the single
     * TransactionEngine.execute() call. Using Optional would cause NonUniqueResultException.
     * Per Finacle TRAN_DETAIL: a single voucher can cover multiple subledger entries.
     * Callers should use getFirst() or stream().findFirst() for single-result contexts.
     */
    List<DepositTransaction> findByTenantIdAndVoucherNumber(String tenantId, String voucherNumber);

    /**
     * CBS Transaction 360: lookup by journal entry ID.
     * Returns List (not Optional) because fund transfers create TWO deposit transactions
     * (TRANSFER_DEBIT + TRANSFER_CREDIT) sharing the same journalEntryId.
     * Using Optional would cause NonUniqueResultException at runtime.
     * Per Finacle TRAN_DETAIL: a single journal entry can have multiple subledger entries.
     */
    List<DepositTransaction> findByTenantIdAndJournalEntryId(String tenantId, Long journalEntryId);

    /** All deposit transactions for a business date. JOIN FETCH for voucher register JSP (OSIV disabled). */
    @Query("SELECT dt FROM DepositTransaction dt JOIN FETCH dt.depositAccount JOIN FETCH dt.branch "
            + "WHERE dt.tenantId = :tenantId "
            + "AND dt.valueDate = :date ORDER BY dt.postingDate ASC")
    List<DepositTransaction> findByTenantIdAndValueDate(
            @Param("tenantId") String tenantId, @Param("date") LocalDate date);
}

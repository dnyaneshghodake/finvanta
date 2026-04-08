package com.finvanta.repository;

import com.finvanta.domain.entity.DepositTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * CBS Deposit Transaction Repository per Finacle TRAN_DETAIL / Temenos STMT.ENTRY.
 */
@Repository
public interface DepositTransactionRepository extends JpaRepository<DepositTransaction, Long> {

    Optional<DepositTransaction> findByTenantIdAndTransactionRef(String tenantId, String transactionRef);

    List<DepositTransaction> findByTenantIdAndDepositAccountIdOrderByPostingDateDesc(
        String tenantId, Long depositAccountId);

    /** Mini statement: last N transactions */
    @Query("SELECT dt FROM DepositTransaction dt WHERE dt.tenantId = :tenantId " +
           "AND dt.depositAccount.id = :accountId ORDER BY dt.postingDate DESC")
    List<DepositTransaction> findRecentTransactions(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId,
        org.springframework.data.domain.Pageable pageable);

    /** Statement for date range */
    @Query("SELECT dt FROM DepositTransaction dt WHERE dt.tenantId = :tenantId " +
           "AND dt.depositAccount.id = :accountId " +
           "AND dt.valueDate BETWEEN :fromDate AND :toDate " +
           "ORDER BY dt.postingDate ASC")
    List<DepositTransaction> findByDateRange(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate);

    /** Daily debit sum for daily withdrawal limit check */
    @Query("SELECT COALESCE(SUM(dt.amount), 0) FROM DepositTransaction dt " +
           "WHERE dt.tenantId = :tenantId AND dt.depositAccount.id = :accountId " +
           "AND dt.valueDate = :date AND dt.debitCredit = 'DEBIT' AND dt.reversed = false")
    BigDecimal sumDailyDebits(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId,
        @Param("date") LocalDate date);

    /** Idempotency check */
    Optional<DepositTransaction> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    /** All deposit transactions for a business date (for voucher register / daily report) */
    @Query("SELECT dt FROM DepositTransaction dt WHERE dt.tenantId = :tenantId " +
           "AND dt.valueDate = :date ORDER BY dt.postingDate ASC")
    List<DepositTransaction> findByTenantIdAndValueDate(
        @Param("tenantId") String tenantId,
        @Param("date") LocalDate date);
}

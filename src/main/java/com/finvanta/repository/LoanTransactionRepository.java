package com.finvanta.repository;

import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.domain.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanTransactionRepository extends JpaRepository<LoanTransaction, Long> {

    Optional<LoanTransaction> findByTenantIdAndTransactionRef(String tenantId, String transactionRef);

    List<LoanTransaction> findByTenantIdAndLoanAccountIdOrderByPostingDateDesc(String tenantId, Long loanAccountId);

    List<LoanTransaction> findByTenantIdAndLoanAccountIdAndTransactionType(
        String tenantId, Long loanAccountId, TransactionType transactionType
    );

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM LoanTransaction t " +
           "WHERE t.tenantId = :tenantId AND t.loanAccount.id = :accountId " +
           "AND t.transactionType = :type AND t.reversed = false")
    BigDecimal sumByAccountAndType(
        @Param("tenantId") String tenantId,
        @Param("accountId") Long accountId,
        @Param("type") TransactionType type
    );

    @Query("SELECT t FROM LoanTransaction t WHERE t.tenantId = :tenantId " +
           "AND t.valueDate BETWEEN :fromDate AND :toDate ORDER BY t.postingDate")
    List<LoanTransaction> findByTenantIdAndValueDateBetween(
        @Param("tenantId") String tenantId,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate
    );

    /** CBS Transaction 360: lookup by voucher number for branch-level reconciliation */
    Optional<LoanTransaction> findByTenantIdAndVoucherNumber(String tenantId, String voucherNumber);

    /** CBS Transaction 360: find transactions linked to a specific journal entry */
    List<LoanTransaction> findByTenantIdAndJournalEntryId(String tenantId, Long journalEntryId);

    /**
     * CBS Idempotency lookup: find existing transaction by client-supplied key.
     * Per Finacle UNIQUE.REF pattern, if a transaction with this idempotency key
     * already exists, the original result is returned instead of creating a duplicate.
     */
    Optional<LoanTransaction> findByTenantIdAndIdempotencyKey(
        String tenantId, String idempotencyKey);

    /**
     * CBS Daily Aggregate: sum of all non-reversed transactions by a user on a date.
     * Used by TransactionLimitService for daily aggregate limit validation.
     * Per Finacle/Temenos internal controls, daily aggregate limits prevent
     * a single user from processing excessive amounts in one business day.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM LoanTransaction t " +
           "WHERE t.tenantId = :tenantId AND t.createdBy = :username " +
           "AND t.valueDate = :valueDate AND t.reversed = false")
    BigDecimal sumDailyAmountByUser(
        @Param("tenantId") String tenantId,
        @Param("username") String username,
        @Param("valueDate") LocalDate valueDate);
}

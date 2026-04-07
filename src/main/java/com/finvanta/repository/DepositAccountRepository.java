package com.finvanta.repository;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.enums.DepositAccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * CBS Deposit Account Repository per Finacle CUSTACCT / Temenos ACCOUNT standards.
 */
@Repository
public interface DepositAccountRepository extends JpaRepository<DepositAccount, Long> {

    Optional<DepositAccount> findByTenantIdAndAccountNumber(String tenantId, String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId AND da.accountNumber = :accountNumber")
    Optional<DepositAccount> findAndLockByTenantIdAndAccountNumber(
        @Param("tenantId") String tenantId,
        @Param("accountNumber") String accountNumber);

    /** All ACTIVE deposit accounts for a tenant (for EOD interest accrual — ACTIVE only) */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId AND da.accountStatus = 'ACTIVE'")
    List<DepositAccount> findAllActiveAccounts(@Param("tenantId") String tenantId);

    /**
     * All non-closed deposit accounts for a tenant (for account list page).
     * Per Finacle CUSTACCT: the account list shows all accounts in any operational state
     * (PENDING_ACTIVATION, ACTIVE, DORMANT, FROZEN) — only CLOSED accounts are excluded.
     * This is distinct from findAllActiveAccounts which is used by EOD and returns ACTIVE only.
     */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId AND da.accountStatus <> 'CLOSED' ORDER BY da.accountNumber")
    List<DepositAccount> findAllNonClosedAccounts(@Param("tenantId") String tenantId);

    /** Active savings accounts (for interest accrual -- current accounts have 0% rate) */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId " +
           "AND da.accountStatus = 'ACTIVE' AND da.accountType LIKE 'SAVINGS%' AND da.interestRate > 0")
    List<DepositAccount> findActiveSavingsAccounts(@Param("tenantId") String tenantId);

    /** Accounts by customer */
    List<DepositAccount> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    /** Accounts by branch (for branch isolation) */
    List<DepositAccount> findByTenantIdAndBranchId(String tenantId, Long branchId);

    /** Active accounts by branch */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId " +
           "AND da.branch.id = :branchId AND da.accountStatus NOT IN ('CLOSED')")
    List<DepositAccount> findActiveByBranch(
        @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /** Dormancy candidates: active accounts with no transaction for 2+ years */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId " +
           "AND da.accountStatus = 'ACTIVE' AND da.lastTransactionDate < :cutoffDate")
    List<DepositAccount> findDormancyCandidates(
        @Param("tenantId") String tenantId, @Param("cutoffDate") LocalDate cutoffDate);

    /** Total deposit balance for dashboard */
    @Query("SELECT COALESCE(SUM(da.ledgerBalance), 0) FROM DepositAccount da " +
           "WHERE da.tenantId = :tenantId AND da.accountStatus NOT IN ('CLOSED')")
    BigDecimal calculateTotalDeposits(@Param("tenantId") String tenantId);

    /** Count by account type (for CASA ratio) */
    @Query("SELECT da.accountType, COUNT(da) FROM DepositAccount da " +
           "WHERE da.tenantId = :tenantId AND da.accountStatus NOT IN ('CLOSED') GROUP BY da.accountType")
    List<Object[]> countByAccountType(@Param("tenantId") String tenantId);

    long countByTenantIdAndAccountStatusNot(String tenantId, DepositAccountStatus status);
}

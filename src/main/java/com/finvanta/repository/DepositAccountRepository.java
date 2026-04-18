package com.finvanta.repository;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.enums.DepositAccountStatus;

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
 * CBS Deposit Account Repository per Finacle CUSTACCT / Temenos ACCOUNT standards.
 */
@Repository
public interface DepositAccountRepository extends JpaRepository<DepositAccount, Long> {

    Optional<DepositAccount> findByTenantIdAndAccountNumber(String tenantId, String accountNumber);

    /** CBS Tier-1: 30s lock timeout per Finacle ACCT_LOCK. Prevents indefinite blocking
     *  when concurrent postings contend on the same account (e.g., deposit + withdrawal). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId AND da.accountNumber = :accountNumber")
    Optional<DepositAccount> findAndLockByTenantIdAndAccountNumber(
            @Param("tenantId") String tenantId, @Param("accountNumber") String accountNumber);

    /** All ACTIVE deposit accounts for a tenant (for EOD interest accrual — ACTIVE only).
     *  JOIN FETCH customer+branch so JSP views can access a.customer.firstName, a.branch.branchCode
     *  without LazyInitializationException (OSIV is disabled per Tier-1 CBS standards). */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId AND da.accountStatus = 'ACTIVE'")
    List<DepositAccount> findAllActiveAccounts(@Param("tenantId") String tenantId);

    /**
     * All non-closed deposit accounts for a tenant (for account list page).
     * Per Finacle CUSTACCT: the account list shows all accounts in any operational state
     * (PENDING_ACTIVATION, ACTIVE, DORMANT, FROZEN) — only CLOSED accounts are excluded.
     * This is distinct from findAllActiveAccounts which is used by EOD and returns ACTIVE only.
     * JOIN FETCH customer+branch for JSP rendering (OSIV disabled).
     */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId AND da.accountStatus <> 'CLOSED' ORDER BY da.accountNumber")
    List<DepositAccount> findAllNonClosedAccounts(@Param("tenantId") String tenantId);

    /**
     * Savings accounts eligible for interest accrual (ACTIVE, DORMANT, FROZEN).
     * Per RBI directive: DORMANT accounts continue to accrue interest — dormancy does
     * not forfeit the customer's right to interest on their balance.
     * Per PMLA / RBI Freeze Guidelines: FROZEN accounts continue to accrue interest —
     * freeze ≠ forfeiture. The freeze only restricts debit/credit operations.
     * Current accounts have 0% rate so are excluded by the interestRate > 0 filter.
     * Per Finacle PDDEF: uses enum-based type filter instead of LIKE 'SAVINGS%' string match.
     */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus IN ('ACTIVE', 'DORMANT', 'FROZEN') "
            + "AND da.accountType IN ('SAVINGS', 'SAVINGS_NRI', 'SAVINGS_MINOR', 'SAVINGS_JOINT', 'SAVINGS_PMJDY') "
            + "AND da.interestRate > 0")
    List<DepositAccount> findActiveSavingsAccounts(@Param("tenantId") String tenantId);

    /** Accounts by product code (for product active account count) */
    List<DepositAccount> findByTenantIdAndProductCode(String tenantId, String productCode);

    /** CBS: DB-level COUNT for product active account check — avoids loading entire deposit list into memory */
    @Query("SELECT COUNT(da) FROM DepositAccount da WHERE da.tenantId = :tenantId "
            + "AND da.productCode = :productCode AND da.accountStatus <> 'CLOSED'")
    long countNonClosedByProductCode(@Param("tenantId") String tenantId, @Param("productCode") String productCode);

    // === CBS ACCTINQ: CASA Account Search per Finacle ACCTINQ / Temenos ACCOUNT.ENQUIRY ===

    /**
     * Search CASA accounts by account number, customer name, or customer CIF.
     * Per Finacle ACCTINQ: branch staff must locate accounts instantly for
     * teller operations, customer complaints, and RBI inspection queries.
     * All branches visible (ADMIN/AUDITOR). Branch-scoped variant below.
     *
     * CBS: Joins to Customer entity for name/CIF search. Account number and
     * customer number use exact-prefix LIKE; names use case-insensitive LIKE.
     * PAN search is NOT included — PAN is encrypted, use Customer CIF_SEARCH instead.
     */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus <> 'CLOSED' AND ("
            + "da.accountNumber LIKE CONCAT('%', :query, '%') OR "
            + "da.customer.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "LOWER(da.customer.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(da.customer.lastName) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY da.accountNumber")
    List<DepositAccount> searchAccounts(
            @Param("tenantId") String tenantId, @Param("query") String query,
            org.springframework.data.domain.Pageable pageable);

    /** Convenience overload — default max 500 results to prevent OOM at scale */
    default List<DepositAccount> searchAccounts(String tenantId, String query) {
        return searchAccounts(tenantId, query, org.springframework.data.domain.PageRequest.of(0, 500));
    }

    /** Branch-scoped CASA search for MAKER/CHECKER per Finacle BRANCH_CONTEXT.
     *  JOIN FETCH for JSP rendering (OSIV disabled). */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.branch.id = :branchId AND da.accountStatus <> 'CLOSED' AND ("
            + "da.accountNumber LIKE CONCAT('%', :query, '%') OR "
            + "da.customer.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "LOWER(da.customer.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(da.customer.lastName) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY da.accountNumber")
    List<DepositAccount> searchAccountsByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId,
            @Param("query") String query, org.springframework.data.domain.Pageable pageable);

    /** Convenience overload — default max 500 results */
    default List<DepositAccount> searchAccountsByBranch(String tenantId, Long branchId, String query) {
        return searchAccountsByBranch(tenantId, branchId, query, org.springframework.data.domain.PageRequest.of(0, 500));
    }

    /** Accounts by customer */
    List<DepositAccount> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    /** Accounts by branch (for branch isolation) */
    List<DepositAccount> findByTenantIdAndBranchId(String tenantId, Long branchId);

    /** Active accounts by branch. JOIN FETCH for JSP rendering (OSIV disabled). */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.branch.id = :branchId AND da.accountStatus NOT IN ('CLOSED')")
    List<DepositAccount> findActiveByBranch(@Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /** Dormancy candidates: active accounts with no transaction for 2+ years */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus = 'ACTIVE' AND da.lastTransactionDate < :cutoffDate")
    List<DepositAccount> findDormancyCandidates(
            @Param("tenantId") String tenantId, @Param("cutoffDate") LocalDate cutoffDate);

    /**
     * INOPERATIVE candidates: DORMANT accounts with no transaction for 10+ years.
     * Per RBI Unclaimed Deposits Direction 2024: DORMANT (2yr) → INOPERATIVE (10yr).
     * This query correctly filters for accountStatus = 'DORMANT' (not 'ACTIVE'),
     * unlike findDormancyCandidates which only returns ACTIVE accounts.
     */
    @Query("SELECT da FROM DepositAccount da WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus = 'DORMANT' AND da.lastTransactionDate < :cutoffDate")
    List<DepositAccount> findInoperativeCandidates(
            @Param("tenantId") String tenantId, @Param("cutoffDate") LocalDate cutoffDate);

    /** Total deposit balance for dashboard */
    @Query("SELECT COALESCE(SUM(da.ledgerBalance), 0) FROM DepositAccount da "
            + "WHERE da.tenantId = :tenantId AND da.accountStatus NOT IN ('CLOSED')")
    BigDecimal calculateTotalDeposits(@Param("tenantId") String tenantId);

    /** Count by account type (for CASA ratio) */
    @Query("SELECT da.accountType, COUNT(da) FROM DepositAccount da "
            + "WHERE da.tenantId = :tenantId AND da.accountStatus NOT IN ('CLOSED') GROUP BY da.accountType")
    List<Object[]> countByAccountType(@Param("tenantId") String tenantId);

    long countByTenantIdAndAccountStatusNot(String tenantId, DepositAccountStatus status);

    // === CASA Pipeline Queries (per Finacle ACCTOPN workflow stages) ===

    /** Stage 1: Accounts pending activation. JOIN FETCH for JSP (OSIV disabled). */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus = 'PENDING_ACTIVATION' ORDER BY da.createdAt DESC")
    List<DepositAccount> findPendingActivation(@Param("tenantId") String tenantId);

    /** Stage 2: Active accounts (operational). JOIN FETCH for JSP (OSIV disabled). */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus = 'ACTIVE' ORDER BY da.accountNumber")
    List<DepositAccount> findActiveAccounts(@Param("tenantId") String tenantId);

    /** Stage 3: Attention required accounts. JOIN FETCH for JSP (OSIV disabled). */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus IN ('DORMANT', 'FROZEN', 'INOPERATIVE') ORDER BY da.accountStatus, da.accountNumber")
    List<DepositAccount> findAttentionRequired(@Param("tenantId") String tenantId);

    // === CBS UDGAM: Unclaimed Deposits Reporting per RBI Direction 2024 ===

    /**
     * Find INOPERATIVE accounts for RBI UDGAM unclaimed deposits reporting.
     * Per RBI Unclaimed Deposits Direction 2024: accounts with no customer-initiated
     * transaction for 10+ years must be reported to the UDGAM portal.
     * Returns accounts with non-zero balance (zero-balance INOPERATIVE accounts
     * are not reportable — no funds to claim).
     */
    @Query("SELECT da FROM DepositAccount da JOIN FETCH da.customer JOIN FETCH da.branch "
            + "WHERE da.tenantId = :tenantId "
            + "AND da.accountStatus = 'INOPERATIVE' AND da.ledgerBalance > 0 "
            + "ORDER BY da.lastTransactionDate ASC")
    List<DepositAccount> findUnclaimedDeposits(@Param("tenantId") String tenantId);

    /**
     * Summary: total unclaimed deposit amount for regulatory reporting.
     */
    @Query("SELECT COALESCE(SUM(da.ledgerBalance), 0) FROM DepositAccount da "
            + "WHERE da.tenantId = :tenantId AND da.accountStatus = 'INOPERATIVE' AND da.ledgerBalance > 0")
    java.math.BigDecimal sumUnclaimedDepositBalance(@Param("tenantId") String tenantId);
}

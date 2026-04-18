package com.finvanta.repository;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanAccountRepository extends JpaRepository<LoanAccount, Long> {

    Optional<LoanAccount> findByTenantIdAndAccountNumber(String tenantId, String accountNumber);

    /** CBS Tier-1: 30s lock timeout per Finacle ACCT_LOCK standard. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.accountNumber = :accountNumber")
    Optional<LoanAccount> findAndLockByTenantIdAndAccountNumber(
            @Param("tenantId") String tenantId, @Param("accountNumber") String accountNumber);

    List<LoanAccount> findByTenantIdAndStatus(String tenantId, LoanStatus status);

    List<LoanAccount> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    @Query(
            "SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF') AND la.daysPastDue >= :threshold")
    List<LoanAccount> findNpaCandidates(@Param("tenantId") String tenantId, @Param("threshold") int threshold);

    /** JOIN FETCH customer+branch for JSP rendering (OSIV disabled). */
    @Query("SELECT la FROM LoanAccount la JOIN FETCH la.customer JOIN FETCH la.branch "
            + "WHERE la.tenantId = :tenantId AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF')")
    List<LoanAccount> findAllActiveAccounts(@Param("tenantId") String tenantId);

    /** CBS: DB-level COUNT for product active account check — avoids loading entire portfolio into memory */
    @Query("SELECT COUNT(la) FROM LoanAccount la WHERE la.tenantId = :tenantId "
            + "AND la.productType = :productType AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF')")
    long countActiveByProductType(@Param("tenantId") String tenantId, @Param("productType") String productType);

    @Query(
            "SELECT COALESCE(SUM(la.outstandingPrincipal), 0) FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF')")
    BigDecimal calculateTotalOutstandingPrincipal(@Param("tenantId") String tenantId);

    long countByTenantIdAndStatus(String tenantId, LoanStatus status);

    /**
     * CBS Dashboard: Count accounts grouped by status in a single query.
     * Eliminates N+1 problem of calling countByTenantIdAndStatus() per status.
     * Returns List of [LoanStatus, Long] arrays.
     */
    @Query("SELECT la.status, COUNT(la) FROM LoanAccount la WHERE la.tenantId = :tenantId GROUP BY la.status")
    List<Object[]> countByTenantIdGroupByStatus(@Param("tenantId") String tenantId);

    boolean existsByTenantIdAndApplicationId(String tenantId, Long applicationId);

    @Query(
            "SELECT COALESCE(SUM(la.outstandingPrincipal), 0) FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.branch.id = :branchId AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF')")
    java.math.BigDecimal calculateTotalOutstandingByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /** CBS Branch Portfolio: all loan accounts at a specific branch */
    List<LoanAccount> findByTenantIdAndBranchId(String tenantId, Long branchId);

    // === CBS LOANINQ: Loan Account Search per Finacle LOANINQ / Temenos AA.ARRANGEMENT.ENQUIRY ===

    /**
     * Search loan accounts by account number, customer name, or customer CIF.
     * Per Finacle LOANINQ: branch staff must locate loan accounts instantly for
     * repayment processing, NPA follow-up, and RBI inspection queries.
     * All branches visible (ADMIN/AUDITOR). Branch-scoped variant below.
     */
    @Query("SELECT la FROM LoanAccount la JOIN FETCH la.customer JOIN FETCH la.branch "
            + "WHERE la.tenantId = :tenantId "
            + "AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF') AND ("
            + "la.accountNumber LIKE CONCAT('%', :query, '%') OR "
            + "la.customer.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "LOWER(la.customer.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(la.customer.lastName) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY la.accountNumber")
    List<LoanAccount> searchAccounts(
            @Param("tenantId") String tenantId, @Param("query") String query,
            org.springframework.data.domain.Pageable pageable);

    /** Convenience overload — default max 500 results to prevent OOM at scale */
    default List<LoanAccount> searchAccounts(String tenantId, String query) {
        return searchAccounts(tenantId, query, org.springframework.data.domain.PageRequest.of(0, 500));
    }

    /** Branch-scoped loan search for MAKER/CHECKER. JOIN FETCH for JSP (OSIV disabled). */
    @Query("SELECT la FROM LoanAccount la JOIN FETCH la.customer JOIN FETCH la.branch "
            + "WHERE la.tenantId = :tenantId "
            + "AND la.branch.id = :branchId AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF') AND ("
            + "la.accountNumber LIKE CONCAT('%', :query, '%') OR "
            + "la.customer.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "LOWER(la.customer.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(la.customer.lastName) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY la.accountNumber")
    List<LoanAccount> searchAccountsByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId,
            @Param("query") String query, org.springframework.data.domain.Pageable pageable);

    /** Convenience overload — default max 500 results */
    default List<LoanAccount> searchAccountsByBranch(String tenantId, Long branchId, String query) {
        return searchAccountsByBranch(tenantId, branchId, query, org.springframework.data.domain.PageRequest.of(0, 500));
    }

    /** CBS Dashboard: Total NPA outstanding (Sub-Standard + Doubtful + Loss) */
    @Query(
            "SELECT COALESCE(SUM(la.outstandingPrincipal), 0) FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status IN ('NPA_SUBSTANDARD', 'NPA_DOUBTFUL', 'NPA_LOSS')")
    BigDecimal calculateTotalNpaOutstanding(@Param("tenantId") String tenantId);

    /** CBS Dashboard: Total provisioning held across all accounts */
    @Query(
            "SELECT COALESCE(SUM(la.provisioningAmount), 0) FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status NOT IN ('CLOSED', 'WRITTEN_OFF')")
    BigDecimal calculateTotalProvisioning(@Param("tenantId") String tenantId);
}

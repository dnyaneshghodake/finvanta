package com.finvanta.repository;

import com.finvanta.domain.entity.Customer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByTenantIdAndCustomerNumber(String tenantId, String customerNumber);

    /** JOIN FETCH branch for JSP rendering (OSIV disabled). customer/list.jsp accesses cust.branch.branchCode. */
    @Query("SELECT c FROM Customer c JOIN FETCH c.branch WHERE c.tenantId = :tenantId AND c.active = true")
    List<Customer> findByTenantIdAndActiveTrue(@Param("tenantId") String tenantId);

    boolean existsByTenantIdAndCustomerNumber(String tenantId, String customerNumber);

    List<Customer> findByTenantIdAndKycVerifiedFalse(String tenantId);

    long countByTenantIdAndActiveTrue(String tenantId);

    /** JOIN FETCH branch for JSP rendering (OSIV disabled). */
    @Query("SELECT c FROM Customer c JOIN FETCH c.branch WHERE c.tenantId = :tenantId AND c.branch.id = :branchId AND c.active = true")
    List<Customer> findByTenantIdAndBranchIdAndActiveTrue(@Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    // === P1 Gap 5.1: Customer Search (essential for branch operations) ===

    /**
     * Search customers by name (first or last), customer number, or mobile.
     * Per Finacle CIF_SEARCH: branch staff must be able to search by any identifier.
     * Uses LOWER() for case-insensitive matching.
     *
     * CBS CRITICAL: PAN search is NOT included in LIKE queries because PAN is encrypted
     * (AES-256-GCM with random IV). LIKE on ciphertext NEVER matches plaintext input.
     * For PAN-based lookup, use findByPanHash() with SHA-256 hash comparison.
     *
     * NOTE: This searches ALL branches — use searchCustomersByBranch for MAKER/CHECKER.
     */
    @Query("SELECT c FROM Customer c JOIN FETCH c.branch WHERE c.tenantId = :tenantId AND c.active = true AND ("
            + "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "c.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.mobileNumber LIKE CONCAT('%', :query, '%'))")
    List<Customer> searchCustomers(@Param("tenantId") String tenantId, @Param("query") String query);

    /**
     * Branch-isolated customer search per Finacle BRANCH_CONTEXT.
     * MAKER/CHECKER users can only search customers at their home branch.
     * Same search logic as searchCustomers but with branch filter.
     */
    @Query("SELECT c FROM Customer c JOIN FETCH c.branch WHERE c.tenantId = :tenantId AND c.active = true "
            + "AND c.branch.id = :branchId AND ("
            + "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "c.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.mobileNumber LIKE CONCAT('%', :query, '%'))")
    List<Customer> searchCustomersByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("query") String query);

    // === Paginated Search (per Finacle CIF_SEARCH / Temenos ENQUIRY) ===

    /** Paginated: all active customers for a tenant (ADMIN/AUDITOR) */
    Page<Customer> findByTenantIdAndActiveTrue(String tenantId, Pageable pageable);

    /** Paginated: active customers at a specific branch (MAKER/CHECKER) */
    Page<Customer> findByTenantIdAndBranchIdAndActiveTrue(String tenantId, Long branchId, Pageable pageable);

    /** Paginated: search all branches (ADMIN/AUDITOR) */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND c.active = true AND ("
            + "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "c.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.mobileNumber LIKE CONCAT('%', :query, '%'))")
    Page<Customer> searchCustomersPaged(
            @Param("tenantId") String tenantId, @Param("query") String query, Pageable pageable);

    /** Paginated: search within branch (MAKER/CHECKER) */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND c.active = true "
            + "AND c.branch.id = :branchId AND ("
            + "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "c.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.mobileNumber LIKE CONCAT('%', :query, '%'))")
    Page<Customer> searchCustomersByBranchPaged(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId,
            @Param("query") String query, Pageable pageable);

    /**
     * PAN-based customer lookup via SHA-256 hash.
     * Per CBS: PAN is encrypted (AES-256-GCM), so LIKE/= on ciphertext doesn't work.
     * Caller must compute SHA-256 hash of the search PAN and pass it here.
     * Returns Optional since one PAN = one CIF per RBI KYC norms.
     */
    Optional<Customer> findByTenantIdAndPanHashAndActiveTrue(String tenantId, String panHash);

    // === KYC Re-Verification (RBI Master Direction on KYC 2016 Section 16) ===

    /**
     * Customers with expired KYC (kycExpiryDate before businessDate).
     * Used by EOD batch to flag customers for re-KYC outreach.
     * Only active, KYC-verified customers are included.
     */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId " + "AND c.active = true AND c.kycVerified = true "
            + "AND c.kycExpiryDate IS NOT NULL AND c.kycExpiryDate < :businessDate "
            + "AND c.rekycDue = false")
    List<Customer> findKycExpiredCustomers(
            @Param("tenantId") String tenantId, @Param("businessDate") java.time.LocalDate businessDate);

    /**
     * Customers with KYC expiring within the next 90 days.
     * Used for proactive re-KYC outreach before expiry.
     */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId " + "AND c.active = true AND c.kycVerified = true "
            + "AND c.kycExpiryDate IS NOT NULL "
            + "AND c.kycExpiryDate BETWEEN :businessDate AND :warningDate "
            + "AND c.rekycDue = false")
    List<Customer> findKycExpiringSoonCustomers(
            @Param("tenantId") String tenantId,
            @Param("businessDate") java.time.LocalDate businessDate,
            @Param("warningDate") java.time.LocalDate warningDate);

    // === PII Hash-Based De-Duplication (per RBI KYC: one PAN = one CIF) ===

    /** Check if PAN hash already exists (for duplicate CIF prevention on encrypted PAN) */
    boolean existsByTenantIdAndPanHash(String tenantId, String panHash);

    /** Check if Aadhaar hash already exists (for duplicate CIF prevention on encrypted Aadhaar) */
    boolean existsByTenantIdAndAadhaarHash(String tenantId, String aadhaarHash);
}

package com.finvanta.repository;

import com.finvanta.domain.entity.Customer;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByTenantIdAndCustomerNumber(String tenantId, String customerNumber);

    List<Customer> findByTenantIdAndActiveTrue(String tenantId);

    Optional<Customer> findByTenantIdAndPanNumber(String tenantId, String panNumber);

    boolean existsByTenantIdAndCustomerNumber(String tenantId, String customerNumber);

    List<Customer> findByTenantIdAndKycVerifiedFalse(String tenantId);

    long countByTenantIdAndActiveTrue(String tenantId);

    List<Customer> findByTenantIdAndBranchIdAndActiveTrue(String tenantId, Long branchId);

    // === P1 Gap 5.1: Customer Search (essential for branch operations) ===

    /**
     * Search customers by name (first or last), customer number, mobile, or PAN.
     * Per Finacle CIF_SEARCH: branch staff must be able to search by any identifier.
     * Uses LOWER() for case-insensitive matching.
     * NOTE: This searches ALL branches — use searchCustomersByBranch for MAKER/CHECKER.
     */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND c.active = true AND ("
            + "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "c.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.mobileNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.panNumber LIKE CONCAT('%', :query, '%'))")
    List<Customer> searchCustomers(@Param("tenantId") String tenantId, @Param("query") String query);

    /**
     * Branch-isolated customer search per Finacle BRANCH_CONTEXT.
     * MAKER/CHECKER users can only search customers at their home branch.
     * Same search logic as searchCustomers but with branch filter.
     */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND c.active = true "
            + "AND c.branch.id = :branchId AND ("
            + "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "c.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.mobileNumber LIKE CONCAT('%', :query, '%') OR "
            + "c.panNumber LIKE CONCAT('%', :query, '%'))")
    List<Customer> searchCustomersByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId, @Param("query") String query);

    // === P1 Gap 5.2: Duplicate CIF Detection (per RBI KYC: one PAN = one CIF) ===

    /** Check if PAN already exists (for duplicate CIF prevention) */
    boolean existsByTenantIdAndPanNumber(String tenantId, String panNumber);

    /** Check if Aadhaar already exists */
    boolean existsByTenantIdAndAadhaarNumber(String tenantId, String aadhaarNumber);

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

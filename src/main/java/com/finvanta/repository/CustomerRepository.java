package com.finvanta.repository;

import com.finvanta.domain.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
     */
    @Query("SELECT c FROM Customer c WHERE c.tenantId = :tenantId AND c.active = true AND (" +
           "LOWER(c.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(c.lastName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "c.customerNumber LIKE CONCAT('%', :query, '%') OR " +
           "c.mobileNumber LIKE CONCAT('%', :query, '%') OR " +
           "c.panNumber LIKE CONCAT('%', :query, '%'))")
    List<Customer> searchCustomers(
        @Param("tenantId") String tenantId,
        @Param("query") String query);

    // === P1 Gap 5.2: Duplicate CIF Detection (per RBI KYC: one PAN = one CIF) ===

    /** Check if PAN already exists (for duplicate CIF prevention) */
    boolean existsByTenantIdAndPanNumber(String tenantId, String panNumber);

    /** Check if Aadhaar already exists */
    boolean existsByTenantIdAndAadhaarNumber(String tenantId, String aadhaarNumber);
}

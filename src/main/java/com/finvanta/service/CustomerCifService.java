package com.finvanta.service;

import com.finvanta.domain.entity.Customer;

import java.util.List;

/**
 * CBS Customer CIF Service per Finacle CIF_MASTER / Temenos CUSTOMER.
 *
 * Central service layer for all Customer Information File (CIF) operations.
 * Per RBI KYC Master Direction 2016 / Digital Lending Guidelines 2022.
 *
 * All business validations (duplicate PAN, KYC status, active accounts check)
 * reside in this service — NOT in controllers. Controllers are thin
 * orchestration layers that delegate to this service.
 *
 * Per Finacle CIF_MASTER:
 * - One PAN = One CIF (RBI KYC mandate)
 * - KYC verification date uses CBS business date (not system date)
 * - Branch access enforced on all read/write operations
 * - Every state change audited via AuditService
 */
public interface CustomerCifService {

    /**
     * Create a new customer with auto-generated CIF number.
     * Per RBI KYC: validates duplicate PAN, branch existence, tenant isolation.
     *
     * @param firstName     Customer first name (mandatory)
     * @param lastName      Customer last name (mandatory)
     * @param dateOfBirth   Date of birth (optional)
     * @param panNumber     PAN number (optional, but unique per tenant if provided)
     * @param aadhaarNumber Aadhaar number (optional)
     * @param mobileNumber  Mobile number (optional)
     * @param email         Email address (optional)
     * @param address       Address (optional)
     * @param city          City (optional)
     * @param state         State (optional)
     * @param pinCode       PIN code (optional)
     * @param customerType  INDIVIDUAL or CORPORATE (defaults to INDIVIDUAL)
     * @param branchId      Branch ID for the customer (mandatory)
     * @return Created customer entity
     */
    Customer createCustomer(
            String firstName, String lastName,
            java.time.LocalDate dateOfBirth,
            String panNumber, String aadhaarNumber,
            String mobileNumber, String email,
            String address, String city, String state,
            String pinCode, String customerType,
            Long branchId);

    /**
     * Get customer by ID with branch access enforcement.
     *
     * @param customerId Customer ID
     * @return Customer entity
     */
    Customer getCustomer(Long customerId);

    /**
     * Verify KYC for a customer. Uses CBS business date for verification date.
     * Per RBI KYC Master Direction 2016 Section 16.
     *
     * @param customerId Customer ID
     * @return Updated customer entity
     */
    Customer verifyKyc(Long customerId);

    /**
     * Deactivate a customer. ADMIN only.
     * Per RBI: cannot deactivate customer with active loan accounts.
     *
     * @param customerId Customer ID
     * @return Updated customer entity
     */
    Customer deactivateCustomer(Long customerId);

    /**
     * Update mutable customer fields. PAN, Aadhaar, and customer number are
     * IMMUTABLE after creation per RBI KYC norms.
     *
     * @param customerId Customer ID
     * @param updated    Customer entity with updated mutable fields
     * @param branchId   Branch ID (for branch transfer if applicable)
     * @return Updated customer entity
     */
    Customer updateCustomer(Long customerId, Customer updated, Long branchId);

    /**
     * Search customers. Branch-scoped for MAKER/CHECKER, all branches for ADMIN.
     *
     * @param query Search query (min 2 chars, or empty for full list)
     * @return List of matching customers
     */
    List<Customer> searchCustomers(String query);
}

package com.finvanta.api;

import com.finvanta.domain.entity.Customer;
import com.finvanta.service.CustomerCifService;
import com.finvanta.util.PiiMaskingUtil;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Customer CIF REST API per Finacle CIF_API / Temenos IRIS Customer.
 *
 * Thin orchestration layer — delegates ALL business logic to CustomerCifService.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller: @PreAuthorize, request/response mapping, NO @Transactional
 *   Service: @Transactional, business validations, repository calls, audit
 *
 * CBS Role Matrix:
 *   MAKER   → create customer
 *   CHECKER → verify KYC
 *   ADMIN   → deactivate, all MAKER+CHECKER ops
 *
 * PII Security per RBI IT Governance §8.5:
 * - PAN/Aadhaar NEVER exposed in API responses (masked to last 4)
 * - Full PII only in encrypted DB columns (AES-256-GCM)
 */
@RestController("customerCifApiController")
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerCifService customerService;

    public CustomerController(
            CustomerCifService customerService) {
        this.customerService = customerService;
    }

    // === CIF Lifecycle ===

    /**
     * Create customer with auto-generated CIF number. MAKER/ADMIN.
     * Per CBS Tier-1: uses createCustomerFromEntity() which accepts a full Customer
     * entity — same method used by the UI controller. The service layer handles
     * mass assignment protection, validation, duplicate checks, and audit logging.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            createCustomer(
                    @Valid @RequestBody CreateCustomerRequest req) {
        Customer c = new Customer();
        populateCustomerFromRequest(c, req);
        Customer saved = customerService
                .createCustomerFromEntity(c, req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(saved),
                "Customer created: "
                        + saved.getCustomerNumber()));
    }

    /** Get customer by ID. Branch access enforced. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            getCustomer(@PathVariable Long id) {
        Customer c = customerService.getCustomer(id);
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c)));
    }

    /**
     * Update mutable customer fields. MAKER/ADMIN.
     * PAN, Aadhaar, and customer number are IMMUTABLE after creation per RBI KYC norms.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            updateCustomer(@PathVariable Long id,
                    @Valid @RequestBody CreateCustomerRequest req) {
        Customer updated = new Customer();
        populateCustomerFromRequest(updated, req);
        Customer saved = customerService
                .updateCustomer(id, updated, req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(saved),
                "Customer updated: "
                        + saved.getCustomerNumber()));
    }

    /** Verify KYC. CHECKER/ADMIN. Uses CBS business date. */
    @PostMapping("/{id}/verify-kyc")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            verifyKyc(@PathVariable Long id) {
        Customer c = customerService.verifyKyc(id);
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c),
                "KYC verified"));
    }

    /** Deactivate customer. ADMIN only. */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            deactivate(@PathVariable Long id) {
        Customer c = customerService
                .deactivateCustomer(id);
        return ResponseEntity.ok(ApiResponse.success(
                CustomerResponse.from(c),
                "Customer deactivated"));
    }

    /** Search customers. Branch-scoped for MAKER/CHECKER. */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<CustomerResponse>>>
            search(@RequestParam String q) {
        var results = customerService
                .searchCustomers(q);
        var items = results.stream()
                .map(CustomerResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Request DTOs (per CERSAI Specification v2.0 / RBI KYC Direction) ===

    public record CreateCustomerRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            LocalDate dateOfBirth,
            String panNumber,
            String aadhaarNumber,
            String mobileNumber,
            String email,
            String address,
            String city,
            String state,
            String pinCode,
            String customerType,
            @NotNull Long branchId,
            // CBS CKYC Demographics
            String gender,
            String fatherName,
            String motherName,
            String spouseName,
            String nationality,
            String maritalStatus,
            String occupationCode,
            String annualIncomeBand,
            String kycRiskCategory,
            // CBS: Boolean wrapper — null means "not provided" (omitted from JSON).
            // Primitive boolean defaults to false when omitted, which would silently
            // clear PEP flag on partial updates — FATF/RBI compliance violation.
            Boolean pep,
            // CBS KYC Document Details
            String kycMode,
            String photoIdType,
            String photoIdNumber,
            String addressProofType,
            String addressProofNumber,
            // CBS Permanent Address (CKYC/CERSAI)
            String permanentAddress,
            String permanentCity,
            String permanentState,
            String permanentPinCode,
            String permanentCountry,
            // CBS: Boolean wrapper — same rationale as pep above.
            Boolean addressSameAsPermanent,
            // CBS Income & Exposure (RBI Norms)
            BigDecimal monthlyIncome,
            BigDecimal maxBorrowingLimit,
            String employmentType,
            String employerName,
            Integer cibilScore,
            // CBS Nominee Details (RBI Nomination Guidelines)
            LocalDate nomineeDob,
            String nomineeAddress,
            String nomineeGuardianName) {}

    // === Response DTO (PII masked per RBI IT Governance §8.5) ===

    public record CustomerResponse(
            Long id, String customerNumber, String firstName, String lastName,
            String maskedPan, String maskedAadhaar, String maskedMobile,
            String email, String customerType, String gender, String dateOfBirth,
            String maritalStatus, String fatherName, String motherName, String nationality,
            String occupationCode, String annualIncomeBand,
            boolean kycVerified, String kycRiskCategory, String kycExpiryDate,
            boolean rekycDue, boolean pep, String ckycStatus, String ckycNumber, String kycMode,
            String address, String city, String state, String pinCode,
            BigDecimal monthlyIncome, BigDecimal maxBorrowingLimit,
            String employmentType, String employerName, Integer cibilScore,
            boolean active, String branchCode, String createdAt) {
        static CustomerResponse from(Customer c) {
            return new CustomerResponse(
                    c.getId(), c.getCustomerNumber(), c.getFirstName(), c.getLastName(),
                    PiiMaskingUtil.maskPan(c.getPanNumber()),
                    PiiMaskingUtil.maskAadhaar(c.getAadhaarNumber()),
                    PiiMaskingUtil.maskMobile(c.getMobileNumber()),
                    c.getEmail(), c.getCustomerType(), c.getGender(),
                    c.getDateOfBirth() != null ? c.getDateOfBirth().toString() : null,
                    c.getMaritalStatus(), c.getFatherName(), c.getMotherName(),
                    c.getNationality(), c.getOccupationCode(), c.getAnnualIncomeBand(),
                    c.isKycVerified(), c.getKycRiskCategory(),
                    c.getKycExpiryDate() != null ? c.getKycExpiryDate().toString() : null,
                    c.isRekycDue(), c.isPep(), c.getCkycStatus(), c.getCkycNumber(),
                    c.getKycMode(),
                    c.getAddress(), c.getCity(), c.getState(), c.getPinCode(),
                    c.getMonthlyIncome(), c.getMaxBorrowingLimit(),
                    c.getEmploymentType(), c.getEmployerName(), c.getCibilScore(),
                    c.isActive(),
                    c.getBranch() != null ? c.getBranch().getBranchCode() : null,
                    c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        }
    }

    // === Private Helpers ===

    /**
     * CBS: Populates a Customer entity from the API request DTO.
     * Shared between create and update endpoints — DRY per CBS coding standards.
     * The service layer handles mass assignment protection (resets id, version,
     * kycVerified, etc.) so this method safely copies ALL user-provided fields.
     */
    private void populateCustomerFromRequest(Customer c, CreateCustomerRequest req) {
        c.setFirstName(req.firstName());
        c.setLastName(req.lastName());
        c.setDateOfBirth(req.dateOfBirth());
        c.setPanNumber(req.panNumber());
        c.setAadhaarNumber(req.aadhaarNumber());
        c.setMobileNumber(req.mobileNumber());
        c.setEmail(req.email());
        c.setAddress(req.address());
        c.setCity(req.city());
        c.setState(req.state());
        c.setPinCode(req.pinCode());
        c.setCustomerType(req.customerType());
        // CKYC Demographics
        c.setGender(req.gender());
        c.setFatherName(req.fatherName());
        c.setMotherName(req.motherName());
        c.setSpouseName(req.spouseName());
        c.setNationality(req.nationality());
        c.setMaritalStatus(req.maritalStatus());
        c.setOccupationCode(req.occupationCode());
        c.setAnnualIncomeBand(req.annualIncomeBand());
        c.setKycRiskCategory(req.kycRiskCategory());
        // CBS: Only set PEP if explicitly provided (non-null). Null = not provided in JSON.
        // Prevents silent clearing of PEP flag on partial updates per FATF Recommendation 12.
        if (req.pep() != null) c.setPep(req.pep());
        // KYC Document Details
        c.setKycMode(req.kycMode());
        c.setPhotoIdType(req.photoIdType());
        c.setPhotoIdNumber(req.photoIdNumber());
        c.setAddressProofType(req.addressProofType());
        c.setAddressProofNumber(req.addressProofNumber());
        // Permanent Address
        c.setPermanentAddress(req.permanentAddress());
        c.setPermanentCity(req.permanentCity());
        c.setPermanentState(req.permanentState());
        c.setPermanentPinCode(req.permanentPinCode());
        c.setPermanentCountry(req.permanentCountry());
        // CBS: Only set addressSameAsPermanent if explicitly provided (non-null).
        if (req.addressSameAsPermanent() != null) c.setAddressSameAsPermanent(req.addressSameAsPermanent());
        // Income & Exposure
        c.setMonthlyIncome(req.monthlyIncome());
        c.setMaxBorrowingLimit(req.maxBorrowingLimit());
        c.setEmploymentType(req.employmentType());
        c.setEmployerName(req.employerName());
        c.setCibilScore(req.cibilScore());
        // Nominee
        c.setNomineeDob(req.nomineeDob());
        c.setNomineeAddress(req.nomineeAddress());
        c.setNomineeGuardianName(req.nomineeGuardianName());
    }
}

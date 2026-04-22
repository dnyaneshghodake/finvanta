package com.finvanta.api;

import com.finvanta.domain.entity.Customer;
import com.finvanta.service.BusinessDateService;
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
public class CustomerApiController {

    private final CustomerCifService customerService;
    private final BusinessDateService businessDateService;

    public CustomerApiController(
            CustomerCifService customerService,
            BusinessDateService businessDateService) {
        this.customerService = customerService;
        this.businessDateService = businessDateService;
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

    /**
     * CIF Lookup — single customer by ID.
     *
     * <p>Per CIF Lookup API Contract v1.0: returns 30 fields covering identity,
     * KYC, contact, address, occupation, risk, and compliance. PAN/Aadhaar are
     * masked per RBI IT Governance §8.5. Nested address objects provided for
     * permanent, correspondence, and legacy fallback.
     *
     * <p>Used by 12+ frontend screens (Account Opening, Transfers, FD, Loans,
     * KYC Verification, Freeze, Statements, etc.) via the shared CifLookup widget.
     *
     * <p>Branch access enforced. Every lookup audit-logged per RBI §8.3.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CifLookupResponse>>
            getCustomer(@PathVariable Long id) {
        Customer c = customerService.getCustomerWithAudit(id);
        // CBS: Resolve business date for KYC expiry check — service-layer code
        // MUST use the parameterized isKycExpired(businessDate), not the no-arg
        // overload that uses LocalDate.now(). Fallback to system date if no day open.
        LocalDate bizDate;
        try {
            bizDate = businessDateService.getCurrentBusinessDate();
        } catch (Exception e) {
            bizDate = LocalDate.now();
        }
        return ResponseEntity.ok(ApiResponse.success(
                CifLookupResponse.from(c, bizDate)));
    }

    /**
     * Update mutable customer fields. MAKER/ADMIN.
     * PAN, Aadhaar, and customer number are IMMUTABLE after creation per RBI KYC norms.
     * If panNumber or aadhaarNumber are provided in the request body, they are ignored
     * (the service layer does not copy them to the existing entity). To prevent confusion,
     * the API rejects requests that attempt to change these fields.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CustomerResponse>>
            updateCustomer(@PathVariable Long id,
                    @Valid @RequestBody CreateCustomerRequest req) {
        // CBS: Reject requests that attempt to change immutable PII fields.
        // Per RBI KYC norms: PAN and Aadhaar are immutable after CIF creation.
        // The service layer silently ignores them, but silent ignore is a poor API
        // contract — callers may believe the change was applied. Fail-fast instead.
        if (req.panNumber() != null && !req.panNumber().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "IMMUTABLE_FIELD",
                    "PAN number is immutable after creation. Cannot be changed via update."));
        }
        if (req.aadhaarNumber() != null && !req.aadhaarNumber().isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "IMMUTABLE_FIELD",
                    "Aadhaar number is immutable after creation. Cannot be changed via update."));
        }
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
            search(@RequestParam(required = false) String q) {
        var results = (q != null && q.trim().length() >= 2)
                ? customerService.searchCustomers(q)
                : customerService.searchCustomers("");
        var items = results.stream()
                .map(CustomerResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    // === Request DTOs (per CERSAI Specification v2.0 / RBI KYC Direction) ===

    public record CreateCustomerRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            String middleName,
            LocalDate dateOfBirth,
            String panNumber,
            String aadhaarNumber,
            String mobileNumber,
            String email,
            String alternateMobile,
            String communicationPref,
            String address,
            String city,
            String state,
            String pinCode,
            String customerType,
            @NotNull Long branchId,
            // CBS CKYC Demographics (CERSAI v2.0 Part I)
            String gender,
            String fatherName,
            String motherName,
            String spouseName,
            String nationality,
            String maritalStatus,
            String residentStatus,
            String occupationCode,
            String annualIncomeBand,
            String sourceOfFunds,
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
            // CBS OVD — Officially Valid Documents (RBI KYC §3)
            String passportNumber,
            LocalDate passportExpiry,
            String voterId,
            String drivingLicense,
            // CBS FATCA / CRS (Foreign Account Tax Compliance Act)
            String fatcaCountry,
            // CBS Permanent Address (CKYC/CERSAI)
            String permanentAddress,
            String permanentCity,
            String permanentDistrict,
            String permanentState,
            String permanentPinCode,
            String permanentCountry,
            // CBS: Boolean wrapper — same rationale as pep above.
            Boolean addressSameAsPermanent,
            // CBS Correspondence Address (CKYC/CERSAI — separate from permanent)
            String correspondenceAddress,
            String correspondenceCity,
            String correspondenceDistrict,
            String correspondenceState,
            String correspondencePinCode,
            String correspondenceCountry,
            // CBS Income & Exposure (RBI Norms)
            BigDecimal monthlyIncome,
            BigDecimal maxBorrowingLimit,
            String employmentType,
            String employerName,
            Integer cibilScore,
            // CBS Segmentation (Finacle CIF_MASTER)
            String customerSegment,
            String sourceOfIntroduction,
            // CBS Corporate / Non-Individual (RBI KYC §9)
            String companyName,
            String cin,
            String gstin,
            LocalDate dateOfIncorporation,
            String constitutionType,
            String natureOfBusiness,
            // CBS Nominee Details (RBI Nomination Guidelines)
            LocalDate nomineeDob,
            String nomineeAddress,
            String nomineeGuardianName) {}

    // === Response DTO (PII masked per RBI IT Governance §8.5) ===

    public record CustomerResponse(
            // Identity
            Long id, String customerNumber, String firstName, String lastName,
            String middleName, String fullName, String customerType,
            String maskedPan, String maskedAadhaar, String maskedMobile,
            String email, String alternateMobile, String communicationPref,
            String dateOfBirth,
            // Demographics (CERSAI v2.0)
            String gender, String fatherName, String motherName, String spouseName,
            String nationality, String maritalStatus, String residentStatus,
            String occupationCode, String annualIncomeBand, String sourceOfFunds,
            // KYC Lifecycle
            boolean kycVerified, String kycRiskCategory, String kycExpiryDate,
            boolean rekycDue, boolean pep, String ckycStatus, String ckycNumber,
            String kycMode, boolean videoKycDone,
            // OVD (masked per RBI — only type exposed, not number)
            String photoIdType, String addressProofType,
            // FATCA
            String fatcaCountry,
            // Legacy Address
            String address, String city, String state, String pinCode,
            // Permanent Address (CKYC)
            String permanentAddress, String permanentCity, String permanentDistrict,
            String permanentState, String permanentPinCode, String permanentCountry,
            boolean addressSameAsPermanent,
            // Correspondence Address (CKYC)
            String correspondenceAddress, String correspondenceCity, String correspondenceDistrict,
            String correspondenceState, String correspondencePinCode, String correspondenceCountry,
            // Income & Exposure
            BigDecimal monthlyIncome, BigDecimal maxBorrowingLimit,
            String employmentType, String employerName, Integer cibilScore,
            // Segmentation
            String customerSegment, String sourceOfIntroduction, String relationshipManagerId,
            // Corporate (RBI KYC §9)
            String companyName, String cin, String gstin,
            String dateOfIncorporation, String constitutionType, String natureOfBusiness,
            // Group Exposure
            Long customerGroupId, String customerGroupName,
            // Nominee
            String nomineeDob, String nomineeAddress, String nomineeGuardianName,
            // Status & Audit
            boolean active, String branchCode, String createdAt) {
        static CustomerResponse from(Customer c) {
            return new CustomerResponse(
                    c.getId(), c.getCustomerNumber(), c.getFirstName(), c.getLastName(),
                    c.getMiddleName(), c.getFullName(), c.getCustomerType(),
                    PiiMaskingUtil.maskPan(c.getPanNumber()),
                    PiiMaskingUtil.maskAadhaar(c.getAadhaarNumber()),
                    PiiMaskingUtil.maskMobile(c.getMobileNumber()),
                    c.getEmail(), c.getAlternateMobile(), c.getCommunicationPref(),
                    c.getDateOfBirth() != null ? c.getDateOfBirth().toString() : null,
                    c.getGender(), c.getFatherName(), c.getMotherName(), c.getSpouseName(),
                    c.getNationality(), c.getMaritalStatus(), c.getResidentStatus(),
                    c.getOccupationCode(), c.getAnnualIncomeBand(), c.getSourceOfFunds(),
                    c.isKycVerified(), c.getKycRiskCategory(),
                    c.getKycExpiryDate() != null ? c.getKycExpiryDate().toString() : null,
                    c.isRekycDue(), c.isPep(), c.getCkycStatus(), c.getCkycNumber(),
                    c.getKycMode(), c.isVideoKycDone(),
                    c.getPhotoIdType(), c.getAddressProofType(),
                    c.getFatcaCountry(),
                    c.getAddress(), c.getCity(), c.getState(), c.getPinCode(),
                    c.getPermanentAddress(), c.getPermanentCity(), c.getPermanentDistrict(),
                    c.getPermanentState(), c.getPermanentPinCode(), c.getPermanentCountry(),
                    c.isAddressSameAsPermanent(),
                    c.getCorrespondenceAddress(), c.getCorrespondenceCity(), c.getCorrespondenceDistrict(),
                    c.getCorrespondenceState(), c.getCorrespondencePinCode(), c.getCorrespondenceCountry(),
                    c.getMonthlyIncome(), c.getMaxBorrowingLimit(),
                    c.getEmploymentType(), c.getEmployerName(), c.getCibilScore(),
                    c.getCustomerSegment(), c.getSourceOfIntroduction(), c.getRelationshipManagerId(),
                    c.getCompanyName(), c.getCin(), c.getGstin(),
                    c.getDateOfIncorporation() != null ? c.getDateOfIncorporation().toString() : null,
                    c.getConstitutionType(), c.getNatureOfBusiness(),
                    c.getCustomerGroupId(), c.getCustomerGroupName(),
                    c.getNomineeDob() != null ? c.getNomineeDob().toString() : null,
                    c.getNomineeAddress(), c.getNomineeGuardianName(),
                    c.isActive(),
                    c.getBranch() != null ? c.getBranch().getBranchCode() : null,
                    c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        }
    }

    // === CIF Lookup Response DTO (per CIF Lookup API Contract v1.0) ===
    //
    // 30 fields consumed by the shared CifLookup.tsx widget across 12+ screens.
    // Field naming matches the frontend CifCustomer TypeScript type exactly.
    // PAN masked as ABCD***34F, Aadhaar masked as **** **** 1234.
    // Nested address objects for permanent, correspondence, and legacy fallback.

    /**
     * Nested address object per CIF Lookup Contract §3.27-28.
     * Used for permanentAddress and correspondenceAddress.
     */
    public record AddressDto(
            String line1,
            String line2,
            String city,
            String district,
            String state,
            String pincode,
            String country) {}

    /**
     * Legacy flat address for backward compatibility per CIF Lookup Contract §3.29.
     * Used only if permanentAddress is null.
     */
    public record LegacyAddressDto(
            String street,
            String city,
            String state,
            String pincode) {}

    /**
     * CIF Lookup Response per CIF Lookup API Contract v1.0.
     *
     * <p>30 fields covering identity (7), KYC (4), contact (2), personal (6),
     * occupation (3), risk (3), branch (1), and addresses (3 nested objects).
     *
     * <p>Per RBI IT Governance §8.5: PAN/Aadhaar NEVER exposed raw in API responses.
     * Masking applied at the DTO boundary — decrypted PII never reaches the wire.
     */
    public record CifLookupResponse(
            // §3 Identity (7 fields)
            Long id,
            String customerNumber,
            String firstName,
            String lastName,
            String fullName,
            String customerType,
            String status,
            // §3 KYC & Compliance (4 fields)
            String kycStatus,
            String pan,
            String aadhaar,
            String ckycNumber,
            // §3 Contact (2 fields)
            String mobile,
            String email,
            // §3 Personal (6 fields)
            String dob,
            String gender,
            String nationality,
            String residentStatus,
            String fatherOrSpouseName,
            String maritalStatus,
            // §3 Occupation & Financial (3 fields)
            String occupation,
            String annualIncomeRange,
            String sourceOfFunds,
            // §3 Risk & Compliance (3 fields)
            String riskCategory,
            Boolean pepFlag,
            String fatcaCountry,
            // §3 Branch (1 field)
            String branchCode,
            // §3 Addresses (nested objects)
            AddressDto permanentAddress,
            AddressDto correspondenceAddress,
            LegacyAddressDto address) {

        /**
         * Factory method accepting CBS business date for KYC expiry check.
         * Per Customer.java:667-670: service-layer code MUST use the parameterized
         * isKycExpired(businessDate), not the no-arg overload that uses LocalDate.now().
         */
        static CifLookupResponse from(Customer c, LocalDate businessDate) {
            // Compute status from active flag
            String status = c.isActive() ? "ACTIVE" : "INACTIVE";

            // Compute kycStatus from boolean + expiry using CBS business date
            String kycStatus;
            if (c.isKycVerified()) {
                kycStatus = c.isKycExpired(businessDate) ? "EXPIRED" : "VERIFIED";
            } else {
                kycStatus = "PENDING";
            }

            // Compute fatherOrSpouseName — prefer spouse, fall back to father
            String fatherOrSpouse = c.getSpouseName() != null && !c.getSpouseName().isBlank()
                    ? c.getSpouseName() : c.getFatherName();

            // Map occupationCode → occupation per contract enum values
            String occupation = mapOccupation(c.getOccupationCode(), c.getEmploymentType());

            // Map annualIncomeBand → annualIncomeRange per contract enum values
            String annualIncomeRange = mapIncomeRange(c.getAnnualIncomeBand());

            // Build permanent address (nested)
            AddressDto permAddr = null;
            if (c.getPermanentAddress() != null && !c.getPermanentAddress().isBlank()) {
                permAddr = new AddressDto(
                        c.getPermanentAddress(), null,
                        c.getPermanentCity(),
                        c.getPermanentDistrict(),
                        c.getPermanentState(),
                        c.getPermanentPinCode(),
                        c.getPermanentCountry());
            }

            // Build correspondence address (nested)
            AddressDto corrAddr = null;
            if (c.getCorrespondenceAddress() != null && !c.getCorrespondenceAddress().isBlank()) {
                corrAddr = new AddressDto(
                        c.getCorrespondenceAddress(), null,
                        c.getCorrespondenceCity(),
                        c.getCorrespondenceDistrict(),
                        c.getCorrespondenceState(),
                        c.getCorrespondencePinCode(),
                        c.getCorrespondenceCountry());
            }

            // Build legacy flat address (backward compat)
            LegacyAddressDto legacyAddr = null;
            if (c.getAddress() != null && !c.getAddress().isBlank()) {
                legacyAddr = new LegacyAddressDto(
                        c.getAddress(), c.getCity(),
                        c.getState(), c.getPinCode());
            }

            return new CifLookupResponse(
                    c.getId(),
                    c.getCustomerNumber(),
                    c.getFirstName(),
                    c.getLastName(),
                    c.getFullName(),
                    c.getCustomerType(),
                    status,
                    kycStatus,
                    PiiMaskingUtil.maskPan(c.getPanNumber()),
                    PiiMaskingUtil.maskAadhaar(c.getAadhaarNumber()),
                    c.getCkycNumber(),
                    c.getMobileNumber(),
                    c.getEmail(),
                    c.getDateOfBirth() != null ? c.getDateOfBirth().toString() : null,
                    mapGender(c.getGender()),
                    c.getNationality(),
                    c.getResidentStatus(),
                    fatherOrSpouse,
                    c.getMaritalStatus(),
                    occupation,
                    annualIncomeRange,
                    c.getSourceOfFunds(),
                    c.getKycRiskCategory(),
                    c.isPep(),
                    c.getFatcaCountry(),
                    c.getBranch() != null ? c.getBranch().getBranchCode() : null,
                    permAddr,
                    corrAddr,
                    legacyAddr);
        }

        /**
         * Maps CERSAI single-char gender code to contract enum.
         * Entity stores M/F/T per CERSAI v2.0 / NALSA 2014.
         * Contract expects MALE/FEMALE/OTHER per CIF Lookup §3.15.
         */
        private static String mapGender(String gender) {
            if (gender == null) return null;
            return switch (gender) {
                case "M" -> "MALE";
                case "F" -> "FEMALE";
                case "T" -> "OTHER";
                default -> gender;
            };
        }

        /**
         * Maps internal occupationCode/employmentType to contract occupation enum.
         * Contract values: SALARIED, SELF_EMPLOYED, BUSINESS, PROFESSIONAL,
         *                  RETIRED, STUDENT, HOMEMAKER
         */
        private static String mapOccupation(String occupationCode, String employmentType) {
            if (occupationCode != null && !occupationCode.isBlank()) {
                return switch (occupationCode) {
                    case "SALARIED_PRIVATE", "SALARIED_GOVT" -> "SALARIED";
                    case "SELF_EMPLOYED" -> "SELF_EMPLOYED";
                    case "BUSINESS" -> "BUSINESS";
                    case "PROFESSIONAL" -> "PROFESSIONAL";
                    case "RETIRED" -> "RETIRED";
                    case "STUDENT" -> "STUDENT";
                    case "HOUSEWIFE" -> "HOMEMAKER";
                    case "AGRICULTURIST" -> "SELF_EMPLOYED";
                    default -> occupationCode;
                };
            }
            // Fall back to employmentType if occupationCode is not set
            if (employmentType != null && !employmentType.isBlank()) {
                return employmentType;
            }
            return null;
        }

        /**
         * Maps internal annualIncomeBand to contract annualIncomeRange enum.
         * Contract values: BELOW_1L, 1L_5L, 5L_10L, 10L_25L, 25L_50L, ABOVE_50L
         */
        private static String mapIncomeRange(String annualIncomeBand) {
            if (annualIncomeBand == null) return null;
            return switch (annualIncomeBand) {
                case "BELOW_1L" -> "BELOW_1L";
                case "1L_TO_5L" -> "1L_5L";
                case "5L_TO_10L" -> "5L_10L";
                case "10L_TO_25L" -> "10L_25L";
                case "25L_TO_1CR" -> "25L_50L";
                case "ABOVE_1CR" -> "ABOVE_50L";
                default -> annualIncomeBand;
            };
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
        // Identity
        c.setFirstName(req.firstName());
        c.setLastName(req.lastName());
        c.setMiddleName(req.middleName());
        c.setDateOfBirth(req.dateOfBirth());
        c.setPanNumber(req.panNumber());
        c.setAadhaarNumber(req.aadhaarNumber());
        c.setCustomerType(req.customerType());
        // Contact
        c.setMobileNumber(req.mobileNumber());
        c.setEmail(req.email());
        c.setAlternateMobile(req.alternateMobile());
        c.setCommunicationPref(req.communicationPref());
        // Legacy Address
        c.setAddress(req.address());
        c.setCity(req.city());
        c.setState(req.state());
        c.setPinCode(req.pinCode());
        // CKYC Demographics (CERSAI v2.0 Part I)
        c.setGender(req.gender());
        c.setFatherName(req.fatherName());
        c.setMotherName(req.motherName());
        c.setSpouseName(req.spouseName());
        c.setNationality(req.nationality());
        c.setMaritalStatus(req.maritalStatus());
        if (req.residentStatus() != null) c.setResidentStatus(req.residentStatus());
        c.setOccupationCode(req.occupationCode());
        c.setAnnualIncomeBand(req.annualIncomeBand());
        c.setSourceOfFunds(req.sourceOfFunds());
        // CBS: Only set kycRiskCategory if explicitly provided (non-null).
        // Null = not provided in JSON → preserve entity default ("MEDIUM").
        if (req.kycRiskCategory() != null) c.setKycRiskCategory(req.kycRiskCategory());
        // CBS: Only set PEP if explicitly provided (non-null).
        // Prevents silent clearing of PEP flag on partial updates per FATF Rec 12.
        if (req.pep() != null) c.setPep(req.pep());
        // KYC Document Details
        c.setKycMode(req.kycMode());
        c.setPhotoIdType(req.photoIdType());
        c.setPhotoIdNumber(req.photoIdNumber());
        c.setAddressProofType(req.addressProofType());
        c.setAddressProofNumber(req.addressProofNumber());
        // OVD — Officially Valid Documents (RBI KYC §3)
        c.setPassportNumber(req.passportNumber());
        c.setPassportExpiry(req.passportExpiry());
        c.setVoterId(req.voterId());
        c.setDrivingLicense(req.drivingLicense());
        // FATCA / CRS
        c.setFatcaCountry(req.fatcaCountry());
        // Permanent Address (CKYC/CERSAI)
        c.setPermanentAddress(req.permanentAddress());
        c.setPermanentCity(req.permanentCity());
        c.setPermanentDistrict(req.permanentDistrict());
        c.setPermanentState(req.permanentState());
        c.setPermanentPinCode(req.permanentPinCode());
        c.setPermanentCountry(req.permanentCountry());
        if (req.addressSameAsPermanent() != null) c.setAddressSameAsPermanent(req.addressSameAsPermanent());
        // Correspondence Address (CKYC/CERSAI)
        c.setCorrespondenceAddress(req.correspondenceAddress());
        c.setCorrespondenceCity(req.correspondenceCity());
        c.setCorrespondenceDistrict(req.correspondenceDistrict());
        c.setCorrespondenceState(req.correspondenceState());
        c.setCorrespondencePinCode(req.correspondencePinCode());
        c.setCorrespondenceCountry(req.correspondenceCountry());
        // Income & Exposure
        c.setMonthlyIncome(req.monthlyIncome());
        c.setMaxBorrowingLimit(req.maxBorrowingLimit());
        c.setEmploymentType(req.employmentType());
        c.setEmployerName(req.employerName());
        c.setCibilScore(req.cibilScore());
        // Segmentation
        c.setCustomerSegment(req.customerSegment());
        c.setSourceOfIntroduction(req.sourceOfIntroduction());
        // Corporate / Non-Individual (RBI KYC §9)
        c.setCompanyName(req.companyName());
        c.setCin(req.cin());
        c.setGstin(req.gstin());
        c.setDateOfIncorporation(req.dateOfIncorporation());
        c.setConstitutionType(req.constitutionType());
        c.setNatureOfBusiness(req.natureOfBusiness());
        // Nominee
        c.setNomineeDob(req.nomineeDob());
        c.setNomineeAddress(req.nomineeAddress());
        c.setNomineeGuardianName(req.nomineeGuardianName());
    }
}

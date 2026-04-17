package com.finvanta.domain.entity;

import com.finvanta.config.PiiEncryptionConverter;
import com.finvanta.domain.enums.CustomerType;
import com.finvanta.domain.enums.KycRiskCategory;
import com.finvanta.util.PiiHashUtil;

import jakarta.persistence.*;

import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Customer Information File (CIF) per Finacle CIF_MASTER / Temenos CUSTOMER.
 *
 * Per RBI Master Direction on KYC 2016 and PMLA 2002:
 * - KYC must be verified before account opening (kycVerified=true required)
 * - KYC must be periodically re-verified based on risk category:
 *     LOW risk:    every 10 years
 *     MEDIUM risk: every 8 years
 *     HIGH risk:   every 2 years
 * - Expired KYC blocks new account opening and flags existing accounts
 * - Customer type determines applicable KYC document requirements
 * - PAN/Aadhaar are encrypted at rest (AES-256-GCM via PiiEncryptionConverter)
 * - PAN/Aadhaar hash stored separately for de-duplication without decryption
 *
 * Customer Types (per RBI KYC Direction):
 *   INDIVIDUAL       - Natural person (standard KYC)
 *   JOINT            - Joint account holders (KYC for all holders)
 *   HUF              - Hindu Undivided Family (KYC for Karta + coparceners)
 *   PARTNERSHIP      - Partnership firm (KYC for all partners)
 *   COMPANY          - Private/Public limited company (KYC for directors)
 *   TRUST            - Trust/Society (KYC for trustees)
 *   NRI              - Non-Resident Indian (FEMA compliance, NRE/NRO)
 *   MINOR            - Minor (guardian KYC, age-based restrictions)
 *   GOVERNMENT       - Government entity
 *
 * KYC Risk Categories (per RBI KYC Direction Section 16):
 *   LOW    - Salaried, known employer, low-value accounts (re-KYC: 10 years)
 *   MEDIUM - Self-employed, moderate-value accounts (re-KYC: 8 years)
 *   HIGH   - PEP, high-value, complex structures, adverse media (re-KYC: 2 years)
 */
@Entity
@Table(
        name = "customers",
        indexes = {
            @Index(name = "idx_cust_tenant_custno", columnList = "tenant_id, customer_number", unique = true),
            @Index(name = "idx_cust_pan", columnList = "tenant_id, pan_number"),
            @Index(name = "idx_cust_aadhaar", columnList = "tenant_id, aadhaar_number"),
            // CBS: NOT unique at DB level — PAN/Aadhaar are optional fields, so pan_hash/aadhaar_hash
            // can be NULL for many customers. SQL Server treats NULL as a single distinct value in
            // unique indexes, meaning only ONE customer per tenant could have NULL PAN/Aadhaar.
            // Duplicate prevention is enforced at the application level via
            // CustomerCifServiceImpl.validateCustomerFields() using existsByTenantIdAndPanHash().
            // This is the standard Finacle CIF_MASTER approach for optional-but-unique fields.
            @Index(name = "idx_cust_pan_hash", columnList = "tenant_id, pan_hash"),
            @Index(name = "idx_cust_aadhaar_hash", columnList = "tenant_id, aadhaar_hash"),
            @Index(name = "idx_cust_kyc_expiry", columnList = "tenant_id, kyc_expiry_date"),
            @Index(name = "idx_cust_tenant_branch", columnList = "tenant_id, branch_id"),
            @Index(name = "idx_cust_ckyc", columnList = "tenant_id, ckyc_number"),
            @Index(name = "idx_cust_ckyc_status", columnList = "tenant_id, ckyc_status")
        })
@Getter
@Setter
@NoArgsConstructor
public class Customer extends BaseEntity {

    @Column(name = "customer_number", nullable = false, length = 40)
    private String customerNumber;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * PAN number — encrypted at rest per RBI IT Governance Direction 2023.
     * Column length expanded from 10 to 100 to accommodate Base64(IV+ciphertext).
     * Application code sees plaintext; DB stores AES-256-GCM ciphertext.
     */
    @Convert(converter = PiiEncryptionConverter.class)
    @Column(name = "pan_number", length = 100)
    private String panNumber;

    /**
     * Aadhaar number — encrypted at rest per RBI IT Governance Direction 2023.
     * Column length expanded from 12 to 100 to accommodate Base64(IV+ciphertext).
     * Per UIDAI guidelines, Aadhaar must never be stored in plaintext.
     */
    @Convert(converter = PiiEncryptionConverter.class)
    @Column(name = "aadhaar_number", length = 100)
    private String aadhaarNumber;

    @Column(name = "mobile_number", length = 15)
    private String mobileNumber;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "pin_code", length = 6)
    private String pinCode;

    @Column(name = "kyc_verified", nullable = false)
    private boolean kycVerified = false;

    @Column(name = "kyc_verified_date")
    private LocalDate kycVerifiedDate;

    @Column(name = "kyc_verified_by", length = 100)
    private String kycVerifiedBy;

    // === KYC Risk & Expiry (RBI Master Direction on KYC 2016 Section 16) ===

    /**
     * KYC risk category determines re-verification frequency:
     *   LOW    → re-KYC every 10 years (salaried, known employer)
     *   MEDIUM → re-KYC every 8 years (self-employed, moderate value)
     *   HIGH   → re-KYC every 2 years (PEP, high-value, adverse media)
     * Per RBI: risk category must be assessed at onboarding and reviewed periodically.
     *
     * <p>CBS Tier-1 (Gap 1): Validated against {@link KycRiskCategory} enum at the service
     * layer. Field remains String for backward compatibility with the API response DTO
     * ({@code CustomerResponse} record). Full enum migration requires coordinated change
     * across entity + API controller + tests in a dedicated PR.
     */
    @Column(name = "kyc_risk_category", length = 10)
    private String kycRiskCategory = "MEDIUM";

    /**
     * KYC expiry date — computed from kycVerifiedDate + risk-based period.
     * After this date, customer's KYC is considered expired:
     *   - New account opening is BLOCKED
     *   - Existing accounts are flagged for re-KYC
     *   - EOD batch identifies expired KYC customers for operations follow-up
     * Null = KYC not yet verified (new customer).
     */
    @Column(name = "kyc_expiry_date")
    private LocalDate kycExpiryDate;

    /**
     * Whether re-KYC is due (set by EOD batch when kycExpiryDate is approaching/passed).
     * Operations team uses this flag to prioritize re-KYC outreach.
     */
    @Column(name = "rekyc_due", nullable = false)
    private boolean rekycDue = false;

    // === PII Hash for De-Duplication (per RBI KYC: one PAN = one CIF) ===

    /**
     * SHA-256 hash of PAN number for de-duplication without decryption.
     * Since panNumber is encrypted (AES-256-GCM), DB-level uniqueness checks on
     * ciphertext are impossible (same plaintext produces different ciphertext due to IV).
     * The hash enables duplicate detection: hash(PAN1) == hash(PAN2) → same PAN.
     * Set automatically when panNumber is set.
     */
    @Column(name = "pan_hash", length = 64)
    private String panHash;

    /**
     * SHA-256 hash of Aadhaar number for de-duplication without decryption.
     * Same rationale as panHash — encrypted values can't be compared at DB level.
     */
    @Column(name = "aadhaar_hash", length = 64)
    private String aadhaarHash;

    @Column(name = "cibil_score")
    private Integer cibilScore;

    /**
     * Customer type per RBI KYC Direction.
     * Determines KYC document requirements and regulatory treatment.
     * Values: INDIVIDUAL, JOINT, HUF, PARTNERSHIP, COMPANY, TRUST, NRI, MINOR, GOVERNMENT
     *
     * <p>CBS Tier-1 (Gap 1): Validated against {@link CustomerType} enum at the service
     * layer. Field remains String for backward compatibility with the API response DTO.
     */
    @Column(name = "customer_type", length = 20)
    private String customerType = "INDIVIDUAL";

    /**
     * Politically Exposed Person flag per RBI KYC Direction Section 2(1)(fa).
     * PEP customers require enhanced due diligence (EDD) and HIGH risk classification.
     * Per FATF Recommendation 12: PEP status must be checked at onboarding and periodically.
     */
    @Column(name = "is_pep", nullable = false)
    private boolean pep = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * CBS: EAGER fetch for branch — branch is needed on EVERY customer screen
     * (list, view, edit) and is a single-row @ManyToOne (not a collection).
     * Per Finacle CIF_MASTER: customer always carries its SOL/branch context.
     * EAGER eliminates N+1 lazy-load queries when open-in-view=false (SQL Server).
     * With H2 in-memory, lazy vs eager is invisible. With SQL Server over TCP,
     * each lazy load costs ~2-5ms — EAGER joins it into the initial query for free.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /**
     * Customer group ID for group exposure tracking per RBI Exposure Norms.
     * Customers in the same group (e.g., related companies, family members)
     * share a combined exposure limit. Null = standalone customer.
     */
    @Column(name = "customer_group_id")
    private Long customerGroupId;

    /**
     * Customer group name for display (denormalized for reporting).
     */
    @Column(name = "customer_group_name", length = 200)
    private String customerGroupName;

    // --- CBS Customer Exposure Limits (per Finacle CIF_LIMIT / RBI Exposure Norms) ---

    /**
     * Monthly gross income for Debt-to-Income (DTI) ratio calculation.
     * Per RBI Fair Practices Code: total EMI obligations should not exceed
     * 50-60% of monthly income. Set during KYC/income verification.
     */
    @Column(name = "monthly_income", precision = 18, scale = 2)
    private java.math.BigDecimal monthlyIncome;

    /**
     * Maximum borrowing limit for this customer.
     * Set by the bank based on income assessment, credit score, and risk category.
     * Per RBI Exposure Norms: single borrower exposure is capped.
     * Null = no explicit limit (system-wide limits from product_master apply).
     */
    @Column(name = "max_borrowing_limit", precision = 18, scale = 2)
    private java.math.BigDecimal maxBorrowingLimit;

    /**
     * Employment type for income assessment: SALARIED, SELF_EMPLOYED, BUSINESS, RETIRED, OTHER
     */
    @Column(name = "employment_type", length = 30)
    private String employmentType;

    /** Employer name (for salaried customers) */
    @Column(name = "employer_name", length = 200)
    private String employerName;

    // ========================================================================
    // CKYC / CERSAI FIELDS (per PMLA Rules 2014 / RBI KYC Direction 2016)
    // ========================================================================

    /**
     * CKYC Identifier (KIN) — 14-digit number assigned by CERSAI.
     * Per PMLA Rules 2014 Rule 9(1A): banks must upload KYC to CERSAI
     * and store the returned KIN for future reference/download.
     * Null = not yet registered with CERSAI.
     */
    @Column(name = "ckyc_number", length = 14)
    private String ckycNumber;

    /**
     * CKYC registration status with CERSAI.
     * Values: NOT_REGISTERED, PENDING_UPLOAD, UPLOADED, REGISTERED, DOWNLOAD_VERIFIED, FAILED
     */
    @Column(name = "ckyc_status", length = 30)
    private String ckycStatus = "NOT_REGISTERED";

    /** Date when KYC data was uploaded to CERSAI */
    @Column(name = "ckyc_upload_date")
    private LocalDate ckycUploadDate;

    /** Date when KYC data was downloaded/verified from CERSAI */
    @Column(name = "ckyc_download_date")
    private LocalDate ckycDownloadDate;

    /**
     * CKYC account type per CERSAI specification.
     * Values: INDIVIDUAL, NON_INDIVIDUAL
     * Maps from customerType: INDIVIDUAL/JOINT/MINOR/NRI → INDIVIDUAL; rest → NON_INDIVIDUAL
     */
    @Column(name = "ckyc_account_type", length = 20)
    private String ckycAccountType = "INDIVIDUAL";

    // === Demographics (CKYC Mandatory Fields per CERSAI Specification v2.0) ===

    /**
     * Gender per RBI/CERSAI classification.
     * Values: M (Male), F (Female), T (Transgender) per Supreme Court NALSA judgment 2014.
     * CKYC mandatory field for INDIVIDUAL customers.
     */
    @Column(name = "gender", length = 1)
    private String gender;

    /**
     * Father's name — CKYC mandatory for INDIVIDUAL customers.
     * Per CERSAI: required for identity verification and CKYC record matching.
     */
    @Column(name = "father_name", length = 200)
    private String fatherName;

    /**
     * Spouse name — CKYC field (mandatory if married).
     * Per CERSAI: used for CKYC record matching and nominee validation.
     */
    @Column(name = "spouse_name", length = 200)
    private String spouseName;

    /**
     * Mother's name — CKYC mandatory field.
     * Per CERSAI: required for all INDIVIDUAL customers.
     */
    @Column(name = "mother_name", length = 200)
    private String motherName;

    /**
     * Nationality per CERSAI specification.
     * Values: INDIAN, NRI, PIO, OCI, FOREIGN
     * Per FEMA 1999: nationality determines applicable regulatory framework.
     */
    @Column(name = "nationality", length = 20)
    private String nationality = "INDIAN";

    /**
     * Marital status per CERSAI specification.
     * Values: SINGLE, MARRIED, DIVORCED, WIDOWED, SEPARATED
     */
    @Column(name = "marital_status", length = 20)
    private String maritalStatus;

    /**
     * Occupation code per RBI classification (used in CKYC upload).
     * Values: SALARIED_PRIVATE, SALARIED_GOVT, BUSINESS, PROFESSIONAL,
     *         SELF_EMPLOYED, RETIRED, HOUSEWIFE, STUDENT, AGRICULTURIST, OTHER
     * Per CERSAI: mapped from employmentType but more granular.
     */
    @Column(name = "occupation_code", length = 30)
    private String occupationCode;

    /**
     * Annual income band per CERSAI specification.
     * Values: BELOW_1L, 1L_TO_5L, 5L_TO_10L, 10L_TO_25L, 25L_TO_1CR, ABOVE_1CR
     * Per CERSAI: income is reported as bands, not exact amounts.
     */
    @Column(name = "annual_income_band", length = 20)
    private String annualIncomeBand;

    // === KYC Document Details (CKYC Mandatory) ===

    /**
     * KYC mode — how KYC was performed.
     * Values: IN_PERSON, VIDEO_KYC, DIGITAL_KYC, CKYC_DOWNLOAD
     * Per RBI: Video KYC allowed since Jan 2020 (RBI/2020-21/12).
     */
    @Column(name = "kyc_mode", length = 20)
    private String kycMode;

    /**
     * Photo ID document type used for KYC.
     * Values: PASSPORT, VOTER_ID, DRIVING_LICENSE, NREGA_CARD, PAN_CARD, AADHAAR
     * Per RBI KYC Direction: at least one photo ID is mandatory.
     */
    @Column(name = "photo_id_type", length = 30)
    private String photoIdType;

    /** Photo ID document number (encrypted at rest) */
    @Convert(converter = PiiEncryptionConverter.class)
    @Column(name = "photo_id_number", length = 100)
    private String photoIdNumber;

    /**
     * Address proof document type used for KYC.
     * Values: PASSPORT, VOTER_ID, DRIVING_LICENSE, UTILITY_BILL,
     *         BANK_STATEMENT, AADHAAR, RATION_CARD, RENT_AGREEMENT
     */
    @Column(name = "address_proof_type", length = 30)
    private String addressProofType;

    /** Address proof document number (encrypted at rest) */
    @Convert(converter = PiiEncryptionConverter.class)
    @Column(name = "address_proof_number", length = 100)
    private String addressProofNumber;

    /**
     * Whether Video KYC was performed per RBI circular RBI/2020-21/12.
     * Per RBI: V-KYC is allowed as an alternative to in-person verification
     * for low-risk customers. The video recording must be stored for audit.
     */
    @Column(name = "video_kyc_done", nullable = false)
    private boolean videoKycDone = false;

    // === Permanent Address (CKYC requires separate permanent + correspondence) ===

    /** Permanent address line — CKYC mandatory (separate from correspondence) */
    @Column(name = "permanent_address", length = 500)
    private String permanentAddress;

    @Column(name = "permanent_city", length = 100)
    private String permanentCity;

    @Column(name = "permanent_state", length = 100)
    private String permanentState;

    @Column(name = "permanent_pin_code", length = 6)
    private String permanentPinCode;

    @Column(name = "permanent_country", length = 50)
    private String permanentCountry = "INDIA";

    /**
     * Whether correspondence address is same as permanent address.
     * Per CERSAI: if true, permanent address fields are copied to correspondence.
     */
    @Column(name = "address_same_as_permanent", nullable = false)
    private boolean addressSameAsPermanent = true;

    // === Nominee Details (RBI Nomination Guidelines — expanded for CKYC) ===

    /** Nominee date of birth — required for minor nominees per RBI */
    @Column(name = "nominee_dob")
    private LocalDate nomineeDob;

    /** Nominee address — required per RBI nomination guidelines */
    @Column(name = "nominee_address", length = 500)
    private String nomineeAddress;

    /** Guardian name — required if nominee is a minor per RBI */
    @Column(name = "nominee_guardian_name", length = 200)
    private String nomineeGuardianName;

    public String getFullName() {
        return firstName + " " + lastName;
    }

    // === CBS Tier-1 (Gap 6): Pre-masked PII accessors for view layer ===
    //
    // Per RBI IT Governance Direction 2023 / UIDAI Aadhaar Act 2016:
    // PII must be masked before it reaches the presentation layer. These
    // @Transient computed properties expose pre-masked values so JSP views
    // use ${cust.maskedPan} instead of decrypting the full PAN in the template
    // and masking inline. This ensures decrypted PII never traverses the
    // template engine pipeline — masking happens at the entity boundary.
    //
    // Per Finacle CIF_LIST: PII columns in list views always show masked values.
    // The view page uses controller-provided masked values (PiiMaskingUtil)
    // for consistency; these accessors extend the same pattern to list views.

    /** Masked PAN for list/display — never exposes full decrypted PAN to view layer. */
    @Transient
    public String getMaskedPan() {
        return com.finvanta.util.PiiMaskingUtil.maskPan(this.panNumber);
    }

    /** Masked Aadhaar for list/display — per UIDAI, only last 4 digits visible. */
    @Transient
    public String getMaskedAadhaar() {
        return com.finvanta.util.PiiMaskingUtil.maskAadhaar(this.aadhaarNumber);
    }

    /** Masked mobile for list/display — only last 4 digits visible. */
    @Transient
    public String getMaskedMobile() {
        return com.finvanta.util.PiiMaskingUtil.maskMobile(this.mobileNumber);
    }

    /**
     * Computes CKYC account type from customer type.
     * Per CERSAI: INDIVIDUAL/JOINT/MINOR/NRI → INDIVIDUAL; rest → NON_INDIVIDUAL
     *
     * <p>CBS Tier-1 (Gap 1): Delegates to {@link CustomerType#getCkycAccountType()} via
     * enum lookup. Falls back to "INDIVIDUAL" for null/unrecognized values.
     */
    public void computeCkycAccountType() {
        CustomerType ct = CustomerType.fromString(customerType);
        this.ckycAccountType = ct != null ? ct.getCkycAccountType() : "NON_INDIVIDUAL";
    }

    // === KYC Helpers ===

    /**
     * Returns the re-KYC period in years based on risk category.
     * Per RBI Master Direction on KYC 2016 Section 16:
     *   LOW    → 10 years
     *   MEDIUM → 8 years
     *   HIGH   → 2 years
     *
     * <p>CBS Tier-1 (Gap 1): Delegates to {@link KycRiskCategory#getRenewalYears()} via
     * enum lookup. Falls back to MEDIUM (8 years) for null/unrecognized values.
     */
    public int getKycRenewalYears() {
        KycRiskCategory risk = KycRiskCategory.fromString(kycRiskCategory);
        return risk != null ? risk.getRenewalYears() : KycRiskCategory.MEDIUM.getRenewalYears();
    }

    /**
     * Computes and sets kycExpiryDate from kycVerifiedDate + risk-based period.
     * Called after KYC verification and after risk category changes.
     */
    public void computeKycExpiry() {
        if (kycVerifiedDate != null) {
            this.kycExpiryDate = kycVerifiedDate.plusYears(getKycRenewalYears());
        }
    }

    /**
     * Returns true if KYC has expired (past expiry date).
     * Per CBS standards: uses provided business date, NOT LocalDate.now().
     * The no-arg overload is retained for JSP EL compatibility (view layer)
     * where business date is not directly available — uses system date as
     * approximation. Service-layer code MUST use the parameterized version.
     */
    public boolean isKycExpired(LocalDate businessDate) {
        if (kycExpiryDate == null) return !kycVerified;
        return businessDate.isAfter(kycExpiryDate);
    }

    /** JSP/view-layer convenience — uses system date as approximation. */
    public boolean isKycExpired() {
        return isKycExpired(LocalDate.now());
    }

    /**
     * Returns true if KYC is expiring within the next 90 days.
     * Per CBS standards: uses provided business date, NOT LocalDate.now().
     */
    public boolean isKycExpiringSoon(LocalDate businessDate) {
        if (kycExpiryDate == null) return false;
        return businessDate.isAfter(kycExpiryDate.minusDays(90))
                && !businessDate.isAfter(kycExpiryDate);
    }

    /** JSP/view-layer convenience — uses system date as approximation. */
    public boolean isKycExpiringSoon() {
        return isKycExpiringSoon(LocalDate.now());
    }

    // === PII Immutability Guard (GAP 2 — Tier-1 defense-in-depth) ===

    /**
     * CBS Tier-1 CRITICAL: PAN and Aadhaar are IMMUTABLE after initial CIF creation.
     * Per RBI KYC Master Direction 2016: PAN/Aadhaar define the CIF identity and
     * must never be changed — correction requires CIF closure and re-creation.
     *
     * <p>This {@code @PreUpdate} hook is the LAST LINE OF DEFENSE. The service layer
     * ({@code CustomerCifServiceImpl.updateCustomer}) already excludes PAN/Aadhaar
     * from the mutable field copy, and the API controller rejects requests with
     * PAN/Aadhaar in the body. But if any future code path (batch job, admin tool,
     * direct repo save) accidentally modifies these fields, this hook catches it
     * at the JPA boundary — before the SQL UPDATE is issued.
     *
     * <p>Per Finacle CIF_MASTER: immutable fields are enforced by DB triggers.
     * JPA {@code @PreUpdate} is the closest equivalent in Spring Boot without
     * requiring DB-vendor-specific DDL.
     *
     * <p><b>Mechanism:</b> Stores the original PAN/Aadhaar hashes at load time via
     * {@code @PostLoad}. On {@code @PreUpdate}, recomputes hashes from current field
     * values and compares. If changed, throws {@code IllegalStateException} which
     * aborts the transaction. Uses hashes (not plaintext) because the encrypted
     * ciphertext changes on every read (random IV), so direct comparison is impossible.
     */
    @Transient
    private String originalPanHash;

    @Transient
    private String originalAadhaarHash;

    @PostLoad
    void snapshotImmutableFields() {
        this.originalPanHash = this.panHash;
        this.originalAadhaarHash = this.aadhaarHash;
    }

    @PreUpdate
    void enforceImmutablePii() {
        // Only enforce if snapshots were taken (i.e., entity was loaded from DB,
        // not a freshly created entity going through its first save).
        if (originalPanHash != null) {
            // CBS Tier-1: Detect BOTH modification AND nullification of PAN.
            // The previous implementation only checked (originalPanHash != null && panHash != null),
            // which short-circuited when panHash was set to null — silently allowing PAN deletion.
            // Per RBI KYC Master Direction 2016: PAN defines the CIF identity and must never be
            // removed or changed after creation. Correction requires CIF closure and re-creation.
            if (!originalPanHash.equals(panHash)) {
                throw new IllegalStateException(
                        "CBS IMMUTABILITY VIOLATION: PAN number cannot be changed or removed after CIF creation. "
                                + "Per RBI KYC Master Direction 2016: PAN defines the CIF identity. "
                                + "Customer: " + customerNumber);
            }
        }
        if (originalAadhaarHash != null) {
            // CBS Tier-1: Same nullification guard for Aadhaar.
            if (!originalAadhaarHash.equals(aadhaarHash)) {
                throw new IllegalStateException(
                        "CBS IMMUTABILITY VIOLATION: Aadhaar number cannot be changed or removed after CIF creation. "
                                + "Per UIDAI / RBI KYC norms: Aadhaar is immutable post-CIF creation. "
                                + "Customer: " + customerNumber);
            }
        }
    }

    // === PII Hash Helpers ===

    /**
     * Computes SHA-256 hash of PAN for de-duplication.
     * Called when panNumber is set during customer creation/update.
     */
    public void computePanHash() {
        this.panHash = PiiHashUtil.computeSha256(this.panNumber);
    }

    /**
     * Computes SHA-256 hash of Aadhaar for de-duplication.
     * Called when aadhaarNumber is set during customer creation/update.
     */
    public void computeAadhaarHash() {
        this.aadhaarHash = PiiHashUtil.computeSha256(this.aadhaarNumber);
    }
}

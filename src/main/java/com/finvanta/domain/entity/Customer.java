package com.finvanta.domain.entity;

import com.finvanta.config.PiiEncryptionConverter;

import jakarta.persistence.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
            @Index(name = "idx_cust_pan_hash", columnList = "tenant_id, pan_hash"),
            @Index(name = "idx_cust_aadhaar_hash", columnList = "tenant_id, aadhaar_hash"),
            @Index(name = "idx_cust_kyc_expiry", columnList = "tenant_id, kyc_expiry_date"),
            @Index(name = "idx_cust_tenant_branch", columnList = "tenant_id, branch_id")
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

    @ManyToOne(fetch = FetchType.LAZY)
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

    public String getFullName() {
        return firstName + " " + lastName;
    }

    // === KYC Helpers ===

    /**
     * Returns the re-KYC period in years based on risk category.
     * Per RBI Master Direction on KYC 2016 Section 16:
     *   LOW    → 10 years
     *   MEDIUM → 8 years
     *   HIGH   → 2 years
     */
    public int getKycRenewalYears() {
        if ("HIGH".equals(kycRiskCategory)) return 2;
        if ("LOW".equals(kycRiskCategory)) return 10;
        return 8; // MEDIUM default
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

    // === PII Hash Helpers ===

    /**
     * Computes SHA-256 hash of PAN for de-duplication.
     * Called when panNumber is set during customer creation/update.
     */
    public void computePanHash() {
        this.panHash = computeSha256(this.panNumber);
    }

    /**
     * Computes SHA-256 hash of Aadhaar for de-duplication.
     * Called when aadhaarNumber is set during customer creation/update.
     */
    public void computeAadhaarHash() {
        this.aadhaarHash = computeSha256(this.aadhaarNumber);
    }

    private static String computeSha256(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

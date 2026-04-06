package com.finvanta.domain.entity;

import com.finvanta.config.PiiEncryptionConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "customers", indexes = {
    @Index(name = "idx_cust_tenant_custno", columnList = "tenant_id, customer_number", unique = true),
    @Index(name = "idx_cust_pan", columnList = "tenant_id, pan_number"),
    @Index(name = "idx_cust_aadhaar", columnList = "tenant_id, aadhaar_number")
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

    @Column(name = "cibil_score")
    private Integer cibilScore;

    @Column(name = "customer_type", length = 20)
    private String customerType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

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
}

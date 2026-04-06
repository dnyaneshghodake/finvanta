package com.finvanta.domain.entity;

import com.finvanta.domain.enums.CollateralType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS Collateral Master per Finacle COLMAS / Temenos AA.COLLATERAL.
 *
 * Every secured loan product (Gold Loan, LAP, Vehicle Loan, FD-backed) requires
 * structured collateral tracking with:
 *   - Collateral type and type-specific attributes
 *   - Market value and forced sale value (FSV)
 *   - Valuation details (appraiser, date, validity)
 *   - Lien status (CREATED, REGISTERED, RELEASED)
 *   - Insurance tracking (for property/vehicle)
 *   - LTV (Loan-to-Value) ratio enforcement
 *
 * Per RBI norms:
 *   - Gold Loan: max LTV 75% (RBI circular 2020)
 *   - Housing Loan: max LTV 75-90% based on loan amount
 *   - Vehicle Loan: max LTV 85%
 *   - Collateral must be revalued periodically (annually for property)
 *
 * Lifecycle: PENDING -> VERIFIED -> LIEN_CREATED -> LIEN_REGISTERED -> RELEASED
 *
 * A single loan can have multiple collaterals (e.g., property + guarantor FD).
 * A single collateral can secure multiple loans (shared collateral with LTV split).
 */
@Entity
@Table(name = "collaterals", indexes = {
    @Index(name = "idx_collateral_tenant_ref", columnList = "tenant_id, collateral_ref", unique = true),
    @Index(name = "idx_collateral_loan", columnList = "tenant_id, loan_application_id"),
    @Index(name = "idx_collateral_type", columnList = "tenant_id, collateral_type")
})
@Getter
@Setter
@NoArgsConstructor
public class Collateral extends BaseEntity {

    @Column(name = "collateral_ref", nullable = false, length = 40)
    private String collateralRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    // --- Collateral Classification ---

    @Enumerated(EnumType.STRING)
    @Column(name = "collateral_type", nullable = false, length = 30)
    private CollateralType collateralType;

    @Column(name = "description", length = 500)
    private String description;

    // --- Ownership ---

    @Column(name = "owner_name", nullable = false, length = 200)
    private String ownerName;

    /** SELF, SPOUSE, PARENT, GUARANTOR, THIRD_PARTY */
    @Column(name = "owner_relationship", nullable = false, length = 30)
    private String ownerRelationship;

    // --- Gold-specific fields (per RBI Gold Loan Guidelines 2020) ---

    /** Gold purity: 22K, 24K, 18K */
    @Column(name = "gold_purity", length = 10)
    private String goldPurity;

    /** Gross weight in grams */
    @Column(name = "gold_weight_grams", precision = 10, scale = 3)
    private BigDecimal goldWeightGrams;

    /** Net weight after deducting stones/impurities */
    @Column(name = "gold_net_weight_grams", precision = 10, scale = 3)
    private BigDecimal goldNetWeightGrams;

    /** Rate per gram used for valuation */
    @Column(name = "gold_rate_per_gram", precision = 10, scale = 2)
    private BigDecimal goldRatePerGram;

    // --- Property-specific fields (for LAP / Home Loan) ---

    @Column(name = "property_address", length = 500)
    private String propertyAddress;

    /** RESIDENTIAL, COMMERCIAL, INDUSTRIAL, LAND, AGRICULTURAL */
    @Column(name = "property_type", length = 30)
    private String propertyType;

    @Column(name = "property_area_sqft", precision = 12, scale = 2)
    private BigDecimal propertyAreaSqft;

    @Column(name = "registration_number", length = 100)
    private String registrationNumber;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    // --- Vehicle-specific fields ---

    @Column(name = "vehicle_type", length = 30)
    private String vehicleType;

    @Column(name = "vehicle_registration", length = 20)
    private String vehicleRegistration;

    @Column(name = "vehicle_make", length = 50)
    private String vehicleMake;

    @Column(name = "vehicle_model", length = 50)
    private String vehicleModel;

    @Column(name = "vehicle_year")
    private Integer vehicleYear;

    // --- FD-specific fields ---

    @Column(name = "fd_number", length = 50)
    private String fdNumber;

    @Column(name = "fd_bank_name", length = 100)
    private String fdBankName;

    @Column(name = "fd_amount", precision = 18, scale = 2)
    private BigDecimal fdAmount;

    @Column(name = "fd_maturity_date")
    private LocalDate fdMaturityDate;

    // --- Valuation ---

    @Column(name = "market_value", precision = 18, scale = 2)
    private BigDecimal marketValue;

    /** Forced Sale Value - distress sale estimate (typically 70-80% of market) */
    @Column(name = "forced_sale_value", precision = 18, scale = 2)
    private BigDecimal forcedSaleValue;

    @Column(name = "valuation_date")
    private LocalDate valuationDate;

    @Column(name = "valuation_amount", precision = 18, scale = 2)
    private BigDecimal valuationAmount;

    @Column(name = "valuator_name", length = 200)
    private String valuatorName;

    @Column(name = "valuator_firm", length = 200)
    private String valuatorFirm;

    @Column(name = "valuator_license", length = 50)
    private String valuatorLicense;

    /** Valuation report validity in months (typically 6-12) */
    @Column(name = "valuation_validity_months")
    private Integer valuationValidityMonths;

    // --- Lien Status ---

    /** PENDING, LIEN_CREATED, LIEN_REGISTERED, RELEASED, INVOKED */
    @Column(name = "lien_status", nullable = false, length = 20)
    private String lienStatus = "PENDING";

    @Column(name = "lien_creation_date")
    private LocalDate lienCreationDate;

    @Column(name = "lien_reference", length = 100)
    private String lienReference;

    // --- Insurance (for property/vehicle) ---

    @Column(name = "insurance_policy_number", length = 50)
    private String insurancePolicyNumber;

    @Column(name = "insurance_company", length = 200)
    private String insuranceCompany;

    @Column(name = "insurance_expiry_date")
    private LocalDate insuranceExpiryDate;

    @Column(name = "insurance_amount", precision = 18, scale = 2)
    private BigDecimal insuranceAmount;

    /** ACTIVE, RELEASED, INVOKED, EXPIRED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    /**
     * Calculates LTV ratio: loan amount / collateral market value.
     * Returns null if market value is zero or null.
     */
    public BigDecimal calculateLtv(BigDecimal loanAmount) {
        if (marketValue == null || marketValue.signum() <= 0) {
            return null;
        }
        return loanAmount.multiply(new BigDecimal("100"))
            .divide(marketValue, 2, java.math.RoundingMode.HALF_UP);
    }
}

package com.finvanta.domain.enums;

/**
 * CBS AML/CFT Customer Risk Category per RBI KYC Master Direction 2016 Section 16.
 *
 * <p>Per RBI: banks must adopt a risk-based approach to Customer Due Diligence (CDD).
 * Risk category determines:
 * <ul>
 *   <li>KYC re-verification frequency (LOW=10yr, MEDIUM=8yr, HIGH=2yr, VERY_HIGH=annual)</li>
 *   <li>Transaction monitoring sensitivity (higher risk = lower alert thresholds)</li>
 *   <li>Enhanced Due Diligence (EDD) requirements for HIGH and above</li>
 *   <li>Senior management approval for VERY_HIGH and PEP accounts</li>
 * </ul>
 *
 * <p>Per Finacle AML_RISK / Temenos KYC.RISK.CATEGORY.
 */
public enum AmlRiskCategory {

    /** Low risk: salaried individuals, government employees, pensioners */
    LOW,

    /** Medium risk: self-employed, small business, NRIs from low-risk countries */
    MEDIUM,

    /** High risk: cash-intensive businesses, high-value accounts, NRIs from high-risk countries */
    HIGH,

    /** Very high risk: trusts, charities, shell companies, complex ownership structures */
    VERY_HIGH,

    /**
     * Politically Exposed Person per RBI KYC MD Section 2(qa).
     * Requires senior management approval for account opening and
     * enhanced ongoing monitoring. Includes domestic and foreign PEPs,
     * their family members, and close associates.
     */
    PEP,

    /** Customer matched against sanctions list — account frozen pending investigation */
    SANCTIONED;

    /** Returns true if this category requires Enhanced Due Diligence */
    public boolean requiresEdd() {
        return this == HIGH || this == VERY_HIGH || this == PEP || this == SANCTIONED;
    }

    /** Returns true if this category requires senior management approval */
    public boolean requiresSeniorApproval() {
        return this == VERY_HIGH || this == PEP || this == SANCTIONED;
    }

    /** Returns KYC re-verification period in years per RBI norms */
    public int kycReviewPeriodYears() {
        return switch (this) {
            case LOW -> 10;
            case MEDIUM -> 8;
            case HIGH -> 2;
            case VERY_HIGH, PEP, SANCTIONED -> 1;
        };
    }
}

package com.finvanta.domain.enums;

/**
 * CBS KYC Risk Category per RBI Master Direction on KYC 2016 Section 16.
 *
 * <p>Closed enumeration — prevents arbitrary strings from being persisted.
 * Per Finacle CIF_MASTER / Temenos CUSTOMER: risk categories are a finite set
 * defined by the regulator; no bank-specific extensions are permitted.
 *
 * <p>Re-KYC periods per RBI KYC Direction:
 * <ul>
 *   <li>{@code LOW}    — 10 years (salaried, known employer, low-value)</li>
 *   <li>{@code MEDIUM} — 8 years  (self-employed, moderate-value)</li>
 *   <li>{@code HIGH}   — 2 years  (PEP, high-value, complex structures, adverse media)</li>
 * </ul>
 *
 * <p>DB storage: VARCHAR via {@code @Enumerated(EnumType.STRING)}.
 */
public enum KycRiskCategory {

    LOW(10, "Low Risk"),
    MEDIUM(8, "Medium Risk"),
    HIGH(2, "High Risk");

    private final int renewalYears;
    private final String displayName;

    KycRiskCategory(int renewalYears, String displayName) {
        this.renewalYears = renewalYears;
        this.displayName = displayName;
    }

    /** Re-KYC period in years per RBI KYC Direction Section 16. */
    public int getRenewalYears() {
        return renewalYears;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Safe valueOf that returns null instead of throwing IllegalArgumentException.
     * Used for server-side validation of user input from forms and API.
     */
    public static KycRiskCategory fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

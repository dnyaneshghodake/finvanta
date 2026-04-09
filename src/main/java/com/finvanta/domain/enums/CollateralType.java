package com.finvanta.domain.enums;

/**
 * CBS Collateral Type per Finacle COLMAS / Temenos AA.COLLATERAL.
 *
 * Per RBI norms, each collateral type has different:
 *   - LTV (Loan-to-Value) limits
 *   - Valuation frequency requirements
 *   - Insurance requirements
 *   - Documentation requirements
 *
 * RBI LTV limits (per type):
 *   GOLD:        75% (RBI circular dated 24 Feb 2020)
 *   PROPERTY:    75-90% (based on loan amount slab, RBI Housing Finance)
 *   VEHICLE:     85% (industry standard)
 *   FD:          90% (of FD value)
 *   SHARES:      50% (of market value, per RBI NBFC guidelines)
 *   MACHINERY:   70% (of forced sale value)
 *   UNSECURED:   N/A (no collateral)
 */
public enum CollateralType {
    GOLD, // Gold ornaments/bullion (RBI Gold Loan Guidelines)
    PROPERTY, // Immovable property (LAP, Home Loan)
    VEHICLE, // Motor vehicle (Car Loan, Commercial Vehicle)
    FD, // Fixed Deposit (lien on FD)
    SHARES, // Listed equity shares (pledge)
    MACHINERY, // Plant & Machinery (MSME loans)
    INVENTORY, // Stock/Inventory (Working Capital)
    RECEIVABLES, // Book Debts / Receivables (Working Capital)
    UNSECURED; // No collateral (Personal Loan, Education Loan)

    /**
     * Returns the RBI-mandated maximum LTV ratio for this collateral type.
     * Returns null for UNSECURED (no LTV applicable).
     */
    public java.math.BigDecimal getMaxLtvPercent() {
        return switch (this) {
            case GOLD -> new java.math.BigDecimal("75.00");
            case PROPERTY -> new java.math.BigDecimal("80.00");
            case VEHICLE -> new java.math.BigDecimal("85.00");
            case FD -> new java.math.BigDecimal("90.00");
            case SHARES -> new java.math.BigDecimal("50.00");
            case MACHINERY -> new java.math.BigDecimal("70.00");
            case INVENTORY -> new java.math.BigDecimal("75.00");
            case RECEIVABLES -> new java.math.BigDecimal("75.00");
            case UNSECURED -> null;
        };
    }

    /** Returns true if this collateral type requires insurance */
    public boolean requiresInsurance() {
        return this == PROPERTY || this == VEHICLE || this == MACHINERY;
    }

    /** Returns true if this collateral type requires periodic revaluation */
    public boolean requiresPeriodicValuation() {
        return this == PROPERTY || this == GOLD || this == SHARES || this == MACHINERY || this == INVENTORY;
    }

    /** Returns the recommended revaluation frequency in months */
    public int getValuationFrequencyMonths() {
        return switch (this) {
            case GOLD -> 6; // Semi-annual (gold price volatility)
            case PROPERTY -> 12; // Annual
            case SHARES -> 3; // Quarterly (market volatility)
            case MACHINERY -> 12; // Annual
            case INVENTORY -> 3; // Quarterly
            default -> 0;
        };
    }
}

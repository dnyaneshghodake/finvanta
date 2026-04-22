package com.finvanta.domain.enums;

/**
 * CBS Priority Sector Lending Category per RBI Master Direction on PSL 2020.
 *
 * <p>Per RBI: Domestic scheduled commercial banks must achieve the following
 * PSL targets as a percentage of Adjusted Net Bank Credit (ANBC):
 * <ul>
 *   <li>Total PSL: 40% of ANBC</li>
 *   <li>Agriculture: 18% of ANBC (of which 10% direct agriculture)</li>
 *   <li>Micro Enterprises: 7.5% of ANBC</li>
 *   <li>Weaker Sections: 12% of ANBC (cross-cutting across categories)</li>
 * </ul>
 *
 * <p>Shortfall in PSL targets results in mandatory deposits to RIDF/MSME Fund
 * at below-market rates (currently Bank Rate minus 2%).
 *
 * <p>Per Finacle PSL_MASTER / Temenos AA.PSL.CATEGORY.
 */
public enum PslCategory {

    /** Agriculture — 18% target. Includes crop loans, farm infrastructure, allied activities */
    AGRICULTURE,

    /** Micro Enterprise — 7.5% target. Investment <= 1 crore, turnover <= 5 crore */
    MICRO_ENTERPRISE,

    /** Small Enterprise. Investment <= 10 crore, turnover <= 50 crore */
    SMALL_ENTERPRISE,

    /** Medium Enterprise. Investment <= 50 crore, turnover <= 250 crore */
    MEDIUM_ENTERPRISE,

    /** Education loans per RBI model scheme (up to 20 lakh domestic, 30 lakh abroad) */
    EDUCATION,

    /** Housing loans up to 35 lakh (metro) / 25 lakh (non-metro) per RBI limits */
    HOUSING,

    /** Social Infrastructure — schools, hospitals, sanitation, drinking water */
    SOCIAL_INFRASTRUCTURE,

    /** Renewable Energy — solar, wind, biomass, micro-hydel */
    RENEWABLE_ENERGY,

    /** Export Credit per RBI guidelines */
    EXPORT_CREDIT,

    /** Not classified under any priority sector */
    NON_PSL;

    /**
     * Returns the RBI PSL target percentage for this category.
     * Note: Micro Enterprise has a separate sub-target within MSME.
     * Weaker Section (12%) is a cross-cutting target tracked separately.
     */
    public java.math.BigDecimal targetPercentage() {
        return switch (this) {
            case AGRICULTURE -> new java.math.BigDecimal("18.00");
            case MICRO_ENTERPRISE -> new java.math.BigDecimal("7.50");
            default -> java.math.BigDecimal.ZERO; // No individual target
        };
    }

    /** Returns true if this category counts toward the 40% PSL target */
    public boolean isPrioritySector() {
        return this != NON_PSL;
    }
}

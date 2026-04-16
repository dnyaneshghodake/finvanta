package com.finvanta.domain.enums;

/**
 * CBS Product Lifecycle Status per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * Per Tier-1 CBS (Finacle/Temenos/BNP) product lifecycle:
 *   DRAFT      → Product being configured. Cannot be used for origination.
 *                GL mapping and parameters may be incomplete.
 *   ACTIVE     → Product is live. New loans/accounts can be created.
 *                EOD operations (accrual, NPA, provisioning) run normally.
 *   SUSPENDED  → Temporarily paused. No new origination allowed.
 *                Existing accounts continue — EOD operations run normally.
 *                Per RBI: used when regulatory review is pending on a product.
 *   RETIRED    → Permanently closed. No new origination. Existing accounts
 *                run to maturity. Per Finacle PDDEF: retired products cannot
 *                be reactivated — create a new product code instead.
 *
 * Allowed transitions:
 *   DRAFT → ACTIVE (activation — all GL codes must be mapped)
 *   ACTIVE → SUSPENDED (temporary pause — reversible)
 *   SUSPENDED → ACTIVE (reactivation)
 *   ACTIVE → RETIRED (permanent closure — irreversible)
 *   SUSPENDED → RETIRED (permanent closure — irreversible)
 *
 * Per RBI Fair Practices Code 2023:
 * - Product terms must be transparent and documented
 * - Product changes must be communicated to existing borrowers
 * - Retired products must honor existing contractual terms
 */
public enum ProductStatus {

    /** Product being configured — not yet available for origination */
    DRAFT,

    /** Product is live — available for new loan/account origination */
    ACTIVE,

    /** Temporarily suspended — no new origination, existing accounts continue */
    SUSPENDED,

    /** Permanently retired — no new origination, existing accounts run to maturity */
    RETIRED;

    /** Returns true if new loans/accounts can be originated under this product */
    public boolean isOriginationAllowed() {
        return this == ACTIVE;
    }

    /** Returns true if EOD operations (accrual, NPA, provisioning) should run */
    public boolean isEodOperationsAllowed() {
        return this == ACTIVE || this == SUSPENDED || this == RETIRED;
    }

    /** Returns true if this is a terminal state (cannot transition further except to RETIRED) */
    public boolean isRetired() {
        return this == RETIRED;
    }

    /**
     * Safe valueOf that returns null instead of throwing IllegalArgumentException.
     * Used for server-side validation of user input.
     */
    public static ProductStatus fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

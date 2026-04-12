package com.finvanta.domain.enums;

/**
 * CBS CASA Account Type per Finacle PDDEF ACCT_TYPE / Temenos ACCOUNT CATEGORY.
 *
 * Per RBI Banking Regulation Act and Finacle/Temenos CASA standards:
 * Account type is a coded field that drives interest calculation, GL mapping,
 * regulatory reporting (CRR/SLR), and product configuration.
 *
 * Using an enum prevents data corruption from typos (e.g., "SAVING" vs "SAVINGS")
 * that would silently break interest calculation queries, dormancy classification,
 * and RBI regulatory reporting.
 *
 * Account Type Hierarchy:
 *   SAVINGS*  — Interest-bearing, RBI regulated rate, quarterly credit
 *   CURRENT*  — Zero interest per RBI norms, business operations
 *
 * Per Finacle PDDEF: each account type maps to a product code which determines
 * GL codes, interest rates, minimum balance, and fee schedules.
 */
public enum DepositAccountType {

    /** Individual savings (SB), interest-bearing, RBI regulated rate */
    SAVINGS("Savings Account", true),

    /** NRE/NRO savings per FEMA guidelines */
    SAVINGS_NRI("NRI Savings (NRE/NRO)", true),

    /** Minor's account (guardian-operated until 18) */
    SAVINGS_MINOR("Minor Savings Account", true),

    /** Joint account (Either/Survivor, Former/Survivor) */
    SAVINGS_JOINT("Joint Savings Account", true),

    /** Pradhan Mantri Jan Dhan Yojana (zero-balance, RBI mandated) */
    SAVINGS_PMJDY("PMJDY Zero Balance Savings", true),

    /** Business current account, zero interest per RBI norms */
    CURRENT("Current Account", false),

    /** Current with overdraft facility */
    CURRENT_OD("Current Account with OD", false);

    private final String displayName;
    private final boolean interestBearing;

    DepositAccountType(String displayName, boolean interestBearing) {
        this.displayName = displayName;
        this.interestBearing = interestBearing;
    }

    /** Human-readable name for UI display */
    public String getDisplayName() {
        return displayName;
    }

    /** Whether this account type earns interest (all SAVINGS types do, CURRENT does not) */
    public boolean isInterestBearing() {
        return interestBearing;
    }

    /** Whether this is a savings account type (starts with SAVINGS) */
    public boolean isSavings() {
        return name().startsWith("SAVINGS");
    }

    /** Whether this is a current account type (starts with CURRENT) */
    public boolean isCurrent() {
        return name().startsWith("CURRENT");
    }

    /**
     * GL code resolution: savings accounts use GL 2010, current accounts use GL 2020.
     * Per Finacle PDDEF: GL mapping is product-driven, but the account type determines
     * the primary deposit liability GL for subledger reconciliation.
     */
    public String getDepositGlCode() {
        return isSavings() ? "2010" : "2020";
    }
}

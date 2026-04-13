package com.finvanta.domain.enums;

/**
 * CBS Fixed Deposit Lifecycle Status per Finacle TD_MASTER / Temenos FIXED.DEPOSIT.
 *
 * Lifecycle:
 *   ACTIVE → MATURED → CLOSED (normal maturity)
 *   ACTIVE → PREMATURE_CLOSED (early withdrawal with penalty)
 *   ACTIVE → MATURED → RENEWED (auto-renewal at maturity)
 *   ACTIVE → LIEN_MARKED (collateral for loan — still active, but closure blocked)
 *
 * Per RBI: FD status transitions must be audited with full before/after state.
 */
public enum FdStatus {

    /** FD is active — interest accruing, not yet matured */
    ACTIVE,

    /** FD has reached maturity date — pending closure or renewal */
    MATURED,

    /** FD closed normally after maturity — principal + interest credited to CASA */
    CLOSED,

    /** FD closed before maturity — penalty applied, reduced interest */
    PREMATURE_CLOSED,

    /** FD auto-renewed at maturity — new tenure started */
    RENEWED;

    /** Whether this is a terminal state (no further transitions) */
    public boolean isTerminal() {
        return this == CLOSED || this == PREMATURE_CLOSED;
    }

    /** Whether interest should continue to accrue */
    public boolean isAccrualActive() {
        return this == ACTIVE;
    }
}

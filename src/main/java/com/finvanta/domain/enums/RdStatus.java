package com.finvanta.domain.enums;

/**
 * CBS Recurring Deposit Status per Finacle RD_MASTER / Temenos FIXED.DEPOSIT.
 *
 * <p>RD Lifecycle: ACTIVE → MATURED → CLOSED
 *                  ACTIVE → PREMATURE_CLOSED
 *                  ACTIVE → DEFAULTED (3+ missed installments)
 *
 * <p>Per RBI: missed installments incur penalty but do not auto-close.
 * After 3 consecutive misses, the RD is marked DEFAULTED and the
 * customer must visit the branch to regularize or close.
 */
public enum RdStatus {

    /** Active — installments being collected, interest accruing */
    ACTIVE,

    /** Matured — all installments paid, maturity date reached */
    MATURED,

    /** Closed — maturity amount credited to CASA */
    CLOSED,

    /** Premature closed — closed before maturity with penalty */
    PREMATURE_CLOSED,

    /** Defaulted — 3+ consecutive missed installments */
    DEFAULTED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this == CLOSED || this == PREMATURE_CLOSED;
    }
}

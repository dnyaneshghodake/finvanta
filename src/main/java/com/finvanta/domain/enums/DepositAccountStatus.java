package com.finvanta.domain.enums;

/**
 * CBS CASA Account Lifecycle Status per Finacle CUSTACCT / Temenos ACCOUNT standards.
 *
 * Lifecycle transitions per RBI Banking Regulation Act:
 *   PENDING_ACTIVATION → ACTIVE (on first deposit or checker approval)
 *   ACTIVE → DORMANT (24 months no customer-initiated txn, per RBI KYC 2016 Sec 38)
 *   DORMANT → INOPERATIVE (10 years no txn, per RBI Unclaimed Deposits)
 *   ACTIVE → FROZEN (court order / regulatory / AML per PMLA)
 *   FROZEN → ACTIVE (on unfreeze by ADMIN)
 *   ACTIVE/DORMANT → CLOSED (customer request + zero balance)
 *   ACTIVE → DECEASED (death claim processing per RBI Nomination Guidelines)
 *
 * Per Finacle CUSTACCT: status drives all transaction gating logic
 * (isDebitAllowed, isCreditAllowed) and EOD processing decisions.
 */
public enum DepositAccountStatus {
    PENDING_ACTIVATION,  // Account created but not yet activated (maker-checker pending)
    ACTIVE,              // Normal operating state — all transactions allowed
    DORMANT,             // No customer-initiated txn for 24+ months (RBI KYC 2016 Sec 38)
    INOPERATIVE,         // No customer-initiated txn for 10+ years (RBI Unclaimed Deposits)
    FROZEN,              // Regulatory/court freeze — partial or total block per PMLA
    CLOSED,              // Terminal state — zero balance, no further transactions
    DECEASED;            // Death claim processing — special handling per RBI Nomination

    /** Returns true if the account is in a terminal state (no further lifecycle transitions) */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /** Returns true if the account is in normal operating state */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /** Returns true if the account is frozen (any freeze type) */
    public boolean isFrozen() {
        return this == FROZEN;
    }

    /** Returns true if the account is dormant (no customer txn for 24+ months) */
    public boolean isDormant() {
        return this == DORMANT;
    }

    /** Returns true if the account is closed */
    public boolean isClosed() {
        return this == CLOSED;
    }

    /**
     * Returns true if the account can participate in EOD interest accrual.
     * Per Finacle EOD: only ACTIVE accounts accrue interest.
     * DORMANT accounts continue to accrue per RBI (interest is a right).
     * FROZEN accounts continue to accrue per PMLA (freeze ≠ forfeiture).
     */
    public boolean isInterestAccrualEligible() {
        return this == ACTIVE || this == DORMANT || this == FROZEN;
    }

    /**
     * Returns true if the account can transition to DORMANT during EOD.
     * Only ACTIVE accounts can become dormant.
     */
    public boolean canBecomeDormant() {
        return this == ACTIVE;
    }
}
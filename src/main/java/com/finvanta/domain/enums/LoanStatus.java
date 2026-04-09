package com.finvanta.domain.enums;

/**
 * Loan account lifecycle status per RBI IRAC norms, Early Warning Framework,
 * and CDR/SDR restructuring guidelines.
 *
 * DPD-based classification lifecycle (RBI Master Circular on IRAC):
 *   ACTIVE → SMA_0 (1-30 DPD) → SMA_1 (31-60 DPD) → SMA_2 (61-90 DPD)
 *   → NPA_SUBSTANDARD (91-365 DPD) → NPA_DOUBTFUL (366-1095 DPD) → NPA_LOSS (>1095 DPD)
 *
 * RBI mandates SMA reporting to CRILC (Central Repository of Information on Large Credits)
 * for exposures >= ₹5 crore. SMA classification is mandatory for all accounts.
 *
 * Restructured accounts (RBI CDR/SDR framework):
 *   RESTRUCTURED status is set via explicit maker-checker action when loan terms
 *   are modified (rate reduction, tenure extension, moratorium). Restructured accounts
 *   retain their DPD-based SMA/NPA classification for provisioning purposes but are
 *   flagged separately for CRILC reporting and higher provisioning (5% for first 2 years).
 *
 * Terminal states: CLOSED (fully repaid), WRITTEN_OFF (loss recognized in P&L).
 */
public enum LoanStatus {
    ACTIVE, // Standard performing asset — 0 DPD
    SMA_0, // RBI Early Warning: 1-30 DPD (Special Mention Account)
    SMA_1, // RBI Early Warning: 31-60 DPD
    SMA_2, // RBI Early Warning: 61-90 DPD
    NPA_SUBSTANDARD, // RBI IRAC: 91-365 DPD
    NPA_DOUBTFUL, // RBI IRAC: 366-1095 DPD
    NPA_LOSS, // RBI IRAC: >1095 DPD
    CLOSED, // Fully repaid — terminal state
    WRITTEN_OFF, // Loss recognized in P&L — terminal state
    RESTRUCTURED; // RBI CDR/SDR: Modified loan terms (rate/tenure/moratorium)

    public boolean isNpa() {
        return this == NPA_SUBSTANDARD || this == NPA_DOUBTFUL || this == NPA_LOSS;
    }

    public boolean isSma() {
        return this == SMA_0 || this == SMA_1 || this == SMA_2;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }

    public boolean isActive() {
        return this == ACTIVE;
    }

    /** Returns true if this is a terminal state (no further lifecycle transitions) */
    public boolean isTerminal() {
        return this == CLOSED || this == WRITTEN_OFF;
    }

    /**
     * Returns true if the account is performing (standard asset for provisioning).
     * Per RBI IRAC, ACTIVE and SMA accounts are standard assets.
     * RESTRUCTURED accounts have separate provisioning treatment (5% for 2 years).
     */
    public boolean isPerforming() {
        return this == ACTIVE || isSma() || this == RESTRUCTURED;
    }

    /**
     * Returns true if income recognition should be stopped (NPA accounts).
     * Per RBI Master Circular on IRAC Norms, interest accrued on NPA accounts
     * must not be recognized as income in P&L — it should be tracked in a
     * memorandum (suspense) account only.
     */
    public boolean isIncomeReversalRequired() {
        return isNpa();
    }
}

package com.finvanta.domain.enums;

/**
 * Loan account lifecycle status per RBI IRAC and Early Warning Framework.
 *
 * Standard lifecycle:
 *   ACTIVE → SMA_0 (1-30 DPD) → SMA_1 (31-60 DPD) → SMA_2 (61-90 DPD)
 *   → NPA_SUBSTANDARD (91-365 DPD) → NPA_DOUBTFUL (366-1095 DPD) → NPA_LOSS (>1095 DPD)
 *
 * RBI mandates SMA reporting to CRILC (Central Repository of Information on Large Credits)
 * for exposures >= ₹5 crore. SMA classification is mandatory for all accounts.
 */
public enum LoanStatus {
    ACTIVE,
    SMA_0,              // RBI Early Warning: 1-30 DPD (Special Mention Account)
    SMA_1,              // RBI Early Warning: 31-60 DPD
    SMA_2,              // RBI Early Warning: 61-90 DPD
    NPA_SUBSTANDARD,    // RBI IRAC: 91-365 DPD
    NPA_DOUBTFUL,       // RBI IRAC: 366-1095 DPD
    NPA_LOSS,           // RBI IRAC: >1095 DPD
    CLOSED,
    WRITTEN_OFF,
    RESTRUCTURED,
    OVERDUE;

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

    /** Returns true if income recognition should be stopped (NPA accounts) */
    public boolean isIncomeReversalRequired() {
        return isNpa();
    }
}

package com.finvanta.domain.enums;

/**
 * CBS Business Calendar Day Status per Finacle/Temenos Day Control standards.
 *
 * Day lifecycle:
 *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
 *
 * Rules:
 * - Only ONE day can be in DAY_OPEN status at a time per tenant
 * - Financial transactions are only allowed when day status is DAY_OPEN
 * - EOD_RUNNING blocks all new transactions while batch processes
 * - DAY_CLOSED is terminal — day cannot be reopened
 * - NOT_OPENED is the initial state for all calendar dates
 */
public enum DayStatus {
    NOT_OPENED, // Initial state — day has not been opened yet
    DAY_OPEN, // Day is open for transactions
    EOD_RUNNING, // End-of-day batch is in progress — no new transactions
    DAY_CLOSED; // Day is closed — terminal state, cannot reopen

    /** Returns true if financial transactions are allowed in this state */
    public boolean isTransactionAllowed() {
        return this == DAY_OPEN;
    }

    /** Returns true if EOD can be initiated from this state */
    public boolean canStartEod() {
        return this == DAY_OPEN;
    }

    /** Returns true if the day can be closed from this state */
    public boolean canClose() {
        return this == DAY_OPEN || this == EOD_RUNNING;
    }
}

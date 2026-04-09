package com.finvanta.domain.enums;

/**
 * CBS Business Calendar Day Status per Finacle DAYCTRL / Temenos COB standards.
 *
 * <h3>Tier-1 Branch-Level Day Control:</h3>
 * Per Finacle SOL architecture, day status is managed PER BRANCH.
 * Each branch independently transitions through the day lifecycle.
 *
 * Day lifecycle per branch:
 *   NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
 *
 * Rules:
 * - Only ONE day can be in DAY_OPEN status at a time PER BRANCH
 * - Financial transactions are only allowed when the BRANCH's day is DAY_OPEN
 * - EOD_RUNNING blocks new transactions at THAT BRANCH while batch processes
 * - DAY_CLOSED is terminal — day cannot be reopened at that branch
 * - NOT_OPENED is the initial state for all calendar dates at all branches
 *
 * Per Finacle:
 * - Branch A can be DAY_OPEN while Branch B is DAY_CLOSED
 * - Branch A can run EOD while Branch B is still DAY_OPEN
 * - HO consolidation runs only after ALL branches reach DAY_CLOSED or EOD_RUNNING
 */
public enum DayStatus {
    /** Initial state — day has not been opened yet at this branch */
    NOT_OPENED,

    /** Day is open for transactions at this branch */
    DAY_OPEN,

    /** End-of-day batch is in progress at this branch — no new transactions */
    EOD_RUNNING,

    /** Day is closed at this branch — terminal state, cannot reopen */
    DAY_CLOSED;

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

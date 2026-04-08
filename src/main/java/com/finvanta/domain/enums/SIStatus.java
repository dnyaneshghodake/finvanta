package com.finvanta.domain.enums;

/**
 * Standing Instruction lifecycle status per Finacle SI_MASTER / Temenos STANDING.ORDER.
 *
 * Lifecycle:
 *   PENDING_APPROVAL → ACTIVE → PAUSED → ACTIVE (resume)
 *   ACTIVE → EXPIRED (end date reached or loan closed)
 *   ACTIVE → CANCELLED (customer request)
 *   PENDING_APPROVAL → REJECTED (checker rejects)
 *
 * Per RBI Payment Systems Act 2007 and NPCI NACH framework:
 * - SI requires explicit customer mandate (written/digital)
 * - Customer can cancel SI at any time (RBI Customer Rights)
 * - Bank must notify customer on every SI execution (success or failure)
 */
public enum SIStatus {

    /** Maker submitted, awaiting checker approval per CBS dual-authorization */
    PENDING_APPROVAL,

    /** Active and eligible for EOD execution on nextExecutionDate */
    ACTIVE,

    /** Temporarily suspended by customer — skips execution until resumed */
    PAUSED,

    /** Terminated by customer request — terminal state */
    CANCELLED,

    /** End date reached or linked loan closed — terminal state */
    EXPIRED,

    /** Checker rejected the SI registration — terminal state */
    REJECTED;

    /** Returns true if this SI should be picked up by EOD execution */
    public boolean isExecutable() {
        return this == ACTIVE;
    }

    /** Returns true if this is a terminal state (no further transitions) */
    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED || this == REJECTED;
    }
}

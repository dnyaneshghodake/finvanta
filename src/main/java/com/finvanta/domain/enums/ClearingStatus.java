package com.finvanta.domain.enums;

/**
 * CBS Clearing Transaction State Machine per Finacle CLG_MASTER / RBI Payment Systems.
 *
 * OUTWARD state transitions:
 *   INITIATED → VALIDATED → SUSPENSE_POSTED → SENT_TO_NETWORK → SETTLED → COMPLETED
 *   INITIATED → VALIDATION_FAILED (terminal)
 *   SENT_TO_NETWORK → NETWORK_REJECTED → REVERSED (terminal)
 *   SENT_TO_NETWORK → SETTLEMENT_FAILED → REVERSED (terminal)
 *
 * INWARD state transitions:
 *   RECEIVED → VALIDATED → SUSPENSE_POSTED → CREDITED → COMPLETED
 *   RECEIVED → VALIDATION_FAILED → RETURNED (terminal)
 *   VALIDATED → CREDIT_FAILED → RETURNED (terminal)
 *
 * Per RBI: No direct jump to COMPLETED. Every intermediate state must be recorded
 * for audit trail and TAT (Turnaround Time) tracking.
 */
public enum ClearingStatus {

    // === OUTWARD States ===
    /** Clearing request initiated by customer/system */
    INITIATED,
    /** All validations passed (balance, limits, AML, IFSC) */
    VALIDATED,
    /** Customer debited, amount parked in outward suspense GL */
    SUSPENSE_POSTED,
    /** Sent to RBI/NPCI payment network */
    SENT_TO_NETWORK,
    /** RBI/NPCI confirmed settlement */
    SETTLED,
    /** Suspense cleared, transaction fully complete */
    COMPLETED,

    // === INWARD States ===
    /** Received from RBI/NPCI payment network */
    RECEIVED,
    /** Customer account credited from inward suspense */
    CREDITED,

    // === Failure/Terminal States ===
    /** Validation failed (balance, IFSC, limits) */
    VALIDATION_FAILED,
    /** Network rejected the transaction */
    NETWORK_REJECTED,
    /** Settlement failed at RBI/NPCI level */
    SETTLEMENT_FAILED,
    /** Credit to customer account failed (account frozen/closed) */
    CREDIT_FAILED,
    /** Reversal posted — funds returned to originator */
    REVERSED,
    /** Inward transaction returned to originating bank */
    RETURNED;

    /**
     * Whether this is a terminal state (no further transitions allowed).
     * Per RBI Payment Systems: VALIDATION_FAILED is semi-terminal — it allows
     * transition to RETURNED (explicit operator return to originating bank)
     * but no other transitions. All other terminal states are fully terminal.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == REVERSED
                || this == RETURNED || this == NETWORK_REJECTED;
    }

    /** Whether this state indicates the transaction is still in-flight */
    public boolean isInFlight() {
        return this == INITIATED || this == VALIDATED || this == SUSPENSE_POSTED
                || this == SENT_TO_NETWORK || this == RECEIVED || this == SETTLED
                || this == CREDITED;
    }

    /** Whether suspense GL has been posted (needs clearing on completion/failure) */
    public boolean isSuspenseActive() {
        return this == SUSPENSE_POSTED || this == SENT_TO_NETWORK || this == SETTLED;
    }

    /**
     * Validates whether a state transition is allowed per the CBS state machine.
     * Per RBI Payment Systems: no state skipping. Every intermediate state must
     * be recorded for audit trail and TAT tracking.
     *
     * @param target The target state to transition to
     * @return true if the transition is valid
     */
    public boolean canTransitionTo(ClearingStatus target) {
        if (this == target) return false; // No self-transition
        if (this.isTerminal()) return false; // Terminal = no transitions
        return switch (this) {
            // OUTWARD flow
            case INITIATED -> target == VALIDATED || target == VALIDATION_FAILED;
            case VALIDATED -> target == SUSPENSE_POSTED || target == VALIDATION_FAILED
                    || target == RETURNED; // RETURNED for inward return before suspense
            case SUSPENSE_POSTED -> target == SENT_TO_NETWORK || target == COMPLETED
                    || target == CREDITED || target == REVERSED
                    || target == CREDIT_FAILED; // COMPLETED for real-time rails
            case SENT_TO_NETWORK -> target == SETTLED || target == NETWORK_REJECTED
                    || target == SETTLEMENT_FAILED || target == REVERSED;
            case SETTLED -> target == COMPLETED;
            // INWARD flow
            case RECEIVED -> target == VALIDATED || target == VALIDATION_FAILED
                    || target == RETURNED; // RETURNED for direct inward return
            case CREDITED -> target == COMPLETED;
            // Failure states (can transition to reversal/return)
            case SETTLEMENT_FAILED -> target == REVERSED;
            case CREDIT_FAILED -> target == RETURNED;
            // Semi-terminal: VALIDATION_FAILED allows only RETURNED (operator return)
            case VALIDATION_FAILED -> target == RETURNED;
            // Terminal states
            case COMPLETED, NETWORK_REJECTED,
                    REVERSED, RETURNED -> false;
        };
    }
}

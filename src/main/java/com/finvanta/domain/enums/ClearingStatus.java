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

    /** Whether this is a terminal state (no further transitions allowed) */
    public boolean isTerminal() {
        return this == COMPLETED || this == VALIDATION_FAILED || this == REVERSED
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
}

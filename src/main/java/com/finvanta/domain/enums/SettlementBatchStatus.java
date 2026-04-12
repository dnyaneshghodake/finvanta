package com.finvanta.domain.enums;

/**
 * CBS Settlement Batch Status per Finacle SETTLEMENT_MASTER / RBI Settlement Framework.
 *
 * Lifecycle:
 *   PENDING → settlement submitted to RBI
 *   CONFIRMED → RBI confirms settlement (funds moved)
 *   RECONCILED → CBS confirms all clearing transactions are completed
 *   FAILED → settlement rejected by RBI
 */
public enum SettlementBatchStatus {

    /** Settlement submitted to RBI, awaiting confirmation */
    PENDING,
    /** RBI confirmed settlement — funds moved between banks */
    CONFIRMED,
    /** CBS reconciliation completed — all clearing transactions are COMPLETED */
    RECONCILED,
    /** Settlement rejected by RBI */
    FAILED;

    /** Whether this batch is awaiting RBI confirmation */
    public boolean isPending() { return this == PENDING; }

    /** Whether RBI has confirmed the settlement */
    public boolean isConfirmed() { return this == CONFIRMED; }

    /** Whether CBS reconciliation is complete */
    public boolean isReconciled() { return this == RECONCILED; }

    /** Whether this is a terminal state */
    public boolean isTerminal() { return this == RECONCILED || this == FAILED; }

    /**
     * Validates whether a state transition is allowed.
     * PENDING → CONFIRMED → RECONCILED (success path)
     * PENDING → FAILED (rejection path)
     */
    public boolean canTransitionTo(SettlementBatchStatus target) {
        if (this == target) return false;
        return switch (this) {
            case PENDING -> target == CONFIRMED || target == FAILED;
            case CONFIRMED -> target == RECONCILED;
            case RECONCILED, FAILED -> false;
        };
    }
}

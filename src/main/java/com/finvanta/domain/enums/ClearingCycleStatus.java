package com.finvanta.domain.enums;

/**
 * CBS Clearing Cycle Status per Finacle CLG_CYCLE / RBI NEFT Settlement Windows.
 *
 * Lifecycle:
 *   OPEN → transactions accumulate during the half-hourly window
 *   CLOSED → window ends, net obligation calculated, no more transactions
 *   SUBMITTED → net obligation submitted to RBI/NPCI for settlement
 *   SETTLED → RBI/NPCI confirms settlement
 */
public enum ClearingCycleStatus {

    /** Cycle is open — transactions can be added */
    OPEN,
    /** Cycle closed — net obligation calculated, no more transactions */
    CLOSED,
    /** Net obligation submitted to RBI/NPCI */
    SUBMITTED,
    /** RBI/NPCI confirmed settlement */
    SETTLED;

    /** Whether this cycle can accept new transactions */
    public boolean isOpen() { return this == OPEN; }

    /** Whether this cycle has been settled */
    public boolean isSettled() { return this == SETTLED; }

    /** Whether this cycle is in a terminal state */
    public boolean isTerminal() { return this == SETTLED; }

    /**
     * Validates whether a state transition is allowed.
     * OPEN → CLOSED → SUBMITTED → SETTLED (linear progression only)
     */
    public boolean canTransitionTo(ClearingCycleStatus target) {
        if (this == target) return false;
        return switch (this) {
            case OPEN -> target == CLOSED;
            case CLOSED -> target == SUBMITTED;
            case SUBMITTED -> target == SETTLED;
            case SETTLED -> false;
        };
    }
}

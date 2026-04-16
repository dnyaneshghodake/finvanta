package com.finvanta.domain.enums;

/**
 * CBS Charge Transaction Lifecycle Status per Finacle CHG_MASTER / Temenos FT.CHARGE.
 *
 * <p>Tracks the lifecycle state of a persisted {@code ChargeTransaction}. The
 * engine enforces these transitions:
 * <ul>
 *   <li>{@code LEVIED}   -> {@code WAIVED}   via {@code ChargeKernel.waiveCharge()}
 *       (policy-driven exception, bank absorbs the fee as income)</li>
 *   <li>{@code LEVIED}   -> {@code REVERSED} via {@code ChargeKernel.reverseCharge()}
 *       (operational rollback, e.g. source transaction reversed / incorrect levy)</li>
 *   <li>{@code WAIVED}   -> (terminal)</li>
 *   <li>{@code REVERSED} -> (terminal)</li>
 * </ul>
 *
 * <p>Per RBI Fair Practices Code 2023 §5.7 and IT Governance Direction 2023 §8:
 * every charge lifecycle event must be traceable to the originating journal
 * entry and be retained in the immutable audit trail. {@code WAIVED} is an
 * income-giveup event; {@code REVERSED} is a true double-entry rollback.
 */
public enum ChargeTransactionStatus {
    /** Charge levied and GL journal posted. Default state after {@code levyCharge}. */
    LEVIED,
    /** Charge waived (fee income relinquished). Contra journal posted. */
    WAIVED,
    /** Charge reversed (operational rollback). Symmetric contra journal posted. */
    REVERSED
}

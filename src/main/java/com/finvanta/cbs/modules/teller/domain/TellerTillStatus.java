package com.finvanta.cbs.modules.teller.domain;

/**
 * CBS Teller Till Lifecycle States per RBI Internal Controls Guidelines.
 *
 * <p>A till is a per-teller cash position that tracks physical cash on hand for
 * a specific business date. The till must be in {@link #OPEN} status before the
 * teller can post any cash transaction (deposit/withdrawal). End-of-day requires
 * a physical-count reconciliation that transitions OPEN -> CLOSED.
 *
 * <p>Lifecycle:
 * <pre>
 *   PENDING_OPEN   (created by teller, awaiting supervisor approval if required)
 *      | (supervisor approves OR auto-approve below threshold)
 *      v
 *   OPEN           (teller can post cash transactions)
 *      | (teller submits close request with physical denomination count)
 *      v
 *   PENDING_CLOSE  (awaiting supervisor sign-off; variance review)
 *      | (supervisor approves)
 *      v
 *   CLOSED         (no further mutations allowed; balance handed to vault/next shift)
 * </pre>
 *
 * <p>SUSPENDED is an out-of-band state used when a fraud investigation freezes a
 * till mid-shift. No cash transactions are accepted while SUSPENDED.
 */
public enum TellerTillStatus {

    /**
     * Open requested but awaiting supervisor approval. Used when opening balance
     * exceeds the configured "till open soft limit" or when policy mandates dual
     * control on every till open.
     */
    PENDING_OPEN,

    /** Active till; the teller can post cash deposits and withdrawals. */
    OPEN,

    /** Close requested; physical-count entered; awaiting supervisor sign-off. */
    PENDING_CLOSE,

    /** Terminal state for the day; no further cash movements permitted. */
    CLOSED,

    /**
     * Frozen mid-shift by Admin/Compliance (fraud, audit, system incident).
     * No mutations until the freeze is cleared and the till is reopened.
     */
    SUSPENDED
}

package com.finvanta.domain.enums;

/**
 * CBS Notification Delivery Status per Finacle ALERT_LOG / Temenos DE.MESSAGE.
 *
 * Per RBI Master Direction on Digital Payment Security Controls 2021 §8.2:
 * - Every notification attempt must be tracked (success and failure)
 * - Failed notifications must be retried with exponential backoff
 * - Permanently failed notifications must be escalated to operations
 *
 * Lifecycle:
 *   PENDING → notification created, queued for dispatch
 *   SENT → dispatched to SMS gateway / email server (no delivery confirmation yet)
 *   DELIVERED → delivery confirmed (SMS DLR callback / email read receipt)
 *   FAILED → dispatch failed (gateway error, invalid number/email, timeout)
 *   SUPPRESSED → notification suppressed (DND hours, customer opt-out, duplicate)
 *
 * Per RBI: FAILED notifications for financial transactions (debit/credit alerts)
 * must be retried at least 3 times within 24 hours before marking as permanently failed.
 */
public enum NotificationStatus {

    /** Queued for dispatch — not yet sent to gateway */
    PENDING,

    /** Dispatched to SMS gateway / email server — awaiting delivery confirmation */
    SENT,

    /** Delivery confirmed by gateway (SMS DLR / email delivery receipt) */
    DELIVERED,

    /** Dispatch failed — gateway error, invalid recipient, timeout */
    FAILED,

    /** Suppressed — DND hours, customer opt-out, duplicate detection */
    SUPPRESSED;

    /** Whether this is a terminal state (no further transitions) */
    public boolean isTerminal() {
        return this == DELIVERED || this == SUPPRESSED;
    }

    /** Whether this notification can be retried */
    public boolean isRetryable() {
        return this == FAILED;
    }

    /** Whether this notification was successfully delivered */
    public boolean isDelivered() {
        return this == DELIVERED;
    }

    /**
     * Validates whether a state transition is allowed.
     * PENDING → SENT → DELIVERED (success path)
     * PENDING → FAILED (immediate failure)
     * SENT → FAILED (gateway timeout)
     * PENDING → SUPPRESSED (DND/opt-out)
     * FAILED → PENDING (retry)
     */
    public boolean canTransitionTo(NotificationStatus target) {
        if (this == target) return false;
        return switch (this) {
            case PENDING -> target == SENT || target == FAILED
                    || target == SUPPRESSED;
            case SENT -> target == DELIVERED || target == FAILED;
            case FAILED -> target == PENDING; // retry
            case DELIVERED, SUPPRESSED -> false; // terminal
        };
    }
}

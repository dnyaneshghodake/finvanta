package com.finvanta.domain.enums;

/**
 * CBS Document Verification Status Enum per Finacle DOC_MASTER lifecycle.
 *
 * Document Lifecycle:
 *   UPLOADED → VERIFIED (by CHECKER) → EXPIRED (past validity date)
 *   UPLOADED → REJECTED (invalid/unclear document)
 *
 * Per Finacle DOC_MASTER:
 * - Documents are immutable once VERIFIED — new version uploaded for updates
 * - REJECTED documents require a reason (mandatory per RBI audit norms)
 * - EXPIRED is set by EOD batch when document's expiryDate has passed
 *
 * Compile-time safety: prevents invalid status values at code level.
 * DB storage: stored as VARCHAR via @Enumerated(EnumType.STRING).
 */
public enum DocumentVerificationStatus {

    /** Document uploaded by MAKER, pending CHECKER verification */
    UPLOADED,

    /** Document verified by CHECKER — immutable after this state */
    VERIFIED,

    /** Document rejected by CHECKER — rejection reason mandatory */
    REJECTED,

    /** Document expired (past validity date) — set by EOD batch */
    EXPIRED;

    /** Returns true if document is in a terminal state (VERIFIED, REJECTED, EXPIRED) */
    public boolean isTerminal() {
        return this == VERIFIED || this == REJECTED || this == EXPIRED;
    }

    /** Returns true if document can be verified/rejected (only UPLOADED) */
    public boolean canProcess() {
        return this == UPLOADED;
    }

    /**
     * Safe valueOf that returns null instead of throwing IllegalArgumentException.
     */
    public static DocumentVerificationStatus fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

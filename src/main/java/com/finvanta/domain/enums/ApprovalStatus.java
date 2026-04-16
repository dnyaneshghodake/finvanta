package com.finvanta.domain.enums;

/**
 * Maker-Checker workflow approval status per CBS/Finacle authorization standards.
 *
 * Per RBI guidelines on internal controls (circular on Operational Risk):
 * - All financial transactions require dual authorization (Maker-Checker)
 * - Maker and Checker must be different users
 * - RETURNED allows Checker to send back to Maker for correction
 */
public enum ApprovalStatus {
    PENDING_APPROVAL, // Submitted by Maker, awaiting Checker action
    APPROVED, // Approved by Checker (one-time use — must be consumed before action executes)
    REJECTED, // Rejected by Checker (with reason)
    RETURNED, // Returned to Maker for correction
    CANCELLED, // Cancelled by Maker before Checker action
    CONSUMED // Approval consumed after action executed — prevents replay (terminal state)
}

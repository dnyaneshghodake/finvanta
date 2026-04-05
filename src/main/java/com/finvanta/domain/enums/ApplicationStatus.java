package com.finvanta.domain.enums;

/**
 * Loan application lifecycle status per CBS Maker-Checker workflow.
 *
 * Standard origination pipeline (Finacle/Temenos pattern):
 *   DRAFT → SUBMITTED → UNDER_VERIFICATION → VERIFIED → PENDING_APPROVAL
 *   → APPROVED → DISBURSED
 *
 * Per RBI Fair Practices Code, each stage requires a different officer
 * (Maker-Checker-Approver segregation). Rejection can occur at any stage.
 */
public enum ApplicationStatus {
    DRAFT,                  // Created by Maker, not yet submitted
    SUBMITTED,              // Submitted for verification (Maker → Checker)
    UNDER_VERIFICATION,     // Being verified by Checker
    VERIFIED,               // Verified by Checker, pending approval
    PENDING_APPROVAL,       // Awaiting Approver decision
    APPROVED,               // Approved by Approver, ready for account creation
    REJECTED,               // Rejected at any stage (with reason)
    DISBURSED,              // Loan disbursed, application lifecycle complete
    CANCELLED               // Cancelled by applicant or bank
}

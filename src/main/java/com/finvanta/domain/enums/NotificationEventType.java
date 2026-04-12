package com.finvanta.domain.enums;

/**
 * CBS Notification Event Types per Finacle ALERT_MASTER / RBI Customer Protection 2024.
 *
 * Per RBI: every debit/credit on a customer account MUST trigger an alert.
 * Per Finacle ALERT_CONFIG: event types map to notification templates.
 */
public enum NotificationEventType {
    // === CASA Events (mandatory per RBI) ===
    CASA_CREDIT,
    CASA_DEBIT,
    CASA_TRANSFER_SENT,
    CASA_TRANSFER_RECEIVED,
    CASA_INTEREST_CREDIT,

    // === FD Events ===
    FD_BOOKED,
    FD_MATURITY_CLOSED,
    FD_PREMATURE_CLOSED,
    FD_INTEREST_PAYOUT,

    // === Loan Events ===
    LOAN_DISBURSED,
    LOAN_EMI_DEBIT,
    LOAN_REPAYMENT,
    LOAN_OVERDUE,

    // === Clearing Events ===
    CLEARING_OUTWARD_INITIATED,
    CLEARING_INWARD_CREDITED,

    // === Security Events ===
    LOGIN_SUCCESS,
    LOGIN_FAILED,
    PASSWORD_CHANGED,
    MFA_ENABLED
}

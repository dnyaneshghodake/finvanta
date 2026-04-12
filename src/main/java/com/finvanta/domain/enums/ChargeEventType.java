package com.finvanta.domain.enums;

/**
 * CBS Charge Event Types per Finacle CHG_MASTER / Temenos FT.COMMISSION.TYPE.
 *
 * Each event type maps to a charge definition that specifies the fee amount/percentage,
 * GST applicability, and GL codes. The ChargeEngine resolves the applicable charge
 * definition when a chargeable event occurs in any CBS module.
 *
 * Per RBI Fair Practices Code: all charges must be transparently disclosed to customers
 * and applied only as per the approved schedule of charges filed with RBI.
 */
public enum ChargeEventType {

    // === Clearing / Remittance Charges ===
    /** NEFT outward transfer processing fee */
    NEFT_OUTWARD,
    /** RTGS outward transfer processing fee */
    RTGS_OUTWARD,
    /** IMPS outward transfer processing fee */
    IMPS_OUTWARD,
    /** UPI outward transfer — typically zero per NPCI directive */
    UPI_OUTWARD,

    // === CASA Charges ===
    /** Cash withdrawal at non-home branch (inter-branch) */
    CASH_WITHDRAWAL_OTHER_BRANCH,
    /** Cheque book issuance fee */
    CHEQUE_BOOK_ISSUANCE,
    /** Demand draft issuance fee */
    DD_ISSUANCE,
    /** Account statement request (physical copy) */
    STATEMENT_REQUEST,
    /** Minimum balance non-maintenance penalty */
    MIN_BALANCE_PENALTY,
    /** SMS alert subscription fee */
    SMS_ALERT_FEE,
    /** Debit card annual fee */
    DEBIT_CARD_ANNUAL_FEE,

    // === Loan Charges ===
    /** Loan processing fee (charged at disbursement) */
    LOAN_PROCESSING_FEE,
    /** Loan prepayment/foreclosure penalty */
    LOAN_PREPAYMENT_PENALTY,
    /** Loan late payment penalty (distinct from penal interest) */
    LOAN_LATE_PAYMENT_FEE,
    /** Loan documentation/legal fee */
    LOAN_DOCUMENTATION_FEE,
    /** CIBIL/CRILC report pull fee */
    CREDIT_REPORT_FEE
}

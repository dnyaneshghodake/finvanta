package com.finvanta.accounting;

/**
 * Centralized GL Code constants for the Finvanta CBS platform.
 * Per Finacle/Temenos guidelines, GL codes must be configurable and
 * centralized — never scattered as String literals across service classes.
 *
 * In production, these would be loaded from a GL mapping configuration
 * table (product_type → GL codes). For this foundational implementation,
 * they are centralized constants matching the seed data in data.sql.
 *
 * Chart of Accounts Structure (Indian Banking Standard):
 * 1xxx = Assets
 * 2xxx = Liabilities
 * 3xxx = Equity
 * 4xxx = Income
 * 5xxx = Expenses
 */
public final class GLConstants {

    private GLConstants() {}

    // --- ASSET GL Codes ---
    /** Loan Portfolio — outstanding principal for term loans */
    public static final String LOAN_ASSET = "1001";
    /** Interest Receivable — accrued interest not yet collected */
    public static final String INTEREST_RECEIVABLE = "1002";
    /** Provision for NPA — contra-asset for loan loss provisioning */
    public static final String PROVISION_NPA = "1003";
    /** Bank Operations Account — cash/bank for disbursements and collections */
    public static final String BANK_OPERATIONS = "1100";
    /** Inter-Branch Receivable — Settlement receivable from other branches */
    public static final String INTER_BRANCH_RECEIVABLE = "1300";

    // --- LIABILITY GL Codes ---
    /** Customer Deposits (legacy — use SB_DEPOSITS / CA_DEPOSITS for CASA module) */
    public static final String CUSTOMER_DEPOSITS = "2001";
    /** Savings Bank Deposits — Liability GL for CASA Savings accounts */
    public static final String SB_DEPOSITS = "2010";
    /** Current Account Deposits — Liability GL for CASA Current accounts */
    public static final String CA_DEPOSITS = "2020";
    /** CGST Payable — GST liability on service charges (18%) */
    public static final String CGST_PAYABLE = "2200";
    /** SGST Payable — State GST liability on service charges (9%) */
    public static final String SGST_PAYABLE = "2201";
    /** Inter-Branch Payable — Settlement payable to other branches */
    public static final String INTER_BRANCH_PAYABLE = "2300";
    // --- CLEARING SUSPENSE GL Codes (per Finacle CLG_MASTER / RBI Payment Systems) ---
    // Per RBI: each payment rail MUST have separate inward + outward suspense GLs
    // for independent reconciliation. A single generic suspense GL makes it impossible
    // to reconcile NEFT vs RTGS vs IMPS independently — fails RBI inspection.

    /** @deprecated Use rail-specific suspense GLs (2600-2631) instead */
    @Deprecated(forRemoval = true)
    public static final String CLEARING_SUSPENSE = "2400";

    /** NEFT Outward Suspense — funds debited from customer, pending NEFT batch settlement */
    public static final String NEFT_OUTWARD_SUSPENSE = "2600";
    /** NEFT Inward Suspense — funds received from NEFT, pending credit to customer */
    public static final String NEFT_INWARD_SUSPENSE = "2601";
    /** RTGS Outward Suspense — funds debited, pending RTGS real-time settlement */
    public static final String RTGS_OUTWARD_SUSPENSE = "2610";
    /** RTGS Inward Suspense — funds received from RTGS, pending credit */
    public static final String RTGS_INWARD_SUSPENSE = "2611";
    /** IMPS Outward Suspense — funds debited, pending IMPS settlement */
    public static final String IMPS_OUTWARD_SUSPENSE = "2620";
    /** IMPS Inward Suspense — funds received from IMPS, pending credit */
    public static final String IMPS_INWARD_SUSPENSE = "2621";
    /** UPI Outward Suspense — funds debited, pending UPI settlement */
    public static final String UPI_OUTWARD_SUSPENSE = "2630";
    /** UPI Inward Suspense — funds received from UPI, pending credit */
    public static final String UPI_INWARD_SUSPENSE = "2631";

    // --- SETTLEMENT GL Codes ---
    /** RBI Settlement (Nostro) — bank's account with RBI for NEFT/RTGS settlement */
    public static final String RBI_SETTLEMENT = "1400";

    /** TDS Payable — Tax Deducted at Source on deposit interest per IT Act Section 194A */
    public static final String TDS_PAYABLE = "2500";

    // --- INCOME GL Codes ---
    /** Interest Income on Deposits — for bank's earning on CASA float */
    public static final String INTEREST_INCOME_DEPOSITS = "4010";
    /** Interest Income from Loans */
    public static final String INTEREST_INCOME = "4001";
    /** Fee Income */
    public static final String FEE_INCOME = "4002";
    /** Penal Interest Income */
    public static final String PENAL_INTEREST_INCOME = "4003";

    // --- SUSPENSE GL Codes (RBI IRAC — NPA Interest Management) ---
    /**
     * Interest Suspense — tracks interest accrued on NPA accounts.
     * Per RBI IRAC Master Circular, interest on NPA accounts must NOT be
     * recognized as income in P&L. It is parked in this suspense account
     * until actually received from the borrower.
     *
     * When loan becomes NPA:
     *   DR Interest Income (4001) — reverse previously recognized income
     *   CR Interest Suspense (2100) — park in suspense
     *
     * When NPA interest is actually collected:
     *   DR Interest Suspense (2100) — release from suspense
     *   CR Interest Income (4001) — recognize as income
     */
    public static final String INTEREST_SUSPENSE = "2100";

    /** Sundry Suspense — general suspense for unreconciled items */
    public static final String SUNDRY_SUSPENSE = "2101";

    // --- EXPENSE GL Codes ---
    /** Provision Expense — P&L charge for NPA provisioning */
    public static final String PROVISION_EXPENSE = "5001";
    /** Write-Off Expense */
    public static final String WRITE_OFF_EXPENSE = "5002";
    /** Interest Expense on Deposits — P&L charge for savings interest payouts */
    public static final String INTEREST_EXPENSE_DEPOSITS = "5010";

    // --- FIXED DEPOSIT GL Codes (per Finacle TD_MASTER / RBI Banking Regulation Act) ---

    /** Fixed Deposit Liability — customer FD principal held by bank */
    public static final String FD_DEPOSITS = "2030";
    /** FD Interest Payable — accrued FD interest not yet paid/credited */
    public static final String FD_INTEREST_PAYABLE = "2031";
    /** FD Interest Expense — P&L charge for FD interest payouts */
    public static final String FD_INTEREST_EXPENSE = "5011";
}

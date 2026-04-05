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

    // --- LIABILITY GL Codes ---
    /** Customer Deposits */
    public static final String CUSTOMER_DEPOSITS = "2001";

    // --- INCOME GL Codes ---
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
}

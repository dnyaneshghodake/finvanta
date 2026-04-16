package com.finvanta.domain.enums;

/**
 * CBS Product Category per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * Defines the fundamental accounting semantics for a product. The category
 * determines which GL account types are valid for each GL mapping slot:
 *
 *   LOAN categories (TERM_LOAN, DEMAND_LOAN, OVERDRAFT, CASH_CREDIT):
 *     - glLoanAsset → ASSET (principal outstanding)
 *     - glInterestReceivable → ASSET (accrued interest receivable)
 *     - glInterestIncome → INCOME (interest earned)
 *
 *   CASA categories (CASA_SAVINGS, CASA_CURRENT):
 *     - glLoanAsset → LIABILITY (customer deposit balance)
 *     - glInterestReceivable → EXPENSE (interest expense on deposits)
 *     - glInterestIncome → EXPENSE (interest expense P&L)
 *
 *   TERM_DEPOSIT (FD):
 *     - glLoanAsset → LIABILITY (FD deposit balance)
 *     - glInterestReceivable → LIABILITY (FD interest payable — accrued, owed to depositor)
 *     - glInterestIncome → EXPENSE (FD interest expense P&L)
 *
 * Per RBI Fair Practices Code 2023: product category is immutable after creation.
 * Changing a category would corrupt GL semantics for all existing accounts.
 *
 * Per Finacle PDDEF: category is set once at product creation and determines
 * the GL validation rules, interest calculation method defaults, and
 * regulatory reporting classification for the product's entire lifecycle.
 */
public enum ProductCategory {

    // --- Loan Categories ---

    /** Term Loan — fixed tenure, EMI-based repayment (Home Loan, Vehicle Loan, Personal Loan) */
    TERM_LOAN,

    /** Demand Loan — repayable on demand, no fixed schedule (Gold Loan, Crop Loan) */
    DEMAND_LOAN,

    /** Overdraft — revolving credit facility against collateral (OD against FD/Property) */
    OVERDRAFT,

    /** Cash Credit — working capital facility for businesses (CC against stock/debtors) */
    CASH_CREDIT,

    // --- Deposit Categories ---

    /** CASA Savings — interest-bearing savings account per RBI Banking Regulation Act */
    CASA_SAVINGS,

    /** CASA Current — zero-interest business current account */
    CASA_CURRENT,

    /** Term Deposit (FD) — fixed-term deposit with predetermined maturity */
    TERM_DEPOSIT;

    /** Returns true if this is a loan product category */
    public boolean isLoan() {
        return this == TERM_LOAN || this == DEMAND_LOAN || this == OVERDRAFT || this == CASH_CREDIT;
    }

    /** Returns true if this is a CASA deposit product category */
    public boolean isCasa() {
        return this == CASA_SAVINGS || this == CASA_CURRENT;
    }

    /** Returns true if this is a term deposit (FD) product category */
    public boolean isTermDeposit() {
        return this == TERM_DEPOSIT;
    }

    /** Returns true if this is any deposit product (CASA or FD) */
    public boolean isDeposit() {
        return isCasa() || isTermDeposit();
    }

    /**
     * Safe valueOf that returns null instead of throwing IllegalArgumentException.
     * Used for server-side validation of user input from forms.
     */
    public static ProductCategory fromString(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.finvanta.domain.enums;

/**
 * Loan transaction types per CBS/Finacle transaction code standards.
 *
 * Each transaction type maps to specific GL entries:
 *   DISBURSEMENT     → DR Loan Asset (1001) / CR Bank Operations (1100)
 *   REPAYMENT        → DR Bank Operations (1100) / CR Loan Asset + Interest Receivable
 *   INTEREST_ACCRUAL → DR Interest Receivable (1002) / CR Interest Income (4001)
 *   PENALTY_CHARGE   → DR Interest Receivable (1002) / CR Penal Interest Income (4003)
 *   WRITE_OFF        → DR Write-Off Expense (5002) / CR Loan Asset (1001)
 *
 * Per RBI guidelines, all transactions must be posted with value date and
 * posting date, and linked to a journal entry for GL reconciliation.
 */
public enum TransactionType {
    DISBURSEMENT, // Loan disbursement to borrower
    REPAYMENT_PRINCIPAL, // EMI/bullet repayment — principal component
    REPAYMENT_INTEREST, // EMI/bullet repayment — interest component
    INTEREST_ACCRUAL, // Daily interest accrual (EOD batch)
    PENALTY_CHARGE, // Penal interest on overdue EMIs (RBI Fair Lending)
    FEE_CHARGE, // Processing fee, documentation charge, etc.
    PREPAYMENT, // Part/full prepayment before maturity
    WRITE_OFF, // NPA write-off (RBI IRAC Loss category)
    REVERSAL, // Reversal of erroneous transaction
    ADJUSTMENT // Manual adjustment by authorized officer
}

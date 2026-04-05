package com.finvanta.domain.enums;

/**
 * General Ledger account type per Indian Banking Chart of Accounts.
 *
 * Standard CBS GL hierarchy (Finacle/Temenos):
 *   1xxx = ASSET      (Loan Portfolio, Interest Receivable, Bank Operations)
 *   2xxx = LIABILITY   (Customer Deposits, Borrowings)
 *   3xxx = EQUITY      (Share Capital, Reserves)
 *   4xxx = INCOME      (Interest Income, Fee Income, Penal Interest)
 *   5xxx = EXPENSE     (Provision Expense, Write-Off Expense)
 *
 * Normal balance: ASSET/EXPENSE = Debit, LIABILITY/INCOME/EQUITY = Credit
 */
public enum GLAccountType {
    ASSET,
    LIABILITY,
    INCOME,
    EXPENSE,
    EQUITY
}

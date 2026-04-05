package com.finvanta.domain.enums;

/**
 * Double-entry bookkeeping indicator per CBS/Finacle GL posting standards.
 * Every journal entry must have balanced DEBIT and CREDIT legs.
 * Per Indian Accounting Standards (Ind AS), assets/expenses increase on DEBIT,
 * liabilities/income/equity increase on CREDIT.
 */
public enum DebitCredit {
    DEBIT,
    CREDIT
}

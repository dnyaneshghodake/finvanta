package com.finvanta.service;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * CBS CASA (Current Account Savings Account) Service Interface
 * per Finacle CUSTACCT / Temenos ACCOUNT standards.
 *
 * All financial operations route through TransactionEngine for:
 * - Double-entry GL posting
 * - Business date validation
 * - Transaction batch control
 * - Maker-checker enforcement
 * - Voucher generation
 * - Audit trail
 *
 * Account lifecycle per RBI Banking Regulation Act:
 *   Open -> Activate -> Deposit/Withdraw/Transfer -> Dormant (2yr) -> Inoperative (10yr)
 *   Active -> Freeze (court/regulatory) -> Unfreeze
 *   Active -> Close (zero balance + customer request)
 */
public interface DepositAccountService {

    /**
     * Open a new savings or current account per Finacle ACCTOPN.
     * Validates: KYC verified, active customer, active branch, valid account type.
     * RBI: Current accounts cannot bear interest. Savings rate floor tracked.
     * GL: Initial deposit via TransactionEngine if amount > 0.
     */
    DepositAccount openAccount(Long customerId, Long branchId, String accountType,
                                String productCode, BigDecimal initialDeposit,
                                String nomineeName, String nomineeRelationship);

    /**
     * Cash deposit - DR Bank Ops (1100) / CR Customer Deposits (2010/2020).
     * Credits allowed on ACTIVE, DORMANT (reactivates), FROZEN (unless CREDIT_FREEZE).
     * All postings via TransactionEngine 10-step chain.
     */
    DepositTransaction deposit(String accountNumber, BigDecimal amount, LocalDate businessDate,
                                String narration, String idempotencyKey, String channel);

    /**
     * Cash withdrawal - DR Customer Deposits / CR Bank Ops.
     * Validates: account ACTIVE, not DEBIT_FREEZE/TOTAL_FREEZE, sufficient funds.
     * Available = ledgerBalance - holdAmount - unclearedAmount + odLimit.
     */
    DepositTransaction withdraw(String accountNumber, BigDecimal amount, LocalDate businessDate,
                                 String narration, String idempotencyKey, String channel);

    /**
     * Fund transfer between deposit accounts (internal).
     * Atomic: debit source + credit target in single TransactionEngine call.
     * DR source deposit GL / CR target deposit GL.
     */
    DepositTransaction transfer(String fromAccountNumber, String toAccountNumber,
                                 BigDecimal amount, LocalDate businessDate,
                                 String narration, String idempotencyKey);

    /**
     * Daily interest accrual for savings accounts (EOD Step).
     * Formula: closingBalance * rate / 36500 (Actual/365).
     * Accumulates in accruedInterest field; NOT credited to balance yet.
     */
    void accrueInterest(String accountNumber, LocalDate businessDate);

    /**
     * Quarterly interest credit (Mar 31, Jun 30, Sep 30, Dec 31).
     * GL: DR Interest Expense (5010) / CR Customer Deposits (2010).
     * TDS: If YTD interest > INR 40,000, deduct 10% per IT Act Section 194A.
     */
    DepositTransaction creditInterest(String accountNumber, LocalDate businessDate);

    /**
     * Activate a PENDING_ACTIVATION account after checker approval.
     * Per Finacle ACCTOPN: account opening requires dual authorization.
     * MAKER submits → CHECKER approves via workflow → this method activates.
     * Processes deferred initial deposit if specified in the workflow payload.
     */
    DepositAccount activateAccount(String accountNumber);

    /**
     * Freeze account per court order, regulatory directive, or AML.
     * freezeType: DEBIT_FREEZE, CREDIT_FREEZE, TOTAL_FREEZE.
     * Only ADMIN role can freeze (enforced via SecurityConfig).
     */
    DepositAccount freezeAccount(String accountNumber, String freezeType, String reason);

    /** Unfreeze account - restore to ACTIVE. Only ADMIN role. */
    DepositAccount unfreezeAccount(String accountNumber);

    /**
     * Close account. Requires zero ledger balance.
     * Terminal state - no further transactions allowed.
     */
    DepositAccount closeAccount(String accountNumber, String reason);

    /**
     * EOD batch: Mark accounts with no customer-initiated txn for 24+ months as DORMANT.
     * Per RBI Master Direction on KYC 2016, Section 38.
     * @return count of accounts marked dormant
     */
    int markDormantAccounts(LocalDate businessDate);

    /** Get account by number (read-only, no lock) */
    DepositAccount getAccount(String accountNumber);

    /** All non-closed accounts for tenant */
    List<DepositAccount> getActiveAccounts();

    /** Accounts by branch (branch isolation per Finacle SOL) */
    List<DepositAccount> getAccountsByBranch(Long branchId);

    /** Accounts by customer CIF */
    List<DepositAccount> getAccountsByCustomer(Long customerId);

    /** Full transaction history for account */
    List<DepositTransaction> getTransactionHistory(String accountNumber);

    /** Mini statement - last N transactions */
    List<DepositTransaction> getMiniStatement(String accountNumber, int count);

    /**
     * Account statement for date range per RBI passbook/statement requirements.
     * Per Finacle STMT_DETAIL / Temenos STMT.ENTRY: all transactions within the
     * date range, ordered by posting date ascending.
     */
    List<DepositTransaction> getStatement(String accountNumber, LocalDate fromDate, LocalDate toDate);

    /**
     * Reverse a deposit transaction per Finacle TRAN_REVERSAL.
     * Creates contra GL entries via TransactionEngine and marks original as reversed.
     * Per CBS audit rules: original transaction is never deleted, only marked reversed.
     * Only CHECKER/ADMIN can reverse (enforced via SecurityConfig).
     *
     * @param transactionRef Original transaction reference to reverse
     * @param reason Mandatory reversal reason for audit trail
     * @param businessDate CBS business date for the reversal posting
     * @return The reversal DepositTransaction record
     */
    DepositTransaction reverseTransaction(String transactionRef, String reason, LocalDate businessDate);
}

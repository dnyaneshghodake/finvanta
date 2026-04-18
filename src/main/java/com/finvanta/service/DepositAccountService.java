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
    DepositAccount openAccount(
            Long customerId,
            Long branchId,
            String accountType,
            String productCode,
            BigDecimal initialDeposit,
            String nomineeName,
            String nomineeRelationship);

    /**
     * Cash deposit - DR Bank Ops (1100) / CR Customer Deposits (2010/2020).
     * Credits allowed on ACTIVE, DORMANT (reactivates), FROZEN (unless CREDIT_FREEZE).
     * All postings via TransactionEngine 10-step chain.
     */
    DepositTransaction deposit(
            String accountNumber,
            BigDecimal amount,
            LocalDate businessDate,
            String narration,
            String idempotencyKey,
            String channel);

    /**
     * Cash withdrawal - DR Customer Deposits / CR Bank Ops.
     * Validates: account ACTIVE, not DEBIT_FREEZE/TOTAL_FREEZE, sufficient funds.
     * Available = ledgerBalance - holdAmount - unclearedAmount + odLimit.
     */
    DepositTransaction withdraw(
            String accountNumber,
            BigDecimal amount,
            LocalDate businessDate,
            String narration,
            String idempotencyKey,
            String channel);

    /**
     * Fund transfer between deposit accounts (internal).
     * Atomic: debit source + credit target in single TransactionEngine call.
     * DR source deposit GL / CR target deposit GL.
     */
    DepositTransaction transfer(
            String fromAccountNumber,
            String toAccountNumber,
            BigDecimal amount,
            LocalDate businessDate,
            String narration,
            String idempotencyKey);

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

    /**
     * CBS Account Maintenance per Finacle ACCTMOD / Temenos ACCOUNT.MODIFY.
     *
     * Modifies operational parameters on an existing ACTIVE account.
     * Per Tier-1 CBS: account maintenance is a separate workflow from account opening.
     * Only ACTIVE accounts can be modified — PENDING/FROZEN/DORMANT/CLOSED are rejected.
     *
     * Modifiable fields (per Finacle ACCTMOD):
     *   - nomineeName, nomineeRelationship (RBI Deposit Insurance)
     *   - jointHolderMode (EITHER_SURVIVOR, FORMER_SURVIVOR, JOINTLY)
     *   - chequeBookEnabled, debitCardEnabled (operational flags)
     *   - dailyWithdrawalLimit, dailyTransferLimit (per-account risk controls)
     *   - odLimit (for CURRENT_OD accounts only)
     *   - interestRate (per-account override, ADMIN only, savings accounts only)
     *   - minimumBalance (per-account override/waiver, ADMIN only)
     *
     * Immutable after creation (must open new account):
     *   - accountNumber, accountType, productCode, currencyCode, branch, customer
     *
     * Per RBI IT Governance §8.3: every modification is audited with before/after state.
     * Per Finacle ACCTMOD: CHECKER/ADMIN only (enforced in SecurityConfig).
     *
     * @param accountNumber Account to modify
     * @param nomineeName New nominee name (null = no change)
     * @param nomineeRelationship New nominee relationship (null = no change)
     * @param jointHolderMode New joint holder mode (null = no change)
     * @param chequeBookEnabled Enable/disable cheque book (null = no change)
     * @param debitCardEnabled Enable/disable debit card (null = no change)
     * @param dailyWithdrawalLimit New daily withdrawal limit (null = no change)
     * @param dailyTransferLimit New daily transfer limit (null = no change)
     * @param odLimit New OD limit for CURRENT_OD accounts (null = no change)
     * @param interestRate Per-account interest rate override (null = no change)
     * @param minimumBalance Per-account minimum balance override/waiver (null = no change)
     * @return Updated account
     */
    DepositAccount maintainAccount(
            String accountNumber,
            String nomineeName,
            String nomineeRelationship,
            String jointHolderMode,
            Boolean chequeBookEnabled,
            Boolean debitCardEnabled,
            BigDecimal dailyWithdrawalLimit,
            BigDecimal dailyTransferLimit,
            BigDecimal odLimit,
            BigDecimal interestRate,
            BigDecimal minimumBalance);

    /** Get account by number (read-only, no lock) */
    DepositAccount getAccount(String accountNumber);

    /**
     * All non-closed accounts for tenant (for account list page).
     * Returns PENDING_ACTIVATION, ACTIVE, DORMANT, FROZEN — excludes only CLOSED.
     * Per Finacle CUSTACCT: checkers must see PENDING accounts to activate them.
     */
    List<DepositAccount> getAllAccounts();

    /**
     * All ACTIVE accounts only (for transfer dropdown — only transactable accounts).
     * Excludes PENDING_ACTIVATION, DORMANT, FROZEN, CLOSED.
     */
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
     * CBS Tier-1: Apply subledger balance update after maker-checker GL posting.
     *
     * <p>When a CHECKER approves a PENDING_APPROVAL deposit/withdrawal, the
     * TransactionReExecutionService posts the GL via the engine. This method
     * applies the corresponding account balance update (subledger).
     *
     * <p>Must be called AFTER TransactionReExecutionService.reExecuteApprovedTransaction()
     * returns successfully. The TransactionResult carries the journal/voucher references
     * needed to link the subledger record.
     *
     * @param accountNumber The deposit account to update
     * @param amount Transaction amount
     * @param transactionType CASH_DEPOSIT, CASH_WITHDRAWAL, etc.
     * @param result The TransactionResult from the re-executed engine posting
     * @param businessDate The business date for the posting
     * @return The created DepositTransaction subledger record
     */
    DepositTransaction applyApprovedTransaction(
            String accountNumber,
            BigDecimal amount,
            String transactionType,
            com.finvanta.transaction.TransactionResult result,
            LocalDate businessDate);

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

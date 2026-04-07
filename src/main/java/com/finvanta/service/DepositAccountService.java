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

    /** Open a new savings or current account per Finacle ACCTOPN */
    DepositAccount openAccount(Long customerId, Long branchId, String accountType,
                                String productCode, BigDecimal initialDeposit,
                                String nomineeName, String nomineeRelationship);

    /** Activate a pending account (maker-checker verified) */
    DepositAccount activateAccount(String accountNumber);

    /** Cash deposit - DR Bank Ops (1100) / CR Customer Deposits (2010/2020) */
    DepositTransaction deposit(String accountNumber, BigDecimal amount,
                                String narration, String channel);

    /** Cash withdrawal - DR Customer Deposits / CR Bank Ops */
    DepositTransaction withdraw(String accountNumber, BigDecimal amount,
                                 String narration, String channel);

    /** Fund transfer between deposit accounts */
    DepositTransaction transfer(String fromAccountNumber, String toAccountNumber,
                                 BigDecimal amount, String narration);

    /** Daily interest accrual for savings accounts (EOD) */
    void accrueInterest(String accountNumber, LocalDate businessDate);

    /** Quarterly interest credit to account (EOD - Mar/Jun/Sep/Dec) */
    void creditInterest(String accountNumber, LocalDate businessDate);

    /** Freeze account (court order, regulatory, AML) */
    DepositAccount freezeAccount(String accountNumber, String freezeType, String reason);

    /** Unfreeze account */
    DepositAccount unfreezeAccount(String accountNumber);

    /** Close account (zero balance required) */
    DepositAccount closeAccount(String accountNumber, String reason);

    /** Mark dormant accounts (2yr no transaction) - EOD batch */
    int markDormantAccounts(LocalDate businessDate);

    /** Get account by number */
    DepositAccount getAccount(String accountNumber);

    /** Get all active accounts (admin view) */
    List<DepositAccount> getActiveAccounts();

    /** Get accounts by branch (branch isolation) */
    List<DepositAccount> getAccountsByBranch(Long branchId);

    /** Get accounts by customer */
    List<DepositAccount> getAccountsByCustomer(Long customerId);

    /** Get transaction history */
    List<DepositTransaction> getTransactionHistory(String accountNumber);

    /** Get mini statement (last 10 transactions) */
    List<DepositTransaction> getMiniStatement(String accountNumber);
}

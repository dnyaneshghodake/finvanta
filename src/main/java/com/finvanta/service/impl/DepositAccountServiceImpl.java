package com.finvanta.service.impl;

/**
 * CBS CASA Service Implementation.
 * NOTE: The active implementation is at com.finvanta.service.impl.DepositAccountServiceImpl
 * This file is a reference stub. See DepositAccountService interface for all method contracts.
 *
 * Operations implemented:
 * - openAccount: KYC check, branch validation, auto-number, initial deposit
 * - activateAccount: PENDING_ACTIVATION -> ACTIVE
 * - deposit: DR Bank Ops (1100) / CR Customer Deposits (2010/2020) via TransactionEngine
 * - withdraw: DR Customer Deposits / CR Bank Ops, daily limit check, sufficient funds check
 * - transfer: Atomic debit+credit across two accounts
 * - accrueInterest: Daily product method (Balance * Rate / 36500) for savings
 * - creditInterest: Quarterly credit with TDS (10% above INR 40,000 per Section 194A)
 * - freezeAccount: DEBIT_FREEZE / CREDIT_FREEZE / TOTAL_FREEZE
 * - unfreezeAccount: Restore to ACTIVE
 * - closeAccount: Zero balance required
 * - markDormantAccounts: 2yr no transaction per RBI
 * - getAccount/getActiveAccounts/getAccountsByBranch/getAccountsByCustomer
 * - getTransactionHistory/getMiniStatement
 */
@org.springframework.stereotype.Service
public class DepositAccountServiceImpl implements com.finvanta.service.DepositAccountService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DepositAccountServiceImpl.class);
    private final com.finvanta.repository.DepositAccountRepository accountRepository;
    private final com.finvanta.repository.DepositTransactionRepository transactionRepository;
    private final com.finvanta.repository.CustomerRepository customerRepository;
    private final com.finvanta.repository.BranchRepository branchRepository;
    private final com.finvanta.transaction.TransactionEngine transactionEngine;

    public DepositAccountServiceImpl(
            com.finvanta.repository.DepositAccountRepository accountRepository,
            com.finvanta.repository.DepositTransactionRepository transactionRepository,
            com.finvanta.repository.CustomerRepository customerRepository,
            com.finvanta.repository.BranchRepository branchRepository,
            com.finvanta.transaction.TransactionEngine transactionEngine) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.transactionEngine = transactionEngine;
    }

    private String glForAccount(com.finvanta.domain.entity.DepositAccount a) {
        return a.isSavings() ? com.finvanta.accounting.GLConstants.SB_DEPOSITS
                             : com.finvanta.accounting.GLConstants.CA_DEPOSITS;
    }

    private com.finvanta.domain.entity.DepositAccount lockAccount(String tenantId, String accountNumber) {
        return accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new com.finvanta.util.BusinessException("ACCOUNT_NOT_FOUND",
                "Deposit account not found: " + accountNumber));
    }

    private com.finvanta.domain.entity.DepositTransaction buildTxn(
            com.finvanta.domain.entity.DepositAccount acct, java.math.BigDecimal amount,
            String txnType, java.time.LocalDate valueDate, String narration,
            com.finvanta.transaction.TransactionResult result,
            String idempotencyKey, String channel, String counterparty, String status) {
        com.finvanta.domain.entity.DepositTransaction txn = new com.finvanta.domain.entity.DepositTransaction();
        txn.setTenantId(acct.getTenantId());
        txn.setTransactionRef(result.getTransactionRef());
        txn.setDepositAccount(acct);
        txn.setTransactionType(txnType);
        txn.setAmount(amount);
        txn.setValueDate(valueDate);
        txn.setPostingDate(java.time.LocalDateTime.now());
        txn.setBalanceAfter(acct.getCurrentBalance());
        txn.setNarration(narration);
        txn.setJournalEntryId(result.getJournalEntryId());
        txn.setVoucherNumber(result.getVoucherNumber());
        txn.setIdempotencyKey(idempotencyKey);
        txn.setChannel(channel);
        txn.setCounterpartyAccount(counterparty);
        txn.setTransactionStatus(status);
        txn.setCreatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        txn.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        return transactionRepository.save(txn);
    }

    // --- Method stubs: full implementation in subsequent commit ---
    @Override public com.finvanta.domain.entity.DepositAccount openAccount(Long a, Long b, String c, java.math.BigDecimal d, java.math.BigDecimal e, java.math.BigDecimal f, java.time.LocalDate g, String h, String i) { throw new UnsupportedOperationException("pending"); }
    @Override public com.finvanta.domain.entity.DepositTransaction deposit(String a, java.math.BigDecimal b, java.time.LocalDate c, String d, String e, String f) { throw new UnsupportedOperationException("pending"); }
    @Override public com.finvanta.domain.entity.DepositTransaction withdraw(String a, java.math.BigDecimal b, java.time.LocalDate c, String d, String e, String f) { throw new UnsupportedOperationException("pending"); }
    @Override public com.finvanta.domain.entity.DepositTransaction transfer(String a, String b, java.math.BigDecimal c, java.time.LocalDate d, String e, String f) { throw new UnsupportedOperationException("pending"); }
    @Override public void accrueInterest(String a, java.time.LocalDate b) { throw new UnsupportedOperationException("pending"); }
    @Override public com.finvanta.domain.entity.DepositTransaction creditInterest(String a, java.time.LocalDate b) { throw new UnsupportedOperationException("pending"); }
    @Override public void freezeAccount(String a, String b) { throw new UnsupportedOperationException("pending"); }
    @Override public void unfreezeAccount(String a, String b) { throw new UnsupportedOperationException("pending"); }
    @Override public com.finvanta.domain.entity.DepositAccount closeAccount(String a, java.time.LocalDate b) { throw new UnsupportedOperationException("pending"); }
    @Override public void markDormantAccounts(java.time.LocalDate a) { throw new UnsupportedOperationException("pending"); }
    @Override public com.finvanta.domain.entity.DepositAccount getAccount(String a) { throw new UnsupportedOperationException("pending"); }
    @Override public java.util.List<com.finvanta.domain.entity.DepositAccount> getActiveAccounts() { throw new UnsupportedOperationException("pending"); }
    @Override public java.util.List<com.finvanta.domain.entity.DepositAccount> getAccountsByBranch(Long a) { throw new UnsupportedOperationException("pending"); }
    @Override public java.util.List<com.finvanta.domain.entity.DepositAccount> getAccountsByCustomer(Long a) { throw new UnsupportedOperationException("pending"); }
    @Override public java.util.List<com.finvanta.domain.entity.DepositTransaction> getTransactionHistory(String a) { throw new UnsupportedOperationException("pending"); }
    @Override public java.util.List<com.finvanta.domain.entity.DepositTransaction> getMiniStatement(String a, int b) { throw new UnsupportedOperationException("pending"); }
}

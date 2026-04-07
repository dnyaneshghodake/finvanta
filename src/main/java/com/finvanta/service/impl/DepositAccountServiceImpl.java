package com.finvanta.service.impl;

/**
 * CBS CASA Service Implementation per Finacle CUSTACCT / Temenos ACCOUNT.
 *
 * All financial postings route through TransactionEngine (10-step validated pipeline).
 * This service owns the DEPOSIT subledger: balance updates, interest accrual,
 * dormancy classification, freeze management, and TDS compliance.
 *
 * GL Mapping:
 *   Savings Deposits  -> GL 2010 (SB Deposits - Liability)
 *   Current Deposits  -> GL 2020 (CA Deposits - Liability)
 *   Interest Expense  -> GL 5010 (Interest Expense on Deposits - Expense)
 *   TDS Payable       -> GL 2500 (TDS Payable - Liability per IT Act Section 194A)
 *
 * Concurrency: PESSIMISTIC_WRITE lock on every balance mutation.
 * Transfer deadlock prevention: accounts locked in alphabetical order.
 * Interest accrual: idempotent per business date (double-accrual guard).
 */
@org.springframework.stereotype.Service
public class DepositAccountServiceImpl implements com.finvanta.service.DepositAccountService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DepositAccountServiceImpl.class);

    /** RBI Master Direction on KYC 2016, Section 38: 24 months no customer-initiated txn = DORMANT */
    private static final long DORMANCY_MONTHS = 24;
    /** IT Act Section 194A: TDS threshold for annual interest income (INR 40,000 for non-seniors) */
    private static final java.math.BigDecimal TDS_THRESHOLD = new java.math.BigDecimal("40000.00");
    /** TDS rate: 10% per IT Act Section 194A */
    private static final java.math.BigDecimal TDS_RATE = new java.math.BigDecimal("0.10");

    private final com.finvanta.repository.DepositAccountRepository accountRepository;
    private final com.finvanta.repository.DepositTransactionRepository transactionRepository;
    private final com.finvanta.repository.CustomerRepository customerRepository;
    private final com.finvanta.repository.BranchRepository branchRepository;
    private final com.finvanta.transaction.TransactionEngine transactionEngine;
    private final com.finvanta.service.BusinessDateService businessDateService;
    private final com.finvanta.audit.AuditService auditService;

    public DepositAccountServiceImpl(
            com.finvanta.repository.DepositAccountRepository accountRepository,
            com.finvanta.repository.DepositTransactionRepository transactionRepository,
            com.finvanta.repository.CustomerRepository customerRepository,
            com.finvanta.repository.BranchRepository branchRepository,
            com.finvanta.transaction.TransactionEngine transactionEngine,
            com.finvanta.service.BusinessDateService businessDateService,
            com.finvanta.audit.AuditService auditService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.transactionEngine = transactionEngine;
        this.businessDateService = businessDateService;
        this.auditService = auditService;
    }

    /** Recompute available balance from ledger balance minus holds and uncleared. */
    private void recomputeAvailable(com.finvanta.domain.entity.DepositAccount acct) {
        acct.setAvailableBalance(acct.getLedgerBalance()
            .subtract(acct.getHoldAmount())
            .subtract(acct.getUnclearedAmount()));
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
            String idempotencyKey, String channel, String counterparty) {
        com.finvanta.domain.entity.DepositTransaction txn = new com.finvanta.domain.entity.DepositTransaction();
        txn.setTenantId(acct.getTenantId());
        txn.setTransactionRef(result.getTransactionRef());
        txn.setDepositAccount(acct);
        txn.setTransactionType(txnType);
        txn.setAmount(amount);
        txn.setValueDate(valueDate);
        txn.setPostingDate(java.time.LocalDateTime.now());
        txn.setDebitCredit(txnType.contains("WITHDRAWAL") || txnType.contains("DEBIT") || txnType.equals("TDS_DEBIT") || txnType.equals("CHARGE_DEBIT") ? "DEBIT" : "CREDIT");
        txn.setBalanceAfter(acct.getLedgerBalance());
        txn.setNarration(narration);
        txn.setJournalEntryId(result.getJournalEntryId());
        txn.setVoucherNumber(result.getVoucherNumber());
        txn.setIdempotencyKey(idempotencyKey);
        txn.setChannel(channel);
        txn.setCounterpartyAccount(counterparty);
        txn.setCreatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        txn.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        return transactionRepository.save(txn);
    }

    // === Account Opening ===
    @Override
    @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositAccount openAccount(Long customerId, Long branchId,
            String accountType, String productCode, java.math.BigDecimal initialDeposit,
            String nomineeName, String nomineeRelationship) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        String user = com.finvanta.util.SecurityUtil.getCurrentUsername();
        com.finvanta.domain.entity.Customer cust = customerRepository.findById(customerId)
            .filter(c -> tid.equals(c.getTenantId()))
            .orElseThrow(() -> new com.finvanta.util.BusinessException("CUSTOMER_NOT_FOUND", "Not found: " + customerId));
        if (!cust.isActive()) throw new com.finvanta.util.BusinessException("CUSTOMER_INACTIVE", "Inactive customer");
        if (!cust.isKycVerified()) throw new com.finvanta.util.BusinessException("KYC_NOT_VERIFIED", "KYC required per RBI");
        com.finvanta.domain.entity.Branch branch = branchRepository.findById(branchId)
            .filter(b -> tid.equals(b.getTenantId()) && b.isActive())
            .orElseThrow(() -> new com.finvanta.util.BusinessException("BRANCH_NOT_FOUND", "Branch: " + branchId));
        if (accountType == null || (!accountType.startsWith("SAVINGS") && !accountType.startsWith("CURRENT")))
            throw new com.finvanta.util.BusinessException("INVALID_ACCOUNT_TYPE", "Must be SAVINGS* or CURRENT*");

        String accNo = com.finvanta.util.ReferenceGenerator.generateDepositAccountNumber(branch.getBranchCode());
        com.finvanta.domain.entity.DepositAccount a = new com.finvanta.domain.entity.DepositAccount();
        a.setTenantId(tid); a.setAccountNumber(accNo); a.setCustomer(cust); a.setBranch(branch);
        a.setAccountType(accountType); a.setProductCode(productCode != null ? productCode : accountType);
        a.setAccountStatus("ACTIVE"); a.setCurrencyCode("INR");
        a.setLedgerBalance(java.math.BigDecimal.ZERO); a.setAvailableBalance(java.math.BigDecimal.ZERO);
        a.setHoldAmount(java.math.BigDecimal.ZERO); a.setUnclearedAmount(java.math.BigDecimal.ZERO);
        a.setOdLimit(java.math.BigDecimal.ZERO); a.setMinimumBalance(java.math.BigDecimal.ZERO);
        a.setInterestRate(accountType.startsWith("SAVINGS") ? new java.math.BigDecimal("4.0000") : java.math.BigDecimal.ZERO);
        a.setAccruedInterest(java.math.BigDecimal.ZERO);
        a.setYtdInterestCredited(java.math.BigDecimal.ZERO); a.setYtdTdsDeducted(java.math.BigDecimal.ZERO);
        java.time.LocalDate bizDate = businessDateService.getCurrentBusinessDate();
        a.setOpenedDate(bizDate); a.setLastTransactionDate(bizDate);
        a.setNomineeName(nomineeName); a.setNomineeRelationship(nomineeRelationship);
        a.setCreatedBy(user); a.setUpdatedBy(user);
        com.finvanta.domain.entity.DepositAccount saved = accountRepository.save(a);
        auditService.logEvent("DepositAccount", saved.getId(), "ACCOUNT_OPENED",
            null, accNo, "DEPOSIT",
            "CASA account opened: " + accNo + " type=" + accountType
                + " customer=" + cust.getCustomerNumber() + " branch=" + branch.getBranchCode());
        log.info("CASA opened: num={}, type={}, cust={}", accNo, accountType, cust.getCustomerNumber());
        if (initialDeposit != null && initialDeposit.signum() > 0) {
            deposit(accNo, initialDeposit, bizDate, "Initial deposit at account opening", null, "BRANCH");
            saved = accountRepository.findByTenantIdAndAccountNumber(tid, accNo).orElse(saved);
        }
        return saved;
    }

    // === Deposit ===
    @Override
    @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositTransaction deposit(String accountNumber, java.math.BigDecimal amount,
            java.time.LocalDate businessDate, String narration, String idempotencyKey, String channel) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        if (idempotencyKey != null) {
            com.finvanta.domain.entity.DepositTransaction dup = transactionRepository.findByTenantIdAndIdempotencyKey(tid, idempotencyKey).orElse(null);
            if (dup != null) return dup;
        }
        com.finvanta.domain.entity.DepositAccount acct = lockAccount(tid, accountNumber);
        if (!acct.isCreditAllowed()) throw new com.finvanta.util.BusinessException("ACCOUNT_NOT_CREDITABLE", "Status " + acct.getAccountStatus());
        String gl = glForAccount(acct);
        com.finvanta.transaction.TransactionResult r = transactionEngine.execute(com.finvanta.transaction.TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("CASH_DEPOSIT").accountReference(accountNumber)
            .amount(amount).valueDate(businessDate).branchCode(acct.getBranch().getBranchCode())
            .narration(narration != null ? narration : "Cash deposit").idempotencyKey(idempotencyKey)
            .journalLines(java.util.List.of(
                new com.finvanta.accounting.AccountingService.JournalLineRequest(com.finvanta.accounting.GLConstants.BANK_OPERATIONS, com.finvanta.domain.enums.DebitCredit.DEBIT, amount, "Cash deposit"),
                new com.finvanta.accounting.AccountingService.JournalLineRequest(gl, com.finvanta.domain.enums.DebitCredit.CREDIT, amount, "Credit " + accountNumber)
            )).build());
        if (r.isPendingApproval()) return buildTxn(acct, amount, "CASH_DEPOSIT", businessDate, narration, r, idempotencyKey, channel, null);
        acct.setLedgerBalance(acct.getLedgerBalance().add(amount));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);
        if (acct.isDormant()) { acct.setAccountStatus("ACTIVE"); acct.setDormantDate(null); }
        acct.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);
        return buildTxn(acct, amount, "CASH_DEPOSIT", businessDate, narration, r, idempotencyKey, channel, null);
    }

    // === Withdrawal ===
    @Override @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositTransaction withdraw(String acn, java.math.BigDecimal amount,
            java.time.LocalDate bd, String narration, String idk, String channel) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        if (idk != null) { var dup = transactionRepository.findByTenantIdAndIdempotencyKey(tid, idk).orElse(null); if (dup != null) return dup; }
        var acct = lockAccount(tid, acn);
        if (!acct.isDebitAllowed()) throw new com.finvanta.util.BusinessException("ACCOUNT_NOT_DEBITABLE", "Status " + acct.getAccountStatus());
        if (!acct.hasSufficientFunds(amount)) throw new com.finvanta.util.BusinessException("INSUFFICIENT_BALANCE", "Withdrawal " + amount + " > available " + acct.getEffectiveAvailable());
        // CBS: Daily withdrawal limit enforcement per Finacle ACCTLIMIT / Temenos LIMIT.CHECK
        if (acct.getDailyWithdrawalLimit() != null && acct.getDailyWithdrawalLimit().signum() > 0) {
            java.math.BigDecimal dailyDebits = transactionRepository.sumDailyDebits(tid, acct.getId(), bd);
            if (dailyDebits.add(amount).compareTo(acct.getDailyWithdrawalLimit()) > 0)
                throw new com.finvanta.util.BusinessException("DAILY_LIMIT_EXCEEDED",
                    "Daily withdrawal limit INR " + acct.getDailyWithdrawalLimit()
                        + " exceeded. Today's debits: " + dailyDebits + ", requested: " + amount);
        }
        String gl = glForAccount(acct);
        var r = transactionEngine.execute(com.finvanta.transaction.TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("CASH_WITHDRAWAL").accountReference(acn)
            .amount(amount).valueDate(bd).branchCode(acct.getBranch().getBranchCode())
            .narration(narration != null ? narration : "Cash withdrawal").idempotencyKey(idk)
            .journalLines(java.util.List.of(
                new com.finvanta.accounting.AccountingService.JournalLineRequest(gl, com.finvanta.domain.enums.DebitCredit.DEBIT, amount, "Debit " + acn),
                new com.finvanta.accounting.AccountingService.JournalLineRequest(com.finvanta.accounting.GLConstants.BANK_OPERATIONS, com.finvanta.domain.enums.DebitCredit.CREDIT, amount, "Cash withdrawal")
            )).build());
        if (r.isPendingApproval()) return buildTxn(acct, amount, "CASH_WITHDRAWAL", bd, narration, r, idk, channel, null);
        acct.setLedgerBalance(acct.getLedgerBalance().subtract(amount));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(bd); acct.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);
        return buildTxn(acct, amount, "CASH_WITHDRAWAL", bd, narration, r, idk, channel, null);
    }

    // === Transfer ===
    @Override @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositTransaction transfer(String from, String to,
            java.math.BigDecimal amount, java.time.LocalDate bd, String narration, String idk) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        if (from.equals(to)) throw new com.finvanta.util.BusinessException("SAME_ACCOUNT", "Cannot transfer to same account");
        // CBS: Lock accounts in deterministic order (alphabetical by account number)
        // to prevent ABBA deadlock when two concurrent transfers happen in opposite directions.
        // Per Finacle TRAN_POSTING / Temenos OFS: all multi-account locks must be ordered.
        boolean fromFirst = from.compareTo(to) < 0;
        var firstAcct = lockAccount(tid, fromFirst ? from : to);
        var secondAcct = lockAccount(tid, fromFirst ? to : from);
        var src = fromFirst ? firstAcct : secondAcct;
        var tgt = fromFirst ? secondAcct : firstAcct;
        if (!src.isDebitAllowed()) throw new com.finvanta.util.BusinessException("SOURCE_NOT_DEBITABLE", "Source " + src.getAccountStatus());
        if (!tgt.isCreditAllowed()) throw new com.finvanta.util.BusinessException("TARGET_NOT_CREDITABLE", "Target " + tgt.getAccountStatus());
        if (!src.hasSufficientFunds(amount)) throw new com.finvanta.util.BusinessException("INSUFFICIENT_BALANCE", "Transfer " + amount + " > available " + src.getEffectiveAvailable());
        var r = transactionEngine.execute(com.finvanta.transaction.TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("TRANSFER_DEBIT").accountReference(from)
            .amount(amount).valueDate(bd).branchCode(src.getBranch().getBranchCode())
            .narration(narration != null ? narration : "Transfer to " + to).idempotencyKey(idk)
            .journalLines(java.util.List.of(
                new com.finvanta.accounting.AccountingService.JournalLineRequest(glForAccount(src), com.finvanta.domain.enums.DebitCredit.DEBIT, amount, "Transfer debit"),
                new com.finvanta.accounting.AccountingService.JournalLineRequest(glForAccount(tgt), com.finvanta.domain.enums.DebitCredit.CREDIT, amount, "Transfer credit")
            )).build());
        if (r.isPendingApproval()) return buildTxn(src, amount, "TRANSFER_DEBIT", bd, narration, r, idk, "INTERNAL", to);
        src.setLedgerBalance(src.getLedgerBalance().subtract(amount));
        recomputeAvailable(src);
        src.setLastTransactionDate(bd); src.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername()); accountRepository.save(src);
        tgt.setLedgerBalance(tgt.getLedgerBalance().add(amount));
        recomputeAvailable(tgt);
        tgt.setLastTransactionDate(bd); if (tgt.isDormant()) { tgt.setAccountStatus("ACTIVE"); tgt.setDormantDate(null); }
        tgt.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername()); accountRepository.save(tgt);
        buildTxn(tgt, amount, "TRANSFER_CREDIT", bd, "Transfer from " + from, r, null, "INTERNAL", from);
        return buildTxn(src, amount, "TRANSFER_DEBIT", bd, narration, r, idk, "INTERNAL", to);
    }

    // === Interest Accrual (EOD daily) ===
    @Override @org.springframework.transaction.annotation.Transactional
    public void accrueInterest(String acn, java.time.LocalDate bd) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        if (!acct.isSavings() || acct.getInterestRate().signum() <= 0) return;
        // CBS: Guard against double-accrual if EOD reruns or retries for the same date.
        // Per Finacle EOD / Temenos COB: accrual is idempotent per business date.
        if (bd.equals(acct.getLastInterestAccrualDate())) return;
        var daily = acct.getLedgerBalance().multiply(acct.getInterestRate())
            .divide(new java.math.BigDecimal("36500"), 2, java.math.RoundingMode.HALF_UP);
        if (daily.signum() <= 0) return;
        acct.setAccruedInterest(acct.getAccruedInterest().add(daily));
        acct.setLastInterestAccrualDate(bd);
        accountRepository.save(acct);
    }

    // === Quarterly Interest Credit with TDS ===
    @Override @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositTransaction creditInterest(String acn, java.time.LocalDate bd) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        var interest = acct.getAccruedInterest();
        if (interest.signum() <= 0) return null;
        // CBS: Idempotency guard — prevent double credit on EOD retry.
        if (bd.equals(acct.getLastInterestCreditDate())) {
            log.warn("Interest already credited for {} on {}, skipping", acn, bd);
            return null;
        }
        String gl = glForAccount(acct);
        var r = transactionEngine.execute(com.finvanta.transaction.TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("INTEREST_CREDIT").accountReference(acn)
            .amount(interest).valueDate(bd).branchCode(acct.getBranch().getBranchCode())
            .narration("Quarterly interest credit").systemGenerated(true)
            .journalLines(java.util.List.of(
                new com.finvanta.accounting.AccountingService.JournalLineRequest(com.finvanta.accounting.GLConstants.INTEREST_EXPENSE_DEPOSITS, com.finvanta.domain.enums.DebitCredit.DEBIT, interest, "Interest expense"),
                new com.finvanta.accounting.AccountingService.JournalLineRequest(gl, com.finvanta.domain.enums.DebitCredit.CREDIT, interest, "Interest credit " + acn)
            )).build());
        acct.setLedgerBalance(acct.getLedgerBalance().add(interest));
        acct.setYtdInterestCredited(acct.getYtdInterestCredited().add(interest));

        // IT Act Section 194A: TDS deduction at source if YTD interest exceeds threshold.
        // TDS is deducted on the TOTAL quarterly credit amount (not just the excess).
        // Per RBI/IT Act: TDS = 10% of interest if cumulative YTD interest > INR 40,000.
        java.math.BigDecimal tdsAmount = java.math.BigDecimal.ZERO;
        if (acct.getYtdInterestCredited().compareTo(TDS_THRESHOLD) > 0) {
            tdsAmount = interest.multiply(TDS_RATE).setScale(2, java.math.RoundingMode.HALF_UP);
            if (tdsAmount.signum() > 0) {
                acct.setLedgerBalance(acct.getLedgerBalance().subtract(tdsAmount));
                acct.setYtdTdsDeducted(acct.getYtdTdsDeducted().add(tdsAmount));
                // GL: DR Customer Deposits / CR TDS Payable (2500)
                transactionEngine.execute(com.finvanta.transaction.TransactionRequest.builder()
                    .sourceModule("DEPOSIT").transactionType("TDS_DEBIT").accountReference(acn)
                    .amount(tdsAmount).valueDate(bd).branchCode(acct.getBranch().getBranchCode())
                    .narration("TDS u/s 194A on interest INR " + interest).systemGenerated(true)
                    .journalLines(java.util.List.of(
                        new com.finvanta.accounting.AccountingService.JournalLineRequest(gl, com.finvanta.domain.enums.DebitCredit.DEBIT, tdsAmount, "TDS deduction"),
                        new com.finvanta.accounting.AccountingService.JournalLineRequest(com.finvanta.accounting.GLConstants.TDS_PAYABLE, com.finvanta.domain.enums.DebitCredit.CREDIT, tdsAmount, "TDS payable u/s 194A")
                    )).build());
                log.info("TDS deducted: account={}, interest={}, tds={}", acn, interest, tdsAmount);
            }
        }

        recomputeAvailable(acct);
        acct.setAccruedInterest(java.math.BigDecimal.ZERO);
        acct.setLastInterestCreditDate(bd);
        accountRepository.save(acct);
        return buildTxn(acct, interest, "INTEREST_CREDIT", bd,
            "Quarterly interest credit" + (tdsAmount.signum() > 0 ? " (TDS INR " + tdsAmount + " deducted)" : ""),
            r, null, "SYSTEM", null);
    }
    // === Freeze ===
    @Override @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositAccount freezeAccount(String acn, String freezeType, String reason) {
        var acct = lockAccount(com.finvanta.util.TenantContext.getCurrentTenant(), acn);
        if (acct.isClosed()) throw new com.finvanta.util.BusinessException("ACCOUNT_CLOSED", "Cannot freeze closed account");
        String prevStatus = acct.getAccountStatus();
        acct.setAccountStatus("FROZEN"); acct.setFreezeType(freezeType); acct.setFreezeReason(reason);
        acct.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent("DepositAccount", saved.getId(), "FREEZE",
            prevStatus, "FROZEN", "DEPOSIT",
            "Account frozen: " + acn + " type=" + freezeType + " reason=" + reason);
        log.info("Account frozen: {} type={}", acn, freezeType);
        return saved;
    }

    // === Unfreeze ===
    @Override @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositAccount unfreezeAccount(String acn) {
        var acct = lockAccount(com.finvanta.util.TenantContext.getCurrentTenant(), acn);
        if (!acct.isFrozen()) throw new com.finvanta.util.BusinessException("NOT_FROZEN", "Account is not frozen");
        acct.setAccountStatus("ACTIVE"); acct.setFreezeType(null); acct.setFreezeReason(null);
        acct.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        log.info("Account unfrozen: {}", acn);
        return accountRepository.save(acct);
    }

    // === Close ===
    @Override @org.springframework.transaction.annotation.Transactional
    public com.finvanta.domain.entity.DepositAccount closeAccount(String acn, String reason) {
        var acct = lockAccount(com.finvanta.util.TenantContext.getCurrentTenant(), acn);
        if (acct.isClosed()) throw new com.finvanta.util.BusinessException("ALREADY_CLOSED", "Already closed");
        if (acct.getLedgerBalance().signum() != 0)
            throw new com.finvanta.util.BusinessException("NON_ZERO_BALANCE", "Balance must be zero to close. Current: " + acct.getLedgerBalance());
        acct.setAccountStatus("CLOSED"); acct.setClosedDate(businessDateService.getCurrentBusinessDate()); acct.setClosureReason(reason);
        acct.setUpdatedBy(com.finvanta.util.SecurityUtil.getCurrentUsername());
        log.info("Account closed: {}", acn);
        return accountRepository.save(acct);
    }

    // === Dormancy (EOD batch) ===
    @Override @org.springframework.transaction.annotation.Transactional
    public int markDormantAccounts(java.time.LocalDate businessDate) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        java.time.LocalDate cutoff = businessDate.minusMonths(DORMANCY_MONTHS);
        var candidates = accountRepository.findDormancyCandidates(tid, cutoff);
        int count = 0;
        for (var acct : candidates) {
            acct.setAccountStatus("DORMANT"); acct.setDormantDate(businessDate);
            acct.setUpdatedBy("SYSTEM_EOD");
            accountRepository.save(acct);
            count++;
        }
        if (count > 0) log.info("Marked {} accounts as DORMANT (cutoff={})", count, cutoff);
        return count;
    }

    // === Read Operations ===
    @Override public com.finvanta.domain.entity.DepositAccount getAccount(String acn) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        return accountRepository.findByTenantIdAndAccountNumber(tid, acn)
            .orElseThrow(() -> new com.finvanta.util.BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
    }
    @Override public java.util.List<com.finvanta.domain.entity.DepositAccount> getActiveAccounts() {
        return accountRepository.findAllActiveAccounts(com.finvanta.util.TenantContext.getCurrentTenant());
    }
    @Override public java.util.List<com.finvanta.domain.entity.DepositAccount> getAccountsByBranch(Long branchId) {
        return accountRepository.findByTenantIdAndBranchId(com.finvanta.util.TenantContext.getCurrentTenant(), branchId);
    }
    @Override public java.util.List<com.finvanta.domain.entity.DepositAccount> getAccountsByCustomer(Long customerId) {
        return accountRepository.findByTenantIdAndCustomerId(com.finvanta.util.TenantContext.getCurrentTenant(), customerId);
    }
    @Override public java.util.List<com.finvanta.domain.entity.DepositTransaction> getTransactionHistory(String acn) {
        String tid = com.finvanta.util.TenantContext.getCurrentTenant();
        var acct = accountRepository.findByTenantIdAndAccountNumber(tid, acn)
            .orElseThrow(() -> new com.finvanta.util.BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findByTenantIdAndDepositAccountIdOrderByPostingDateDesc(tid, acct.getId());
    }
    @Override public java.util.List<com.finvanta.domain.entity.DepositTransaction> getMiniStatement(String acn, int count) {
        var all = getTransactionHistory(acn);
        return all.size() <= count ? all : all.subList(0, count);
    }
}

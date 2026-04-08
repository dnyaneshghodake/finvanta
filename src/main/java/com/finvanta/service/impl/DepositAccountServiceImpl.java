package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.InterestAccrual;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.InterestAccrualRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.workflow.ApprovalWorkflowService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
@Service
public class DepositAccountServiceImpl implements DepositAccountService {
    private static final Logger log = LoggerFactory.getLogger(DepositAccountServiceImpl.class);

    /** RBI Master Direction on KYC 2016, Section 38: 24 months no customer-initiated txn = DORMANT */
    private static final long DORMANCY_MONTHS = 24;
    /** IT Act Section 194A: TDS threshold for annual interest income (INR 40,000 for non-seniors) */
    private static final BigDecimal TDS_THRESHOLD = new BigDecimal("40000.00");
    /** TDS rate: 10% per IT Act Section 194A */
    private static final BigDecimal TDS_RATE = new BigDecimal("0.10");

    private final DepositAccountRepository accountRepository;
    private final DepositTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final InterestAccrualRepository accrualRepository;
    private final ProductMasterRepository productMasterRepository;
    private final TransactionEngine transactionEngine;
    private final BusinessDateService businessDateService;
    private final AuditService auditService;
    private final ApprovalWorkflowService workflowService;

    public DepositAccountServiceImpl(DepositAccountRepository accountRepository,
                                      DepositTransactionRepository transactionRepository,
                                      CustomerRepository customerRepository,
                                      BranchRepository branchRepository,
                                      InterestAccrualRepository accrualRepository,
                                      ProductMasterRepository productMasterRepository,
                                      TransactionEngine transactionEngine,
                                      BusinessDateService businessDateService,
                                      AuditService auditService,
                                      ApprovalWorkflowService workflowService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.accrualRepository = accrualRepository;
        this.productMasterRepository = productMasterRepository;
        this.transactionEngine = transactionEngine;
        this.businessDateService = businessDateService;
        this.auditService = auditService;
        this.workflowService = workflowService;
    }

    /** Recompute available balance from ledger balance minus holds and uncleared. */
    private void recomputeAvailable(DepositAccount acct) {
        acct.setAvailableBalance(acct.getLedgerBalance()
            .subtract(acct.getHoldAmount())
            .subtract(acct.getUnclearedAmount()));
    }

    private String glForAccount(DepositAccount a) {
        return a.isSavings() ? GLConstants.SB_DEPOSITS : GLConstants.CA_DEPOSITS;
    }

    private DepositAccount lockAccount(String tenantId, String accountNumber) {
        return accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Deposit account not found: " + accountNumber));
    }

    /**
     * Build and persist a DepositTransaction record.
     *
     * @param debitCredit Explicit "DEBIT" or "CREDIT" — caller determines direction.
     *                    Per Finacle TRAN_DETAIL: debit/credit indicator must be set by
     *                    the posting logic, not inferred from transaction type name strings.
     *                    This prevents misclassification of REVERSAL transactions.
     */
    private DepositTransaction buildTxn(DepositAccount acct, BigDecimal amount,
            String txnType, String debitCredit, LocalDate valueDate, String narration,
            TransactionResult result, String txnRef,
            String idempotencyKey, String channel, String counterparty) {
        DepositTransaction txn = new DepositTransaction();
        txn.setTenantId(acct.getTenantId());
        txn.setTransactionRef(txnRef);
        txn.setDepositAccount(acct);
        txn.setTransactionType(txnType);
        txn.setAmount(amount);
        txn.setValueDate(valueDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setDebitCredit(debitCredit);
        txn.setBalanceAfter(acct.getLedgerBalance());
        txn.setNarration(narration);
        txn.setJournalEntryId(result.getJournalEntryId());
        txn.setVoucherNumber(result.getVoucherNumber());
        txn.setIdempotencyKey(idempotencyKey);
        txn.setChannel(channel);
        txn.setCounterpartyAccount(counterparty);
        txn.setCreatedBy(SecurityUtil.getCurrentUsername());
        txn.setUpdatedBy(SecurityUtil.getCurrentUsername());
        return transactionRepository.save(txn);
    }

    // === Account Opening ===
    @Override
    @Transactional
    public DepositAccount openAccount(Long customerId, Long branchId,
            String accountType, String productCode, BigDecimal initialDeposit,
            String nomineeName, String nomineeRelationship) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        Customer cust = customerRepository.findById(customerId)
            .filter(c -> tid.equals(c.getTenantId()))
            .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Not found: " + customerId));
        if (!cust.isActive()) throw new BusinessException("CUSTOMER_INACTIVE", "Inactive customer");
        if (!cust.isKycVerified()) throw new BusinessException("KYC_NOT_VERIFIED", "KYC required per RBI");
        Branch branch = branchRepository.findById(branchId)
            .filter(b -> tid.equals(b.getTenantId()) && b.isActive())
            .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch: " + branchId));
        if (accountType == null || (!accountType.startsWith("SAVINGS") && !accountType.startsWith("CURRENT")))
            throw new BusinessException("INVALID_ACCOUNT_TYPE", "Must be SAVINGS* or CURRENT*");

        // CBS Phase 2: Product-driven rate and minimum balance per Finacle PDDEF.
        // Interest rate and minimum balance are resolved from ProductMaster, not hardcoded.
        // Fallback to defaults if product not found (backward compatibility with Phase 1).
        String resolvedProductCode = productCode != null ? productCode : accountType;
        BigDecimal interestRate = accountType.startsWith("SAVINGS") ? new BigDecimal("4.0000") : BigDecimal.ZERO;
        BigDecimal minimumBalance = BigDecimal.ZERO;
        var productOpt = productMasterRepository.findByTenantIdAndProductCode(tid, resolvedProductCode);
        if (productOpt.isPresent()) {
            var product = productOpt.get();
            if (product.getMinInterestRate() != null) {
                interestRate = product.getMinInterestRate(); // Use min rate as default for CASA
            }
            if (product.getMinLoanAmount() != null) {
                minimumBalance = product.getMinLoanAmount(); // Repurposed: minLoanAmount = minBalance for CASA
            }
            log.info("CASA product resolved: code={}, rate={}, minBal={}", resolvedProductCode, interestRate, minimumBalance);
        } else {
            log.warn("CASA product not found: {}, using defaults (rate={}, minBal={})", resolvedProductCode, interestRate, minimumBalance);
        }

        String accNo = ReferenceGenerator.generateDepositAccountNumber(branch.getBranchCode());
        DepositAccount a = new DepositAccount();
        a.setTenantId(tid); a.setAccountNumber(accNo); a.setCustomer(cust); a.setBranch(branch);
        a.setAccountType(accountType); a.setProductCode(resolvedProductCode);
        // CBS Phase 2: Maker-Checker for account opening per Finacle ACCTOPN.
        // Account starts in PENDING_ACTIVATION — requires CHECKER approval to activate.
        // PENDING_ACTIVATION accounts cannot transact (isDebitAllowed/isCreditAllowed return false).
        // Initial deposit is deferred until activation.
        a.setAccountStatus(DepositAccountStatus.PENDING_ACTIVATION); a.setCurrencyCode("INR");
        a.setLedgerBalance(BigDecimal.ZERO); a.setAvailableBalance(BigDecimal.ZERO);
        a.setHoldAmount(BigDecimal.ZERO); a.setUnclearedAmount(BigDecimal.ZERO);
        a.setOdLimit(BigDecimal.ZERO); a.setMinimumBalance(minimumBalance);
        a.setInterestRate(interestRate);
        a.setAccruedInterest(BigDecimal.ZERO);
        a.setYtdInterestCredited(BigDecimal.ZERO); a.setYtdTdsDeducted(BigDecimal.ZERO);
        LocalDate bizDate = businessDateService.getCurrentBusinessDate();
        a.setOpenedDate(bizDate); a.setLastTransactionDate(bizDate);
        a.setNomineeName(nomineeName); a.setNomineeRelationship(nomineeRelationship);
        a.setCreatedBy(user); a.setUpdatedBy(user);
        DepositAccount saved = accountRepository.save(a);

        // CBS Phase 2: Initiate maker-checker approval workflow.
        // Per Finacle ACCTOPN / Temenos ACCOUNT.OPENING: account opening requires
        // dual authorization. MAKER submits → CHECKER approves → account activates.
        String payload = "CASA|" + accNo + "|" + accountType + "|" + cust.getCustomerNumber()
            + "|" + branch.getBranchCode() + "|rate=" + interestRate + "|minBal=" + minimumBalance
            + (initialDeposit != null && initialDeposit.signum() > 0 ? "|initDep=" + initialDeposit : "");
        try {
            workflowService.initiateApproval("DepositAccount", saved.getId(),
                "ACCOUNT_OPENING", "CASA account opening: " + accNo, payload);
            log.info("CASA maker-checker initiated: num={}, maker={}", accNo, user);
        } catch (Exception e) {
            // If workflow initiation fails (e.g., duplicate), auto-activate for backward compatibility.
            // Per Finacle: workflow failure should not block account creation in non-strict mode.
            log.warn("Maker-checker initiation failed for {}, auto-activating: {}", accNo, e.getMessage());
            saved.setAccountStatus(DepositAccountStatus.ACTIVE);
            accountRepository.save(saved);
        }

        auditService.logEvent("DepositAccount", saved.getId(), "ACCOUNT_OPENED",
            null, accNo, "DEPOSIT",
            "CASA account opened: " + accNo + " type=" + accountType
                + " customer=" + cust.getCustomerNumber() + " branch=" + branch.getBranchCode()
                + " status=" + saved.getAccountStatus()
                + " rate=" + interestRate + " minBal=" + minimumBalance);
        log.info("CASA opened: num={}, type={}, cust={}, status={}", accNo, accountType, cust.getCustomerNumber(), saved.getAccountStatus());

        // CBS: Initial deposit is only processed if account is ACTIVE (auto-activated or Phase 1 mode).
        // For PENDING_ACTIVATION accounts, the initial deposit is deferred until checker approval.
        if (saved.isActive() && initialDeposit != null && initialDeposit.signum() > 0) {
            deposit(accNo, initialDeposit, bizDate, "Initial deposit at account opening", null, "BRANCH");
            saved = accountRepository.findByTenantIdAndAccountNumber(tid, accNo).orElse(saved);
        }
        return saved;
    }

    // === Deposit ===
    @Override
    @Transactional
    public DepositTransaction deposit(String accountNumber, BigDecimal amount,
            LocalDate businessDate, String narration, String idempotencyKey, String channel) {
        String tid = TenantContext.getCurrentTenant();
        if (idempotencyKey != null) {
            DepositTransaction dup = transactionRepository.findByTenantIdAndIdempotencyKey(tid, idempotencyKey).orElse(null);
            if (dup != null) return dup;
        }
        DepositAccount acct = lockAccount(tid, accountNumber);
        if (!acct.isCreditAllowed()) throw new BusinessException("ACCOUNT_NOT_CREDITABLE", "Status " + acct.getAccountStatus());
        String gl = glForAccount(acct);
        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("CASH_DEPOSIT").accountReference(accountNumber)
            .amount(amount).valueDate(businessDate).branchCode(acct.getBranch().getBranchCode())
            .narration(narration != null ? narration : "Cash deposit").idempotencyKey(idempotencyKey)
            .journalLines(List.of(
                new JournalLineRequest(GLConstants.BANK_OPERATIONS, DebitCredit.DEBIT, amount, "Cash deposit"),
                new JournalLineRequest(gl, DebitCredit.CREDIT, amount, "Credit " + accountNumber)
            )).build());
        if (r.isPendingApproval()) return buildTxn(acct, amount, "CASH_DEPOSIT", "CREDIT", businessDate, narration, r, r.getTransactionRef(), idempotencyKey, channel, null);
        acct.setLedgerBalance(acct.getLedgerBalance().add(amount));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);
        if (acct.isDormant()) { acct.setAccountStatus(DepositAccountStatus.ACTIVE); acct.setDormantDate(null); }
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);
        return buildTxn(acct, amount, "CASH_DEPOSIT", "CREDIT", businessDate, narration, r, r.getTransactionRef(), idempotencyKey, channel, null);
    }

    // === Withdrawal ===
    @Override @Transactional
    public DepositTransaction withdraw(String acn, BigDecimal amount,
            LocalDate bd, String narration, String idk, String channel) {
        String tid = TenantContext.getCurrentTenant();
        if (idk != null) { var dup = transactionRepository.findByTenantIdAndIdempotencyKey(tid, idk).orElse(null); if (dup != null) return dup; }
        var acct = lockAccount(tid, acn);
        if (!acct.isDebitAllowed()) throw new BusinessException("ACCOUNT_NOT_DEBITABLE", "Status " + acct.getAccountStatus());
        if (!acct.hasSufficientFunds(amount)) throw new BusinessException("INSUFFICIENT_BALANCE", "Withdrawal " + amount + " > available " + acct.getEffectiveAvailable());
        // CBS: Minimum balance enforcement per Finacle ACCTLIMIT / RBI CASA norms.
        // Withdrawal that breaches minimum balance is rejected (PMJDY accounts exempt: minBal=0).
        // Per Tier-1 CBS: penalty-based breach is a Phase 2 enhancement via ChargeEngine.
        if (acct.getMinimumBalance().signum() > 0) {
            BigDecimal postWithdrawalBalance = acct.getLedgerBalance().subtract(amount);
            if (postWithdrawalBalance.compareTo(acct.getMinimumBalance()) < 0) {
                throw new BusinessException("MINIMUM_BALANCE_BREACH",
                    "Withdrawal of INR " + amount + " would breach minimum balance of INR "
                        + acct.getMinimumBalance() + ". Post-withdrawal balance would be INR "
                        + postWithdrawalBalance);
            }
        }
        // CBS: Daily withdrawal limit enforcement per Finacle ACCTLIMIT / Temenos LIMIT.CHECK
        if (acct.getDailyWithdrawalLimit() != null && acct.getDailyWithdrawalLimit().signum() > 0) {
            BigDecimal dailyDebits = transactionRepository.sumDailyDebits(tid, acct.getId(), bd);
            if (dailyDebits.add(amount).compareTo(acct.getDailyWithdrawalLimit()) > 0)
                throw new BusinessException("DAILY_LIMIT_EXCEEDED",
                    "Daily withdrawal limit INR " + acct.getDailyWithdrawalLimit()
                        + " exceeded. Today's debits: " + dailyDebits + ", requested: " + amount);
        }
        String gl = glForAccount(acct);
        var r = transactionEngine.execute(TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("CASH_WITHDRAWAL").accountReference(acn)
            .amount(amount).valueDate(bd).branchCode(acct.getBranch().getBranchCode())
            .narration(narration != null ? narration : "Cash withdrawal").idempotencyKey(idk)
            .journalLines(List.of(
                new JournalLineRequest(gl, DebitCredit.DEBIT, amount, "Debit " + acn),
                new JournalLineRequest(GLConstants.BANK_OPERATIONS, DebitCredit.CREDIT, amount, "Cash withdrawal")
            )).build());
        if (r.isPendingApproval()) return buildTxn(acct, amount, "CASH_WITHDRAWAL", "DEBIT", bd, narration, r, r.getTransactionRef(), idk, channel, null);
        acct.setLedgerBalance(acct.getLedgerBalance().subtract(amount));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(bd); acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);
        return buildTxn(acct, amount, "CASH_WITHDRAWAL", "DEBIT", bd, narration, r, r.getTransactionRef(), idk, channel, null);
    }

    // === Transfer ===
    @Override @Transactional
    public DepositTransaction transfer(String from, String to,
            BigDecimal amount, LocalDate bd, String narration, String idk) {
        String tid = TenantContext.getCurrentTenant();
        if (from.equals(to)) throw new BusinessException("SAME_ACCOUNT", "Cannot transfer to same account");
        // CBS: Lock accounts in deterministic order (alphabetical by account number)
        // to prevent ABBA deadlock when two concurrent transfers happen in opposite directions.
        // Per Finacle TRAN_POSTING / Temenos OFS: all multi-account locks must be ordered.
        boolean fromFirst = from.compareTo(to) < 0;
        var firstAcct = lockAccount(tid, fromFirst ? from : to);
        var secondAcct = lockAccount(tid, fromFirst ? to : from);
        var src = fromFirst ? firstAcct : secondAcct;
        var tgt = fromFirst ? secondAcct : firstAcct;
        if (!src.isDebitAllowed()) throw new BusinessException("SOURCE_NOT_DEBITABLE", "Source " + src.getAccountStatus());
        if (!tgt.isCreditAllowed()) throw new BusinessException("TARGET_NOT_CREDITABLE", "Target " + tgt.getAccountStatus());
        if (!src.hasSufficientFunds(amount)) throw new BusinessException("INSUFFICIENT_BALANCE", "Transfer " + amount + " > available " + src.getEffectiveAvailable());
        // CBS: Minimum balance enforcement on transfer source per Finacle ACCTLIMIT
        if (src.getMinimumBalance().signum() > 0) {
            BigDecimal postTransferBalance = src.getLedgerBalance().subtract(amount);
            if (postTransferBalance.compareTo(src.getMinimumBalance()) < 0) {
                throw new BusinessException("MINIMUM_BALANCE_BREACH",
                    "Transfer of INR " + amount + " would breach minimum balance of INR "
                        + src.getMinimumBalance() + " on source account " + from);
            }
        }
        var r = transactionEngine.execute(TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("TRANSFER_DEBIT").accountReference(from)
            .amount(amount).valueDate(bd).branchCode(src.getBranch().getBranchCode())
            .narration(narration != null ? narration : "Transfer to " + to).idempotencyKey(idk)
            .journalLines(List.of(
                new JournalLineRequest(glForAccount(src), DebitCredit.DEBIT, amount, "Transfer debit"),
                new JournalLineRequest(glForAccount(tgt), DebitCredit.CREDIT, amount, "Transfer credit")
            )).build());
        if (r.isPendingApproval()) return buildTxn(src, amount, "TRANSFER_DEBIT", "DEBIT", bd, narration, r, r.getTransactionRef(), idk, "INTERNAL", to);
        src.setLedgerBalance(src.getLedgerBalance().subtract(amount));
        recomputeAvailable(src);
        src.setLastTransactionDate(bd); src.setUpdatedBy(SecurityUtil.getCurrentUsername()); accountRepository.save(src);
        tgt.setLedgerBalance(tgt.getLedgerBalance().add(amount));
        recomputeAvailable(tgt);
        tgt.setLastTransactionDate(bd); if (tgt.isDormant()) { tgt.setAccountStatus(DepositAccountStatus.ACTIVE); tgt.setDormantDate(null); }
        tgt.setUpdatedBy(SecurityUtil.getCurrentUsername()); accountRepository.save(tgt);
        // CBS: TRANSFER_CREDIT leg gets its own unique transactionRef to satisfy
        // the unique constraint on (tenant_id, transaction_ref) in deposit_transactions.
        // Per Finacle TRAN_DETAIL: each subledger entry has its own unique reference.
        String creditTxnRef = ReferenceGenerator.generateTransactionRef();
        buildTxn(tgt, amount, "TRANSFER_CREDIT", "CREDIT", bd, "Transfer from " + from, r, creditTxnRef, null, "INTERNAL", from);
        return buildTxn(src, amount, "TRANSFER_DEBIT", "DEBIT", bd, narration, r, r.getTransactionRef(), idk, "INTERNAL", to);
    }

    // === Interest Accrual (EOD daily) ===
    @Override @Transactional
    public void accrueInterest(String acn, LocalDate bd) {
        String tid = TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        if (!acct.isSavings() || acct.getInterestRate().signum() <= 0) return;
        // CBS: Guard against double-accrual if EOD reruns or retries for the same date.
        // Per Finacle EOD / Temenos COB: accrual is idempotent per business date.
        if (bd.equals(acct.getLastInterestAccrualDate())) return;
        // CBS: YTD Reset on Indian Financial Year boundary (April 1).
        // Per IT Act Section 194A: TDS threshold is per financial year (Apr 1 - Mar 31).
        // Without reset, YTD accumulates forever and TDS is incorrectly applied in year 2+.
        if (bd.getMonthValue() == 4 && bd.getDayOfMonth() == 1) {
            if (acct.getLastInterestAccrualDate() != null
                    && acct.getLastInterestAccrualDate().getYear() < bd.getYear()) {
                acct.setYtdInterestCredited(BigDecimal.ZERO);
                acct.setYtdTdsDeducted(BigDecimal.ZERO);
                log.info("FY reset: YTD counters reset for {} on {}", acn, bd);
            }
        }
        BigDecimal daily = acct.getLedgerBalance().multiply(acct.getInterestRate())
            .divide(new BigDecimal("36500"), 2, RoundingMode.HALF_UP);
        if (daily.signum() <= 0) return;
        acct.setAccruedInterest(acct.getAccruedInterest().add(daily));
        acct.setLastInterestAccrualDate(bd);
        accountRepository.save(acct);

        // CBS: Per-day interest accrual record for RBI audit trail.
        // Per RBI IT Governance Direction 2023: all financial calculations must be
        // logged and reproducible. This enables deterministic accrual replay,
        // audit reconciliation, and forensic analysis.
        // Reuses the existing interest_accruals table (shared with loan module).
        InterestAccrual record = new InterestAccrual();
        record.setTenantId(tid);
        record.setAccountId(acct.getId());
        record.setAccrualDate(bd);
        record.setPrincipalBase(acct.getLedgerBalance());
        record.setRateApplied(acct.getInterestRate());
        record.setDaysCount(1);
        record.setAccruedAmount(daily);
        record.setAccrualType("CASA_REGULAR");
        record.setPostedFlag(false); // Not yet posted to GL — credited quarterly
        record.setBusinessDate(bd);
        record.setCreatedBy("SYSTEM_EOD");
        accrualRepository.save(record);
    }

    // === Quarterly Interest Credit with TDS ===
    @Override @Transactional
    public DepositTransaction creditInterest(String acn, LocalDate bd) {
        String tid = TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        var interest = acct.getAccruedInterest();
        if (interest.signum() <= 0) return null;
        // CBS: Idempotency guard — prevent double credit on EOD retry.
        if (bd.equals(acct.getLastInterestCreditDate())) {
            log.warn("Interest already credited for {} on {}, skipping", acn, bd);
            return null;
        }
        String gl = glForAccount(acct);
        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("INTEREST_CREDIT").accountReference(acn)
            .amount(interest).valueDate(bd).branchCode(acct.getBranch().getBranchCode())
            .narration("Quarterly interest credit").systemGenerated(true)
            .journalLines(List.of(
                new JournalLineRequest(GLConstants.INTEREST_EXPENSE_DEPOSITS, DebitCredit.DEBIT, interest, "Interest expense"),
                new JournalLineRequest(gl, DebitCredit.CREDIT, interest, "Interest credit " + acn)
            )).build());
        acct.setLedgerBalance(acct.getLedgerBalance().add(interest));
        acct.setYtdInterestCredited(acct.getYtdInterestCredited().add(interest));

        // IT Act Section 194A: TDS deduction at source if YTD interest exceeds threshold.
        // TDS is deducted on the TOTAL quarterly credit amount (not just the excess).
        // Per RBI/IT Act: TDS = 10% of interest if cumulative YTD interest > INR 40,000.
        BigDecimal tdsAmount = BigDecimal.ZERO;
        if (acct.getYtdInterestCredited().compareTo(TDS_THRESHOLD) > 0) {
            tdsAmount = interest.multiply(TDS_RATE).setScale(2, RoundingMode.HALF_UP);
            if (tdsAmount.signum() > 0) {
                acct.setLedgerBalance(acct.getLedgerBalance().subtract(tdsAmount));
                acct.setYtdTdsDeducted(acct.getYtdTdsDeducted().add(tdsAmount));
                // GL: DR Customer Deposits / CR TDS Payable (2500)
                transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("DEPOSIT").transactionType("TDS_DEBIT").accountReference(acn)
                    .amount(tdsAmount).valueDate(bd).branchCode(acct.getBranch().getBranchCode())
                    .narration("TDS u/s 194A on interest INR " + interest).systemGenerated(true)
                    .journalLines(List.of(
                        new JournalLineRequest(gl, DebitCredit.DEBIT, tdsAmount, "TDS deduction"),
                        new JournalLineRequest(GLConstants.TDS_PAYABLE, DebitCredit.CREDIT, tdsAmount, "TDS payable u/s 194A")
                    )).build());
                log.info("TDS deducted: account={}, interest={}, tds={}", acn, interest, tdsAmount);
            }
        }

        recomputeAvailable(acct);
        acct.setAccruedInterest(BigDecimal.ZERO);
        acct.setLastInterestCreditDate(bd);
        accountRepository.save(acct);
        return buildTxn(acct, interest, "INTEREST_CREDIT", "CREDIT", bd,
            "Quarterly interest credit" + (tdsAmount.signum() > 0 ? " (TDS INR " + tdsAmount + " deducted)" : ""),
            r, r.getTransactionRef(), null, "SYSTEM", null);
    }
    // === Account Activation (Maker-Checker Phase 2) ===
    // Per Finacle ACCTOPN: CHECKER approves the workflow → this method activates the account.
    // Transitions: PENDING_ACTIVATION → ACTIVE
    @Override @Transactional
    public DepositAccount activateAccount(String acn) {
        String tid = TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        if (acct.getAccountStatus() != DepositAccountStatus.PENDING_ACTIVATION) {
            throw new BusinessException("INVALID_STATE",
                "Account " + acn + " is not in PENDING_ACTIVATION state. Current: " + acct.getAccountStatus());
        }
        acct.setAccountStatus(DepositAccountStatus.ACTIVE);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);

        auditService.logEvent("DepositAccount", saved.getId(), "ACCOUNT_ACTIVATED",
            "PENDING_ACTIVATION", "ACTIVE", "DEPOSIT",
            "Account activated by checker: " + acn + " | Activated by: " + SecurityUtil.getCurrentUsername());

        log.info("CASA activated: num={}, checker={}", acn, SecurityUtil.getCurrentUsername());
        return saved;
    }

    // === Freeze ===
    @Override @Transactional
    public DepositAccount freezeAccount(String acn, String freezeType, String reason) {
        var acct = lockAccount(TenantContext.getCurrentTenant(), acn);
        if (acct.isClosed()) throw new BusinessException("ACCOUNT_CLOSED", "Cannot freeze closed account");
        DepositAccountStatus prevStatus = acct.getAccountStatus();
        acct.setAccountStatus(DepositAccountStatus.FROZEN); acct.setFreezeType(freezeType); acct.setFreezeReason(reason);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent("DepositAccount", saved.getId(), "FREEZE",
            prevStatus.name(), "FROZEN", "DEPOSIT",
            "Account frozen: " + acn + " type=" + freezeType + " reason=" + reason);
        log.info("Account frozen: {} type={}", acn, freezeType);
        return saved;
    }

    // === Unfreeze ===
    @Override @Transactional
    public DepositAccount unfreezeAccount(String acn) {
        var acct = lockAccount(TenantContext.getCurrentTenant(), acn);
        if (!acct.isFrozen()) throw new BusinessException("NOT_FROZEN", "Account is not frozen");
        String prevFreezeType = acct.getFreezeType();
        acct.setAccountStatus(DepositAccountStatus.ACTIVE); acct.setFreezeType(null); acct.setFreezeReason(null);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent("DepositAccount", saved.getId(), "UNFREEZE",
            "FROZEN", "ACTIVE", "DEPOSIT",
            "Account unfrozen: " + acn + " (was " + prevFreezeType + ")");
        log.info("Account unfrozen: {}", acn);
        return saved;
    }

    // === Close ===
    // Per Finacle ACCTCLS / Temenos ACCOUNT.CLOSURE:
    // 1. Balance must be zero
    // 2. Accrued interest must be zero (credit pending interest first)
    // 3. No active holds/liens
    // 4. Not already closed
    @Override @Transactional
    public DepositAccount closeAccount(String acn, String reason) {
        var acct = lockAccount(TenantContext.getCurrentTenant(), acn);
        if (acct.isClosed()) throw new BusinessException("ALREADY_CLOSED", "Already closed");
        if (acct.getAccruedInterest() != null && acct.getAccruedInterest().signum() > 0)
            throw new BusinessException("PENDING_INTEREST",
                "Account has accrued interest INR " + acct.getAccruedInterest()
                    + ". Credit interest before closure.");
        if (acct.getHoldAmount() != null && acct.getHoldAmount().signum() > 0)
            throw new BusinessException("ACTIVE_HOLD",
                "Account has active hold/lien INR " + acct.getHoldAmount()
                    + ". Release all holds before closure.");
        if (acct.getLedgerBalance().signum() != 0)
            throw new BusinessException("NON_ZERO_BALANCE",
                "Balance must be zero to close. Current: " + acct.getLedgerBalance());
        DepositAccountStatus prevStatus = acct.getAccountStatus();
        acct.setAccountStatus(DepositAccountStatus.CLOSED); acct.setClosedDate(businessDateService.getCurrentBusinessDate()); acct.setClosureReason(reason);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent("DepositAccount", saved.getId(), "ACCOUNT_CLOSED",
            prevStatus.name(), "CLOSED", "DEPOSIT",
            "Account closed: " + acn + " reason=" + reason);
        log.info("Account closed: {}", acn);
        return saved;
    }

    // === Dormancy (EOD batch) ===
    @Override @Transactional
    public int markDormantAccounts(LocalDate businessDate) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate cutoff = businessDate.minusMonths(DORMANCY_MONTHS);
        var candidates = accountRepository.findDormancyCandidates(tid, cutoff);
        int count = 0;
        for (var acct : candidates) {
            acct.setAccountStatus(DepositAccountStatus.DORMANT); acct.setDormantDate(businessDate);
            acct.setUpdatedBy("SYSTEM_EOD");
            accountRepository.save(acct);
            count++;
        }
        if (count > 0) log.info("Marked {} accounts as DORMANT (cutoff={})", count, cutoff);
        return count;
    }

    // === Read Operations ===
    @Override public DepositAccount getAccount(String acn) {
        String tid = TenantContext.getCurrentTenant();
        return accountRepository.findByTenantIdAndAccountNumber(tid, acn)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
    }
    @Override public List<DepositAccount> getAllAccounts() {
        return accountRepository.findAllNonClosedAccounts(TenantContext.getCurrentTenant());
    }
    @Override public List<DepositAccount> getActiveAccounts() {
        return accountRepository.findAllActiveAccounts(TenantContext.getCurrentTenant());
    }
    @Override public List<DepositAccount> getAccountsByBranch(Long branchId) {
        return accountRepository.findByTenantIdAndBranchId(TenantContext.getCurrentTenant(), branchId);
    }
    @Override public List<DepositAccount> getAccountsByCustomer(Long customerId) {
        return accountRepository.findByTenantIdAndCustomerId(TenantContext.getCurrentTenant(), customerId);
    }
    @Override public List<DepositTransaction> getTransactionHistory(String acn) {
        String tid = TenantContext.getCurrentTenant();
        var acct = accountRepository.findByTenantIdAndAccountNumber(tid, acn)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findByTenantIdAndDepositAccountIdOrderByPostingDateDesc(tid, acct.getId());
    }
    @Override public List<DepositTransaction> getMiniStatement(String acn, int count) {
        String tid = TenantContext.getCurrentTenant();
        var acct = accountRepository.findByTenantIdAndAccountNumber(tid, acn)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findRecentTransactions(tid, acct.getId(), PageRequest.of(0, count));
    }

    @Override public List<DepositTransaction> getStatement(String acn, LocalDate fromDate, LocalDate toDate) {
        String tid = TenantContext.getCurrentTenant();
        var acct = accountRepository.findByTenantIdAndAccountNumber(tid, acn)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findByDateRange(tid, acct.getId(), fromDate, toDate);
    }

    // === Transaction Reversal ===
    // Per Finacle TRAN_REVERSAL / Temenos REVERSAL:
    // 1. Find original transaction (must exist, must not already be reversed)
    // 2. Post contra GL entries via TransactionEngine (swap DR/CR legs)
    // 3. Restore account balance (add back for debits, subtract for credits)
    // 4. Mark original as reversed (never delete per CBS audit rules)
    // 5. Create reversal DepositTransaction linked to original
    @Override @Transactional
    public DepositTransaction reverseTransaction(String transactionRef, String reason, LocalDate businessDate) {
        String tid = TenantContext.getCurrentTenant();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Reversal reason is mandatory per RBI audit norms");
        }

        // Step 1: Find and validate original transaction
        DepositTransaction original = transactionRepository.findByTenantIdAndTransactionRef(tid, transactionRef)
            .orElseThrow(() -> new BusinessException("TRANSACTION_NOT_FOUND",
                "Transaction not found: " + transactionRef));
        if (original.isReversed()) {
            throw new BusinessException("ALREADY_REVERSED",
                "Transaction " + transactionRef + " is already reversed");
        }

        // Step 2: Lock the account
        var acct = lockAccount(tid, original.getDepositAccount().getAccountNumber());
        if (acct.isClosed()) {
            throw new BusinessException("ACCOUNT_CLOSED",
                "Cannot reverse transaction on closed account: " + acct.getAccountNumber());
        }

        String gl = glForAccount(acct);
        BigDecimal amount = original.getAmount();
        boolean wasDebit = "DEBIT".equals(original.getDebitCredit());

        // Step 3: Post contra GL entries (swap DR/CR from original)
        List<JournalLineRequest> contraLines;
        if (wasDebit) {
            // Original was DR Customer Deposits / CR Bank Ops → Reverse: DR Bank Ops / CR Customer Deposits
            contraLines = List.of(
                new JournalLineRequest(GLConstants.BANK_OPERATIONS, DebitCredit.DEBIT, amount,
                    "Reversal of " + original.getTransactionType() + " " + transactionRef),
                new JournalLineRequest(gl, DebitCredit.CREDIT, amount,
                    "Reversal credit " + acct.getAccountNumber())
            );
        } else {
            // Original was DR Bank Ops / CR Customer Deposits → Reverse: DR Customer Deposits / CR Bank Ops
            contraLines = List.of(
                new JournalLineRequest(gl, DebitCredit.DEBIT, amount,
                    "Reversal of " + original.getTransactionType() + " " + transactionRef),
                new JournalLineRequest(GLConstants.BANK_OPERATIONS, DebitCredit.CREDIT, amount,
                    "Reversal debit " + acct.getAccountNumber())
            );
        }

        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
            .sourceModule("DEPOSIT").transactionType("REVERSAL")
            .accountReference(acct.getAccountNumber())
            .amount(amount).valueDate(businessDate)
            .branchCode(acct.getBranch().getBranchCode())
            .narration("Reversal of " + transactionRef + ": " + reason)
            .journalLines(contraLines)
            .systemGenerated(false)
            .build());

        // Step 4: Restore account balance
        if (wasDebit) {
            // Original debited → reversal credits back
            acct.setLedgerBalance(acct.getLedgerBalance().add(amount));
        } else {
            // Original credited → reversal debits back
            acct.setLedgerBalance(acct.getLedgerBalance().subtract(amount));
        }
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);

        // Step 5: Mark original as reversed
        original.setReversed(true);
        original.setReversedByRef(r.getTransactionRef());
        original.setUpdatedBy(SecurityUtil.getCurrentUsername());
        transactionRepository.save(original);

        auditService.logEvent("DepositTransaction", original.getId(), "REVERSAL",
            original.getTransactionRef(), r.getTransactionRef(), "DEPOSIT",
            "Transaction reversed: " + transactionRef + " | Reason: " + reason
                + " | Amount: INR " + amount + " | Account: " + acct.getAccountNumber());

        log.info("Transaction reversed: ref={}, amount={}, account={}, reason={}",
            transactionRef, amount, acct.getAccountNumber(), reason);

        // Step 6: Create reversal transaction record.
        // CBS: Reversal direction is opposite of original — reversing a CREDIT creates
        // a DEBIT record, and vice versa. Per Finacle TRAN_REVERSAL / Temenos REVERSAL:
        // the subledger entry must accurately reflect the contra movement.
        String reversalDirection = wasDebit ? "CREDIT" : "DEBIT";
        return buildTxn(acct, amount, "REVERSAL", reversalDirection, businessDate,
            "Reversal of " + transactionRef + ": " + reason,
            r, r.getTransactionRef(), null, original.getChannel(), null);
    }
}

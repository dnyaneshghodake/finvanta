package com.finvanta.cbs.modules.account.service;

import com.finvanta.accounting.AccountingService;
import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.cbs.modules.account.dto.request.FinancialRequest;
import com.finvanta.cbs.modules.account.dto.request.OpenAccountRequest;
import com.finvanta.cbs.modules.account.dto.request.TransferRequest;
import com.finvanta.cbs.modules.account.validator.AccountValidator;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.DepositAccountType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.service.CbsReferenceService;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS CASA Module Service Implementation per CBS CUSTACCT standard.
 *
 * <p>REFACTORED Tier-1 CBS service demonstrating the target architecture:
 * <ul>
 *   <li>All financial postings route through {@link TransactionEngine#execute}</li>
 *   <li>Business validations delegated to {@link AccountValidator}</li>
 *   <li>Audit trail on every state mutation via {@link AuditService}</li>
 *   <li>{@code @Transactional} on every mutation method</li>
 *   <li>Pessimistic locking on balance mutations</li>
 *   <li>No DTO/API concerns -- returns domain entities to the controller</li>
 * </ul>
 *
 * <p>GL Mapping:
 * <ul>
 *   <li>Savings Deposits -> GL 2010 (SB Deposits - Liability)</li>
 *   <li>Current Deposits -> GL 2020 (CA Deposits - Liability)</li>
 *   <li>Interest Expense -> GL 5010 (Interest Expense on Deposits - Expense)</li>
 *   <li>TDS Payable -> GL 2500 (TDS Payable - Liability per IT Act Section 194A)</li>
 * </ul>
 *
 * <p>Concurrency: PESSIMISTIC_WRITE lock on every balance mutation.
 * Transfer deadlock prevention: accounts locked in alphabetical order.
 */
@Service
public class DepositAccountModuleServiceImpl implements DepositAccountModuleService {

    private static final Logger log = LoggerFactory.getLogger(DepositAccountModuleServiceImpl.class);

    private final DepositAccountRepository accountRepository;
    private final DepositTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final ProductGLResolver glResolver;
    private final TransactionEngine transactionEngine;
    private final AccountingService accountingService;
    private final BusinessDateService businessDateService;
    private final AuditService auditService;
    private final AccountValidator accountValidator;
    private final BranchAccessValidator branchAccessValidator;
    private final CbsReferenceService cbsReferenceService;

    public DepositAccountModuleServiceImpl(
            DepositAccountRepository accountRepository,
            DepositTransactionRepository transactionRepository,
            CustomerRepository customerRepository,
            BranchRepository branchRepository,
            ProductGLResolver glResolver,
            TransactionEngine transactionEngine,
            AccountingService accountingService,
            BusinessDateService businessDateService,
            AuditService auditService,
            AccountValidator accountValidator,
            BranchAccessValidator branchAccessValidator,
            CbsReferenceService cbsReferenceService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.glResolver = glResolver;
        this.transactionEngine = transactionEngine;
        this.accountingService = accountingService;
        this.businessDateService = businessDateService;
        this.auditService = auditService;
        this.accountValidator = accountValidator;
        this.branchAccessValidator = branchAccessValidator;
        this.cbsReferenceService = cbsReferenceService;
    }

    // === Account Lifecycle ===

    @Override
    @Transactional
    public DepositAccount openAccount(OpenAccountRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new BusinessException("CBS-CUST-001", "Customer not found"));

        accountValidator.validateAccountOpening(request, customer, businessDate);

        Branch branch = branchRepository.findById(request.branchId())
                .orElseThrow(() -> new BusinessException("CBS-TXN-005", "Branch not found"));
        branchAccessValidator.validateAccess(branch);

        DepositAccountType parsedAccountType = DepositAccountType.valueOf(request.accountType());
        String accNo = cbsReferenceService.generateDepositAccountNumber(
                branch.getBranchCode(), parsedAccountType.isSavings());

        DepositAccount account = new DepositAccount();
        account.setTenantId(tenantId);
        account.setAccountNumber(accNo);
        account.setCustomer(customer);
        account.setBranch(branch);
        account.setAccountType(parsedAccountType);
        account.setProductCode(request.productCode());
        account.setCurrencyCode(request.currencyCode());
        account.setAccountStatus(DepositAccountStatus.PENDING_ACTIVATION);
        account.setOpenedDate(businessDate);
        account.setLedgerBalance(BigDecimal.ZERO);
        account.setAvailableBalance(BigDecimal.ZERO);
        account.setHoldAmount(BigDecimal.ZERO);
        account.setUnclearedAmount(BigDecimal.ZERO);

        DepositAccount saved = accountRepository.save(account);

        auditService.logEvent("DepositAccount", saved.getId(), "OPEN",
                null, saved, "ACCOUNT", "Account opened in PENDING_ACTIVATION");

        log.info("CBS Account opened: {} for customer {} in branch {}",
                saved.getAccountNumber(), customer.getCustomerNumber(), branch.getBranchCode());

        return saved;
    }

    @Override
    @Transactional
    public DepositAccount activateAccount(String accountNumber) {
        DepositAccount account = lockAccount(accountNumber);

        if (account.getAccountStatus() != DepositAccountStatus.PENDING_ACTIVATION) {
            throw new BusinessException("CBS-ACCT-002",
                    "Account must be in PENDING_ACTIVATION to activate. Current: "
                            + account.getAccountStatus());
        }

        account.setAccountStatus(DepositAccountStatus.ACTIVE);
        DepositAccount saved = accountRepository.save(account);

        auditService.logEventInline("DepositAccount", saved.getId(), "ACTIVATE",
                null, saved, "ACCOUNT", "Account activated by CHECKER");

        return saved;
    }

    @Override
    @Transactional
    public DepositAccount freezeAccount(String accountNumber, String freezeType, String reason) {
        DepositAccount account = lockAccount(accountNumber);
        accountValidator.validateAccountForTransaction(account);

        account.setAccountStatus(DepositAccountStatus.FROZEN);
        account.setFreezeType(freezeType);
        account.setFreezeReason(reason);
        DepositAccount saved = accountRepository.save(account);

        auditService.logEventInline("DepositAccount", saved.getId(), "FREEZE",
                null, saved, "ACCOUNT", "Account frozen: " + freezeType);

        return saved;
    }

    @Override
    @Transactional
    public DepositAccount unfreezeAccount(String accountNumber) {
        DepositAccount account = lockAccount(accountNumber);

        if (account.getAccountStatus() != DepositAccountStatus.FROZEN) {
            throw new BusinessException("CBS-ACCT-002", "Account is not frozen");
        }

        account.setAccountStatus(DepositAccountStatus.ACTIVE);
        account.setFreezeType(null);
        account.setFreezeReason(null);
        DepositAccount saved = accountRepository.save(account);

        auditService.logEventInline("DepositAccount", saved.getId(), "UNFREEZE",
                null, saved, "ACCOUNT", "Account unfrozen");

        return saved;
    }

    @Override
    @Transactional
    public DepositAccount closeAccount(String accountNumber, String reason) {
        DepositAccount account = lockAccount(accountNumber);

        if (account.getAccountStatus() == DepositAccountStatus.CLOSED) {
            throw new BusinessException("CBS-ACCT-004",
                    "Account is already closed: " + accountNumber);
        }

        if (account.getHoldAmount() != null && account.getHoldAmount().signum() > 0) {
            throw new BusinessException("CBS-ACCT-010",
                    "Account has active hold/lien. Release all holds before closure.");
        }

        if (account.getLedgerBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("CBS-ACCT-007",
                    "Account balance must be zero to close. Current: " + account.getLedgerBalance());
        }

        account.setAccountStatus(DepositAccountStatus.CLOSED);
        account.setClosedDate(businessDateService.getCurrentBusinessDate());
        account.setClosureReason(reason);
        DepositAccount saved = accountRepository.save(account);

        auditService.logEventInline("DepositAccount", saved.getId(), "CLOSE",
                null, saved, "ACCOUNT", "Account closed: " + reason);

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepositAccount> getPendingAccounts() {
        String tenantId = TenantContext.getCurrentTenant();
        return accountRepository.findByTenantIdAndAccountStatus(
                tenantId, DepositAccountStatus.PENDING_ACTIVATION);
    }

    // === Financial Operations ===

    @Override
    @Transactional
    public DepositTransaction deposit(String accountNumber, FinancialRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // Idempotency check
        if (request.idempotencyKey() != null) {
            DepositTransaction dup = transactionRepository
                    .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                    .orElse(null);
            if (dup != null) {
                return dup;
            }
        }

        DepositAccount acct = lockAccount(accountNumber);
        branchAccessValidator.validateAccess(acct.getBranch());
        accountValidator.validateAccountForTransaction(acct);

        String gl = glForAccount(acct);

        // CBS: Route through TransactionEngine -- the SINGLE enforcement point
        accountingService.generateEngineToken();
        try {
            TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("DEPOSIT")
                    .transactionType("CASH_DEPOSIT")
                    .accountReference(accountNumber)
                    .amount(request.amount())
                    .valueDate(businessDate)
                    .branchCode(acct.getBranch().getBranchCode())
                    .narration(request.narration() != null ? request.narration() : "Cash deposit")
                    .idempotencyKey(request.idempotencyKey())
                    .journalLines(List.of(
                            new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                                    DebitCredit.DEBIT, request.amount(), "Cash deposit"),
                            new JournalLineRequest(gl,
                                    DebitCredit.CREDIT, request.amount(), "Credit " + accountNumber)))
                    .build());

            // Update subledger
            BigDecimal balanceBefore = acct.getLedgerBalance();
            acct.setLedgerBalance(balanceBefore.add(request.amount()));
            acct.setAvailableBalance(acct.getAvailableBalance().add(request.amount()));
            acct.setLastTransactionDate(businessDate);
            accountRepository.save(acct);

            // Record module-specific transaction
            return buildAndSaveTxn(acct, r, request.amount(), DebitCredit.CREDIT,
                    "CASH_DEPOSIT", request.narration(), request.channel(),
                    balanceBefore, acct.getLedgerBalance(),
                    businessDate, request.idempotencyKey(), tenantId);
        } finally {
            accountingService.clearEngineToken();
        }
    }

    @Override
    @Transactional
    public DepositTransaction withdraw(String accountNumber, FinancialRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        if (request.idempotencyKey() != null) {
            DepositTransaction dup = transactionRepository
                    .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                    .orElse(null);
            if (dup != null) {
                return dup;
            }
        }

        DepositAccount acct = lockAccount(accountNumber);
        branchAccessValidator.validateAccess(acct.getBranch());
        accountValidator.validateAccountForTransaction(acct);
        accountValidator.validateSufficientBalance(acct, request.amount());

        String gl = glForAccount(acct);

        accountingService.generateEngineToken();
        try {
            TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("DEPOSIT")
                    .transactionType("CASH_WITHDRAWAL")
                    .accountReference(accountNumber)
                    .amount(request.amount())
                    .valueDate(businessDate)
                    .branchCode(acct.getBranch().getBranchCode())
                    .narration(request.narration() != null ? request.narration() : "Cash withdrawal")
                    .idempotencyKey(request.idempotencyKey())
                    .journalLines(List.of(
                            new JournalLineRequest(gl,
                                    DebitCredit.DEBIT, request.amount(), "Debit " + accountNumber),
                            new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                                    DebitCredit.CREDIT, request.amount(), "Cash withdrawal")))
                    .build());

            BigDecimal balanceBefore = acct.getLedgerBalance();
            acct.setLedgerBalance(balanceBefore.subtract(request.amount()));
            acct.setAvailableBalance(acct.getAvailableBalance().subtract(request.amount()));
            acct.setLastTransactionDate(businessDate);
            accountRepository.save(acct);

            return buildAndSaveTxn(acct, r, request.amount(), DebitCredit.DEBIT,
                    "CASH_WITHDRAWAL", request.narration(), request.channel(),
                    balanceBefore, acct.getLedgerBalance(),
                    businessDate, request.idempotencyKey(), tenantId);
        } finally {
            accountingService.clearEngineToken();
        }
    }

    @Override
    @Transactional
    public DepositTransaction transfer(TransferRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // Idempotency check (same pattern as deposit/withdraw)
        if (request.idempotencyKey() != null) {
            DepositTransaction dup = transactionRepository
                    .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                    .orElse(null);
            if (dup != null) {
                return dup;
            }
        }

        accountValidator.validateTransfer(request);

        // Deadlock prevention: lock accounts in alphabetical order
        String first = request.fromAccount().compareTo(request.toAccount()) < 0
                ? request.fromAccount() : request.toAccount();
        String second = first.equals(request.fromAccount())
                ? request.toAccount() : request.fromAccount();

        DepositAccount firstAcct = lockAccount(first);
        DepositAccount secondAcct = lockAccount(second);

        DepositAccount src = first.equals(request.fromAccount()) ? firstAcct : secondAcct;
        DepositAccount dst = first.equals(request.fromAccount()) ? secondAcct : firstAcct;

        branchAccessValidator.validateAccess(src.getBranch());
        accountValidator.validateAccountForTransaction(src);
        accountValidator.validateAccountForTransaction(dst);
        accountValidator.validateSufficientBalance(src, request.amount());

        String srcGl = glForAccount(src);
        String dstGl = glForAccount(dst);

        accountingService.generateEngineToken();
        try {
            TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("DEPOSIT")
                    .transactionType("TRANSFER_DEBIT")
                    .accountReference(request.fromAccount())
                    .amount(request.amount())
                    .valueDate(businessDate)
                    .branchCode(src.getBranch().getBranchCode())
                    .narration(request.narration() != null ? request.narration() : "Transfer to " + request.toAccount())
                    .idempotencyKey(request.idempotencyKey())
                    .journalLines(List.of(
                            new JournalLineRequest(srcGl,
                                    DebitCredit.DEBIT, request.amount(), "Transfer debit " + request.fromAccount()),
                            new JournalLineRequest(dstGl,
                                    DebitCredit.CREDIT, request.amount(), "Transfer credit " + request.toAccount())))
                    .build());

            // Debit source
            BigDecimal srcBalBefore = src.getLedgerBalance();
            src.setLedgerBalance(srcBalBefore.subtract(request.amount()));
            src.setAvailableBalance(src.getAvailableBalance().subtract(request.amount()));
            src.setLastTransactionDate(businessDate);
            accountRepository.save(src);

            // Credit destination
            BigDecimal dstBalBefore = dst.getLedgerBalance();
            dst.setLedgerBalance(dstBalBefore.add(request.amount()));
            dst.setAvailableBalance(dst.getAvailableBalance().add(request.amount()));
            dst.setLastTransactionDate(businessDate);
            accountRepository.save(dst);

            // Record TRANSFER_DEBIT for source account
            DepositTransaction debitTxn = buildAndSaveTxn(src, r, request.amount(), DebitCredit.DEBIT,
                    "TRANSFER_DEBIT", "Transfer to " + request.toAccount(), "API",
                    srcBalBefore, src.getLedgerBalance(),
                    businessDate, request.idempotencyKey(), tenantId);

            // Record TRANSFER_CREDIT for destination account (separate txn ref for unique constraint)
            String creditTxnRef = ReferenceGenerator.generateTransactionRef();
            DepositTransaction creditTxn = new DepositTransaction();
            creditTxn.setDepositAccount(dst);
            creditTxn.setBranch(dst.getBranch());
            creditTxn.setBranchCode(dst.getBranch().getBranchCode());
            creditTxn.setTransactionRef(creditTxnRef);
            creditTxn.setTransactionType("TRANSFER_CREDIT");
            creditTxn.setDebitCredit(DebitCredit.CREDIT.name());
            creditTxn.setAmount(request.amount());
            creditTxn.setBalanceBefore(dstBalBefore);
            creditTxn.setBalanceAfter(dst.getLedgerBalance());
            creditTxn.setValueDate(businessDate);
            creditTxn.setPostingDate(LocalDateTime.now());
            creditTxn.setNarration("Transfer from " + request.fromAccount());
            creditTxn.setChannel("API");
            creditTxn.setVoucherNumber(r.getVoucherNumber());
            creditTxn.setJournalEntryId(r.getJournalEntryId());
            creditTxn.setTenantId(tenantId);
            transactionRepository.save(creditTxn);

            return debitTxn;
        } finally {
            accountingService.clearEngineToken();
        }
    }

    @Override
    @Transactional
    public DepositTransaction reverseTransaction(String transactionRef, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        DepositTransaction original = transactionRepository
                .findByTenantIdAndTransactionRef(tenantId, transactionRef)
                .orElseThrow(() -> new BusinessException("CBS-TXN-001",
                        "Transaction not found: " + transactionRef));

        if (original.isReversed()) {
            throw new BusinessException("CBS-TXN-001",
                    "Transaction " + transactionRef + " is already reversed");
        }

        DepositAccount acct = lockAccount(original.getDepositAccount().getAccountNumber());

        accountingService.generateEngineToken();
        try {
            TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("DEPOSIT")
                    .transactionType("REVERSAL")
                    .accountReference(acct.getAccountNumber())
                    .amount(original.getAmount())
                    .valueDate(businessDate)
                    .branchCode(acct.getBranch().getBranchCode())
                    .narration("REVERSAL: " + reason + " [Orig: " + transactionRef + "]")
                    .journalLines(buildReversalJournalLines(original, acct))
                    .build());

            // Reverse the balance effect
            BigDecimal balanceBefore = acct.getLedgerBalance();
            BigDecimal reverseAmount = "DEBIT".equals(original.getDebitCredit())
                    ? original.getAmount() : original.getAmount().negate();
            acct.setLedgerBalance(balanceBefore.add(reverseAmount));
            acct.setAvailableBalance(acct.getAvailableBalance().add(reverseAmount));
            acct.setLastTransactionDate(businessDate);
            accountRepository.save(acct);

            // Mark original as reversed
            original.setReversed(true);
            original.setReversedByRef(r.getTransactionRef());
            transactionRepository.save(original);

            DepositTransaction txn = new DepositTransaction();
            txn.setDepositAccount(acct);
            txn.setBranch(acct.getBranch());
            txn.setBranchCode(acct.getBranch().getBranchCode());
            txn.setTransactionRef(r.getTransactionRef());
            txn.setTransactionType("REVERSAL");
            txn.setDebitCredit("DEBIT".equals(original.getDebitCredit()) ? "CREDIT" : "DEBIT");
            txn.setAmount(original.getAmount());
            txn.setBalanceBefore(balanceBefore);
            txn.setBalanceAfter(acct.getLedgerBalance());
            txn.setValueDate(businessDate);
            txn.setPostingDate(LocalDateTime.now());
            txn.setNarration("REVERSAL: " + reason);
            txn.setChannel("API");
            txn.setVoucherNumber(r.getVoucherNumber());
            txn.setJournalEntryId(r.getJournalEntryId());
            txn.setTenantId(tenantId);

            return transactionRepository.save(txn);
        } finally {
            accountingService.clearEngineToken();
        }
    }

    // === Inquiry ===

    @Override
    @Transactional(readOnly = true)
    public DepositAccount getAccount(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        DepositAccount account = accountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException("CBS-ACCT-001",
                        "Account not found: " + accountNumber));
        branchAccessValidator.validateAccess(account.getBranch());
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepositTransaction> getMiniStatement(String accountNumber, int count) {
        DepositAccount account = getAccount(accountNumber); // validates access
        String tenantId = TenantContext.getCurrentTenant();
        return transactionRepository.findRecentTransactions(
                tenantId, account.getId(), PageRequest.of(0, count));
    }

    // === Private Helpers ===

    private DepositAccount lockAccount(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        return accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException("CBS-ACCT-001",
                        "Account not found: " + accountNumber));
    }

    /**
     * Resolves the GL code for the deposit account.
     * Uses ProductGLResolver for product-configured GL, falls back to type-based GL.
     */
    private String glForAccount(DepositAccount a) {
        ProductMaster product = glResolver.getProduct(a.getProductCode());
        if (product != null && product.getGlLoanAsset() != null) {
            return product.getGlLoanAsset();
        }
        return a.getAccountType().name().startsWith("CURRENT")
                ? GLConstants.CA_DEPOSITS : GLConstants.SB_DEPOSITS;
    }

    /**
     * Builds reversal GL journal lines by swapping DR/CR based on original transaction direction.
     * Per CBS double-entry reversal: a deposit reversal (original CREDIT) posts DR account_GL / CR BANK_OPS;
     * a withdrawal reversal (original DEBIT) posts DR BANK_OPS / CR account_GL.
     */
    private List<JournalLineRequest> buildReversalJournalLines(DepositTransaction original, DepositAccount acct) {
        String gl = glForAccount(acct);
        boolean wasDebit = "DEBIT".equals(original.getDebitCredit());
        if (wasDebit) {
            return List.of(
                    new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                            DebitCredit.DEBIT, original.getAmount(), "Reversal"),
                    new JournalLineRequest(gl,
                            DebitCredit.CREDIT, original.getAmount(), "Reversal"));
        } else {
            return List.of(
                    new JournalLineRequest(gl,
                            DebitCredit.DEBIT, original.getAmount(), "Reversal"),
                    new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                            DebitCredit.CREDIT, original.getAmount(), "Reversal"));
        }
    }

    private DepositTransaction buildAndSaveTxn(
            DepositAccount acct, TransactionResult r,
            BigDecimal amount, DebitCredit debitCredit,
            String transactionType, String narration, String channel,
            BigDecimal balanceBefore, BigDecimal balanceAfter,
            LocalDate businessDate, String idempotencyKey, String tenantId) {
        DepositTransaction txn = new DepositTransaction();
        txn.setDepositAccount(acct);
        txn.setBranch(acct.getBranch());
        txn.setBranchCode(acct.getBranch().getBranchCode());
        txn.setTransactionRef(r.getTransactionRef());
        txn.setTransactionType(transactionType);
        txn.setDebitCredit(debitCredit.name());
        txn.setAmount(amount);
        txn.setBalanceBefore(balanceBefore);
        txn.setBalanceAfter(balanceAfter);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setNarration(narration);
        txn.setChannel(channel != null ? channel : "API");
        txn.setVoucherNumber(r.getVoucherNumber());
        txn.setJournalEntryId(r.getJournalEntryId());
        txn.setIdempotencyKey(idempotencyKey);
        txn.setTenantId(tenantId);
        return transactionRepository.save(txn);
    }
}

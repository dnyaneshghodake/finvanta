package com.finvanta.cbs.modules.account.service;

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
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
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
    private final ProductMasterRepository productMasterRepository;
    private final TransactionEngine transactionEngine;
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
            ProductMasterRepository productMasterRepository,
            TransactionEngine transactionEngine,
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
        this.productMasterRepository = productMasterRepository;
        this.transactionEngine = transactionEngine;
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

        DepositAccountType parsedAccountType;
        try {
            parsedAccountType = DepositAccountType.valueOf(request.accountType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("CBS-ACCT-002",
                    "Invalid account type: " + request.accountType()
                            + ". Valid types: " + java.util.Arrays.toString(DepositAccountType.values()));
        }

        // CBS Duplicate Guard per CBS ACCTOPN: one active account per CIF per type per branch.
        // Mirrors the legacy DepositAccountServiceImpl check (lines 489-499). Closed accounts
        // do NOT count as duplicates so a customer who closed and reopens the same product is
        // permitted. This must run BEFORE sequence allocation to avoid wasting account numbers.
        long existingActiveCount = accountRepository
                .findByTenantIdAndCustomerId(tenantId, request.customerId()).stream()
                .filter(existing -> existing.getAccountType() == parsedAccountType
                        && existing.getBranch().getId().equals(request.branchId())
                        && !existing.isClosed())
                .count();
        if (existingActiveCount > 0) {
            throw new BusinessException("CBS-ACCT-008",
                    "Customer " + customer.getCustomerNumber() + " already has an active "
                            + parsedAccountType + " account at branch " + branch.getBranchCode()
                            + ". Per CBS ACCTOPN: one account per CIF per type per branch.");
        }

        // CBS Product-driven defaults per CBS PDDEF: interest rate and minimum balance are
        // resolved from ProductMaster, not hardcoded. Mirrors legacy lines 504-530.
        BigDecimal interestRate = parsedAccountType.isInterestBearing()
                ? new BigDecimal("4.0000") : BigDecimal.ZERO;
        BigDecimal minimumBalance = BigDecimal.ZERO;
        var productOpt = productMasterRepository
                .findByTenantIdAndProductCode(tenantId, request.productCode());
        if (productOpt.isPresent()) {
            var product = productOpt.get();
            if (product.getMinInterestRate() != null) {
                interestRate = product.getMinInterestRate();
            }
            if (product.getMinLoanAmount() != null) {
                // CBS: minLoanAmount is repurposed as minimum balance for CASA products.
                minimumBalance = product.getMinLoanAmount();
            }
        } else {
            log.warn("CASA product not found: {}, using defaults (rate={}, minBal={})",
                    request.productCode(), interestRate, minimumBalance);
        }

        // CBS sequence allocation is the LAST step before entity construction. All
        // throwable validations above complete first so we don't waste sequence numbers
        // on rejected requests.
        String accNo = cbsReferenceService.generateDepositAccountNumber(
                branch.getBranchCode(), parsedAccountType.isSavings());
        String currentUser = SecurityUtil.getCurrentUsername();

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
        account.setLastTransactionDate(businessDate);
        account.setLedgerBalance(BigDecimal.ZERO);
        account.setAvailableBalance(BigDecimal.ZERO);
        account.setHoldAmount(BigDecimal.ZERO);
        account.setUnclearedAmount(BigDecimal.ZERO);
        account.setOdLimit(BigDecimal.ZERO);
        account.setMinimumBalance(minimumBalance);
        account.setInterestRate(interestRate);
        account.setAccruedInterest(BigDecimal.ZERO);
        account.setYtdInterestCredited(BigDecimal.ZERO);
        account.setYtdTdsDeducted(BigDecimal.ZERO);
        // CBS: Default fullName from CIF so the account-level passbook always has a name
        // even when the request omits one. Matches legacy behavior at lines 581-582.
        account.setFullName(customer.getFullName());

        // Nominee details per RBI Nomination Guidelines (DeposAcct Rules 1985 Section 45ZA).
        // Concatenate first + last to match the entity's single nominee_name column.
        String nomineeFirst = request.nomineeFirstName();
        String nomineeLast = request.nomineeLastName();
        if ((nomineeFirst != null && !nomineeFirst.isBlank())
                || (nomineeLast != null && !nomineeLast.isBlank())) {
            StringBuilder nomineeName = new StringBuilder();
            if (nomineeFirst != null && !nomineeFirst.isBlank()) {
                nomineeName.append(nomineeFirst.trim());
            }
            if (nomineeLast != null && !nomineeLast.isBlank()) {
                if (nomineeName.length() > 0) nomineeName.append(' ');
                nomineeName.append(nomineeLast.trim());
            }
            account.setNomineeName(nomineeName.toString());
        }
        if (request.nomineeRelationship() != null && !request.nomineeRelationship().isBlank()) {
            account.setNomineeRelationship(request.nomineeRelationship());
        }

        // CBS Joint Holder Mandate per RBI Joint Account Guidelines:
        // operatingInstructions on the request maps to the entity's jointHolderMode column,
        // which encodes the operational mandate (EITHER_SURVIVOR, FORMER_SURVIVOR, JOINTLY).
        // The withdrawal/transfer paths consult this field to enforce multi-signatory rules.
        if (request.operatingInstructions() != null && !request.operatingInstructions().isBlank()) {
            account.setJointHolderMode(request.operatingInstructions().trim());
        }

        // CBS Audit Fields per RBI IT Governance Direction 2023 §8.3:
        // every state-changing record must carry createdBy and updatedBy.
        account.setCreatedBy(currentUser);
        account.setUpdatedBy(currentUser);

        // NOTE: request.jointHolderCif(), request.initialDeposit(), and request.idempotencyKey()
        // are accepted by the DTO but intentionally not persisted on the DepositAccount entity:
        //   - jointHolderCif -> requires the Customer<->Customer joint-holder link table
        //     (not yet implemented in the refactored module). The duplicate-CIF check above
        //     guards against the same primary CIF opening duplicate accounts.
        //   - initialDeposit -> per CBS ACCTOPN, account opening and initial funding are TWO
        //     separate transactions. Account opens in PENDING_ACTIVATION with zero balance;
        //     the initial deposit is booked as a separate deposit() call post-activation.
        //     Matches legacy v1 behavior at DepositAccountServiceImpl.java:650-657.
        //   - idempotencyKey -> openAccount is not currently idempotent at the API level;
        //     the duplicate guard above (CIF + type + branch + active) is the enforcement
        //     mechanism. A caller retrying with the same key gets the duplicate exception.

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
        // Normalize and whitelist-validate the freeze type BEFORE acquiring the
        // row lock so we fail fast on bad input without holding a write lock.
        // Per CBS ACCTFRZ: only DEBIT_FREEZE / CREDIT_FREEZE / TOTAL_FREEZE are
        // honored by DepositAccount.isDebitAllowed/isCreditAllowed. Any other
        // value silently degrades to a deny-all freeze, masking operator error.
        String normalizedFreezeType = freezeType != null
                ? freezeType.trim().toUpperCase() : null;
        accountValidator.validateFreezeType(normalizedFreezeType);

        DepositAccount account = lockAccount(accountNumber);

        // CBS ACCTFRZ: a freeze is permitted on any non-terminal status. Mirrors
        // the legacy DepositAccountServiceImpl.freezeAccount guard
        // (src/main/java/com/finvanta/service/impl/DepositAccountServiceImpl.java:1403)
        // which rejects CLOSED only. Three legitimate flows are blocked if we
        // require ACTIVE here:
        //   1. Freeze escalation (DEBIT_FREEZE -> TOTAL_FREEZE) per PMLA 2002
        //      / court orders -- the account is already FROZEN.
        //   2. Regulatory freeze on a DORMANT account (court attachment, ED order)
        //      -- the account is DORMANT but still subject to legal action.
        //   3. Fraud freeze during activation pipeline -- PENDING_ACTIVATION
        //      accounts must be freezable when fraud is detected mid-onboarding.
        // CLOSED accounts are terminal: no freeze can apply because the account
        // no longer exists from a banking standpoint.
        if (account.isClosed()) {
            throw new BusinessException("CBS-ACCT-004",
                    "Cannot freeze closed account: " + accountNumber);
        }

        DepositAccountStatus prevStatus = account.getAccountStatus();
        String prevFreezeType = account.getFreezeType();
        account.setAccountStatus(DepositAccountStatus.FROZEN);
        account.setFreezeType(normalizedFreezeType);
        account.setFreezeReason(reason);
        DepositAccount saved = accountRepository.save(account);

        // CBS Audit: record FROM-state so RBI inspection can trace the escalation
        // path (e.g. ACTIVE -> DEBIT_FREEZE -> TOTAL_FREEZE).
        auditService.logEventInline("DepositAccount", saved.getId(), "FREEZE",
                prevStatus.name(), "FROZEN", "ACCOUNT",
                "Account " + accountNumber + " frozen: " + normalizedFreezeType
                        + (prevFreezeType != null ? " (was " + prevFreezeType + ")" : "")
                        + " | Reason: " + reason);

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

        // CBS Idempotency: lock account FIRST, then check for duplicate. Concurrent
        // retries with the same idempotency key serialize on the PESSIMISTIC_WRITE
        // row lock; the second retry sees the first retry's committed DepositTransaction
        // (READ_COMMITTED post-commit visibility) and returns it. Checking before the
        // lock would leave a TOCTOU window where both retries pass the dup check, both
        // post to GL via TransactionEngine, and only the subledger UNIQUE constraint
        // (uq_deptxn_idempotency, prod-only filtered index) catches the second insert.
        DepositAccount acct = lockAccount(accountNumber);
        DepositTransaction dup = findExistingByIdempotencyKey(tenantId, request.idempotencyKey());
        if (dup != null) {
            return dup;
        }

        branchAccessValidator.validateAccess(acct.getBranch());
        // Deposit is a CREDIT: honors DEBIT_FREEZE (credits allowed) and
        // DORMANT (credits may reactivate) per RBI Freeze Guidelines.
        accountValidator.validateAccountForCredit(acct);

        String gl = glForAccount(acct);

        // CBS: Route through TransactionEngine -- the SINGLE enforcement point.
        // The engine itself manages the ENGINE_TOKEN ceremony internally
        // (TransactionEngine.execute lines 673/715); calling generate/clear from
        // a service violates the ArchUnit engineTokenHelpers_notUsedOutsideEngine
        // guard and reaches into the engine's private posting plumbing.
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

        // Update subledger. Per CBS BAL_DERIVE: ledger is the only field that moves
        // by the txn amount; available is recomputed from (ledger - hold - uncleared)
        // so active liens/uncleared cheques are honored. See recomputeAvailable() below.
        BigDecimal balanceBefore = acct.getLedgerBalance();
        acct.setLedgerBalance(balanceBefore.add(request.amount()));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);

        // CBS Dormancy Reactivation per RBI KYC §38: a customer-initiated credit
        // on a DORMANT account transitions it back to ACTIVE. Must happen BEFORE
        // save() so the state change persists in the same row update as the balance.
        reactivateIfDormant(acct, "deposit of INR " + request.amount());

        accountRepository.save(acct);

        // Record module-specific transaction
        return buildAndSaveTxn(acct, r, request.amount(), DebitCredit.CREDIT,
                "CASH_DEPOSIT", request.narration(), request.channel(),
                balanceBefore, acct.getLedgerBalance(),
                businessDate, request.idempotencyKey(), tenantId);
    }

    @Override
    @Transactional
    public DepositTransaction withdraw(String accountNumber, FinancialRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // CBS Idempotency: see deposit() for rationale. Lock first, then dup-check.
        DepositAccount acct = lockAccount(accountNumber);
        DepositTransaction dup = findExistingByIdempotencyKey(tenantId, request.idempotencyKey());
        if (dup != null) {
            return dup;
        }

        branchAccessValidator.validateAccess(acct.getBranch());
        // Withdrawal is a DEBIT: blocked on DEBIT_FREEZE, TOTAL_FREEZE, DORMANT, CLOSED.
        accountValidator.validateAccountForDebit(acct);
        accountValidator.validateSufficientBalance(acct, request.amount());

        String gl = glForAccount(acct);

        // Engine manages its own ENGINE_TOKEN -- see deposit() for rationale.
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
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);
        accountRepository.save(acct);

        return buildAndSaveTxn(acct, r, request.amount(), DebitCredit.DEBIT,
                "CASH_WITHDRAWAL", request.narration(), request.channel(),
                balanceBefore, acct.getLedgerBalance(),
                businessDate, request.idempotencyKey(), tenantId);
    }

    @Override
    @Transactional
    public DepositTransaction transfer(TransferRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

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

        // CBS Idempotency: see deposit() for rationale. Both account locks held;
        // concurrent retries serialize on either lock and observe the prior commit.
        DepositTransaction dup = findExistingByIdempotencyKey(tenantId, request.idempotencyKey());
        if (dup != null) {
            return dup;
        }

        branchAccessValidator.validateAccess(src.getBranch());
        // Transfer is a DEBIT on src and a CREDIT on dst. Validating each leg with
        // the direction-appropriate check preserves RBI/PMLA partial freeze semantics:
        // a DEBIT_FROZEN destination can still receive the transfer-in credit, and
        // a CREDIT_FROZEN source can still be debited.
        accountValidator.validateAccountForDebit(src);
        accountValidator.validateAccountForCredit(dst);
        accountValidator.validateSufficientBalance(src, request.amount());

        String srcGl = glForAccount(src);
        String dstGl = glForAccount(dst);

        // Engine manages its own ENGINE_TOKEN -- see deposit() for rationale.
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
        recomputeAvailable(src);
        src.setLastTransactionDate(businessDate);
        accountRepository.save(src);

        // Credit destination
        BigDecimal dstBalBefore = dst.getLedgerBalance();
        dst.setLedgerBalance(dstBalBefore.add(request.amount()));
        recomputeAvailable(dst);
        dst.setLastTransactionDate(businessDate);
        // CBS Dormancy Reactivation per RBI KYC §38: a transfer credit on a
        // DORMANT destination transitions it back to ACTIVE. Matches the legacy
        // DepositAccountServiceImpl.transfer() behavior (lines 1040-1055).
        reactivateIfDormant(dst, "transfer from " + request.fromAccount());
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
        creditTxn.setIdempotencyKey(null);
        creditTxn.setTenantId(tenantId);
        transactionRepository.save(creditTxn);

        return debitTxn;
    }

    @Override
    @Transactional
    public DepositTransaction reverseTransaction(String transactionRef, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // CBS TOCTOU safety: acquire PESSIMISTIC_WRITE on the transaction row FIRST,
        // then re-check isReversed inside the lock. Two concurrent reversals of the
        // same transactionRef will serialize here; the second will see isReversed=true
        // and be rejected -- preventing double reversal and GL/subledger corruption.
        DepositTransaction original = transactionRepository
                .findAndLockByTenantIdAndTransactionRef(tenantId, transactionRef)
                .orElseThrow(() -> new BusinessException("CBS-TXN-001",
                        "Transaction not found: " + transactionRef));

        if (original.isReversed()) {
            throw new BusinessException("CBS-TXN-001",
                    "Transaction " + transactionRef + " is already reversed");
        }

        // Transfer reversals require atomic reversal of BOTH legs (debit + credit).
        // The legacy DepositAccountServiceImpl has dedicated reverseTransfer() logic
        // for this; reversing only one leg here would corrupt the counterparty account.
        // Until that flow is ported, reject transfer reversals explicitly rather than
        // silently performing an unbalanced single-leg reversal.
        String txnType = original.getTransactionType();
        if ("TRANSFER_DEBIT".equals(txnType) || "TRANSFER_CREDIT".equals(txnType)) {
            throw new BusinessException("CBS-TXN-012",
                    "Transfer reversals must be executed via the transfer reversal flow "
                            + "(both legs reversed atomically). Original txn: " + transactionRef);
        }

        DepositAccount acct = lockAccount(original.getDepositAccount().getAccountNumber());

        // Engine manages its own ENGINE_TOKEN -- see deposit() for rationale.
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

        // Reverse the balance effect. Per CBS BAL_DERIVE: only ledger moves directly;
        // available is recomputed from (ledger - hold - uncleared).
        BigDecimal balanceBefore = acct.getLedgerBalance();
        BigDecimal reverseAmount = "DEBIT".equals(original.getDebitCredit())
                ? original.getAmount() : original.getAmount().negate();
        acct.setLedgerBalance(balanceBefore.add(reverseAmount));
        recomputeAvailable(acct);
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
     * CBS Dormancy Reactivation on Credit per RBI Master Direction on KYC 2016 §38.
     *
     * <p>Any customer-initiated credit (deposit, transfer-in) on a DORMANT account
     * MUST transition the account back to ACTIVE. Without reactivation the account
     * receives the funds but stays DORMANT, and subsequent debits are blocked by
     * {@link DepositAccount#isDebitAllowed()} -- leaving the customer with
     * inaccessible money and a passbook that lies about the real account state.
     *
     * <p>Mirrors the legacy behavior at
     * {@code src/main/java/com/finvanta/service/impl/DepositAccountServiceImpl.java:723-741}
     * (deposit path) and {@code :1040-1055} (transfer destination path).
     *
     * <p>Audit: uses {@link AuditService#logEventInline} (REQUIRED propagation)
     * because the caller holds a PESSIMISTIC_WRITE lock on the account row.
     * Using {@code logEvent} (REQUIRES_NEW) would suspend the outer TX without
     * releasing the row lock -- a deadlock vector on SQL Server.
     *
     * @param acct       the credited account (lock already held by caller)
     * @param reasonTxt  short narration for the audit trail (e.g. "deposit", "transfer from X")
     */
    private void reactivateIfDormant(DepositAccount acct, String reasonTxt) {
        if (!acct.isDormant()) {
            return;
        }
        acct.setAccountStatus(DepositAccountStatus.ACTIVE);
        acct.setDormantDate(null);
        auditService.logEventInline(
                "DepositAccount", acct.getId(), "DORMANCY_REACTIVATED",
                "DORMANT", "ACTIVE", "ACCOUNT",
                "Account " + acct.getAccountNumber() + " reactivated via " + reasonTxt);
    }

    /**
     * Recomputes the account's available balance from its source fields.
     *
     * <p>Per CBS BAL_DERIVE: {@code availableBalance = ledgerBalance - holdAmount
     * - unclearedAmount}. This is the SINGLE source of truth for the derivation
     * formula, mirroring the legacy {@code DepositAccountServiceImpl.recomputeAvailable}
     * (src/main/java/com/finvanta/service/impl/DepositAccountServiceImpl.java:153-156).
     *
     * <p>Why recompute rather than increment: incremental updates
     * ({@code available += amount}) drift from {@code ledger - hold - uncleared}
     * whenever holdAmount or unclearedAmount are non-zero (active liens, court
     * attachments, uncleared cheques). Recomputing on every mutation keeps the
     * invariant intact regardless of concurrent hold/clearing operations and
     * guarantees the available figure shown on the passbook always equals
     * {@code ledger - hold - uncleared}.
     *
     * <p>Caller MUST set {@code ledgerBalance}, {@code holdAmount}, and
     * {@code unclearedAmount} BEFORE invoking this helper. Available is purely
     * derived state -- it is never the input to any other balance calculation.
     */
    private void recomputeAvailable(DepositAccount acct) {
        acct.setAvailableBalance(
                acct.getLedgerBalance()
                        .subtract(acct.getHoldAmount())
                        .subtract(acct.getUnclearedAmount()));
    }

    /**
     * Locates a previously-committed {@code DepositTransaction} by idempotency key.
     *
     * <p>MUST be called only after the relevant account row(s) are locked via
     * {@link #lockAccount}. The pessimistic lock is what makes this race-free:
     * a concurrent retry that arrived first will have committed (and released
     * the lock) by the time this thread acquires it, so the prior row is
     * visible under READ_COMMITTED. Calling this helper before locking
     * re-introduces the TOCTOU window this method is designed to close.
     *
     * <p>Returns {@code null} for null/blank keys so callers can pass through
     * the request key without a guard.
     */
    private DepositTransaction findExistingByIdempotencyKey(String tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return transactionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .orElse(null);
    }

    /**
     * Resolves the GL code for the deposit account.
     * Uses ProductGLResolver for product-configured GL, falls back to type-based GL.
     */
    private String glForAccount(DepositAccount a) {
        ProductMaster product = glResolver.getProduct(a.getProductCode());
        if (product != null
                && product.getProductCategory() != null
                && product.getProductCategory().isDeposit()
                && product.getGlLoanAsset() != null) {
            return product.getGlLoanAsset();
        }
        return a.isSavings()
                ? GLConstants.SB_DEPOSITS : GLConstants.CA_DEPOSITS;
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

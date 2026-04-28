package com.finvanta.cbs.modules.teller.service;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.cbs.modules.teller.domain.CashDenomination;
import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;
import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.domain.TellerTillStatus;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.repository.CashDenominationRepository;
import com.finvanta.cbs.modules.teller.repository.TellerTillRepository;
import com.finvanta.cbs.modules.teller.validator.DenominationValidator;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Teller Module Service Implementation per CBS TELLER standard.
 *
 * <p>Tier-1 cash-channel orchestration. Implements the contract documented on
 * {@link TellerService}. Key invariants enforced here:
 * <ul>
 *   <li><b>Engine-first GL posting.</b> All cash deposits route through
 *       {@link TransactionEngine#execute} so idempotency, business-date
 *       validation, per-user limits, and the maker-checker gate all live in
 *       the engine -- the teller service never reaches into GL plumbing.</li>
 *   <li><b>Maker-checker safety.</b> When the engine returns
 *       {@link TransactionResult#isPendingApproval()}, the till and customer
 *       balances stay UNCHANGED. Mirrors the pattern this PR established for
 *       {@code DepositAccountModuleServiceImpl} (review thread on lines
 *       480-488). Crediting the till before checker approval is the single
 *       most common cash-module bug -- prevented at source here.</li>
 *   <li><b>Pessimistic locking, fixed order.</b> Customer-account row first,
 *       then till row. Same rule as the transfer deadlock-prevention in
 *       {@code DepositAccountModuleServiceImpl.transfer}: never hold the
 *       cash-side lock waiting on the customer-side lock.</li>
 *   <li><b>Idempotency lock-then-check.</b> Same TOCTOU-safe ordering as the
 *       DepositAccount module: acquire all locks first, then dup-check the
 *       idempotency key. Concurrent retries serialize on the locks and the
 *       second retry observes the first retry's committed transaction.</li>
 *   <li><b>FICN handling.</b> Counterfeit notes detected via
 *       {@link DenominationValidator#hasCounterfeit} reject the deposit before
 *       any GL or till mutation. Per RBI FICN guidelines: a separate FICN
 *       acknowledgement workflow runs; the customer is not credited.</li>
 *   <li><b>CTR / PMLA.</b> Deposits at or above {@value #CTR_PAN_THRESHOLD_RUPEES}
 *       INR require either {@code panNumber} or {@code form60Reference}.
 *       Rejected at the boundary so the AML reporting path is never bypassed.</li>
 * </ul>
 */
@Service
public class TellerServiceImpl implements TellerService {

    private static final Logger log = LoggerFactory.getLogger(TellerServiceImpl.class);

    /**
     * CBS soft threshold for till open per RBI Internal Controls. Opening
     * balance at or below this auto-approves to OPEN; anything higher routes
     * to a supervisor (PENDING_OPEN) for dual-control sign-off. Configurable
     * per branch in a future TellerConfig table; for now this is the default.
     */
    private static final BigDecimal TILL_OPEN_AUTO_APPROVE_THRESHOLD = new BigDecimal("200000");

    /**
     * CBS CTR threshold per PMLA Rule 9 / RBI Operational Risk Guidelines.
     * Cash deposits at or above this rupee value require PAN (or Form 60/61).
     */
    private static final String CTR_PAN_THRESHOLD_RUPEES = "50000";
    private static final BigDecimal CTR_PAN_THRESHOLD = new BigDecimal(CTR_PAN_THRESHOLD_RUPEES);

    private final TellerTillRepository tillRepository;
    private final CashDenominationRepository denominationRepository;
    private final DepositAccountRepository accountRepository;
    private final DepositTransactionRepository transactionRepository;
    private final BranchRepository branchRepository;
    private final ProductGLResolver glResolver;
    private final TransactionEngine transactionEngine;
    private final BusinessDateService businessDateService;
    private final AuditService auditService;
    private final DenominationValidator denominationValidator;
    private final BranchAccessValidator branchAccessValidator;

    public TellerServiceImpl(
            TellerTillRepository tillRepository,
            CashDenominationRepository denominationRepository,
            DepositAccountRepository accountRepository,
            DepositTransactionRepository transactionRepository,
            BranchRepository branchRepository,
            ProductGLResolver glResolver,
            TransactionEngine transactionEngine,
            BusinessDateService businessDateService,
            AuditService auditService,
            DenominationValidator denominationValidator,
            BranchAccessValidator branchAccessValidator) {
        this.tillRepository = tillRepository;
        this.denominationRepository = denominationRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.branchRepository = branchRepository;
        this.glResolver = glResolver;
        this.transactionEngine = transactionEngine;
        this.businessDateService = businessDateService;
        this.auditService = auditService;
        this.denominationValidator = denominationValidator;
        this.branchAccessValidator = branchAccessValidator;
    }

    // =====================================================================
    // Till Lifecycle -- Open
    // =====================================================================

    @Override
    @Transactional
    public TellerTill openTill(OpenTillRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        String tellerUser = SecurityUtil.getCurrentUsername();
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (branchId == null) {
            // Per RBI Internal Controls: a teller without a branch assignment
            // must NOT be able to handle cash. Fail fast with a domain error
            // rather than relying on a downstream NPE.
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Teller has no branch assignment; cannot open till");
        }
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Branch not found for current teller: " + branchId));

        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // CBS duplicate-till guard: one till per teller per business date.
        // The unique index idx_till_teller_date is the DB safety net; this
        // application-level check produces a friendly error before the
        // constraint violation fires.
        tillRepository.findByTellerAndDate(tenantId, tellerUser, businessDate)
                .ifPresent(t -> {
                    throw new BusinessException(CbsErrorCodes.TELLER_TILL_DUPLICATE,
                            "Till already exists for teller " + tellerUser
                                    + " on " + businessDate
                                    + " (status: " + t.getStatus() + ")");
                });

        TellerTill till = new TellerTill();
        till.setTenantId(tenantId);
        till.setTellerUserId(tellerUser);
        till.setBranch(branch);
        till.setBranchCode(branch.getBranchCode());
        till.setBusinessDate(businessDate);
        till.setOpeningBalance(request.openingBalance());
        till.setCurrentBalance(request.openingBalance());
        till.setTillCashLimit(request.tillCashLimit());
        till.setRemarks(request.remarks());
        till.setCreatedBy(tellerUser);
        till.setUpdatedBy(tellerUser);

        // Auto-approve when opening balance is within the soft threshold;
        // otherwise the till sits in PENDING_OPEN awaiting supervisor sign-off.
        if (request.openingBalance().compareTo(TILL_OPEN_AUTO_APPROVE_THRESHOLD) <= 0) {
            till.setStatus(TellerTillStatus.OPEN);
            till.setOpenedAt(LocalDateTime.now());
        } else {
            till.setStatus(TellerTillStatus.PENDING_OPEN);
        }

        TellerTill saved = tillRepository.save(till);

        // Audit uses logEvent (REQUIRES_NEW): no PESSIMISTIC_WRITE lock is held
        // here, so the deadlock hazard documented on AuditService.logEvent does
        // not apply. logEventInline is reserved for the cashDeposit path which
        // does hold a row lock.
        auditService.logEvent(
                "TellerTill", saved.getId(), "OPEN_REQUEST",
                null, saved, "TELLER",
                "Till open request for " + tellerUser + " at " + branch.getBranchCode()
                        + " on " + businessDate + " (status=" + saved.getStatus() + ")");

        log.info("CBS Teller till {} created for {} branch {} (status={})",
                saved.getId(), tellerUser, branch.getBranchCode(), saved.getStatus());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public TellerTill getMyCurrentTill() {
        String tenantId = TenantContext.getCurrentTenant();
        String tellerUser = SecurityUtil.getCurrentUsername();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();
        return tillRepository.findByTellerAndDate(tenantId, tellerUser, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                        "No till open for teller " + tellerUser + " on " + businessDate));
    }

    // =====================================================================
    // Cash Deposit
    // =====================================================================

    /**
     * Posts a customer cash deposit at the teller counter. See
     * {@link TellerService#cashDeposit} for the contract; the implementation
     * comments below explain the lock order, idempotency, and FICN handling.
     */
    @Override
    @Transactional
    public CashDepositResponse cashDeposit(CashDepositRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        String tellerUser = SecurityUtil.getCurrentUsername();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // 1. Pre-lock validation: denomination math + FICN + CTR.
        // Run BEFORE any DB lock so a bad request never holds a write lock.
        denominationValidator.validateSum(request.denominations(), request.amount());
        if (denominationValidator.hasCounterfeit(request.denominations())) {
            // Per RBI FICN: deposit containing counterfeit notes is NOT
            // credited; the genuine portion is held until a separate FICN
            // acknowledgement workflow runs. Dedicated error code so the BFF
            // can surface the FICN handover screen, not a generic toast.
            throw new BusinessException(CbsErrorCodes.TELLER_COUNTERFEIT_DETECTED,
                    "Counterfeit notes detected. Deposit rejected pending FICN review "
                            + "per RBI Master Direction on Counterfeit Notes.");
        }
        validateCtrCompliance(request);

        // 2. Lock customer account FIRST, then till. Lock order matches the
        // transfer deadlock-prevention pattern in DepositAccountModuleServiceImpl
        // -- the cash side never holds its row lock waiting on a customer lock.
        DepositAccount acct = lockAccountForCredit(tenantId, request.accountNumber());
        TellerTill till = lockOpenTill(tenantId, tellerUser, businessDate);

        // 3. Idempotency dedupe AFTER locks are held (TOCTOU-safe ordering).
        DepositTransaction dup = transactionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                .orElse(null);
        if (dup != null) {
            return mapToResponse(dup, till,
                    lookupDenominationsForResponse(tenantId, dup.getTransactionRef()),
                    request.amount().compareTo(CTR_PAN_THRESHOLD) >= 0,
                    /* ficnTriggered */ false);
        }

        // 4. Engine owns idempotency-registry insert, business-date validation,
        // per-user limit + maker-checker gate, and double-entry GL posting.
        // Both the API and teller channels MUST go through the same engine so
        // GL semantics are identical regardless of how the deposit arrived.
        TransactionResult r = postCashDepositGl(acct, till, request, businessDate);
        boolean ctrTriggered = request.amount().compareTo(CTR_PAN_THRESHOLD) >= 0;

        // 5. Maker-checker pending: persist a PENDING DepositTransaction with
        // balances UNCHANGED. Till NOT mutated, NO CashDenomination rows.
        // The workflow re-execution path mutates state when the checker
        // approves. Mirrors the pattern in DepositAccountModuleServiceImpl.
        if (r.isPendingApproval()) {
            String pendingNarration = resolveNarration(request) + " [PENDING APPROVAL]";
            DepositTransaction pendingTxn = persistTxn(
                    acct, till, r, request,
                    acct.getLedgerBalance(), acct.getLedgerBalance(),
                    pendingNarration, tenantId);
            auditService.logEventInline(
                    "TellerCashDeposit", pendingTxn.getId(), "PENDING_APPROVAL",
                    null, pendingTxn, "TELLER",
                    "Cash deposit pending checker approval: INR " + request.amount()
                            + " account=" + request.accountNumber()
                            + " till=" + till.getId());
            return mapToResponse(pendingTxn, till, List.of(), ctrTriggered, false);
        }

        // 6. Posted: mutate customer ledger + till + persist denominations.
        BigDecimal balanceBefore = acct.getLedgerBalance();
        applyDepositToAccount(acct, request.amount(), businessDate);
        applyDepositToTill(till, request.amount(), tellerUser);

        DepositTransaction txn = persistTxn(
                acct, till, r, request, balanceBefore, acct.getLedgerBalance(),
                resolveNarration(request), tenantId);
        persistDenominations(till, request, txn.getTransactionRef(), businessDate, tenantId);

        // Audit must be logEventInline (REQUIRED): caller holds PESSIMISTIC_WRITE
        // on both the till and account rows. logEvent (REQUIRES_NEW) would
        // suspend without releasing locks and deadlock on SQL Server -- see
        // AuditService.logEvent Javadoc for the deadlock hazard.
        auditService.logEventInline(
                "TellerCashDeposit", txn.getId(), "POSTED",
                null, txn, "TELLER",
                "Cash deposit POSTED: INR " + request.amount()
                        + " account=" + request.accountNumber()
                        + " till=" + till.getId()
                        + " ctr=" + ctrTriggered);

        log.info("CBS Teller deposit POSTED txnRef={} account={} amount={} till={} ctr={}",
                txn.getTransactionRef(), request.accountNumber(), request.amount(),
                till.getId(), ctrTriggered);

        return mapToResponse(txn, till,
                buildResponseDenominationLines(request), ctrTriggered, false);
    }

    // =====================================================================
    // Private helpers
    // =====================================================================

    /**
     * Resolves the customer-deposit GL code for the given account. Mirrors
     * {@code DepositAccountModuleServiceImpl.glForAccount} so the teller
     * channel and the API channel post to identical GL codes for the same
     * account. Falls back to type-based defaults when ProductMaster has no
     * GL configured.
     */
    private String customerDepositGl(DepositAccount a) {
        ProductMaster product = glResolver.getProduct(a.getProductCode());
        if (product != null && product.getGlLoanAsset() != null) {
            return product.getGlLoanAsset();
        }
        return a.isSavings() ? GLConstants.SB_DEPOSITS : GLConstants.CA_DEPOSITS;
    }

    /**
     * CBS PMLA / CTR boundary check per RBI Operational Risk Guidelines and
     * PMLA Rule 9. Cash deposits at or above {@link #CTR_PAN_THRESHOLD} INR
     * require either a PAN or a Form 60/61 serial number. Rejected here
     * before any GL or till mutation so the AML reporting path is never
     * silently bypassed by a missing identifier.
     */
    private void validateCtrCompliance(CashDepositRequest req) {
        if (req.amount().compareTo(CTR_PAN_THRESHOLD) < 0) {
            return;
        }
        boolean hasPan = req.panNumber() != null && !req.panNumber().isBlank();
        boolean hasForm60 = req.form60Reference() != null && !req.form60Reference().isBlank();
        if (!hasPan && !hasForm60) {
            throw new BusinessException(CbsErrorCodes.COMP_CTR_THRESHOLD,
                    "Cash deposit of INR " + req.amount() + " requires PAN or Form 60/61 "
                            + "per PMLA Rule 9 (threshold: INR " + CTR_PAN_THRESHOLD + ")");
        }
    }

    /**
     * Acquires PESSIMISTIC_WRITE on the customer account and validates it
     * accepts credits. Per RBI Freeze Guidelines: CREDIT_FREEZE and TOTAL_FREEZE
     * block credits, DEBIT_FREEZE permits them, DORMANT permits them (the
     * deposit reactivates a dormant account elsewhere in this method).
     */
    private DepositAccount lockAccountForCredit(String tenantId, String accountNumber) {
        DepositAccount acct = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.ACCT_NOT_FOUND,
                        "Account not found: " + accountNumber));
        // Branch isolation: a teller may only credit accounts at their own
        // branch. ADMIN/AUDITOR are exempt via BranchAccessValidator's
        // role-aware logic.
        branchAccessValidator.validateAccess(acct.getBranch());
        if (acct.isClosed()) {
            throw new BusinessException(CbsErrorCodes.ACCT_CLOSED,
                    "Account is closed: " + accountNumber);
        }
        if (!acct.isCreditAllowed()) {
            throw new BusinessException(CbsErrorCodes.ACCT_FROZEN,
                    "Credit not allowed on " + accountNumber
                            + " (status=" + acct.getAccountStatus()
                            + ", freezeType=" + acct.getFreezeType() + ")");
        }
        return acct;
    }

    /**
     * Acquires PESSIMISTIC_WRITE on the teller's till for the current
     * business date and confirms it is in OPEN status. The repository
     * query is keyed by tellerUserId, so the authenticated principal
     * implicitly determines ownership -- a separate ownership check is
     * not required.
     */
    private TellerTill lockOpenTill(String tenantId, String tellerUser, LocalDate businessDate) {
        TellerTill till = tillRepository
                .findAndLockByTellerAndDate(tenantId, tellerUser, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                        "No till open for teller " + tellerUser + " on " + businessDate));
        if (!till.isOpen()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Till is not OPEN for cash deposit (current status: " + till.getStatus() + ")");
        }
        return till;
    }

    /**
     * Routes the GL posting through {@link TransactionEngine#execute}. The
     * engine is the single enforcement point for idempotency, business-date
     * validation, per-user transaction limits, and the maker-checker gate.
     * The teller channel and the API channel use the SAME engine path so GL
     * semantics are identical regardless of how the deposit was made.
     *
     * <p>Journal pair:
     * <pre>
     *   DR BANK_OPERATIONS (cash in hand)        amount
     *       CR customer deposit GL (SB / CA / product-specific)  amount
     * </pre>
     */
    private TransactionResult postCashDepositGl(
            DepositAccount acct, TellerTill till,
            CashDepositRequest req, LocalDate businessDate) {
        String customerGl = customerDepositGl(acct);
        return transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("TELLER")
                .transactionType("CASH_DEPOSIT")
                .accountReference(req.accountNumber())
                .amount(req.amount())
                .valueDate(businessDate)
                .branchCode(acct.getBranch().getBranchCode())
                .narration(resolveNarration(req))
                .idempotencyKey(req.idempotencyKey())
                .journalLines(List.of(
                        new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                                DebitCredit.DEBIT, req.amount(),
                                "Cash received at counter (till " + till.getId() + ")"),
                        new JournalLineRequest(customerGl,
                                DebitCredit.CREDIT, req.amount(),
                                "Credit " + req.accountNumber())))
                .build());
    }

    /**
     * Mutates the customer account ledger, recomputes available balance, and
     * reactivates the account if it was DORMANT (per RBI KYC §38: a customer-
     * initiated credit reactivates a dormant account). Caller must hold
     * PESSIMISTIC_WRITE on the account row.
     */
    private void applyDepositToAccount(DepositAccount acct, BigDecimal amount, LocalDate businessDate) {
        acct.setLedgerBalance(acct.getLedgerBalance().add(amount));
        // CBS BAL_DERIVE: available = ledger - hold - uncleared. Recompute
        // (do not increment) so active liens / uncleared cheques remain honored.
        acct.setAvailableBalance(
                acct.getLedgerBalance()
                        .subtract(acct.getHoldAmount())
                        .subtract(acct.getUnclearedAmount()));
        acct.setLastTransactionDate(businessDate);
        if (acct.isDormant()) {
            acct.setAccountStatus(com.finvanta.domain.enums.DepositAccountStatus.ACTIVE);
            acct.setDormantDate(null);
            // Inline because the caller holds the row lock; REQUIRES_NEW would
            // deadlock per AuditService.logEvent Javadoc.
            auditService.logEventInline(
                    "DepositAccount", acct.getId(), "DORMANCY_REACTIVATED",
                    "DORMANT", "ACTIVE", "ACCOUNT",
                    "Account " + acct.getAccountNumber()
                            + " reactivated via teller cash deposit of INR " + amount);
        }
        accountRepository.save(acct);
    }

    /**
     * Mutates the till {@code currentBalance} by the deposit amount. Caller
     * must hold PESSIMISTIC_WRITE on the till row.
     *
     * <p>Per RBI Internal Controls / CBS BAL_RECON: the till balance is the
     * cash subledger for the branch's GL BANK_OPERATIONS. EOD reconciliation
     * must satisfy:
     * <pre>
     *   sum(till.currentBalance @ branch X for business date D)
     *     + vault.currentBalance @ branch X for business date D
     *     == GL BANK_OPERATIONS branch balance @ branch X for business date D
     * </pre>
     */
    private void applyDepositToTill(TellerTill till, BigDecimal amount, String tellerUser) {
        till.setCurrentBalance(till.getCurrentBalance().add(amount));
        till.setUpdatedBy(tellerUser);
        tillRepository.save(till);
    }

    /**
     * Builds the customer-facing {@link DepositTransaction} row with channel
     * "TELLER". The {@code idempotencyKey} is persisted on this row so retries
     * dedupe via {@code DepositTransactionRepository.findByTenantIdAndIdempotencyKey}.
     */
    private DepositTransaction persistTxn(
            DepositAccount acct, TellerTill till, TransactionResult r, CashDepositRequest req,
            BigDecimal balanceBefore, BigDecimal balanceAfter, String narration, String tenantId) {
        DepositTransaction txn = new DepositTransaction();
        txn.setDepositAccount(acct);
        txn.setBranch(acct.getBranch());
        txn.setBranchCode(acct.getBranch().getBranchCode());
        txn.setTransactionRef(r.getTransactionRef());
        txn.setTransactionType("CASH_DEPOSIT");
        txn.setDebitCredit(DebitCredit.CREDIT.name());
        txn.setAmount(req.amount());
        txn.setBalanceBefore(balanceBefore);
        txn.setBalanceAfter(balanceAfter);
        txn.setValueDate(r.getValueDate());
        txn.setPostingDate(LocalDateTime.now());
        txn.setNarration(narration);
        txn.setChannel("TELLER");
        txn.setVoucherNumber(r.getVoucherNumber());
        txn.setJournalEntryId(r.getJournalEntryId());
        txn.setIdempotencyKey(req.idempotencyKey());
        txn.setTenantId(tenantId);
        return transactionRepository.save(txn);
    }

    /**
     * Persists immutable {@link CashDenomination} rows -- one per non-zero
     * denomination after coalescing duplicates. Counterfeit-flagged rows
     * are NEVER reached here because the FICN guard in {@code cashDeposit}
     * rejects the deposit before this point; every persisted row is genuine
     * tender.
     */
    private void persistDenominations(
            TellerTill till, CashDepositRequest req, String transactionRef,
            LocalDate businessDate, String tenantId) {
        Map<IndianCurrencyDenomination, DenominationValidator.MergedRow> merged =
                denominationValidator.coalesce(req.denominations());
        for (Map.Entry<IndianCurrencyDenomination, DenominationValidator.MergedRow> e
                : merged.entrySet()) {
            IndianCurrencyDenomination denom = e.getKey();
            DenominationValidator.MergedRow row = e.getValue();
            CashDenomination cd = new CashDenomination();
            cd.setTenantId(tenantId);
            cd.setTransactionRef(transactionRef);
            cd.setTillId(till.getId());
            cd.setValueDate(businessDate);
            cd.setDenomination(denom);
            cd.setUnitCount(row.unitCount());
            cd.setTotalValue(denom.totalFor(row.unitCount()));
            cd.setDirection("IN"); // cash deposit is always inflow at the till
            // FICN-flagged deposits are rejected upstream; persisted rows are
            // genuine tender. Defensive: ensure counterfeit fields are zero/false.
            cd.setCounterfeitFlag(false);
            cd.setCounterfeitCount(null);
            denominationRepository.save(cd);
        }
    }

    /**
     * Default narration when the operator omits one. Per CBS audit standards
     * a transaction narration is mandatory (TransactionRequest builder
     * enforces non-blank narration), so we synthesize one from the
     * mandatory {@code depositorName} field rather than failing.
     */
    private String resolveNarration(CashDepositRequest req) {
        return (req.narration() != null && !req.narration().isBlank())
                ? req.narration()
                : "Cash deposit by " + req.depositorName();
    }

    /**
     * Builds the response-side denomination echo from the request rows.
     * The validator's {@code coalesce} merges duplicates so the receipt
     * has exactly one line per denomination, ordered by enum declaration.
     */
    private List<CashDepositResponse.DenominationLine> buildResponseDenominationLines(
            CashDepositRequest req) {
        Map<IndianCurrencyDenomination, DenominationValidator.MergedRow> merged =
                denominationValidator.coalesce(req.denominations());
        List<CashDepositResponse.DenominationLine> lines = new ArrayList<>(merged.size());
        for (Map.Entry<IndianCurrencyDenomination, DenominationValidator.MergedRow> e
                : merged.entrySet()) {
            IndianCurrencyDenomination denom = e.getKey();
            DenominationValidator.MergedRow row = e.getValue();
            lines.add(new CashDepositResponse.DenominationLine(
                    denom,
                    row.unitCount(),
                    denom.totalFor(row.unitCount()),
                    row.counterfeitCount()));
        }
        return lines;
    }

    /**
     * Reads the persisted denomination breakdown for an idempotent retry.
     * The first attempt's denominations are returned to the caller so retries
     * are byte-for-byte identical from the BFF's perspective.
     */
    private List<CashDepositResponse.DenominationLine> lookupDenominationsForResponse(
            String tenantId, String transactionRef) {
        List<CashDepositResponse.DenominationLine> lines = new ArrayList<>();
        for (CashDenomination cd : denominationRepository
                .findByTenantIdAndTransactionRefOrderByDenominationAsc(tenantId, transactionRef)) {
            lines.add(new CashDepositResponse.DenominationLine(
                    cd.getDenomination(),
                    cd.getUnitCount(),
                    cd.getTotalValue(),
                    cd.getCounterfeitCount() != null ? cd.getCounterfeitCount() : 0L));
        }
        return lines;
    }

    /**
     * Maps a persisted {@link DepositTransaction} + post-state till to the
     * response DTO. Used by both the success and pending-approval paths,
     * and by the idempotent-retry early return. The till balance shown
     * reflects state AT THE TIME OF MAPPING -- for pending-approval and
     * dup-retry calls the till has not been re-mutated and the balance is
     * the same as before this request.
     */
    private CashDepositResponse mapToResponse(
            DepositTransaction txn, TellerTill till,
            List<CashDepositResponse.DenominationLine> denominations,
            boolean ctrTriggered, boolean ficnTriggered) {
        boolean pending = txn.getNarration() != null
                && txn.getNarration().contains("[PENDING APPROVAL]");
        return new CashDepositResponse(
                txn.getTransactionRef(),
                txn.getVoucherNumber(),
                txn.getDepositAccount().getAccountNumber(),
                txn.getAmount(),
                txn.getBalanceBefore(),
                txn.getBalanceAfter(),
                txn.getValueDate(),
                txn.getPostingDate(),
                txn.getNarration(),
                txn.getChannel(),
                pending,
                till.getCurrentBalance(),
                till.getId(),
                till.getTellerUserId(),
                denominations,
                ctrTriggered,
                ficnTriggered);
    }
}

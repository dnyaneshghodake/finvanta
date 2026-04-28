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
}

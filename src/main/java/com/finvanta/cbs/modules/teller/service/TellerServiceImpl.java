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
import com.finvanta.cbs.modules.teller.dto.request.CashWithdrawalRequest;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.domain.CounterfeitNoteRegister;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.dto.response.CashWithdrawalResponse;
import com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse;
import com.finvanta.cbs.modules.teller.exception.FicnDetectedException;
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
    private final FicnRegisterService ficnRegisterService;
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
            FicnRegisterService ficnRegisterService,
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
        this.ficnRegisterService = ficnRegisterService;
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
    // Till Lifecycle -- Supervisor Approval (PENDING_OPEN → OPEN)
    // =====================================================================

    @Override
    @Transactional
    public TellerTill approveTillOpen(Long tillId) {
        String tenantId = TenantContext.getCurrentTenant();
        String supervisor = SecurityUtil.getCurrentUsername();

        // Use findByIdWithBranch (JOIN FETCH branch) so the mapper can read
        // entity.getBranch().getBranchName() AFTER this @Transactional boundary
        // closes without LazyInitializationException -- OSIV is disabled in v2.
        TellerTill till = tillRepository.findByIdWithBranch(tenantId, tillId)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Till not found: " + tillId));

        if (!till.isPendingOpen()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Till is not in PENDING_OPEN status (current: " + till.getStatus()
                            + "). Only PENDING_OPEN tills can be approved.");
        }

        // CBS Maker ≠ Checker per RBI Internal Controls. The teller who
        // opened the till cannot approve their own till. This prevents a
        // single operator from bypassing the dual-control requirement on
        // high-value opening balances.
        if (supervisor.equals(till.getTellerUserId())) {
            throw new BusinessException(CbsErrorCodes.WF_SELF_APPROVAL,
                    "Supervisor cannot approve their own till. "
                            + "A different CHECKER or ADMIN must sign off. "
                            + "Teller: " + till.getTellerUserId()
                            + ", Supervisor: " + supervisor);
        }

        till.setStatus(TellerTillStatus.OPEN);
        till.setOpenedAt(LocalDateTime.now());
        till.setOpenedBySupervisor(supervisor);
        till.setUpdatedBy(supervisor);
        TellerTill saved = tillRepository.save(till);

        auditService.logEvent(
                "TellerTill", saved.getId(), "TILL_APPROVED",
                "PENDING_OPEN", "OPEN", "TELLER",
                "Till " + tillId + " approved by supervisor " + supervisor
                        + " for teller " + till.getTellerUserId()
                        + " at branch " + till.getBranchCode()
                        + " (opening balance: INR " + till.getOpeningBalance() + ")");

        log.info("CBS Teller till {} approved by supervisor {} for teller {} branch {}",
                tillId, supervisor, till.getTellerUserId(), till.getBranchCode());
        return saved;
    }

    // =====================================================================
    // Till Lifecycle -- Close (OPEN → PENDING_CLOSE → CLOSED)
    // =====================================================================

    @Override
    @Transactional
    public TellerTill requestCloseTill(BigDecimal countedBalance, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String tellerUser = SecurityUtil.getCurrentUsername();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        TellerTill till = tillRepository
                .findAndLockByTellerAndDate(tenantId, tellerUser, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                        "No till found for teller " + tellerUser + " on " + businessDate));

        if (!till.isOpen()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Till must be OPEN to request close (current: " + till.getStatus() + ")");
        }

        // Compute variance: counted (physical) - current (system).
        // Positive = overage (teller has more cash than system thinks).
        // Negative = shortage (teller has less cash than system thinks).
        BigDecimal variance = countedBalance.subtract(till.getCurrentBalance());

        till.setCountedBalance(countedBalance);
        till.setVarianceAmount(variance);
        till.setStatus(TellerTillStatus.PENDING_CLOSE);
        till.setRemarks(remarks);
        till.setUpdatedBy(tellerUser);
        TellerTill saved = tillRepository.save(till);

        String varianceNote = variance.signum() == 0
                ? "zero variance"
                : (variance.signum() > 0 ? "OVERAGE INR " + variance : "SHORTAGE INR " + variance.abs());

        auditService.logEventInline(
                "TellerTill", saved.getId(), "CLOSE_REQUESTED",
                "OPEN", "PENDING_CLOSE", "TELLER",
                "Till close requested by " + tellerUser
                        + " | system=" + till.getCurrentBalance()
                        + " | counted=" + countedBalance
                        + " | " + varianceNote);

        log.info("CBS Teller till {} close requested by {} (variance: {})",
                saved.getId(), tellerUser, varianceNote);
        return saved;
    }

    @Override
    @Transactional
    public TellerTill approveTillClose(Long tillId) {
        String tenantId = TenantContext.getCurrentTenant();
        String supervisor = SecurityUtil.getCurrentUsername();

        // Use findByIdWithBranch (JOIN FETCH branch) so the mapper can read
        // entity.getBranch().getBranchName() AFTER this @Transactional boundary
        // closes without LazyInitializationException -- OSIV is disabled in v2.
        TellerTill till = tillRepository.findByIdWithBranch(tenantId, tillId)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                        "Till not found: " + tillId));

        if (!till.isPendingClose()) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    "Till must be PENDING_CLOSE to approve close (current: " + till.getStatus() + ")");
        }

        // Maker ≠ checker: the teller who counted cannot approve their own close.
        if (supervisor.equals(till.getTellerUserId())) {
            throw new BusinessException(CbsErrorCodes.WF_SELF_APPROVAL,
                    "Supervisor cannot approve their own till close. "
                            + "Teller: " + till.getTellerUserId());
        }

        till.setStatus(TellerTillStatus.CLOSED);
        till.setClosedAt(LocalDateTime.now());
        till.setClosedBySupervisor(supervisor);
        till.setUpdatedBy(supervisor);
        TellerTill saved = tillRepository.save(till);

        String varianceNote = saved.getVarianceAmount() != null && saved.getVarianceAmount().signum() != 0
                ? "variance=" + saved.getVarianceAmount()
                : "zero variance";

        auditService.logEvent(
                "TellerTill", saved.getId(), "TILL_CLOSED",
                "PENDING_CLOSE", "CLOSED", "TELLER",
                "Till " + tillId + " closed by supervisor " + supervisor
                        + " for teller " + till.getTellerUserId()
                        + " | " + varianceNote);

        log.info("CBS Teller till {} CLOSED by supervisor {} ({})",
                tillId, supervisor, varianceNote);
        return saved;
    }

    // =====================================================================
    // Maker-Checker: Apply approved teller transaction
    // =====================================================================

    @Override
    @Transactional
    public void applyApprovedTellerTransaction(
            String accountNumber,
            BigDecimal amount,
            String transactionType,
            String makerUserId,
            TransactionResult result,
            LocalDate businessDate) {
        // CBS: This method is called by WorkflowController.approve() after
        // TransactionReExecutionService has posted the GL for a TELLER-sourced
        // transaction. It must:
        //   1. Lock the customer account FIRST, then resolve + lock the till.
        //   2. Apply the balance effect to the customer ledger.
        //   3. Mutate the till currentBalance.
        //   4. Persist CashDenomination rows (deferred — see NOTE below).
        //
        // The till is resolved from (makerUserId, businessDate) because a
        // teller has at most one till per business date (unique index
        // uq_till_tenant_teller_date). The makerUserId comes from
        // workflow.getMakerUserId() — the original teller who submitted the
        // deposit/withdrawal before it was routed to maker-checker.
        //
        // NOTE: CashDenomination rows require the original denomination
        // breakdown from the request, which is stored in the workflow's
        // payloadSnapshot JSON. Parsing that payload and reconstituting the
        // denomination list is a non-trivial enhancement that requires:
        //   a. The cashDeposit/cashWithdrawal methods to serialize the
        //      denomination breakdown into the TransactionRequest (which is
        //      then captured by the workflow's payloadSnapshot).
        //   b. This method to deserialize it back.
        //
        // For now, this method applies the ledger + till balance effect but
        // defers denomination persistence to a follow-up commit. The till
        // balance is correct; the denomination detail will be back-filled.

        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // 1. Lock customer account FIRST (canonical lock order).
        DepositAccount acct = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.ACCT_NOT_FOUND,
                        "Account not found: " + accountNumber));

        // 2. Resolve + lock the original teller's till. The till is found by
        // (tenantId, makerUserId, businessDate) — the unique index guarantees
        // at most one row. The lock serializes with any concurrent cash
        // deposit/withdrawal the teller may be posting on the same till.
        TellerTill till = tillRepository
                .findAndLockByTellerAndDate(tenantId, makerUserId, businessDate)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                        "Till not found for teller " + makerUserId
                                + " on " + businessDate
                                + ". The teller's till may have been closed before "
                                + "the checker approved the transaction."));

        // 3. Apply balance effect to customer ledger.
        boolean isCredit = "CASH_DEPOSIT".equals(transactionType);
        BigDecimal balanceBefore = acct.getLedgerBalance();
        if (isCredit) {
            acct.setLedgerBalance(balanceBefore.add(amount));
        } else {
            acct.setLedgerBalance(balanceBefore.subtract(amount));
        }
        acct.setAvailableBalance(
                acct.getLedgerBalance()
                        .subtract(acct.getHoldAmount())
                        .subtract(acct.getUnclearedAmount()));
        acct.setLastTransactionDate(businessDate);
        if (isCredit && acct.isDormant()) {
            acct.setAccountStatus(com.finvanta.domain.enums.DepositAccountStatus.ACTIVE);
            acct.setDormantDate(null);
            auditService.logEventInline(
                    "DepositAccount", acct.getId(), "DORMANCY_REACTIVATED",
                    "DORMANT", "ACTIVE", "ACCOUNT",
                    "Account " + accountNumber
                            + " reactivated via approved teller " + transactionType);
        }
        acct.setUpdatedBy(currentUser);
        accountRepository.save(acct);

        // 4. Mutate the till balance.
        if (isCredit) {
            till.setCurrentBalance(till.getCurrentBalance().add(amount));
        } else {
            till.setCurrentBalance(till.getCurrentBalance().subtract(amount));
        }
        till.setUpdatedBy(currentUser);
        tillRepository.save(till);

        // 5. Denomination persistence deferred (see NOTE above).

        auditService.logEventInline(
                "TellerCashTransaction", null, "APPROVED_APPLIED",
                null, null, "TELLER",
                "Approved teller " + transactionType + " applied: account=" + accountNumber
                        + " amount=INR " + amount + " till=" + till.getId()
                        + " teller=" + makerUserId
                        + " balanceBefore=INR " + balanceBefore
                        + " balanceAfter=INR " + acct.getLedgerBalance());

        log.info("CBS Teller approved txn applied: type={} account={} amount={} till={} teller={}",
                transactionType, accountNumber, amount, till.getId(), makerUserId);
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

        // 1. Pre-lock validation: denomination math + CTR.
        // Run BEFORE any DB lock so a bad request never holds a write lock.
        // The FICN check is deliberately DEFERRED until after locks are
        // acquired so the register row can be written with a definitive
        // till_id / branch_id (resolved from the locked entities, not from
        // the unauthenticated principal). See recordFicnDetection() for the
        // rationale -- an FICN row is a regulatory artefact per RBI Master
        // Direction and must carry the actual till that impounded the notes.
        denominationValidator.validateSum(request.denominations(), request.amount());
        validateCtrCompliance(request);

        // 2. Lock customer account FIRST, then till. Lock order matches the
        // transfer deadlock-prevention pattern in DepositAccountModuleServiceImpl
        // -- the cash side never holds its row lock waiting on a customer lock.
        DepositAccount acct = lockAccountForCredit(tenantId, request.accountNumber());
        TellerTill till = lockOpenTill(tenantId, tellerUser, businessDate);

        // 2.5. FICN gate (post-lock). If the denomination breakdown contains
        // any counterfeit-flagged notes, impound them per RBI Master Direction
        // on Counterfeit Notes: the deposit is REJECTED (customer is not
        // credited for any portion, per Option B -- the genuine notes must
        // be re-tendered in a fresh transaction), and a permanent
        // CounterfeitNoteRegister row is committed so the customer receives
        // a printable acknowledgement slip with a traceable register ref.
        //
        // The throw happens AFTER the register write so the exception payload
        // carries the permanent register reference. The @Transactional on
        // cashDeposit means the register row is committed ONLY if the
        // exception propagates cleanly -- if the FICN write itself fails,
        // Spring rolls back the TX and the customer sees a generic error
        // (they can retry, the bank has no record). This is deliberate:
        // rather than risk a misleading "impounded" slip without a register
        // row, we fail-safe to "no impoundment, retry with supervisor".
        //
        // Spring rolls back on RuntimeException by default, which applies to
        // both BusinessException and FicnDetectedException. But we WANT the
        // register row to SURVIVE the rollback triggered by
        // FicnDetectedException -- that's the whole point. So we pre-commit
        // the row via a REQUIRES_NEW sub-transaction in recordFicnDetection.
        if (denominationValidator.hasCounterfeit(request.denominations())) {
            // Delegate to the dedicated FicnRegisterService whose
            // recordDetection() runs in a REQUIRES_NEW sub-transaction. The
            // register row commits BEFORE this method's @Transactional
            // boundary closes, so when we throw FicnDetectedException below
            // the parent TX rolls back (unrolling whatever locks we hold) but
            // the register row + its inline-audit log remain durably
            // committed. That is what guarantees the customer's printed
            // acknowledgement slip is always backed by a real DB row.
            FicnAcknowledgementResponse ack = ficnRegisterService.recordDetection(
                    request,
                    acct.getBranch(),
                    acct.getBranch().getBranchCode(),
                    till.getId(),
                    tellerUser,
                    businessDate,
                    tenantId);
            throw new FicnDetectedException(ack);
        }

        // 3. Idempotency dedupe AFTER locks are held (TOCTOU-safe ordering).
        DepositTransaction dup = transactionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                .orElse(null);
        if (dup != null) {
            // CBS dup-retry: the prior commit's pending state is the source
            // of truth. Use journalEntryId nullability as the discriminator --
            // the engine populates it only on POSTED, leaves it null on
            // PENDING_APPROVAL. Parsing the narration string is unsafe because
            // the operator supplies it verbatim via req.narration().
            boolean priorPending = dup.getJournalEntryId() == null;
            return mapToResponse(dup, till,
                    lookupDenominationsForResponse(tenantId, dup.getTransactionRef()),
                    request.amount().compareTo(CTR_PAN_THRESHOLD) >= 0,
                    /* ficnTriggered */ false,
                    priorPending);
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
            // Cap the combined narration at the column limit (500 chars). The
            // user narration is already @Size(max=500); appending " [PENDING
            // APPROVAL]" without truncation would overflow the column.
            String pendingNarration = capNarration(
                    resolveNarration(request) + " [PENDING APPROVAL]");
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
            return mapToResponse(pendingTxn, till, List.of(), ctrTriggered, false,
                    /* pendingApproval */ true);
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
                buildResponseDenominationLines(request), ctrTriggered, false,
                /* pendingApproval */ false);
    }

    // =====================================================================
    // Cash Withdrawal
    // =====================================================================

    /**
     * Pays out cash to a customer at the counter. Mirrors {@link #cashDeposit}
     * with direction-aware differences:
     * <ul>
     *   <li>Account validation: {@code isDebitAllowed()} (CREDIT_FREEZE allows
     *       debits; DEBIT_FREEZE / TOTAL_FREEZE / DORMANT / CLOSED block them).</li>
     *   <li>Sufficient-balance + minimum-balance + per-account daily withdrawal
     *       limit checks before any GL post.</li>
     *   <li>Till-side: physical cash availability check
     *       ({@code currentBalance >= amount}) -- {@code CBS-TELLER-006}.</li>
     *   <li>GL pair flipped: DR customer GL / CR BANK_OPERATIONS.</li>
     *   <li>Till mutation: DECREMENT {@code currentBalance} on POSTED.</li>
     *   <li>Denomination rows persisted with {@code direction = 'OUT'}.</li>
     * </ul>
     *
     * <p>Same lock order, same lock-then-check idempotency, same maker-checker
     * gate as deposit. The pending-approval path leaves the till and customer
     * ledger UNCHANGED -- the customer leaves the counter empty-handed AND
     * un-debited until the supervisor approves.
     */
    @Override
    @Transactional
    public CashWithdrawalResponse cashWithdrawal(CashWithdrawalRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        String tellerUser = SecurityUtil.getCurrentUsername();
        LocalDate businessDate = businessDateService.getCurrentBusinessDate();

        // 1. Pre-lock validation: denomination math. Bean Validation already
        // rejected non-zero counterfeit on a withdrawal request, so the
        // hasCounterfeit() guard from the deposit path is unnecessary here.
        denominationValidator.validateSum(request.denominations(), request.amount());

        // 2. Lock customer account FIRST, then till -- canonical order.
        DepositAccount acct = lockAccountForDebit(tenantId, request.accountNumber());
        TellerTill till = lockOpenTill(tenantId, tellerUser, businessDate);

        // 3. Customer-side balance + limit checks. These mirror the v2 deposit
        // module's withdraw() so the teller channel and API channel reject the
        // same scenarios with the same error codes.
        accountValidatorEnforceSufficient(acct, request.amount());
        accountValidatorEnforceMinBalance(acct, request.amount());
        accountValidatorEnforceDailyLimit(acct, request.amount(), businessDate, tenantId);

        // 4. Till-side cash availability. Per RBI Internal Controls: a till
        // can never go negative; the teller must request a vault buy first.
        if (till.getCurrentBalance().compareTo(request.amount()) < 0) {
            throw new BusinessException(CbsErrorCodes.TELLER_TILL_INSUFFICIENT_CASH,
                    "Till has INR " + till.getCurrentBalance()
                            + " on hand; cannot pay out INR " + request.amount()
                            + ". Request a vault buy before continuing.");
        }

        // 5. Idempotency dedupe AFTER locks (TOCTOU-safe).
        DepositTransaction dup = transactionRepository
                .findByTenantIdAndIdempotencyKey(tenantId, request.idempotencyKey())
                .orElse(null);
        if (dup != null) {
            // CBS dup-retry: see deposit-side comment. journalEntryId is the
            // authoritative pending discriminator; narration parsing is unsafe.
            boolean priorPending = dup.getJournalEntryId() == null;
            return mapWithdrawalToResponse(dup, till,
                    lookupWithdrawalDenominations(tenantId, dup.getTransactionRef()),
                    request.amount().compareTo(CTR_PAN_THRESHOLD) >= 0,
                    request.chequeNumber(),
                    priorPending);
        }

        // 6. Engine post: DR customer GL / CR BANK_OPERATIONS.
        TransactionResult r = postCashWithdrawalGl(acct, till, request, businessDate);
        boolean ctrTriggered = request.amount().compareTo(CTR_PAN_THRESHOLD) >= 0;

        // 7. Maker-checker pending: balances UNCHANGED, no denom rows.
        if (r.isPendingApproval()) {
            // Cap the combined narration at the column limit (500 chars). See
            // mirroring comment on the deposit path for the rationale.
            String pendingNarration = capNarration(
                    resolveWithdrawalNarration(request) + " [PENDING APPROVAL]");
            DepositTransaction pendingTxn = persistWithdrawalTxn(
                    acct, till, r, request,
                    acct.getLedgerBalance(), acct.getLedgerBalance(),
                    pendingNarration, tenantId);
            auditService.logEventInline(
                    "TellerCashWithdrawal", pendingTxn.getId(), "PENDING_APPROVAL",
                    null, pendingTxn, "TELLER",
                    "Cash withdrawal pending checker approval: INR " + request.amount()
                            + " account=" + request.accountNumber()
                            + " till=" + till.getId());
            return mapWithdrawalToResponse(pendingTxn, till, java.util.List.of(),
                    ctrTriggered, request.chequeNumber(),
                    /* pendingApproval */ true);
        }

        // 8. Posted: debit ledger, decrement till, persist denominations OUT.
        BigDecimal balanceBefore = acct.getLedgerBalance();
        applyWithdrawalToAccount(acct, request.amount(), businessDate);
        applyWithdrawalToTill(till, request.amount(), tellerUser);

        DepositTransaction txn = persistWithdrawalTxn(
                acct, till, r, request, balanceBefore, acct.getLedgerBalance(),
                resolveWithdrawalNarration(request), tenantId);
        persistWithdrawalDenominations(till, request, txn.getTransactionRef(), businessDate, tenantId);

        auditService.logEventInline(
                "TellerCashWithdrawal", txn.getId(), "POSTED",
                null, txn, "TELLER",
                "Cash withdrawal POSTED: INR " + request.amount()
                        + " account=" + request.accountNumber()
                        + " till=" + till.getId()
                        + " ctr=" + ctrTriggered);

        log.info("CBS Teller withdrawal POSTED txnRef={} account={} amount={} till={} ctr={}",
                txn.getTransactionRef(), request.accountNumber(), request.amount(),
                till.getId(), ctrTriggered);

        return mapWithdrawalToResponse(txn, till,
                buildWithdrawalResponseLines(request), ctrTriggered, request.chequeNumber(),
                /* pendingApproval */ false);
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
     *
     * <p>The denomination breakdown is NOT appended to the narration -- it
     * is already persisted in immutable {@link CashDenomination} rows linked
     * by {@code transactionRef}. Encoding it into the narration risked
     * overflowing the {@code deposit_transactions.narration VARCHAR(500)}
     * column when combined with the user's own narration (up to 500 chars
     * by Bean Validation) plus the {@code [PENDING APPROVAL]} marker on the
     * maker-checker path.
     */
    private String resolveNarration(CashDepositRequest req) {
        return (req.narration() != null && !req.narration().isBlank())
                ? req.narration()
                : "Cash deposit by " + req.depositorName();
    }

    /**
     * Maximum length of the {@code deposit_transactions.narration} column.
     * Mirrors the DDL: {@code narration VARCHAR(500)}.
     */
    private static final int NARRATION_COLUMN_LIMIT = 500;

    /**
     * Caps a narration string to the {@link #NARRATION_COLUMN_LIMIT} so that
     * appending markers like {@code " [PENDING APPROVAL]"} to a user narration
     * already at the {@code @Size(max=500)} limit cannot overflow the DB
     * column. Returns the input unchanged when within the limit.
     */
    private static String capNarration(String narration) {
        if (narration == null || narration.length() <= NARRATION_COLUMN_LIMIT) {
            return narration;
        }
        return narration.substring(0, NARRATION_COLUMN_LIMIT);
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
            boolean ctrTriggered, boolean ficnTriggered,
            boolean pendingApproval) {
        // CBS: pendingApproval is supplied authoritatively by the caller --
        // either from the engine's r.isPendingApproval() on the live path,
        // or from journalEntryId nullability on the dup-retry path. We MUST
        // NOT parse it from the narration string because the narration
        // includes operator-supplied req.narration() verbatim, which would
        // let a teller typing "[PENDING APPROVAL]" into their narration
        // cause a successfully-POSTED deposit to be reported as pending --
        // triggering an operator-initiated retry that double-credits the
        // customer (with a fresh idempotency key per JSP page render).
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
                pendingApproval,
                till.getCurrentBalance(),
                till.getId(),
                till.getTellerUserId(),
                denominations,
                ctrTriggered,
                ficnTriggered);
    }

    // =====================================================================
    // Withdrawal-specific helpers (mirror the deposit helpers above)
    // =====================================================================

    /**
     * Acquires PESSIMISTIC_WRITE on the customer account and validates it
     * accepts debits. Per RBI Freeze Guidelines: DEBIT_FREEZE / TOTAL_FREEZE
     * block debits, CREDIT_FREEZE permits them, DORMANT blocks them
     * (re-activation requires a credit, not a debit), CLOSED blocks them.
     *
     * <p>Mirrors {@link #lockAccountForCredit} but with the freeze semantics
     * inverted via {@link DepositAccount#isDebitAllowed()}.
     */
    private DepositAccount lockAccountForDebit(String tenantId, String accountNumber) {
        DepositAccount acct = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() -> new BusinessException(CbsErrorCodes.ACCT_NOT_FOUND,
                        "Account not found: " + accountNumber));
        branchAccessValidator.validateAccess(acct.getBranch());
        if (acct.isClosed()) {
            throw new BusinessException(CbsErrorCodes.ACCT_CLOSED,
                    "Account is closed: " + accountNumber);
        }
        if (!acct.isDebitAllowed()) {
            throw new BusinessException(CbsErrorCodes.ACCT_FROZEN,
                    "Debit not allowed on " + accountNumber
                            + " (status=" + acct.getAccountStatus()
                            + ", freezeType=" + acct.getFreezeType() + ")");
        }
        return acct;
    }

    /**
     * Sufficient-balance enforcement using the account's
     * {@code effectiveAvailable} (ledger - hold - uncleared + odLimit).
     * Mirrors the same check the v2 deposit module performs in withdraw().
     */
    private void accountValidatorEnforceSufficient(DepositAccount acct, BigDecimal amount) {
        BigDecimal effectiveAvailable = acct.getEffectiveAvailable();
        if (effectiveAvailable.compareTo(amount) < 0) {
            throw new BusinessException(CbsErrorCodes.ACCT_INSUFFICIENT_BALANCE,
                    "Insufficient balance. Available: INR " + effectiveAvailable
                            + ", requested: INR " + amount);
        }
    }

    /**
     * Minimum-balance enforcement per RBI CASA norms. Skipped for accounts
     * with zero/null minimum balance (PMJDY, current accounts) so the
     * teller channel matches v2 deposit-module semantics.
     */
    private void accountValidatorEnforceMinBalance(DepositAccount acct, BigDecimal amount) {
        BigDecimal minBalance = acct.getMinimumBalance();
        if (minBalance == null || minBalance.signum() <= 0) {
            return;
        }
        BigDecimal postBalance = acct.getLedgerBalance().subtract(amount);
        if (postBalance.compareTo(minBalance) < 0) {
            throw new BusinessException(CbsErrorCodes.ACCT_MINIMUM_BALANCE_BREACH,
                    "Withdrawal of INR " + amount
                            + " would breach minimum balance of INR " + minBalance
                            + " on account " + acct.getAccountNumber()
                            + ". Post-debit balance: INR " + postBalance);
        }
    }

    /**
     * Per-account daily withdrawal limit per RBI Operational Risk Guidelines.
     * Independent of the user/role-level limit enforced inside the engine --
     * BOTH gates apply. Skipped when the account has no configured cap.
     */
    private void accountValidatorEnforceDailyLimit(
            DepositAccount acct, BigDecimal amount, LocalDate businessDate, String tenantId) {
        BigDecimal limit = acct.getDailyWithdrawalLimit();
        if (limit == null || limit.signum() <= 0) {
            return;
        }
        BigDecimal already = transactionRepository
                .sumDailyDebits(tenantId, acct.getId(), businessDate);
        if (already == null) already = BigDecimal.ZERO;
        if (already.add(amount).compareTo(limit) > 0) {
            throw new BusinessException(CbsErrorCodes.ACCT_DAILY_LIMIT_EXCEEDED,
                    "Daily withdrawal limit INR " + limit
                            + " exceeded on " + acct.getAccountNumber()
                            + ". Today's debits: INR " + already
                            + ", requested: INR " + amount);
        }
    }

    /**
     * Routes the withdrawal GL posting through the engine. Journal pair is
     * the inverse of {@link #postCashDepositGl}: cash leaves the till and
     * the customer-deposit liability shrinks.
     *
     * <pre>
     *   DR customer deposit GL (SB / CA / product-specific)  amount
     *       CR BANK_OPERATIONS (cash in hand)                amount
     * </pre>
     */
    private TransactionResult postCashWithdrawalGl(
            DepositAccount acct, TellerTill till,
            CashWithdrawalRequest req, LocalDate businessDate) {
        String customerGl = customerDepositGl(acct);
        return transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("TELLER")
                .transactionType("CASH_WITHDRAWAL")
                .accountReference(req.accountNumber())
                .amount(req.amount())
                .valueDate(businessDate)
                .branchCode(acct.getBranch().getBranchCode())
                .narration(resolveWithdrawalNarration(req))
                .idempotencyKey(req.idempotencyKey())
                .journalLines(List.of(
                        new JournalLineRequest(customerGl,
                                DebitCredit.DEBIT, req.amount(),
                                "Debit " + req.accountNumber()),
                        new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                                DebitCredit.CREDIT, req.amount(),
                                "Cash paid out at counter (till " + till.getId() + ")")))
                .build());
    }

    /**
     * Decrements the customer ledger and recomputes available balance.
     * Caller must hold PESSIMISTIC_WRITE on the account row. Symmetric to
     * {@link #applyDepositToAccount} but on the debit side -- and crucially,
     * does NOT include a dormancy reactivation step (DORMANT accounts are
     * already rejected by {@link #lockAccountForDebit} per RBI KYC §38).
     */
    private void applyWithdrawalToAccount(DepositAccount acct, BigDecimal amount, LocalDate businessDate) {
        acct.setLedgerBalance(acct.getLedgerBalance().subtract(amount));
        acct.setAvailableBalance(
                acct.getLedgerBalance()
                        .subtract(acct.getHoldAmount())
                        .subtract(acct.getUnclearedAmount()));
        acct.setLastTransactionDate(businessDate);
        accountRepository.save(acct);
    }

    /** Decrements till {@code currentBalance}. Caller holds the lock. */
    private void applyWithdrawalToTill(TellerTill till, BigDecimal amount, String tellerUser) {
        till.setCurrentBalance(till.getCurrentBalance().subtract(amount));
        till.setUpdatedBy(tellerUser);
        tillRepository.save(till);
    }

    /**
     * Builds the customer-facing {@code DepositTransaction} row for a
     * withdrawal. Channel = TELLER, debitCredit = DEBIT, transactionType =
     * CASH_WITHDRAWAL. {@code chequeNumber} surfaced when present so the
     * cheque-clearing reconciliation in the clearing module can match by
     * (account, cheque number, amount).
     */
    private DepositTransaction persistWithdrawalTxn(
            DepositAccount acct, TellerTill till, TransactionResult r, CashWithdrawalRequest req,
            BigDecimal balanceBefore, BigDecimal balanceAfter, String narration, String tenantId) {
        DepositTransaction txn = new DepositTransaction();
        txn.setDepositAccount(acct);
        txn.setBranch(acct.getBranch());
        txn.setBranchCode(acct.getBranch().getBranchCode());
        txn.setTransactionRef(r.getTransactionRef());
        txn.setTransactionType("CASH_WITHDRAWAL");
        txn.setDebitCredit(DebitCredit.DEBIT.name());
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
        txn.setChequeNumber(req.chequeNumber());
        txn.setTenantId(tenantId);
        return transactionRepository.save(txn);
    }

    /**
     * Persists immutable {@code direction='OUT'} denomination rows for the
     * cash paid out. Counterfeit fields are always zero/false because the
     * Bean Validation guard on {@link CashWithdrawalRequest} rejected any
     * non-zero counterfeit count at the boundary.
     */
    private void persistWithdrawalDenominations(
            TellerTill till, CashWithdrawalRequest req, String transactionRef,
            LocalDate businessDate, String tenantId) {
        java.util.Map<IndianCurrencyDenomination, DenominationValidator.MergedRow> merged =
                denominationValidator.coalesce(req.denominations());
        for (java.util.Map.Entry<IndianCurrencyDenomination, DenominationValidator.MergedRow> e
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
            cd.setDirection("OUT"); // cash withdrawal = outflow at the till
            cd.setCounterfeitFlag(false);
            cd.setCounterfeitCount(null);
            denominationRepository.save(cd);
        }
    }

    /**
     * Default narration for withdrawals. Mirrors {@link #resolveNarration}.
     * Denomination breakdown is persisted separately in {@link CashDenomination}
     * rows; it is NOT appended here to keep the combined narration within the
     * {@code deposit_transactions.narration VARCHAR(500)} column limit.
     */
    private String resolveWithdrawalNarration(CashWithdrawalRequest req) {
        return (req.narration() != null && !req.narration().isBlank())
                ? req.narration()
                : "Cash withdrawal by " + req.beneficiaryName();
    }

    /**
     * Builds the response-side denomination echo for a withdrawal. Same
     * coalesce + sort discipline as the deposit echo so the receipt slip
     * is direction-agnostic from the BFF's perspective.
     */
    private List<CashWithdrawalResponse.DenominationLine> buildWithdrawalResponseLines(
            CashWithdrawalRequest req) {
        java.util.Map<IndianCurrencyDenomination, DenominationValidator.MergedRow> merged =
                denominationValidator.coalesce(req.denominations());
        List<CashWithdrawalResponse.DenominationLine> lines = new ArrayList<>(merged.size());
        for (java.util.Map.Entry<IndianCurrencyDenomination, DenominationValidator.MergedRow> e
                : merged.entrySet()) {
            IndianCurrencyDenomination denom = e.getKey();
            DenominationValidator.MergedRow row = e.getValue();
            lines.add(new CashWithdrawalResponse.DenominationLine(
                    denom,
                    row.unitCount(),
                    denom.totalFor(row.unitCount()),
                    /* counterfeit always 0 on a withdrawal */ 0L));
        }
        return lines;
    }

    /**
     * Reads persisted denomination breakdown for an idempotent withdrawal
     * retry. Symmetric to {@link #lookupDenominationsForResponse}.
     */
    private List<CashWithdrawalResponse.DenominationLine> lookupWithdrawalDenominations(
            String tenantId, String transactionRef) {
        List<CashWithdrawalResponse.DenominationLine> lines = new ArrayList<>();
        for (CashDenomination cd : denominationRepository
                .findByTenantIdAndTransactionRefOrderByDenominationAsc(tenantId, transactionRef)) {
            lines.add(new CashWithdrawalResponse.DenominationLine(
                    cd.getDenomination(),
                    cd.getUnitCount(),
                    cd.getTotalValue(),
                    cd.getCounterfeitCount() != null ? cd.getCounterfeitCount() : 0L));
        }
        return lines;
    }

    /**
     * Maps a persisted withdrawal {@code DepositTransaction} + post-state
     * till to {@link CashWithdrawalResponse}. Symmetric to
     * {@link #mapToResponse} for deposits. {@code chequeNumber} is sourced
     * from the original request so the receipt slip can render it without
     * an extra DB hit.
     */
    private CashWithdrawalResponse mapWithdrawalToResponse(
            DepositTransaction txn, TellerTill till,
            List<CashWithdrawalResponse.DenominationLine> denominations,
            boolean ctrTriggered, String chequeNumber,
            boolean pendingApproval) {
        // CBS: pendingApproval supplied authoritatively by the caller. See the
        // mirroring comment in mapToResponse() for the rationale -- narration
        // parsing is unsafe because operator-supplied req.narration() flows
        // through verbatim.
        return new CashWithdrawalResponse(
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
                pendingApproval,
                till.getCurrentBalance(),
                till.getId(),
                till.getTellerUserId(),
                denominations,
                ctrTriggered,
                chequeNumber);
    }
}

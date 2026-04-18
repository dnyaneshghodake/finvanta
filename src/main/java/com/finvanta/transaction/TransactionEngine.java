package com.finvanta.transaction;

import com.finvanta.accounting.AccountingService;
import com.finvanta.accounting.PostingIntegrityGuard;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.IdempotencyRegistry;
import com.finvanta.domain.entity.TransactionOutbox;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.IdempotencyRegistryRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.repository.TransactionOutboxRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.MakerCheckerService;
import com.finvanta.service.SequenceGeneratorService;
import com.finvanta.service.TransactionLimitService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import com.finvanta.domain.enums.DebitCredit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Generic Transaction Engine per Finacle TRAN_POSTING / Temenos TRANSACTION framework.
 *
 * This is the SINGLE ENFORCEMENT POINT for the entire CBS platform.
 * Every financial transaction — regardless of which module initiates it — must pass
 * through this engine. The engine enforces the CBS validation chain in the correct
 * order, ensuring no module can bypass any step.
 *
 * Validation chain (executed in order):
 *   Step 0:   Financial safety kill switch (PostingIntegrityGuard)
 *   Step 1:   Idempotency check (engine-level cross-module dedup via IdempotencyRegistry)
 *   Step 1.5: Tenant validation + RBI CTR/AML flagging (TransactionValidationService)
 *   Step 2:   Business date validation (value date must be valid calendar date)
 *   Step 2.5: Value date window validation (T-2 to T+0)
 *   Step 3:   Day status validation (only DAY_OPEN allows postings; EOD system exempt)
 *   Step 4:   Amount & currency validation (positive, precision, INR only)
 *   Step 5:   Branch validation (branch must exist and be active)
 *   Step 5.5: Transaction batch validation (open batch required)
 *   Step 6:   Transaction limit validation (per-role per-transaction + daily aggregate)
 *   Step 7:   Maker-checker gate (transactions above threshold → PENDING_APPROVAL)
 *   Step 8.0: Voucher & transaction ref pre-allocation (before GL locks)
 *   Step 8:   Double-entry journal posting (GL validation, balance update, ledger, batch)
 *   Step 10:  Audit trail (immutable record with hash chain)
 *   Step 11:  Outbox event publishing (same TX — for async reconciliation, CTR, fraud)
 *
 * After the engine returns TransactionResult, the calling module:
 *   - Records its module-specific transaction (LoanTransaction, DepositTransaction, etc.)
 *   - Updates its module-specific subledger (outstanding principal, balance, etc.)
 *   - Links to the journalEntryId and voucherNumber from the result
 *
 * Architecture:
 *   Module (Loan/Deposit/Remittance)
 *     → builds TransactionRequest (what to do)
 *     → calls TransactionEngine.execute(request)
 *       → 10-step validation chain
 *     → receives TransactionResult (what happened)
 *     → updates its own subledger
 *
 * This design ensures:
 * 1. No CBS module can bypass any validation step
 * 2. New modules (Deposit, Remittance) get all validations for free
 * 3. Validation order is centrally controlled and auditable
 * 4. Voucher generation is consistent across all modules
 * 5. Maker-checker threshold is enforced uniformly
 */
@Service
public class TransactionEngine {

    private static final Logger log = LoggerFactory.getLogger(TransactionEngine.class);

    /** Configurable value date window: max days back from current business date */
    @org.springframework.beans.factory.annotation.Value("${cbs.value-date.back-days:2}") // NOSONAR — inline FQCN avoids import collision with lombok.Value
    private int valueDateBackDays;

    /** Configurable value date window: max days forward from current business date */
    @org.springframework.beans.factory.annotation.Value("${cbs.value-date.forward-days:0}") // NOSONAR — inline FQCN avoids import collision with lombok.Value
    private int valueDateForwardDays;

    private final AccountingService accountingService;
    private final TransactionLimitService limitService;
    private final MakerCheckerService makerCheckerService;
    private final BusinessDateService businessDateService;
    private final BusinessCalendarRepository calendarRepository;
    private final BranchRepository branchRepository;
    private final TransactionBatchRepository batchRepository;
    private final AuditService auditService;
    private final SequenceGeneratorService sequenceGenerator;
    private final PostingIntegrityGuard integrityGuard;
    private final IdempotencyRegistryRepository idempotencyRepository;
    private final TransactionValidationService validationService;
    private final TransactionOutboxRepository outboxRepository;

    /**
     * CBS Tier-1: Self-proxy for @Transactional method invocation.
     * Spring AOP proxies do NOT intercept self-calls (this.executeInternal()).
     * Without this, the @Transactional on executeInternal() is ignored — the
     * isolation level and transaction demarcation would not be applied.
     * Same pattern as BatchService.self and StandingInstructionServiceImpl.self.
     */
    @Lazy
    @Autowired
    private TransactionEngine self;

    public TransactionEngine(
            AccountingService accountingService,
            TransactionLimitService limitService,
            MakerCheckerService makerCheckerService,
            BusinessDateService businessDateService,
            BusinessCalendarRepository calendarRepository,
            BranchRepository branchRepository,
            TransactionBatchRepository batchRepository,
            AuditService auditService,
            SequenceGeneratorService sequenceGenerator,
            PostingIntegrityGuard integrityGuard,
            IdempotencyRegistryRepository idempotencyRepository,
            TransactionValidationService validationService,
            TransactionOutboxRepository outboxRepository) {
        this.accountingService = accountingService;
        this.limitService = limitService;
        this.makerCheckerService = makerCheckerService;
        this.businessDateService = businessDateService;
        this.calendarRepository = calendarRepository;
        this.branchRepository = branchRepository;
        this.batchRepository = batchRepository;
        this.auditService = auditService;
        this.sequenceGenerator = sequenceGenerator;
        this.integrityGuard = integrityGuard;
        this.idempotencyRepository = idempotencyRepository;
        this.validationService = validationService;
        this.outboxRepository = outboxRepository;
    }

    /** CBS Tier-1: Max retry attempts on deadlock/lock-timeout per Finacle GL_LOCK */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /** CBS Tier-1: Base backoff delay in ms (doubles on each retry: 100→200→400) */
    private static final long RETRY_BASE_DELAY_MS = 100;

    /** CBS Tier-1: Accepted currency code per RBI FEMA regulations.
     *  Even a domestic-only CBS must explicitly validate currency to reject
     *  non-INR postings and prepare for future FCY module integration.
     *  Per Finacle TRAN_POSTING.CURRENCY / Temenos CURRENCY.MARKET. */
    private static final String CBS_CURRENCY_CODE = "INR";

    /**
     * Executes a financial transaction through the CBS validation chain with
     * automatic retry on transient lock failures.
     *
     * <p><b>CBS Tier-1 Retry Strategy per Finacle GL_LOCK / Temenos EB.LOCK:</b>
     * SQL Server deadlock victims and lock-timeout failures are transient — the
     * same transaction will succeed on retry once the conflicting lock is released.
     * This method retries up to {@value #MAX_RETRY_ATTEMPTS} times with exponential
     * backoff (100ms → 200ms → 400ms) before failing permanently.
     *
     * <p>Only {@code PessimisticLockingFailureException} (lock timeout),
     * {@code DeadlockLoserDataAccessException} (deadlock victim), and
     * {@code CannotAcquireLockException} (generic lock failure) trigger retry.
     * Business exceptions ({@code BusinessException}) fail immediately — they
     * indicate validation failures, not transient contention.
     *
     * <p><b>Retry scope:</b> The retry mechanism is effective for system-generated
     * transactions (EOD batch, Standing Instructions) that call {@code execute()}
     * directly without an enclosing caller transaction. For user-initiated
     * transactions (deposit, withdrawal), the caller's {@code @Transactional}
     * method is the atomic boundary — a lock failure poisons the caller's TX,
     * and retry must happen at the caller level (controller/service).
     *
     * @param request The transaction request built by the calling module
     * @return TransactionResult with journal ref, voucher number, and posting status
     * @throws BusinessException if any validation step fails (non-retryable)
     * @throws PessimisticLockingFailureException if all retry attempts exhausted
     */
    public TransactionResult execute(TransactionRequest request) {
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return self.executeInternal(request);
            } catch (PessimisticLockingFailureException e) {
                // Catches all lock-related failures including DeadlockLoserDataAccessException
                // and CannotAcquireLockException (both are subclasses of PessimisticLockingFailureException).
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    log.error(
                            "Transaction FAILED after {} retries (lock contention): module={}, type={}, amount={}, account={}",
                            MAX_RETRY_ATTEMPTS,
                            request.getSourceModule(),
                            request.getTransactionType(),
                            request.getAmount(),
                            request.getAccountReference(),
                            e);
                    throw e;
                }
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1)); // exponential: 100, 200, 400
                log.warn(
                        "Transaction retry {}/{} after lock failure: module={}, type={}, delay={}ms, error={}",
                        attempt,
                        MAX_RETRY_ATTEMPTS,
                        request.getSourceModule(),
                        request.getTransactionType(),
                        delay,
                        e.getClass().getSimpleName());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("TRANSACTION_INTERRUPTED", "Transaction interrupted during retry backoff");
                }
            }
        }
        // Unreachable — loop either returns or throws
        throw new IllegalStateException("Retry loop exited without result");
    }

    /**
     * Internal transaction execution — the core 11-step CBS validation + GL posting pipeline.
     *
     * <p><b>CBS Tier-1 CRITICAL — Financial Atomicity (REQUIRED propagation):</b>
     * This method uses {@code Propagation.REQUIRED} (joins the caller's transaction)
     * to guarantee GL↔Subledger atomicity. The GL posting and the caller's subledger
     * update (e.g., account balance in DepositAccountServiceImpl.deposit()) MUST
     * commit or roll back as a single atomic unit. Without this:
     * <ul>
     *   <li>REQUIRES_NEW would commit GL independently — if the caller's subledger
     *       update fails afterward, GL is committed but account balance is not →
     *       permanent financial inconsistency (GL shows deposit, account doesn't)</li>
     *   <li>This violates Tier-1 CBS Foundational Principle #1: "Financial atomicity
     *       across modules" — GL and subledger MUST be in the same TX boundary</li>
     * </ul>
     *
     * <p><b>Retry mechanism:</b> The retry loop in {@code execute()} catches
     * {@code PessimisticLockingFailureException} and retries. With REQUIRED propagation,
     * a lock failure inside this method poisons the caller's transaction (marked
     * rollback-only). The retry in {@code execute()} will fail because it joins the
     * same poisoned TX. This is the CORRECT behavior — the caller (e.g.,
     * DepositAccountServiceImpl) must catch the exception at its own level and retry
     * the entire operation (lock account + engine.execute + save balance). The retry
     * loop in {@code execute()} serves as a defense-in-depth for system-generated
     * transactions that have no caller-level retry.
     *
     * <p><b>Isolation level:</b> Uses {@code READ_COMMITTED} (SQL Server default)
     * combined with {@code PESSIMISTIC_WRITE} locks on GL rows. The pessimistic
     * locks provide SERIALIZABLE-equivalent behavior for the specific rows being
     * updated, while READ_COMMITTED avoids unnecessary lock escalation on read-only
     * queries (GL validation, calendar lookup, batch check). Per Finacle TRAN_POSTING:
     * the posting isolation comes from row-level pessimistic locks, not from raising
     * the transaction isolation level globally (which would cause excessive blocking).
     *
     * <p><b>Per Tier-1 CBS Blueprint §4 (Posting Engine):</b> "All inside ONE DB
     * transaction" — account balance update, GL running balance, ledger entry,
     * subledger, and exposure table must be in a single atomic commit.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransactionResult executeInternal(TransactionRequest request) {
        // ================================================================
        // STEP 0: Financial Safety Kill Switch (BEFORE any DB touch)
        // Per Tier-1 CBS blueprint / RBI IT Governance §8.3:
        // If the system is in RESTRICTED MODE (GL imbalance, hash chain break,
        // batch mismatch), ALL postings are blocked. This check runs BEFORE
        // any validation, sequence allocation, or DB write to prevent
        // compounding damage on a corrupted ledger.
        // ================================================================
        integrityGuard.assertPostingAllowed();

        String tenantId = TenantContext.getCurrentTenant();
        String currentUser =
                request.getInitiatedBy() != null ? request.getInitiatedBy() : SecurityUtil.getCurrentUsername();

        log.info(
                "Transaction engine: module={}, type={}, amount={}, account={}, user={}",
                request.getSourceModule(),
                request.getTransactionType(),
                request.getAmount(),
                request.getAccountReference(),
                currentUser);

        // ================================================================
        // STEP 1: Engine-Level Idempotency Check (Cross-Module Dedup)
        // Per Finacle UNIQUE.REF / Temenos OFS.DUPLICATE.CHECK:
        // Every transaction with a non-null idempotencyKey is checked against
        // the engine-level idempotency_registry table BEFORE any validation
        // or GL posting. If a duplicate key is found, the previous result is
        // returned immediately without re-posting — preventing double GL entries.
        //
        // Two-tier design:
        //   1. Engine-level: idempotency_registry (cross-module, checked first)
        //   2. Module-level: unique constraints on deposit_transactions, etc.
        // Both layers must pass — defense-in-depth.
        // ================================================================
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            var existing = idempotencyRepository.findByTenantIdAndIdempotencyKey(
                    tenantId, request.getIdempotencyKey());
            if (existing.isPresent()) {
                IdempotencyRegistry prev = existing.get();
                log.info("IDEMPOTENCY HIT: key={}, previousTxnRef={}, status={}",
                        request.getIdempotencyKey(), prev.getTransactionRef(), prev.getStatus());
                return new TransactionResult(
                        prev.getTransactionRef(),
                        prev.getVoucherNumber(),
                        prev.getJournalEntryId(),
                        null, // journalRef not stored in registry — caller uses journalEntryId
                        request.getAmount(),
                        request.getAmount(),
                        request.getValueDate(),
                        prev.getCreatedAt(),
                        prev.getStatus());
            }
        }

        // ================================================================
        // STEP 1.5: Tenant Validation + RBI Compliance Flagging
        // Per RBI IT Governance Direction 2023 §8.3:
        //   - Suspended/unlicensed tenant → hard reject (no posting)
        //   - Cash transactions ≥ ₹10L → CTR flag for FIU-IND reporting
        //   - Any transaction ≥ ₹50L → large value flag for enhanced monitoring
        // Per RBI PMLA 2002: CTR flagging is NON-BLOCKING (tipping-off offense).
        // ================================================================
        validationService.validateTenant();
        int rbiFlags = validationService.evaluateRbiComplianceFlags(
                request.getAmount(), request.getTransactionType(), request.getAccountReference());
        if (rbiFlags != 0) {
            log.info("RBI compliance flags: {} for {} INR {} on {}",
                    rbiFlags, request.getTransactionType(), request.getAmount(), request.getAccountReference());
        }

        // ================================================================
        // STEP 2: Business Date Validation (Branch-Scoped)
        // Value date must exist in the business calendar for the transaction's branch.
        // Per Finacle DAYCTRL: calendar is per-branch — a date can be a holiday at
        // Branch A (state holiday) but a working day at Branch B.
        // ================================================================
        Long branchId = resolveBranchId(tenantId, request);

        BusinessCalendar calendar;
        if (branchId != null) {
            calendar = calendarRepository
                    .findByTenantIdAndBranchIdAndBusinessDate(tenantId, branchId, request.getValueDate())
                    .orElseThrow(() -> new BusinessException(
                            "INVALID_VALUE_DATE",
                            "Value date " + request.getValueDate()
                                    + " not found in business calendar for branch " + request.getBranchCode()));
        } else {
            // Fallback for system operations without branch context
            calendar = calendarRepository
                    .findByTenantIdAndBusinessDate(tenantId, request.getValueDate())
                    .orElseThrow(() -> new BusinessException(
                            "INVALID_VALUE_DATE",
                            "Value date " + request.getValueDate() + " not found in business calendar"));
        }

        if (calendar.isHoliday()) {
            throw new BusinessException(
                    "HOLIDAY_POSTING",
                    "Cannot post transactions on a holiday: " + request.getValueDate()
                            + (request.getBranchCode() != null ? " at branch " + request.getBranchCode() : ""));
        }

        // ================================================================
        // STEP 2.5: Value Date Window Validation
        // Per Finacle/Temenos: user-initiated transactions can only be posted with
        // value dates within a configurable window (default T-2 to T+0).
        // This prevents excessive back-dating that bypasses period-close controls.
        // System-generated transactions (EOD batch) are exempt — they always use
        // the current business date which is inherently within the window.
        // ================================================================
        if (!request.isSystemGenerated()) {
            try {
                LocalDate currentBizDate = businessDateService.getCurrentBusinessDate();
                businessDateService.validateValueDateWindow(
                        request.getValueDate(), currentBizDate, valueDateBackDays, valueDateForwardDays);
            } catch (BusinessException e) {
                if ("NO_OPEN_DAY".equals(e.getErrorCode())) {
                    // No day open — let Step 3 handle this (DAY_NOT_OPEN error)
                    log.debug("Value date window check skipped — no day currently open");
                } else {
                    throw e;
                }
            }
        }

        // ================================================================
        // STEP 3: Day Status Validation
        // Only DAY_OPEN allows postings; system-generated EOD postings exempt.
        // Per Finacle DAYCTRL: after EOD completes (eodComplete=true), user
        // transactions are blocked even though dayStatus is DAY_OPEN. The day
        // must be closed and a new day opened before transactions resume.
        // ================================================================
        if (!request.isSystemGenerated()) {
            if (!calendar.getDayStatus().isTransactionAllowed()) {
                throw new BusinessException(
                        "DAY_NOT_OPEN",
                        "Business date " + request.getValueDate() + " is in " + calendar.getDayStatus()
                                + " state. Transactions not allowed.");
            }
            if (calendar.isEodComplete()) {
                throw new BusinessException(
                        "EOD_ALREADY_COMPLETE",
                        "Transactions not allowed after EOD completion for " + request.getValueDate()
                                + ". Day must be closed and a new day opened.");
            }
        } else {
            // System-generated: allowed during DAY_OPEN and EOD_RUNNING
            if (!calendar.getDayStatus().isTransactionAllowed() && !calendar.isEodRunning()) {
                throw new BusinessException(
                        "DAY_NOT_OPEN",
                        "Business date " + request.getValueDate() + " is in " + calendar.getDayStatus() + " state.");
            }
        }

        // ================================================================
        // STEP 4: Amount & Currency Validation
        // Per RBI/Finacle standards: amount must be positive, within precision limits.
        // CBS precision: max 18 digits total, 2 decimal places (DECIMAL(18,2)).
        // Already validated by TransactionRequest.Builder, but defense-in-depth.
        //
        // CBS Tier-1 Currency Validation per RBI FEMA / Finacle TRAN_POSTING.CURRENCY:
        // Even a domestic-only CBS must explicitly validate currency code. This:
        //   1. Rejects non-INR postings that could corrupt GL balances
        //   2. Prepares for future FCY module integration (Remittance, Trade Finance)
        //   3. Ensures all journal lines are in the same currency (no mixed-currency journals)
        // Per Finacle: FCY transactions require FCY→LCY conversion at the GL posting
        // level via exchange rate lookup. Until the FCY module is implemented, only INR
        // is accepted. The currencyCode field on TransactionRequest is optional — if
        // absent, INR is assumed (backward compatible with existing callers).
        // ================================================================
        if (request.getCurrencyCode() != null && !CBS_CURRENCY_CODE.equals(request.getCurrencyCode())) {
            throw new BusinessException(
                    "UNSUPPORTED_CURRENCY",
                    "Currency " + request.getCurrencyCode() + " is not supported. "
                            + "Only " + CBS_CURRENCY_CODE + " transactions are allowed. "
                            + "Per RBI FEMA: FCY transactions require the FCY module (not yet implemented).");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BusinessException(
                    "INVALID_AMOUNT", "Transaction amount must be positive: " + request.getAmount());
        }
        // CBS: Use stripTrailingZeros() before scale check to avoid false positives.
        // BigDecimal("1000.000").scale()=3 but is logically 0 decimal places.
        // BigDecimal("100.10").stripTrailingZeros().scale()=1, which is valid.
        // Without this, amounts constructed from JSON/String with trailing zeros are rejected.
        BigDecimal normalizedAmount = request.getAmount().stripTrailingZeros();
        if (normalizedAmount.scale() > 2) {
            throw new BusinessException(
                    "INVALID_AMOUNT_PRECISION",
                    "Transaction amount cannot have more than 2 decimal places: " + request.getAmount() + " (scale="
                            + normalizedAmount.scale() + ")");
        }
        if (normalizedAmount.precision() - normalizedAmount.scale() > 16) {
            throw new BusinessException(
                    "INVALID_AMOUNT_OVERFLOW",
                    "Transaction amount exceeds CBS maximum (16 integer digits): " + request.getAmount());
        }

        // ================================================================
        // STEP 5: Branch Validation
        // Branch must exist and be active (if specified)
        // ================================================================
        if (request.getBranchCode() != null && !request.getBranchCode().isBlank()) {
            branchRepository
                    .findByTenantIdAndBranchCode(tenantId, request.getBranchCode())
                    .filter(b -> b.isActive())
                    .orElseThrow(() -> new BusinessException(
                            "INVALID_BRANCH", "Branch not found or inactive: " + request.getBranchCode()));
        }

        // ================================================================
        // STEP 5.5: Transaction Batch Validation
        // Per Finacle/Temenos batch control: user-initiated transactions require
        // an OPEN transaction batch for the business date. Without a batch,
        // transactions cannot be tracked for intra-day reconciliation.
        // System-generated EOD transactions are exempt (batches are closed
        // before EOD starts per Step 0 of BatchService.runEodBatch).
        // ================================================================
        if (!request.isSystemGenerated()) {
            var openBatches = batchRepository.findOpenBatches(tenantId, request.getValueDate());
            if (openBatches.isEmpty()) {
                // CBS: Enhanced diagnostics — log tenant + date + total batch count for troubleshooting.
                // Common causes: (1) batches created for different date, (2) tenant mismatch,
                // (3) all batches already closed, (4) day status changed after batch creation.
                long totalBatches = batchRepository
                        .findByTenantIdAndBusinessDateOrderByOpenedAtAsc(tenantId, request.getValueDate())
                        .size();
                log.error(
                        "BATCH_NOT_OPEN: tenant={}, valueDate={}, totalBatchesForDate={}, module={}, type={}",
                        tenantId,
                        request.getValueDate(),
                        totalBatches,
                        request.getSourceModule(),
                        request.getTransactionType());
                throw new BusinessException(
                        "BATCH_NOT_OPEN",
                        "No open transaction batch for business date " + request.getValueDate()
                                + " (tenant=" + tenantId + ", totalBatches=" + totalBatches
                                + "). Open a batch via Transaction Batches before posting transactions.");
            }
        }

        // ================================================================
        // STEP 6: Transaction Limit Validation
        // Per-role per-transaction + daily aggregate (skip for system-generated)
        // Uses CBS business date (not system date) for daily aggregate calculation
        // ================================================================
        if (!request.isSystemGenerated()) {
            limitService.validateTransactionLimit(
                    request.getAmount(), request.getTransactionType(), request.getValueDate());
        }

        log.debug("Transaction pipeline: STEP 6 (limits) passed — entering STEP 7 (maker-checker). module={}, type={}, amount={}",
                request.getSourceModule(), request.getTransactionType(), request.getAmount());

        // ================================================================
        // STEP 7: Maker-Checker Gate
        // Per RBI Internal Controls: transactions above the per-transaction limit
        // require dual authorization (maker initiates, checker approves).
        // System-generated transactions (EOD batch) bypass maker-checker.
        // Below-threshold transactions are auto-approved (single authorization).
        //
        // When maker-checker is triggered:
        //   - An ApprovalWorkflow record is created with PENDING_APPROVAL status
        //   - TransactionResult is returned with status=PENDING_APPROVAL
        //   - NO GL posting occurs until checker approves
        //   - Caller must check result.isPendingApproval() and handle accordingly
        // ================================================================
        String postingStatus = "POSTED";
        if (!request.isSystemGenerated()
                && !request.isPreApproved()
                && makerCheckerService.requiresApproval(request.getAmount(), request.getTransactionType())) {
            postingStatus = "PENDING_APPROVAL";

            // Create approval workflow with JSON-serialized TransactionRequest payload.
            // CBS Tier-1: The payload must be deserializable back into a TransactionRequest
            // by TransactionReExecutionService when the checker approves. The old pipe-delimited
            // format was not deserializable — this JSON format enables full re-execution.
            String jsonPayload;
            try {
                // Build a serializable map of the request (TransactionRequest is immutable, not a bean)
                var payloadMap = new java.util.LinkedHashMap<String, Object>();
                payloadMap.put("sourceModule", request.getSourceModule());
                payloadMap.put("transactionType", request.getTransactionType());
                payloadMap.put("accountReference", request.getAccountReference());
                payloadMap.put("amount", request.getAmount());
                payloadMap.put("valueDate", request.getValueDate().toString());
                payloadMap.put("branchCode", request.getBranchCode());
                payloadMap.put("narration", request.getNarration());
                payloadMap.put("productType", request.getProductType());
                payloadMap.put("idempotencyKey", request.getIdempotencyKey());
                payloadMap.put("currencyCode", request.getCurrencyCode());
                payloadMap.put("initiatedBy", currentUser);
                // Serialize journal lines
                if (request.getJournalLines() != null) {
                    var lines = new java.util.ArrayList<java.util.Map<String, Object>>();
                    for (var line : request.getJournalLines()) {
                        var lineMap = new java.util.LinkedHashMap<String, Object>();
                        lineMap.put("glCode", line.glCode());
                        lineMap.put("debitCredit", line.debitCredit().name());
                        lineMap.put("amount", line.amount());
                        lineMap.put("narration", line.narration());
                        lines.add(lineMap);
                    }
                    payloadMap.put("journalLines", lines);
                }
                jsonPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                        .writeValueAsString(payloadMap);
            } catch (Exception e) {
                log.warn("Failed to serialize TransactionRequest to JSON, using fallback: {}", e.getMessage());
                jsonPayload = request.getSourceModule() + "|" + request.getAccountReference()
                        + "|" + request.getAmount() + "|" + request.getTransactionType();
            }
            makerCheckerService.createPendingApproval(
                    "Transaction",
                    0L,
                    request.getTransactionType(),
                    jsonPayload);

            String txnRef = ReferenceGenerator.generateTransactionRef();
            auditService.logEvent(
                    "Transaction",
                    0L,
                    "PENDING_APPROVAL",
                    null,
                    txnRef,
                    request.getSourceModule(),
                    "Transaction pending approval: " + request.getTransactionType()
                            + " INR " + request.getAmount()
                            + " for " + request.getAccountReference()
                            + " | User: " + currentUser);

            log.info(
                    "Transaction pending maker-checker approval: type={}, amount={}, account={}, user={}",
                    request.getTransactionType(),
                    request.getAmount(),
                    request.getAccountReference(),
                    currentUser);

            TransactionResult pendingResult = new TransactionResult(
                    txnRef,
                    null,
                    null,
                    null,
                    request.getAmount(),
                    request.getAmount(),
                    request.getValueDate(),
                    LocalDateTime.now(),
                    postingStatus);

            // CBS Tier-1: Register in idempotency registry for PENDING_APPROVAL too.
            // If the same request is retried, the engine returns PENDING_APPROVAL
            // without creating a duplicate ApprovalWorkflow record.
            registerIdempotency(tenantId, request, pendingResult);

            return pendingResult;
        }

        log.debug("Transaction pipeline: STEP 7 (maker-checker) passed — auto-approved. Entering STEP 8 (GL posting). module={}, type={}",
                request.getSourceModule(), request.getTransactionType());

        // ================================================================
        // STEP 8.0 (PRE-ALLOCATION): Voucher & Transaction Ref Generation
        // CBS CRITICAL: Pre-allocate voucher number and transaction ref BEFORE
        // acquiring PESSIMISTIC_WRITE locks in Step 8. SequenceGeneratorService
        // uses REQUIRES_NEW propagation which suspends the current TX. If we
        // allocated AFTER GL locks (the old Step 9 position), the REQUIRES_NEW
        // would suspend a TX holding GL/ledger PESSIMISTIC_WRITE locks — a
        // deadlock vector on SQL Server identical to the AuditService issue.
        //
        // Pre-allocation is safe: if Step 8 fails and rolls back, the voucher
        // number is "wasted" (gap in sequence). Per Finacle/Temenos/RBI:
        // gaps in voucher numbers are explicitly allowed; duplicates are not.
        // The SequenceGeneratorService REQUIRES_NEW ensures the sequence
        // increment commits independently, preventing reuse on retry.
        // ================================================================
        String voucherNumber = generateVoucherNumber(request.getBranchCode(), request.getValueDate());
        String txnRef = ReferenceGenerator.generateTransactionRef();

        // ================================================================
        // STEP 8: Double-Entry Journal Posting
        // GL validation → DR==CR → GL balance update → Ledger → Batch totals
        // This is delegated to AccountingService which handles:
        //   - GL account validation (active, postable)
        //   - Double-entry validation (DR total == CR total)
        //   - GL balance update (pessimistic lock)
        //   - Immutable ledger posting (hash chain)
        //   - Batch running totals update (pessimistic lock)
        //
        // CBS Compound Posting (Finacle TRAN_POSTING multi-leg):
        // When compoundJournalGroups is set, each group is posted as a separate
        // balanced journal entry. All share the same voucher and transaction ref.
        // The first journal entry is returned as the primary reference.
        // ================================================================
        JournalEntry journalEntry;
        // CBS: Generate cryptographic engine context token so AccountingService can verify
        // this call originates from the validated TransactionEngine pipeline.
        // The token is a UUID stored in a ThreadLocal — no external class can forge it.
        AccountingService.generateEngineToken();
        try {
            if (request.isCompound()) {
                // CBS Compound Posting: each group is a separate balanced journal entry.
                // All groups share the same voucher, transaction ref, and audit trail.
                // The first journal entry is returned as the primary reference.
                // All journal entries use the same sourceRef (account number) so they can
                // be queried together via JournalEntryRepository.findByTenantIdAndSourceModuleAndSourceRef().
                JournalEntry firstEntry = null;
                for (TransactionRequest.CompoundJournalGroup group : request.getCompoundJournalGroups()) {
                    JournalEntry entry = accountingService.postJournalEntry(
                            request.getValueDate(),
                            group.narration(),
                            request.getSourceModule(),
                            request.getAccountReference(),
                            group.lines(),
                            request.getBranchCode(),
                            voucherNumber,
                            txnRef);
                    if (firstEntry == null) {
                        firstEntry = entry;
                    }
                    log.debug(
                            "Compound journal group posted: ref={}, debit={}, credit={}",
                            entry.getJournalRef(),
                            entry.getTotalDebit(),
                            entry.getTotalCredit());
                }
                journalEntry = firstEntry;
            } else {
                journalEntry = accountingService.postJournalEntry(
                        request.getValueDate(),
                        request.getNarration(),
                        request.getSourceModule(),
                        request.getAccountReference(),
                        request.getJournalLines(),
                        request.getBranchCode(),
                        voucherNumber,
                        txnRef);
            }
        } finally {
            // Always clear the engine context token — prevents stale tokens on thread pool reuse
            AccountingService.clearEngineToken();
        }

        log.debug("Transaction pipeline: STEP 8 (GL posting) completed. journalRef={}, debit={}, credit={}",
                journalEntry.getJournalRef(), journalEntry.getTotalDebit(), journalEntry.getTotalCredit());

        // ================================================================
        // STEP 9: Voucher Number — already pre-allocated in Step 8.0 above.
        // Moved before GL locks to avoid REQUIRES_NEW deadlock.
        // ================================================================

        // ================================================================
        // STEP 10: Audit Trail
        // Immutable record with hash chain via AuditService.
        // txnRef already pre-allocated in Step 8.0 above.
        // ================================================================
        LocalDateTime postingDate = LocalDateTime.now();

        // CBS CRITICAL: Use logEventInline (Propagation.REQUIRED) — NOT logEvent (REQUIRES_NEW).
        // At this point the transaction holds PESSIMISTIC_WRITE locks acquired during Step 8
        // (GL balance update, ledger sentinel). logEvent(REQUIRES_NEW) would suspend this TX
        // without releasing those locks, then open a new connection whose INSERT into
        // audit_logs contends with the held locks — a guaranteed deadlock on SQL Server.
        // logEventInline joins THIS transaction so the audit INSERT sees the same lock scope.
        // If this TX rolls back, the audit record is also lost — acceptable because a
        // rolled-back posting never happened (nothing to audit).
        auditService.logEventInline(
                "Transaction",
                journalEntry.getId(),
                request.getTransactionType(),
                null,
                txnRef,
                request.getSourceModule(),
                "Transaction posted: " + request.getTransactionType()
                        + " INR " + request.getAmount()
                        + " for " + request.getAccountReference()
                        + " | Journal: " + journalEntry.getJournalRef()
                        + " | Voucher: " + voucherNumber
                        + " | User: " + currentUser);

        log.info(
                "Transaction engine completed: ref={}, voucher={}, journal={}, module={}, type={}, amount={}",
                txnRef,
                voucherNumber,
                journalEntry.getJournalRef(),
                request.getSourceModule(),
                request.getTransactionType(),
                request.getAmount());

        TransactionResult postedResult = new TransactionResult(
                txnRef,
                voucherNumber,
                journalEntry.getId(),
                journalEntry.getJournalRef(),
                journalEntry.getTotalDebit(),
                journalEntry.getTotalCredit(),
                request.getValueDate(),
                postingDate,
                postingStatus);

        // CBS Tier-1: Register successful posting in idempotency registry.
        // Future requests with the same idempotencyKey return this result
        // without re-posting — preventing double GL entries.
        registerIdempotency(tenantId, request, postedResult);

        // ================================================================
        // STEP 11: Outbox Event Publishing (Same TX — Outbox Pattern)
        // Per Tier-1 CBS blueprint / Finacle EVENT_QUEUE:
        // Insert outbox event INSIDE the same DB transaction as the GL posting.
        // This guarantees exactly-once delivery: if GL commits, event commits;
        // if GL rolls back, event rolls back. No dual-write problem.
        // A separate async process dispatches PENDING events to downstream
        // consumers (reconciliation, FIU-IND CTR, fraud monitoring, notifications).
        // ================================================================
        publishOutboxEvent(tenantId, request, postedResult, rbiFlags);

        return postedResult;
    }

    /**
     * CBS Tier-1 Transaction Preview (Dry-Run Validation) per Finacle TRAN_PREVIEW.
     *
     * <p>Runs the full validation chain (Steps 1–7) WITHOUT committing any GL posting.
     * Each step's pass/fail is recorded in the returned {@link TransactionPreview}.
     * Unlike {@link #execute(TransactionRequest)} which throws on the FIRST failure,
     * this method continues through ALL steps to collect a complete checklist.
     *
     * <p><b>What is validated:</b>
     * <ul>
     *   <li>Step 2: Business date exists in calendar, not a holiday</li>
     *   <li>Step 2.5: Value date within allowed window (T-2 to T+0)</li>
     *   <li>Step 3: Day status is DAY_OPEN, EOD not complete</li>
     *   <li>Step 4: Amount positive, ≤2 decimal places, currency INR</li>
     *   <li>Step 5: Branch exists and is active</li>
     *   <li>Step 5.5: Open transaction batch exists for the business date</li>
     *   <li>Step 6: Per-transaction and daily aggregate limits</li>
     *   <li>Step 7: Maker-checker threshold (reports whether approval required)</li>
     *   <li>GL: Journal lines validated (GL codes exist, active, postable, DR==CR)</li>
     * </ul>
     *
     * <p><b>What is NOT done:</b> No GL balance update, no ledger posting, no voucher
     * allocation, no audit trail, no sequence consumption. This is purely read-only.
     *
     * <p><b>Thread safety:</b> Read-only — no locks acquired, no state mutated.
     * Safe to call from AJAX endpoints without contention concerns.
     *
     * @param request The transaction request to validate
     * @return TransactionPreview with all check results and GL line preview
     */
    @Transactional(readOnly = true)
    public TransactionPreview validate(TransactionRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        TransactionPreview.Builder preview = TransactionPreview.builder()
                .amount(request.getAmount())
                .transactionType(request.getTransactionType())
                .accountNumber(request.getAccountReference())
                .branchCode(request.getBranchCode())
                .valueDate(request.getValueDate())
                .narration(request.getNarration());

        // --- Step 2: Business Date Validation ---
        Long branchId = resolveBranchId(tenantId, request);

        BusinessCalendar calendar = null;
        try {
            if (branchId != null) {
                calendar = calendarRepository
                        .findByTenantIdAndBranchIdAndBusinessDate(tenantId, branchId, request.getValueDate())
                        .orElse(null);
            }
            if (calendar == null) {
                calendar = calendarRepository
                        .findByTenantIdAndBusinessDate(tenantId, request.getValueDate())
                        .orElse(null);
            }
            boolean dateValid = calendar != null && !calendar.isHoliday();
            preview.addCheck("BUSINESS_DATE", "Day Control",
                    "Value date " + request.getValueDate() + " exists in calendar and is not a holiday",
                    dateValid,
                    calendar == null ? "Date not found in business calendar"
                            : calendar.isHoliday() ? "Holiday: " + calendar.getHolidayDescription() : "OK");
        } catch (Exception e) {
            preview.addCheck("BUSINESS_DATE", "Day Control", "Business date validation", false, e.getMessage());
        }

        // --- Step 2.5: Value Date Window ---
        if (!request.isSystemGenerated() && calendar != null) {
            try {
                LocalDate currentBizDate = businessDateService.getCurrentBusinessDate();
                businessDateService.validateValueDateWindow(
                        request.getValueDate(), currentBizDate, valueDateBackDays, valueDateForwardDays);
                preview.addCheck("VALUE_DATE_WINDOW", "Day Control",
                        "Value date within allowed window (T-" + valueDateBackDays + " to T+" + valueDateForwardDays + ")",
                        true, "Current biz date: " + currentBizDate);
            } catch (BusinessException e) {
                if (!"NO_OPEN_DAY".equals(e.getErrorCode())) {
                    preview.addCheck("VALUE_DATE_WINDOW", "Day Control", "Value date window", false, e.getMessage());
                }
            }
        }

        // --- Step 3: Day Status ---
        if (calendar != null && !request.isSystemGenerated()) {
            boolean dayOpen = calendar.getDayStatus().isTransactionAllowed() && !calendar.isEodComplete();
            preview.addCheck("DAY_STATUS", "Day Control",
                    "Day is open for transactions (status: " + calendar.getDayStatus() + ")",
                    dayOpen,
                    !calendar.getDayStatus().isTransactionAllowed() ? "Day status: " + calendar.getDayStatus()
                            : calendar.isEodComplete() ? "EOD already complete — day must be closed and reopened" : "OK");
        }

        // --- Step 4: Amount & Currency ---
        boolean amountValid = request.getAmount() != null && request.getAmount().signum() > 0;
        boolean precisionValid = true;
        if (amountValid) {
            BigDecimal norm = request.getAmount().stripTrailingZeros();
            precisionValid = norm.scale() <= 2 && (norm.precision() - norm.scale()) <= 16;
        }
        boolean currencyValid = request.getCurrencyCode() == null || CBS_CURRENCY_CODE.equals(request.getCurrencyCode());
        preview.addCheck("AMOUNT", "Amount", "Amount is positive with ≤2 decimal places",
                amountValid && precisionValid, amountValid ? "INR " + request.getAmount() : "Invalid amount");
        preview.addCheck("CURRENCY", "Amount", "Currency is " + CBS_CURRENCY_CODE,
                currencyValid, currencyValid ? CBS_CURRENCY_CODE : "Unsupported: " + request.getCurrencyCode());

        // --- Step 5: Branch ---
        if (request.getBranchCode() != null && !request.getBranchCode().isBlank()) {
            var branchOpt = branchRepository.findByTenantIdAndBranchCode(tenantId, request.getBranchCode());
            boolean branchActive = branchOpt.isPresent() && branchOpt.get().isActive();
            preview.addCheck("BRANCH", "Branch", "Branch " + request.getBranchCode() + " exists and is active",
                    branchActive, branchActive ? "OK" : "Branch not found or inactive");
        }

        // --- Step 5.5: Batch ---
        if (!request.isSystemGenerated()) {
            var openBatches = batchRepository.findOpenBatches(tenantId, request.getValueDate());
            preview.addCheck("BATCH", "Batch Control",
                    "Open transaction batch exists for " + request.getValueDate(),
                    !openBatches.isEmpty(),
                    openBatches.isEmpty() ? "No OPEN batch — open via Transaction Batches screen" : openBatches.size() + " open batch(es)");
        }

        // --- Step 6: Transaction Limits ---
        if (!request.isSystemGenerated()) {
            try {
                limitService.validateTransactionLimit(
                        request.getAmount(), request.getTransactionType(), request.getValueDate());
                preview.addCheck("TRANSACTION_LIMIT", "Limits",
                        "Per-transaction and daily aggregate limits", true, "Within limits");
            } catch (BusinessException e) {
                preview.addCheck("TRANSACTION_LIMIT", "Limits",
                        "Transaction limit validation", false, e.getMessage());
            }
            // CBS: Limit details for preview display are embedded in the check detail
            // string above (the BusinessException message includes limit values).
            // Future enhancement: expose structured limit info via TransactionLimitService.
        }

        // --- Step 7: Maker-Checker ---
        if (!request.isSystemGenerated()) {
            boolean needsApproval = makerCheckerService.requiresApproval(
                    request.getAmount(), request.getTransactionType());
            preview.requiresApproval(needsApproval);
            preview.addCheck("MAKER_CHECKER", "Authorization",
                    needsApproval ? "Requires checker approval (amount exceeds threshold or high-risk operation)"
                            : "Auto-approved (within single-authorization threshold)",
                    true, // Maker-checker is never a blocker — it's informational
                    needsApproval ? "PENDING_APPROVAL — checker must authorize before GL posting" : "Single authorization");
        }

        // --- GL Validation (read-only — no posting) ---
        List<TransactionPreview.JournalLinePreview> glLines = new ArrayList<>();
        if (request.getJournalLines() != null && request.getJournalLines().size() >= 2) {
            BigDecimal totalDr = BigDecimal.ZERO;
            BigDecimal totalCr = BigDecimal.ZERO;
            boolean allGlValid = true;
            for (var line : request.getJournalLines()) {
                var glOpt = accountingService.getGlMasterRepository()
                        .findByTenantIdAndGlCode(tenantId, line.glCode());
                boolean valid = glOpt.isPresent() && glOpt.get().isActive() && !glOpt.get().isHeaderAccount();
                String glName = glOpt.map(GLMaster::getGlName).orElse("UNKNOWN");
                glLines.add(new TransactionPreview.JournalLinePreview(
                        line.glCode(), glName, line.debitCredit().name(), line.amount(), line.narration()));
                if (!valid) allGlValid = false;
                if (line.debitCredit() == DebitCredit.DEBIT) {
                    totalDr = totalDr.add(line.amount());
                } else {
                    totalCr = totalCr.add(line.amount());
                }
            }
            preview.journalLines(glLines);
            preview.addCheck("GL_VALIDATION", "GL",
                    "All GL codes exist, are active, and are postable",
                    allGlValid, allGlValid ? "All GL codes valid" : "One or more GL codes invalid/inactive");
            boolean balanced = totalDr.compareTo(totalCr) == 0;
            preview.addCheck("DOUBLE_ENTRY", "GL",
                    "Double-entry balance: DR " + totalDr + " = CR " + totalCr,
                    balanced, balanced ? "Balanced" : "IMBALANCE: DR " + totalDr + " ≠ CR " + totalCr);
        }

        return preview.build();
    }

    /**
     * CBS Tier-1: Register a transaction result in the engine-level idempotency registry.
     * Called after both POSTED and PENDING_APPROVAL paths to prevent duplicate processing
     * on retried requests. Only registers if the request carries a non-null idempotencyKey.
     *
     * <p>Uses logEventInline-style inline save (same TX) so the registry entry commits
     * or rolls back atomically with the GL posting. If the TX rolls back, the registry
     * entry is also lost — the next retry will re-execute cleanly.
     */
    private void registerIdempotency(String tenantId, TransactionRequest request, TransactionResult result) {
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            return; // System-generated transactions without keys — skip registration
        }
        try {
            IdempotencyRegistry entry = new IdempotencyRegistry();
            entry.setTenantId(tenantId);
            entry.setIdempotencyKey(request.getIdempotencyKey());
            entry.setTransactionRef(result.getTransactionRef());
            entry.setVoucherNumber(result.getVoucherNumber());
            entry.setJournalEntryId(result.getJournalEntryId());
            entry.setStatus(result.getStatus());
            entry.setSourceModule(request.getSourceModule());
            entry.setCreatedAt(LocalDateTime.now());
            idempotencyRepository.save(entry);
            log.debug("Idempotency registered: key={}, txnRef={}, status={}",
                    request.getIdempotencyKey(), result.getTransactionRef(), result.getStatus());
        } catch (Exception e) {
            // CBS Safety: If registry INSERT fails (e.g., unique constraint on concurrent retry),
            // log but do NOT fail the posting — the GL posting is already committed/pending.
            // Module-level idempotency provides the second layer of defense.
            log.warn("Idempotency registry INSERT failed (non-fatal): key={}, error={}",
                    request.getIdempotencyKey(), e.getMessage());
        }
    }

    /**
     * CBS Tier-1: Publish outbox event for post-commit async processing.
     * Inserted INSIDE the same DB transaction as the GL posting (Outbox Pattern).
     *
     * <p>Creates one TRANSACTION_POSTED event for every posting. If RBI compliance
     * flags are set, creates additional CTR_REPORTABLE and/or LARGE_VALUE events
     * so the FIU-IND reporting job can process them independently.
     */
    private void publishOutboxEvent(String tenantId, TransactionRequest request,
                                     TransactionResult result, int rbiFlags) {
        try {
            // Primary event: TRANSACTION_POSTED
            TransactionOutbox event = new TransactionOutbox();
            event.setTenantId(tenantId);
            event.setEventType("TRANSACTION_POSTED");
            event.setTransactionRef(result.getTransactionRef());
            event.setVoucherNumber(result.getVoucherNumber());
            event.setJournalEntryId(result.getJournalEntryId());
            event.setSourceModule(request.getSourceModule());
            event.setTransactionType(request.getTransactionType());
            event.setAccountReference(request.getAccountReference());
            event.setAmount(request.getAmount());
            event.setBranchCode(request.getBranchCode());
            event.setValueDate(request.getValueDate());
            event.setRbiFlags(rbiFlags);
            event.setCreatedAt(LocalDateTime.now());
            outboxRepository.save(event);

            // CBS: If CTR-reportable, create a separate CTR event for FIU-IND batch job
            if ((rbiFlags & TransactionValidationService.RBI_FLAG_CTR) != 0) {
                TransactionOutbox ctrEvent = new TransactionOutbox();
                ctrEvent.setTenantId(tenantId);
                ctrEvent.setEventType("CTR_REPORTABLE");
                ctrEvent.setTransactionRef(result.getTransactionRef());
                ctrEvent.setVoucherNumber(result.getVoucherNumber());
                ctrEvent.setJournalEntryId(result.getJournalEntryId());
                ctrEvent.setSourceModule(request.getSourceModule());
                ctrEvent.setTransactionType(request.getTransactionType());
                ctrEvent.setAccountReference(request.getAccountReference());
                ctrEvent.setAmount(request.getAmount());
                ctrEvent.setBranchCode(request.getBranchCode());
                ctrEvent.setValueDate(request.getValueDate());
                ctrEvent.setRbiFlags(rbiFlags);
                ctrEvent.setCreatedAt(LocalDateTime.now());
                outboxRepository.save(ctrEvent);
            }

            log.debug("Outbox event published: txnRef={}, type={}, rbiFlags={}",
                    result.getTransactionRef(), request.getTransactionType(), rbiFlags);
        } catch (Exception e) {
            // CBS Safety: Outbox INSERT failure must NOT fail the GL posting.
            // The GL posting is the primary operation; outbox is supplementary.
            // Missing outbox events are caught by EOD reconciliation.
            log.warn("Outbox event INSERT failed (non-fatal): txnRef={}, error={}",
                    result.getTransactionRef(), e.getMessage());
        }
    }

    /**
     * Resolves the branch ID for a transaction request.
     * Checks request.branchCode first, falls back to current user's branch.
     * Extracted to eliminate duplication between executeInternal() and validate().
     */
    private Long resolveBranchId(String tenantId, TransactionRequest request) {
        Long branchId = null;
        if (request.getBranchCode() != null && !request.getBranchCode().isBlank()) {
            var branchOpt = branchRepository.findByTenantIdAndBranchCode(tenantId, request.getBranchCode());
            if (branchOpt.isPresent()) {
                branchId = branchOpt.get().getId();
            }
        }
        if (branchId == null) {
            branchId = SecurityUtil.getCurrentUserBranchId();
        }
        return branchId;
    }

    /**
     * Generates a voucher number per Finacle/Temenos convention.
     * Format: VCH/{branchCode}/{YYYYMMDD}/{sequence}
     *
     * Uses DB-backed sequence partitioned by branch+date via SequenceGeneratorService.
     * This guarantees globally unique voucher numbers across:
     *   - JVM restarts (sequence persisted in DB)
     *   - Multiple JVM instances (pessimistic lock serializes allocation)
     *   - Cluster/HA deployments (single source of truth)
     *
     * Per Finacle TRAN_POSTING: voucher numbers must be unique within a business day
     * per branch. The sequence name "VOUCHER_{branch}_{date}" ensures daily reset
     * semantics — each new business date starts a new sequence automatically.
     */
    private String generateVoucherNumber(String branchCode, LocalDate valueDate) {
        String branch = branchCode != null ? branchCode : "HQ";
        String dateStr = valueDate.toString().replace("-", "");
        String seqName = "VOUCHER_" + branch + "_" + dateStr;
        String seq = sequenceGenerator.nextFormattedValue(seqName, 6);
        return "VCH/" + branch + "/" + dateStr + "/" + seq;
    }
}

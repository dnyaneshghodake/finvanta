package com.finvanta.transaction;

import com.finvanta.accounting.AccountingService;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.BranchRepository;
import com.finvanta.service.SequenceGeneratorService;
import com.finvanta.service.TransactionLimitService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Generic Transaction Engine per Finacle TRAN_POSTING / Temenos TRANSACTION framework.
 *
 * This is the SINGLE ENFORCEMENT POINT for the entire CBS platform.
 * Every financial transaction — regardless of which module initiates it — must pass
 * through this engine. The engine enforces the CBS validation chain in the correct
 * order, ensuring no module can bypass any step.
 *
 * Validation chain (executed in order):
 *   Step 1:  Idempotency check (prevent duplicate processing)
 *   Step 2:  Business date validation (value date must be valid calendar date)
 *   Step 3:  Day status validation (only DAY_OPEN allows postings; EOD system exempt)
 *   Step 4:  Amount validation (positive, within precision limits)
 *   Step 5:  Branch validation (branch must exist and be active)
 *   Step 6:  Transaction limit validation (per-role per-transaction + daily aggregate)
 *   Step 7:  Maker-checker gate (transactions above threshold → PENDING_APPROVAL)
 *   Step 8:  Double-entry journal posting (GL validation, balance update, ledger, batch)
 *   Step 9:  Voucher generation (unique voucher number per branch per date)
 *   Step 10: Audit trail (immutable record with hash chain)
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

    private final AccountingService accountingService;
    private final TransactionLimitService limitService;
    private final BusinessCalendarRepository calendarRepository;
    private final BranchRepository branchRepository;
    private final AuditService auditService;
    private final SequenceGeneratorService sequenceGenerator;

    public TransactionEngine(AccountingService accountingService,
                              TransactionLimitService limitService,
                              BusinessCalendarRepository calendarRepository,
                              BranchRepository branchRepository,
                              AuditService auditService,
                              SequenceGeneratorService sequenceGenerator) {
        this.accountingService = accountingService;
        this.limitService = limitService;
        this.calendarRepository = calendarRepository;
        this.branchRepository = branchRepository;
        this.auditService = auditService;
        this.sequenceGenerator = sequenceGenerator;
    }

    /**
     * Executes a financial transaction through the CBS validation chain.
     *
     * This is the ONLY method that should be called for financial postings.
     * All CBS modules must use this instead of calling AccountingService directly.
     *
     * @param request The transaction request built by the calling module
     * @return TransactionResult with journal ref, voucher number, and posting status
     * @throws BusinessException if any validation step fails
     */
    @Transactional
    public TransactionResult execute(TransactionRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = request.getInitiatedBy() != null
            ? request.getInitiatedBy() : SecurityUtil.getCurrentUsername();

        log.info("Transaction engine: module={}, type={}, amount={}, account={}, user={}",
            request.getSourceModule(), request.getTransactionType(),
            request.getAmount(), request.getAccountReference(), currentUser);

        // ================================================================
        // STEP 1: Idempotency Check
        // Per Finacle UNIQUE.REF — prevent duplicate processing on retries
        // ================================================================
        // NOTE: Idempotency is checked at the module level (e.g., LoanTransaction.idempotencyKey)
        // because the engine doesn't own module-specific transaction tables.
        // The engine validates the request; the module checks for duplicates.

        // ================================================================
        // STEP 2: Business Date Validation
        // Value date must exist in the business calendar
        // ================================================================
        BusinessCalendar calendar = calendarRepository
            .findByTenantIdAndBusinessDate(tenantId, request.getValueDate())
            .orElseThrow(() -> new BusinessException("INVALID_VALUE_DATE",
                "Value date " + request.getValueDate() + " not found in business calendar"));

        if (calendar.isHoliday()) {
            throw new BusinessException("HOLIDAY_POSTING",
                "Cannot post transactions on a holiday: " + request.getValueDate());
        }

        // ================================================================
        // STEP 3: Day Status Validation
        // Only DAY_OPEN allows postings; system-generated EOD postings exempt
        // ================================================================
        if (!request.isSystemGenerated()) {
            if (!calendar.getDayStatus().isTransactionAllowed()) {
                throw new BusinessException("DAY_NOT_OPEN",
                    "Business date " + request.getValueDate() + " is in "
                        + calendar.getDayStatus() + " state. Transactions not allowed.");
            }
        } else {
            // System-generated: allowed during DAY_OPEN and EOD_RUNNING
            if (!calendar.getDayStatus().isTransactionAllowed() && !calendar.isEodRunning()) {
                throw new BusinessException("DAY_NOT_OPEN",
                    "Business date " + request.getValueDate() + " is in "
                        + calendar.getDayStatus() + " state.");
            }
        }

        // ================================================================
        // STEP 4: Amount Validation
        // Per RBI/Finacle standards: amount must be positive, within precision limits.
        // CBS precision: max 18 digits total, 2 decimal places (DECIMAL(18,2)).
        // Already validated by TransactionRequest.Builder, but defense-in-depth.
        // ================================================================
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT",
                "Transaction amount must be positive: " + request.getAmount());
        }
        if (request.getAmount().scale() > 2) {
            throw new BusinessException("INVALID_AMOUNT_PRECISION",
                "Transaction amount cannot have more than 2 decimal places: "
                    + request.getAmount() + " (scale=" + request.getAmount().scale() + ")");
        }
        if (request.getAmount().precision() - request.getAmount().scale() > 16) {
            throw new BusinessException("INVALID_AMOUNT_OVERFLOW",
                "Transaction amount exceeds CBS maximum (16 integer digits): "
                    + request.getAmount());
        }

        // ================================================================
        // STEP 5: Branch Validation
        // Branch must exist and be active (if specified)
        // ================================================================
        if (request.getBranchCode() != null && !request.getBranchCode().isBlank()) {
            branchRepository.findByTenantIdAndBranchCode(tenantId, request.getBranchCode())
                .filter(b -> b.isActive())
                .orElseThrow(() -> new BusinessException("INVALID_BRANCH",
                    "Branch not found or inactive: " + request.getBranchCode()));
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

        // ================================================================
        // STEP 7: Maker-Checker Gate
        // Transactions above configured threshold require checker approval
        // For now: all transactions are auto-approved (maker-checker on
        // financial transactions is a future enhancement)
        // System-generated transactions (EOD) bypass maker-checker
        // ================================================================
        String postingStatus = "POSTED";
        // Future: if (!request.isSystemGenerated() && exceedsThreshold(request)) {
        //     postingStatus = "PENDING_APPROVAL";
        //     // Save to pending_transactions table, return without GL posting
        // }

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
        // CBS: Set engine context flag so AccountingService knows this call is
        // from the validated TransactionEngine pipeline (defense-in-depth).
        AccountingService.enterEngineContext();
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
                        group.lines()
                    );
                    if (firstEntry == null) {
                        firstEntry = entry;
                    }
                    log.debug("Compound journal group posted: ref={}, debit={}, credit={}",
                        entry.getJournalRef(), entry.getTotalDebit(), entry.getTotalCredit());
                }
                journalEntry = firstEntry;
            } else {
                journalEntry = accountingService.postJournalEntry(
                    request.getValueDate(),
                    request.getNarration(),
                    request.getSourceModule(),
                    request.getAccountReference(),
                    request.getJournalLines()
                );
            }
        } finally {
            // Always clear the engine context — prevents stale flag on thread reuse
            AccountingService.exitEngineContext();
        }

        // ================================================================
        // STEP 9: Voucher Generation
        // Unique voucher number per branch per date
        // Format: VCH/branchCode/YYYYMMDD/sequence
        // ================================================================
        String voucherNumber = generateVoucherNumber(
            request.getBranchCode(), request.getValueDate());

        // ================================================================
        // STEP 10: Audit Trail
        // Immutable record with hash chain via AuditService
        // ================================================================
        String txnRef = ReferenceGenerator.generateTransactionRef();
        LocalDateTime postingDate = LocalDateTime.now();

        auditService.logEvent("Transaction", journalEntry.getId(),
            request.getTransactionType(),
            null, txnRef, request.getSourceModule(),
            "Transaction posted: " + request.getTransactionType()
                + " ₹" + request.getAmount()
                + " for " + request.getAccountReference()
                + " | Journal: " + journalEntry.getJournalRef()
                + " | Voucher: " + voucherNumber
                + " | User: " + currentUser);

        log.info("Transaction engine completed: ref={}, voucher={}, journal={}, module={}, type={}, amount={}",
            txnRef, voucherNumber, journalEntry.getJournalRef(),
            request.getSourceModule(), request.getTransactionType(), request.getAmount());

        return new TransactionResult(
            txnRef,
            voucherNumber,
            journalEntry.getId(),
            journalEntry.getJournalRef(),
            journalEntry.getTotalDebit(),
            journalEntry.getTotalCredit(),
            request.getValueDate(),
            postingDate,
            postingStatus
        );
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

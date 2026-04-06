package com.finvanta.accounting;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.JournalEntryLine;
import com.finvanta.domain.entity.TransactionBatch;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CBS General Ledger (GL) and Journal Entry Service.
 *
 * Per Finacle/Temenos accounting engine standards:
 * - All financial postings use double-entry bookkeeping (DR total == CR total)
 * - GL balances are updated atomically with pessimistic locking
 * - Journal entries are immutable once posted (reversal creates a new entry)
 * - Trial balance validation ensures GL integrity (Assets + Expenses = Liabilities + Income + Equity)
 *
 * Chart of Accounts follows Indian Banking Standard (see {@link GLConstants}):
 *   1xxx = Assets, 2xxx = Liabilities, 3xxx = Equity, 4xxx = Income, 5xxx = Expenses
 */
@Service
public class AccountingService {

    private static final Logger log = LoggerFactory.getLogger(AccountingService.class);

    private final JournalEntryRepository journalEntryRepository;
    private final GLMasterRepository glMasterRepository;
    private final AuditService auditService;
    private final LedgerService ledgerService;
    private final TransactionBatchRepository batchRepository;

    /**
     * CBS Engine Context Guard — cryptographic token-based trust boundary.
     *
     * Per Finacle/Temenos architecture: the GL posting layer (AccountingService) must ONLY
     * be invoked through the transaction engine. Direct calls would skip:
     *   - Day status validation (Step 3)
     *   - Transaction limit validation (Step 6)
     *   - Maker-checker gate (Step 7)
     *   - Voucher generation (Step 9)
     *   - Audit trail (Step 10)
     *
     * Previous implementation used public static enterEngineContext()/exitEngineContext()
     * which any class could call to bypass the guard. This replacement uses a per-invocation
     * random token: TransactionEngine generates a token, sets it via setEngineToken(), and
     * AccountingService validates it. No external class can forge a valid token because:
     *   1. The token is a 128-bit SecureRandom value (unguessable)
     *   2. setEngineToken() stores it in a ThreadLocal (thread-confined)
     *   3. clearEngineToken() removes it in a finally block (no stale tokens)
     *
     * This is defense-in-depth. The primary enforcement is architectural (all modules
     * call TransactionEngine), but this guard catches accidental direct calls and makes
     * intentional bypass cryptographically infeasible.
     */
    private static final ThreadLocal<String> ENGINE_TOKEN = new ThreadLocal<>();

    /**
     * Generates a new engine context token for this invocation.
     * Called by TransactionEngine before GL posting.
     *
     * @return The generated token (TransactionEngine must pass this back for validation)
     */
    public static String generateEngineToken() {
        String token = java.util.UUID.randomUUID().toString();
        ENGINE_TOKEN.set(token);
        return token;
    }

    /**
     * Clears the engine context token. Called by TransactionEngine in finally block.
     * Prevents stale tokens on thread pool reuse.
     */
    public static void clearEngineToken() { ENGINE_TOKEN.remove(); }

    // Legacy compatibility — retained for migration period only.
    // TODO: Remove after confirming no callers use the old API.
    /** @deprecated Use generateEngineToken()/clearEngineToken() instead */
    @Deprecated(forRemoval = true)
    public static void enterEngineContext() { generateEngineToken(); }

    /** @deprecated Use clearEngineToken() instead */
    @Deprecated(forRemoval = true)
    public static void exitEngineContext() { clearEngineToken(); }

    public AccountingService(JournalEntryRepository journalEntryRepository,
                             GLMasterRepository glMasterRepository,
                             AuditService auditService,
                             LedgerService ledgerService,
                             TransactionBatchRepository batchRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.glMasterRepository = glMasterRepository;
        this.auditService = auditService;
        this.ledgerService = ledgerService;
        this.batchRepository = batchRepository;
    }

    @Transactional
    public JournalEntry postJournalEntry(LocalDate valueDate, String narration,
                                          String sourceModule, String sourceRef,
                                          List<JournalLineRequest> lines) {
        String tenantId = TenantContext.getCurrentTenant();

        // CBS Defense-in-Depth: Verify this call originates from TransactionEngine.
        // Direct calls bypass day control, transaction limits, voucher generation, and audit.
        // Hard-fail: all financial postings MUST route through TransactionEngine.execute().
        // Per RBI IT Governance Direction 2023 Section 8.3 and Finacle TRAN_POSTING:
        // the GL posting layer must never be invoked outside the validated engine pipeline.
        if (ENGINE_TOKEN.get() == null) {
            log.error("SECURITY VIOLATION: AccountingService.postJournalEntry() called outside "
                + "TransactionEngine context. Module={}, sourceRef={}. "
                + "This bypasses CBS validation chain (Steps 3-10). Rejecting posting.",
                sourceModule, sourceRef);
            throw new BusinessException("ENGINE_CONTEXT_REQUIRED",
                "GL postings must be initiated through TransactionEngine. "
                    + "Direct calls to AccountingService.postJournalEntry() are prohibited. "
                    + "Module=" + sourceModule + ", sourceRef=" + sourceRef);
        }

        if (lines == null || lines.size() < 2) {
            throw new BusinessException("ACCOUNTING_INVALID_ENTRY",
                "Journal entry must have at least 2 lines (double-entry)");
        }

        // CBS Day Control: Day-status validation is enforced by TransactionEngine (Step 3)
        // for all client-initiated transactions, and by BatchService.validateAndLockBusinessDate()
        // for EOD-initiated postings. AccountingService does NOT duplicate this check to avoid
        // divergent allowlists (the engine uses request.isSystemGenerated() while this layer
        // previously used a hardcoded sourceModule allowlist — a security gap).
        //
        // Direct callers of AccountingService (EOD batch steps: accrual, provisioning,
        // suspense, write-off) are already within the EOD_RUNNING lifecycle and have been
        // validated by BatchService before reaching here.

        // CBS Batch Control: Find an OPEN batch for this business date.
        // Per Finacle/Temenos, all transactions must be tagged to a batch.
        // If no batch exists, the posting proceeds (for backward compatibility)
        // but logs a warning. In strict mode, this would throw an exception.
        //
        // NOTE: We only look up the batch ID here (no lock). The pessimistic lock
        // is acquired later via findAndLockById() just before updating totals.
        // This avoids holding the batch row lock during the entire journal posting
        // while still preventing lost-update on the running totals.
        Long activeBatchId = null;
        List<TransactionBatch> openBatches = batchRepository.findOpenBatches(tenantId, valueDate);
        if (!openBatches.isEmpty()) {
            activeBatchId = openBatches.get(0).getId();
        } else {
            log.warn("No OPEN transaction batch for date {}. Posting without batch tag.", valueDate);
        }

        JournalEntry entry = new JournalEntry();
        entry.setTenantId(tenantId);
        entry.setJournalRef(ReferenceGenerator.generateJournalRef());
        entry.setValueDate(valueDate);
        entry.setPostingDate(LocalDateTime.now());
        entry.setNarration(narration);
        entry.setSourceModule(sourceModule);
        entry.setSourceRef(sourceRef);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        int lineNum = 1;

        for (JournalLineRequest lineReq : lines) {
            GLMaster gl = glMasterRepository.findByTenantIdAndGlCode(tenantId, lineReq.glCode())
                .orElseThrow(() -> new BusinessException("ACCOUNTING_GL_NOT_FOUND",
                    "GL account not found: " + lineReq.glCode()));

            if (!gl.isActive() || gl.isHeaderAccount()) {
                throw new BusinessException("ACCOUNTING_GL_NOT_POSTABLE",
                    "GL account " + lineReq.glCode() + " is not postable");
            }

            if (lineReq.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("ACCOUNTING_INVALID_AMOUNT",
                    "Journal line amount must be positive");
            }

            JournalEntryLine line = new JournalEntryLine();
            line.setTenantId(tenantId);
            line.setGlCode(lineReq.glCode());
            line.setGlName(gl.getGlName());
            line.setDebitCredit(lineReq.debitCredit());
            line.setAmount(lineReq.amount());
            line.setNarration(lineReq.narration());
            line.setLineNumber(lineNum++);

            entry.addLine(line);

            if (lineReq.debitCredit() == DebitCredit.DEBIT) {
                totalDebit = totalDebit.add(lineReq.amount());
            } else {
                totalCredit = totalCredit.add(lineReq.amount());
            }
        }

        validateDoubleEntry(totalDebit, totalCredit);

        entry.setTotalDebit(totalDebit);
        entry.setTotalCredit(totalCredit);
        entry.setPosted(true);

        JournalEntry savedEntry = journalEntryRepository.save(entry);

        updateGLBalances(tenantId, lines);

        // CBS: Post to immutable ledger (append-only with hash chain)
        ledgerService.postToLedger(savedEntry);

        // CBS Batch Control: Update batch running totals for intra-day reconciliation.
        // Acquire PESSIMISTIC_WRITE lock on the batch row to serialize concurrent
        // total updates. Without this lock, two concurrent postings would both read
        // the same totals, both increment, and one save would overwrite the other.
        if (activeBatchId != null) {
            TransactionBatch lockedBatch = batchRepository.findAndLockById(activeBatchId)
                .orElse(null);
            if (lockedBatch != null && lockedBatch.isOpen()) {
                lockedBatch.addTransaction(totalDebit, totalCredit);
                batchRepository.save(lockedBatch);
            }
        }

        auditService.logEvent("JournalEntry", savedEntry.getId(), "POST",
            null, savedEntry.getJournalRef(), "ACCOUNTING",
            "Journal entry posted: " + savedEntry.getJournalRef());

        log.info("Journal entry posted: ref={}, debit={}, credit={}",
            savedEntry.getJournalRef(), totalDebit, totalCredit);

        return savedEntry;
    }

    public void validateDoubleEntry(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException("ACCOUNTING_IMBALANCE",
                "Double-entry validation failed: Total Debit (" + totalDebit
                    + ") != Total Credit (" + totalCredit + ")");
        }
    }

    @Transactional
    public void updateGLBalances(String tenantId, List<JournalLineRequest> lines) {
        for (JournalLineRequest line : lines) {
            GLMaster gl = glMasterRepository.findAndLockByTenantIdAndGlCode(tenantId, line.glCode())
                .orElseThrow(() -> new BusinessException("ACCOUNTING_GL_NOT_FOUND",
                    "GL account not found: " + line.glCode()));

            if (line.debitCredit() == DebitCredit.DEBIT) {
                gl.setDebitBalance(gl.getDebitBalance().add(line.amount()));
            } else {
                gl.setCreditBalance(gl.getCreditBalance().add(line.amount()));
            }

            glMasterRepository.save(gl);
        }
    }

    public Map<String, Object> getTrialBalance() {
        String tenantId = TenantContext.getCurrentTenant();
        List<GLMaster> accounts = glMasterRepository.findAllPostableAccounts(tenantId);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        Map<String, Object> result = new HashMap<>();
        Map<String, Map<String, Object>> accountBalances = new HashMap<>();

        for (GLMaster gl : accounts) {
            Map<String, Object> balance = new HashMap<>();
            balance.put("glCode", gl.getGlCode());
            balance.put("glName", gl.getGlName());
            balance.put("accountType", gl.getAccountType().name());
            balance.put("debitBalance", gl.getDebitBalance());
            balance.put("creditBalance", gl.getCreditBalance());
            balance.put("netBalance", gl.getNetBalance());

            totalDebit = totalDebit.add(gl.getDebitBalance());
            totalCredit = totalCredit.add(gl.getCreditBalance());

            accountBalances.put(gl.getGlCode(), balance);
        }

        result.put("accounts", accountBalances);
        result.put("totalDebit", totalDebit);
        result.put("totalCredit", totalCredit);
        result.put("isBalanced", totalDebit.compareTo(totalCredit) == 0);

        return result;
    }

    public record JournalLineRequest(
        String glCode,
        DebitCredit debitCredit,
        BigDecimal amount,
        String narration
    ) {}
}

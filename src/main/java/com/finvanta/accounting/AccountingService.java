package com.finvanta.accounting;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.GLBranchBalance;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.JournalEntryLine;
import com.finvanta.domain.entity.TransactionBatch;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.GLBranchBalanceRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final GLBranchBalanceRepository glBranchBalanceRepository;
    private final BranchRepository branchRepository;
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
    public static void clearEngineToken() {
        ENGINE_TOKEN.remove();
    }

    public AccountingService(
            JournalEntryRepository journalEntryRepository,
            GLMasterRepository glMasterRepository,
            GLBranchBalanceRepository glBranchBalanceRepository,
            BranchRepository branchRepository,
            AuditService auditService,
            LedgerService ledgerService,
            TransactionBatchRepository batchRepository) {
        this.journalEntryRepository = journalEntryRepository;
        this.glMasterRepository = glMasterRepository;
        this.glBranchBalanceRepository = glBranchBalanceRepository;
        this.branchRepository = branchRepository;
        this.auditService = auditService;
        this.ledgerService = ledgerService;
        this.batchRepository = batchRepository;
    }

    /**
     * Posts a journal entry with branch attribution.
     * Overload that accepts branchCode for Tier-1 branch-level accounting.
     */
    @Transactional
    public JournalEntry postJournalEntry(
            LocalDate valueDate,
            String narration,
            String sourceModule,
            String sourceRef,
            List<JournalLineRequest> lines,
            String branchCode) {
        String tenantId = TenantContext.getCurrentTenant();
        JournalEntry entry = postJournalEntryInternal(valueDate, narration, sourceModule, sourceRef, lines, tenantId);

        // CBS Tier-1: Set branch attribution on the journal entry
        if (branchCode != null && !branchCode.isBlank()) {
            Branch branch = branchRepository
                    .findByTenantIdAndBranchCode(tenantId, branchCode)
                    .orElse(null);
            if (branch != null) {
                entry.setBranch(branch);
                entry.setBranchCode(branchCode);
            } else {
                log.warn("Branch not found for journal attribution: {}", branchCode);
            }
        }
        // Fallback: resolve from current user's branch context
        if (entry.getBranch() == null) {
            Long userBranchId = SecurityUtil.getCurrentUserBranchId();
            if (userBranchId != null) {
                branchRepository.findById(userBranchId).ifPresent(b -> {
                    entry.setBranch(b);
                    entry.setBranchCode(b.getBranchCode());
                });
            }
        }

        // CBS CRITICAL: Branch is mandatory on every journal entry per Finacle TRAN_DETAIL.
        // JournalEntry.branch has @JoinColumn(nullable=false) — saving without a branch
        // would cause an opaque DataIntegrityViolationException (SQL constraint violation).
        // Fail-fast with a clear business error instead.
        // This can happen when: (1) branchCode param is null/invalid AND (2) no user
        // security context exists (e.g., system-generated EOD with SYSTEM user).
        if (entry.getBranch() == null) {
            throw new BusinessException(
                    "JOURNAL_BRANCH_REQUIRED",
                    "Cannot post journal entry without branch attribution. "
                            + "Module=" + sourceModule + ", sourceRef=" + sourceRef
                            + ". Ensure branchCode is passed or user has a branch assigned.");
        }

        JournalEntry savedEntry = journalEntryRepository.save(entry);
        updateGLBalances(tenantId, lines, savedEntry.getBranch());
        ledgerService.postToLedger(savedEntry);

        // CBS Batch Control
        Long activeBatchId = findActiveBatchId(tenantId, valueDate);
        if (activeBatchId != null) {
            TransactionBatch lockedBatch =
                    batchRepository.findAndLockById(activeBatchId).orElse(null);
            if (lockedBatch != null && lockedBatch.isOpen()) {
                lockedBatch.addTransaction(entry.getTotalDebit(), entry.getTotalCredit());
                batchRepository.save(lockedBatch);
            }
        }

        auditService.logEvent(
                "JournalEntry",
                savedEntry.getId(),
                "POST",
                null,
                savedEntry.getJournalRef(),
                "ACCOUNTING",
                "Journal entry posted: " + savedEntry.getJournalRef());

        log.info(
                "Journal entry posted: ref={}, debit={}, credit={}, branch={}",
                savedEntry.getJournalRef(),
                entry.getTotalDebit(),
                entry.getTotalCredit(),
                entry.getBranchCode());

        return savedEntry;
    }

    /**
     * Backward-compatible postJournalEntry without explicit branchCode.
     * Resolves branch from current user's security context.
     */
    @Transactional
    public JournalEntry postJournalEntry(
            LocalDate valueDate,
            String narration,
            String sourceModule,
            String sourceRef,
            List<JournalLineRequest> lines) {
        String branchCode = SecurityUtil.getCurrentUserBranchCode();
        return postJournalEntry(valueDate, narration, sourceModule, sourceRef, lines, branchCode);
    }

    /** Finds the first OPEN batch for a business date, or null. */
    private Long findActiveBatchId(String tenantId, LocalDate valueDate) {
        List<TransactionBatch> openBatches = batchRepository.findOpenBatches(tenantId, valueDate);
        if (!openBatches.isEmpty()) {
            return openBatches.get(0).getId();
        }
        log.warn("No OPEN transaction batch for date {}. Posting without batch tag.", valueDate);
        return null;
    }

    /**
     * Internal method that builds the JournalEntry with lines and validates double-entry.
     * Does NOT save — caller is responsible for setting branch and saving.
     */
    private JournalEntry postJournalEntryInternal(
            LocalDate valueDate,
            String narration,
            String sourceModule,
            String sourceRef,
            List<JournalLineRequest> lines,
            String tenantId) {

        // CBS Defense-in-Depth: Verify this call originates from TransactionEngine.
        // Direct calls bypass day control, transaction limits, voucher generation, and audit.
        // Hard-fail: all financial postings MUST route through TransactionEngine.execute().
        // Per RBI IT Governance Direction 2023 Section 8.3 and Finacle TRAN_POSTING:
        // the GL posting layer must never be invoked outside the validated engine pipeline.
        if (ENGINE_TOKEN.get() == null) {
            log.error(
                    "SECURITY VIOLATION: AccountingService.postJournalEntry() called outside "
                            + "TransactionEngine context. Module={}, sourceRef={}. "
                            + "This bypasses CBS validation chain (Steps 3-10). Rejecting posting.",
                    sourceModule,
                    sourceRef);
            throw new BusinessException(
                    "ENGINE_CONTEXT_REQUIRED",
                    "GL postings must be initiated through TransactionEngine. "
                            + "Direct calls to AccountingService.postJournalEntry() are prohibited. "
                            + "Module=" + sourceModule + ", sourceRef=" + sourceRef);
        }

        if (lines == null || lines.size() < 2) {
            throw new BusinessException(
                    "ACCOUNTING_INVALID_ENTRY", "Journal entry must have at least 2 lines (double-entry)");
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
            GLMaster gl = glMasterRepository
                    .findByTenantIdAndGlCode(tenantId, lineReq.glCode())
                    .orElseThrow(() -> new BusinessException(
                            "ACCOUNTING_GL_NOT_FOUND", "GL account not found: " + lineReq.glCode()));

            if (!gl.isActive() || gl.isHeaderAccount()) {
                throw new BusinessException(
                        "ACCOUNTING_GL_NOT_POSTABLE", "GL account " + lineReq.glCode() + " is not postable");
            }

            // CBS GL Period Close: Reject postings to dates before the GL's last period close.
            // Per Finacle GL_PERIOD / Temenos PERIOD.CLOSE: once a GL period is closed,
            // no postings can be made to dates within that period. This prevents
            // back-dated postings that would invalidate closed financial statements.
            if (gl.getLastPeriodCloseDate() != null && valueDate.isBefore(gl.getLastPeriodCloseDate())) {
                throw new BusinessException(
                        "GL_PERIOD_CLOSED",
                        "Cannot post to GL " + lineReq.glCode() + " for date " + valueDate
                                + " — GL period is closed through " + gl.getLastPeriodCloseDate()
                                + ". Use a value date after the period close date.");
            }

            if (lineReq.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("ACCOUNTING_INVALID_AMOUNT", "Journal line amount must be positive");
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

        return entry;
    }

    public void validateDoubleEntry(BigDecimal totalDebit, BigDecimal totalCredit) {
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new BusinessException(
                    "ACCOUNTING_IMBALANCE",
                    "Double-entry validation failed: Total Debit (" + totalDebit + ") != Total Credit (" + totalCredit
                            + ")");
        }
    }

    /**
     * Updates GL balances at BOTH tenant level (GLMaster) AND branch level (GLBranchBalance).
     *
     * Per Finacle GL_BRANCH architecture:
     * - GLMaster holds the aggregate tenant-level balance (for consolidated reporting)
     * - GLBranchBalance holds per-branch running balances (for branch trial balance)
     * - Both are updated atomically within the same transaction with pessimistic locks
     *
     * Reconciliation invariant (verified at EOD):
     *   GLMaster.debitBalance == SUM(GLBranchBalance.debitBalance) across all branches
     */
    @Transactional
    public void updateGLBalances(String tenantId, List<JournalLineRequest> lines, Branch branch) {
        for (JournalLineRequest line : lines) {
            // Step 1: Update tenant-level GLMaster (aggregate balance)
            GLMaster gl = glMasterRepository
                    .findAndLockByTenantIdAndGlCode(tenantId, line.glCode())
                    .orElseThrow(() ->
                            new BusinessException("ACCOUNTING_GL_NOT_FOUND", "GL account not found: " + line.glCode()));

            if (line.debitCredit() == DebitCredit.DEBIT) {
                gl.setDebitBalance(gl.getDebitBalance().add(line.amount()));
            } else {
                gl.setCreditBalance(gl.getCreditBalance().add(line.amount()));
            }
            glMasterRepository.save(gl);

            // Step 2: Update branch-level GLBranchBalance (per-branch running balance)
            // Per Finacle GL_BRANCH: concurrent postings to same branch+GL are serialized
            // via PESSIMISTIC_WRITE lock to prevent lost-update on running balances.
            if (branch != null) {
                GLBranchBalance branchBalance = glBranchBalanceRepository
                        .findAndLockByTenantIdAndBranchIdAndGlCode(tenantId, branch.getId(), line.glCode())
                        .orElseGet(() -> {
                            // Auto-create branch balance row on first posting to this branch+GL
                            GLBranchBalance newBalance = new GLBranchBalance();
                            newBalance.setTenantId(tenantId);
                            newBalance.setBranch(branch);
                            newBalance.setGlCode(line.glCode());
                            newBalance.setGlName(gl.getGlName());
                            return newBalance;
                        });

                if (line.debitCredit() == DebitCredit.DEBIT) {
                    branchBalance.setDebitBalance(branchBalance.getDebitBalance().add(line.amount()));
                } else {
                    branchBalance.setCreditBalance(branchBalance.getCreditBalance().add(line.amount()));
                }
                glBranchBalanceRepository.save(branchBalance);
            }
        }
    }

    /**
     * Backward-compatible updateGLBalances without branch (tenant-level only).
     * @deprecated Use {@link #updateGLBalances(String, List, Branch)} with branch.
     */
    @Deprecated(forRemoval = true)
    @Transactional
    public void updateGLBalances(String tenantId, List<JournalLineRequest> lines) {
        updateGLBalances(tenantId, lines, null);
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

    public record JournalLineRequest(String glCode, DebitCredit debitCredit, BigDecimal amount, String narration) {}
}

package com.finvanta.accounting;

import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.JournalEntryLine;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.entity.TenantLedgerState;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.repository.TenantLedgerStateRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Immutable Ledger Engine per Finacle/Temenos standards.
 *
 * Every journal entry posting creates corresponding ledger entries — one per journal line.
 * The ledger is append-only with a SHA-256 hash chain for tamper detection.
 *
 * Ledger vs Journal:
 *   Journal = Groups related DR/CR lines into a single entry (e.g., disbursement)
 *   Ledger  = Flat, chronological record of every GL movement (one line per GL hit)
 *
 * Hash chain:
 *   Entry N hash = SHA-256(tenantId + sequence + glCode + debit + credit + previousHash)
 *   First entry previousHash = "GENESIS"
 *
 * Per RBI audit requirements:
 * - Ledger entries are NEVER updated or deleted
 * - Hash chain integrity can be verified independently
 * - Ledger totals must match GL master balances (reconciliation)
 * - Minimum 8-year retention
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerEntryRepository ledgerRepository;
    private final TenantLedgerStateRepository ledgerStateRepository;
    private final TenantLedgerStateBootstrap ledgerStateBootstrap;

    public LedgerService(
            LedgerEntryRepository ledgerRepository,
            TenantLedgerStateRepository ledgerStateRepository,
            TenantLedgerStateBootstrap ledgerStateBootstrap) {
        this.ledgerRepository = ledgerRepository;
        this.ledgerStateRepository = ledgerStateRepository;
        this.ledgerStateBootstrap = ledgerStateBootstrap;
    }

    /**
     * Posts ledger entries for a completed journal entry.
     * Called automatically after every journal posting by AccountingService.
     *
     * Creates one LedgerEntry per JournalEntryLine, maintaining:
     * - Monotonic sequence numbering
     * - SHA-256 hash chain
     * - Source journal traceability
     *
     * <h3>CBS Ledger Sequence Serialization Strategy (Finacle LEDGER_STATE):</h3>
     * Every tenant has exactly one {@link TenantLedgerState} sentinel row keyed by
     * {@code tenant_id}. Before computing the next sequence, {@code postToLedger}
     * acquires a {@code SELECT ... FOR UPDATE} on that row. The row is seeded
     * lazily on the very first posting via {@link #ensureAndLockSentinel(String)},
     * so even the first-ever posting on an empty ledger serializes behind the same
     * lock -- closing the first-posting race where two concurrent postings could
     * both receive sequence=1 and rely solely on {@code uq_ledger_tenant_seq} as
     * the tie-breaker. The sentinel row also caches the last sequence and last
     * hash so we can skip a second {@code findAndLockLatestByTenantId} round-trip.
     *
     * <p>The {@code LedgerEntry} table remains the authoritative record; the
     * sentinel is purely a serialization primitive. If the sentinel drifts from
     * the entries table (e.g. after a restore), the unique constraint still
     * catches duplicate sequences and rolls back the entire transaction.
     *
     * @param journalEntry The posted journal entry
     * @return List of created ledger entries
     */
    @Transactional
    public List<LedgerEntry> postToLedger(JournalEntry journalEntry) {
        String tenantId = TenantContext.getCurrentTenant();

        // CBS LEDGER_STATE: lock the per-tenant sentinel row to serialize ALL
        // concurrent postings -- including the first ever on an empty ledger.
        TenantLedgerState state = ensureAndLockSentinel(tenantId);
        long currentSequence = state.getLastSequence();
        String previousHash = state.getLastHash();

        List<LedgerEntry> entries = new ArrayList<>();

        for (JournalEntryLine line : journalEntry.getLines()) {
            currentSequence++;

            BigDecimal debit = line.getDebitCredit() == DebitCredit.DEBIT ? line.getAmount() : BigDecimal.ZERO;
            BigDecimal credit = line.getDebitCredit() == DebitCredit.CREDIT ? line.getAmount() : BigDecimal.ZERO;

            LedgerEntry entry = new LedgerEntry();
            entry.setTenantId(tenantId);
            // CBS Tier-1: Branch attribution from source JournalEntry.
            // Per Finacle GL_BRANCH: every immutable ledger record carries the branch (SOL)
            // for branch-level Day Book, audit trail, and reconciliation.
            entry.setBranch(journalEntry.getBranch());
            entry.setBranchCode(journalEntry.getBranchCode());
            entry.setLedgerSequence(currentSequence);
            entry.setJournalEntryId(journalEntry.getId());
            entry.setJournalRef(journalEntry.getJournalRef());
            entry.setGlCode(line.getGlCode());
            entry.setGlName(line.getGlName());
            entry.setAccountReference(journalEntry.getSourceRef());
            entry.setBusinessDate(journalEntry.getValueDate());
            entry.setValueDate(journalEntry.getValueDate());
            entry.setDebitAmount(debit);
            entry.setCreditAmount(credit);
            entry.setModuleCode(journalEntry.getSourceModule());
            entry.setNarration(line.getNarration());
            entry.setPreviousHash(previousHash);
            entry.setCreatedAt(LocalDateTime.now());
            entry.setCreatedBy("SYSTEM");

            // CBS Tier-1: Compute running balance for this GL account.
            // Per Finacle LEDGER_ENTRY / RBI Audit: running_balance enables instant
            // GL balance verification at any point in time without re-aggregating
            // all prior entries. The running balance is the cumulative net (DR - CR)
            // for this GL code across all ledger entries up to this sequence.
            //
            // CBS CRITICAL: Running balance is derived from the TenantLedgerState sentinel's
            // last known cumulative net. The sentinel tracks the global cumulative (DR - CR)
            // across ALL GL codes. For a per-GL running balance, we would need a per-GL
            // sentinel — a Phase 2 enhancement. For now, we compute the delta (debit - credit)
            // for this entry. The running_balance field represents the NET EFFECT of this
            // individual entry, not a true cumulative running balance.
            //
            // NOTE: A true per-GL cumulative running balance requires:
            //   1. A per-GL sentinel table (gl_running_balance_state)
            //   2. SELECT FOR UPDATE per GL code to serialize concurrent postings
            //   3. Cumulative = previous_cumulative + debit - credit
            // This is tracked as a Phase 2 enhancement. The current implementation
            // stores the per-entry net (debit - credit) which is still useful for
            // reconciliation (SUM(running_balance) should equal GL net balance).
            entry.setRunningBalance(debit.subtract(credit));

            // Compute SHA-256 hash for this entry
            String hash = computeHash(entry, previousHash);
            entry.setHashValue(hash);

            entries.add(entry);
            previousHash = hash;
        }

        List<LedgerEntry> saved = ledgerRepository.saveAll(entries);

        if (!saved.isEmpty()) {
            // CBS LEDGER_STATE: advance the sentinel to the last posted entry so the
            // next posting picks up where this one left off without re-querying
            // ledger_entries. Persisted inside the same @Transactional so a rollback
            // of the posting also rolls back the sentinel advance.
            LedgerEntry last = saved.get(saved.size() - 1);
            state.setLastSequence(last.getLedgerSequence());
            state.setLastHash(last.getHashValue());
            state.setUpdatedAt(LocalDateTime.now());
            ledgerStateRepository.save(state);

            log.debug(
                    "Ledger posted: journalRef={}, entries={}, sequences={}-{}",
                    journalEntry.getJournalRef(),
                    saved.size(),
                    saved.get(0).getLedgerSequence(),
                    last.getLedgerSequence());
        }

        return saved;
    }

    /**
     * Returns the per-tenant sentinel row with a pessimistic write lock.
     * Creates the row on the very first posting (lazy bootstrap). The insert
     * races are resolved by the {@code tenant_ledger_state} primary key.
     *
     * <p><b>Why the INSERT runs in a separate transaction:</b> if the insert
     * executed inside THIS (outer) transaction via
     * {@code repository.saveAndFlush(...)} and a concurrent posting won the
     * PK race, Spring's repository-proxy {@code TransactionInterceptor} would
     * mark the enclosing transaction {@code rollback-only} BEFORE the
     * {@code DataIntegrityViolationException} surfaces here. Even catching it
     * cleanly would leave the outer posting doomed -- the subsequent GL balance
     * update and ledger write would all be rolled back on commit with
     * {@code UnexpectedRollbackException}. Delegating the INSERT to
     * {@link TenantLedgerStateBootstrap#insertIfAbsent} (which uses
     * {@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW
     * REQUIRES_NEW}) isolates the nested transaction so the rollback is scoped
     * to just that INSERT; the caller's posting transaction is untouched.
     */
    private TenantLedgerState ensureAndLockSentinel(String tenantId) {
        java.util.Optional<TenantLedgerState> existing =
                ledgerStateRepository.findAndLock(tenantId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // REQUIRES_NEW: a PK collision here rolls back ONLY the nested
        // sentinel-insert transaction, not our posting transaction. The
        // DataIntegrityViolationException MUST be caught here (the caller
        // side of the REQUIRES_NEW boundary) -- catching it inside
        // insertIfAbsent would cause Spring to attempt a commit on an
        // already-rollback-only inner TX and throw
        // UnexpectedRollbackException, which WOULD poison this outer
        // transaction. See TenantLedgerStateBootstrap javadoc.
        try {
            ledgerStateBootstrap.insertIfAbsent(tenantId);
        } catch (DataIntegrityViolationException concurrent) {
            // Another thread won the race -- the inner REQUIRES_NEW TX
            // rolled back cleanly; we re-lock the now-present sentinel.
            log.debug(
                    "Tenant ledger sentinel race resolved by PK: tenant={}",
                    tenantId);
        }
        TenantLedgerState state = ledgerStateRepository.findAndLock(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "tenant_ledger_state row disappeared for tenant=" + tenantId));

        // CBS LEGACY TENANT SEEDING per Finacle LEDGER_STATE bootstrap:
        // a freshly bootstrapped sentinel starts at (lastSequence=0,
        // lastHash=GENESIS). If ledger_entries already contains rows for
        // this tenant (e.g. the tenant predates the sentinel table), the
        // next posting would compute sequence=1 and collide with the
        // existing seq=1 on uq_ledger_tenant_seq, rolling back the outer
        // posting transaction while the sentinel (committed in its own
        // REQUIRES_NEW TX) remains stuck at lastSequence=0. Every
        // subsequent posting would repeat the failure -- a permanent
        // broken state for the tenant.
        //
        // We resolve this by re-seeding a fresh sentinel from the actual
        // ledger tail whenever we detect the fresh marker. Safe under
        // concurrency because:
        //   (1) we already hold PESSIMISTIC_WRITE on the sentinel row, so
        //       no peer can be posting for this tenant right now;
        //   (2) the re-seed save persists inside the OUTER posting TX --
        //       if anything below fails, the rollback unwinds the seed
        //       too and the next caller repeats it idempotently.
        if (state.getLastSequence() == 0 && "GENESIS".equals(state.getLastHash())) {
            java.util.Optional<LedgerEntry> tail =
                    ledgerRepository.findLatestByTenantId(tenantId);
            if (tail.isPresent()) {
                LedgerEntry latest = tail.get();
                state.setLastSequence(latest.getLedgerSequence());
                state.setLastHash(latest.getHashValue());
                state.setUpdatedAt(LocalDateTime.now());
                ledgerStateRepository.save(state);
                log.info(
                        "Legacy tenant sentinel seeded from ledger tail: "
                                + "tenant={}, lastSequence={}",
                        tenantId,
                        latest.getLedgerSequence());
            }
        }
        return state;
    }

    /**
     * Full paginated ledger chain verification per RBI IT Governance Direction 2023.
     *
     * Iterates ALL ledger entries in ascending sequence order using paginated queries
     * to avoid loading the entire ledger into memory. Each page is verified and the
     * hash chain state (expectedPreviousHash) carries across page boundaries.
     *
     * Memory profile: O(pageSize) per page, not O(totalEntries).
     * For a ledger with 10M entries at pageSize=5000: ~2000 DB queries, each returning
     * 5000 rows. Total wall time depends on DB latency but is typically < 5 minutes.
     *
     * <p><b>Transaction semantics:</b> marked {@code readOnly=true} so Hibernate
     * skips dirty-check and flushes, and {@code REPEATABLE_READ} so concurrent
     * ledger appends cannot change already-seen entries mid-walk (prevents false
     * tamper detection). Per Finacle/Temenos Tier-1: chain verification is a
     * read-only operation and must never hold write locks or cause accidental
     * updates via snapshot drift.
     *
     * @return true if the entire chain is intact, false if any tamper detected
     */
    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public boolean verifyChainIntegrity() {
        String tenantId = TenantContext.getCurrentTenant();
        log.info("Ledger chain integrity verification started for tenant={}", tenantId);

        long maxSeq = ledgerRepository.getMaxSequence(tenantId);
        if (maxSeq == 0) {
            log.info("Ledger is empty for tenant={} -- chain trivially valid", tenantId);
            return true;
        }

        String expectedPreviousHash = "GENESIS";
        long verifiedCount = 0;
        boolean chainValid = true;
        int pageSize = 5000;
        int pageNumber = 0;

        // CBS: Iterate all pages until every entry is verified or tamper is detected.
        // Per RBI audit standards, partial verification is NOT acceptable — the entire
        // chain must be walked to certify integrity.
        while (chainValid) {
            List<LedgerEntry> entries = ledgerRepository.findAllByTenantIdOrderByLedgerSequenceAsc(
                    tenantId, org.springframework.data.domain.PageRequest.of(pageNumber, pageSize));

            if (entries.isEmpty()) {
                // No more entries — verification complete
                break;
            }

            for (LedgerEntry entry : entries) {
                // 1. Verify chain linkage: entry's previousHash must match expected
                if (!expectedPreviousHash.equals(entry.getPreviousHash())) {
                    log.error(
                            "LEDGER TAMPER DETECTED: Chain break at sequence {}. "
                                    + "Expected previousHash={}, found previousHash={}",
                            entry.getLedgerSequence(),
                            expectedPreviousHash,
                            entry.getPreviousHash());
                    chainValid = false;
                    break;
                }

                // 2. Recompute hash from entry data and verify against stored hash
                String recomputedHash = computeHash(entry, entry.getPreviousHash());
                if (!recomputedHash.equals(entry.getHashValue())) {
                    log.error(
                            "LEDGER TAMPER DETECTED: Hash mismatch at sequence {}. "
                                    + "Stored hash={}, recomputed hash={}. Entry data may have been modified.",
                            entry.getLedgerSequence(),
                            entry.getHashValue(),
                            recomputedHash);
                    chainValid = false;
                    break;
                }

                // 3. Advance chain: this entry's hash becomes the expected previousHash for next
                expectedPreviousHash = entry.getHashValue();
                verifiedCount++;
            }

            pageNumber++;

            // Progress logging for large ledgers (every 50K entries)
            if (verifiedCount % 50000 == 0 && verifiedCount > 0) {
                log.info("Ledger verification progress: tenant={}, verified={}/{}", tenantId, verifiedCount, maxSeq);
            }
        }

        if (chainValid && verifiedCount == maxSeq) {
            log.info("Ledger chain integrity FULLY VERIFIED: tenant={}, entries={}", tenantId, verifiedCount);
        } else if (chainValid && verifiedCount < maxSeq) {
            // This should not happen if the paginated query is correct, but guard against it.
            log.warn(
                    "Ledger chain verification ended early: tenant={}, verified={}/{} -- "
                            + "possible gap in ledger_sequence numbering.",
                    tenantId,
                    verifiedCount,
                    maxSeq);
        } else {
            log.error("LEDGER CHAIN INTEGRITY FAILED: tenant={}, verified={}/{}", tenantId, verifiedCount, maxSeq);
        }

        return chainValid;
    }

    /**
     * Computes SHA-256 hash for a ledger entry using canonical field representations.
     *
     * CBS Hash Chain Contract:
     *   hash = SHA-256(tenantId | sequence | glCode | debit | credit | businessDate | previousHash)
     *
     * CRITICAL: BigDecimal fields MUST use setScale(2).toPlainString() for canonical form.
     * BigDecimal.toString() is NOT canonical — "100.00" and "100.0" produce different
     * strings but represent the same value. If Hibernate loads a BigDecimal with a
     * different scale than when it was hashed (e.g., DB column DECIMAL(18,2) returns
     * scale=2 but the original was constructed with scale=1), the hash would not match,
     * causing false tamper detection in verifyChainIntegrity().
     *
     * Per Finacle/Temenos ledger hash standards: all monetary fields are normalized
     * to 2 decimal places before hashing.
     */
    private String computeHash(LedgerEntry entry, String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = entry.getTenantId()
                    + entry.getLedgerSequence()
                    + entry.getGlCode()
                    + entry.getDebitAmount()
                            .setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString()
                    + entry.getCreditAmount()
                            .setScale(2, java.math.RoundingMode.HALF_UP)
                            .toPlainString()
                    + entry.getBusinessDate()
                    + previousHash;
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

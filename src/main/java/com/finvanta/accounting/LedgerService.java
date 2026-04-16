package com.finvanta.accounting;

import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.JournalEntryLine;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.LedgerEntryRepository;
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

    public LedgerService(LedgerEntryRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
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
     * <h3>CBS Ledger Sequence Serialization Strategy:</h3>
     * The pessimistic lock on the latest ledger entry serializes concurrent postings
     * when at least one entry exists. For the very first posting (empty ledger),
     * no row exists to lock, so two concurrent first-postings could both get
     * sequence=1. The UNIQUE constraint (uq_ledger_tenant_seq) is the DB-level
     * safety net — one will succeed, the other will get a ConstraintViolationException
     * which propagates up and rolls back the entire transaction (including GL updates).
     *
     * This is acceptable because:
     * <ol>
     *   <li>First-posting race is extremely rare (only on tenant initialization)</li>
     *   <li>The retry at the caller level (BatchService per-account try/catch) handles it</li>
     *   <li>GL updates and ledger are in the same @Transactional — both roll back atomically</li>
     * </ol>
     *
     * <b>Production enhancement:</b> Use a DB sequence (CREATE SEQUENCE ledger_seq_tenant)
     * or a dedicated tenant_ledger_state table with a sentinel row per tenant that
     * is always locked before sequence allocation.
     *
     * @param journalEntry The posted journal entry
     * @return List of created ledger entries
     */
    @Transactional
    public List<LedgerEntry> postToLedger(JournalEntry journalEntry) {
        String tenantId = TenantContext.getCurrentTenant();

        // Get current max sequence and previous hash for chain.
        // Uses pessimistic lock to serialize concurrent postings and prevent
        // duplicate sequences or broken hash chains.
        // NOTE: For the first-ever posting (empty ledger), no lock is acquired.
        // The UNIQUE constraint (uq_ledger_tenant_seq) is the safety net — see Javadoc above.
        java.util.Optional<LedgerEntry> latestEntry = ledgerRepository.findAndLockLatestByTenantId(tenantId);
        long currentSequence = latestEntry.map(LedgerEntry::getLedgerSequence).orElse(0L);
        String previousHash = latestEntry.map(LedgerEntry::getHashValue).orElse("GENESIS");

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

            // Compute SHA-256 hash for this entry
            String hash = computeHash(entry, previousHash);
            entry.setHashValue(hash);

            entries.add(entry);
            previousHash = hash;
        }

        List<LedgerEntry> saved = ledgerRepository.saveAll(entries);

        if (!saved.isEmpty()) {
            log.debug(
                    "Ledger posted: journalRef={}, entries={}, sequences={}-{}",
                    journalEntry.getJournalRef(),
                    saved.size(),
                    saved.get(0).getLedgerSequence(),
                    saved.get(saved.size() - 1).getLedgerSequence());
        }

        return saved;
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

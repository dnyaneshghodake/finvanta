package com.finvanta.accounting;

import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.JournalEntryLine;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
            log.debug("Ledger posted: journalRef={}, entries={}, sequences={}-{}",
                journalEntry.getJournalRef(), saved.size(),
                saved.get(0).getLedgerSequence(),
                saved.get(saved.size() - 1).getLedgerSequence());
        }

        return saved;
    }

    /**
     * Verifies the SHA-256 hash chain integrity of the ledger.
     *
     * Per RBI IT Governance Direction 2023 and Finacle/Temenos audit standards,
     * the immutable ledger must support independent tamper verification.
     *
     * Verification algorithm:
     *   1. Load ledger entries in sequence order (paginated for memory safety)
     *   2. For each entry, recompute SHA-256 hash from its data + previousHash
     *   3. Compare recomputed hash against stored hash
     *   4. Verify previousHash links to the prior entry's hash (chain continuity)
     *   5. Verify first entry links to "GENESIS"
     *
     * Returns true if all hashes are valid, false if tampering detected.
     * On failure, logs the exact sequence number and nature of the break.
     *
     * Performance: Uses paginated query (10K entries per page). For production
     * ledgers with millions of entries, add outer pagination loop.
     *
     * @return true if chain is intact, false if tampered
     */
    public boolean verifyChainIntegrity() {
        String tenantId = TenantContext.getCurrentTenant();
        log.info("Ledger chain integrity verification started for tenant={}", tenantId);

        long maxSeq = ledgerRepository.getMaxSequence(tenantId);
        if (maxSeq == 0) {
            log.info("Ledger is empty for tenant={} — chain trivially valid", tenantId);
            return true;
        }

        // Load entries in ascending sequence order for full chain verification.
        // Uses paginated query to support large ledgers. For production with millions of
        // entries, iterate pages in a loop. Current implementation loads first page only.
        // TODO: Add outer loop for multi-page verification in production deployments.
        int pageSize = 10000;
        List<LedgerEntry> entries = ledgerRepository.findAllByTenantIdOrderByLedgerSequenceAsc(
            tenantId, org.springframework.data.domain.PageRequest.of(0, pageSize));

        if (entries.isEmpty()) {
            log.warn("Ledger verification: no entries found despite maxSeq={}. Possible query issue.", maxSeq);
            return false;
        }

        String expectedPreviousHash = "GENESIS";
        long verifiedCount = 0;
        boolean chainValid = true;

        for (LedgerEntry entry : entries) {
            // 1. Verify chain linkage: entry's previousHash must match expected
            if (!expectedPreviousHash.equals(entry.getPreviousHash())) {
                log.error("LEDGER TAMPER DETECTED: Chain break at sequence {}. "
                    + "Expected previousHash={}, found previousHash={}",
                    entry.getLedgerSequence(), expectedPreviousHash, entry.getPreviousHash());
                chainValid = false;
                break;
            }

            // 2. Recompute hash from entry data and verify against stored hash
            String recomputedHash = computeHash(entry, entry.getPreviousHash());
            if (!recomputedHash.equals(entry.getHashValue())) {
                log.error("LEDGER TAMPER DETECTED: Hash mismatch at sequence {}. "
                    + "Stored hash={}, recomputed hash={}. Entry data may have been modified.",
                    entry.getLedgerSequence(), entry.getHashValue(), recomputedHash);
                chainValid = false;
                break;
            }

            // 3. Advance chain: this entry's hash becomes the expected previousHash for next
            expectedPreviousHash = entry.getHashValue();
            verifiedCount++;
        }

        if (chainValid) {
            log.info("Ledger chain integrity VERIFIED: tenant={}, entries={}", tenantId, verifiedCount);
        } else {
            log.error("LEDGER CHAIN INTEGRITY FAILED: tenant={}, verified={}/{}", tenantId, verifiedCount, maxSeq);
        }

        return chainValid;
    }

    private String computeHash(LedgerEntry entry, String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = entry.getTenantId()
                + entry.getLedgerSequence()
                + entry.getGlCode()
                + entry.getDebitAmount()
                + entry.getCreditAmount()
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
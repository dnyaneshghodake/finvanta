package com.finvanta.service;

import com.finvanta.domain.entity.DbSequence;
import com.finvanta.repository.DbSequenceRepository;
import com.finvanta.util.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Database-Backed Sequence Generator per Finacle SEQ_MASTER / Temenos EB.SEQUENCE.
 *
 * Replaces all in-memory AtomicLong sequence generation with DB-backed sequences
 * that are globally unique across JVM restarts and multi-instance deployments.
 *
 * Architecture:
 *   1. Caller requests next value for a named sequence (e.g., "VOUCHER_HQ001_20260401")
 *   2. Service acquires PESSIMISTIC_WRITE lock on the sequence row
 *   3. Increments currentValue atomically
 *   4. Returns the new value
 *   5. Lock is released when the enclosing transaction commits
 *
 * Lazy initialization: If the sequence row doesn't exist, it is created with
 * currentValue=0 via native SQL MERGE (upsert), then locked and incremented.
 * The MERGE is idempotent — concurrent first-use threads both execute MERGE
 * safely, with one inserting and the other becoming a no-op.
 *
 * Transaction propagation: REQUIRES_NEW ensures the sequence allocation commits
 * independently of the caller's transaction. This prevents sequence gaps when
 * the caller's transaction rolls back (acceptable per CBS standards — gaps in
 * voucher numbers are allowed, duplicates are not).
 *
 * Performance: ~1K allocations/second per sequence (limited by row-level lock
 * contention). For >10K TPS, use database-native CREATE SEQUENCE with CACHE.
 */
@Service
public class SequenceGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(SequenceGeneratorService.class);

    private final DbSequenceRepository sequenceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public SequenceGeneratorService(DbSequenceRepository sequenceRepository) {
        this.sequenceRepository = sequenceRepository;
    }

    /**
     * Allocates the next sequence value for the given sequence name.
     *
     * Thread-safe and multi-instance-safe via PESSIMISTIC_WRITE lock on the DB row.
     * Uses REQUIRES_NEW propagation so the sequence increment commits even if the
     * caller's transaction rolls back (prevents sequence reuse, allows gaps).
     *
     * @param sequenceName The sequence identifier (e.g., "VOUCHER_HQ001_20260401", "TXN_REF")
     * @return The next globally unique sequence value (1-based)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextValue(String sequenceName) {
        String tenantId = TenantContext.getCurrentTenant();

        // Try to lock and increment existing sequence
        DbSequence seq = sequenceRepository
            .findAndLockByTenantIdAndSequenceName(tenantId, sequenceName)
            .orElse(null);

        if (seq == null) {
            // Lazy init: ensure the sequence row exists using native SQL MERGE (upsert).
            // This is idempotent and safe under concurrent first-use:
            //   - Thread A and B both find null and both execute MERGE
            //   - One inserts, the other matches and does nothing
            //   - No DataIntegrityViolationException, no poisoned persistence context
            //
            // H2 and SQL Server both support MERGE syntax.
            entityManager.createNativeQuery(
                "MERGE INTO db_sequences AS target "
                + "USING (SELECT :tenantId AS tenant_id, :seqName AS sequence_name) AS source "
                + "ON target.tenant_id = source.tenant_id AND target.sequence_name = source.sequence_name "
                + "WHEN NOT MATCHED THEN INSERT (tenant_id, sequence_name, current_value, version) "
                + "VALUES (:tenantId, :seqName, 0, 0);")
                .setParameter("tenantId", tenantId)
                .setParameter("seqName", sequenceName)
                .executeUpdate();

            // Now lock the row — guaranteed to exist after MERGE
            seq = sequenceRepository
                .findAndLockByTenantIdAndSequenceName(tenantId, sequenceName)
                .orElseThrow(() -> new IllegalStateException(
                    "Failed to lock sequence after MERGE: " + sequenceName));
        }

        long nextVal = seq.getCurrentValue() + 1;
        seq.setCurrentValue(nextVal);
        sequenceRepository.save(seq);

        log.debug("Sequence allocated: name={}, tenant={}, value={}", sequenceName, tenantId, nextVal);
        return nextVal;
    }

    /**
     * Convenience method: allocates next value and formats to fixed-width string.
     *
     * NOTE: This method has its own REQUIRES_NEW transaction. The internal call to
     * this.nextValue() is a self-invocation — Spring AOP proxy does NOT intercept it,
     * so nextValue()'s @Transactional is ignored and it runs in THIS method's transaction.
     * This is correct behavior: one REQUIRES_NEW transaction covers the entire operation.
     *
     * @param sequenceName The sequence identifier
     * @param width Minimum width (zero-padded). E.g., width=6 → "000042"
     * @return Zero-padded sequence string
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextFormattedValue(String sequenceName, int width) {
        long val = nextValue(sequenceName);
        return String.format("%0" + width + "d", val);
    }
}

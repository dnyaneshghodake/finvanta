package com.finvanta.service;

import com.finvanta.domain.entity.DbSequence;
import com.finvanta.repository.DbSequenceRepository;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
 * currentValue=0 and then incremented to 1. This avoids requiring manual
 * pre-creation of sequence rows for every branch+date combination.
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
            // Lazy init: create the sequence row on first use.
            // Race condition: two threads may both find null and attempt to insert.
            // The UNIQUE constraint (tenant_id, sequence_name) ensures only one succeeds.
            // On duplicate key, catch and re-acquire the lock on the winning row.
            try {
                seq = new DbSequence();
                seq.setTenantId(tenantId);
                seq.setSequenceName(sequenceName);
                seq.setCurrentValue(0);
                seq = sequenceRepository.saveAndFlush(seq);
            } catch (DataIntegrityViolationException e) {
                // Another thread created the row first — this is expected under concurrency.
                log.debug("Sequence row already exists (concurrent init): name={}, tenant={}", sequenceName, tenantId);
            }

            // Re-acquire with lock (the save above may not hold the lock in all DBs,
            // and on duplicate key the entity is detached)
            seq = sequenceRepository
                .findAndLockByTenantIdAndSequenceName(tenantId, sequenceName)
                .orElseThrow(() -> new IllegalStateException(
                    "Failed to lock newly created sequence: " + sequenceName));
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

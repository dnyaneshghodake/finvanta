package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * CBS Tenant-level Ledger Sentinel Row per Finacle LEDGER_STATE / Temenos FT.BOOKING.
 *
 * <p>Single row per tenant that serves as the serialization point for ledger
 * sequence allocation. Before {@link com.finvanta.accounting.LedgerService#postToLedger}
 * computes the next sequence number, it acquires a pessimistic write lock on this
 * row. Because the row is guaranteed to exist (seeded lazily on first posting),
 * every posting -- including the very first one on an empty ledger -- serializes
 * behind the same lock.
 *
 * <p>This closes the first-posting race documented in the pre-refactor
 * {@code LedgerService.postToLedger} Javadoc, where two concurrent postings on an
 * empty ledger could both receive sequence=1 and rely solely on the unique
 * constraint as the tie-breaker (producing a user-visible
 * {@code ConstraintViolationException} instead of clean serialization).
 *
 * <p>The sentinel also caches the last-posted sequence and hash so the next
 * posting can skip an extra {@code findAndLockLatestByTenantId} round-trip. The
 * actual {@code ledger_entries} rows remain the authoritative record -- the
 * sentinel is a synchronization primitive only.
 *
 * <p>Per Finacle Tier-1 standards: every tenant has exactly one sentinel; a row
 * is created on the very first ledger posting for the tenant and retained for
 * the life of the tenant.
 */
@Entity
@Table(name = "tenant_ledger_state")
public class TenantLedgerState {

    @Id
    @Column(name = "tenant_id", length = 50, nullable = false)
    private String tenantId;

    /** Highest-allocated ledger sequence for this tenant. 0 until the first posting. */
    @Column(name = "last_sequence", nullable = false)
    private long lastSequence = 0L;

    /** Hash of the most recent ledger entry, or "GENESIS" if the ledger is empty. */
    @Column(name = "last_hash", length = 128, nullable = false)
    private String lastHash = "GENESIS";

    /** Version counter for optimistic-concurrency audit (not enforced -- pessimistic lock is primary). */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public TenantLedgerState() {}

    public TenantLedgerState(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public void setLastSequence(long lastSequence) {
        this.lastSequence = lastSequence;
    }

    public String getLastHash() {
        return lastHash;
    }

    public void setLastHash(String lastHash) {
        this.lastHash = lastHash;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

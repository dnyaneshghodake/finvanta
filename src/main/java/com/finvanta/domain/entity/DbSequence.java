package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Database-Backed Sequence per Finacle SEQ_MASTER / Temenos EB.SEQUENCE pattern.
 *
 * Provides globally unique, monotonically increasing sequence numbers backed by
 * a database row with pessimistic locking. This eliminates all in-memory sequence
 * collision risks across:
 *   - JVM restarts (sequence survives in DB)
 *   - Multiple JVM instances (pessimistic lock serializes allocation)
 *   - Cluster/HA deployments (single source of truth in shared DB)
 *
 * Each sequence is identified by (tenant_id, sequence_name). Common sequences:
 *   - VOUCHER_{branchCode}_{YYYYMMDD} — daily voucher number per branch
 *   - TXN_REF    — transaction reference numbers
 *   - JRN_REF    — journal entry reference numbers
 *   - ACCT_REF   — loan account numbers
 *   - APP_REF    — application numbers
 *   - CUST_REF   — customer CIF numbers
 *   - LEDGER_SEQ — immutable ledger sequence
 *
 * Per Finacle SEQ_MASTER: sequence rows are pre-created on first use (lazy init).
 * The pessimistic lock on the row serializes concurrent allocations, ensuring
 * no two transactions ever get the same sequence value.
 *
 * Production: For extreme throughput (>10K TPS), use database-native sequences
 * (CREATE SEQUENCE) with CACHE option. The table-based approach supports
 * ~1K TPS which is sufficient for most CBS deployments.
 */
@Entity
@Table(name = "db_sequences",
    indexes = {
        @Index(name = "idx_dbseq_tenant_name", columnList = "tenant_id, sequence_name", unique = true)
    }
)
@Getter
@Setter
@NoArgsConstructor
public class DbSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    /**
     * Sequence identifier. Convention:
     *   VOUCHER_{branchCode}_{YYYYMMDD} — resets daily per branch
     *   TXN_REF / JRN_REF / ACCT_REF   — global, never resets
     */
    @Column(name = "sequence_name", nullable = false, length = 100)
    private String sequenceName;

    /** Current value — always the LAST allocated number. Next = currentValue + 1. */
    @Column(name = "current_value", nullable = false)
    private long currentValue = 0;

    /**
     * Row version for DDL compatibility. Not used for optimistic locking —
     * this entity relies exclusively on PESSIMISTIC_WRITE via the repository.
     * The @Version annotation is intentionally omitted to avoid spurious
     * OptimisticLockException on top of the pessimistic lock.
     */
    @Column(name = "version", nullable = false)
    private long version = 0;
}

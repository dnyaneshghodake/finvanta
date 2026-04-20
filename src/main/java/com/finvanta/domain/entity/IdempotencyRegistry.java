package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Tier-1 Engine-Level Idempotency Registry per Finacle UNIQUE.REF / Temenos OFS.DUPLICATE.CHECK.
 *
 * <p>Provides cross-module duplicate detection at the TransactionEngine level.
 * Every transaction with a non-null idempotencyKey is registered here BEFORE
 * any GL posting. If a duplicate key is detected, the engine returns the
 * previous TransactionResult without re-posting.
 *
 * <p><b>Why engine-level (not just module-level):</b>
 * <ul>
 *   <li>Module-level idempotency (unique constraints on deposit_transactions,
 *       loan_transactions) only catches duplicates WITHIN a module</li>
 *   <li>A future Remittance module that forgets to add idempotency = duplicate GL postings</li>
 *   <li>Engine-level dedup is the single enforcement point — no module can bypass it</li>
 * </ul>
 *
 * <p><b>Schema:</b> Unique constraint on (tenant_id, idempotency_key) with filtered
 * index (WHERE idempotency_key IS NOT NULL) to allow system-generated transactions
 * without keys.
 *
 * @see com.finvanta.transaction.TransactionEngine
 */
@Entity
@Table(
        name = "idempotency_registry",
        indexes = {
            @Index(name = "idx_idem_tenant_key", columnList = "tenant_id, idempotency_key", unique = true),
            @Index(name = "idx_idem_tenant_txnref", columnList = "tenant_id, transaction_ref")
        })
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRegistry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    /** The client-supplied idempotency key — unique per tenant. */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    /** The transaction reference returned for this key. */
    @Column(name = "transaction_ref", nullable = false, length = 40)
    private String transactionRef;

    /** The voucher number returned for this key (null if PENDING_APPROVAL). */
    @Column(name = "voucher_number", length = 50)
    private String voucherNumber;

    /** The journal entry ID returned for this key (null if PENDING_APPROVAL). */
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    /** The posting status (POSTED, PENDING_APPROVAL). */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    /** Source module that created this entry. */
    @Column(name = "source_module", length = 50)
    private String sourceModule;

    /** When this entry was created. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

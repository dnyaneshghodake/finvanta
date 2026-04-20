package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Tier-1 Transaction Outbox per Finacle EVENT_QUEUE / Temenos EB.EVENT.
 *
 * <p><b>Outbox Pattern:</b> Every committed transaction inserts a row into this
 * table within the SAME database transaction as the GL posting. A separate
 * async process (scheduled job or CDC listener) reads unpublished events and
 * dispatches them to downstream consumers:
 * <ul>
 *   <li>Reconciliation engine (GL↔subledger check)</li>
 *   <li>FIU-IND CTR reporting (cash ≥ ₹10L)</li>
 *   <li>Fraud monitoring / AML velocity checks</li>
 *   <li>Customer notifications (SMS/email)</li>
 *   <li>Regulatory reporting aggregation</li>
 * </ul>
 *
 * <p><b>Why outbox (not direct event publishing):</b>
 * Direct event publishing (e.g., Kafka produce) inside the DB transaction
 * creates a dual-write problem: if the DB commits but Kafka fails, the event
 * is lost. If Kafka succeeds but DB rolls back, a phantom event is published.
 * The outbox pattern guarantees exactly-once delivery by making the event
 * INSERT part of the same ACID transaction as the GL posting.
 *
 * <p><b>Processing:</b> A scheduled job polls for status=PENDING events,
 * processes them, and marks them PUBLISHED. Failed events are retried up to
 * maxRetries times before being marked FAILED for manual investigation.
 *
 * @see com.finvanta.transaction.TransactionEngine
 */
@Entity
@Table(
        name = "transaction_outbox",
        indexes = {
            @Index(name = "idx_outbox_tenant_status", columnList = "tenant_id, status"),
            @Index(name = "idx_outbox_created", columnList = "created_at"),
            @Index(name = "idx_outbox_txnref", columnList = "tenant_id, transaction_ref")
        })
@Getter
@Setter
@NoArgsConstructor
public class TransactionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    /** Event type: TRANSACTION_POSTED, CTR_REPORTABLE, LARGE_VALUE, REVERSAL, etc. */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** Transaction reference from TransactionEngine */
    @Column(name = "transaction_ref", nullable = false, length = 40)
    private String transactionRef;

    /** Voucher number for cross-reference */
    @Column(name = "voucher_number", length = 50)
    private String voucherNumber;

    /** Journal entry ID for GL traceability */
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    /** Source module: DEPOSIT, LOAN, CLEARING, etc. */
    @Column(name = "source_module", length = 50)
    private String sourceModule;

    /** Transaction type: CASH_DEPOSIT, CASH_WITHDRAWAL, etc. */
    @Column(name = "transaction_type", length = 30)
    private String transactionType;

    /** Account reference */
    @Column(name = "account_reference", length = 40)
    private String accountReference;

    /** Transaction amount */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    /** Branch code */
    @Column(name = "branch_code", length = 20)
    private String branchCode;

    /** Value date */
    @Column(name = "value_date")
    private LocalDate valueDate;

    /** RBI compliance flags (bitmask: 1=CTR, 2=LARGE_VALUE) */
    @Column(name = "rbi_flags")
    private int rbiFlags;

    /** Processing status: PENDING, PUBLISHED, FAILED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    /** Number of processing attempts */
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** Last processing error (for FAILED events) */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /** When the event was published (null if not yet published) */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}

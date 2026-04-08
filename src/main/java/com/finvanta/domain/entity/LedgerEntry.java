package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Immutable Ledger Entry per Finacle/Temenos standards.
 *
 * The ledger is an append-only, chronological record of every GL movement.
 * Unlike journal entries (which group related DR/CR lines), each ledger entry
 * is a single-line record of one debit or credit to one GL account.
 *
 * Key properties:
 * - APPEND-ONLY: No updates, no deletes (enforced by DB triggers in production)
 * - HASH CHAIN: Each entry contains SHA-256 hash linking to previous entry (tamper detection)
 * - CHRONOLOGICAL: Ordered by ledger_sequence (monotonically increasing)
 * - AUDITABLE: Every entry links back to its source journal for full traceability
 *
 * Per RBI audit requirements:
 * - Ledger must be independently verifiable against GL master balances
 * - Hash chain must be intact — any break indicates tampering
 * - Retention: minimum 8 years per RBI data retention guidelines
 *
 * Example:
 *   Seq 1: GL 1001 (Loan Asset) DEBIT ₹10,00,000 — Loan disbursement
 *   Seq 2: GL 1100 (Bank Ops)   CREDIT ₹10,00,000 — Loan disbursement
 *   Seq 3: GL 1002 (Int Recv)   DEBIT ₹2,740      — Daily interest accrual
 *   Seq 4: GL 4001 (Int Income) CREDIT ₹2,740      — Daily interest accrual
 *
 * No @Version column — ledger entries are immutable (no optimistic locking needed).
 */
@Entity
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_ledger_tenant_gl", columnList = "tenant_id, gl_code, business_date"),
    @Index(name = "idx_ledger_tenant_date", columnList = "tenant_id, business_date"),
    @Index(name = "idx_ledger_journal", columnList = "tenant_id, journal_entry_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_ledger_tenant_seq", columnNames = {"tenant_id", "ledger_sequence"})
})
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    /** Monotonically increasing sequence — global ordering within tenant */
    @Column(name = "ledger_sequence", nullable = false)
    private long ledgerSequence;

    /** Source journal entry that created this ledger record */
    @Column(name = "journal_entry_id", nullable = false)
    private Long journalEntryId;

    /** Journal reference for human-readable tracing */
    @Column(name = "journal_ref", nullable = false, length = 40)
    private String journalRef;

    /** GL code this entry affects */
    @Column(name = "gl_code", nullable = false, length = 20)
    private String glCode;

    /** GL account name (denormalized for reporting without joins) */
    @Column(name = "gl_name", length = 200)
    private String glName;

    /** Account reference (loan account number, customer number, etc.) */
    @Column(name = "account_reference", length = 40)
    private String accountReference;

    /** CBS business date — the date this entry belongs to */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Value date — the effective date of the transaction */
    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    /** Debit amount (zero if this is a credit entry) */
    @Column(name = "debit_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    /** Credit amount (zero if this is a debit entry) */
    @Column(name = "credit_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    /** Running balance of the GL account after this entry */
    @Column(name = "running_balance", precision = 18, scale = 2)
    private BigDecimal runningBalance;

    /** Source module: LOAN, SUSPENSE, PROVISIONING, etc. */
    @Column(name = "module_code", length = 50)
    private String moduleCode;

    /** Narration/description */
    @Column(name = "narration", length = 500)
    private String narration;

    /** SHA-256 hash of this entry's data for tamper detection */
    @Column(name = "hash_value", nullable = false, length = 64)
    private String hashValue;

    /** Hash of the previous ledger entry — forms the chain */
    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    /** Timestamp when this ledger entry was created */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Who/what created this entry */
    @Column(name = "created_by", length = 100)
    private String createdBy;
}
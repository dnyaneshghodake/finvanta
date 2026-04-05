package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Enterprise Transaction Batch Control per Finacle/Temenos standards.
 *
 * Batch lifecycle:
 *   DAY_OPEN → BATCH_OPEN (one or more) → TRANSACTIONS_ALLOWED → BATCH_CLOSE (all)
 *   → EOD_PROCESSING → DAY_CLOSE → NEXT_DAY_OPEN
 *
 * Rules:
 * - No financial transaction can be posted unless tagged to an OPEN batch
 * - EOD cannot start unless ALL batches for the business date are CLOSED
 * - Closed batches are immutable — no reopening
 * - Each batch tracks total debit/credit for intra-day reconciliation
 * - Unique constraint: (tenant_id, business_date, batch_name)
 *
 * Batch types:
 *   INTRA_DAY  — Regular branch operations (deposits, repayments, disbursements)
 *   CLEARING   — RTGS/NEFT/IMPS settlement cycles
 *   SYSTEM     — EOD system-generated batches (accrual, provisioning)
 *
 * Example:
 *   Business Date: 4 April
 *   MORNING_BATCH (INTRA_DAY) → OPEN 9:00 → Customer deposits ₹50,000 → CLOSE 13:00
 *   AFTERNOON_BATCH (INTRA_DAY) → OPEN 13:00 → Loan repayments → CLOSE 17:00
 *   EOD_SYSTEM_BATCH (SYSTEM) → OPEN by EOD → Accrual, NPA, Provisioning → CLOSE by EOD
 *   → All CLOSED → EOD completes → Day Close
 */
@Entity
@Table(name = "transaction_batches", indexes = {
    @Index(name = "idx_txnbatch_tenant_date", columnList = "tenant_id, business_date"),
    @Index(name = "idx_txnbatch_tenant_date_status", columnList = "tenant_id, business_date, status"),
    @Index(name = "idx_txnbatch_branch", columnList = "tenant_id, branch_id, business_date")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_txnbatch_tenant_date_name",
        columnNames = {"tenant_id", "business_date", "batch_name"})
})
@Getter
@Setter
@NoArgsConstructor
public class TransactionBatch extends BaseEntity {

    /** Branch this batch belongs to (null = tenant-level/system batch) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    /** CBS business date — NOT system date */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Unique batch name per business date: MORNING_BATCH, AFTERNOON_BATCH, EOD_SYSTEM, etc. */
    @Column(name = "batch_name", nullable = false, length = 50)
    private String batchName;

    /**
     * Batch type per CBS classification:
     *   INTRA_DAY — Regular branch operations
     *   CLEARING  — RTGS/NEFT/IMPS settlement
     *   SYSTEM    — EOD automated batches
     */
    @Column(name = "batch_type", nullable = false, length = 20)
    private String batchType;

    /**
     * Batch status lifecycle: OPEN → CLOSED / CANCELLED
     * Once CLOSED, batch is immutable — cannot be reopened.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** Running total of transactions in this batch */
    @Column(name = "total_transactions", nullable = false)
    private int totalTransactions = 0;

    /** Running total debit amount — must equal total_credit at batch close */
    @Column(name = "total_debit", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDebit = BigDecimal.ZERO;

    /** Running total credit amount — must equal total_debit at batch close */
    @Column(name = "total_credit", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCredit = BigDecimal.ZERO;

    /** Maker-checker: who requested the batch operation */
    @Column(name = "maker_id", length = 100)
    private String makerId;

    /** Maker-checker: who approved the batch operation */
    @Column(name = "checker_id", length = 100)
    private String checkerId;

    /** Approval status for maker-checker control */
    @Column(name = "approval_status", length = 20)
    private String approvalStatus;

    /** Remarks/notes for the batch */
    @Column(name = "remarks", length = 500)
    private String remarks;

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public boolean isClosed() {
        return "CLOSED".equals(status);
    }

    /**
     * Returns true if batch debit/credit totals are balanced.
     * Required for batch close validation.
     */
    public boolean isBalanced() {
        return totalDebit.compareTo(totalCredit) == 0;
    }

    /**
     * Increments batch totals when a transaction is posted to this batch.
     */
    public void addTransaction(BigDecimal debit, BigDecimal credit) {
        this.totalTransactions++;
        this.totalDebit = this.totalDebit.add(debit);
        this.totalCredit = this.totalCredit.add(credit);
    }
}
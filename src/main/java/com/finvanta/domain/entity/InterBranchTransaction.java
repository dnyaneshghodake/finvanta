package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * CBS Inter-Branch Transaction — Settlement ledger per Finacle IB_SETTLEMENT.
 *
 * Records funds transfers between branches with dual GL posting:
 * - Source branch: DR Customer / CR Inter-Branch Payable (GL 2300)
 * - Target branch: DR Inter-Branch Receivable (GL 1300) / CR Customer
 *
 * Settlement workflow:
 * 1. Transaction recorded (PENDING status)
 * 2. EOD settlement: sum branch receivables/payables; validate balanced
 * 3. If balanced: SETTLED; if not: FAILED (log for investigation)
 *
 * Per Finacle IB_SETTLEMENT: Each inter-branch transfer has dual journals
 * at source and target branches with automatic netting at EOD.
 * This table tracks settlement matching and error conditions.
 */
@Entity
@Table(
        name = "inter_branch_transactions",
        indexes = {
            @Index(
                    name = "idx_ibxfr_tenant_sourcetarget",
                    columnList = "tenant_id, source_branch_id, target_branch_id"),
            @Index(name = "idx_ibxfr_settlement_status", columnList = "settlement_status"),
            @Index(name = "idx_ibxfr_business_date", columnList = "business_date")
        })
@Getter
@Setter
public class InterBranchTransaction extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_branch_id", nullable = false)
    private Branch sourceBranch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_branch_id", nullable = false)
    private Branch targetBranch;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount; // Transfer amount (gross)

    @Column(name = "source_journal_id")
    private Long sourceJournalId; // FK to journal_entries (at source branch)

    @Column(name = "target_journal_id")
    private Long targetJournalId; // FK to journal_entries (at target branch)

    @Column(name = "settlement_status", length = 20, nullable = false)
    private String settlementStatus; // PENDING, SETTLED, FAILED

    @Column(name = "settlement_batch_ref", length = 50)
    private String settlementBatchRef; // Reference to settlement batch (for linking multiple transfers)

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate; // CBS business date

    @Column(name = "failure_reason", length = 500)
    private String failureReason; // If FAILED, reason for settlement failure
}

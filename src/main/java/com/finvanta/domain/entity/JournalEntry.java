package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Journal Entry per Finacle TRAN_DETAIL / Temenos STMT.ENTRY.
 *
 * Every financial posting creates a journal entry with balanced DR/CR lines.
 * Per Tier-1 CBS (Finacle/Temenos/BNP): every journal entry is tagged to the
 * originating branch for branch-level accounting and audit trail.
 *
 * Branch attribution rules:
 * - Single-branch transaction: journal tagged to the account's branch
 * - Inter-branch transaction: TWO journal entries created — one per branch
 *   (linked by source_ref), with Inter-Branch Payable/Receivable GLs
 * - System/EOD transactions: tagged to the account's branch (not HO)
 *
 * Per RBI audit requirements: journal entries are immutable once posted.
 * Corrections are made via reversal entries (new journal with contra lines).
 */
@Entity
@Table(
        name = "journal_entries",
        indexes = {
            @Index(name = "idx_je_tenant_ref", columnList = "tenant_id, journal_ref", unique = true),
            @Index(name = "idx_je_value_date", columnList = "tenant_id, value_date"),
            @Index(name = "idx_je_posting_date", columnList = "tenant_id, posting_date"),
            @Index(name = "idx_je_tenant_branch", columnList = "tenant_id, branch_id, value_date"),
            @Index(name = "idx_je_branch_module", columnList = "tenant_id, branch_id, source_module")
        })
@Getter
@Setter
@NoArgsConstructor
public class JournalEntry extends BaseEntity {

    @Column(name = "journal_ref", nullable = false, length = 40)
    private String journalRef;

    /**
     * Originating branch for this journal entry.
     * Per Finacle TRAN_DETAIL: every GL posting is attributed to a branch (SOL).
     * This enables branch-level Day Book, trial balance, and P&L generation.
     *
     * For inter-branch transactions, each branch gets its own journal entry.
     * The two entries are linked via source_ref (account number) and can be
     * queried together for inter-branch settlement reconciliation.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /**
     * Branch code denormalized for efficient reporting and display.
     * Avoids joining to branches table for every journal listing query.
     */
    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "posting_date", nullable = false)
    private LocalDateTime postingDate;

    @Column(name = "narration", nullable = false, length = 500)
    private String narration;

    @Column(name = "total_debit", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDebit = BigDecimal.ZERO;

    @Column(name = "total_credit", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCredit = BigDecimal.ZERO;

    @Column(name = "source_module", length = 50)
    private String sourceModule;

    @Column(name = "source_ref", length = 100)
    private String sourceRef;

    @Column(name = "is_reversed", nullable = false)
    private boolean reversed = false;

    @Column(name = "is_posted", nullable = false)
    private boolean posted = false;

    /**
     * CBS Tier-1: Voucher number linking this journal to the voucher register.
     * Per Finacle TRAN_DETAIL / RBI Audit: every journal entry must carry the voucher
     * reference for the mandatory Transaction ↔ Journal ↔ Voucher ↔ Ledger linkage chain.
     * Set by AccountingService.postJournalEntry() from the pre-allocated voucher number.
     * Without this, RBI auditors cannot reconcile the voucher register with the GL.
     */
    @Column(name = "voucher_number", length = 50)
    private String voucherNumber;

    /**
     * CBS Tier-1: Transaction reference linking this journal back to the engine-level
     * transaction. Enables Transaction ↔ Journal bidirectional traceability.
     * Set by AccountingService.postJournalEntry() from the pre-allocated txn ref.
     */
    @Column(name = "transaction_ref", length = 40)
    private String transactionRef;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalEntryLine> lines = new ArrayList<>();

    public void addLine(JournalEntryLine line) {
        lines.add(line);
        line.setJournalEntry(this);
    }
}

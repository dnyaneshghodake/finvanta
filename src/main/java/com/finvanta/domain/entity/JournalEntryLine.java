package com.finvanta.domain.entity;

import com.finvanta.domain.enums.DebitCredit;

import jakarta.persistence.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "journal_entry_lines",
        indexes = {
            @Index(name = "idx_jel_journal", columnList = "journal_entry_id"),
            @Index(name = "idx_jel_gl", columnList = "tenant_id, gl_code")
        })
@Getter
@Setter
@NoArgsConstructor
public class JournalEntryLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry journalEntry;

    @Column(name = "gl_code", nullable = false, length = 20)
    private String glCode;

    @Column(name = "gl_name", length = 200)
    private String glName;

    @Enumerated(EnumType.STRING)
    @Column(name = "debit_credit", nullable = false, length = 10)
    private DebitCredit debitCredit;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "narration", length = 500)
    private String narration;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;
}

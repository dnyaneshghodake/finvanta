package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entries", indexes = {
    @Index(name = "idx_je_tenant_ref", columnList = "tenant_id, journal_ref", unique = true),
    @Index(name = "idx_je_value_date", columnList = "tenant_id, value_date"),
    @Index(name = "idx_je_posting_date", columnList = "tenant_id, posting_date")
})
@Getter
@Setter
@NoArgsConstructor
public class JournalEntry extends BaseEntity {

    @Column(name = "journal_ref", nullable = false, length = 40)
    private String journalRef;

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

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalEntryLine> lines = new ArrayList<>();

    public void addLine(JournalEntryLine line) {
        lines.add(line);
        line.setJournalEntry(this);
    }
}

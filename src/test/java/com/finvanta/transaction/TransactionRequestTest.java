package com.finvanta.transaction;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.domain.enums.DebitCredit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TransactionRequest Builder validation tests.
 * Ensures CBS mandatory fields are enforced at build time.
 */
class TransactionRequestTest {

    private List<JournalLineRequest> validLines() {
        return List.of(
                new JournalLineRequest("1100", DebitCredit.DEBIT, new BigDecimal("100000"), "DR"),
                new JournalLineRequest("1001", DebitCredit.CREDIT, new BigDecimal("100000"), "CR"));
    }

    @Test
    @DisplayName("Valid request builds successfully")
    void validRequest() {
        TransactionRequest req = TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("DISBURSEMENT")
                .accountReference("LN001")
                .amount(new BigDecimal("100000"))
                .valueDate(LocalDate.of(2026, 4, 1))
                .branchCode("HQ001")
                .narration("Test disbursement")
                .journalLines(validLines())
                .build();

        assertEquals("LOAN", req.getSourceModule());
        assertEquals(new BigDecimal("100000"), req.getAmount());
        assertFalse(req.isSystemGenerated());
    }

    @Test
    @DisplayName("Null amount rejected")
    void nullAmount() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("TEST")
                .accountReference("LN001")
                .valueDate(LocalDate.now())
                .narration("test")
                .journalLines(validLines())
                .build());
    }

    @Test
    @DisplayName("Zero amount rejected")
    void zeroAmount() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("TEST")
                .accountReference("LN001")
                .amount(BigDecimal.ZERO)
                .valueDate(LocalDate.now())
                .narration("test")
                .journalLines(validLines())
                .build());
    }

    @Test
    @DisplayName("Missing source module rejected")
    void missingModule() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .transactionType("TEST")
                .accountReference("LN001")
                .amount(new BigDecimal("1000"))
                .valueDate(LocalDate.now())
                .narration("test")
                .journalLines(validLines())
                .build());
    }

    @Test
    @DisplayName("Missing narration rejected per CBS audit rules")
    void missingNarration() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("TEST")
                .accountReference("LN001")
                .amount(new BigDecimal("1000"))
                .valueDate(LocalDate.now())
                .journalLines(validLines())
                .build());
    }

    @Test
    @DisplayName("Single journal line rejected (must be double-entry)")
    void singleLine() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("TEST")
                .accountReference("LN001")
                .amount(new BigDecimal("1000"))
                .valueDate(LocalDate.now())
                .narration("test")
                .journalLines(List.of(new JournalLineRequest("1100", DebitCredit.DEBIT, new BigDecimal("1000"), "DR")))
                .build());
    }

    @Test
    @DisplayName("Missing account reference rejected per CBS audit rules")
    void missingAccountReference() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("TEST")
                .amount(new BigDecimal("1000"))
                .valueDate(LocalDate.now())
                .narration("test")
                .journalLines(validLines())
                .build());
    }

    @Test
    @DisplayName("Missing transaction type rejected")
    void missingTransactionType() {
        assertThrows(IllegalArgumentException.class, () -> TransactionRequest.builder()
                .sourceModule("LOAN")
                .accountReference("LN001")
                .amount(new BigDecimal("1000"))
                .valueDate(LocalDate.now())
                .narration("test")
                .journalLines(validLines())
                .build());
    }

    @Test
    @DisplayName("System-generated flag is set correctly")
    void systemGenerated() {
        TransactionRequest req = TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("ACCRUAL")
                .accountReference("LN001")
                .amount(new BigDecimal("274"))
                .valueDate(LocalDate.now())
                .narration("Interest accrual")
                .journalLines(validLines())
                .systemGenerated(true)
                .build();

        assertTrue(req.isSystemGenerated());
    }
}

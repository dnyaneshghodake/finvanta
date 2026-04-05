package com.finvanta.accounting;

import com.finvanta.util.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Double-Entry Validation Tests for AccountingService.
 *
 * Per CBS/Finacle/Temenos accounting engine standards:
 * - Every journal entry must have DR total == CR total
 * - Imbalanced entries must be rejected before posting
 *
 * These tests validate the validateDoubleEntry() method independently
 * of the full postJournalEntry() flow (which requires Spring context).
 */
class AccountingServiceDoubleEntryTest {

    private AccountingService accountingService;

    @BeforeEach
    void setUp() {
        // Create with null dependencies — we only test validateDoubleEntry()
        accountingService = new AccountingService(null, null, null, null, null);
    }

    @Test
    @DisplayName("Balanced entry passes validation")
    void balancedEntry_passes() {
        assertDoesNotThrow(() ->
            accountingService.validateDoubleEntry(
                new BigDecimal("100000.00"),
                new BigDecimal("100000.00")));
    }

    @Test
    @DisplayName("Imbalanced entry throws ACCOUNTING_IMBALANCE")
    void imbalancedEntry_throws() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
            accountingService.validateDoubleEntry(
                new BigDecimal("100000.00"),
                new BigDecimal("99999.99")));
        assertEquals("ACCOUNTING_IMBALANCE", ex.getErrorCode());
    }

    @Test
    @DisplayName("Zero debit and credit passes (edge case)")
    void zeroBalanced_passes() {
        assertDoesNotThrow(() ->
            accountingService.validateDoubleEntry(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Large amounts: ₹50 crore balanced passes")
    void largeAmounts_balanced_passes() {
        BigDecimal fiftyCore = new BigDecimal("500000000.00");
        assertDoesNotThrow(() ->
            accountingService.validateDoubleEntry(fiftyCore, fiftyCore));
    }

    @Test
    @DisplayName("One paisa imbalance is detected")
    void onePaisaImbalance_detected() {
        assertThrows(BusinessException.class, () ->
            accountingService.validateDoubleEntry(
                new BigDecimal("1000000.00"),
                new BigDecimal("1000000.01")));
    }
}

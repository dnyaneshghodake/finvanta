package com.finvanta.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LoanStatus enum behavior tests per RBI IRAC lifecycle rules.
 * Validates classification helpers used throughout the CBS engine.
 */
class LoanStatusTest {

    @Test
    @DisplayName("NPA statuses: only Sub-Standard, Doubtful, Loss")
    void isNpa() {
        assertTrue(LoanStatus.NPA_SUBSTANDARD.isNpa());
        assertTrue(LoanStatus.NPA_DOUBTFUL.isNpa());
        assertTrue(LoanStatus.NPA_LOSS.isNpa());

        assertFalse(LoanStatus.ACTIVE.isNpa());
        assertFalse(LoanStatus.SMA_0.isNpa());
        assertFalse(LoanStatus.SMA_1.isNpa());
        assertFalse(LoanStatus.SMA_2.isNpa());
        assertFalse(LoanStatus.CLOSED.isNpa());
        assertFalse(LoanStatus.WRITTEN_OFF.isNpa());
        assertFalse(LoanStatus.RESTRUCTURED.isNpa());
    }

    @Test
    @DisplayName("SMA statuses: only SMA-0, SMA-1, SMA-2")
    void isSma() {
        assertTrue(LoanStatus.SMA_0.isSma());
        assertTrue(LoanStatus.SMA_1.isSma());
        assertTrue(LoanStatus.SMA_2.isSma());

        assertFalse(LoanStatus.ACTIVE.isSma());
        assertFalse(LoanStatus.NPA_SUBSTANDARD.isSma());
        assertFalse(LoanStatus.RESTRUCTURED.isSma());
    }

    @Test
    @DisplayName("Terminal states: only CLOSED and WRITTEN_OFF")
    void isTerminal() {
        assertTrue(LoanStatus.CLOSED.isTerminal());
        assertTrue(LoanStatus.WRITTEN_OFF.isTerminal());

        assertFalse(LoanStatus.ACTIVE.isTerminal());
        assertFalse(LoanStatus.NPA_LOSS.isTerminal());
        assertFalse(LoanStatus.RESTRUCTURED.isTerminal());
    }

    @Test
    @DisplayName("Performing: ACTIVE + SMA + RESTRUCTURED")
    void isPerforming() {
        assertTrue(LoanStatus.ACTIVE.isPerforming());
        assertTrue(LoanStatus.SMA_0.isPerforming());
        assertTrue(LoanStatus.SMA_1.isPerforming());
        assertTrue(LoanStatus.SMA_2.isPerforming());
        assertTrue(LoanStatus.RESTRUCTURED.isPerforming());

        assertFalse(LoanStatus.NPA_SUBSTANDARD.isPerforming());
        assertFalse(LoanStatus.NPA_DOUBTFUL.isPerforming());
        assertFalse(LoanStatus.NPA_LOSS.isPerforming());
        assertFalse(LoanStatus.CLOSED.isPerforming());
        assertFalse(LoanStatus.WRITTEN_OFF.isPerforming());
    }

    @Test
    @DisplayName("Income reversal required: same as NPA")
    void isIncomeReversalRequired() {
        assertTrue(LoanStatus.NPA_SUBSTANDARD.isIncomeReversalRequired());
        assertTrue(LoanStatus.NPA_DOUBTFUL.isIncomeReversalRequired());
        assertTrue(LoanStatus.NPA_LOSS.isIncomeReversalRequired());

        assertFalse(LoanStatus.ACTIVE.isIncomeReversalRequired());
        assertFalse(LoanStatus.SMA_2.isIncomeReversalRequired());
        assertFalse(LoanStatus.RESTRUCTURED.isIncomeReversalRequired());
    }

    @ParameterizedTest
    @EnumSource(LoanStatus.class)
    @DisplayName("Every status has a defined classification group")
    void everyStatusHasClassification(LoanStatus status) {
        // Every status must be in exactly one of: active, sma, npa, terminal, restructured
        int groups = 0;
        if (status.isActive()) groups++;
        if (status.isSma()) groups++;
        if (status.isNpa()) groups++;
        if (status.isTerminal()) groups++;
        if (status == LoanStatus.RESTRUCTURED) groups++;

        assertEquals(1, groups,
            status + " must belong to exactly one classification group");
    }
}

package com.finvanta.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DayStatus enum behavior tests per Finacle/Temenos Day Control lifecycle.
 *
 * Lifecycle: NOT_OPENED → DAY_OPEN → EOD_RUNNING → DAY_CLOSED
 */
class DayStatusTest {

    @Test
    @DisplayName("Only DAY_OPEN allows transactions")
    void isTransactionAllowed() {
        assertTrue(DayStatus.DAY_OPEN.isTransactionAllowed());

        assertFalse(DayStatus.NOT_OPENED.isTransactionAllowed());
        assertFalse(DayStatus.EOD_RUNNING.isTransactionAllowed());
        assertFalse(DayStatus.DAY_CLOSED.isTransactionAllowed());
    }

    @Test
    @DisplayName("Only DAY_OPEN can start EOD")
    void canStartEod() {
        assertTrue(DayStatus.DAY_OPEN.canStartEod());

        assertFalse(DayStatus.NOT_OPENED.canStartEod());
        assertFalse(DayStatus.EOD_RUNNING.canStartEod());
        assertFalse(DayStatus.DAY_CLOSED.canStartEod());
    }

    @Test
    @DisplayName("DAY_OPEN and EOD_RUNNING can close")
    void canClose() {
        assertTrue(DayStatus.DAY_OPEN.canClose());
        assertTrue(DayStatus.EOD_RUNNING.canClose());

        assertFalse(DayStatus.NOT_OPENED.canClose());
        assertFalse(DayStatus.DAY_CLOSED.canClose());
    }

    @Test
    @DisplayName("Lifecycle transitions are valid")
    void lifecycleTransitions() {
        // NOT_OPENED → DAY_OPEN: valid (day open)
        assertFalse(DayStatus.NOT_OPENED.isTransactionAllowed());
        assertTrue(DayStatus.DAY_OPEN.isTransactionAllowed());

        // DAY_OPEN → EOD_RUNNING: valid (EOD starts)
        assertTrue(DayStatus.DAY_OPEN.canStartEod());
        assertFalse(DayStatus.EOD_RUNNING.isTransactionAllowed());

        // EOD_RUNNING → DAY_CLOSED: valid (EOD completes)
        assertTrue(DayStatus.EOD_RUNNING.canClose());
        assertFalse(DayStatus.DAY_CLOSED.canClose()); // terminal
    }
}

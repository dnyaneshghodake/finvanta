package com.finvanta.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BusinessException Tests — CBS error code propagation.
 *
 * Per CBS standards, every business error must carry a machine-readable
 * error code for programmatic handling and a human-readable message for display.
 */
class BusinessExceptionTest {

    @Test
    @DisplayName("Error code and message are preserved")
    void errorCodeAndMessage() {
        BusinessException ex = new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: LN001");
        assertEquals("ACCOUNT_NOT_FOUND", ex.getErrorCode());
        assertEquals("Loan account not found: LN001", ex.getMessage());
    }

    @Test
    @DisplayName("Cause is preserved in 3-arg constructor")
    void causePreserved() {
        RuntimeException cause = new RuntimeException("DB connection failed");
        BusinessException ex = new BusinessException("DB_ERROR", "Database error", cause);
        assertEquals("DB_ERROR", ex.getErrorCode());
        assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("BusinessException is a RuntimeException (unchecked)")
    void isRuntimeException() {
        BusinessException ex = new BusinessException("TEST", "test");
        assertTrue(ex instanceof RuntimeException);
    }
}

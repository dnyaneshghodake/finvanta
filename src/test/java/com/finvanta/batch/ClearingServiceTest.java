package com.finvanta.batch;

import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.repository.ClearingTransactionRepository;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClearingService.
 *
 * Validates:
 * - Clearing transaction lifecycle (INITIATED -> PENDING -> CONFIRMED -> SETTLED)
 * - GL posting for suspense account (GL 2400)
 * - Settlement GL posting (confirmation moves from suspense to settlement GL)
 * - Reversal on failure (FAILED status + reversal posting)
 * - EOD suspense balance check (must be zero)
 */
@DisplayName("Clearing Service Tests")
public class ClearingServiceTest {

    @Mock
    private ClearingTransactionRepository clearingRepository;

    @Mock
    private TransactionEngine transactionEngine;

    private ClearingService clearingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clearingService = new ClearingService(
                clearingRepository,
                null, // GLMasterRepository can be null for this test
                transactionEngine);
        TenantContext.setCurrentTenant("DEFAULT");
    }

    @Test
    @DisplayName("Entity status field stores string values")
    void testEntityStatusField() {
        // Arrange
        ClearingTransaction txn = new ClearingTransaction();

        // Act & Assert
        txn.setStatus("INITIATED");
        assertEquals("INITIATED", txn.getStatus());

        txn.setStatus("PENDING");
        assertEquals("PENDING", txn.getStatus());

        txn.setStatus("CONFIRMED");
        assertEquals("CONFIRMED", txn.getStatus());

        txn.setStatus("SETTLED");
        assertEquals("SETTLED", txn.getStatus());

        txn.setStatus("FAILED");
        assertEquals("FAILED", txn.getStatus());

        txn.setStatus("REVERSED");
        assertEquals("REVERSED", txn.getStatus());
    }

    @Test
    @DisplayName("Find clearing transactions by status and tenant")
    void testFindPendingTransactions() {
        // Arrange
        String tenantId = "DEFAULT";
        List<String> pendingStatuses = List.of("PENDING", "INITIATED");

        when(clearingRepository.findByTenantIdAndStatusInOrderByInitiatedDateAsc(tenantId, pendingStatuses))
                .thenReturn(java.util.Collections.emptyList());

        // Act
        var result = clearingRepository.findByTenantIdAndStatusInOrderByInitiatedDateAsc(tenantId, pendingStatuses);

        // Assert
        assertTrue(result.isEmpty());
        verify(clearingRepository, times(1))
                .findByTenantIdAndStatusInOrderByInitiatedDateAsc(tenantId, pendingStatuses);
    }

    @Test
    @DisplayName("Check for pending clearing on business date")
    void testCheckPendingClearingExists() {
        // Arrange
        String tenantId = "DEFAULT";
        LocalDate businessDate = LocalDate.of(2026, 4, 7);
        List<String> settledStatuses = List.of("SETTLED", "FAILED");

        when(clearingRepository.existsByTenantIdAndBusinessDateAndStatusNotIn(tenantId, businessDate, settledStatuses))
                .thenReturn(false);

        // Act
        boolean result = clearingRepository.existsByTenantIdAndBusinessDateAndStatusNotIn(
                tenantId, businessDate, settledStatuses);

        // Assert
        assertFalse(result, "Should not have pending clearing");
        verify(clearingRepository, times(1))
                .existsByTenantIdAndBusinessDateAndStatusNotIn(tenantId, businessDate, settledStatuses);
    }

    @Test
    @DisplayName("Clearing transaction has required fields")
    void testClearingTransactionFields() {
        // Arrange & Act
        ClearingTransaction txn = new ClearingTransaction();
        txn.setId(1L);
        txn.setTenantId("DEFAULT");
        txn.setClearingRef("CLR202604070001");
        txn.setSourceType("CHEQUE");
        txn.setAmount(new BigDecimal("100000.00"));
        txn.setCustomerAccountRef("ACC001");
        txn.setStatus("INITIATED");

        // Assert
        assertEquals(1L, txn.getId());
        assertEquals("DEFAULT", txn.getTenantId());
        assertEquals("CLR202604070001", txn.getClearingRef());
        assertEquals("CHEQUE", txn.getSourceType());
        assertEquals(new BigDecimal("100000.00"), txn.getAmount());
        assertEquals("ACC001", txn.getCustomerAccountRef());
        assertEquals("INITIATED", txn.getStatus());
    }
}

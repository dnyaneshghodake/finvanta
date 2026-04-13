package com.finvanta.batch;

import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.PaymentRail;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClearingService (legacy) and ClearingStatus state machine.
 *
 * ClearingService is @Deprecated — only validateSuspenseBalance() remains.
 * ClearingEngine is the replacement; these tests validate:
 * - ClearingStatus state machine transitions (outward + inward flows)
 * - ClearingTransaction entity field access (new typed enum fields)
 * - Terminal state and suspense-active detection
 * - Failure/reversal transition paths
 */
@DisplayName("Clearing Service & Status Tests")
public class ClearingServiceTest {

    @Mock
    private GLMasterRepository glRepository;

    @SuppressWarnings("deprecation")
    private ClearingService clearingService;

    @BeforeEach
    @SuppressWarnings("deprecation")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clearingService = new ClearingService(glRepository);
        TenantContext.setCurrentTenant("DEFAULT");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("ClearingStatus enum stores all expected values")
    void testClearingStatusValues() {
        ClearingTransaction txn = new ClearingTransaction();

        txn.setStatus(ClearingStatus.INITIATED);
        assertEquals(ClearingStatus.INITIATED, txn.getStatus());

        txn.setStatus(ClearingStatus.VALIDATED);
        assertEquals(ClearingStatus.VALIDATED, txn.getStatus());

        txn.setStatus(ClearingStatus.SUSPENSE_POSTED);
        assertEquals(ClearingStatus.SUSPENSE_POSTED, txn.getStatus());

        txn.setStatus(ClearingStatus.SETTLED);
        assertEquals(ClearingStatus.SETTLED, txn.getStatus());

        txn.setStatus(ClearingStatus.COMPLETED);
        assertEquals(ClearingStatus.COMPLETED, txn.getStatus());

        txn.setStatus(ClearingStatus.REVERSED);
        assertEquals(ClearingStatus.REVERSED, txn.getStatus());
    }

    @Test
    @DisplayName("Outward state machine: valid transitions")
    void testOutwardStateTransitions() {
        assertTrue(ClearingStatus.INITIATED.canTransitionTo(ClearingStatus.VALIDATED));
        assertTrue(ClearingStatus.VALIDATED.canTransitionTo(ClearingStatus.SUSPENSE_POSTED));
        assertTrue(ClearingStatus.SUSPENSE_POSTED.canTransitionTo(ClearingStatus.SENT_TO_NETWORK));
        assertTrue(ClearingStatus.SENT_TO_NETWORK.canTransitionTo(ClearingStatus.SETTLED));
        assertTrue(ClearingStatus.SETTLED.canTransitionTo(ClearingStatus.COMPLETED));
        // Real-time rails can skip SENT_TO_NETWORK
        assertTrue(ClearingStatus.SUSPENSE_POSTED.canTransitionTo(ClearingStatus.COMPLETED));
    }

    @Test
    @DisplayName("Invalid state transitions are rejected")
    void testInvalidTransitions() {
        // No self-transition
        assertFalse(ClearingStatus.INITIATED.canTransitionTo(ClearingStatus.INITIATED));
        // No skipping states
        assertFalse(ClearingStatus.INITIATED.canTransitionTo(ClearingStatus.COMPLETED));
        assertFalse(ClearingStatus.INITIATED.canTransitionTo(ClearingStatus.SETTLED));
        // Terminal states cannot transition
        assertFalse(ClearingStatus.COMPLETED.canTransitionTo(ClearingStatus.REVERSED));
        assertFalse(ClearingStatus.REVERSED.canTransitionTo(ClearingStatus.COMPLETED));
        assertFalse(ClearingStatus.RETURNED.canTransitionTo(ClearingStatus.COMPLETED));
    }

    @Test
    @DisplayName("Terminal state detection")
    void testTerminalStates() {
        assertTrue(ClearingStatus.COMPLETED.isTerminal());
        assertTrue(ClearingStatus.REVERSED.isTerminal());
        assertTrue(ClearingStatus.RETURNED.isTerminal());
        assertTrue(ClearingStatus.NETWORK_REJECTED.isTerminal());

        // VALIDATION_FAILED is semi-terminal: allows → RETURNED only
        assertFalse(ClearingStatus.VALIDATION_FAILED.isTerminal());

        assertFalse(ClearingStatus.INITIATED.isTerminal());
        assertFalse(ClearingStatus.SUSPENSE_POSTED.isTerminal());
        assertFalse(ClearingStatus.SENT_TO_NETWORK.isTerminal());
    }

    @Test
    @DisplayName("Suspense active detection")
    void testSuspenseActiveStates() {
        assertTrue(ClearingStatus.SUSPENSE_POSTED.isSuspenseActive());
        assertTrue(ClearingStatus.SENT_TO_NETWORK.isSuspenseActive());
        assertTrue(ClearingStatus.SETTLED.isSuspenseActive());

        assertFalse(ClearingStatus.INITIATED.isSuspenseActive());
        assertFalse(ClearingStatus.COMPLETED.isSuspenseActive());
        assertFalse(ClearingStatus.REVERSED.isSuspenseActive());
    }

    @Test
    @DisplayName("Failure transitions are valid")
    void testFailureTransitions() {
        assertTrue(ClearingStatus.INITIATED.canTransitionTo(ClearingStatus.VALIDATION_FAILED));
        assertTrue(ClearingStatus.SENT_TO_NETWORK.canTransitionTo(ClearingStatus.NETWORK_REJECTED));
        assertTrue(ClearingStatus.SENT_TO_NETWORK.canTransitionTo(ClearingStatus.SETTLEMENT_FAILED));
        assertTrue(ClearingStatus.SETTLEMENT_FAILED.canTransitionTo(ClearingStatus.REVERSED));
        assertTrue(ClearingStatus.CREDIT_FAILED.canTransitionTo(ClearingStatus.RETURNED));
        // VALIDATION_FAILED is semi-terminal: allows only RETURNED
        assertTrue(ClearingStatus.VALIDATION_FAILED.canTransitionTo(ClearingStatus.RETURNED));
        assertFalse(ClearingStatus.VALIDATION_FAILED.canTransitionTo(ClearingStatus.COMPLETED));
        assertFalse(ClearingStatus.VALIDATION_FAILED.canTransitionTo(ClearingStatus.REVERSED));
    }

    @Test
    @DisplayName("Inward return: direct RECEIVED/VALIDATED → RETURNED transitions")
    void testInwardReturnDirectTransitions() {
        // Per Finacle CLG_RETURN: inward transactions can be returned directly
        // from RECEIVED or VALIDATED without going through VALIDATION_FAILED first
        assertTrue(ClearingStatus.RECEIVED.canTransitionTo(ClearingStatus.RETURNED));
        assertTrue(ClearingStatus.VALIDATED.canTransitionTo(ClearingStatus.RETURNED));
    }

    @Test
    @DisplayName("ClearingTransaction has required fields with new types")
    void testClearingTransactionFields() {
        ClearingTransaction txn = new ClearingTransaction();
        txn.setId(1L);
        txn.setTenantId("DEFAULT");
        txn.setExternalRefNo("CLR202604070001");
        txn.setPaymentRail(PaymentRail.NEFT);
        txn.setDirection(ClearingDirection.OUTWARD);
        txn.setAmount(new BigDecimal("100000.00"));
        txn.setCustomerAccountRef("ACC001");
        txn.setStatus(ClearingStatus.INITIATED);
        txn.setValueDate(LocalDate.of(2026, 4, 7));
        txn.setInitiatedAt(LocalDateTime.now());
        txn.setBranchCode("BR001");
        txn.setCounterpartyIfsc("SBIN0001234");
        txn.setCounterpartyAccount("9876543210");
        txn.setCounterpartyName("Test Beneficiary");

        assertEquals(1L, txn.getId());
        assertEquals("DEFAULT", txn.getTenantId());
        assertEquals("CLR202604070001", txn.getExternalRefNo());
        assertEquals(PaymentRail.NEFT, txn.getPaymentRail());
        assertEquals(ClearingDirection.OUTWARD, txn.getDirection());
        assertEquals(new BigDecimal("100000.00"), txn.getAmount());
        assertEquals("ACC001", txn.getCustomerAccountRef());
        assertEquals(ClearingStatus.INITIATED, txn.getStatus());
        assertEquals("BR001", txn.getBranchCode());
        assertEquals("SBIN0001234", txn.getCounterpartyIfsc());
    }

    @Test
    @DisplayName("ClearingTransaction isTerminal delegates to ClearingStatus")
    void testEntityTerminalHelper() {
        ClearingTransaction txn = new ClearingTransaction();

        txn.setStatus(ClearingStatus.INITIATED);
        assertFalse(txn.isTerminal());

        txn.setStatus(ClearingStatus.COMPLETED);
        assertTrue(txn.isTerminal());

        txn.setStatus(ClearingStatus.REVERSED);
        assertTrue(txn.isTerminal());
    }

    @Test
    @DisplayName("ClearingTransaction isSuspenseActive delegates to ClearingStatus")
    void testEntitySuspenseActiveHelper() {
        ClearingTransaction txn = new ClearingTransaction();

        txn.setStatus(ClearingStatus.SUSPENSE_POSTED);
        assertTrue(txn.isSuspenseActive());

        txn.setStatus(ClearingStatus.COMPLETED);
        assertFalse(txn.isSuspenseActive());
    }

    @Test
    @DisplayName("PaymentRail properties are correct")
    void testPaymentRailProperties() {
        assertTrue(PaymentRail.NEFT.requiresCycleNetting());
        assertFalse(PaymentRail.RTGS.requiresCycleNetting());
        assertFalse(PaymentRail.IMPS.requiresCycleNetting());
        assertFalse(PaymentRail.UPI.requiresCycleNetting());

        assertFalse(PaymentRail.NEFT.isRealTime());
        assertTrue(PaymentRail.RTGS.isRealTime());
        assertTrue(PaymentRail.IMPS.isRealTime());
        assertTrue(PaymentRail.UPI.isRealTime());
    }

    @Test
    @DisplayName("Inward state machine: SUSPENSE_POSTED → CREDITED transition")
    void testInwardCreditedTransition() {
        assertTrue(ClearingStatus.SUSPENSE_POSTED.canTransitionTo(ClearingStatus.CREDITED));
        assertTrue(ClearingStatus.CREDITED.canTransitionTo(ClearingStatus.COMPLETED));
        // CREDITED is NOT terminal
        assertFalse(ClearingStatus.CREDITED.isTerminal());
    }

    @Test
    @DisplayName("Inward state machine: SUSPENSE_POSTED → CREDIT_FAILED transition")
    void testInwardCreditFailedTransition() {
        // ClearingStateManager.markCreditFailed() transitions SUSPENSE_POSTED → CREDIT_FAILED
        assertTrue(ClearingStatus.SUSPENSE_POSTED.canTransitionTo(ClearingStatus.CREDIT_FAILED));
        // CREDIT_FAILED → RETURNED (return to originating bank)
        assertTrue(ClearingStatus.CREDIT_FAILED.canTransitionTo(ClearingStatus.RETURNED));
        // CREDIT_FAILED is NOT terminal (can still be returned)
        assertFalse(ClearingStatus.CREDIT_FAILED.isTerminal());
    }
}

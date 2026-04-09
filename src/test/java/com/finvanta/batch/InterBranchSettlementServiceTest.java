package com.finvanta.batch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.InterBranchTransaction;
import com.finvanta.repository.InterBranchSettlementRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CBS Test: Inter-Branch Settlement per Finacle IB_SETTLEMENT.
 *
 * Validates:
 * - Date-scoped settlement (only today's PENDING settled)
 * - Incomplete GL detection (missing journals → FAILED)
 * - HO manual settle with mandatory reason/auth
 * - Audit trail for all settlement actions
 * - Entity field validation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterBranchSettlement Service Tests")
class InterBranchSettlementServiceTest {

    @Mock
    private InterBranchSettlementRepository settlementRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private BusinessDateService businessDateService;

    @InjectMocks
    private InterBranchSettlementService settlementService;

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 4, 15);

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant("DEFAULT");
        lenient().when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private InterBranchTransaction createPendingTxn(Long id, boolean hasJournals, LocalDate date) {
        InterBranchTransaction txn = new InterBranchTransaction();
        txn.setId(id);
        txn.setTenantId("DEFAULT");
        txn.setAmount(new BigDecimal("10000.00"));
        txn.setSettlementStatus("PENDING");
        txn.setBusinessDate(date);
        if (hasJournals) {
            txn.setSourceJournalId(100L);
            txn.setTargetJournalId(200L);
        }
        Branch source = new Branch();
        source.setBranchCode("HQ001");
        Branch target = new Branch();
        target.setBranchCode("DEL001");
        txn.setSourceBranch(source);
        txn.setTargetBranch(target);
        return txn;
    }

    // === Entity Tests ===

    @Test
    @DisplayName("InterBranchTransaction has required fields with Branch entities")
    void testInterBranchTransactionFields() {
        Branch sourceBranch = new Branch();
        sourceBranch.setId(1L);
        sourceBranch.setBranchCode("HQ001");

        Branch targetBranch = new Branch();
        targetBranch.setId(2L);
        targetBranch.setBranchCode("DEL001");

        InterBranchTransaction txn = new InterBranchTransaction();
        txn.setId(1L);
        txn.setTenantId("DEFAULT");
        txn.setSourceBranch(sourceBranch);
        txn.setTargetBranch(targetBranch);
        txn.setAmount(new BigDecimal("500000.00"));
        txn.setSettlementStatus("PENDING");
        txn.setBusinessDate(LocalDate.of(2026, 4, 7));

        assertEquals("HQ001", txn.getSourceBranch().getBranchCode());
        assertEquals("DEL001", txn.getTargetBranch().getBranchCode());
        assertEquals(new BigDecimal("500000.00"), txn.getAmount());
        assertEquals("PENDING", txn.getSettlementStatus());
    }

    // === Settlement Tests ===

    @Test
    @DisplayName("settleInterBranch with no pending transactions returns early")
    void settleInterBranch_noPending_returnsEarly() {
        when(settlementRepository.findAndLockPendingByDate(anyString(), eq(BUSINESS_DATE)))
                .thenReturn(Collections.emptyList());
        when(settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(anyString(), eq("PENDING")))
                .thenReturn(Collections.emptyList());

        settlementService.settleInterBranch(BUSINESS_DATE);

        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("settleInterBranch marks complete GL transactions as SETTLED")
    void settleInterBranch_completeGL_settled() {
        InterBranchTransaction txn = createPendingTxn(1L, true, BUSINESS_DATE);
        when(settlementRepository.findAndLockPendingByDate(anyString(), eq(BUSINESS_DATE)))
                .thenReturn(List.of(txn));
        when(settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(anyString(), eq("PENDING")))
                .thenReturn(List.of(txn));

        settlementService.settleInterBranch(BUSINESS_DATE);

        assertEquals("SETTLED", txn.getSettlementStatus());
        assertNotNull(txn.getSettlementBatchRef());
        assertTrue(txn.getSettlementBatchRef().startsWith("IB-SETTLEMENT-"));
        verify(settlementRepository).save(txn);
    }

    @Test
    @DisplayName("settleInterBranch marks incomplete GL transactions as FAILED")
    void settleInterBranch_incompleteGL_failed() {
        InterBranchTransaction txn = createPendingTxn(1L, false, BUSINESS_DATE);
        when(settlementRepository.findAndLockPendingByDate(anyString(), eq(BUSINESS_DATE)))
                .thenReturn(List.of(txn));
        when(settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(anyString(), eq("PENDING")))
                .thenReturn(List.of(txn));

        settlementService.settleInterBranch(BUSINESS_DATE);

        assertEquals("FAILED", txn.getSettlementStatus());
        assertTrue(txn.getFailureReason().contains("Incomplete GL posting"));
    }

    // === HO Manual Settle Tests ===

    @Test
    @DisplayName("manualSettleStalePending settles only prior-date pending with HO auth")
    void manualSettleStalePending_settlesOnlyPriorDate() {
        InterBranchTransaction staleTxn = createPendingTxn(1L, true, BUSINESS_DATE.minusDays(1));
        InterBranchTransaction todayTxn = createPendingTxn(2L, true, BUSINESS_DATE);
        when(settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(anyString(), eq("PENDING")))
                .thenReturn(List.of(staleTxn, todayTxn));

        int[] result = settlementService.manualSettleStalePending("Prior EOD failed", "HO-AUTH-001");

        // Only the stale prior-date txn should be settled; today's txn should be untouched
        assertEquals(1, result[0]);
        assertEquals(0, result[1]);
        assertEquals("SETTLED", staleTxn.getSettlementStatus());
        assertTrue(staleTxn.getSettlementBatchRef().contains("HO-MANUAL"));
        assertEquals("PENDING", todayTxn.getSettlementStatus()); // Today's txn left for normal EOD
        verify(auditService).logEvent(eq("InterBranchTransaction"), isNull(),
                eq("HO_MANUAL_SETTLEMENT"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("manualSettleStalePending throws when reason is missing")
    void manualSettleStalePending_noReason_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService.manualSettleStalePending("", "HO-AUTH-001"));
        assertEquals("REASON_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("manualSettleStalePending throws when HO auth ref is missing")
    void manualSettleStalePending_noAuth_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService.manualSettleStalePending("Some reason", ""));
        assertEquals("HO_AUTH_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("manualSettleStalePending with no pending returns zero counts")
    void manualSettleStalePending_noPending_returnsZero() {
        when(settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(anyString(), eq("PENDING")))
                .thenReturn(Collections.emptyList());

        int[] result = settlementService.manualSettleStalePending("Reason", "HO-AUTH-001");

        assertEquals(0, result[0]);
        assertEquals(0, result[1]);
    }

    @Test
    @DisplayName("countStalePending counts only prior-date transactions, excludes today's")
    void countStalePending_countsOnlyPriorDate() {
        InterBranchTransaction staleTxn1 = createPendingTxn(1L, true, BUSINESS_DATE.minusDays(1));
        InterBranchTransaction staleTxn2 = createPendingTxn(2L, true, BUSINESS_DATE.minusDays(2));
        InterBranchTransaction todayTxn = createPendingTxn(3L, true, BUSINESS_DATE);
        when(settlementRepository.findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(anyString(), eq("PENDING")))
                .thenReturn(List.of(staleTxn1, staleTxn2, todayTxn));

        long count = settlementService.countStalePending();

        // Only the 2 prior-date transactions should be counted; today's is not stale
        assertEquals(2, count);
    }
}

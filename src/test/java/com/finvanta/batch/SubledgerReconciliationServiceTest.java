package com.finvanta.batch;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.LoanAccountRepository;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SubledgerReconciliationService.
 * Validates subledger-to-GL reconciliation logic for loan and CASA modules.
 */
@ExtendWith(MockitoExtension.class)
class SubledgerReconciliationServiceTest {

    @Mock
    private LoanAccountRepository loanAccountRepository;

    @Mock
    private DepositAccountRepository depositAccountRepository;

    @Mock
    private GLMasterRepository glMasterRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private SubledgerReconciliationService service;

    @Test
    void testReconcileBalanced() {
        // Setup: subledger totals match GL balances
        when(loanAccountRepository.calculateTotalOutstandingPrincipal(anyString()))
                .thenReturn(new BigDecimal("1000000.00"));

        GLMaster loanGL = new GLMaster();
        loanGL.setDebitBalance(new BigDecimal("1000000.00"));
        loanGL.setCreditBalance(BigDecimal.ZERO);
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), eq("1001")))
                .thenReturn(Optional.of(loanGL));

        // CASA: no accounts = zero balance
        when(depositAccountRepository.findAllNonClosedAccounts(anyString())).thenReturn(Collections.emptyList());

        GLMaster sbGL = new GLMaster();
        sbGL.setDebitBalance(BigDecimal.ZERO);
        sbGL.setCreditBalance(BigDecimal.ZERO);
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), eq("2010")))
                .thenReturn(Optional.of(sbGL));

        GLMaster caGL = new GLMaster();
        caGL.setDebitBalance(BigDecimal.ZERO);
        caGL.setCreditBalance(BigDecimal.ZERO);
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), eq("2020")))
                .thenReturn(Optional.of(caGL));

        var result = service.reconcile();

        assertTrue(result.isBalanced());
        assertEquals(0, result.discrepancyCount());
    }

    @Test
    void testReconcileLoanMismatch() {
        // Setup: loan subledger = 1M but GL = 900K (100K discrepancy)
        when(loanAccountRepository.calculateTotalOutstandingPrincipal(anyString()))
                .thenReturn(new BigDecimal("1000000.00"));

        GLMaster loanGL = new GLMaster();
        loanGL.setDebitBalance(new BigDecimal("900000.00"));
        loanGL.setCreditBalance(BigDecimal.ZERO);
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), eq("1001")))
                .thenReturn(Optional.of(loanGL));

        when(depositAccountRepository.findAllNonClosedAccounts(anyString())).thenReturn(Collections.emptyList());

        GLMaster sbGL = new GLMaster();
        sbGL.setDebitBalance(BigDecimal.ZERO);
        sbGL.setCreditBalance(BigDecimal.ZERO);
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), eq("2010")))
                .thenReturn(Optional.of(sbGL));

        GLMaster caGL = new GLMaster();
        caGL.setDebitBalance(BigDecimal.ZERO);
        caGL.setCreditBalance(BigDecimal.ZERO);
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), eq("2020")))
                .thenReturn(Optional.of(caGL));

        var result = service.reconcile();

        assertFalse(result.isBalanced());
        assertEquals(1, result.discrepancyCount());
        assertEquals("LOAN_PRINCIPAL", result.discrepancies().get(0).checkCode());
        assertEquals(new BigDecimal("100000.00"), result.discrepancies().get(0).variance());
    }

    @Test
    void testReconcileGlNotFound() {
        // Setup: GL code doesn't exist — should default to zero
        when(loanAccountRepository.calculateTotalOutstandingPrincipal(anyString()))
                .thenReturn(BigDecimal.ZERO);
        when(depositAccountRepository.findAllNonClosedAccounts(anyString())).thenReturn(Collections.emptyList());
        when(glMasterRepository.findByTenantIdAndGlCode(anyString(), anyString()))
                .thenReturn(Optional.empty());

        var result = service.reconcile();

        assertTrue(result.isBalanced());
    }
}

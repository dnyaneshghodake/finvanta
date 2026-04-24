package com.finvanta.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.RecurringDeposit;
import com.finvanta.domain.enums.RdStatus;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.RecurringDepositRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.SequenceGeneratorService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CBS Tier-1 unit tests for {@link RecurringDepositServiceImpl#prematureClose}.
 * Covers the GL-safety invariants introduced in this PR: cap of adjustedInterest
 * at accruedInterest, omission of zero-amount interest line, and reversal of
 * excess accrual so GL 2041 (RD_INTEREST_PAYABLE) stays in parity with subledger.
 */
@ExtendWith(MockitoExtension.class)
class RecurringDepositServiceImplPrematureCloseTest {

    private static final String TENANT = "T1";
    private static final String RD_NO = "RD/BR01/000001";
    private static final LocalDate BD = LocalDate.of(2026, 6, 1);

    @Mock private RecurringDepositRepository rdRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private TransactionEngine transactionEngine;
    @Mock private BusinessDateService businessDateService;
    @Mock private SequenceGeneratorService sequenceGenerator;
    @Mock private AuditService auditService;

    private RecurringDepositServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecurringDepositServiceImpl(
                rdRepository, customerRepository, branchRepository,
                transactionEngine, businessDateService,
                sequenceGenerator, auditService);
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RecurringDeposit activeRd(
            BigDecimal cumDep, BigDecimal rate, BigDecimal prematurePenaltyRate,
            BigDecimal accrued, LocalDate booking) {
        RecurringDeposit rd = new RecurringDeposit();
        rd.setRdAccountNumber(RD_NO);
        rd.setLinkedAccountNumber("SB/BR01/0001");
        rd.setBranchCode("BR01");
        rd.setStatus(RdStatus.ACTIVE);
        rd.setCumulativeDeposit(cumDep);
        rd.setInterestRate(rate);
        rd.setPrematurePenaltyRate(prematurePenaltyRate);
        rd.setAccruedInterest(accrued);
        rd.setBookingDate(booking);
        return rd;
    }

    @Test
    void prematureClose_penaltyGeRate_reversesAllAccruedAndOmitsInterestLine() {
        RecurringDeposit rd = activeRd(
                new BigDecimal("12000.00"), new BigDecimal("6.0000"),
                new BigDecimal("6.0000"), new BigDecimal("150.00"),
                BD.minusMonths(6));
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BD);
        when(rdRepository.findByTenantIdAndRdAccountNumber(TENANT, RD_NO))
                .thenReturn(Optional.of(rd));

        service.prematureClose(RD_NO, "customer request");

        ArgumentCaptor<TransactionRequest> cap = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionEngine, times(2)).execute(cap.capture());
        List<TransactionRequest> calls = cap.getAllValues();
        assertEquals("RD_INTEREST_REVERSAL", calls.get(0).getTransactionType());
        assertEquals(0, new BigDecimal("150.00").compareTo(calls.get(0).getAmount()));
        TransactionRequest closure = calls.get(1);
        assertEquals("RD_PREMATURE_CLOSE", closure.getTransactionType());
        assertEquals(0, new BigDecimal("12000.00").compareTo(closure.getAmount()));
        assertEquals(2, closure.getJournalLines().size(),
                "interest DR line must be omitted when adjustedInterest == 0");
        assertEquals(RdStatus.PREMATURE_CLOSED, rd.getStatus());
        assertEquals(BD, rd.getClosureDate());
        assertEquals(0, BigDecimal.ZERO.compareTo(rd.getAccruedInterest()));
        verify(rdRepository).save(rd);
    }

    @Test
    void prematureClose_formulaExceedsAccrued_capsAdjustedAtAccrued() {
        RecurringDeposit rd = activeRd(
                new BigDecimal("12000.00"), new BigDecimal("7.0000"),
                new BigDecimal("1.0000"), new BigDecimal("10.00"),
                BD.minusDays(365));
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BD);
        when(rdRepository.findByTenantIdAndRdAccountNumber(TENANT, RD_NO))
                .thenReturn(Optional.of(rd));

        service.prematureClose(RD_NO, "reason");

        ArgumentCaptor<TransactionRequest> cap = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionEngine, times(1)).execute(cap.capture());
        TransactionRequest closure = cap.getValue();
        assertEquals("RD_PREMATURE_CLOSE", closure.getTransactionType());
        assertEquals(0, new BigDecimal("12010.00").compareTo(closure.getAmount()));
        assertEquals(3, closure.getJournalLines().size());
        assertEquals(0, new BigDecimal("10.00").compareTo(rd.getAccruedInterest()));
    }

    @Test
    void prematureClose_excessAccrued_reversesDeltaThenPostsClosureWith3Lines() {
        RecurringDeposit rd = activeRd(
                new BigDecimal("10000.00"), new BigDecimal("7.0000"),
                new BigDecimal("2.0000"), new BigDecimal("500.00"),
                BD.minusDays(100));
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BD);
        when(rdRepository.findByTenantIdAndRdAccountNumber(TENANT, RD_NO))
                .thenReturn(Optional.of(rd));

        service.prematureClose(RD_NO, "reason");

        ArgumentCaptor<TransactionRequest> cap = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionEngine, times(2)).execute(cap.capture());
        assertEquals("RD_INTEREST_REVERSAL", cap.getAllValues().get(0).getTransactionType());
        TransactionRequest closure = cap.getAllValues().get(1);
        assertEquals("RD_PREMATURE_CLOSE", closure.getTransactionType());
        assertEquals(3, closure.getJournalLines().size());
    }

    @Test
    void prematureClose_throwsRdNotActive_whenStatusNotActive() {
        RecurringDeposit rd = activeRd(
                new BigDecimal("1000.00"), new BigDecimal("6.0000"),
                new BigDecimal("1.0000"), BigDecimal.ZERO, BD.minusMonths(1));
        rd.setStatus(RdStatus.MATURED);
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BD);
        when(rdRepository.findByTenantIdAndRdAccountNumber(TENANT, RD_NO))
                .thenReturn(Optional.of(rd));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prematureClose(RD_NO, "r"));
        assertEquals("RD_NOT_ACTIVE", ex.getErrorCode());
        verifyNoInteractions(transactionEngine);
    }

    @Test
    void prematureClose_throwsRdNotFound_whenRepoReturnsEmpty() {
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BD);
        when(rdRepository.findByTenantIdAndRdAccountNumber(TENANT, RD_NO))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.prematureClose(RD_NO, "r"));
        assertEquals("RD_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(transactionEngine);
    }
}

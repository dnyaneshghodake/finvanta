package com.finvanta.cbs.modules.teller.service;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.cbs.modules.teller.domain.CashDenomination;
import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;
import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.domain.TellerTillStatus;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.DenominationEntry;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.repository.CashDenominationRepository;
import com.finvanta.cbs.modules.teller.repository.TellerTillRepository;
import com.finvanta.cbs.modules.teller.validator.DenominationValidator;
import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.DepositAccountType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.CbsSecurityContext;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tier-1 orchestration tests for {@link TellerServiceImpl}.
 *
 * <p>Each test asserts a single Tier-1 invariant from
 * {@code TellerServiceImpl}'s class Javadoc. Mocks the collaborators (engine,
 * repos, audit) so the test focuses on the service's locking + maker-checker
 * + FICN routing logic, not on engine internals.
 *
 * <p>Test username is {@code teller1} (ROLE_TELLER, branchId=1L, branchCode=HQ001).
 * Customer account fixture is at the same branch so {@code BranchAccessValidator}
 * passes without ADMIN override.
 */
@ExtendWith(MockitoExtension.class)
class TellerServiceImplTest {

    private static final String TENANT = "DEFAULT";
    private static final String TELLER_USER = "teller1";
    private static final String BRANCH_CODE = "HQ001";
    private static final Long BRANCH_ID = 1L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 4, 1);

    @Mock private TellerTillRepository tillRepository;
    @Mock private CashDenominationRepository denominationRepository;
    @Mock private DepositAccountRepository accountRepository;
    @Mock private DepositTransactionRepository transactionRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private ProductGLResolver glResolver;
    @Mock private TransactionEngine transactionEngine;
    @Mock private BusinessDateService businessDateService;
    @Mock private AuditService auditService;

    private DenominationValidator denominationValidator;
    private BranchAccessValidator branchAccessValidator;
    private TellerServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        // Real validator (no collaborators) so we exercise the full sum-equality
        // path and counterfeit detection rather than mocking it away.
        denominationValidator = new DenominationValidator();
        // Real BranchAccessValidator wired to a live SecurityContext per the
        // pattern in DepositAccountServiceTest. Mocking would let us bypass
        // branch isolation, which we want to test.
        branchAccessValidator = new BranchAccessValidator(new CbsSecurityContext());

        service = new TellerServiceImpl(
                tillRepository,
                denominationRepository,
                accountRepository,
                transactionRepository,
                branchRepository,
                glResolver,
                transactionEngine,
                businessDateService,
                auditService,
                denominationValidator,
                branchAccessValidator);

        // Auth context: TELLER role + branchId=1L matches buildAccount() branch.
        BranchAwareUserDetails userDetails = new BranchAwareUserDetails(
                TELLER_USER, "pass",
                List.of(new SimpleGrantedAuthority("ROLE_TELLER")),
                BRANCH_ID, BRANCH_CODE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userDetails, "pass", userDetails.getAuthorities()));
    }

    // =====================================================================
    // Fixture builders
    // =====================================================================

    private Branch buildBranch() {
        Branch b = new Branch();
        b.setId(BRANCH_ID);
        b.setBranchCode(BRANCH_CODE);
        b.setBranchName("Headquarters");
        return b;
    }

    private TellerTill buildOpenTill(BigDecimal openingBal, BigDecimal currentBal) {
        TellerTill t = new TellerTill();
        t.setId(100L);
        t.setTenantId(TENANT);
        t.setTellerUserId(TELLER_USER);
        t.setBranch(buildBranch());
        t.setBranchCode(BRANCH_CODE);
        t.setBusinessDate(BUSINESS_DATE);
        t.setStatus(TellerTillStatus.OPEN);
        t.setOpeningBalance(openingBal);
        t.setCurrentBalance(currentBal);
        return t;
    }

    private DepositAccount buildSavingsAccount(String accNo, BigDecimal balance) {
        DepositAccount a = new DepositAccount();
        a.setId(1L);
        a.setTenantId(TENANT);
        a.setAccountNumber(accNo);
        a.setAccountType(DepositAccountType.SAVINGS);
        a.setAccountStatus(DepositAccountStatus.ACTIVE);
        a.setLedgerBalance(balance);
        a.setAvailableBalance(balance);
        a.setHoldAmount(BigDecimal.ZERO);
        a.setUnclearedAmount(BigDecimal.ZERO);
        a.setOdLimit(BigDecimal.ZERO);
        a.setBranch(buildBranch());
        return a;
    }

    private TransactionResult mockPostedResult() {
        return new TransactionResult(
                "TXN-T-001",
                "VCH/HQ001/20260401/000001",
                100L,
                "JRN001",
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                BUSINESS_DATE,
                LocalDateTime.now(),
                "POSTED");
    }

    private TransactionResult mockPendingResult() {
        return new TransactionResult(
                "TXN-T-PEND-001",
                "VCH/HQ001/20260401/000099",
                null, // no journal entry yet
                "JRN-PEND",
                new BigDecimal("60000.00"),
                new BigDecimal("60000.00"),
                BUSINESS_DATE,
                LocalDateTime.now(),
                "PENDING_APPROVAL");
    }

    /**
     * Standard CBS-compliant request: 5 x INR 500 + 3 x INR 100 = INR 2800,
     * no counterfeit, depositor name supplied per PMLA §12. Sub-CTR amount
     * so PAN is not required.
     */
    private CashDepositRequest standardRequest(String accountNumber, String idempotencyKey) {
        return new CashDepositRequest(
                accountNumber,
                new BigDecimal("2800"),
                List.of(
                        new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 0),
                        new DenominationEntry(IndianCurrencyDenomination.NOTE_100, 3, 0)),
                idempotencyKey,
                "Ramesh Kumar",
                null, null, "Salary deposit", null);
    }

    // =====================================================================
    // Scenarios -- batch 1: happy path, validation rejections, pending
    // =====================================================================

    @Test
    @DisplayName("happy path: deposit POSTED -> ledger, available, till incremented; denominations persisted")
    void cashDeposit_happyPath_postsLedgerAndTillAndDenominations() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        TellerTill till = buildOpenTill(new BigDecimal("100000.00"), new BigDecimal("100000.00"));

        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber(TENANT, "DEP001"))
                .thenReturn(Optional.of(acct));
        when(tillRepository.findAndLockByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.of(till));
        when(transactionRepository.findByTenantIdAndIdempotencyKey(eq(TENANT), anyString()))
                .thenReturn(Optional.empty());
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tillRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(denominationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CashDepositResponse r = service.cashDeposit(standardRequest("DEP001", "idem-key-1"));

        assertNotNull(r);
        assertFalse(r.pendingApproval(), "POSTED engine result must not surface as pending");
        // Ledger incremented by 2800
        assertEquals(0, new BigDecimal("52800.00").compareTo(acct.getLedgerBalance()),
                "ledger should be 50000 + 2800");
        // Available recomputed (not incremented) per CBS BAL_DERIVE
        assertEquals(0, new BigDecimal("52800.00").compareTo(acct.getAvailableBalance()));
        // Till incremented by the same amount
        assertEquals(0, new BigDecimal("102800.00").compareTo(till.getCurrentBalance()),
                "till should be 100000 + 2800");
        // Two denomination rows persisted (NOTE_500 + NOTE_100)
        verify(denominationRepository, times(2)).save(any(CashDenomination.class));
        // GL post happened exactly once
        verify(transactionEngine, times(1)).execute(any());
        // Audit event POSTED (logEventInline because locks held)
        verify(auditService, times(1)).logEventInline(
                eq("TellerCashDeposit"), any(), eq("POSTED"),
                isNull(), any(DepositTransaction.class), eq("TELLER"), anyString());
    }

    @Test
    @DisplayName("denomination mismatch: rejected before any DB lock or GL post")
    void cashDeposit_denomMismatch_rejectedPreLock() {
        // 5 x 500 = 2500, but operator typed amount = 2800 (mismatch INR 300)
        CashDepositRequest req = new CashDepositRequest(
                "DEP001",
                new BigDecimal("2800"),
                List.of(new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 0)),
                "idem-key-2", "Ramesh Kumar", null, null, "test", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cashDeposit(req));
        assertEquals("CBS-TELLER-004", ex.getErrorCode());

        // CRITICAL: no lock acquired, no engine call, no audit
        verify(accountRepository, never()).findAndLockByTenantIdAndAccountNumber(any(), any());
        verify(tillRepository, never()).findAndLockByTellerAndDate(any(), any(), any());
        verify(transactionEngine, never()).execute(any());
    }

    @Test
    @DisplayName("FICN: counterfeit notes reject the deposit before any mutation")
    void cashDeposit_ficnDetected_rejectsBeforeLock() {
        // 5 x 500 (genuine) + 1 x 500 (counterfeit) = 3000 physical, sum matches.
        // Per RBI FICN: deposit rejected, customer NOT credited.
        CashDepositRequest req = new CashDepositRequest(
                "DEP001",
                new BigDecimal("3000"),
                List.of(new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 1)),
                "idem-key-3", "Ramesh Kumar", null, null, "test", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cashDeposit(req));
        assertEquals("CBS-TELLER-008", ex.getErrorCode());

        verify(accountRepository, never()).findAndLockByTenantIdAndAccountNumber(any(), any());
        verify(tillRepository, never()).findAndLockByTellerAndDate(any(), any(), any());
        verify(transactionEngine, never()).execute(any());
        verify(denominationRepository, never()).save(any());
    }

    @Test
    @DisplayName("maker-checker pending: balances UNCHANGED, till NOT mutated, no denom rows")
    void cashDeposit_pendingApproval_doesNotMutateState() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        TellerTill till = buildOpenTill(new BigDecimal("100000.00"), new BigDecimal("100000.00"));
        BigDecimal ledgerBefore = acct.getLedgerBalance();
        BigDecimal tillBefore = till.getCurrentBalance();

        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber(TENANT, "DEP001"))
                .thenReturn(Optional.of(acct));
        when(tillRepository.findAndLockByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.of(till));
        when(transactionRepository.findByTenantIdAndIdempotencyKey(eq(TENANT), anyString()))
                .thenReturn(Optional.empty());
        when(transactionEngine.execute(any())).thenReturn(mockPendingResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CashDepositResponse r = service.cashDeposit(standardRequest("DEP001", "idem-key-pending"));

        assertTrue(r.pendingApproval(),
                "engine returned PENDING_APPROVAL must surface to caller");
        // Customer ledger MUST NOT have moved
        assertEquals(0, ledgerBefore.compareTo(acct.getLedgerBalance()),
                "PENDING deposit must not mutate customer ledger");
        // Till MUST NOT have moved
        assertEquals(0, tillBefore.compareTo(till.getCurrentBalance()),
                "PENDING deposit must not mutate till balance");
        // NO denomination rows persisted on pending path
        verify(denominationRepository, never()).save(any());
        // No till save on pending path
        verify(tillRepository, never()).save(any());
        // Pending audit event is emitted
        verify(auditService, times(1)).logEventInline(
                eq("TellerCashDeposit"), any(), eq("PENDING_APPROVAL"),
                isNull(), any(DepositTransaction.class), eq("TELLER"), anyString());
    }
}

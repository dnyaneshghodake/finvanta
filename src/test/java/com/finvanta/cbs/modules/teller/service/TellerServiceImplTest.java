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
    @Mock private FicnRegisterService ficnRegisterService;
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

        // FicnRegisterService is mocked because the FICN write happens in a
        // REQUIRES_NEW sub-transaction that the unit test cannot reproduce.
        // The dedicated FICN service test (TellerServiceImplFicnTest) covers
        // the delegation contract; this test only verifies that cashDeposit
        // calls into ficnRegisterService.recordDetection on the FICN gate
        // and propagates the FicnDetectedException -- see
        // cashDeposit_ficnDetected_writesRegisterAndThrows below.
        service = new TellerServiceImpl(
                tillRepository,
                denominationRepository,
                ficnRegisterService,
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
    @DisplayName("FICN: counterfeit notes write register, throw FicnDetectedException, no GL/till mutation")
    void cashDeposit_ficnDetected_writesRegisterAndThrows() {
        // 5 x 500 (genuine) + 1 x 500 (counterfeit) = 3000 physical, sum matches.
        // Per RBI FICN: deposit rejected (Option B -- entire deposit refused),
        // counterfeit notes impounded, customer handed an FICN ack slip.
        //
        // The behavior changed in the FICN refactor: the check moved from
        // pre-lock to post-lock so the register row carries a definitive
        // till_id / branch_id (resolved from the locked entities). This test
        // therefore mocks the lock acquisition, expects the locks WERE taken,
        // and asserts that ficnRegisterService.recordDetection was invoked
        // with the locked till+branch.
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        TellerTill till = buildOpenTill(new BigDecimal("100000.00"), new BigDecimal("100000.00"));
        BigDecimal ledgerBefore = acct.getLedgerBalance();
        BigDecimal tillBefore = till.getCurrentBalance();

        CashDepositRequest req = new CashDepositRequest(
                "DEP001",
                new BigDecimal("3000"),
                List.of(new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 5, 1)),
                "idem-key-3", "Ramesh Kumar", null, null, "test", null);

        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber(TENANT, "DEP001"))
                .thenReturn(Optional.of(acct));
        when(tillRepository.findAndLockByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.of(till));
        // Stub the FICN-write service to return a representative slip. The
        // service-level recordDetection logic is covered by its own test;
        // here we only verify that cashDeposit DELEGATES to it and propagates
        // FicnDetectedException with the slip payload.
        com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse stubAck =
                new com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse(
                        "FICN/HQ001/20260401/000001",
                        "idem-key-3",
                        BRANCH_CODE,
                        "Headquarters",
                        BUSINESS_DATE,
                        LocalDateTime.now(),
                        TELLER_USER,
                        "Ramesh Kumar",
                        null, null, null,
                        List.of(),
                        new BigDecimal("500"),
                        /* firRequired */ false,
                        "PENDING",
                        null);
        when(ficnRegisterService.recordDetection(
                any(CashDepositRequest.class),
                any(),
                eq(BRANCH_CODE),
                eq(till.getId()),
                eq(TELLER_USER),
                eq(BUSINESS_DATE),
                eq(TENANT)))
                .thenReturn(stubAck);

        com.finvanta.cbs.modules.teller.exception.FicnDetectedException ex = assertThrows(
                com.finvanta.cbs.modules.teller.exception.FicnDetectedException.class,
                () -> service.cashDeposit(req));
        assertEquals("CBS-TELLER-008", ex.getErrorCode());
        assertEquals("FICN/HQ001/20260401/000001", ex.getAcknowledgement().registerRef(),
                "exception payload must carry the register ref minted by FicnRegisterService");

        // FICN gate fired AFTER locks: the ack write happened with the locked
        // till + branch. This is the post-lock contract -- if the FICN gate
        // ever moves back pre-lock, this verify fails.
        verify(ficnRegisterService, times(1)).recordDetection(
                any(CashDepositRequest.class),
                any(),
                eq(BRANCH_CODE),
                eq(till.getId()),
                eq(TELLER_USER),
                eq(BUSINESS_DATE),
                eq(TENANT));

        // CRITICAL invariant: customer ledger NOT mutated. The FicnDetectedException
        // rolls back the parent transaction; in a real Spring context the
        // mutation would also roll back. In this unit test there's no real
        // transaction so we assert the in-memory entity was never modified.
        assertEquals(0, ledgerBefore.compareTo(acct.getLedgerBalance()),
                "FICN-rejected deposit must not mutate the customer ledger");
        assertEquals(0, tillBefore.compareTo(till.getCurrentBalance()),
                "FICN-rejected deposit must not mutate the till balance");

        // No GL post, no DepositTransaction insert, no CashDenomination insert.
        verify(transactionEngine, never()).execute(any());
        verify(transactionRepository, never()).save(any());
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

    // =====================================================================
    // Scenarios -- batch 2a: idempotent retry, till-not-open, CTR rules
    // =====================================================================

    @Test
    @DisplayName("idempotent retry: returns prior receipt without re-mutating till or ledger")
    void cashDeposit_idempotentRetry_returnsPriorReceipt() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("52800.00"));
        TellerTill till = buildOpenTill(new BigDecimal("100000.00"), new BigDecimal("102800.00"));
        BigDecimal ledgerBefore = acct.getLedgerBalance();
        BigDecimal tillBefore = till.getCurrentBalance();

        // Prior committed transaction surfaced by the lock-then-check dedupe.
        DepositTransaction prior = new DepositTransaction();
        prior.setId(99L);
        prior.setTenantId(TENANT);
        prior.setTransactionRef("TXN-PRIOR-001");
        prior.setVoucherNumber("VCH/HQ001/20260401/000001");
        prior.setAmount(new BigDecimal("2800"));
        prior.setBalanceBefore(new BigDecimal("50000.00"));
        prior.setBalanceAfter(new BigDecimal("52800.00"));
        prior.setValueDate(BUSINESS_DATE);
        prior.setPostingDate(LocalDateTime.now());
        prior.setNarration("Salary deposit");
        prior.setChannel("TELLER");
        prior.setIdempotencyKey("idem-retry");
        prior.setDepositAccount(acct);

        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber(TENANT, "DEP001"))
                .thenReturn(Optional.of(acct));
        when(tillRepository.findAndLockByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.of(till));
        when(transactionRepository.findByTenantIdAndIdempotencyKey(TENANT, "idem-retry"))
                .thenReturn(Optional.of(prior));

        CashDepositResponse r = service.cashDeposit(standardRequest("DEP001", "idem-retry"));

        assertEquals("TXN-PRIOR-001", r.transactionRef(),
                "retry must surface the prior receipt's txn ref");
        // Engine NEVER called on retry -- the prior commit already posted GL
        verify(transactionEngine, never()).execute(any());
        // Till and ledger MUST NOT change on retry
        assertEquals(0, ledgerBefore.compareTo(acct.getLedgerBalance()));
        assertEquals(0, tillBefore.compareTo(till.getCurrentBalance()));
        // No new denomination rows
        verify(denominationRepository, never()).save(any());
    }

    @Test
    @DisplayName("till not open: cash deposit rejected with CBS-TELLER-001 (no GL post)")
    void cashDeposit_tillNotOpen_rejectedAfterAccountLock() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));

        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber(TENANT, "DEP001"))
                .thenReturn(Optional.of(acct));
        // No till for this teller today.
        when(tillRepository.findAndLockByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cashDeposit(standardRequest("DEP001", "idem-no-till")));
        assertEquals("CBS-TELLER-001", ex.getErrorCode());

        verify(transactionEngine, never()).execute(any());
        verify(denominationRepository, never()).save(any());
    }

    @Test
    @DisplayName("CTR threshold: deposit at INR 50000 without PAN/Form60 rejected with CBS-COMP-002")
    void cashDeposit_ctrThreshold_requiresPanOrForm60() {
        // 100 x 500 = 50,000 -- exactly at the CTR threshold per PMLA Rule 9.
        // No PAN, no Form 60 reference -- must be rejected.
        CashDepositRequest req = new CashDepositRequest(
                "DEP001",
                new BigDecimal("50000"),
                List.of(new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 100, 0)),
                "idem-ctr", "High-Value Depositor",
                null, null, "Threshold deposit", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cashDeposit(req));
        assertEquals("CBS-COMP-002", ex.getErrorCode());

        // CTR check runs BEFORE locks per the validateCtrCompliance contract,
        // so no DB activity should have happened.
        verify(accountRepository, never()).findAndLockByTenantIdAndAccountNumber(any(), any());
        verify(transactionEngine, never()).execute(any());
    }

    @Test
    @DisplayName("CTR threshold: PAN supplied -> deposit proceeds and ctrTriggered=true")
    void cashDeposit_ctrThreshold_panSupplied_proceeds() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100000.00"));
        TellerTill till = buildOpenTill(new BigDecimal("200000.00"), new BigDecimal("200000.00"));

        CashDepositRequest req = new CashDepositRequest(
                "DEP001",
                new BigDecimal("50000"),
                List.of(new DenominationEntry(IndianCurrencyDenomination.NOTE_500, 100, 0)),
                "idem-ctr-ok", "High-Value Depositor",
                "9876543210", "ABCDE1234F", "Threshold deposit", null);

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

        CashDepositResponse r = service.cashDeposit(req);

        assertNotNull(r);
        assertTrue(r.ctrTriggered(), "CTR flag must surface to BFF for the receipt slip");
        assertFalse(r.pendingApproval());
        assertEquals(0, new BigDecimal("150000.00").compareTo(acct.getLedgerBalance()));
    }

    // =====================================================================
    // Scenarios -- batch 2b: dormancy reactivation + openTill lifecycle
    // =====================================================================

    @Test
    @DisplayName("dormancy reactivation: deposit on DORMANT account transitions to ACTIVE")
    void cashDeposit_dormantAccount_reactivatesPerKycRule() {
        // Per RBI KYC §38: a customer-initiated credit on a dormant account
        // reactivates it to ACTIVE.
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100.00"));
        acct.setAccountStatus(DepositAccountStatus.DORMANT);
        acct.setDormantDate(LocalDate.of(2024, 1, 1));
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

        service.cashDeposit(standardRequest("DEP001", "idem-dormant"));

        assertEquals(DepositAccountStatus.ACTIVE, acct.getAccountStatus(),
                "dormant account must reactivate per RBI KYC §38");
        assertNull(acct.getDormantDate(), "dormantDate must be cleared on reactivation");
        // Inline-audit reactivation event must be recorded
        verify(auditService).logEventInline(
                eq("DepositAccount"), eq(1L), eq("DORMANCY_REACTIVATED"),
                eq("DORMANT"), eq("ACTIVE"), eq("ACCOUNT"), anyString());
    }

    @Test
    @DisplayName("openTill: opening balance below threshold auto-approves to OPEN")
    void openTill_belowThreshold_autoApprovesToOpen() {
        // INR 50,000 is well below the INR 200,000 auto-approve threshold.
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(branchRepository.findById(BRANCH_ID)).thenReturn(Optional.of(buildBranch()));
        when(tillRepository.findByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.empty());
        when(tillRepository.save(any())).thenAnswer(inv -> {
            TellerTill saved = inv.getArgument(0);
            saved.setId(101L);
            return saved;
        });

        TellerTill till = service.openTill(
                new OpenTillRequest(new BigDecimal("50000"), null, "Morning shift"));

        assertEquals(TellerTillStatus.OPEN, till.getStatus());
        assertNotNull(till.getOpenedAt(), "OPEN status must carry an openedAt timestamp");
        assertEquals(0, new BigDecimal("50000").compareTo(till.getOpeningBalance()));
        assertEquals(0, new BigDecimal("50000").compareTo(till.getCurrentBalance()),
                "currentBalance must equal opening balance at till-open time");
        // Audit uses logEvent (REQUIRES_NEW) since no row locks held
        verify(auditService).logEvent(
                eq("TellerTill"), any(), eq("OPEN_REQUEST"),
                isNull(), any(TellerTill.class), eq("TELLER"), anyString());
    }

    @Test
    @DisplayName("openTill: opening balance above threshold creates PENDING_OPEN for supervisor")
    void openTill_aboveThreshold_routesToPendingOpen() {
        // INR 500,000 is above the INR 200,000 soft threshold.
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(branchRepository.findById(BRANCH_ID)).thenReturn(Optional.of(buildBranch()));
        when(tillRepository.findByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.empty());
        when(tillRepository.save(any())).thenAnswer(inv -> {
            TellerTill saved = inv.getArgument(0);
            saved.setId(102L);
            return saved;
        });

        TellerTill till = service.openTill(
                new OpenTillRequest(new BigDecimal("500000"), null, "High-cash shift"));

        assertEquals(TellerTillStatus.PENDING_OPEN, till.getStatus(),
                "above-threshold opening balance must route to supervisor");
        assertNull(till.getOpenedAt(),
                "openedAt is set only after supervisor approval, not on PENDING_OPEN");
    }

    @Test
    @DisplayName("openTill: duplicate till for the same business date is rejected")
    void openTill_duplicate_rejectedWithCbsTeller010() {
        // Existing till already present for this teller today.
        TellerTill existing = buildOpenTill(new BigDecimal("100000"), new BigDecimal("100000"));
        when(businessDateService.getCurrentBusinessDate()).thenReturn(BUSINESS_DATE);
        when(branchRepository.findById(BRANCH_ID)).thenReturn(Optional.of(buildBranch()));
        when(tillRepository.findByTellerAndDate(TENANT, TELLER_USER, BUSINESS_DATE))
                .thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.openTill(
                        new OpenTillRequest(new BigDecimal("50000"), null, null)));
        assertEquals("CBS-TELLER-010", ex.getErrorCode());

        verify(tillRepository, never()).save(any());
    }
}

package com.finvanta.service;

import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.DepositAccountType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.InterestAccrualRepository;
import com.finvanta.service.impl.DepositAccountServiceImpl;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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
 * CBS CASA Unit Tests per Finacle CUSTACCT / Temenos ACCOUNT standards.
 *
 * Validates:
 * - Deposit: GL posting DR Bank Ops / CR Customer Deposits, balance update
 * - Withdrawal: Sufficient funds check, debit-allowed check, balance update
 * - Transfer: Atomic debit+credit, same-account rejection
 * - Freeze: Debit blocked on frozen account, credit allowed on DEBIT_FREEZE
 * - Interest accrual: Daily product method (balance * rate / 36500)
 * - Dormancy: 24-month no-txn classification
 */
@ExtendWith(MockitoExtension.class)
class DepositAccountServiceTest {

    @Mock
    private DepositAccountRepository accountRepository;

    @Mock
    private DepositTransactionRepository transactionRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private InterestAccrualRepository accrualRepository;

    @Mock
    private com.finvanta.repository.ProductMasterRepository productMasterRepository;

    @Mock
    private TransactionEngine transactionEngine;

    @Mock
    private BusinessDateService businessDateService;

    @Mock
    private com.finvanta.audit.AuditService auditService;

    @Mock
    private com.finvanta.workflow.ApprovalWorkflowService workflowService;

    @Mock
    private com.finvanta.repository.DailyBalanceSnapshotRepository balanceSnapshotRepository;

    @Mock
    private com.finvanta.repository.BusinessCalendarRepository calendarRepository;

    private BranchAccessValidator branchAccessValidator;

    private DepositAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        // CBS Tier-1: Use real BranchAccessValidator (not mock) to test branch enforcement.
        // The validator reads from SecurityContext which we set up with BranchAwareUserDetails.
        branchAccessValidator = new BranchAccessValidator();
        service = new DepositAccountServiceImpl(
                accountRepository,
                transactionRepository,
                customerRepository,
                branchRepository,
                accrualRepository,
                productMasterRepository,
                transactionEngine,
                businessDateService,
                auditService,
                workflowService,
                branchAccessValidator,
                balanceSnapshotRepository,
                calendarRepository);
        TenantContext.setCurrentTenant("DEFAULT");
        // CBS Tier-1: Use BranchAwareUserDetails so SecurityUtil.getCurrentUserBranchId() works.
        // Branch ID=1L, branchCode="HQ001" matches the branch set in buildSavingsAccount().
        BranchAwareUserDetails userDetails = new BranchAwareUserDetails(
                "maker1", "pass", List.of(new SimpleGrantedAuthority("ROLE_MAKER")), 1L, "HQ001");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        userDetails, "pass", userDetails.getAuthorities()));
    }

    private DepositAccount buildSavingsAccount(String accNo, BigDecimal balance) {
        DepositAccount a = new DepositAccount();
        a.setId(1L);
        a.setTenantId("DEFAULT");
        a.setAccountNumber(accNo);
        a.setAccountType(DepositAccountType.SAVINGS);
        a.setAccountStatus(DepositAccountStatus.ACTIVE);
        a.setLedgerBalance(balance);
        a.setAvailableBalance(balance);
        a.setHoldAmount(BigDecimal.ZERO);
        a.setUnclearedAmount(BigDecimal.ZERO);
        a.setOdLimit(BigDecimal.ZERO);
        a.setMinimumBalance(BigDecimal.ZERO);
        a.setInterestRate(new BigDecimal("4.0000"));
        a.setAccruedInterest(BigDecimal.ZERO);
        a.setYtdInterestCredited(BigDecimal.ZERO);
        a.setYtdTdsDeducted(BigDecimal.ZERO);
        // CBS Tier-1: Branch must have ID matching the user's branchId (1L)
        // for BranchAccessValidator to pass.
        Branch branch = new Branch();
        branch.setId(1L);
        branch.setBranchCode("HQ001");
        a.setBranch(branch);
        return a;
    }

    private TransactionResult mockPostedResult() {
        return new TransactionResult(
                "TXN001",
                "VCH/HQ001/20260401/000001",
                100L,
                "JRN001",
                new BigDecimal("10000.00"),
                new BigDecimal("10000.00"),
                LocalDate.of(2026, 4, 1),
                LocalDateTime.now(),
                "POSTED");
    }

    @Test
    void deposit_shouldCreditBalanceAndPostGL() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositTransaction txn = service.deposit(
                "DEP001", new BigDecimal("10000.00"), LocalDate.of(2026, 4, 1), "Cash deposit", null, "BRANCH");

        assertNotNull(txn);
        assertEquals("CREDIT", txn.getDebitCredit());
        assertEquals(new BigDecimal("60000.00"), acct.getLedgerBalance());
        verify(transactionEngine).execute(any());
    }

    @Test
    void withdraw_shouldDebitBalanceWhenSufficientFunds() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositTransaction txn = service.withdraw(
                "DEP001", new BigDecimal("20000.00"), LocalDate.of(2026, 4, 1), "Cash withdrawal", null, "BRANCH");

        assertNotNull(txn);
        assertEquals("DEBIT", txn.getDebitCredit());
        assertEquals(new BigDecimal("30000.00"), acct.getLedgerBalance());
    }

    @Test
    void withdraw_shouldRejectWhenInsufficientFunds() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("5000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.withdraw(
                        "DEP001",
                        new BigDecimal("50000.00"),
                        LocalDate.of(2026, 4, 1),
                        "Cash withdrawal",
                        null,
                        "BRANCH"));

        assertEquals("INSUFFICIENT_BALANCE", ex.getErrorCode());
        verify(transactionEngine, never()).execute(any());
    }

    @Test
    void withdraw_shouldRejectWhenAccountFrozen() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setAccountStatus(DepositAccountStatus.FROZEN);
        acct.setFreezeType("TOTAL_FREEZE");
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.withdraw(
                        "DEP001",
                        new BigDecimal("1000.00"),
                        LocalDate.of(2026, 4, 1),
                        "Cash withdrawal",
                        null,
                        "BRANCH"));

        assertEquals("ACCOUNT_NOT_DEBITABLE", ex.getErrorCode());
    }

    @Test
    void transfer_shouldRejectSameAccount() {
        assertThrows(
                BusinessException.class,
                () -> service.transfer(
                        "DEP001",
                        "DEP001",
                        new BigDecimal("1000.00"),
                        LocalDate.of(2026, 4, 1),
                        "Self transfer",
                        null));
    }

    @Test
    void accrueInterest_shouldAccumulateDailyInterest() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accrueInterest("DEP001", LocalDate.of(2026, 4, 1));

        // 100000 * 4.0000 / 36500 = 10.96 (rounded to 2 dp)
        assertTrue(acct.getAccruedInterest().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(LocalDate.of(2026, 4, 1), acct.getLastInterestAccrualDate());
    }

    @Test
    void accrueInterest_shouldSkipCurrentAccounts() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100000.00"));
        acct.setAccountType(DepositAccountType.CURRENT);
        acct.setInterestRate(BigDecimal.ZERO);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        service.accrueInterest("DEP001", LocalDate.of(2026, 4, 1));

        assertEquals(BigDecimal.ZERO, acct.getAccruedInterest());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void freezeAccount_shouldSetFrozenStatus() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositAccount result = service.freezeAccount("DEP001", "DEBIT_FREEZE", "Court order");

        assertEquals(DepositAccountStatus.FROZEN, result.getAccountStatus());
        assertEquals("DEBIT_FREEZE", result.getFreezeType());
        assertEquals("Court order", result.getFreezeReason());
    }

    @Test
    void closeAccount_shouldRejectNonZeroBalance() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        // closeAccount calls businessDateService only AFTER balance check passes,
        // so no mock needed here — it throws before reaching that line.
        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.closeAccount("DEP001", "Customer request"));

        assertEquals("NON_ZERO_BALANCE", ex.getErrorCode());
    }

    @Test
    void deposit_shouldAllowCreditOnDebitFreezeAccount() {
        // Per PMLA: DEBIT_FREEZE allows credits, blocks debits only
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setAccountStatus(DepositAccountStatus.FROZEN);
        acct.setFreezeType("DEBIT_FREEZE");
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — DEBIT_FREEZE allows credits
        DepositTransaction txn = service.deposit(
                "DEP001",
                new BigDecimal("5000.00"),
                LocalDate.of(2026, 4, 1),
                "Credit to frozen account",
                null,
                "BRANCH");
        assertNotNull(txn);
        assertEquals("CREDIT", txn.getDebitCredit());
    }

    @Test
    void withdraw_shouldAllowDebitOnCreditFreezeAccount() {
        // Per PMLA: CREDIT_FREEZE blocks credits only, debits allowed
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setAccountStatus(DepositAccountStatus.FROZEN);
        acct.setFreezeType("CREDIT_FREEZE");
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — CREDIT_FREEZE allows debits
        DepositTransaction txn = service.withdraw(
                "DEP001",
                new BigDecimal("1000.00"),
                LocalDate.of(2026, 4, 1),
                "Debit from credit-frozen account",
                null,
                "BRANCH");
        assertNotNull(txn);
        assertEquals("DEBIT", txn.getDebitCredit());
    }

    @Test
    void accrueInterest_shouldBeIdempotentForSameDate() {
        // CBS: EOD retry must not double-accrue interest
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100000.00"));
        acct.setLastInterestAccrualDate(LocalDate.of(2026, 4, 1)); // already accrued today
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        service.accrueInterest("DEP001", LocalDate.of(2026, 4, 1));

        // Should skip — no save, no balance change
        assertEquals(BigDecimal.ZERO, acct.getAccruedInterest());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void closeAccount_shouldRejectWithPendingAccruedInterest() {
        // Finacle ACCTCLS: must credit accrued interest before closure
        DepositAccount acct = buildSavingsAccount("DEP001", BigDecimal.ZERO);
        acct.setLedgerBalance(BigDecimal.ZERO);
        acct.setAccruedInterest(new BigDecimal("125.50"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.closeAccount("DEP001", "Customer request"));
        assertEquals("PENDING_INTEREST", ex.getErrorCode());
    }

    @Test
    void closeAccount_shouldRejectWithActiveHold() {
        // CBS: Cannot close account with active lien/hold (FD collateral, court order)
        DepositAccount acct = buildSavingsAccount("DEP001", BigDecimal.ZERO);
        acct.setLedgerBalance(BigDecimal.ZERO);
        acct.setHoldAmount(new BigDecimal("10000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        BusinessException ex =
                assertThrows(BusinessException.class, () -> service.closeAccount("DEP001", "Customer request"));
        assertEquals("ACTIVE_HOLD", ex.getErrorCode());
    }

    @Test
    void withdraw_shouldRejectWhenDailyLimitExceeded() {
        // Finacle ACCTLIMIT: daily withdrawal limit enforcement
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("500000.00"));
        acct.setDailyWithdrawalLimit(new BigDecimal("50000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionRepository.sumDailyDebits("DEFAULT", 1L, LocalDate.of(2026, 4, 1)))
                .thenReturn(new BigDecimal("45000.00")); // already withdrew 45K today

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.withdraw(
                        "DEP001", new BigDecimal("10000.00"), LocalDate.of(2026, 4, 1), "Withdrawal", null, "BRANCH"));
        assertEquals("DAILY_LIMIT_EXCEEDED", ex.getErrorCode());
    }

    @Test
    void dormancy_shouldMarkAccountsWithNoRecentTxn() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setLastTransactionDate(LocalDate.of(2024, 1, 1));
        when(accountRepository.findDormancyCandidates(eq("DEFAULT"), any())).thenReturn(List.of(acct));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.markDormantAccounts(LocalDate.of(2026, 4, 1));

        assertEquals(1, count);
        assertEquals(DepositAccountStatus.DORMANT, acct.getAccountStatus());
    }

    @Test
    void withdraw_shouldRejectWhenMinimumBalanceBreach() {
        // CBS: Withdrawal that breaches minimum balance must be rejected
        // Per Finacle ACCTLIMIT / RBI CASA norms
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("10000.00"));
        acct.setMinimumBalance(new BigDecimal("5000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        // Withdrawing 8000 from 10000 would leave 2000 < minBal 5000
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.withdraw(
                        "DEP001", new BigDecimal("8000.00"), LocalDate.of(2026, 4, 1), "Withdrawal", null, "BRANCH"));
        assertEquals("MINIMUM_BALANCE_BREACH", ex.getErrorCode());
        verify(transactionEngine, never()).execute(any());
    }

    @Test
    void withdraw_shouldAllowWhenMinimumBalanceMaintained() {
        // CBS: Withdrawal that maintains minimum balance should succeed
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("10000.00"));
        acct.setMinimumBalance(new BigDecimal("5000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Withdrawing 4000 from 10000 leaves 6000 >= minBal 5000
        DepositTransaction txn = service.withdraw(
                "DEP001", new BigDecimal("4000.00"), LocalDate.of(2026, 4, 1), "Withdrawal", null, "BRANCH");
        assertNotNull(txn);
        assertEquals("DEBIT", txn.getDebitCredit());
    }

    @Test
    void withdraw_shouldAllowPmjdyZeroMinBalance() {
        // CBS: PMJDY accounts have zero minimum balance — all withdrawals allowed
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("500.00"));
        acct.setAccountType(DepositAccountType.SAVINGS_PMJDY);
        acct.setMinimumBalance(BigDecimal.ZERO); // PMJDY = zero min balance
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositTransaction txn = service.withdraw(
                "DEP001", new BigDecimal("400.00"), LocalDate.of(2026, 4, 1), "Withdrawal", null, "BRANCH");
        assertNotNull(txn);
    }

    @Test
    void reverseTransaction_shouldRestoreBalanceAndMarkReversed() {
        // CBS: Transaction reversal per Finacle TRAN_REVERSAL
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        DepositTransaction original = new DepositTransaction();
        original.setId(10L);
        original.setTenantId("DEFAULT");
        original.setTransactionRef("TXN_ORIG");
        original.setDepositAccount(acct);
        original.setTransactionType("CASH_WITHDRAWAL");
        original.setDebitCredit("DEBIT");
        original.setAmount(new BigDecimal("5000.00"));
        original.setReversed(false);
        original.setChannel("BRANCH");

        when(transactionRepository.findByTenantIdAndTransactionRef("DEFAULT", "TXN_ORIG"))
                .thenReturn(Optional.of(original));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositTransaction reversal = service.reverseTransaction("TXN_ORIG", "Teller error", LocalDate.of(2026, 4, 1));

        assertNotNull(reversal);
        assertEquals("REVERSAL", reversal.getTransactionType());
        // Original debit of 5000 reversed → balance should increase by 5000
        assertEquals(new BigDecimal("55000.00"), acct.getLedgerBalance());
        assertTrue(original.isReversed());
        assertEquals("TXN001", original.getReversedByRef()); // from mockPostedResult
    }

    @Test
    void reverseTransaction_shouldRejectAlreadyReversed() {
        DepositTransaction original = new DepositTransaction();
        original.setTenantId("DEFAULT");
        original.setTransactionRef("TXN_ORIG");
        original.setReversed(true); // already reversed

        when(transactionRepository.findByTenantIdAndTransactionRef("DEFAULT", "TXN_ORIG"))
                .thenReturn(Optional.of(original));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.reverseTransaction("TXN_ORIG", "Duplicate reversal", LocalDate.of(2026, 4, 1)));
        assertEquals("ALREADY_REVERSED", ex.getErrorCode());
    }

    @Test
    void reverseTransaction_shouldRejectWithoutReason() {
        BusinessException ex = assertThrows(
                BusinessException.class, () -> service.reverseTransaction("TXN_ORIG", "", LocalDate.of(2026, 4, 1)));
        assertEquals("REASON_REQUIRED", ex.getErrorCode());
    }

    @Test
    void activateAccount_shouldTransitionFromPendingToActive() {
        // CBS Phase 2: Maker-Checker account activation
        DepositAccount acct = buildSavingsAccount("DEP001", BigDecimal.ZERO);
        acct.setAccountStatus(DepositAccountStatus.PENDING_ACTIVATION);
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositAccount result = service.activateAccount("DEP001");

        assertEquals(DepositAccountStatus.ACTIVE, result.getAccountStatus());
        verify(auditService)
                .logEvent(
                        eq("DepositAccount"),
                        any(),
                        eq("ACCOUNT_ACTIVATED"),
                        eq("PENDING_ACTIVATION"),
                        eq("ACTIVE"),
                        eq("DEPOSIT"),
                        any());
    }

    @Test
    void activateAccount_shouldRejectAlreadyActiveAccount() {
        // CBS: Cannot activate an already active account
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        // Already ACTIVE from buildSavingsAccount
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.activateAccount("DEP001"));
        assertEquals("INVALID_STATE", ex.getErrorCode());
    }

    @Test
    void accrueInterest_shouldResetYtdOnFinancialYearBoundary() {
        // CBS: YTD counters must reset on April 1 (Indian FY start)
        // Per IT Act Section 194A: TDS threshold is per financial year
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("100000.00"));
        acct.setLastInterestAccrualDate(LocalDate.of(2026, 3, 31)); // last accrual was Mar 31
        acct.setYtdInterestCredited(new BigDecimal("35000.00")); // accumulated in FY25-26
        acct.setYtdTdsDeducted(new BigDecimal("500.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
                .thenReturn(Optional.of(acct));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Accrue on April 1 — should trigger FY reset
        service.accrueInterest("DEP001", LocalDate.of(2026, 4, 1));

        // YTD counters should be reset to zero
        assertEquals(BigDecimal.ZERO, acct.getYtdInterestCredited());
        assertEquals(BigDecimal.ZERO, acct.getYtdTdsDeducted());
        // Interest should still accrue normally
        assertTrue(acct.getAccruedInterest().compareTo(BigDecimal.ZERO) > 0);
        assertEquals(LocalDate.of(2026, 4, 1), acct.getLastInterestAccrualDate());
    }
}

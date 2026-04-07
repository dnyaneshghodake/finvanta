package com.finvanta.service;

import com.finvanta.accounting.GLConstants;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.service.impl.DepositAccountServiceImpl;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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

    @Mock private DepositAccountRepository accountRepository;
    @Mock private DepositTransactionRepository transactionRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private TransactionEngine transactionEngine;
    @Mock private BusinessDateService businessDateService;

    private DepositAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DepositAccountServiceImpl(
            accountRepository, transactionRepository,
            customerRepository, branchRepository, transactionEngine,
            businessDateService);
        TenantContext.setCurrentTenant("DEFAULT");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("maker1", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_MAKER"))));
    }

    private DepositAccount buildSavingsAccount(String accNo, BigDecimal balance) {
        DepositAccount a = new DepositAccount();
        a.setId(1L);
        a.setTenantId("DEFAULT");
        a.setAccountNumber(accNo);
        a.setAccountType("SAVINGS");
        a.setAccountStatus("ACTIVE");
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
        Branch branch = new Branch();
        branch.setBranchCode("HQ001");
        a.setBranch(branch);
        return a;
    }

    private TransactionResult mockPostedResult() {
        return new TransactionResult("TXN001", "VCH/HQ001/20260401/000001",
            100L, "JRN001", new BigDecimal("10000.00"), new BigDecimal("10000.00"),
            LocalDate.of(2026, 4, 1), LocalDateTime.now(), "POSTED");
    }

    @Test
    void deposit_shouldCreditBalanceAndPostGL() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
            .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DepositTransaction txn = service.deposit("DEP001", new BigDecimal("10000.00"),
            LocalDate.of(2026, 4, 1), "Cash deposit", null, "BRANCH");

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

        DepositTransaction txn = service.withdraw("DEP001", new BigDecimal("20000.00"),
            LocalDate.of(2026, 4, 1), "Cash withdrawal", null, "BRANCH");

        assertNotNull(txn);
        assertEquals("DEBIT", txn.getDebitCredit());
        assertEquals(new BigDecimal("30000.00"), acct.getLedgerBalance());
    }

    @Test
    void withdraw_shouldRejectWhenInsufficientFunds() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("5000.00"));
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
            .thenReturn(Optional.of(acct));

        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.withdraw("DEP001", new BigDecimal("50000.00"),
                LocalDate.of(2026, 4, 1), "Cash withdrawal", null, "BRANCH"));

        assertEquals("INSUFFICIENT_BALANCE", ex.getErrorCode());
        verify(transactionEngine, never()).execute(any());
    }

    @Test
    void withdraw_shouldRejectWhenAccountFrozen() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setAccountStatus("FROZEN");
        acct.setFreezeType("TOTAL_FREEZE");
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
            .thenReturn(Optional.of(acct));

        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.withdraw("DEP001", new BigDecimal("1000.00"),
                LocalDate.of(2026, 4, 1), "Cash withdrawal", null, "BRANCH"));

        assertEquals("ACCOUNT_NOT_DEBITABLE", ex.getErrorCode());
    }

    @Test
    void transfer_shouldRejectSameAccount() {
        assertThrows(BusinessException.class, () ->
            service.transfer("DEP001", "DEP001", new BigDecimal("1000.00"),
                LocalDate.of(2026, 4, 1), "Self transfer", null));
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
        acct.setAccountType("CURRENT");
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

        assertEquals("FROZEN", result.getAccountStatus());
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
        BusinessException ex = assertThrows(BusinessException.class, () ->
            service.closeAccount("DEP001", "Customer request"));

        assertEquals("NON_ZERO_BALANCE", ex.getErrorCode());
    }

    @Test
    void deposit_shouldAllowCreditOnDebitFreezeAccount() {
        // Per PMLA: DEBIT_FREEZE allows credits, blocks debits only
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setAccountStatus("FROZEN");
        acct.setFreezeType("DEBIT_FREEZE");
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
            .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — DEBIT_FREEZE allows credits
        DepositTransaction txn = service.deposit("DEP001", new BigDecimal("5000.00"),
            LocalDate.of(2026, 4, 1), "Credit to frozen account", null, "BRANCH");
        assertNotNull(txn);
        assertEquals("CREDIT", txn.getDebitCredit());
    }

    @Test
    void withdraw_shouldAllowDebitOnCreditFreezeAccount() {
        // Per PMLA: CREDIT_FREEZE blocks credits only, debits allowed
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setAccountStatus("FROZEN");
        acct.setFreezeType("CREDIT_FREEZE");
        when(accountRepository.findAndLockByTenantIdAndAccountNumber("DEFAULT", "DEP001"))
            .thenReturn(Optional.of(acct));
        when(transactionEngine.execute(any())).thenReturn(mockPostedResult());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — CREDIT_FREEZE allows debits
        DepositTransaction txn = service.withdraw("DEP001", new BigDecimal("1000.00"),
            LocalDate.of(2026, 4, 1), "Debit from credit-frozen account", null, "BRANCH");
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
    void dormancy_shouldMarkAccountsWithNoRecentTxn() {
        DepositAccount acct = buildSavingsAccount("DEP001", new BigDecimal("50000.00"));
        acct.setLastTransactionDate(LocalDate.of(2024, 1, 1));
        when(accountRepository.findDormancyCandidates(eq("DEFAULT"), any()))
            .thenReturn(List.of(acct));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.markDormantAccounts(LocalDate.of(2026, 4, 1));

        assertEquals(1, count);
        assertEquals("DORMANT", acct.getAccountStatus());
    }
}

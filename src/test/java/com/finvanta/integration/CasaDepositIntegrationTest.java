package com.finvanta.integration;

import com.finvanta.accounting.LedgerService;
import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.domain.entity.*;
import com.finvanta.domain.enums.*;
import com.finvanta.repository.*;
import com.finvanta.service.DepositAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CBS Full CASA Deposit Lifecycle Integration Test.
 *
 * Exercises the FULL TransactionEngine 10-step pipeline with real H2 DB:
 *   DepositAccountService → TransactionEngine → AccountingService
 *     → LedgerService (hash-chain) → GL balance update → Voucher
 *
 * Validates per Finacle/Temenos Tier-1:
 * 1. Double-entry GL integrity (DR == CR after every operation)
 * 2. Voucher generation (VCH/branch/date/seq format)
 * 3. Immutable ledger hash-chain (SHA-256 from GENESIS)
 * 4. tenant_ledger_state bootstrap on first posting
 * 5. Insufficient funds rejection with zero GL impact
 * 6. Freeze enforcement (TOTAL_FREEZE blocks debits)
 * 7. Transfer atomicity (debit + credit in single TX)
 * 8. Sequential voucher numbering across multiple deposits
 */
@SpringBootTest
@ActiveProfiles("test")
class CasaDepositIntegrationTest {

    @Autowired private DepositAccountService depositService;
    @Autowired private DepositAccountRepository accountRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private GLMasterRepository glMasterRepository;
    @Autowired private BusinessCalendarRepository calendarRepository;
    @Autowired private TransactionBatchRepository batchRepository;
    @Autowired private LedgerService ledgerService;

    private static final String TENANT = "TEST_CASA";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 4, 1);
    private Long testBranchId;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        setSecurityContext(0L, "HQ", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(Long branchId, String branchCode, String role) {
        BranchAwareUserDetails ud = new BranchAwareUserDetails(
                "admin", "password",
                List.of(new SimpleGrantedAuthority(role)),
                branchId, branchCode);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        ud, "password", ud.getAuthorities()));
    }

    private void setupReferenceData() {
        Branch branch = new Branch();
        branch.setTenantId(TENANT);
        branch.setBranchCode("BR001");
        branch.setBranchName("Test Branch");
        branch.setActive(true);
        branch.setCreatedBy("SYSTEM");
        branch = branchRepository.save(branch);
        testBranchId = branch.getId();
        setSecurityContext(testBranchId, "BR001", "ROLE_ADMIN");

        Customer customer = new Customer();
        customer.setTenantId(TENANT);
        customer.setCustomerNumber("CIF-CASA-001");
        customer.setFirstName("Casa");
        customer.setLastName("Test");
        customer.setKycVerified(true);
        customer.setKycVerifiedDate(BIZ_DATE);
        customer.setKycVerifiedBy("admin");
        customer.setActive(true);
        customer.setBranch(branch);
        customer.setCreatedBy("SYSTEM");
        customerRepository.save(customer);

        BusinessCalendar cal = new BusinessCalendar();
        cal.setTenantId(TENANT);
        cal.setBranch(branch);
        cal.setBranchCode("BR001");
        cal.setBusinessDate(BIZ_DATE);
        cal.setDayStatus(DayStatus.DAY_OPEN);
        cal.setDayOpenedBy("admin");
        cal.setCreatedBy("SYSTEM");
        calendarRepository.save(cal);

        createGL("1100", "Bank Account - Ops", GLAccountType.ASSET);
        createGL("2010", "Deposits - Savings", GLAccountType.LIABILITY);
        createGL("2020", "Deposits - Current", GLAccountType.LIABILITY);
        createGL("5010", "Interest Expense", GLAccountType.EXPENSE);

        TransactionBatch batch = new TransactionBatch();
        batch.setTenantId(TENANT);
        batch.setBusinessDate(BIZ_DATE);
        batch.setBatchName("CASA_BATCH");
        batch.setBatchType("INTRA_DAY");
        batch.setStatus("OPEN");
        batch.setOpenedBy("admin");
        batch.setOpenedAt(java.time.LocalDateTime.now());
        batch.setCreatedBy("SYSTEM");
        batchRepository.save(batch);
    }

    private void createGL(String code, String name, GLAccountType type) {
        GLMaster gl = new GLMaster();
        gl.setTenantId(TENANT);
        gl.setGlCode(code);
        gl.setGlName(name);
        gl.setAccountType(type);
        gl.setActive(true);
        gl.setHeaderAccount(false);
        gl.setCreatedBy("SYSTEM");
        glMasterRepository.save(gl);
    }

    private DepositAccount createActiveAccount() {
        Customer cust = customerRepository.findAll().stream()
                .filter(c -> c.getTenantId().equals(TENANT))
                .findFirst().orElseThrow();
        DepositAccount acct = depositService.openAccount(
                cust.getId(), testBranchId, "SAVINGS", "SAVINGS",
                null, "Nominee", "SPOUSE");
        depositService.activateAccount(acct.getAccountNumber());
        return depositService.getAccount(acct.getAccountNumber());
    }

    private void assertTrialBalance() {
        List<GLMaster> gls = glMasterRepository.findAllPostableAccounts(TENANT);
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        for (GLMaster gl : gls) {
            dr = dr.add(gl.getDebitBalance());
            cr = cr.add(gl.getCreditBalance());
        }
        assertEquals(0, dr.compareTo(cr),
                "TRIAL BALANCE FAILED: DR=" + dr + " CR=" + cr);
    }

    @Test
    @Transactional
    @DisplayName("Deposit: GL double-entry + voucher + ledger hash-chain + balance")
    void depositFullLifecycle() {
        setupReferenceData();
        DepositAccount acct = createActiveAccount();

        DepositTransaction txn = depositService.deposit(
                acct.getAccountNumber(), new BigDecimal("100000.00"),
                BIZ_DATE, "Initial deposit", null, "BRANCH");

        assertNotNull(txn);
        assertNotNull(txn.getVoucherNumber());
        assertTrue(txn.getVoucherNumber().startsWith("VCH/BR001/"));
        assertNotNull(txn.getTransactionRef());
        assertNotNull(txn.getJournalEntryId());
        assertEquals("CREDIT", txn.getDebitCredit());

        DepositAccount after = depositService.getAccount(acct.getAccountNumber());
        assertEquals(0, new BigDecimal("100000.00").compareTo(after.getLedgerBalance()));

        GLMaster bankOps = glMasterRepository.findByTenantIdAndGlCode(TENANT, "1100").orElseThrow();
        GLMaster deposits = glMasterRepository.findByTenantIdAndGlCode(TENANT, "2010").orElseThrow();
        assertEquals(0, new BigDecimal("100000.00").compareTo(bankOps.getDebitBalance()));
        assertEquals(0, new BigDecimal("100000.00").compareTo(deposits.getCreditBalance()));

        assertTrialBalance();
        assertTrue(ledgerService.verifyChainIntegrity());
    }

    @Test
    @Transactional
    @DisplayName("Withdrawal: balance debit + GL integrity")
    void withdrawalLifecycle() {
        setupReferenceData();
        DepositAccount acct = createActiveAccount();
        depositService.deposit(acct.getAccountNumber(),
                new BigDecimal("50000.00"), BIZ_DATE, "Seed", null, "BRANCH");

        DepositTransaction wd = depositService.withdraw(
                acct.getAccountNumber(), new BigDecimal("15000.00"),
                BIZ_DATE, "Cash withdrawal", null, "BRANCH");

        assertNotNull(wd);
        assertEquals("DEBIT", wd.getDebitCredit());
        assertNotNull(wd.getVoucherNumber());

        DepositAccount after = depositService.getAccount(acct.getAccountNumber());
        assertEquals(0, new BigDecimal("35000.00").compareTo(after.getLedgerBalance()));
        assertTrialBalance();
    }

    @Test
    @Transactional
    @DisplayName("Insufficient funds: rejected with no GL impact")
    void withdrawalInsufficientFunds() {
        setupReferenceData();
        DepositAccount acct = createActiveAccount();
        depositService.deposit(acct.getAccountNumber(),
                new BigDecimal("5000.00"), BIZ_DATE, "Seed", null, "BRANCH");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> depositService.withdraw(acct.getAccountNumber(),
                        new BigDecimal("50000.00"), BIZ_DATE, "Too much", null, "BRANCH"));

        assertEquals("INSUFFICIENT_BALANCE", ex.getErrorCode());
        DepositAccount unchanged = depositService.getAccount(acct.getAccountNumber());
        assertEquals(0, new BigDecimal("5000.00").compareTo(unchanged.getLedgerBalance()));
        assertTrialBalance();
    }

    /** Opens a CURRENT account (different type from SAVINGS) for transfer target. */
    private DepositAccount createActiveCurrentAccount() {
        Customer cust = customerRepository.findAll().stream()
                .filter(c -> c.getTenantId().equals(TENANT))
                .findFirst().orElseThrow();
        DepositAccount acct = depositService.openAccount(
                cust.getId(), testBranchId, "CURRENT", "CURRENT",
                null, "Nominee", "SPOUSE");
        depositService.activateAccount(acct.getAccountNumber());
        return depositService.getAccount(acct.getAccountNumber());
    }

    @Test
    @Transactional
    @DisplayName("Transfer: atomic debit/credit across two accounts")
    void transferLifecycle() {
        setupReferenceData();
        DepositAccount src = createActiveAccount();
        depositService.deposit(src.getAccountNumber(),
                new BigDecimal("100000.00"), BIZ_DATE, "Seed", null, "BRANCH");
        DepositAccount tgt = createActiveCurrentAccount();

        DepositTransaction xfer = depositService.transfer(
                src.getAccountNumber(), tgt.getAccountNumber(),
                new BigDecimal("30000.00"), BIZ_DATE, "Rent", null);

        assertNotNull(xfer);
        assertEquals(0, new BigDecimal("70000.00").compareTo(
                depositService.getAccount(src.getAccountNumber()).getLedgerBalance()));
        assertEquals(0, new BigDecimal("30000.00").compareTo(
                depositService.getAccount(tgt.getAccountNumber()).getLedgerBalance()));
        assertTrialBalance();
    }

    @Test
    @Transactional
    @DisplayName("Frozen account: debit blocked, GL untouched")
    void frozenAccountRejectsDebit() {
        setupReferenceData();
        DepositAccount acct = createActiveAccount();
        depositService.deposit(acct.getAccountNumber(),
                new BigDecimal("50000.00"), BIZ_DATE, "Seed", null, "BRANCH");
        depositService.freezeAccount(acct.getAccountNumber(), "TOTAL_FREEZE", "Court order");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> depositService.withdraw(acct.getAccountNumber(),
                        new BigDecimal("1000.00"), BIZ_DATE, "Blocked", null, "BRANCH"));

        assertEquals("ACCOUNT_NOT_DEBITABLE", ex.getErrorCode());
        assertTrialBalance();
    }

    @Test
    @Transactional
    @DisplayName("Multiple deposits: cumulative GL + sequential vouchers + hash-chain")
    void multipleDepositsSequentialVouchers() {
        setupReferenceData();
        DepositAccount acct = createActiveAccount();

        DepositTransaction t1 = depositService.deposit(acct.getAccountNumber(),
                new BigDecimal("25000.00"), BIZ_DATE, "Dep 1", null, "BRANCH");
        DepositTransaction t2 = depositService.deposit(acct.getAccountNumber(),
                new BigDecimal("75000.00"), BIZ_DATE, "Dep 2", null, "BRANCH");

        assertNotEquals(t1.getVoucherNumber(), t2.getVoucherNumber());

        DepositAccount after = depositService.getAccount(acct.getAccountNumber());
        assertEquals(0, new BigDecimal("100000.00").compareTo(after.getLedgerBalance()));

        GLMaster bankOps = glMasterRepository.findByTenantIdAndGlCode(TENANT, "1100").orElseThrow();
        assertEquals(0, new BigDecimal("100000.00").compareTo(bankOps.getDebitBalance()));

        assertTrialBalance();
        assertTrue(ledgerService.verifyChainIntegrity());
    }
}

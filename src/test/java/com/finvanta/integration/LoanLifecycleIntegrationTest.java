package com.finvanta.integration;

import com.finvanta.domain.entity.*;
import com.finvanta.domain.enums.*;
import com.finvanta.repository.*;
import com.finvanta.service.LoanAccountService;
import com.finvanta.util.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CBS Full Loan Lifecycle Integration Test.
 *
 * Validates the entire loan lifecycle end-to-end with real Spring context and H2 database:
 *   Day Open → Disbursement → Interest Accrual → Repayment → GL Integrity
 *
 * Per Finacle/Temenos testing standards, verifies:
 * 1. Double-entry GL integrity (DR == CR after every operation)
 * 2. Account balance consistency (subledger matches GL)
 * 3. Correct state transitions
 * 4. Business date enforcement
 */
@SpringBootTest
@ActiveProfiles("test")
class LoanLifecycleIntegrationTest {

    @Autowired private LoanAccountService loanAccountService;
    @Autowired private LoanAccountRepository accountRepository;
    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private LoanTransactionRepository transactionRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private GLMasterRepository glMasterRepository;
    @Autowired private BusinessCalendarRepository calendarRepository;

    private static final String TENANT = "TEST_TENANT";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 4, 1);

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        var auth = new UsernamePasswordAuthenticationToken(
            "admin", "password",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void setupReferenceData() {
        // Branch
        Branch branch = new Branch();
        branch.setTenantId(TENANT);
        branch.setBranchCode("TST01");
        branch.setBranchName("Test Branch");
        branch.setActive(true);
        branch.setCreatedBy("SYSTEM");
        branch = branchRepository.save(branch);

        // Customer (KYC verified, good CIBIL)
        Customer customer = new Customer();
        customer.setTenantId(TENANT);
        customer.setCustomerNumber("CUST-TEST-001");
        customer.setFirstName("Test");
        customer.setLastName("Customer");
        customer.setKycVerified(true);
        customer.setKycVerifiedDate(BIZ_DATE);
        customer.setKycVerifiedBy("admin");
        customer.setCibilScore(750);
        customer.setActive(true);
        customer.setBranch(branch);
        customer.setCreatedBy("SYSTEM");
        customerRepository.save(customer);

        // Business Calendar — day opened
        BusinessCalendar calendar = new BusinessCalendar();
        calendar.setTenantId(TENANT);
        calendar.setBusinessDate(BIZ_DATE);
        calendar.setDayStatus(DayStatus.DAY_OPEN);
        calendar.setDayOpenedBy("admin");
        calendar.setCreatedBy("SYSTEM");
        calendarRepository.save(calendar);

        // Also create next day for accrual tests
        BusinessCalendar nextDay = new BusinessCalendar();
        nextDay.setTenantId(TENANT);
        nextDay.setBusinessDate(BIZ_DATE.plusDays(1));
        nextDay.setDayStatus(DayStatus.DAY_OPEN);
        nextDay.setDayOpenedBy("admin");
        nextDay.setCreatedBy("SYSTEM");
        calendarRepository.save(nextDay);

        // GL Master — all postable accounts
        createGL("1001", "Loan Portfolio", GLAccountType.ASSET);
        createGL("1002", "Interest Receivable", GLAccountType.ASSET);
        createGL("1003", "Provision for NPA", GLAccountType.ASSET);
        createGL("1100", "Bank Operations", GLAccountType.ASSET);
        createGL("2100", "Interest Suspense", GLAccountType.LIABILITY);
        createGL("4001", "Interest Income", GLAccountType.INCOME);
        createGL("4002", "Fee Income", GLAccountType.INCOME);
        createGL("4003", "Penal Interest Income", GLAccountType.INCOME);
        createGL("5001", "Provision Expense", GLAccountType.EXPENSE);
        createGL("5002", "Write-Off Expense", GLAccountType.EXPENSE);
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

    private LoanApplication createApprovedApplication() {
        Branch branch = branchRepository.findAll().stream()
            .filter(b -> b.getTenantId().equals(TENANT)).findFirst().orElseThrow();
        Customer customer = customerRepository.findAll().stream()
            .filter(c -> c.getTenantId().equals(TENANT)).findFirst().orElseThrow();

        LoanApplication app = new LoanApplication();
        app.setTenantId(TENANT);
        app.setApplicationNumber("APP-TEST-001");
        app.setCustomer(customer);
        app.setBranch(branch);
        app.setProductType("TERM_LOAN");
        app.setRequestedAmount(new BigDecimal("1000000"));
        app.setApprovedAmount(new BigDecimal("1000000"));
        app.setInterestRate(new BigDecimal("10.0000"));
        app.setTenureMonths(12);
        app.setStatus(ApplicationStatus.APPROVED);
        app.setApplicationDate(BIZ_DATE);
        app.setCreatedBy("admin");
        return applicationRepository.save(app);
    }

    // ========================================================================
    // FULL LIFECYCLE TEST
    // ========================================================================

    @Test
    @Transactional
    @DisplayName("Full lifecycle: Create → Disburse → Accrue → Repay → Verify GL integrity")
    void fullLoanLifecycle() {
        setupReferenceData();
        LoanApplication app = createApprovedApplication();

        // --- Create Account ---
        LoanAccount account = loanAccountService.createLoanAccount(app.getId());
        assertNotNull(account.getAccountNumber());
        assertEquals(LoanStatus.ACTIVE, account.getStatus());
        assertEquals(0, new BigDecimal("1000000").compareTo(account.getSanctionedAmount()));
        assertTrue(account.getEmiAmount().compareTo(BigDecimal.ZERO) > 0);

        String accNo = account.getAccountNumber();

        // --- Disburse ---
        LoanAccount disbursed = loanAccountService.disburseLoan(accNo);
        assertEquals(0, new BigDecimal("1000000.00").compareTo(disbursed.getOutstandingPrincipal()));
        assertEquals(BIZ_DATE, disbursed.getDisbursementDate());

        // CBS Transaction 360: Verify disbursement has voucher and journal linkage
        var disbTxns = transactionRepository.findByTenantIdAndLoanAccountIdOrderByPostingDateDesc(
            TENANT, disbursed.getId());
        assertFalse(disbTxns.isEmpty(), "Disbursement transaction must exist");
        LoanTransaction disbTxn = disbTxns.get(0);
        assertNotNull(disbTxn.getVoucherNumber(), "Disbursement must have voucher number");
        assertNotNull(disbTxn.getJournalEntryId(), "Disbursement must link to journal entry");
        assertNotNull(disbTxn.getTransactionRef(), "Disbursement must have transaction ref");

        // Verify GL after disbursement
        GLMaster loanAssetGL = glMasterRepository.findByTenantIdAndGlCode(TENANT, "1001").orElseThrow();
        GLMaster bankOpsGL = glMasterRepository.findByTenantIdAndGlCode(TENANT, "1100").orElseThrow();
        assertEquals(0, new BigDecimal("1000000.00").compareTo(loanAssetGL.getDebitBalance()));
        assertEquals(0, new BigDecimal("1000000.00").compareTo(bankOpsGL.getCreditBalance()));

        // --- Interest Accrual (1 day) ---
        LocalDate accrualDate = BIZ_DATE.plusDays(1);
        LoanTransaction accrualTxn = loanAccountService.applyInterestAccrual(accNo, accrualDate);
        assertNotNull(accrualTxn);
        assertEquals(TransactionType.INTEREST_ACCRUAL, accrualTxn.getTransactionType());

        // (1,000,000 × 10% / 365) × 1 = 273.97
        assertEquals(0, new BigDecimal("273.97").compareTo(accrualTxn.getAmount()));

        // CBS: Verify voucher number is generated for every transaction (Transaction 360 view)
        assertNotNull(accrualTxn.getVoucherNumber(), "Voucher number must be generated for accrual");
        assertTrue(accrualTxn.getVoucherNumber().startsWith("VCH/"),
            "Voucher must follow CBS format VCH/branch/date/seq");

        // Verify account balance
        LoanAccount afterAccrual = loanAccountService.getAccount(accNo);
        assertEquals(0, new BigDecimal("273.97").compareTo(afterAccrual.getAccruedInterest()));

        // --- Repayment (1 EMI) ---
        BigDecimal emiAmount = afterAccrual.getEmiAmount();
        LoanTransaction repayTxn = loanAccountService.processRepayment(accNo, emiAmount, accrualDate);
        assertNotNull(repayTxn);
        assertTrue(repayTxn.getPrincipalComponent().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(repayTxn.getInterestComponent().compareTo(BigDecimal.ZERO) > 0);

        // CBS Transaction 360: Verify repayment has voucher
        assertNotNull(repayTxn.getVoucherNumber(), "Repayment must have voucher number");
        assertTrue(repayTxn.getVoucherNumber().startsWith("VCH/"),
            "Repayment voucher must follow CBS format");

        // Principal should decrease
        LoanAccount afterRepay = loanAccountService.getAccount(accNo);
        assertTrue(afterRepay.getOutstandingPrincipal().compareTo(new BigDecimal("1000000.00")) < 0);

        // --- Trial Balance: DR == CR ---
        List<GLMaster> allGLs = glMasterRepository.findAllPostableAccounts(TENANT);
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (GLMaster gl : allGLs) {
            totalDebit = totalDebit.add(gl.getDebitBalance());
            totalCredit = totalCredit.add(gl.getCreditBalance());
        }
        assertEquals(0, totalDebit.compareTo(totalCredit),
            "Trial balance FAILED: DR=" + totalDebit + " CR=" + totalCredit);

        // --- Idempotency: duplicate accrual returns null ---
        assertNull(loanAccountService.applyInterestAccrual(accNo, accrualDate));
    }

    @Test
    @Transactional
    @DisplayName("Duplicate disbursement is rejected")
    void disbursementIdempotency() {
        setupReferenceData();
        LoanApplication app = createApprovedApplication();
        LoanAccount account = loanAccountService.createLoanAccount(app.getId());
        loanAccountService.disburseLoan(account.getAccountNumber());

        assertThrows(Exception.class, () ->
            loanAccountService.disburseLoan(account.getAccountNumber()));
    }

    @Test
    @Transactional
    @DisplayName("Duplicate account creation is rejected")
    void accountCreationIdempotency() {
        setupReferenceData();
        LoanApplication app = createApprovedApplication();
        loanAccountService.createLoanAccount(app.getId());

        assertThrows(Exception.class, () ->
            loanAccountService.createLoanAccount(app.getId()));
    }
}

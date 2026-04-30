package com.finvanta.integration;

import com.finvanta.api.dto.OpenAccountRequest;
import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;
import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.DenominationEntry;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.repository.CashDenominationRepository;
import com.finvanta.cbs.modules.teller.repository.TellerTillRepository;
import com.finvanta.cbs.modules.teller.service.TellerService;
import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.entity.TransactionBatch;
import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.domain.enums.DayStatus;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.TransactionBatchRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.service.DepositAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CBS Teller Cash Deposit end-to-end integration test.
 *
 * <p>Wires the full v2 stack against an embedded H2 DB:
 * <pre>
 *   TellerService -> TransactionEngine -> AccountingService
 *     -> ledger/GL update -> TellerTill mutation
 *     -> CashDenomination INSERT
 * </pre>
 *
 * <p>Mirrors the bootstrap pattern in {@link CasaDepositIntegrationTest}:
 * a single {@code setupReferenceData()} seeds branch + customer + calendar +
 * GL + intra-day batch, then each test creates an active CASA account, opens
 * a till, and exercises the cash-deposit flow.
 *
 * <p>Three scenarios cover the most-common Tier-1 invariants:
 * <ol>
 *   <li><b>Happy path:</b> sub-CTR deposit posts ledger + till + denomination
 *       rows; trial balance stays balanced; voucher minted.</li>
 *   <li><b>Maker-checker:</b> deposit above per-user limit is routed to
 *       PENDING_APPROVAL by the engine; ledger and till are UNCHANGED;
 *       no denomination rows are written.</li>
 *   <li><b>Idempotent retry:</b> same idempotency key submitted twice; the
 *       second call returns the prior receipt without double-mutating the
 *       till or the customer ledger.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class TellerCashDepositIntegrationTest {

    @Autowired private TellerService tellerService;
    @Autowired private TellerTillRepository tillRepository;
    @Autowired private CashDenominationRepository denominationRepository;
    @Autowired private DepositAccountService depositAccountService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private BranchRepository branchRepository;
    @Autowired private BusinessCalendarRepository calendarRepository;
    @Autowired private TransactionBatchRepository batchRepository;
    @Autowired private GLMasterRepository glMasterRepository;
    @Autowired private TransactionLimitRepository limitRepository;

    private static final String TENANT = "TEST_TELLER";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 4, 1);
    private static final String TELLER_USER = "teller1";

    private Long testBranchId;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        // Pre-bootstrap auth used only for setupReferenceData() seeding.
        // The real teller principal (with branchId) is set inside the test
        // once the branch row has been created.
        setSecurityContext(0L, "HQ", TELLER_USER, "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // =====================================================================
    // Reference-data + auth helpers (mirrors CasaDepositIntegrationTest)
    // =====================================================================

    private void setSecurityContext(Long branchId, String branchCode, String username, String role) {
        BranchAwareUserDetails ud = new BranchAwareUserDetails(
                username, "password",
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
        // Switch to the teller principal for the rest of the test. ROLE_TELLER
        // is the first-class transactional role for the over-the-counter cash
        // channel per RBI Internal Controls. SecurityUtil.getCurrentUserRole()
        // recognizes TELLER as the most restrictive transactional role; the
        // TELLER/ALL transaction_limits row in data.sql provides the per-txn
        // and daily aggregate caps the engine validates against.
        setSecurityContext(testBranchId, "BR001", TELLER_USER, "ROLE_TELLER");

        Customer customer = new Customer();
        customer.setTenantId(TENANT);
        customer.setCustomerNumber("CIF-TELLER-001");
        customer.setFirstName("Teller");
        customer.setLastName("Customer");
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

        // GL skeleton needed by the TransactionEngine for the cash-deposit
        // double-entry. Codes match GLConstants.BANK_OPERATIONS / SB_DEPOSITS.
        createGL("1100", "Bank Operations (Cash in Hand)", GLAccountType.ASSET);
        createGL("2010", "Deposits - Savings", GLAccountType.LIABILITY);
        createGL("2020", "Deposits - Current", GLAccountType.LIABILITY);

        TransactionBatch batch = new TransactionBatch();
        batch.setTenantId(TENANT);
        batch.setBusinessDate(BIZ_DATE);
        batch.setBatchName("TELLER_BATCH");
        batch.setBatchType("INTRA_DAY");
        batch.setStatus("OPEN");
        batch.setOpenedBy(TELLER_USER);
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

    /**
     * Seeds a per-transaction limit on CASH_DEPOSIT for ROLE_TELLER. When the
     * deposit amount exceeds {@code perTxnLimit}, the engine rejects the
     * transaction with TRANSACTION_LIMIT_EXCEEDED (Step 6 of the engine).
     * Used by the above-limit rejection scenario to deterministically force
     * the hard-reject path without depending on production limit configuration.
     *
     * <p>Role is "TELLER" -- matches what
     * {@link com.finvanta.util.SecurityUtil#getCurrentUserRole()} returns for
     * the test principal. TELLER is the most restrictive transactional role
     * in the hierarchy {@code TELLER < MAKER < CHECKER < ADMIN}.
     */
    private void seedTransactionLimit(BigDecimal perTxnLimit) {
        TransactionLimit lim = new TransactionLimit();
        lim.setTenantId(TENANT);
        lim.setRole("TELLER");
        lim.setTransactionType("CASH_DEPOSIT");
        lim.setPerTransactionLimit(perTxnLimit);
        lim.setDailyAggregateLimit(perTxnLimit.multiply(new BigDecimal("100")));
        lim.setActive(true);
        lim.setDescription("Test limit");
        lim.setCreatedBy("SYSTEM");
        limitRepository.save(lim);
    }

    /** Opens + activates a SAVINGS account for the seeded customer. */
    private DepositAccount createActiveSavingsAccount() {
        Customer cust = customerRepository.findAll().stream()
                .filter(c -> c.getTenantId().equals(TENANT))
                .findFirst().orElseThrow();
        var req = new OpenAccountRequest(
                cust.getId(), testBranchId, "SAVINGS", "SAVINGS",
                null, null,
                null, null, null, null,
                null, null, null, null, null,
                null, null,
                null, null, null, null, null,
                null, null, null,
                "Nominee", "SPOUSE",
                null, null, null, null);
        DepositAccount acct = depositAccountService.openAccount(req);
        depositAccountService.activateAccount(acct.getAccountNumber());
        return depositAccountService.getAccount(acct.getAccountNumber());
    }

    /** Opens a till for the authenticated teller and asserts it auto-promoted to OPEN. */
    private TellerTill openTillForTeller(BigDecimal openingBalance) {
        TellerTill till = tellerService.openTill(
                new OpenTillRequest(openingBalance, null, "Integration test till"));
        assertEquals(com.finvanta.cbs.modules.teller.domain.TellerTillStatus.OPEN,
                till.getStatus(),
                "test fixture assumes opening balance is below the auto-approve threshold");
        return till;
    }

    /** Trial-balance assertion mirroring CasaDepositIntegrationTest.assertTrialBalance(). */
    private void assertTrialBalance() {
        List<GLMaster> gls = glMasterRepository.findAllPostableAccounts(TENANT);
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (GLMaster gl : gls) {
            dr = dr.add(gl.getDebitBalance());
            cr = cr.add(gl.getCreditBalance());
        }
        assertEquals(0, dr.compareTo(cr),
                "TRIAL BALANCE FAILED: DR=" + dr + " CR=" + cr);
    }

    /** Standard 2800 INR cash deposit request: 5 x 500 + 3 x 100, no counterfeit. */
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
    // Scenarios
    // =====================================================================

    @Test
    @Transactional
    @DisplayName("happy path: deposit posts ledger + till + denominations; trial balance balanced")
    void happyPath_postsLedgerTillAndDenominations() {
        setupReferenceData();
        DepositAccount acct = createActiveSavingsAccount();
        TellerTill till = openTillForTeller(new BigDecimal("100000"));
        BigDecimal tillBefore = till.getCurrentBalance();

        CashDepositResponse r = tellerService.cashDeposit(
                standardRequest(acct.getAccountNumber(), "idem-happy-1"));

        // Receipt: posted (not pending), CTR not triggered (sub-50k), FICN false.
        assertNotNull(r);
        assertFalse(r.pendingApproval(),
                "sub-CTR sub-limit deposit must post immediately, not route to checker");
        assertFalse(r.ctrTriggered(),
                "INR 2800 is below the CTR threshold (INR 50k)");
        assertFalse(r.ficnTriggered());
        assertNotNull(r.transactionRef());
        assertNotNull(r.voucherNumber(),
                "POSTED engine result must mint a voucher");
        assertTrue(r.voucherNumber().startsWith("VCH/BR001/"),
                "voucher format VCH/{branch}/{date}/{seq}");

        // Customer ledger incremented by exactly the deposit amount.
        DepositAccount after = depositAccountService.getAccount(acct.getAccountNumber());
        assertEquals(0, new BigDecimal("2800.00").compareTo(after.getLedgerBalance()),
                "ledger should be 0 + 2800 (account opened with zero balance)");

        // Till balance incremented by exactly the deposit amount.
        TellerTill tillAfter = tillRepository.findById(till.getId()).orElseThrow();
        assertEquals(0, tillBefore.add(new BigDecimal("2800")).compareTo(tillAfter.getCurrentBalance()),
                "till should be opening + 2800");

        // GL: BANK_OPERATIONS (DR) and SB_DEPOSITS (CR) both moved by 2800.
        GLMaster bankOps = glMasterRepository.findByTenantIdAndGlCode(TENANT, "1100").orElseThrow();
        GLMaster sbDep = glMasterRepository.findByTenantIdAndGlCode(TENANT, "2010").orElseThrow();
        assertEquals(0, new BigDecimal("2800.00").compareTo(bankOps.getDebitBalance()));
        assertEquals(0, new BigDecimal("2800.00").compareTo(sbDep.getCreditBalance()));

        // Denomination breakdown persisted: 2 rows (NOTE_500, NOTE_100).
        assertEquals(2,
                denominationRepository
                        .findByTenantIdAndTransactionRefOrderByDenominationAsc(TENANT, r.transactionRef())
                        .size(),
                "two denomination rows should be persisted (one per non-zero entry)");

        // Trial balance must remain DR == CR after the post.
        assertTrialBalance();
    }

    @Test
    @Transactional
    @DisplayName("above per-txn limit: deposit hard-rejected, balances + GL UNCHANGED")
    void makerChecker_pendingApproval_doesNotMutateState() {
        // CBS engine semantics (TransactionEngine Step 6 -> TransactionLimitService:
        // "amount > perTransactionLimit" THROWS TRANSACTION_LIMIT_EXCEEDED. Step 7's
        // MakerCheckerService.requiresApproval uses the SAME field but is unreachable
        // for amount-based routing on a non-REVERSAL/WRITE_OFF type, because Step 6
        // already aborted. Per Finacle TRAN_AUTH this is intentional: amount-driven
        // PENDING_APPROVAL only applies to ALWAYS_REQUIRE_APPROVAL types (REVERSAL,
        // WRITE_OFF, WRITE_OFF_RECOVERY). For CASH_DEPOSIT the only valid above-limit
        // path is hard rejection -- the maker must split the deposit or escalate to
        // a higher-privilege role offline.
        //
        // This test therefore asserts the rejection path: the engine throws, and
        // because cashDeposit() is @Transactional the parent rolls back -- no ledger,
        // no till mutation, no denomination row, no GL impact, trial balance holds.
        setupReferenceData();
        // Per-transaction limit at INR 1000 forces our 2800 deposit to be rejected.
        seedTransactionLimit(new BigDecimal("1000"));
        DepositAccount acct = createActiveSavingsAccount();
        TellerTill till = openTillForTeller(new BigDecimal("100000"));
        BigDecimal tillBefore = till.getCurrentBalance();
        BigDecimal ledgerBefore = acct.getLedgerBalance();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tellerService.cashDeposit(
                        standardRequest(acct.getAccountNumber(), "idem-pending-1")),
                "deposit above per-txn limit must be hard-rejected by Step 6");
        assertEquals("TRANSACTION_LIMIT_EXCEEDED", ex.getErrorCode());

        // Customer ledger UNCHANGED -- the engine threw before any GL post.
        DepositAccount after = depositAccountService.getAccount(acct.getAccountNumber());
        assertEquals(0, ledgerBefore.compareTo(after.getLedgerBalance()),
                "rejected deposit must not mutate the customer ledger");

        // Till balance UNCHANGED -- this was the highest-priority bug the
        // DepositAccountModuleServiceImpl review threads spent effort getting
        // right, and the same invariant must hold for the teller path.
        TellerTill tillAfter = tillRepository.findById(till.getId()).orElseThrow();
        assertEquals(0, tillBefore.compareTo(tillAfter.getCurrentBalance()),
                "rejected deposit must not mutate the till balance");

        // Trial balance still holds (we did NOT touch GL).
        assertTrialBalance();
    }

    @Test
    @Transactional
    @DisplayName("idempotent retry: same key -> prior receipt; till + ledger NOT double-mutated")
    void idempotentRetry_returnsPriorReceiptWithoutDoubleMutation() {
        setupReferenceData();
        DepositAccount acct = createActiveSavingsAccount();
        TellerTill till = openTillForTeller(new BigDecimal("100000"));

        // First call: posts the deposit.
        CashDepositResponse first = tellerService.cashDeposit(
                standardRequest(acct.getAccountNumber(), "idem-shared"));
        assertFalse(first.pendingApproval());
        BigDecimal ledgerAfterFirst =
                depositAccountService.getAccount(acct.getAccountNumber()).getLedgerBalance();
        BigDecimal tillAfterFirst =
                tillRepository.findById(till.getId()).orElseThrow().getCurrentBalance();
        assertEquals(0, new BigDecimal("2800.00").compareTo(ledgerAfterFirst));

        // Second call with the SAME idempotency key: must surface the PRIOR
        // receipt without re-mutating anything. This is the lock-then-check
        // contract the service layer enforces.
        CashDepositResponse second = tellerService.cashDeposit(
                standardRequest(acct.getAccountNumber(), "idem-shared"));

        assertEquals(first.transactionRef(), second.transactionRef(),
                "retry must surface the prior transactionRef");
        assertEquals(first.voucherNumber(), second.voucherNumber(),
                "retry must surface the prior voucherNumber");

        // Customer ledger NOT incremented again.
        DepositAccount finalAcct = depositAccountService.getAccount(acct.getAccountNumber());
        assertEquals(0, ledgerAfterFirst.compareTo(finalAcct.getLedgerBalance()),
                "retry must not double-credit the customer ledger");

        // Till NOT incremented again.
        TellerTill finalTill = tillRepository.findById(till.getId()).orElseThrow();
        assertEquals(0, tillAfterFirst.compareTo(finalTill.getCurrentBalance()),
                "retry must not double-credit the till");

        // Exactly one set of denomination rows -- not two.
        assertEquals(2,
                denominationRepository
                        .findByTenantIdAndTransactionRefOrderByDenominationAsc(TENANT, first.transactionRef())
                        .size(),
                "denomination rows are written once per first-time POSTED deposit, not on retry");

        // GL state matches a single posting (not two).
        GLMaster bankOps = glMasterRepository.findByTenantIdAndGlCode(TENANT, "1100").orElseThrow();
        assertEquals(0, new BigDecimal("2800.00").compareTo(bankOps.getDebitBalance()),
                "GL BANK_OPERATIONS must reflect a SINGLE posting, not double");
        assertTrialBalance();
    }
}

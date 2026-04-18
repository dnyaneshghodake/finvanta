package com.finvanta.integration;

import com.finvanta.accounting.GLConstants;
import com.finvanta.accounting.LedgerService;
import com.finvanta.audit.AuditService;
import com.finvanta.batch.ReconciliationService;
import com.finvanta.charge.ChargeKernel;
import com.finvanta.charge.ChargeResult;
import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.domain.entity.*;
import com.finvanta.domain.enums.*;
import com.finvanta.repository.*;
import com.finvanta.service.DepositAccountService;
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
 * CBS End-to-End Integration Test (Phase 7 Tier-1 hardening).
 *
 * <p>Complements {@link LoanLifecycleIntegrationTest} (which covers loan origination,
 * disbursement, interest accrual, repayment) by exercising the <b>CASA + Charges +
 * Ledger/Audit chain + Reconciliation</b> flow end-to-end with real Spring context,
 * real TransactionEngine pipeline, and H2 in-memory database.
 *
 * <p><b>Flow exercised</b> (per Finacle / Temenos / BNP Tier-1 CBS standards and
 * RBI IT Governance Direction 2023):
 * <ol>
 *   <li>Tenant + Branch + Customer + Business Calendar + TransactionBatch + GL Master
 *       seeded (bootstrap state).</li>
 *   <li>Security context with {@link BranchAwareUserDetails} so
 *       {@code SecurityUtil.getCurrentUserBranchId()} resolves correctly.</li>
 *   <li>CASA Savings account seeded in ACTIVE state (post-maker-checker
 *       activation, simulating CHECKER approval).</li>
 *   <li>{@link DepositAccountService#deposit} → exercises full 20-step
 *       {@code TransactionEngine} chain: amount validation → limit enforcement →
 *       account status → business date → day status → batch control → GL resolution →
 *       GL validation → double-entry → GL balance update (locked) → immutable ledger
 *       (hash chain) → batch totals (locked) → subledger update → schedule allocation
 *       → NPA suspense → transaction record → idempotency → audit trail →
 *       reconciliation → journal↔ledger link → transaction↔journal link.</li>
 *   <li>{@link DepositAccountService#withdraw} → symmetric debit leg,
 *       separate TransactionEngine execution, independent voucher.</li>
 *   <li>{@link ChargeKernel#levyCharge} → exercises multi-leg journal posting
 *       (customer DR, fee income CR, CGST CR, SGST CR) via TransactionEngine,
 *       validates GST split per GST Act 2017 §12 (intra-state CGST+SGST vs
 *       inter-state IGST).</li>
 *   <li>{@link ChargeKernel#reverseCharge} → symmetric contra journal
 *       (customer CR, fee income DR, CGST DR, SGST DR), leaves GL neutral.</li>
 *   <li>Final assertions: trial balance DR == CR, ledger hash chain fully
 *       verified, audit chain fully verified, GL ↔ ledger reconciliation
 *       balanced (zero discrepancies).</li>
 * </ol>
 *
 * <p><b>Why this test exists:</b> The pre-phase-7 test suite validated each CBS
 * subsystem (TransactionEngine, ChargeKernel, LedgerService, AuditService,
 * ReconciliationService) in isolation with mocks, plus loan lifecycle with a
 * full Spring context. There was no single harness that asserted the complete
 * CBS <i>happy path</i> produces:
 * <ul>
 *   <li>a balanced trial balance after a realistic mix of deposits, withdrawals,
 *       and charge levies</li>
 *   <li>an intact ledger hash chain walkable back to {@code GENESIS}</li>
 *   <li>an intact audit hash chain walkable back to {@code GENESIS}</li>
 *   <li>a {@code ReconciliationService.reconcileLedgerVsGL()} result with
 *       {@code balanced=true} and zero discrepancies</li>
 * </ul>
 *
 * <p>Per Finacle / Temenos Tier-1 certification standards: the full CBS stack
 * must be regression-tested end-to-end on every release, not just individual
 * components.
 */
@SpringBootTest
@ActiveProfiles("test")
class CbsEndToEndIntegrationTest {

    @Autowired
    private DepositAccountService depositService;

    @Autowired
    private ChargeKernel chargeKernel;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private GLMasterRepository glMasterRepository;

    @Autowired
    private BusinessCalendarRepository calendarRepository;

    @Autowired
    private TransactionBatchRepository batchRepository;

    @Autowired
    private DepositAccountRepository depositAccountRepository;

    @Autowired
    private DepositTransactionRepository depositTransactionRepository;

    @Autowired
    private ChargeDefinitionRepository chargeDefinitionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static final String TENANT = "E2E_TEST_TENANT";
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 4, 1);

    private Long branchId;
    private Long customerId;
    private String accountNumber;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
        setSecurityContext(0L, "HQ");
        // CBS: Clean ALL orphaned audit records for this test tenant.
        // AuditService.logEvent() uses REQUIRES_NEW propagation which commits
        // audit records independently of the test's @Transactional rollback.
        // Stale records break the hash chain for subsequent test runs.
        // Must use findAllByTenantIdOrderByIdAsc (unbounded) not findRecentAuditLogs
        // (capped at 500) to ensure complete cleanup.
        auditLogRepository.deleteAll(
                auditLogRepository.findAllByTenantIdOrderByIdAsc(
                        TENANT, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)));
    }

    private void setSecurityContext(Long branchId, String branchCode) {
        BranchAwareUserDetails userDetails = new BranchAwareUserDetails(
                "admin", "password", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), branchId, branchCode);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        userDetails, "password", userDetails.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        // CBS: Clean up audit records committed via REQUIRES_NEW that survive
        // the test's @Transactional rollback. Must run AFTER each test to prevent
        // stale records from breaking the hash chain in subsequent tests.
        // With executeInternal() using REQUIRES_NEW, ALL audit records created
        // inside the engine pipeline (logEventInline) are committed independently.
        try {
            TenantContext.setCurrentTenant(TENANT);
            auditLogRepository.deleteAll(
                    auditLogRepository.findAllByTenantIdOrderByIdAsc(
                            TENANT, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)));
        } catch (Exception e) {
            // Best-effort cleanup — don't fail teardown
        }
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * Seeds the minimum reference data a Tier-1 CBS needs to accept its first
     * customer transaction: tenant scope, operational branch, KYC-verified
     * customer, GL chart of accounts, business calendar in {@code DAY_OPEN}
     * state, and an {@code OPEN} transaction batch per Finacle BATCH_MASTER.
     */
    private void setupReferenceData() {
        Branch branch = new Branch();
        branch.setTenantId(TENANT);
        branch.setBranchCode("E2E01");
        branch.setBranchName("E2E Test Branch");
        branch.setState("MH");
        branch.setActive(true);
        branch.setCreatedBy("SYSTEM");
        branch = branchRepository.save(branch);
        branchId = branch.getId();

        setSecurityContext(branchId, "E2E01");

        Customer customer = new Customer();
        customer.setTenantId(TENANT);
        customer.setCustomerNumber("CIF-E2E-001");
        customer.setFirstName("E2E");
        customer.setLastName("Customer");
        customer.setKycVerified(true);
        customer.setKycVerifiedDate(BIZ_DATE);
        customer.setKycVerifiedBy("admin");
        customer.setCibilScore(780);
        customer.setActive(true);
        customer.setBranch(branch);
        customer.setCreatedBy("SYSTEM");
        customer = customerRepository.save(customer);
        customerId = customer.getId();

        BusinessCalendar cal = new BusinessCalendar();
        cal.setTenantId(TENANT);
        cal.setBranch(branch);
        cal.setBranchCode(branch.getBranchCode());
        cal.setBusinessDate(BIZ_DATE);
        cal.setDayStatus(DayStatus.DAY_OPEN);
        cal.setDayOpenedBy("admin");
        cal.setCreatedBy("SYSTEM");
        calendarRepository.save(cal);

        TransactionBatch batch = new TransactionBatch();
        batch.setTenantId(TENANT);
        batch.setBusinessDate(BIZ_DATE);
        batch.setBatchName("E2E_BATCH");
        batch.setBatchType("INTRA_DAY");
        batch.setStatus("OPEN");
        batch.setOpenedBy("admin");
        batch.setOpenedAt(java.time.LocalDateTime.now());
        batch.setCreatedBy("SYSTEM");
        batch.setBranch(branch);
        batchRepository.save(batch);

        createGL(GLConstants.LOAN_ASSET, "Loan Portfolio", GLAccountType.ASSET);
        createGL(GLConstants.BANK_OPERATIONS, "Bank Operations", GLAccountType.ASSET);
        createGL(GLConstants.SB_DEPOSITS, "Savings Bank Deposits", GLAccountType.LIABILITY);
        createGL(GLConstants.CA_DEPOSITS, "Current Account Deposits", GLAccountType.LIABILITY);
        createGL(GLConstants.CGST_PAYABLE, "CGST Payable", GLAccountType.LIABILITY);
        createGL(GLConstants.SGST_PAYABLE, "SGST Payable", GLAccountType.LIABILITY);
        createGL(GLConstants.IGST_PAYABLE, "IGST Payable", GLAccountType.LIABILITY);
        createGL(GLConstants.FEE_INCOME, "Fee Income", GLAccountType.INCOME);
        createGL(GLConstants.INTEREST_INCOME, "Interest Income", GLAccountType.INCOME);
        createGL(GLConstants.INTEREST_EXPENSE_DEPOSITS, "Interest Expense on Deposits", GLAccountType.EXPENSE);
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
     * Seeds a CASA Savings account in ACTIVE state (simulating post-maker-checker
     * activation). Bypasses the workflow for test determinism — this is the
     * Tier-1 CBS pattern: the service layer owns the maker-checker, but
     * integration tests seed terminal-state reference data and exercise the
     * financial operations.
     */
    private DepositAccount seedActiveSavingsAccount() {
        Branch branch = branchRepository.findById(branchId).orElseThrow();
        Customer customer = customerRepository.findById(customerId).orElseThrow();

        DepositAccount a = new DepositAccount();
        a.setTenantId(TENANT);
        a.setAccountNumber("SB-E2E01-000001");
        a.setCustomer(customer);
        a.setBranch(branch);
        a.setAccountType(DepositAccountType.SAVINGS);
        a.setProductCode("SAVINGS");
        a.setAccountStatus(DepositAccountStatus.ACTIVE);
        a.setCurrencyCode("INR");
        a.setLedgerBalance(BigDecimal.ZERO);
        a.setAvailableBalance(BigDecimal.ZERO);
        a.setHoldAmount(BigDecimal.ZERO);
        a.setUnclearedAmount(BigDecimal.ZERO);
        a.setOdLimit(BigDecimal.ZERO);
        a.setMinimumBalance(BigDecimal.ZERO);
        a.setInterestRate(new BigDecimal("4.0000"));
        a.setAccruedInterest(BigDecimal.ZERO);
        a.setYtdInterestCredited(BigDecimal.ZERO);
        a.setYtdTdsDeducted(BigDecimal.ZERO);
        a.setOpenedDate(BIZ_DATE);
        a.setLastTransactionDate(BIZ_DATE);
        a.setNomineeName("Spouse");
        a.setNomineeRelationship("SPOUSE");
        a.setCreatedBy("admin");
        a.setUpdatedBy("admin");
        return depositAccountRepository.save(a);
    }

    /**
     * Seeds a waivable flat fee charge definition for the given event type, mapped
     * to the Fee Income GL. Used for levy/waive/reverse assertions.
     */
    private void seedChargeDefinition(ChargeEventType eventType, BigDecimal flatFee) {
        ChargeDefinition def = new ChargeDefinition();
        def.setTenantId(TENANT);
        def.setEventType(eventType);
        def.setChargeName(eventType.name() + " fee");
        def.setChargeType("FLAT");
        def.setChargeAmount(flatFee);
        def.setChargePercentage(BigDecimal.ZERO);
        def.setMinCharge(BigDecimal.ZERO);
        def.setGstApplicable(true);
        def.setGlFeeIncome(GLConstants.FEE_INCOME);
        def.setActive(true);
        def.setWaivable(true);
        def.setCreatedBy("SYSTEM");
        chargeDefinitionRepository.save(def);
    }

    /**
     * Returns trial balance (Σ debitBalance, Σ creditBalance) across all postable
     * GL accounts for the tenant. In a healthy Tier-1 CBS these two must be equal
     * to the last paisa at every point in time.
     */
    private BigDecimal[] trialBalance() {
        List<GLMaster> gls = glMasterRepository.findAllPostableAccounts(TENANT);
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (GLMaster gl : gls) {
            dr = dr.add(gl.getDebitBalance());
            cr = cr.add(gl.getCreditBalance());
        }
        return new BigDecimal[] {dr, cr};
    }

    private void assertTrialBalanced(String checkpoint) {
        BigDecimal[] tb = trialBalance();
        assertEquals(
                0,
                tb[0].compareTo(tb[1]),
                "Trial balance FAILED at " + checkpoint + ": DR=" + tb[0] + " CR=" + tb[1]);
    }

    // ========================================================================
    // END-TO-END HAPPY PATH
    // ========================================================================

    @Test
    @Transactional
    @DisplayName("End-to-end CBS happy path: deposit → withdraw → charge levy → reverse — "
            + "trial balance balanced, ledger + audit chains intact, reconciliation balanced")
    void endToEndHappyPath() {
        // CBS: Force-clean any stale audit records from prior test methods in this class.
        // logEvent(REQUIRES_NEW) in the PENDING_APPROVAL path survives @Transactional rollback.
        // The @BeforeEach cleanup may miss records created by transactionEngineProducesAllArtifacts
        // if test execution order puts that test first.
        auditLogRepository.deleteAll(
                auditLogRepository.findAllByTenantIdOrderByIdAsc(
                        TENANT, org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)));

        // ----------------------------------------------------------------
        // 1. Bootstrap: tenant + branch + customer + calendar + batch + GL
        // ----------------------------------------------------------------
        setupReferenceData();
        assertTrialBalanced("bootstrap (empty GLs)");

        // ----------------------------------------------------------------
        // 2. CASA account seeded in ACTIVE state
        // ----------------------------------------------------------------
        DepositAccount account = seedActiveSavingsAccount();
        accountNumber = account.getAccountNumber();
        assertNotNull(accountNumber);
        assertEquals(DepositAccountStatus.ACTIVE, account.getAccountStatus());

        // ----------------------------------------------------------------
        // 3. Deposit (exercises full 20-step TransactionEngine chain)
        //    Expected GL flow:
        //      DR Bank Operations (1100) 100,000.00
        //      CR SB Deposits       (2010) 100,000.00
        // ----------------------------------------------------------------
        BigDecimal depositAmt = new BigDecimal("100000.00");
        DepositTransaction depTxn = depositService.deposit(
                accountNumber, depositAmt, BIZ_DATE, "Opening deposit", "E2E-DEP-001", "BRANCH");

        assertNotNull(depTxn);
        assertNotNull(depTxn.getVoucherNumber(), "Deposit must produce a voucher (Finacle VCH)");
        assertNotNull(depTxn.getJournalEntryId(), "Deposit must link to journal entry");
        assertNotNull(depTxn.getTransactionRef(), "Deposit must have a transaction reference");
        assertEquals("CREDIT", depTxn.getDebitCredit());
        assertEquals(0, depositAmt.compareTo(depTxn.getAmount()));

        // Subledger: account ledger balance updated
        DepositAccount afterDeposit = depositService.getAccount(accountNumber);
        assertEquals(0, depositAmt.compareTo(afterDeposit.getLedgerBalance()));

        // GL master: DR Bank Ops = 100,000; CR SB Deposits = 100,000
        GLMaster bankOps = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.BANK_OPERATIONS)
                .orElseThrow();
        GLMaster sbDeposits = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.SB_DEPOSITS)
                .orElseThrow();
        assertEquals(0, depositAmt.compareTo(bankOps.getDebitBalance()));
        assertEquals(0, depositAmt.compareTo(sbDeposits.getCreditBalance()));

        // TransactionEngine Step 19 (Journal↔Ledger link): the journal entry
        // posted by the deposit must have produced exactly 2 ledger rows.
        List<LedgerEntry> depLedger = ledgerEntryRepository
                .findByTenantIdAndJournalEntryIdOrderByLedgerSequenceAsc(TENANT, depTxn.getJournalEntryId());
        assertEquals(2, depLedger.size(), "Deposit must produce exactly 2 immutable ledger entries");

        assertTrialBalanced("after deposit");

        // Idempotency: replaying the same key returns the original transaction
        // without posting a second GL entry (TransactionEngine Step 17).
        DepositTransaction depReplay = depositService.deposit(
                accountNumber, depositAmt, BIZ_DATE, "Opening deposit", "E2E-DEP-001", "BRANCH");
        assertEquals(depTxn.getTransactionRef(), depReplay.getTransactionRef(),
                "Idempotency violation: same key must return same transaction");
        assertTrialBalanced("after idempotent deposit replay");

        // ----------------------------------------------------------------
        // 4. Withdrawal (symmetric debit leg, separate TransactionEngine run)
        //    Expected GL flow:
        //      DR SB Deposits       (2010) 30,000.00
        //      CR Bank Operations   (1100) 30,000.00
        // ----------------------------------------------------------------
        BigDecimal withdrawAmt = new BigDecimal("30000.00");
        DepositTransaction wdTxn = depositService.withdraw(
                accountNumber, withdrawAmt, BIZ_DATE, "Cash withdrawal", "E2E-WD-001", "BRANCH");

        assertNotNull(wdTxn.getVoucherNumber());
        assertEquals("DEBIT", wdTxn.getDebitCredit());

        DepositAccount afterWithdraw = depositService.getAccount(accountNumber);
        assertEquals(
                0,
                depositAmt.subtract(withdrawAmt).compareTo(afterWithdraw.getLedgerBalance()),
                "Ledger balance must reflect deposit - withdrawal");

        assertTrialBalanced("after withdrawal");

        // ----------------------------------------------------------------
        // 5. Charge levy via ChargeKernel — multi-leg journal with GST split
        //    (intra-state: branch=MH, customerState=MH → CGST+SGST).
        //    Expected GL flow for INR 100 fee @ 18% GST:
        //      DR SB Deposits       (2010) 118.00
        //      CR Fee Income        (4002) 100.00
        //      CR CGST Payable      (2200)   9.00
        //      CR SGST Payable      (2201)   9.00
        // ----------------------------------------------------------------
        seedChargeDefinition(ChargeEventType.CHEQUE_BOOK_ISSUANCE, new BigDecimal("100.00"));

        ChargeResult chg = chargeKernel.levyCharge(
                ChargeEventType.CHEQUE_BOOK_ISSUANCE,
                accountNumber,
                GLConstants.SB_DEPOSITS,
                BigDecimal.ZERO,
                null,
                "DEPOSIT",
                "E2E-CHG-SRC-001",
                "E2E01",
                "MH");

        assertNotNull(chg, "Charge levy must produce a result when definition matches");
        assertEquals(0, new BigDecimal("100.00").compareTo(chg.baseFee()));
        assertEquals(0, new BigDecimal("9.00").compareTo(chg.cgstAmount()));
        assertEquals(0, new BigDecimal("9.00").compareTo(chg.sgstAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(chg.igstAmount()),
                "Intra-state levy must NOT post IGST (GST Act §12)");
        assertEquals(0, new BigDecimal("118.00").compareTo(chg.totalDebit()));

        // Verify ledger + GL postings for the levy
        GLMaster feeIncome = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.FEE_INCOME)
                .orElseThrow();
        GLMaster cgst = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.CGST_PAYABLE)
                .orElseThrow();
        GLMaster sgst = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.SGST_PAYABLE)
                .orElseThrow();
        assertEquals(0, new BigDecimal("100.00").compareTo(feeIncome.getCreditBalance()));
        assertEquals(0, new BigDecimal("9.00").compareTo(cgst.getCreditBalance()));
        assertEquals(0, new BigDecimal("9.00").compareTo(sgst.getCreditBalance()));

        assertTrialBalanced("after charge levy");

        // ----------------------------------------------------------------
        // 6. Charge reversal via ChargeKernel — symmetric contra journal.
        //    Per RBI FPC 2023 §5.7: reversal MUST mirror the original legs so
        //    GL trial balance remains clean and every income/liability leg
        //    nets to zero.
        // ----------------------------------------------------------------
        chargeKernel.reverseCharge(chg.chargeTransactionId(), "Operational rollback — test");

        // GL neutrality: after reversal, charge-related GLs net to zero
        GLMaster feeIncomeAfter = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.FEE_INCOME)
                .orElseThrow();
        GLMaster cgstAfter = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.CGST_PAYABLE)
                .orElseThrow();
        GLMaster sgstAfter = glMasterRepository
                .findByTenantIdAndGlCode(TENANT, GLConstants.SGST_PAYABLE)
                .orElseThrow();
        assertEquals(
                0,
                feeIncomeAfter.getCreditBalance().subtract(feeIncomeAfter.getDebitBalance()).compareTo(BigDecimal.ZERO),
                "Fee Income net must be zero after charge reversal (RBI FPC 2023 §5.7)");
        assertEquals(
                0,
                cgstAfter.getCreditBalance().subtract(cgstAfter.getDebitBalance()).compareTo(BigDecimal.ZERO),
                "CGST net must be zero after charge reversal");
        assertEquals(
                0,
                sgstAfter.getCreditBalance().subtract(sgstAfter.getDebitBalance()).compareTo(BigDecimal.ZERO),
                "SGST net must be zero after charge reversal");

        assertTrialBalanced("after charge reversal");

        // ----------------------------------------------------------------
        // 7. Ledger hash chain — full O(N) walk from GENESIS
        // ----------------------------------------------------------------
        assertTrue(ledgerService.verifyChainIntegrity(),
                "Ledger hash chain must be intact after end-to-end happy path");

        // Sanity: the ledger must contain at least the rows from deposit (2) +
        // withdrawal (2) + levy (≥3 — customer DR + fee CR + CGST CR + SGST CR) +
        // reversal (symmetric legs).
        long ledgerCount = ledgerEntryRepository.findAllByTenantIdOrderByLedgerSequenceAsc(
                        TENANT, org.springframework.data.domain.PageRequest.of(0, 100))
                .size();
        assertTrue(ledgerCount >= 11,
                "Ledger must contain deposit + withdraw + levy + reverse legs, found " + ledgerCount);

        // ----------------------------------------------------------------
        // 8. Audit chain — full O(N) walk from GENESIS
        // ----------------------------------------------------------------
        assertTrue(auditService.verifyChainIntegrity(TENANT),
                "Audit hash chain must be intact after end-to-end happy path");

        // Audit entries must have been emitted for the DAY_OPEN (skipped in this
        // seeded-setup path), account mutations, and charge transactions.
        long auditCount = auditLogRepository.countByTenantId(TENANT);
        assertTrue(auditCount > 0, "Audit trail must contain events from e2e flow");

        // ----------------------------------------------------------------
        // 9. Reconciliation: GL ↔ Ledger drift must be zero
        // ----------------------------------------------------------------
        ReconciliationService.ReconciliationResult recon = reconciliationService.reconcileLedgerVsGL();
        assertTrue(recon.isBalanced(),
                "GL ↔ Ledger reconciliation must be balanced after e2e flow. "
                        + "Discrepancies: " + recon.discrepancies());

        // ----------------------------------------------------------------
        // 10. Final trial balance: DR == CR
        // ----------------------------------------------------------------
        assertTrialBalanced("final (end of e2e happy path)");
    }

    /**
     * Asserts the 20-step TransactionEngine chain produces all required
     * artifacts on a single transaction: journal entry, ledger rows linked to
     * it (Step 19 journal↔ledger link), a voucher number (Finacle VCH),
     * a transaction reference, and an immutable DepositTransaction row with
     * journal + voucher back-pointers (Step 20 transaction↔journal link).
     */
    @Test
    @Transactional
    @DisplayName("TransactionEngine 20-step chain: deposit produces journal, ledger links, "
            + "voucher, transaction ref, and symmetric GL postings")
    void transactionEngineProducesAllArtifacts() {
        setupReferenceData();
        DepositAccount account = seedActiveSavingsAccount();

        BigDecimal amt = new BigDecimal("50000.00");
        DepositTransaction txn = depositService.deposit(
                account.getAccountNumber(), amt, BIZ_DATE, "Artifact check", "E2E-ART-001", "BRANCH");

        // Step 20: transaction↔journal link
        assertNotNull(txn.getJournalEntryId(), "Transaction must link to journal entry");
        assertNotNull(txn.getVoucherNumber(), "Transaction must have voucher number");
        assertNotNull(txn.getTransactionRef(), "Transaction must have transaction reference");

        // Step 19: journal↔ledger link — the journal entry must exist and have
        // exactly 2 ledger rows (one DR, one CR) all sharing the same journalEntryId.
        JournalEntry journal = journalEntryRepository.findById(txn.getJournalEntryId()).orElseThrow();
        assertEquals(TENANT, journal.getTenantId());
        assertEquals(0, amt.compareTo(journal.getTotalDebit()));
        assertEquals(0, amt.compareTo(journal.getTotalCredit()));

        List<LedgerEntry> ledgerRows = ledgerEntryRepository
                .findByTenantIdAndJournalEntryIdOrderByLedgerSequenceAsc(TENANT, txn.getJournalEntryId());
        assertEquals(2, ledgerRows.size(), "Journal must map to exactly 2 ledger rows");

        BigDecimal ledgerDr = ledgerRows.stream()
                .map(LedgerEntry::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerCr = ledgerRows.stream()
                .map(LedgerEntry::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, ledgerDr.compareTo(ledgerCr),
                "Ledger DR total must equal CR total for a single journal");

        // Hash chain still intact
        assertTrue(ledgerService.verifyChainIntegrity());
    }
}

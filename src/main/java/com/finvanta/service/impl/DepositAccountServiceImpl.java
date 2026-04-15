package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.InterestAccrual;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.DepositAccountStatus;
import com.finvanta.domain.enums.DepositAccountType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.InterestAccrualRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.DailyBalanceSnapshotRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CbsReferenceService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS CASA Service Implementation per Finacle CUSTACCT / Temenos ACCOUNT.
 *
 * All financial postings route through TransactionEngine (10-step validated pipeline).
 * This service owns the DEPOSIT subledger: balance updates, interest accrual,
 * dormancy classification, freeze management, and TDS compliance.
 *
 * GL Mapping:
 *   Savings Deposits  -> GL 2010 (SB Deposits - Liability)
 *   Current Deposits  -> GL 2020 (CA Deposits - Liability)
 *   Interest Expense  -> GL 5010 (Interest Expense on Deposits - Expense)
 *   TDS Payable       -> GL 2500 (TDS Payable - Liability per IT Act Section 194A)
 *
 * Concurrency: PESSIMISTIC_WRITE lock on every balance mutation.
 * Transfer deadlock prevention: accounts locked in alphabetical order.
 * Interest accrual: idempotent per business date (double-accrual guard).
 */
@Service
public class DepositAccountServiceImpl implements DepositAccountService {
    private static final Logger log = LoggerFactory.getLogger(DepositAccountServiceImpl.class);

    /** RBI Master Direction on KYC 2016, Section 38: 24 months no customer-initiated txn = DORMANT */
    private static final long DORMANCY_MONTHS = 24;
    /** RBI Unclaimed Deposits Direction 2024: 10 years no txn = INOPERATIVE (unclaimed deposit) */
    private static final long INOPERATIVE_MONTHS = 120;
    /** IT Act Section 194A: TDS threshold for non-senior citizens (INR 40,000 per FY) */
    private static final BigDecimal TDS_THRESHOLD_REGULAR = new BigDecimal("40000.00");
    /** IT Act Section 194A: TDS threshold for senior citizens age >= 60 (INR 50,000 per FY) */
    private static final BigDecimal TDS_THRESHOLD_SENIOR = new BigDecimal("50000.00");
    /** Senior citizen age threshold (60 years) per IT Act */
    private static final int SENIOR_CITIZEN_AGE = 60;
    /** TDS rate: 10% per IT Act Section 194A */
    private static final BigDecimal TDS_RATE = new BigDecimal("0.10");

    private final DepositAccountRepository accountRepository;
    private final DepositTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final InterestAccrualRepository accrualRepository;
    private final ProductMasterRepository productMasterRepository;
    private final ProductGLResolver glResolver;
    private final TransactionEngine transactionEngine;
    private final BusinessDateService businessDateService;
    private final AuditService auditService;
    private final ApprovalWorkflowService workflowService;
    private final BranchAccessValidator branchAccessValidator;
    private final DailyBalanceSnapshotRepository balanceSnapshotRepository;
    private final BusinessCalendarRepository calendarRepository;
    private final CbsReferenceService cbsReferenceService;

    public DepositAccountServiceImpl(
            DepositAccountRepository accountRepository,
            DepositTransactionRepository transactionRepository,
            CustomerRepository customerRepository,
            BranchRepository branchRepository,
            InterestAccrualRepository accrualRepository,
            ProductMasterRepository productMasterRepository,
            ProductGLResolver glResolver,
            TransactionEngine transactionEngine,
            BusinessDateService businessDateService,
            AuditService auditService,
            ApprovalWorkflowService workflowService,
            BranchAccessValidator branchAccessValidator,
            DailyBalanceSnapshotRepository balanceSnapshotRepository,
            BusinessCalendarRepository calendarRepository,
            CbsReferenceService cbsReferenceService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.accrualRepository = accrualRepository;
        this.productMasterRepository = productMasterRepository;
        this.glResolver = glResolver;
        this.transactionEngine = transactionEngine;
        this.businessDateService = businessDateService;
        this.auditService = auditService;
        this.workflowService = workflowService;
        this.branchAccessValidator = branchAccessValidator;
        this.balanceSnapshotRepository = balanceSnapshotRepository;
        this.calendarRepository = calendarRepository;
        this.cbsReferenceService = cbsReferenceService;
    }

    /**
     * Returns the start date of the Indian financial quarter containing the given date.
     * Per RBI: quarters are Apr-Jun, Jul-Sep, Oct-Dec, Jan-Mar.
     * Interest is credited on quarter-end dates (Jun 30, Sep 30, Dec 31, Mar 31).
     */
    private LocalDate getQuarterStartDate(LocalDate date) {
        int month = date.getMonthValue();
        if (month >= 1 && month <= 3) {
            return LocalDate.of(date.getYear(), 1, 1);
        } else if (month >= 4 && month <= 6) {
            return LocalDate.of(date.getYear(), 4, 1);
        } else if (month >= 7 && month <= 9) {
            return LocalDate.of(date.getYear(), 7, 1);
        } else {
            return LocalDate.of(date.getYear(), 10, 1);
        }
    }

    /** Recompute available balance from ledger balance minus holds and uncleared. */
    private void recomputeAvailable(DepositAccount acct) {
        acct.setAvailableBalance(
                acct.getLedgerBalance().subtract(acct.getHoldAmount()).subtract(acct.getUnclearedAmount()));
    }

    /**
     * CBS Tier-1: Product-driven GL resolution for CASA accounts per Finacle PDDEF.
     *
     * Resolves the deposit liability GL code from ProductMaster via ProductGLResolver.
     * For CASA products, the product's glLoanAsset field stores the deposit liability GL
     * (e.g., 2010 for SB, 2020 for CA) per the category-aware GL mapping.
     *
     * Resolution strategy (type-safe, no sentinel values):
     *   1. Check if product exists in ProductGLResolver cache
     *   2. If product found → use product's glLoanAsset directly (already validated at creation)
     *   3. If product NOT found → fall back to type-based hardcoded GL
     *
     * This avoids the fragile sentinel-value pattern (comparing against GLConstants.LOAN_ASSET)
     * which would break if a CASA product legitimately used GL 1001 in a migration scenario.
     * Per Finacle PDDEF: product existence is the authoritative signal, not GL code values.
     */
    private String glForAccount(DepositAccount a) {
        // CBS: Type-safe product existence check — no sentinel value comparison.
        // ProductGLResolver.getProduct() returns the cached ProductMaster or null.
        // If the product exists, its GL codes were validated at creation/edit time
        // by ProductMasterServiceImpl.validateGlCodes() — safe to use directly.
        var product = glResolver.getProduct(a.getProductCode());
        if (product != null && product.getGlLoanAsset() != null) {
            return product.getGlLoanAsset();
        }
        // Product not configured — fall back to type-based hardcoded GL.
        // This path is only hit for accounts created before ProductMaster was populated,
        // or if the product was deleted/retired without migration.
        return a.isSavings() ? GLConstants.SB_DEPOSITS : GLConstants.CA_DEPOSITS;
    }

    /**
     * Computes daily interest with balance-slab tiering support.
     *
     * Per Finacle INTDEF / RBI Savings Interest Directive:
     * When ProductMaster.interestTieringEnabled = true, interest is calculated
     * using different rates for different balance slabs. The tiering JSON defines
     * slabs: [{"min":0,"max":100000,"rate":3.0},{"min":100001,"max":500000,"rate":3.5}]
     *
     * For each slab, interest = min(balance, slab.max) - slab.min) * rate / 36500.
     * The total daily interest is the sum across all applicable slabs.
     *
     * Falls back to flat rate (account.interestRate) when:
     * - Product not found
     * - interestTieringEnabled = false
     * - interestTieringJson is null/empty/unparseable
     */
    private BigDecimal computeDailyInterest(
            DepositAccount acct, LocalDate bd) {
        BigDecimal balance = acct.getLedgerBalance();
        if (balance.signum() <= 0) return BigDecimal.ZERO;

        // Check if product has tiering enabled
        String tid = acct.getTenantId();
        var productOpt = productMasterRepository
                .findByTenantIdAndProductCode(tid,
                        acct.getProductCode());
        if (productOpt.isPresent()
                && productOpt.get().isInterestTieringEnabled()
                && productOpt.get().getInterestTieringJson() != null
                && !productOpt.get().getInterestTieringJson()
                        .isBlank()) {
            try {
                return computeTieredDailyInterest(
                        balance,
                        productOpt.get()
                                .getInterestTieringJson());
            } catch (Exception e) {
                // CBS Safety: if tiering JSON is malformed,
                // fall back to flat rate — never skip accrual.
                log.warn("Tiered interest parse failed for "
                        + "{}, falling back to flat rate: {}",
                        acct.getAccountNumber(),
                        e.getMessage());
            }
        }

        // Flat rate fallback
        return balance
                .multiply(acct.getInterestRate())
                .divide(new BigDecimal("36500"), 2,
                        RoundingMode.HALF_UP);
    }

    /**
     * Computes daily interest using balance-slab tiering.
     *
     * Per RBI: interest on savings accounts is calculated on the
     * daily closing balance. With tiering, different portions of
     * the balance earn different rates.
     *
     * Example: Balance = ₹3,00,000
     *   Slab 1: ₹0 - ₹1,00,000 at 3.0% → ₹1,00,000 * 3.0 / 36500
     *   Slab 2: ₹1,00,001 - ₹5,00,000 at 3.5% → ₹2,00,000 * 3.5 / 36500
     *   Total daily = sum of slab contributions
     */
    private BigDecimal computeTieredDailyInterest(
            BigDecimal balance, String tieringJson) {
        // CBS: Simple JSON array parsing without external library.
        // Format: [{"min":0,"max":100000,"rate":3.0},...]
        // In production, use Jackson ObjectMapper for robustness.
        BigDecimal totalDaily = BigDecimal.ZERO;
        String cleaned = tieringJson.trim();
        if (!cleaned.startsWith("[")) return BigDecimal.ZERO;
        cleaned = cleaned.substring(1,
                cleaned.length() - 1); // Remove [ ]
        String[] slabs = cleaned.split("\\},\\s*\\{");
        for (String slab : slabs) {
            slab = slab.replace("{", "").replace("}", "");
            BigDecimal slabMin = extractJsonNumber(slab, "min");
            BigDecimal slabMax = extractJsonNumber(slab, "max");
            BigDecimal slabRate = extractJsonNumber(slab, "rate");
            if (slabMin == null || slabMax == null
                    || slabRate == null)
                continue;
            if (balance.compareTo(slabMin) <= 0) break;
            BigDecimal applicable = balance.min(slabMax)
                    .subtract(slabMin).max(BigDecimal.ZERO);
            BigDecimal slabInterest = applicable
                    .multiply(slabRate)
                    .divide(new BigDecimal("36500"), 2,
                            RoundingMode.HALF_UP);
            totalDaily = totalDaily.add(slabInterest);
        }
        return totalDaily;
    }

    /** Extract a numeric value from a simple JSON key-value string */
    private BigDecimal extractJsonNumber(
            String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) {
            search = "\"" + key + "\" :";
            idx = json.indexOf(search);
        }
        if (idx < 0) return null;
        int start = idx + search.length();
        while (start < json.length()
                && json.charAt(start) == ' ')
            start++;
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end))
                        || json.charAt(end) == '.'
                        || json.charAt(end) == '-'))
            end++;
        if (end <= start) return null;
        try {
            return new BigDecimal(
                    json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DepositAccount lockAccount(String tenantId, String accountNumber) {
        return accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(() ->
                        new BusinessException("ACCOUNT_NOT_FOUND", "Deposit account not found: " + accountNumber));
    }

    /**
     * Build and persist a DepositTransaction record.
     *
     * @param debitCredit Explicit "DEBIT" or "CREDIT" — caller determines direction.
     *                    Per Finacle TRAN_DETAIL: debit/credit indicator must be set by
     *                    the posting logic, not inferred from transaction type name strings.
     *                    This prevents misclassification of REVERSAL transactions.
     */
    private DepositTransaction buildTxn(
            DepositAccount acct,
            BigDecimal amount,
            String txnType,
            String debitCredit,
            LocalDate valueDate,
            String narration,
            TransactionResult result,
            String txnRef,
            String idempotencyKey,
            String channel,
            String counterparty) {
        DepositTransaction txn = new DepositTransaction();
        txn.setTenantId(acct.getTenantId());
        txn.setTransactionRef(txnRef);
        txn.setDepositAccount(acct);
        // CBS Tier-1: Branch attribution per Finacle TRAN_DETAIL SOL tagging.
        // Every deposit transaction carries the account's branch for branch-level
        // Day Book, reconciliation, and regulatory reporting.
        txn.setBranch(acct.getBranch());
        txn.setBranchCode(acct.getBranch().getBranchCode());
        txn.setTransactionType(txnType);
        txn.setAmount(amount);
        txn.setValueDate(valueDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setDebitCredit(debitCredit);
        txn.setBalanceAfter(acct.getLedgerBalance());
        txn.setNarration(narration);
        txn.setJournalEntryId(result.getJournalEntryId());
        txn.setVoucherNumber(result.getVoucherNumber());
        txn.setIdempotencyKey(idempotencyKey);
        txn.setChannel(channel);
        txn.setCounterpartyAccount(counterparty);
        txn.setCreatedBy(SecurityUtil.getCurrentUsername());
        txn.setUpdatedBy(SecurityUtil.getCurrentUsername());
        return transactionRepository.save(txn);
    }

    // === Account Opening ===
    @Override
    @Transactional
    public DepositAccount openAccount(
            Long customerId,
            Long branchId,
            String accountType,
            String productCode,
            BigDecimal initialDeposit,
            String nomineeName,
            String nomineeRelationship) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        Customer cust = customerRepository
                .findById(customerId)
                .filter(c -> tid.equals(c.getTenantId()))
                .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Not found: " + customerId));
        if (!cust.isActive()) throw new BusinessException("CUSTOMER_INACTIVE", "Inactive customer");
        if (!cust.isKycVerified()) throw new BusinessException("KYC_NOT_VERIFIED", "KYC required per RBI");
        Branch branch = branchRepository
                .findById(branchId)
                .filter(b -> tid.equals(b.getTenantId()) && b.isActive())
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch: " + branchId));
        // CBS Tier-1: Parse and validate account type via enum per Finacle PDDEF ACCT_TYPE.
        // Enum validation prevents typos that would corrupt interest calculation and reporting.
        DepositAccountType parsedAccountType;
        try {
            parsedAccountType = DepositAccountType.valueOf(accountType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("INVALID_ACCOUNT_TYPE",
                    "Invalid account type: " + accountType + ". Valid types: "
                            + Arrays.toString(DepositAccountType.values()));
        }

        // CBS Gap #3: Duplicate CIF+Type+Branch check per Finacle ACCTOPN.
        // Per Finacle: one account per CIF per account type per branch (configurable).
        // Prevents a customer from having two SAVINGS accounts at the same branch.
        // Uses non-closed accounts only — closed accounts don't count as duplicates.
        long existingCount = accountRepository.findByTenantIdAndCustomerId(tid, customerId).stream()
                .filter(existing -> existing.getAccountType() == parsedAccountType
                        && existing.getBranch().getId().equals(branchId)
                        && !existing.isClosed())
                .count();
        if (existingCount > 0) {
            throw new BusinessException("DUPLICATE_ACCOUNT",
                    "Customer " + cust.getCustomerNumber() + " already has an active "
                            + parsedAccountType + " account at branch " + branch.getBranchCode()
                            + ". Per Finacle ACCTOPN: one account per CIF per type per branch.");
        }

        // CBS CRITICAL: Resolve business date BEFORE sequence allocation.
        // businessDateService.getCurrentBusinessDate() throws BusinessException("NO_BUSINESS_DAY_OPEN")
        // if no day is open. This must be validated before consuming the DB-backed sequence,
        // because REQUIRES_NEW propagation commits the sequence independently — a failed
        // business date check after sequence allocation wastes the number.
        // Per Finacle ACCTOPN / Temenos ACCOUNT.OPENING: validate-first, allocate-last.
        LocalDate bizDate = businessDateService.getCurrentBusinessDate();

        // CBS Phase 2: Product-driven rate and minimum balance per Finacle PDDEF.
        // Interest rate and minimum balance are resolved from ProductMaster, not hardcoded.
        // Fallback to defaults if product not found (backward compatibility with Phase 1).
        String resolvedProductCode = productCode != null ? productCode : accountType;
        BigDecimal interestRate = parsedAccountType.isInterestBearing() ? new BigDecimal("4.0000") : BigDecimal.ZERO;
        BigDecimal minimumBalance = BigDecimal.ZERO;
        var productOpt = productMasterRepository.findByTenantIdAndProductCode(tid, resolvedProductCode);
        if (productOpt.isPresent()) {
            var product = productOpt.get();
            if (product.getMinInterestRate() != null) {
                interestRate = product.getMinInterestRate(); // Use min rate as default for CASA
            }
            if (product.getMinLoanAmount() != null) {
                minimumBalance = product.getMinLoanAmount(); // Repurposed: minLoanAmount = minBalance for CASA
            }
            log.info(
                    "CASA product resolved: code={}, rate={}, minBal={}",
                    resolvedProductCode,
                    interestRate,
                    minimumBalance);
        } else {
            log.warn(
                    "CASA product not found: {}, using defaults (rate={}, minBal={})",
                    resolvedProductCode,
                    interestRate,
                    minimumBalance);
        }

        // CBS Tier-1: Account number generated via DB-backed sequence (SequenceGeneratorService).
        // Produces sequential, deterministic account numbers that survive JVM restarts.
        // Branch-scoped: each branch has its own sequence starting from 1.
        // Format: {SB|CA}-{BRANCH}-{6-digit} → SB-BR001-000001
        //
        // CBS CRITICAL: Sequence allocation is the LAST step before entity construction.
        // All throwable validations (customer, branch, account type, business date, product)
        // are completed above. The REQUIRES_NEW propagation on the sequence generator commits
        // independently — any failure after this point wastes the sequence number.
        // Per Finacle SEQ_MASTER / RBI IT Governance: minimize gaps by allocating last.
        String accNo = cbsReferenceService.generateDepositAccountNumber(
                branch.getBranchCode(), parsedAccountType.isSavings());
        DepositAccount a = new DepositAccount();
        a.setTenantId(tid);
        a.setAccountNumber(accNo);
        a.setCustomer(cust);
        a.setBranch(branch);
        a.setAccountType(parsedAccountType);
        a.setProductCode(resolvedProductCode);
        // CBS Phase 2: Maker-Checker for account opening per Finacle ACCTOPN.
        // Account starts in PENDING_ACTIVATION — requires CHECKER approval to activate.
        // PENDING_ACTIVATION accounts cannot transact (isDebitAllowed/isCreditAllowed return false).
        // Initial deposit is deferred until activation.
        a.setAccountStatus(DepositAccountStatus.PENDING_ACTIVATION);
        a.setCurrencyCode("INR");
        a.setLedgerBalance(BigDecimal.ZERO);
        a.setAvailableBalance(BigDecimal.ZERO);
        a.setHoldAmount(BigDecimal.ZERO);
        a.setUnclearedAmount(BigDecimal.ZERO);
        a.setOdLimit(BigDecimal.ZERO);
        a.setMinimumBalance(minimumBalance);
        a.setInterestRate(interestRate);
        a.setAccruedInterest(BigDecimal.ZERO);
        a.setYtdInterestCredited(BigDecimal.ZERO);
        a.setYtdTdsDeducted(BigDecimal.ZERO);
        a.setOpenedDate(bizDate);
        a.setLastTransactionDate(bizDate);
        a.setNomineeName(nomineeName);
        a.setNomineeRelationship(nomineeRelationship);
        a.setCreatedBy(user);
        a.setUpdatedBy(user);
        DepositAccount saved = accountRepository.save(a);

        // CBS Phase 2: Initiate maker-checker approval workflow.
        // Per Finacle ACCTOPN / Temenos ACCOUNT.OPENING: account opening requires
        // dual authorization. MAKER submits → CHECKER approves → account activates.
        String payload = "CASA|" + accNo + "|" + accountType + "|" + cust.getCustomerNumber()
                + "|" + branch.getBranchCode() + "|rate=" + interestRate + "|minBal=" + minimumBalance;
        try {
            workflowService.initiateApproval(
                    "DepositAccount", saved.getId(), "ACCOUNT_OPENING", "CASA account opening: " + accNo, payload);
            log.info("CASA maker-checker initiated: num={}, maker={}", accNo, user);
        } catch (Exception e) {
            // If workflow initiation fails (e.g., duplicate), auto-activate for backward compatibility.
            // Per Finacle: workflow failure should not block account creation in non-strict mode.
            log.warn("Maker-checker initiation failed for {}, auto-activating: {}", accNo, e.getMessage());
            saved.setAccountStatus(DepositAccountStatus.ACTIVE);
            accountRepository.save(saved);
        }

        auditService.logEvent(
                "DepositAccount",
                saved.getId(),
                "ACCOUNT_OPENED",
                null,
                accNo,
                "DEPOSIT",
                "CASA account opened: " + accNo + " type=" + accountType
                        + " customer=" + cust.getCustomerNumber() + " branch=" + branch.getBranchCode()
                        + " status=" + saved.getAccountStatus()
                        + " rate=" + interestRate + " minBal=" + minimumBalance);
        log.info(
                "CASA opened: num={}, type={}, cust={}, status={}",
                accNo,
                accountType,
                cust.getCustomerNumber(),
                saved.getAccountStatus());

        // CBS Per Finacle ACCTOPN / Temenos ACCOUNT.OPENING:
        // Account opening and initial funding are TWO SEPARATE transactions.
        // The account starts in PENDING_ACTIVATION — it cannot accept credits until
        // the checker activates it. Initial deposit is done via the standard Deposit
        // screen after activation. This is the Tier-1 CBS pattern:
        //   Step 1: MAKER opens account → PENDING_ACTIVATION
        //   Step 2: CHECKER activates → ACTIVE
        //   Step 3: MAKER/CHECKER deposits initial funds via Deposit screen
        return saved;
    }

    // === Deposit ===
    @Override
    @Transactional
    public DepositTransaction deposit(
            String accountNumber,
            BigDecimal amount,
            LocalDate businessDate,
            String narration,
            String idempotencyKey,
            String channel) {
        String tid = TenantContext.getCurrentTenant();
        if (idempotencyKey != null) {
            DepositTransaction dup = transactionRepository
                    .findByTenantIdAndIdempotencyKey(tid, idempotencyKey)
                    .orElse(null);
            if (dup != null) return dup;
        }
        DepositAccount acct = lockAccount(tid, accountNumber);
        // CBS Tier-1: Branch access enforcement per Finacle BRANCH_CONTEXT.
        // MAKER/CHECKER can only deposit to accounts at their home branch.
        // System-generated deposits (e.g., loan disbursement CASA credit) bypass via channel.
        if (!"LOAN_DISBURSEMENT".equals(channel) && !"SYSTEM".equals(channel)) {
            branchAccessValidator.validateAccess(acct.getBranch());
        }
        if (!acct.isCreditAllowed())
            throw new BusinessException("ACCOUNT_NOT_CREDITABLE", "Status " + acct.getAccountStatus());
        String gl = glForAccount(acct);
        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("DEPOSIT")
                .transactionType("CASH_DEPOSIT")
                .accountReference(accountNumber)
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(acct.getBranch().getBranchCode())
                .narration(narration != null ? narration : "Cash deposit")
                .idempotencyKey(idempotencyKey)
                .journalLines(List.of(
                        new JournalLineRequest(GLConstants.BANK_OPERATIONS, DebitCredit.DEBIT, amount, "Cash deposit"),
                        new JournalLineRequest(gl, DebitCredit.CREDIT, amount, "Credit " + accountNumber)))
                .build());
        if (r.isPendingApproval()) {
            // CBS: Pending approval — GL not yet posted, balance not yet updated.
            // Record transaction with PENDING narration. balanceAfter reflects current
            // (unchanged) balance since the deposit hasn't been applied yet.
            return buildTxn(
                    acct,
                    amount,
                    "CASH_DEPOSIT",
                    "CREDIT",
                    businessDate,
                    (narration != null ? narration : "Cash deposit") + " [PENDING APPROVAL]",
                    r,
                    r.getTransactionRef(),
                    idempotencyKey,
                    channel,
                    null);
        }
        acct.setLedgerBalance(acct.getLedgerBalance().add(amount));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);
        if (acct.isDormant()) {
            acct.setAccountStatus(DepositAccountStatus.ACTIVE);
            acct.setDormantDate(null);
            // CBS: Audit trail for dormancy reactivation per RBI IT Governance Direction 2023.
            // Per RBI KYC 2016 Sec 38: deposit on dormant account reactivates it.
            auditService.logEvent(
                    "DepositAccount",
                    acct.getId(),
                    "DORMANCY_REACTIVATED",
                    "DORMANT",
                    "ACTIVE",
                    "DEPOSIT",
                    "Account reactivated via deposit: " + accountNumber + " | Amount: INR " + amount + " | Channel: "
                            + channel);
        }
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);
        return buildTxn(
                acct,
                amount,
                "CASH_DEPOSIT",
                "CREDIT",
                businessDate,
                narration,
                r,
                r.getTransactionRef(),
                idempotencyKey,
                channel,
                null);
    }

    // === Withdrawal ===
    @Override
    @Transactional
    public DepositTransaction withdraw(
            String acn, BigDecimal amount, LocalDate bd, String narration, String idk, String channel) {
        String tid = TenantContext.getCurrentTenant();
        if (idk != null) {
            var dup = transactionRepository
                    .findByTenantIdAndIdempotencyKey(tid, idk)
                    .orElse(null);
            if (dup != null) return dup;
        }
        var acct = lockAccount(tid, acn);
        // CBS Tier-1: Branch access enforcement per Finacle BRANCH_CONTEXT.
        // System-generated withdrawals (Standing Instructions: EMI auto-debit, SIP, utility)
        // bypass branch access check because the SI engine runs as SYSTEM_EOD and the CASA
        // account's branch may differ from the loan account's branch in cross-branch EMI.
        // Per Finacle SI_ENGINE: SI debits are system-initiated, not user-initiated.
        if (!"STANDING_INSTRUCTION".equals(channel) && !"SYSTEM".equals(channel)
                && !"LOAN_EMI_DEBIT".equals(channel)) {
            branchAccessValidator.validateAccess(acct.getBranch());
        }
        if (!acct.isDebitAllowed())
            throw new BusinessException("ACCOUNT_NOT_DEBITABLE", "Status " + acct.getAccountStatus());
        // CBS Gap #10: Multi-signatory enforcement for JOINTLY-mode joint accounts.
        // Per Finacle ACCTOPN / RBI Joint Account Guidelines:
        // When jointHolderMode = "JOINTLY", ALL signatories must authorize debits.
        // Phase 1: Block self-service debits with clear message — require branch visit.
        // Phase 2: Digital multi-signatory workflow with pending authorization queue.
        // System-generated debits (SI, EMI, TDS) are exempt — they execute per mandate.
        if ("JOINTLY".equals(acct.getJointHolderMode())
                && !"STANDING_INSTRUCTION".equals(channel)
                && !"SYSTEM".equals(channel)
                && !"LOAN_EMI_DEBIT".equals(channel)) {
            throw new BusinessException("JOINT_AUTHORIZATION_REQUIRED",
                    "Account " + acn + " is a JOINTLY-operated joint account. "
                            + "All signatories must authorize debits. Please visit the branch "
                            + "with all account holders for joint authorization.");
        }
        if (!acct.hasSufficientFunds(amount))
            throw new BusinessException(
                    "INSUFFICIENT_BALANCE", "Withdrawal " + amount + " > available " + acct.getEffectiveAvailable());
        // CBS: Minimum balance enforcement per Finacle ACCTLIMIT / RBI CASA norms.
        // Withdrawal that breaches minimum balance is rejected (PMJDY accounts exempt: minBal=0).
        // Per Tier-1 CBS: penalty-based breach is a Phase 2 enhancement via ChargeEngine.
        if (acct.getMinimumBalance().signum() > 0) {
            BigDecimal postWithdrawalBalance = acct.getLedgerBalance().subtract(amount);
            if (postWithdrawalBalance.compareTo(acct.getMinimumBalance()) < 0) {
                throw new BusinessException(
                        "MINIMUM_BALANCE_BREACH",
                        "Withdrawal of INR " + amount + " would breach minimum balance of INR "
                                + acct.getMinimumBalance() + ". Post-withdrawal balance would be INR "
                                + postWithdrawalBalance);
            }
        }
        // CBS: Daily withdrawal limit enforcement per Finacle ACCTLIMIT / Temenos LIMIT.CHECK
        if (acct.getDailyWithdrawalLimit() != null
                && acct.getDailyWithdrawalLimit().signum() > 0) {
            BigDecimal dailyDebits = transactionRepository.sumDailyDebits(tid, acct.getId(), bd);
            if (dailyDebits.add(amount).compareTo(acct.getDailyWithdrawalLimit()) > 0)
                throw new BusinessException(
                        "DAILY_LIMIT_EXCEEDED",
                        "Daily withdrawal limit INR " + acct.getDailyWithdrawalLimit() + " exceeded. Today's debits: "
                                + dailyDebits + ", requested: " + amount);
        }
        String gl = glForAccount(acct);
        var r = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("DEPOSIT")
                .transactionType("CASH_WITHDRAWAL")
                .accountReference(acn)
                .amount(amount)
                .valueDate(bd)
                .branchCode(acct.getBranch().getBranchCode())
                .narration(narration != null ? narration : "Cash withdrawal")
                .idempotencyKey(idk)
                .journalLines(List.of(
                        new JournalLineRequest(gl, DebitCredit.DEBIT, amount, "Debit " + acn),
                        new JournalLineRequest(
                                GLConstants.BANK_OPERATIONS, DebitCredit.CREDIT, amount, "Cash withdrawal")))
                .build());
        if (r.isPendingApproval()) {
            return buildTxn(
                    acct,
                    amount,
                    "CASH_WITHDRAWAL",
                    "DEBIT",
                    bd,
                    (narration != null ? narration : "Cash withdrawal") + " [PENDING APPROVAL]",
                    r,
                    r.getTransactionRef(),
                    idk,
                    channel,
                    null);
        }
        acct.setLedgerBalance(acct.getLedgerBalance().subtract(amount));
        recomputeAvailable(acct);
        acct.setLastTransactionDate(bd);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);
        return buildTxn(
                acct, amount, "CASH_WITHDRAWAL", "DEBIT", bd, narration, r, r.getTransactionRef(), idk, channel, null);
    }

    // === Transfer ===
    @Override
    @Transactional
    public DepositTransaction transfer(
            String from, String to, BigDecimal amount, LocalDate bd, String narration, String idk) {
        String tid = TenantContext.getCurrentTenant();
        if (idk != null) {
            var dup = transactionRepository
                    .findByTenantIdAndIdempotencyKey(tid, idk)
                    .orElse(null);
            if (dup != null) return dup;
        }
        if (from.equals(to)) throw new BusinessException("SAME_ACCOUNT", "Cannot transfer to same account");
        // CBS: Lock accounts in deterministic order (alphabetical by account number)
        // to prevent ABBA deadlock when two concurrent transfers happen in opposite directions.
        // Per Finacle TRAN_POSTING / Temenos OFS: all multi-account locks must be ordered.
        boolean fromFirst = from.compareTo(to) < 0;
        var firstAcct = lockAccount(tid, fromFirst ? from : to);
        var secondAcct = lockAccount(tid, fromFirst ? to : from);
        var src = fromFirst ? firstAcct : secondAcct;
        var tgt = fromFirst ? secondAcct : firstAcct;
        if (!src.isDebitAllowed())
            throw new BusinessException("SOURCE_NOT_DEBITABLE", "Source " + src.getAccountStatus());
        // CBS: Multi-signatory enforcement on transfer source (same as withdrawal).
        // System-generated transfers (Standing Instructions: SIP, utility sweep, RD contribution)
        // are exempt — they execute per pre-authorized mandate signed by all joint holders.
        // Per Finacle SI_ENGINE: SI transfers are system-initiated, not user-initiated.
        // The idempotencyKey prefix "SI-" identifies standing instruction transfers.
        if ("JOINTLY".equals(src.getJointHolderMode())
                && (idk == null || !idk.startsWith("SI-"))) {
            throw new BusinessException("JOINT_AUTHORIZATION_REQUIRED",
                    "Source account " + from + " is a JOINTLY-operated joint account. "
                            + "All signatories must authorize debits. Please visit the branch.");
        }
        if (!tgt.isCreditAllowed())
            throw new BusinessException("TARGET_NOT_CREDITABLE", "Target " + tgt.getAccountStatus());
        if (!src.hasSufficientFunds(amount))
            throw new BusinessException(
                    "INSUFFICIENT_BALANCE", "Transfer " + amount + " > available " + src.getEffectiveAvailable());
        // CBS: Minimum balance enforcement on transfer source per Finacle ACCTLIMIT
        if (src.getMinimumBalance().signum() > 0) {
            BigDecimal postTransferBalance = src.getLedgerBalance().subtract(amount);
            if (postTransferBalance.compareTo(src.getMinimumBalance()) < 0) {
                throw new BusinessException(
                        "MINIMUM_BALANCE_BREACH",
                        "Transfer of INR " + amount + " would breach minimum balance of INR " + src.getMinimumBalance()
                                + " on source account " + from);
            }
        }
        // CBS Tier-1: Daily transfer limit enforcement per Finacle ACCTLIMIT / Temenos LIMIT.CHECK.
        // Per RBI Operational Risk Guidelines: transfer limits are independent of withdrawal limits.
        // A customer may have INR 2L daily withdrawal limit but INR 5L daily transfer limit.
        // The daily transfer limit caps the aggregate of all TRANSFER_DEBIT amounts on the
        // source account for the business date. Reversed transfers are excluded from the sum.
        if (src.getDailyTransferLimit() != null && src.getDailyTransferLimit().signum() > 0) {
            BigDecimal dailyTransfers = transactionRepository.sumDailyTransferDebits(tid, src.getId(), bd);
            if (dailyTransfers.add(amount).compareTo(src.getDailyTransferLimit()) > 0) {
                throw new BusinessException(
                        "DAILY_TRANSFER_LIMIT_EXCEEDED",
                        "Daily transfer limit INR " + src.getDailyTransferLimit() + " exceeded on account " + from
                                + ". Today's transfers: INR " + dailyTransfers + ", requested: INR " + amount);
            }
        }
        var r = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("DEPOSIT")
                .transactionType("TRANSFER_DEBIT")
                .accountReference(from)
                .amount(amount)
                .valueDate(bd)
                .branchCode(src.getBranch().getBranchCode())
                .narration(narration != null ? narration : "Transfer to " + to)
                .idempotencyKey(idk)
                .journalLines(List.of(
                        new JournalLineRequest(glForAccount(src), DebitCredit.DEBIT, amount, "Transfer debit"),
                        new JournalLineRequest(glForAccount(tgt), DebitCredit.CREDIT, amount, "Transfer credit")))
                .build());
        if (r.isPendingApproval()) {
            return buildTxn(
                    src,
                    amount,
                    "TRANSFER_DEBIT",
                    "DEBIT",
                    bd,
                    (narration != null ? narration : "Transfer to " + to) + " [PENDING APPROVAL]",
                    r,
                    r.getTransactionRef(),
                    idk,
                    "INTERNAL",
                    to);
        }
        src.setLedgerBalance(src.getLedgerBalance().subtract(amount));
        recomputeAvailable(src);
        src.setLastTransactionDate(bd);
        src.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(src);
        tgt.setLedgerBalance(tgt.getLedgerBalance().add(amount));
        recomputeAvailable(tgt);
        tgt.setLastTransactionDate(bd);
        if (tgt.isDormant()) {
            tgt.setAccountStatus(DepositAccountStatus.ACTIVE);
            tgt.setDormantDate(null);
            auditService.logEvent(
                    "DepositAccount",
                    tgt.getId(),
                    "DORMANCY_REACTIVATED",
                    "DORMANT",
                    "ACTIVE",
                    "DEPOSIT",
                    "Account reactivated via transfer credit: " + tgt.getAccountNumber() + " | Amount: INR " + amount
                            + " | From: " + from);
        }
        tgt.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(tgt);
        // CBS: TRANSFER_CREDIT leg gets its own unique transactionRef to satisfy
        // the unique constraint on (tenant_id, transaction_ref) in deposit_transactions.
        // Per Finacle TRAN_DETAIL: each subledger entry has its own unique reference.
        String creditTxnRef = ReferenceGenerator.generateTransactionRef();
        buildTxn(
                tgt,
                amount,
                "TRANSFER_CREDIT",
                "CREDIT",
                bd,
                "Transfer from " + from,
                r,
                creditTxnRef,
                null,
                "INTERNAL",
                from);
        return buildTxn(
                src, amount, "TRANSFER_DEBIT", "DEBIT", bd, narration, r, r.getTransactionRef(), idk, "INTERNAL", to);
    }

    // === Interest Accrual (EOD daily) ===
    @Override
    @Transactional
    public void accrueInterest(String acn, LocalDate bd) {
        String tid = TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        if (!acct.isSavings() || acct.getInterestRate().signum() <= 0) return;
        // CBS: Guard against double-accrual if EOD reruns or retries for the same date.
        // Per Finacle EOD / Temenos COB: accrual is idempotent per business date.
        if (bd.equals(acct.getLastInterestAccrualDate())) return;
        // CBS: YTD Reset on Indian Financial Year boundary (April 1).
        // Per IT Act Section 194A: TDS threshold is per financial year (Apr 1 - Mar 31).
        // Without reset, YTD accumulates forever and TDS is incorrectly applied in year 2+.
        // Indian FY: Apr 1 of year N to Mar 31 of year N+1. So on April 1, 2026,
        // last accrual date Mar 31, 2026 is in the PREVIOUS FY (both calendar year 2026).
        // Correct check: last accrual was before April 1 of the current calendar year.
        if (bd.getMonthValue() == 4 && bd.getDayOfMonth() == 1) {
            if (acct.getLastInterestAccrualDate() != null
                    && acct.getLastInterestAccrualDate().isBefore(bd)) {
                acct.setYtdInterestCredited(BigDecimal.ZERO);
                acct.setYtdTdsDeducted(BigDecimal.ZERO);
                log.info("FY reset: YTD counters reset for {} on {}", acn, bd);
            }
        }
        // CBS Tier-1: Interest tiering per Finacle INTDEF / RBI Savings Interest Directive.
        // If the product has balance-based tiering enabled, apply different rates per
        // balance slab. Otherwise, use the flat rate from the account.
        // Tiering JSON format: [{"min":0,"max":100000,"rate":3.0},{"min":100001,"max":500000,"rate":3.5}]
        BigDecimal daily = computeDailyInterest(acct, bd);
        if (daily.signum() <= 0) return;
        acct.setAccruedInterest(acct.getAccruedInterest().add(daily));
        acct.setLastInterestAccrualDate(bd);
        accountRepository.save(acct);

        // CBS: Per-day interest accrual record for RBI audit trail.
        // Per RBI IT Governance Direction 2023: all financial calculations must be
        // logged and reproducible. This enables deterministic accrual replay,
        // audit reconciliation, and forensic analysis.
        // Reuses the existing interest_accruals table (shared with loan module).
        InterestAccrual record = new InterestAccrual();
        record.setTenantId(tid);
        record.setAccountId(acct.getId());
        record.setAccrualDate(bd);
        record.setPrincipalBase(acct.getLedgerBalance());
        record.setRateApplied(acct.getInterestRate());
        record.setDaysCount(1);
        record.setAccruedAmount(daily);
        record.setAccrualType("CASA_REGULAR");
        record.setPostedFlag(false); // Not yet posted to GL — credited quarterly
        record.setBusinessDate(bd);
        record.setCreatedBy("SYSTEM_EOD");
        accrualRepository.save(record);
    }

    // === Quarterly Interest Credit with TDS ===
    // Per RBI Savings Interest Directive / Finacle ACCT_BAL_HIST:
    // At quarter-end, interest should be calculated on the MINIMUM daily balance
    // for the quarter, not the closing balance. This uses DailyBalanceSnapshot data
    // captured during EOD Step 8.6. If snapshot data is not available (e.g., account
    // opened mid-quarter, or first quarter after snapshot feature deployment), falls
    // back to the accrued interest already computed daily on closing balance.
    @Override
    @Transactional
    public DepositTransaction creditInterest(String acn, LocalDate bd) {
        String tid = TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        var interest = acct.getAccruedInterest();
        if (interest.signum() <= 0) return null;
        // CBS: Idempotency guard — prevent double credit on EOD retry.
        if (bd.equals(acct.getLastInterestCreditDate())) {
            log.warn("Interest already credited for {} on {}, skipping", acn, bd);
            return null;
        }

        // CBS Tier-1: Minimum Daily Balance interest recalculation per RBI directive.
        // Per Finacle ACCT_BAL_HIST: at quarter-end, recalculate interest using
        // MIN(closing_balance) from daily snapshots instead of the daily-accrued sum.
        // This ensures RBI-compliant interest calculation where a customer who had
        // INR 1,00,000 for 89 days but withdrew to INR 1,000 on day 90 gets interest
        // on INR 1,000 (minimum), not the daily-product sum.
        //
        // IMPORTANT: The day multiplier must be the ACTUAL number of CALENDAR days in
        // the quarter (for the interest formula), but the coverage check must compare
        // snapshot count against BUSINESS days (not calendar days).
        //
        // EOD only runs on business days, so snapshots only exist for business days.
        // A typical quarter has ~91 calendar days but only ~63 business days.
        // Comparing snapshots (~63) against calendar days (~91) would NEVER pass the
        // coverage check, making the min-balance feature completely non-functional.
        //
        // Completeness guard: Only apply min-balance recalculation when snapshot coverage
        // is sufficient (>= businessDays - 1, allowing for quarter-end day lag).
        // When coverage is insufficient, fall through to use daily-accrued interest as-is.
        LocalDate quarterStart = getQuarterStartDate(bd);
        long actualDaysInQuarter = ChronoUnit.DAYS.between(quarterStart, bd) + 1;
        // CBS: Count BUSINESS days (non-holiday) from the calendar for coverage comparison.
        // This uses the account's branch calendar since holidays can vary by branch.
        Long branchId = acct.getBranch() != null ? acct.getBranch().getId() : null;
        long businessDaysInQuarter = branchId != null
                ? calendarRepository.countBusinessDaysInPeriod(tid, branchId, quarterStart, bd)
                : actualDaysInQuarter; // Fallback to calendar days if no branch (shouldn't happen)
        long snapshotDays = balanceSnapshotRepository.countSnapshotsInPeriod(
                tid, acct.getId(), quarterStart, bd);
        if (snapshotDays > 0 && snapshotDays >= businessDaysInQuarter - 1) {
            BigDecimal minBalance = balanceSnapshotRepository.findMinBalanceInPeriod(
                    tid, acct.getId(), quarterStart, bd);
            // CBS: Include today's CURRENT ledger balance as a min-balance candidate.
            // Step 8.6 (snapshot capture) runs AFTER Step 8 (interest credit) in EOD,
            // so today's snapshot doesn't exist yet when this method executes.
            // If the customer made a large withdrawal today, the current balance may be
            // lower than any prior snapshot — we must use the lower of the two.
            // Per RBI: interest on minimum daily balance means the TRUE minimum across
            // ALL days in the quarter, including the current day.
            minBalance = minBalance.min(acct.getLedgerBalance());
            // Recalculate interest on minimum daily balance for the ACTUAL quarter duration
            // Formula: minBalance * rate * actualDays / 36500
            // Per RBI: interest is for the full quarter period, not just snapshot-covered days
            BigDecimal recalculated = minBalance
                    .multiply(acct.getInterestRate())
                    .multiply(BigDecimal.valueOf(actualDaysInQuarter))
                    .divide(new BigDecimal("36500"), 2, RoundingMode.HALF_UP);
            if (recalculated.compareTo(interest) < 0) {
                log.info("Min daily balance adjustment: account={}, accrued={}, minBal recalc={}, minBalance={}, "
                        + "actualDays={}, snapshotDays={}",
                        acn, interest, recalculated, minBalance, actualDaysInQuarter, snapshotDays);
                interest = recalculated;
            }
            // If recalculated > accrued (shouldn't happen if snapshots are correct),
            // use the lower value (accrued) as a safety cap — never overpay interest.
        } else if (snapshotDays > 0) {
            // Insufficient snapshot coverage — log warning but use daily-accrued interest.
            // Per CBS safety principle: when data is incomplete, do NOT recalculate.
            // Daily accrual on closing balance is the conservative fallback.
            log.warn("Min daily balance skipped: account={}, snapshotDays={}, businessDays={}, "
                    + "coverage={}% — insufficient for recalculation, using daily-accrued interest",
                    acn, snapshotDays, businessDaysInQuarter,
                    String.format("%.1f", businessDaysInQuarter > 0
                            ? snapshotDays * 100.0 / businessDaysInQuarter : 0));
        }
        // CBS CRITICAL: If min-balance recalculation reduced interest to zero (e.g., customer
        // withdrew entire balance on quarter-end day → minBalance=0 → recalculated=0),
        // skip the GL posting entirely. A zero-amount GL entry creates phantom transactions
        // in the ledger, corrupts the voucher register, and the TransactionEngine may reject
        // zero amounts. Per Finacle: zero-interest quarters are valid — just clear accrued
        // and update lastInterestCreditDate without posting.
        if (interest.signum() <= 0) {
            acct.setAccruedInterest(BigDecimal.ZERO);
            acct.setLastInterestCreditDate(bd);
            accountRepository.save(acct);
            log.info("Zero interest after min-balance adjustment for {} on {} — no GL posting", acn, bd);
            return null;
        }

        String gl = glForAccount(acct);
        // CBS Tier-1: Resolve interest expense GL from ProductMaster via ProductGLResolver.
        // For CASA products, glInterestReceivable stores the interest expense GL (5010).
        // Uses type-safe product existence check — no sentinel value comparison.
        String interestExpenseGl;
        var interestProduct = glResolver.getProduct(acct.getProductCode());
        if (interestProduct != null && interestProduct.getGlInterestReceivable() != null) {
            interestExpenseGl = interestProduct.getGlInterestReceivable();
        } else {
            interestExpenseGl = GLConstants.INTEREST_EXPENSE_DEPOSITS;
        }
        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("DEPOSIT")
                .transactionType("INTEREST_CREDIT")
                .accountReference(acn)
                .amount(interest)
                .valueDate(bd)
                .branchCode(acct.getBranch().getBranchCode())
                .narration("Quarterly interest credit")
                .systemGenerated(true)
                .journalLines(List.of(
                        new JournalLineRequest(
                                interestExpenseGl, DebitCredit.DEBIT, interest, "Interest expense"),
                        new JournalLineRequest(gl, DebitCredit.CREDIT, interest, "Interest credit " + acn)))
                .build());
        acct.setLedgerBalance(acct.getLedgerBalance().add(interest));
        acct.setYtdInterestCredited(acct.getYtdInterestCredited().add(interest));

        // IT Act Section 194A: TDS deduction at source if YTD interest exceeds threshold.
        // TDS is deducted on the TOTAL quarterly credit amount (not just the excess).
        // Per IT Act: TDS = 10% of interest if cumulative YTD interest exceeds:
        //   INR 40,000 for regular customers
        //   INR 50,000 for senior citizens (age >= 60)
        // Per RBI/IT Act: senior citizen status is determined by customer's age on
        // the business date (not calendar date) from their date of birth.
        BigDecimal tdsThreshold = TDS_THRESHOLD_REGULAR;
        if (acct.getCustomer() != null && acct.getCustomer().getDateOfBirth() != null) {
            long age = ChronoUnit.YEARS.between(acct.getCustomer().getDateOfBirth(), bd);
            if (age >= SENIOR_CITIZEN_AGE) {
                tdsThreshold = TDS_THRESHOLD_SENIOR;
            }
        }
        BigDecimal tdsAmount = BigDecimal.ZERO;
        if (acct.getYtdInterestCredited().compareTo(tdsThreshold) > 0) {
            tdsAmount = interest.multiply(TDS_RATE).setScale(2, RoundingMode.HALF_UP);
            if (tdsAmount.signum() > 0) {
                acct.setLedgerBalance(acct.getLedgerBalance().subtract(tdsAmount));
                acct.setYtdTdsDeducted(acct.getYtdTdsDeducted().add(tdsAmount));
                // GL: DR Customer Deposits / CR TDS Payable (2500)
                transactionEngine.execute(TransactionRequest.builder()
                        .sourceModule("DEPOSIT")
                        .transactionType("TDS_DEBIT")
                        .accountReference(acn)
                        .amount(tdsAmount)
                        .valueDate(bd)
                        .branchCode(acct.getBranch().getBranchCode())
                        .narration("TDS u/s 194A on interest INR " + interest)
                        .systemGenerated(true)
                        .journalLines(List.of(
                                new JournalLineRequest(gl, DebitCredit.DEBIT, tdsAmount, "TDS deduction"),
                                new JournalLineRequest(
                                        GLConstants.TDS_PAYABLE,
                                        DebitCredit.CREDIT,
                                        tdsAmount,
                                        "TDS payable u/s 194A")))
                        .build());
                log.info("TDS deducted: account={}, interest={}, tds={}", acn, interest, tdsAmount);
            }
        }

        recomputeAvailable(acct);
        acct.setAccruedInterest(BigDecimal.ZERO);
        acct.setLastInterestCreditDate(bd);
        accountRepository.save(acct);
        return buildTxn(
                acct,
                interest,
                "INTEREST_CREDIT",
                "CREDIT",
                bd,
                "Quarterly interest credit" + (tdsAmount.signum() > 0 ? " (TDS INR " + tdsAmount + " deducted)" : ""),
                r,
                r.getTransactionRef(),
                null,
                "SYSTEM",
                null);
    }
    // === Account Activation (Maker-Checker Phase 2) ===
    // Per Finacle ACCTOPN: CHECKER approves the workflow → this method activates the account.
    // Transitions: PENDING_ACTIVATION → ACTIVE
    @Override
    @Transactional
    public DepositAccount activateAccount(String acn) {
        String tid = TenantContext.getCurrentTenant();
        var acct = lockAccount(tid, acn);
        if (acct.getAccountStatus() != DepositAccountStatus.PENDING_ACTIVATION) {
            throw new BusinessException(
                    "INVALID_STATE",
                    "Account " + acn + " is not in PENDING_ACTIVATION state. Current: " + acct.getAccountStatus());
        }
        acct.setAccountStatus(DepositAccountStatus.ACTIVE);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);

        auditService.logEvent(
                "DepositAccount",
                saved.getId(),
                "ACCOUNT_ACTIVATED",
                "PENDING_ACTIVATION",
                "ACTIVE",
                "DEPOSIT",
                "Account activated by checker: " + acn + " | Activated by: " + SecurityUtil.getCurrentUsername());

        log.info("CASA activated: num={}, checker={}", acn, SecurityUtil.getCurrentUsername());
        return saved;
    }

    // === Freeze ===
    @Override
    @Transactional
    public DepositAccount freezeAccount(String acn, String freezeType, String reason) {
        var acct = lockAccount(TenantContext.getCurrentTenant(), acn);
        if (acct.isClosed()) throw new BusinessException("ACCOUNT_CLOSED", "Cannot freeze closed account");
        DepositAccountStatus prevStatus = acct.getAccountStatus();
        acct.setAccountStatus(DepositAccountStatus.FROZEN);
        acct.setFreezeType(freezeType);
        acct.setFreezeReason(reason);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent(
                "DepositAccount",
                saved.getId(),
                "FREEZE",
                prevStatus.name(),
                "FROZEN",
                "DEPOSIT",
                "Account frozen: " + acn + " type=" + freezeType + " reason=" + reason);
        log.info("Account frozen: {} type={}", acn, freezeType);
        return saved;
    }

    // === Unfreeze ===
    @Override
    @Transactional
    public DepositAccount unfreezeAccount(String acn) {
        var acct = lockAccount(TenantContext.getCurrentTenant(), acn);
        if (!acct.isFrozen()) throw new BusinessException("NOT_FROZEN", "Account is not frozen");
        String prevFreezeType = acct.getFreezeType();
        acct.setAccountStatus(DepositAccountStatus.ACTIVE);
        acct.setFreezeType(null);
        acct.setFreezeReason(null);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent(
                "DepositAccount",
                saved.getId(),
                "UNFREEZE",
                "FROZEN",
                "ACTIVE",
                "DEPOSIT",
                "Account unfrozen: " + acn + " (was " + prevFreezeType + ")");
        log.info("Account unfrozen: {}", acn);
        return saved;
    }

    // === Close ===
    // Per Finacle ACCTCLS / Temenos ACCOUNT.CLOSURE:
    // 1. Balance must be zero
    // 2. Accrued interest must be zero (credit pending interest first)
    // 3. No active holds/liens
    // 4. Not already closed
    @Override
    @Transactional
    public DepositAccount closeAccount(String acn, String reason) {
        var acct = lockAccount(TenantContext.getCurrentTenant(), acn);
        if (acct.isClosed()) throw new BusinessException("ALREADY_CLOSED", "Already closed");
        // CBS: Multi-signatory enforcement on account closure
        if ("JOINTLY".equals(acct.getJointHolderMode())) {
            throw new BusinessException("JOINT_AUTHORIZATION_REQUIRED",
                    "Account " + acn + " is a JOINTLY-operated joint account. "
                            + "All signatories must authorize closure. Please visit the branch.");
        }
        if (acct.getAccruedInterest() != null && acct.getAccruedInterest().signum() > 0)
            throw new BusinessException(
                    "PENDING_INTEREST",
                    "Account has accrued interest INR " + acct.getAccruedInterest()
                            + ". Credit interest before closure.");
        if (acct.getHoldAmount() != null && acct.getHoldAmount().signum() > 0)
            throw new BusinessException(
                    "ACTIVE_HOLD",
                    "Account has active hold/lien INR " + acct.getHoldAmount() + ". Release all holds before closure.");
        if (acct.getLedgerBalance().signum() != 0)
            throw new BusinessException(
                    "NON_ZERO_BALANCE", "Balance must be zero to close. Current: " + acct.getLedgerBalance());
        DepositAccountStatus prevStatus = acct.getAccountStatus();
        acct.setAccountStatus(DepositAccountStatus.CLOSED);
        acct.setClosedDate(businessDateService.getCurrentBusinessDate());
        acct.setClosureReason(reason);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        var saved = accountRepository.save(acct);
        auditService.logEvent(
                "DepositAccount",
                saved.getId(),
                "ACCOUNT_CLOSED",
                prevStatus.name(),
                "CLOSED",
                "DEPOSIT",
                "Account closed: " + acn + " reason=" + reason);
        log.info("Account closed: {}", acn);
        return saved;
    }

    // === Dormancy (EOD batch) ===
    // Per RBI Master Direction on KYC 2016, Section 38:
    // Accounts with no customer-initiated transaction for 24+ months → DORMANT.
    // Per REVIEW.md: every state change MUST be logged via AuditService.
    @Override
    @Transactional
    public int markDormantAccounts(LocalDate businessDate) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate cutoff = businessDate.minusMonths(DORMANCY_MONTHS);
        var candidates = accountRepository.findDormancyCandidates(tid, cutoff);
        int count = 0;
        for (var acct : candidates) {
            String prevStatus = acct.getAccountStatus().name();
            acct.setAccountStatus(DepositAccountStatus.DORMANT);
            acct.setDormantDate(businessDate);
            acct.setUpdatedBy("SYSTEM_EOD");
            accountRepository.save(acct);

            // CBS: Audit trail for dormancy state change per RBI IT Governance Direction 2023.
            // Per REVIEW.md: every state change must be logged via AuditService.
            // Dormancy is a regulatory classification that affects transaction gating
            // (debits blocked, credits allowed) — must be traceable for RBI audit.
            auditService.logEvent(
                    "DepositAccount",
                    acct.getId(),
                    "DORMANCY_CLASSIFIED",
                    prevStatus,
                    "DORMANT",
                    "DEPOSIT",
                    "Account marked DORMANT: " + acct.getAccountNumber()
                            + " | Last txn: " + acct.getLastTransactionDate()
                            + " | Cutoff: " + cutoff
                            + " | Balance: INR " + acct.getLedgerBalance()
                            + " | Customer: "
                            + (acct.getCustomer() != null ? acct.getCustomer().getCustomerNumber() : "N/A"));

            count++;
        }
        if (count > 0) log.info("Marked {} accounts as DORMANT (cutoff={})", count, cutoff);

        // CBS Gap #2: INOPERATIVE classification per RBI Unclaimed Deposits Direction 2024.
        // Accounts DORMANT for 10+ years (no customer-initiated txn for 120+ months total)
        // must transition to INOPERATIVE. INOPERATIVE accounts require senior management
        // approval to reactivate and must be reported to RBI UDGAM portal.
        // Per RBI: DORMANT (2yr) → INOPERATIVE (10yr) → Unclaimed (reported to UDGAM)
        LocalDate inoperativeCutoff = businessDate.minusMonths(INOPERATIVE_MONTHS);
        var inoperativeCandidates = accountRepository.findInoperativeCandidates(tid, inoperativeCutoff);
        int inoperativeCount = 0;
        for (var acct : inoperativeCandidates) {
            if (acct.getLastTransactionDate() != null
                    && acct.getLastTransactionDate().isBefore(inoperativeCutoff)) {
                acct.setAccountStatus(DepositAccountStatus.INOPERATIVE);
                acct.setUpdatedBy("SYSTEM_EOD");
                accountRepository.save(acct);

                auditService.logEvent(
                        "DepositAccount",
                        acct.getId(),
                        "INOPERATIVE_CLASSIFIED",
                        "DORMANT",
                        "INOPERATIVE",
                        "DEPOSIT",
                        "Account marked INOPERATIVE (10yr+): " + acct.getAccountNumber()
                                + " | Last txn: " + acct.getLastTransactionDate()
                                + " | Balance: INR " + acct.getLedgerBalance()
                                + " | Per RBI Unclaimed Deposits Direction 2024");
                inoperativeCount++;
            }
        }
        if (inoperativeCount > 0) {
            log.info("Marked {} accounts as INOPERATIVE (10yr+ no txn)", inoperativeCount);
        }

        return count + inoperativeCount;
    }

    // === Account Maintenance (Finacle ACCTMOD / Temenos ACCOUNT.MODIFY) ===
    @Override
    @Transactional
    public DepositAccount maintainAccount(
            String acn,
            String nomineeName,
            String nomineeRelationship,
            String jointHolderMode,
            Boolean chequeBookEnabled,
            Boolean debitCardEnabled,
            BigDecimal dailyWithdrawalLimit,
            BigDecimal dailyTransferLimit,
            BigDecimal odLimit,
            BigDecimal interestRate,
            BigDecimal minimumBalance) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        DepositAccount acct = lockAccount(tid, acn);

        // CBS: Only ACTIVE accounts can be modified per Finacle ACCTMOD.
        // PENDING_ACTIVATION: not yet operational — complete activation first.
        // FROZEN: under regulatory hold — modifications blocked per PMLA.
        // DORMANT: requires reactivation via deposit first.
        // CLOSED: terminal — no modifications allowed.
        if (!acct.isActive()) {
            throw new BusinessException("ACCOUNT_NOT_ACTIVE",
                    "Account maintenance requires ACTIVE status. Current: " + acct.getAccountStatus()
                            + ". " + (acct.isDormant() ? "Make a deposit to reactivate first."
                            : acct.getAccountStatus() == DepositAccountStatus.PENDING_ACTIVATION
                            ? "Activate the account first." : ""));
        }

        // CBS: Capture before-state for audit trail per RBI IT Governance §8.3.
        // Every field modification must be traceable with before/after values.
        StringBuilder beforeState = new StringBuilder();
        StringBuilder afterState = new StringBuilder();
        StringBuilder changes = new StringBuilder();
        boolean modified = false;

        // Nominee (RBI Deposit Insurance)
        // CBS: Only record as changed if the new value ACTUALLY differs from current.
        // The HTML form always submits all fields — without this check, every maintenance
        // operation would record all fields as "changed" even when untouched, creating
        // noisy audit trails that obscure real changes during RBI inspection.
        if (nomineeName != null) {
            String newNominee = nomineeName.isBlank() ? null : nomineeName;
            if (!java.util.Objects.equals(newNominee, acct.getNomineeName())) {
                beforeState.append("nominee=").append(acct.getNomineeName());
                acct.setNomineeName(newNominee);
                afterState.append("nominee=").append(acct.getNomineeName());
                changes.append("Nominee: ").append(acct.getNomineeName()).append("; ");
                modified = true;
            }
        }
        if (nomineeRelationship != null) {
            String newRel = nomineeRelationship.isBlank() ? null : nomineeRelationship;
            if (!java.util.Objects.equals(newRel, acct.getNomineeRelationship())) {
                beforeState.append("|rel=").append(acct.getNomineeRelationship());
                acct.setNomineeRelationship(newRel);
                afterState.append("|rel=").append(acct.getNomineeRelationship());
                modified = true;
            }
        }

        // Joint Holder Mode
        if (jointHolderMode != null) {
            String newJoint = jointHolderMode.isBlank() ? null : jointHolderMode;
            if (!java.util.Objects.equals(newJoint, acct.getJointHolderMode())) {
                beforeState.append("|joint=").append(acct.getJointHolderMode());
                acct.setJointHolderMode(newJoint);
                afterState.append("|joint=").append(acct.getJointHolderMode());
                changes.append("JointMode: ").append(acct.getJointHolderMode()).append("; ");
                modified = true;
            }
        }

        // Cheque Book
        if (chequeBookEnabled != null && chequeBookEnabled != acct.isChequeBookEnabled()) {
            beforeState.append("|cheque=").append(acct.isChequeBookEnabled());
            acct.setChequeBookEnabled(chequeBookEnabled);
            afterState.append("|cheque=").append(acct.isChequeBookEnabled());
            changes.append("ChequeBook: ").append(chequeBookEnabled ? "ENABLED" : "DISABLED").append("; ");
            modified = true;
        }

        // Debit Card
        if (debitCardEnabled != null && debitCardEnabled != acct.isDebitCardEnabled()) {
            beforeState.append("|debit=").append(acct.isDebitCardEnabled());
            acct.setDebitCardEnabled(debitCardEnabled);
            afterState.append("|debit=").append(acct.isDebitCardEnabled());
            changes.append("DebitCard: ").append(debitCardEnabled ? "ENABLED" : "DISABLED").append("; ");
            modified = true;
        }

        // Daily Withdrawal Limit
        if (dailyWithdrawalLimit != null) {
            if (dailyWithdrawalLimit.signum() < 0)
                throw new BusinessException("INVALID_LIMIT", "Daily withdrawal limit cannot be negative.");
            if (dailyWithdrawalLimit.compareTo(acct.getDailyWithdrawalLimit()) != 0) {
                beforeState.append("|wdlLimit=").append(acct.getDailyWithdrawalLimit());
                acct.setDailyWithdrawalLimit(dailyWithdrawalLimit);
                afterState.append("|wdlLimit=").append(acct.getDailyWithdrawalLimit());
                changes.append("WithdrawalLimit: INR ").append(dailyWithdrawalLimit).append("; ");
                modified = true;
            }
        }

        // Daily Transfer Limit
        if (dailyTransferLimit != null) {
            if (dailyTransferLimit.signum() < 0)
                throw new BusinessException("INVALID_LIMIT", "Daily transfer limit cannot be negative.");
            if (dailyTransferLimit.compareTo(acct.getDailyTransferLimit()) != 0) {
                beforeState.append("|xfrLimit=").append(acct.getDailyTransferLimit());
                acct.setDailyTransferLimit(dailyTransferLimit);
                afterState.append("|xfrLimit=").append(acct.getDailyTransferLimit());
                changes.append("TransferLimit: INR ").append(dailyTransferLimit).append("; ");
                modified = true;
            }
        }

        // OD Limit (CURRENT_OD accounts only)
        if (odLimit != null) {
            if (!acct.getAccountType().name().contains("CURRENT_OD"))
                throw new BusinessException("OD_NOT_APPLICABLE",
                        "OD limit is only applicable for CURRENT_OD accounts. This account is " + acct.getAccountType());
            if (odLimit.signum() < 0)
                throw new BusinessException("INVALID_LIMIT", "OD limit cannot be negative.");
            if (odLimit.compareTo(acct.getOdLimit()) != 0) {
                beforeState.append("|od=").append(acct.getOdLimit());
                acct.setOdLimit(odLimit);
                recomputeAvailable(acct);
                afterState.append("|od=").append(acct.getOdLimit());
                changes.append("ODLimit: INR ").append(odLimit).append("; ");
                modified = true;
            }
        }

        // Interest Rate Override (ADMIN only, savings accounts only)
        if (interestRate != null) {
            if (!acct.isSavings())
                throw new BusinessException("RATE_NOT_APPLICABLE",
                        "Interest rate override is only applicable for savings accounts. This account is " + acct.getAccountType());
            if (interestRate.signum() < 0 || interestRate.compareTo(new BigDecimal("100")) > 0)
                throw new BusinessException("INVALID_RATE", "Interest rate must be between 0% and 100%.");
            if (interestRate.compareTo(acct.getInterestRate()) != 0) {
                beforeState.append("|rate=").append(acct.getInterestRate());
                acct.setInterestRate(interestRate);
                afterState.append("|rate=").append(acct.getInterestRate());
                changes.append("InterestRate: ").append(interestRate).append("%; ");
                modified = true;
            }
        }

        // Minimum Balance Override/Waiver
        if (minimumBalance != null) {
            if (minimumBalance.signum() < 0)
                throw new BusinessException("INVALID_AMOUNT", "Minimum balance cannot be negative.");
            if (minimumBalance.compareTo(acct.getMinimumBalance()) != 0) {
                beforeState.append("|minBal=").append(acct.getMinimumBalance());
                acct.setMinimumBalance(minimumBalance);
                afterState.append("|minBal=").append(acct.getMinimumBalance());
                changes.append("MinBalance: INR ").append(minimumBalance).append("; ");
                modified = true;
            }
        }

        if (!modified) {
            throw new BusinessException("NO_CHANGES", "No modifications specified.");
        }

        acct.setUpdatedBy(user);
        DepositAccount saved = accountRepository.save(acct);

        auditService.logEvent(
                "DepositAccount",
                saved.getId(),
                "ACCOUNT_MAINTAINED",
                beforeState.toString(),
                afterState.toString(),
                "DEPOSIT",
                "Account maintained: " + acn + " | " + changes + " | By: " + user);

        log.info("Account maintained: acn={}, changes={}, user={}", acn, changes, user);
        return saved;
    }

    // === Read Operations ===
    @Override
    public DepositAccount getAccount(String acn) {
        String tid = TenantContext.getCurrentTenant();
        DepositAccount acct = accountRepository
                .findByTenantIdAndAccountNumber(tid, acn)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        // CBS Tier-1: Branch access enforcement on read.
        // MAKER/CHECKER can only view accounts at their home branch.
        // ADMIN/AUDITOR can view all branches (enforced by BranchAccessValidator).
        branchAccessValidator.validateAccess(acct.getBranch());
        return acct;
    }

    @Override
    public List<DepositAccount> getAllAccounts() {
        return accountRepository.findAllNonClosedAccounts(TenantContext.getCurrentTenant());
    }

    @Override
    public List<DepositAccount> getActiveAccounts() {
        return accountRepository.findAllActiveAccounts(TenantContext.getCurrentTenant());
    }

    @Override
    public List<DepositAccount> getAccountsByBranch(Long branchId) {
        return accountRepository.findByTenantIdAndBranchId(TenantContext.getCurrentTenant(), branchId);
    }

    @Override
    public List<DepositAccount> getAccountsByCustomer(Long customerId) {
        return accountRepository.findByTenantIdAndCustomerId(TenantContext.getCurrentTenant(), customerId);
    }

    @Override
    public List<DepositTransaction> getTransactionHistory(String acn) {
        String tid = TenantContext.getCurrentTenant();
        var acct = accountRepository
                .findByTenantIdAndAccountNumber(tid, acn)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findByTenantIdAndDepositAccountIdOrderByPostingDateDesc(tid, acct.getId());
    }

    @Override
    public List<DepositTransaction> getMiniStatement(String acn, int count) {
        String tid = TenantContext.getCurrentTenant();
        var acct = accountRepository
                .findByTenantIdAndAccountNumber(tid, acn)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findRecentTransactions(tid, acct.getId(), PageRequest.of(0, count));
    }

    @Override
    public List<DepositTransaction> getStatement(String acn, LocalDate fromDate, LocalDate toDate) {
        String tid = TenantContext.getCurrentTenant();
        var acct = accountRepository
                .findByTenantIdAndAccountNumber(tid, acn)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", "Not found: " + acn));
        return transactionRepository.findByDateRange(tid, acct.getId(), fromDate, toDate);
    }

    // === Transaction Reversal ===
    // Per Finacle TRAN_REVERSAL / Temenos REVERSAL:
    // 1. Find original transaction (must exist, must not already be reversed)
    // 2. For TRANSFERS: find and reverse BOTH legs (TRANSFER_DEBIT + TRANSFER_CREDIT)
    //    — lock both accounts in alphabetical order to prevent deadlock
    // 3. Post contra GL entries via TransactionEngine (swap DR/CR legs)
    // 4. Restore account balance (add back for debits, subtract for credits)
    // 5. Mark original(s) as reversed (never delete per CBS audit rules)
    // 6. Create reversal DepositTransaction linked to original
    //
    // CBS CRITICAL: Fund transfers create TWO subledger entries sharing one journal entry.
    // Reversing only one leg leaves the counterparty balance and subledger incorrect —
    // a net money creation/destruction event that violates conservation-of-funds.
    @Override
    @Transactional
    public DepositTransaction reverseTransaction(String transactionRef, String reason, LocalDate businessDate) {
        String tid = TenantContext.getCurrentTenant();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Reversal reason is mandatory per RBI audit norms");
        }

        // Step 1: Find and validate original transaction
        DepositTransaction original = transactionRepository
                .findByTenantIdAndTransactionRef(tid, transactionRef)
                .orElseThrow(() ->
                        new BusinessException("TRANSACTION_NOT_FOUND", "Transaction not found: " + transactionRef));
        if (original.isReversed()) {
            throw new BusinessException("ALREADY_REVERSED", "Transaction " + transactionRef + " is already reversed");
        }

        // CBS Tier-1: Detect fund transfer reversal — requires both-leg handling.
        // Per Finacle TRAN_REVERSAL: TRANSFER_DEBIT and TRANSFER_CREDIT are paired.
        // Without both-leg reversal, the source balance is restored but the target retains
        // the credit — net money creation that corrupts GL trial balance.
        boolean isTransferReversal = "TRANSFER_DEBIT".equals(original.getTransactionType())
                || "TRANSFER_CREDIT".equals(original.getTransactionType());
        if (isTransferReversal) {
            return reverseTransfer(original, reason, businessDate, tid);
        }

        // === Non-transfer reversal (deposit, withdrawal, charge, etc.) ===
        // Step 2: Lock the account
        var acct = lockAccount(tid, original.getDepositAccount().getAccountNumber());
        if (acct.isClosed()) {
            throw new BusinessException(
                    "ACCOUNT_CLOSED", "Cannot reverse transaction on closed account: " + acct.getAccountNumber());
        }

        String gl = glForAccount(acct);
        BigDecimal amount = original.getAmount();
        boolean wasDebit = "DEBIT".equals(original.getDebitCredit());

        // Step 3: Post contra GL entries (swap DR/CR from original)
        List<JournalLineRequest> contraLines;
        if (wasDebit) {
            // Original was DR Customer Deposits / CR Bank Ops → Reverse: DR Bank Ops / CR Customer Deposits
            contraLines = List.of(
                    new JournalLineRequest(
                            GLConstants.BANK_OPERATIONS,
                            DebitCredit.DEBIT,
                            amount,
                            "Reversal of " + original.getTransactionType() + " " + transactionRef),
                    new JournalLineRequest(
                            gl, DebitCredit.CREDIT, amount, "Reversal credit " + acct.getAccountNumber()));
        } else {
            // Original was DR Bank Ops / CR Customer Deposits → Reverse: DR Customer Deposits / CR Bank Ops
            contraLines = List.of(
                    new JournalLineRequest(
                            gl,
                            DebitCredit.DEBIT,
                            amount,
                            "Reversal of " + original.getTransactionType() + " " + transactionRef),
                    new JournalLineRequest(
                            GLConstants.BANK_OPERATIONS,
                            DebitCredit.CREDIT,
                            amount,
                            "Reversal debit " + acct.getAccountNumber()));
        }

        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("DEPOSIT")
                .transactionType("REVERSAL")
                .accountReference(acct.getAccountNumber())
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(acct.getBranch().getBranchCode())
                .narration("Reversal of " + transactionRef + ": " + reason)
                .journalLines(contraLines)
                .systemGenerated(false)
                .build());

        // Step 4: Restore account balance
        if (wasDebit) {
            // Original debited → reversal credits back
            acct.setLedgerBalance(acct.getLedgerBalance().add(amount));
        } else {
            // Original credited → reversal debits back
            acct.setLedgerBalance(acct.getLedgerBalance().subtract(amount));
        }
        recomputeAvailable(acct);
        acct.setLastTransactionDate(businessDate);
        acct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(acct);

        // Step 5: Mark original as reversed
        original.setReversed(true);
        original.setReversedByRef(r.getTransactionRef());
        original.setUpdatedBy(SecurityUtil.getCurrentUsername());
        transactionRepository.save(original);

        auditService.logEvent(
                "DepositTransaction",
                original.getId(),
                "REVERSAL",
                original.getTransactionRef(),
                r.getTransactionRef(),
                "DEPOSIT",
                "Transaction reversed: " + transactionRef + " | Reason: " + reason + " | Amount: INR " + amount
                        + " | Account: " + acct.getAccountNumber());

        log.info(
                "Transaction reversed: ref={}, amount={}, account={}, reason={}",
                transactionRef,
                amount,
                acct.getAccountNumber(),
                reason);

        // Step 6: Create reversal transaction record.
        // CBS: Reversal direction is opposite of original — reversing a CREDIT creates
        // a DEBIT record, and vice versa. Per Finacle TRAN_REVERSAL / Temenos REVERSAL:
        // the subledger entry must accurately reflect the contra movement.
        String reversalDirection = wasDebit ? "CREDIT" : "DEBIT";
        return buildTxn(
                acct,
                amount,
                "REVERSAL",
                reversalDirection,
                businessDate,
                "Reversal of " + transactionRef + ": " + reason,
                r,
                r.getTransactionRef(),
                null,
                original.getChannel(),
                null);
    }

    /**
     * Reverse a fund transfer — handles BOTH legs (TRANSFER_DEBIT + TRANSFER_CREDIT) atomically.
     *
     * Per Finacle TRAN_REVERSAL / Temenos REVERSAL:
     * Fund transfers create two subledger entries sharing one GL journal entry. Both legs
     * must be reversed in a single atomic transaction to maintain subledger ↔ GL consistency.
     *
     * Without both-leg reversal, the source account's balance would be restored but the target
     * account would retain the credited amount — a net money creation event that violates
     * conservation-of-funds and corrupts the GL trial balance.
     *
     * Account locking follows the same alphabetical ordering as the transfer() method
     * to prevent ABBA deadlocks between concurrent transfer + reversal operations.
     */
    private DepositTransaction reverseTransfer(
            DepositTransaction original, String reason, LocalDate businessDate, String tid) {
        BigDecimal amount = original.getAmount();
        String counterpartyAccNo = original.getCounterpartyAccount();

        if (counterpartyAccNo == null || counterpartyAccNo.isBlank()) {
            throw new BusinessException("REVERSAL_FAILED",
                    "Cannot reverse transfer: counterparty account not recorded on transaction "
                            + original.getTransactionRef());
        }

        // Identify debit-side and credit-side account numbers
        String debitAccNo;
        String creditAccNo;
        if ("TRANSFER_DEBIT".equals(original.getTransactionType())) {
            debitAccNo = original.getDepositAccount().getAccountNumber();
            creditAccNo = counterpartyAccNo;
        } else {
            creditAccNo = original.getDepositAccount().getAccountNumber();
            debitAccNo = counterpartyAccNo;
        }

        // Lock BOTH accounts in alphabetical order per deadlock prevention convention
        boolean debitFirst = debitAccNo.compareTo(creditAccNo) < 0;
        var firstAcct = lockAccount(tid, debitFirst ? debitAccNo : creditAccNo);
        var secondAcct = lockAccount(tid, debitFirst ? creditAccNo : debitAccNo);
        var debitAcct = debitFirst ? firstAcct : secondAcct;
        var creditAcct = debitFirst ? secondAcct : firstAcct;

        if (debitAcct.isClosed()) {
            throw new BusinessException("ACCOUNT_CLOSED",
                    "Cannot reverse transfer: source account closed: " + debitAccNo);
        }
        if (creditAcct.isClosed()) {
            throw new BusinessException("ACCOUNT_CLOSED",
                    "Cannot reverse transfer: target account closed: " + creditAccNo);
        }

        // Find the counterparty leg (to mark it reversed too)
        DepositTransaction counterpartyTxn = null;
        if (original.getJournalEntryId() != null) {
            List<DepositTransaction> journalTxns = transactionRepository
                    .findByTenantIdAndJournalEntryId(tid, original.getJournalEntryId());
            for (DepositTransaction jt : journalTxns) {
                if (!jt.getId().equals(original.getId()) && !jt.isReversed()) {
                    counterpartyTxn = jt;
                    break;
                }
            }
        }
        if (counterpartyTxn != null && counterpartyTxn.isReversed()) {
            throw new BusinessException("ALREADY_REVERSED",
                    "Counterparty leg " + counterpartyTxn.getTransactionRef() + " is already reversed");
        }

        // Post contra GL entries: reverse the original transfer journal
        // Original: DR source_gl / CR target_gl → Reversal: DR target_gl / CR source_gl
        String srcGl = glForAccount(debitAcct);
        String tgtGl = glForAccount(creditAcct);
        TransactionResult r = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("DEPOSIT")
                .transactionType("REVERSAL")
                .accountReference(debitAccNo)
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(debitAcct.getBranch().getBranchCode())
                .narration("Transfer reversal: " + original.getTransactionRef() + " — " + reason)
                .journalLines(List.of(
                        new JournalLineRequest(tgtGl, DebitCredit.DEBIT, amount,
                                "Reversal debit " + creditAccNo),
                        new JournalLineRequest(srcGl, DebitCredit.CREDIT, amount,
                                "Reversal credit " + debitAccNo)))
                .systemGenerated(false)
                .build());

        // Restore both account balances
        // Debit account: was debited in original → credit back (add)
        debitAcct.setLedgerBalance(debitAcct.getLedgerBalance().add(amount));
        recomputeAvailable(debitAcct);
        debitAcct.setLastTransactionDate(businessDate);
        debitAcct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(debitAcct);

        // Credit account: was credited in original → debit back (subtract)
        creditAcct.setLedgerBalance(creditAcct.getLedgerBalance().subtract(amount));
        recomputeAvailable(creditAcct);
        creditAcct.setLastTransactionDate(businessDate);
        creditAcct.setUpdatedBy(SecurityUtil.getCurrentUsername());
        accountRepository.save(creditAcct);

        // Mark BOTH legs as reversed
        original.setReversed(true);
        original.setReversedByRef(r.getTransactionRef());
        original.setUpdatedBy(SecurityUtil.getCurrentUsername());
        transactionRepository.save(original);

        if (counterpartyTxn != null) {
            counterpartyTxn.setReversed(true);
            counterpartyTxn.setReversedByRef(r.getTransactionRef());
            counterpartyTxn.setUpdatedBy(SecurityUtil.getCurrentUsername());
            transactionRepository.save(counterpartyTxn);
        }

        auditService.logEvent(
                "DepositTransaction",
                original.getId(),
                "TRANSFER_REVERSAL",
                original.getTransactionRef(),
                r.getTransactionRef(),
                "DEPOSIT",
                "Transfer reversed (both legs): " + original.getTransactionRef()
                        + " | Reason: " + reason + " | Amount: INR " + amount
                        + " | Source: " + debitAccNo + " | Target: " + creditAccNo
                        + (counterpartyTxn != null ? " | Counterparty ref: " + counterpartyTxn.getTransactionRef() : ""));

        log.info("Transfer reversed (both legs): ref={}, amount={}, src={}, tgt={}, reason={}",
                original.getTransactionRef(), amount, debitAccNo, creditAccNo, reason);

        // Create reversal subledger entries for BOTH accounts
        String creditReversalRef = ReferenceGenerator.generateTransactionRef();
        buildTxn(creditAcct, amount, "REVERSAL", "DEBIT", businessDate,
                "Transfer reversal debit: " + original.getTransactionRef() + " — " + reason,
                r, creditReversalRef, null, "INTERNAL", debitAccNo);

        return buildTxn(debitAcct, amount, "REVERSAL", "CREDIT", businessDate,
                "Transfer reversal credit: " + original.getTransactionRef() + " — " + reason,
                r, r.getTransactionRef(), null, "INTERNAL", creditAccNo);
    }
}

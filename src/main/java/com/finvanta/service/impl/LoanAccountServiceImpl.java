package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.accounting.SuspenseService;
import com.finvanta.audit.AuditService;
import com.finvanta.batch.ChargeEngine;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DisbursementSchedule;
import com.finvanta.domain.entity.InterestAccrual;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.domain.enums.*;
import com.finvanta.domain.rules.InterestCalculationRule;
import com.finvanta.domain.rules.NpaClassificationRule;
import com.finvanta.repository.CollateralRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.DisbursementScheduleRepository;
import com.finvanta.repository.InterestAccrualRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CollateralService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanScheduleService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Loan Account Service — Core loan lifecycle management.
 *
 * Implements the Finacle/Temenos loan account lifecycle:
 *   Account Creation → Disbursement → Interest Accrual → Repayment → NPA Classification → Closure
 *
 * Key RBI compliance features:
 * - Double-entry GL posting for all financial transactions (Ind AS compliant)
 * - Actual/365 day-count convention for interest accrual (RBI circular 2009)
 * - Income recognition stops on NPA accounts (RBI IRAC Master Circular)
 * - Pessimistic locking on account mutations to prevent concurrent modification
 * - Full audit trail via AuditService for every state change
 * - Product-aware GL resolution via {@link ProductGLResolver} (Finacle PDDEF pattern)
 * - Per-role transaction limits enforced by TransactionEngine (RBI internal controls)
 * - Idempotency key support on client-initiated transactions (Finacle UNIQUE.REF pattern)
 *
 * GL codes are resolved through product_master configuration via {@link ProductGLResolver}.
 * Falls back to default GL constants when a product is not configured (backward compatible).
 */
@Service
public class LoanAccountServiceImpl implements LoanAccountService {

    private static final Logger log = LoggerFactory.getLogger(LoanAccountServiceImpl.class);

    private final LoanAccountRepository accountRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanTransactionRepository transactionRepository;
    private final InterestAccrualRepository accrualRepository;
    private final DisbursementScheduleRepository disbursementScheduleRepository;
    private final CollateralRepository collateralRepository;
    private final InterestCalculationRule interestRule;
    private final NpaClassificationRule npaRule;
    private final AuditService auditService;
    private final SuspenseService suspenseService;
    private final LoanScheduleService scheduleService;
    private final BusinessDateService businessDateService;
    private final ProductGLResolver glResolver;
    private final TransactionEngine transactionEngine;
    private final ChargeEngine chargeEngine;
    private final DepositAccountRepository depositAccountRepository;
    private final DepositAccountService depositAccountService;
    private final StandingInstructionServiceImpl standingInstructionService;
    private final BranchAccessValidator branchAccessValidator;
    private final CollateralService collateralService;

    public LoanAccountServiceImpl(
            LoanAccountRepository accountRepository,
            LoanApplicationRepository applicationRepository,
            LoanTransactionRepository transactionRepository,
            InterestAccrualRepository accrualRepository,
            DisbursementScheduleRepository disbursementScheduleRepository,
            CollateralRepository collateralRepository,
            InterestCalculationRule interestRule,
            NpaClassificationRule npaRule,
            AuditService auditService,
            SuspenseService suspenseService,
            LoanScheduleService scheduleService,
            BusinessDateService businessDateService,
            ProductGLResolver glResolver,
            TransactionEngine transactionEngine,
            ChargeEngine chargeEngine,
            DepositAccountRepository depositAccountRepository,
            DepositAccountService depositAccountService,
            @Lazy StandingInstructionServiceImpl standingInstructionService,
            BranchAccessValidator branchAccessValidator,
            CollateralService collateralService) {
        this.accountRepository = accountRepository;
        this.applicationRepository = applicationRepository;
        this.transactionRepository = transactionRepository;
        this.accrualRepository = accrualRepository;
        this.disbursementScheduleRepository = disbursementScheduleRepository;
        this.collateralRepository = collateralRepository;
        this.interestRule = interestRule;
        this.npaRule = npaRule;
        this.auditService = auditService;
        this.suspenseService = suspenseService;
        this.scheduleService = scheduleService;
        this.businessDateService = businessDateService;
        this.glResolver = glResolver;
        this.transactionEngine = transactionEngine;
        this.chargeEngine = chargeEngine;
        this.depositAccountRepository = depositAccountRepository;
        this.depositAccountService = depositAccountService;
        this.standingInstructionService = standingInstructionService;
        this.branchAccessValidator = branchAccessValidator;
        this.collateralService = collateralService;
    }

    @Override
    @Transactional
    public LoanAccount createLoanAccount(Long applicationId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanApplication application = applicationRepository
                .findById(applicationId)
                .filter(a -> a.getTenantId().equals(tenantId))
                .orElseThrow(() ->
                        new BusinessException("APPLICATION_NOT_FOUND", "Loan application not found: " + applicationId));

        if (application.getStatus() != ApplicationStatus.APPROVED) {
            throw new BusinessException(
                    "APPLICATION_NOT_APPROVED",
                    "Application must be in APPROVED state. Current: " + application.getStatus());
        }

        // CBS idempotency: prevent duplicate account creation for the same application
        if (accountRepository.existsByTenantIdAndApplicationId(tenantId, applicationId)) {
            throw new BusinessException(
                    "ACCOUNT_ALREADY_EXISTS", "Loan account already exists for application: " + applicationId);
        }

        // CBS: Resolve product defaults from ProductMaster per Finacle PDDEF
        // If product is configured, use product-level defaults for penal rate, currency, etc.
        // If not configured, fall back to application-level or system defaults.
        var product = glResolver.getProduct(application.getProductType());

        LoanAccount account = new LoanAccount();
        account.setTenantId(tenantId);
        account.setAccountNumber(
                ReferenceGenerator.generateAccountNumber(application.getBranch().getBranchCode()));
        account.setApplication(application);
        account.setCustomer(application.getCustomer());
        account.setBranch(application.getBranch());
        account.setProductType(application.getProductType());
        account.setCurrencyCode(product != null ? product.getCurrencyCode() : "INR");
        account.setSanctionedAmount(application.getApprovedAmount());
        account.setInterestRate(application.getInterestRate());
        account.setPenalRate(
                application.getPenalRate() != null
                        ? application.getPenalRate()
                        : (product != null && product.getDefaultPenalRate() != null
                                ? product.getDefaultPenalRate()
                                : BigDecimal.valueOf(2))); // RBI default 2% penal
        account.setRepaymentFrequency(product != null ? product.getRepaymentFrequency() : "MONTHLY");
        account.setTenureMonths(application.getTenureMonths());
        account.setRemainingTenure(application.getTenureMonths());
        account.setCollateralReference(application.getCollateralReference());
        account.setRiskCategory(application.getRiskCategory() != null ? application.getRiskCategory() : "MEDIUM");
        // CBS: Copy disbursement CASA account from application per Finacle DISB_MASTER.
        // Per Tier-1 CBS: loan proceeds credit the borrower's operating account.
        // Validated at disbursement time (account must be ACTIVE, same CIF).
        account.setDisbursementAccountNumber(application.getDisbursementAccountNumber());
        account.setStatus(LoanStatus.ACTIVE);
        account.setCreatedBy(currentUser);

        BigDecimal emi = interestRule.calculateEmi(
                application.getApprovedAmount(), application.getInterestRate(), application.getTenureMonths());
        account.setEmiAmount(emi);

        LoanAccount saved = accountRepository.save(account);

        // CBS: Link collaterals from the application to the newly created account.
        // Per Finacle COLMAS, collateral must be linked to the loan account for
        // account-lifecycle operations (lien management, revaluation, release on closure).
        try {
            var collaterals = collateralRepository.findByTenantIdAndLoanApplicationId(tenantId, applicationId);
            for (var collateral : collaterals) {
                collateral.setLoanAccount(saved);
                collateral.setUpdatedBy(currentUser);
                collateralRepository.save(collateral);
            }
            if (!collaterals.isEmpty()) {
                log.info("Linked {} collateral(s) to account {}", collaterals.size(), saved.getAccountNumber());
            }
        } catch (Exception e) {
            log.warn("Collateral linkage failed for {}: {}", saved.getAccountNumber(), e.getMessage());
        }

        auditService.logEvent(
                "LoanAccount",
                saved.getId(),
                "CREATE",
                null,
                saved.getAccountNumber(),
                "LOAN_ACCOUNTS",
                "Loan account created: " + saved.getAccountNumber() + ", EMI: " + emi);

        log.info(
                "Loan account created: accNo={}, sanctioned={}, emi={}",
                saved.getAccountNumber(),
                saved.getSanctionedAmount(),
                emi);

        return saved;
    }

    /**
     * CBS Disbursement per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT.
     *
     * Supports three disbursement modes (configured per product):
     *   SINGLE       - Full sanctioned amount in one shot (Term Loan, Gold Loan)
     *   MULTI_TRANCHE - Stage-wise linked to milestones (Home Loan, Construction)
     *   DRAWDOWN     - Multiple draws within limit (Working Capital, OD)
     *
     * For SINGLE mode: disburseLoan() disburses full amount (backward compatible).
     * For MULTI_TRANCHE: use disburseTranche() with specific amount per milestone.
     *
     * Per RBI Housing Finance guidelines:
     *   - Disbursement linked to construction progress
     *   - Interest charged only on disbursed amount
     *   - EMI calculated on disbursed amount after full disbursement
     */
    @Override
    @Transactional
    public LoanAccount disburseLoan(String accountNumber) {
        return disburseTranche(accountNumber, null, null);
    }

    /**
     * CBS Tranche Disbursement for multi-disbursement products.
     *
     * @param accountNumber Loan account number
     * @param trancheAmount Amount to disburse (null = full remaining)
     * @param narration     Tranche narration (null = auto-generated)
     * @return Updated loan account
     */
    @Transactional
    public LoanAccount disburseTranche(String accountNumber, BigDecimal trancheAmount, String narration) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.isFullyDisbursed()) {
            throw new BusinessException(
                    "FULLY_DISBURSED", "Loan is already fully disbursed. Total: INR " + account.getDisbursedAmount());
        }

        if ("SINGLE".equals(account.getDisbursementMode())
                && account.getDisbursedAmount().signum() > 0) {
            throw new BusinessException("ALREADY_DISBURSED", "Single-disbursement loan has already been disbursed");
        }

        // Resolve disbursement amount
        BigDecimal disbursementAmount =
                (trancheAmount != null && trancheAmount.signum() > 0) ? trancheAmount : account.getUndisbursedAmount();

        if (disbursementAmount.compareTo(account.getUndisbursedAmount()) > 0) {
            throw new BusinessException(
                    "DISBURSEMENT_EXCEEDS_SANCTIONED",
                    "Disbursement INR " + disbursementAmount + " exceeds undisbursed balance INR "
                            + account.getUndisbursedAmount());
        }

        if (disbursementAmount.signum() <= 0) {
            throw new BusinessException("INVALID_DISBURSEMENT_AMOUNT", "Disbursement amount must be positive");
        }

        LocalDate bizDate = businessDateService.getCurrentBusinessDate();
        String productType = account.getProductType();
        boolean isFirstTranche = account.getDisbursedAmount().signum() == 0;
        int trancheNum = (account.getTranchesDisbursed() != null ? account.getTranchesDisbursed() : 0) + 1;

        String txnNarration = (narration != null && !narration.isBlank())
                ? narration
                : (account.isMultiDisbursement()
                        ? "Tranche " + trancheNum + " disbursement for " + accountNumber
                        : "Loan disbursement for account " + accountNumber);

        // ====================================================================
        // CBS CASA-Linked Disbursement per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT
        //
        // Per Tier-1 CBS (Finacle/Temenos/BNP) and RBI KYC/AML guidelines:
        //   - Loan disbursement MUST credit the borrower's operating CASA account
        //   - GL flow (2-step via Bank Ops bridge):
        //     Step 1 (Loan side): DR Loan Asset (1001) / CR Bank Ops (1100)
        //     Step 2 (CASA side): DR Bank Ops (1100) / CR Customer Deposits (2010/2020)
        //   - Bank Ops GL (1100) acts as the settlement bridge between modules
        //   - CASA subledger (ledger_balance, available_balance) updated atomically
        //   - DepositTransaction record created for CASA mini-statement
        //   - Validation: CASA account must be ACTIVE, same CIF, same tenant
        //
        // The loan-side GL ALWAYS credits Bank Ops (1100). When CASA is linked,
        // depositAccountService.deposit() posts the CASA-side GL (DR Bank Ops / CR Deposits)
        // and updates the CASA subledger. This avoids double-crediting Customer Deposits.
        //
        // Fallback: If no CASA account is linked (disbursementAccountNumber is null),
        // disbursement credits Bank Operations GL (1100) — cash/DD mode.
        // ====================================================================
        String creditGl;
        DepositAccount casaAccount = null;
        String disbAcctNum = account.getDisbursementAccountNumber();

        if (disbAcctNum != null && !disbAcctNum.isBlank()) {
            // CBS: Validate the CASA account at disbursement time (not just at application).
            // Per Finacle DISB_MASTER: account can become FROZEN/CLOSED between approval and disbursement.
            casaAccount = depositAccountRepository
                    .findByTenantIdAndAccountNumber(tenantId, disbAcctNum)
                    .orElseThrow(() -> new BusinessException(
                            "CASA_ACCOUNT_NOT_FOUND",
                            "Disbursement CASA account not found: " + disbAcctNum
                                    + ". Link a valid CASA account before disbursement."));

            // RBI KYC/AML: CASA account must belong to the same customer (CIF linkage).
            // Per RBI: retail loan disbursement to third-party accounts is prohibited.
            if (!casaAccount.getCustomer().getId().equals(account.getCustomer().getId())) {
                throw new BusinessException(
                        "CASA_CIF_MISMATCH",
                        "Disbursement account " + disbAcctNum + " belongs to customer "
                                + casaAccount.getCustomer().getCustomerNumber()
                                + " but loan belongs to "
                                + account.getCustomer().getCustomerNumber()
                                + ". Per RBI KYC/AML, loan proceeds must credit the borrower's own account.");
            }

            // CBS: CASA account must be ACTIVE to receive credit.
            if (!casaAccount.isCreditAllowed()) {
                throw new BusinessException(
                        "CASA_NOT_CREDITABLE",
                        "Disbursement account " + disbAcctNum + " is not creditable (status: "
                                + casaAccount.getAccountStatus()
                                + "). Account must be ACTIVE to receive loan disbursement.");
            }

            // CBS: Loan-side GL always credits Bank Ops (bridge GL).
            // The CASA-side GL (DR Bank Ops / CR Customer Deposits) is posted by
            // depositAccountService.deposit() below, which also updates the CASA subledger.
            // This 2-step approach prevents double-crediting Customer Deposits GL.
            creditGl = glResolver.getBankOperationsGL(productType);
            log.info("CASA-linked disbursement: loan={}, casa={}, bridgeGl={}", accountNumber, disbAcctNum, creditGl);
        } else {
            // Fallback: No CASA linked — credit Bank Operations GL (cash/DD mode)
            creditGl = glResolver.getBankOperationsGL(productType);
            log.info("Cash/DD disbursement: loan={}, gl={} (no CASA linked)", accountNumber, creditGl);
        }

        // CBS: ALL financial postings go through TransactionEngine
        List<JournalLineRequest> journalLines = List.of(
                new JournalLineRequest(
                        glResolver.getLoanAssetGL(productType),
                        DebitCredit.DEBIT,
                        disbursementAmount,
                        "Disbursement T" + trancheNum + " - " + accountNumber),
                new JournalLineRequest(
                        creditGl,
                        DebitCredit.CREDIT,
                        disbursementAmount,
                        (casaAccount != null ? "Credit CASA " + disbAcctNum : "Bank credit") + " T" + trancheNum + " - "
                                + accountNumber));

        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("DISBURSEMENT")
                .accountReference(accountNumber)
                .amount(disbursementAmount)
                .valueDate(bizDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration(txnNarration)
                .journalLines(journalLines)
                .build());

        // CBS: Update CASA subledger atomically within the same @Transactional boundary.
        // Per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT:
        //   - CASA ledger_balance increased by disbursement amount
        //   - CASA available_balance recomputed
        //   - DepositTransaction (LOAN_DISBURSEMENT) created for CASA mini-statement
        //   - CASA last_transaction_date updated for dormancy tracking
        // This ensures GL and subledger stay in perfect sync — any rollback reverses both.
        if (casaAccount != null) {
            depositAccountService.deposit(
                    disbAcctNum,
                    disbursementAmount,
                    bizDate,
                    "Loan disbursement " + accountNumber + " T" + trancheNum + " | Voucher: "
                            + txnResult.getVoucherNumber(),
                    "DISB-" + txnResult.getTransactionRef(), // Idempotency key prevents double-credit on retry
                    "LOAN_DISBURSEMENT");
            log.info("CASA credited: casa={}, amount={}, loan={}", disbAcctNum, disbursementAmount, accountNumber);
        }

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.DISBURSEMENT);
        txn.setAmount(disbursementAmount);
        txn.setPrincipalComponent(disbursementAmount);
        txn.setValueDate(bizDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(account.getDisbursedAmount().add(disbursementAmount));
        txn.setNarration(txnNarration + " | Voucher: " + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setVoucherNumber(txnResult.getVoucherNumber());
        txn.setCreatedBy(currentUser);
        transactionRepository.save(txn);

        // CBS: Create DisbursementSchedule record per Finacle DISB_MASTER.
        // Each tranche disbursement is tracked with milestone, approval, and GL references.
        // This enables audit-grade tracking of multi-tranche disbursement progress.
        DisbursementSchedule disbSchedule = new DisbursementSchedule();
        disbSchedule.setTenantId(tenantId);
        disbSchedule.setLoanAccount(account);
        disbSchedule.setTrancheNumber(trancheNum);
        disbSchedule.setTrancheAmount(disbursementAmount);
        disbSchedule.setTranchePercentage(disbursementAmount
                .multiply(new BigDecimal("100"))
                .divide(account.getSanctionedAmount(), 2, java.math.RoundingMode.HALF_UP));
        disbSchedule.setMilestoneDescription(txnNarration);
        disbSchedule.setActualDate(bizDate);
        disbSchedule.setStatus("DISBURSED");
        disbSchedule.setApprovedBy(currentUser);
        disbSchedule.setApprovedDate(bizDate);
        disbSchedule.setTransactionRef(txnResult.getTransactionRef());
        disbSchedule.setVoucherNumber(txnResult.getVoucherNumber());
        disbSchedule.setCreatedBy(currentUser);
        disbursementScheduleRepository.save(disbSchedule);

        // Update account balances
        account.setDisbursedAmount(account.getDisbursedAmount().add(disbursementAmount));
        account.setOutstandingPrincipal(account.getOutstandingPrincipal().add(disbursementAmount));
        account.setTranchesDisbursed(trancheNum);
        account.setUpdatedBy(currentUser);

        if (isFirstTranche) {
            account.setDisbursementDate(bizDate);
            account.setLastInterestAccrualDate(bizDate);
        }

        // Check if fully disbursed
        boolean nowFullyDisbursed = account.getUndisbursedAmount().signum() == 0;
        if (nowFullyDisbursed || "SINGLE".equals(account.getDisbursementMode())) {
            account.setFullyDisbursed(true);
            account.setNextEmiDate(bizDate.plusMonths(1));
            account.setMaturityDate(bizDate.plusMonths(account.getTenureMonths()));

            // Recalculate EMI on actual disbursed amount
            BigDecimal emi = interestRule.calculateEmi(
                    account.getDisbursedAmount(), account.getInterestRate(), account.getTenureMonths());
            account.setEmiAmount(emi);

            LoanApplication application = account.getApplication();
            application.setStatus(ApplicationStatus.DISBURSED);
            application.setUpdatedBy(currentUser);
            applicationRepository.save(application);

            try {
                scheduleService.generateSchedule(account, bizDate);
            } catch (Exception e) {
                log.warn("Schedule generation skipped: {}", e.getMessage());
            }

            // CBS: Auto-create Standing Instruction for EMI auto-debit per Finacle SI_MASTER.
            // Per Tier-1 CBS (Finacle/Temenos/BNP): when a CASA-linked loan is fully disbursed,
            // a LOAN_EMI Standing Instruction is automatically registered to collect EMI from
            // the borrower's CASA account on the due date. The SI:
            //   - Source: borrower's CASA (disbursementAccountNumber)
            //   - Amount: dynamic from LoanAccount.emiAmount (changes after restructuring)
            //   - Frequency: MONTHLY (aligned with repaymentFrequency)
            //   - First execution: nextEmiDate
            //   - End date: maturityDate (auto-expires when loan closes)
            // Per RBI Payment Systems Act 2007: SI requires customer mandate (implicit at
            // loan sanction — the loan agreement includes auto-debit authorization clause).
            try {
                standingInstructionService.createLoanEmiSI(account, bizDate);
            } catch (Exception e) {
                // SI creation failure must NOT block disbursement (best-effort).
                // Operations can manually create SI later via SI management screen.
                log.warn("SI auto-creation failed for loan {}: {}", accountNumber, e.getMessage());
            }
        }

        LoanAccount saved = accountRepository.save(account);

        auditService.logEvent(
                "LoanAccount",
                saved.getId(),
                "DISBURSE",
                null,
                saved.getAccountNumber(),
                "LOAN_ACCOUNTS",
                "Disbursement: INR " + disbursementAmount
                        + " (Tranche " + trancheNum + ")"
                        + " | Total: INR " + saved.getDisbursedAmount()
                        + " | Remaining: INR " + saved.getUndisbursedAmount()
                        + " | Fully disbursed: " + saved.isFullyDisbursed()
                        + " | Voucher: " + txnResult.getVoucherNumber());

        log.info(
                "Disbursement: accNo={}, tranche={}, amount={}, total={}, remaining={}, fullyDisbursed={}",
                accountNumber,
                trancheNum,
                disbursementAmount,
                saved.getDisbursedAmount(),
                saved.getUndisbursedAmount(),
                saved.isFullyDisbursed());

        return saved;
    }

    @Override
    @Transactional
    public LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            return null;
        }

        // RBI IRAC: Income recognition must stop when account becomes NPA.
        // Interest on NPA accounts is tracked in a memorandum (suspense) account,
        // not recognized as income in P&L. Per RBI Master Circular on IRAC Norms,
        // interest accrued on NPA accounts must be reversed and not taken to income.
        if (account.getStatus().isIncomeReversalRequired()) {
            log.debug(
                    "Interest accrual skipped for NPA account: accNo={}, status={}",
                    accountNumber,
                    account.getStatus());
            return null;
        }

        LocalDate fromDate = account.getLastInterestAccrualDate() != null
                ? account.getLastInterestAccrualDate()
                : account.getDisbursementDate();

        if (fromDate == null || !accrualDate.isAfter(fromDate)) {
            return null;
        }

        BigDecimal accruedAmount = interestRule.calculateDailyAccrual(account, fromDate, accrualDate);

        if (accruedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = List.of(
                new JournalLineRequest(
                        glResolver.getInterestReceivableGL(productType),
                        DebitCredit.DEBIT,
                        accruedAmount,
                        "Interest accrual - " + accountNumber),
                new JournalLineRequest(
                        glResolver.getInterestIncomeGL(productType),
                        DebitCredit.CREDIT,
                        accruedAmount,
                        "Interest income accrual - " + accountNumber));

        // CBS: EOD system operations route through TransactionEngine with systemGenerated(true).
        // This ensures uniform voucher generation, audit trail, and business date validation
        // while skipping user-level transaction limits and maker-checker gates.
        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("INTEREST_ACCRUAL")
                .accountReference(accountNumber)
                .amount(accruedAmount)
                .valueDate(accrualDate)
                .branchCode(account.getBranch() != null ? account.getBranch().getBranchCode() : null)
                .productType(productType)
                .narration("Interest accrual for " + accountNumber)
                .journalLines(journalLines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build());

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.INTEREST_ACCRUAL);
        txn.setAmount(accruedAmount);
        txn.setInterestComponent(accruedAmount);
        txn.setValueDate(accrualDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(account.getTotalOutstanding().add(accruedAmount));
        txn.setNarration("Daily interest accrual | Voucher: " + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setVoucherNumber(txnResult.getVoucherNumber());
        txn.setCreatedBy("SYSTEM");
        LoanTransaction savedTxn = transactionRepository.save(txn);

        account.setAccruedInterest(account.getAccruedInterest().add(accruedAmount));
        account.setLastInterestAccrualDate(accrualDate);
        account.setUpdatedBy("SYSTEM");
        accountRepository.save(account);

        // P0-2: Insert interest accrual record for audit-grade per-day tracking.
        // This enables deterministic replay: any date range can be recalculated from this table.
        // Per RBI audit requirements: all financial calculations must be logged and reproducible.
        InterestAccrual accrual = new InterestAccrual();
        accrual.setTenantId(tenantId);
        accrual.setAccountId(account.getId());
        accrual.setAccrualDate(accrualDate);
        accrual.setPrincipalBase(account.getOutstandingPrincipal());
        accrual.setRateApplied(account.getInterestRate());
        accrual.setDaysCount(1);
        accrual.setAccruedAmount(accruedAmount);
        accrual.setAccrualType("REGULAR");
        accrual.setPostedFlag(true);
        accrual.setPostingDate(accrualDate);
        accrual.setJournalEntryId(txnResult.getJournalEntryId());
        accrual.setTransactionRef(txnResult.getTransactionRef());
        accrual.setBusinessDate(accrualDate);
        accrual.setCreatedBy("SYSTEM");
        accrualRepository.save(accrual);

        log.debug("Interest accrued: accNo={}, amount={}, date={}", accountNumber, accruedAmount, accrualDate);

        return savedTxn;
    }

    @Override
    @Transactional
    public LoanTransaction applyPenalInterest(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            return null;
        }

        // Penal interest only applies to overdue accounts (DPD > 0)
        if (account.getDaysPastDue() <= 0 || account.getOverduePrincipal().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // CBS: Use independent penal accrual date tracker.
        // Regular interest accrual runs before penal in EOD and advances lastInterestAccrualDate
        // to the business date. If we used that same field, days=0 and penal is never calculated.
        // Per Finacle penal interest module, penal has its own accrual date lifecycle.
        // Fallback to nextEmiDate (the overdue trigger date) if penal has never been accrued.
        LocalDate fromDate = account.getLastPenalAccrualDate() != null
                ? account.getLastPenalAccrualDate()
                : account.getNextEmiDate();

        if (fromDate == null || !businessDate.isAfter(fromDate)) {
            return null;
        }

        BigDecimal penalAmount = interestRule.calculatePenalInterest(account, fromDate, businessDate);

        if (penalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // GL Entry: DR Interest Receivable / CR Penal Interest Income
        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = List.of(
                new JournalLineRequest(
                        glResolver.getInterestReceivableGL(productType),
                        DebitCredit.DEBIT,
                        penalAmount,
                        "Penal interest accrual - " + accountNumber),
                new JournalLineRequest(
                        glResolver.getPenalIncomeGL(productType),
                        DebitCredit.CREDIT,
                        penalAmount,
                        "Penal interest income - " + accountNumber));

        // CBS: EOD system operations route through TransactionEngine with systemGenerated(true).
        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("PENALTY_CHARGE")
                .accountReference(accountNumber)
                .amount(penalAmount)
                .valueDate(businessDate)
                .branchCode(account.getBranch() != null ? account.getBranch().getBranchCode() : null)
                .productType(productType)
                .narration("RBI penal interest for overdue account " + accountNumber)
                .journalLines(journalLines)
                .systemGenerated(true)
                .initiatedBy("SYSTEM")
                .build());

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.PENALTY_CHARGE);
        txn.setAmount(penalAmount);
        txn.setPenaltyComponent(penalAmount);
        txn.setValueDate(businessDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(account.getTotalOutstanding().add(penalAmount));
        txn.setNarration("Penal interest: " + penalAmount + " on overdue principal " + account.getOverduePrincipal()
                + " | Voucher: " + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setVoucherNumber(txnResult.getVoucherNumber());
        txn.setCreatedBy("SYSTEM");
        LoanTransaction savedTxn = transactionRepository.save(txn);

        account.setPenalInterestAccrued(account.getPenalInterestAccrued().add(penalAmount));
        account.setLastPenalAccrualDate(businessDate);
        account.setUpdatedBy("SYSTEM");
        accountRepository.save(account);

        // P0-2: Insert penal interest accrual record for audit-grade tracking.
        // Penal interest accruals are tracked separately (accrual_type='PENAL') for reporting.
        InterestAccrual accrual = new InterestAccrual();
        accrual.setTenantId(tenantId);
        accrual.setAccountId(account.getId());
        accrual.setAccrualDate(businessDate);
        accrual.setPrincipalBase(account.getOverduePrincipal());
        accrual.setRateApplied(account.getPenalRate());
        accrual.setDaysCount(1);
        accrual.setAccruedAmount(penalAmount);
        accrual.setAccrualType("PENAL");
        accrual.setPostedFlag(true);
        accrual.setPostingDate(businessDate);
        accrual.setJournalEntryId(txnResult.getJournalEntryId());
        accrual.setTransactionRef(txnResult.getTransactionRef());
        accrual.setBusinessDate(businessDate);
        accrual.setCreatedBy("SYSTEM");
        accrualRepository.save(accrual);

        log.info(
                "Penal interest accrued: accNo={}, amount={}, dpd={}, overduePrincipal={}",
                accountNumber,
                penalAmount,
                account.getDaysPastDue(),
                account.getOverduePrincipal());

        return savedTxn;
    }

    @Override
    @Transactional
    public LoanTransaction processRepayment(String accountNumber, BigDecimal amount, LocalDate valueDate) {
        return processRepayment(accountNumber, amount, valueDate, null);
    }

    /**
     * CBS Repayment with idempotency key support per Finacle UNIQUE.REF pattern.
     *
     * If idempotencyKey is non-null and a transaction with that key already exists,
     * the existing transaction is returned without re-processing. This prevents
     * duplicate repayments on network retries (e.g., user double-clicks submit,
     * or load balancer retries a timed-out request).
     *
     * @param accountNumber Loan account number
     * @param amount        Repayment amount
     * @param valueDate     CBS business date
     * @param idempotencyKey Client-supplied unique key (null = no idempotency protection)
     * @return Transaction record (existing if idempotent retry, new otherwise)
     */
    @Override
    @Transactional
    public LoanTransaction processRepayment(
            String accountNumber, BigDecimal amount, LocalDate valueDate, String idempotencyKey) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // CBS Idempotency: check for existing transaction with same key
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info(
                        "Idempotent repayment detected: key={}, existingRef={}",
                        idempotencyKey,
                        existing.get().getTransactionRef());
                return existing.get();
            }
        }

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException(
                    "ACCOUNT_CLOSED", "Cannot process repayment on " + account.getStatus() + " account");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Repayment amount must be positive");
        }

        BigDecimal[] components =
                interestRule.splitEmiComponents(amount, account.getOutstandingPrincipal(), account.getInterestRate());
        BigDecimal principalPaid = components[0];
        BigDecimal interestPaid = components[1];

        // CBS: ALL financial postings go through TransactionEngine — the single enforcement point.
        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = new java.util.ArrayList<>();
        journalLines.add(new JournalLineRequest(
                glResolver.getBankOperationsGL(productType),
                DebitCredit.DEBIT,
                amount,
                "Loan repayment received - " + accountNumber));
        if (principalPaid.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(
                    glResolver.getLoanAssetGL(productType),
                    DebitCredit.CREDIT,
                    principalPaid,
                    "Principal repayment - " + accountNumber));
        }
        if (interestPaid.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(
                    glResolver.getInterestReceivableGL(productType),
                    DebitCredit.CREDIT,
                    interestPaid,
                    "Interest repayment - " + accountNumber));
        }

        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("REPAYMENT")
                .accountReference(accountNumber)
                .amount(amount)
                .valueDate(valueDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration("Loan repayment for " + accountNumber)
                .journalLines(journalLines)
                .build());

        account.setOutstandingPrincipal(
                account.getOutstandingPrincipal().subtract(principalPaid).max(BigDecimal.ZERO));
        account.setOutstandingInterest(
                account.getOutstandingInterest().subtract(interestPaid).max(BigDecimal.ZERO));
        account.setAccruedInterest(
                account.getAccruedInterest().subtract(interestPaid).max(BigDecimal.ZERO));
        account.setLastPaymentDate(valueDate);

        // RBI IRAC: When interest is collected on an NPA account, release from suspense to income.
        // Per RBI Master Circular: "Interest on NPA accounts, if collected, should be taken to
        // income account only on realization basis."
        // GL Entry: DR Interest Suspense (2100) / CR Interest Income (4001)
        if (account.getStatus().isNpa() && interestPaid.compareTo(BigDecimal.ZERO) > 0) {
            try {
                suspenseService.releaseFromSuspense(account, interestPaid, valueDate);
            } catch (Exception e) {
                log.warn("Suspense release failed for NPA repayment {}: {}", accountNumber, e.getMessage());
            }
        }

        // RBI IRAC: DPD resets only when ALL overdue installments are cleared.
        // Per RBI Master Circular on IRAC Norms: "An account should be treated as
        // 'out of order' if the outstanding balance remains continuously in excess
        // of the sanctioned limit/drawing power for 90 days."
        //
        // Use the amortization schedule as the source of truth: after FIFO payment
        // allocation (done below via scheduleService), check if any installments
        // are still overdue. DPD is recalculated from the oldest unpaid installment.
        // The naive "payment >= 1 EMI → DPD=0" was incorrect for multi-EMI arrears.
        //
        // NOTE: DPD is also recalculated in EOD batch (updateDaysPastDue), so this
        // is a best-effort intra-day update. EOD is the authoritative DPD calculation.
        account.setUpdatedBy(currentUser);

        // CBS: Remaining tenure and next EMI date advance only when a full EMI-equivalent
        // payment is made. Partial payments (amount < EMI) should not advance the schedule.
        // Per Finacle/Temenos loan repayment module, tenure decrement is tied to the
        // installment schedule, not to individual payment events. Two partial payments
        // in the same month should not decrement tenure twice.
        if (account.getEmiAmount() != null && amount.compareTo(account.getEmiAmount()) >= 0) {
            if (account.getRemainingTenure() != null && account.getRemainingTenure() > 0) {
                account.setRemainingTenure(account.getRemainingTenure() - 1);
            }
            if (account.getNextEmiDate() != null) {
                account.setNextEmiDate(account.getNextEmiDate().plusMonths(1));
            }
        }

        // CBS: Loan closure only when all components are zero (principal + interest + penal)
        if (account.getTotalOutstanding().compareTo(BigDecimal.ZERO) == 0) {
            account.setStatus(LoanStatus.CLOSED);
            // CBS Tier-1: Auto-release collateral liens per RBI Fair Practices Code 2023.
            // Bank must release pledge/mortgage within 30 days of loan closure.
            try {
                collateralService.releaseCollateralsForLoan(account.getId(), valueDate);
            } catch (Exception e) {
                log.warn("Collateral release failed for {}: {}", accountNumber, e.getMessage());
            }
            log.info("Loan account closed: accNo={}", accountNumber);
        }

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.REPAYMENT_PRINCIPAL);
        txn.setAmount(amount);
        txn.setPrincipalComponent(principalPaid);
        txn.setInterestComponent(interestPaid);
        txn.setValueDate(valueDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(account.getTotalOutstanding());
        txn.setNarration("EMI repayment - P:" + principalPaid + " I:" + interestPaid + " | Voucher: "
                + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setVoucherNumber(txnResult.getVoucherNumber());
        txn.setIdempotencyKey(idempotencyKey);
        txn.setCreatedBy(currentUser);
        LoanTransaction savedTxn = transactionRepository.save(txn);

        accountRepository.save(account);

        // CBS: Update amortization schedule — allocate payment to oldest unpaid installment (FIFO)
        try {
            scheduleService.updateInstallmentsOnPayment(account.getId(), amount, valueDate);
        } catch (Exception e) {
            log.warn("Schedule update failed for repayment {}: {}", accountNumber, e.getMessage());
        }

        // NOTE: Financial posting audit is handled by TransactionEngine (Step 10).
        // Module-level audit for subledger state change:
        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "REPAYMENT",
                null,
                savedTxn.getTransactionRef(),
                "LOAN_ACCOUNTS",
                "Repayment: " + amount + " (P:" + principalPaid + " I:" + interestPaid + ")"
                        + " | Voucher: " + txnResult.getVoucherNumber()
                        + " | Journal: " + txnResult.getJournalRef());

        log.info(
                "Repayment processed: accNo={}, amount={}, principal={}, interest={}, voucher={}, journal={}",
                accountNumber,
                amount,
                principalPaid,
                interestPaid,
                txnResult.getVoucherNumber(),
                txnResult.getJournalRef());

        return savedTxn;
    }

    @Override
    @Transactional
    public void classifyNPA(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            return;
        }

        LoanStatus previousStatus = account.getStatus();

        // CBS: NPA Upgrade Check per RBI IRAC Master Circular.
        // "An account classified as NPA may be upgraded as 'Standard' if the
        //  entire arrears of interest and principal are paid by the borrower."
        // This is an EXPLICIT upgrade — NPA never auto-downgrades via classify().
        // The upgrade resets NPA date and reverses excess provisioning (handled by
        // ProvisioningService in the next EOD step).
        if (npaRule.isEligibleForNpaUpgrade(account)) {
            account.setStatus(LoanStatus.ACTIVE);
            account.setNpaDate(null);
            account.setNpaClassificationDate(businessDate);
            account.setUpdatedBy("SYSTEM");
            accountRepository.save(account);

            auditService.logEvent(
                    "LoanAccount",
                    account.getId(),
                    "NPA_UPGRADE",
                    previousStatus.name(),
                    LoanStatus.ACTIVE.name(),
                    "LOAN_ACCOUNTS",
                    "NPA upgraded to Standard: " + previousStatus + " -> ACTIVE"
                            + " | DPD: " + account.getDaysPastDue()
                            + " | All arrears cleared");

            log.info(
                    "NPA UPGRADE: accNo={}, {} -> ACTIVE, dpd={}",
                    accountNumber,
                    previousStatus,
                    account.getDaysPastDue());
            return;
        }

        LoanStatus newStatus = npaRule.classify(account);

        if (previousStatus != newStatus) {
            account.setStatus(newStatus);
            if (newStatus.isNpa() && account.getNpaDate() == null) {
                account.setNpaDate(businessDate);
            }
            account.setNpaClassificationDate(businessDate);
            account.setUpdatedBy("SYSTEM");

            // RBI IRAC: When account transitions to NPA, reverse accrued interest to suspense.
            // Previously recognized interest income must be reversed from P&L.
            // GL Entry: DR Interest Income (4001) / CR Interest Suspense (2100)
            // NOTE: reverseInterestToSuspense modifies account.accruedInterest, so we must
            // call it BEFORE saving to avoid the double-save overwrite problem.
            if (newStatus.isNpa() && !previousStatus.isNpa()) {
                try {
                    suspenseService.reverseInterestToSuspense(account, businessDate);
                } catch (Exception e) {
                    log.warn("Suspense reversal failed for {}: {}", accountNumber, e.getMessage());
                }
            }

            accountRepository.save(account);

            auditService.logEvent(
                    "LoanAccount",
                    account.getId(),
                    "NPA_CLASSIFY",
                    previousStatus.name(),
                    newStatus.name(),
                    "LOAN_ACCOUNTS",
                    "NPA classified: " + previousStatus + " -> " + newStatus + ", DPD: " + account.getDaysPastDue());

            log.info(
                    "NPA classification: accNo={}, {} -> {}, dpd={}",
                    accountNumber,
                    previousStatus,
                    newStatus,
                    account.getDaysPastDue());
        }
    }

    @Override
    @Transactional
    public LoanAccount writeOffAccount(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException(
                    "ACCOUNT_ALREADY_TERMINAL", "Account is already in terminal state: " + account.getStatus());
        }

        if (!account.getStatus().isNpa()) {
            throw new BusinessException(
                    "ACCOUNT_NOT_NPA", "Only NPA accounts can be written off. Current status: " + account.getStatus());
        }

        BigDecimal writeOffAmount = account.getOutstandingPrincipal();
        BigDecimal interestReceivable = account.getOutstandingInterest()
                .add(account.getAccruedInterest())
                .add(account.getPenalInterestAccrued());
        BigDecimal provisionHeld = account.getProvisioningAmount();
        String productType = account.getProductType();

        // CBS: Guard against write-off with nothing to write off
        if (writeOffAmount.compareTo(BigDecimal.ZERO) <= 0 && interestReceivable.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(
                    "NOTHING_TO_WRITE_OFF",
                    "Account " + accountNumber + " has no outstanding principal or interest to write off.");
        }

        // CBS: ALL financial postings go through TransactionEngine — the single enforcement point.
        // Write-off is a compound posting (multiple balanced journals in one atomic transaction):
        //   1. DR Write-Off Expense / CR Loan Asset (principal)
        //   2. DR Write-Off Expense / CR Interest Receivable (interest — if any)
        //   3. DR Provision NPA / CR Provision Expense (provision reversal — if any)
        // TransactionEngine enforces: amount → business date → day status → branch →
        // transaction limits (WRITE_OFF) → GL posting → voucher → audit trail
        List<TransactionRequest.CompoundJournalGroup> journalGroups = new java.util.ArrayList<>();

        if (writeOffAmount.compareTo(BigDecimal.ZERO) > 0) {
            journalGroups.add(new TransactionRequest.CompoundJournalGroup(
                    "RBI IRAC write-off principal for NPA account " + accountNumber,
                    List.of(
                            new JournalLineRequest(
                                    glResolver.getWriteOffExpenseGL(productType),
                                    DebitCredit.DEBIT,
                                    writeOffAmount,
                                    "Loan write-off principal - " + accountNumber),
                            new JournalLineRequest(
                                    glResolver.getLoanAssetGL(productType),
                                    DebitCredit.CREDIT,
                                    writeOffAmount,
                                    "Write-off principal asset removal - " + accountNumber))));
        }

        if (interestReceivable.compareTo(BigDecimal.ZERO) > 0) {
            journalGroups.add(new TransactionRequest.CompoundJournalGroup(
                    "RBI IRAC write-off interest for NPA account " + accountNumber,
                    List.of(
                            new JournalLineRequest(
                                    glResolver.getWriteOffExpenseGL(productType),
                                    DebitCredit.DEBIT,
                                    interestReceivable,
                                    "Loan write-off interest - " + accountNumber),
                            new JournalLineRequest(
                                    glResolver.getInterestReceivableGL(productType),
                                    DebitCredit.CREDIT,
                                    interestReceivable,
                                    "Write-off interest receivable removal - " + accountNumber))));
        }

        if (provisionHeld.compareTo(BigDecimal.ZERO) > 0) {
            journalGroups.add(new TransactionRequest.CompoundJournalGroup(
                    "Provision reversal on write-off for " + accountNumber,
                    List.of(
                            new JournalLineRequest(
                                    glResolver.getProvisionNpaGL(productType),
                                    DebitCredit.DEBIT,
                                    provisionHeld,
                                    "Provision reversal on write-off - " + accountNumber),
                            new JournalLineRequest(
                                    glResolver.getProvisionExpenseGL(productType),
                                    DebitCredit.CREDIT,
                                    provisionHeld,
                                    "Provision expense release on write-off - " + accountNumber))));
        }

        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("WRITE_OFF")
                .transactionType("WRITE_OFF")
                .accountReference(accountNumber)
                .amount(writeOffAmount.add(interestReceivable))
                .valueDate(businessDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration("RBI IRAC NPA write-off for " + accountNumber)
                .compoundJournalGroups(journalGroups)
                .build());

        // Record write-off transaction
        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.WRITE_OFF);
        txn.setAmount(writeOffAmount.add(interestReceivable));
        txn.setPrincipalComponent(writeOffAmount);
        txn.setInterestComponent(interestReceivable);
        txn.setValueDate(businessDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(BigDecimal.ZERO);
        txn.setNarration("NPA write-off: principal=" + writeOffAmount
                + ", interest=" + interestReceivable + ", provision reversed=" + provisionHeld
                + " | Voucher: " + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setVoucherNumber(txnResult.getVoucherNumber());
        txn.setCreatedBy(currentUser);
        transactionRepository.save(txn);

        // Update account to terminal state
        LoanStatus previousStatus = account.getStatus();
        account.setStatus(LoanStatus.WRITTEN_OFF);
        account.setOutstandingPrincipal(BigDecimal.ZERO);
        account.setOutstandingInterest(BigDecimal.ZERO);
        account.setAccruedInterest(BigDecimal.ZERO);
        account.setPenalInterestAccrued(BigDecimal.ZERO);
        account.setProvisioningAmount(BigDecimal.ZERO);
        account.setUpdatedBy(currentUser);
        accountRepository.save(account);

        // CBS Tier-1: Auto-release collateral liens on write-off per RBI IRAC.
        // Written-off accounts have no further recovery expectation from collateral.
        try {
            collateralService.releaseCollateralsForLoan(account.getId(), businessDate);
        } catch (Exception e) {
            log.warn("Collateral release failed for write-off {}: {}", accountNumber, e.getMessage());
        }

        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "WRITE_OFF",
                previousStatus.name(),
                LoanStatus.WRITTEN_OFF.name(),
                "LOAN_ACCOUNTS",
                "NPA write-off: P=" + writeOffAmount + ", I=" + interestReceivable + ", provision reversed: "
                        + provisionHeld);

        log.info(
                "Loan written off: accNo={}, principal={}, interest={}, provisionReversed={}",
                accountNumber,
                writeOffAmount,
                interestReceivable,
                provisionHeld);

        return account;
    }

    @Override
    @Transactional
    public LoanTransaction processPrepayment(String accountNumber, BigDecimal amount, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException(
                    "ACCOUNT_CLOSED", "Cannot process prepayment on " + account.getStatus() + " account");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Prepayment amount must be positive");
        }

        BigDecimal totalOutstanding = account.getTotalOutstanding();
        if (amount.compareTo(totalOutstanding) < 0) {
            throw new BusinessException(
                    "PREPAYMENT_INSUFFICIENT",
                    "Prepayment amount (" + amount + ") must cover total outstanding (" + totalOutstanding
                            + "). For partial payment, use regular repayment.");
        }

        // CBS: Reject overpayment — amount must exactly match total outstanding.
        // Per RBI Fair Lending Code 2023 and Finacle foreclosure module:
        // excess funds cannot be silently absorbed. If the customer pays more than
        // totalOutstanding, the excess must be explicitly handled (refund or credit
        // to operating account). For now, enforce exact match to prevent unaccounted
        // funds. The controller pre-fills totalOutstanding as the default amount.
        if (amount.compareTo(totalOutstanding) > 0) {
            throw new BusinessException(
                    "PREPAYMENT_OVERPAYMENT",
                    "Prepayment amount (" + amount + ") exceeds total outstanding (" + totalOutstanding
                            + "). Amount must exactly match outstanding balance.");
        }

        BigDecimal principalDue = account.getOutstandingPrincipal();
        BigDecimal interestDue = account.getOutstandingInterest()
                .add(account.getAccruedInterest())
                .add(account.getPenalInterestAccrued());

        // CBS: ALL financial postings go through TransactionEngine — the single enforcement point.
        // TransactionEngine enforces: amount → business date → day status → branch →
        // transaction limits (including PREPAYMENT) → maker-checker → GL posting → voucher → audit
        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = new java.util.ArrayList<>();
        journalLines.add(new JournalLineRequest(
                glResolver.getBankOperationsGL(productType),
                DebitCredit.DEBIT,
                totalOutstanding,
                "Loan prepayment/foreclosure - " + accountNumber));
        if (principalDue.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(
                    glResolver.getLoanAssetGL(productType),
                    DebitCredit.CREDIT,
                    principalDue,
                    "Prepayment principal closure - " + accountNumber));
        }
        if (interestDue.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(
                    glResolver.getInterestReceivableGL(productType),
                    DebitCredit.CREDIT,
                    interestDue,
                    "Prepayment interest closure - " + accountNumber));
        }

        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("PREPAYMENT")
                .accountReference(accountNumber)
                .amount(totalOutstanding)
                .valueDate(businessDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration("Loan prepayment/foreclosure for " + accountNumber)
                .journalLines(journalLines)
                .build());

        // Record prepayment transaction
        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.PREPAYMENT);
        txn.setAmount(totalOutstanding);
        txn.setPrincipalComponent(principalDue);
        txn.setInterestComponent(interestDue);
        txn.setValueDate(businessDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(BigDecimal.ZERO);
        txn.setNarration("Prepayment/Foreclosure: P=" + principalDue + ", I=" + interestDue + " | Voucher: "
                + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setVoucherNumber(txnResult.getVoucherNumber());
        txn.setCreatedBy(currentUser);
        LoanTransaction savedTxn = transactionRepository.save(txn);

        // Close the account — all components to zero
        LoanStatus previousStatus = account.getStatus();
        account.setStatus(LoanStatus.CLOSED);
        account.setOutstandingPrincipal(BigDecimal.ZERO);
        account.setOutstandingInterest(BigDecimal.ZERO);
        account.setAccruedInterest(BigDecimal.ZERO);
        account.setPenalInterestAccrued(BigDecimal.ZERO);
        account.setOverduePrincipal(BigDecimal.ZERO);
        account.setOverdueInterest(BigDecimal.ZERO);
        account.setDaysPastDue(0);
        account.setLastPaymentDate(businessDate);
        account.setRemainingTenure(0);
        account.setUpdatedBy(currentUser);
        accountRepository.save(account);

        // CBS Tier-1: Auto-release collateral liens per RBI Fair Practices Code 2023.
        try {
            collateralService.releaseCollateralsForLoan(account.getId(), businessDate);
        } catch (Exception e) {
            log.warn("Collateral release failed for {}: {}", accountNumber, e.getMessage());
        }

        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "PREPAYMENT",
                previousStatus.name(),
                LoanStatus.CLOSED.name(),
                "LOAN_ACCOUNTS",
                "Prepayment/Foreclosure: " + totalOutstanding + " (P:" + principalDue + " I:" + interestDue + ")");

        log.info(
                "Prepayment processed: accNo={}, total={}, principal={}, interest={}",
                accountNumber,
                totalOutstanding,
                principalDue,
                interestDue);

        return savedTxn;
    }

    @Override
    @Transactional
    public LoanTransaction reverseTransaction(String transactionRef, String reason, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanTransaction original = transactionRepository
                .findByTenantIdAndTransactionRef(tenantId, transactionRef)
                .orElseThrow(() ->
                        new BusinessException("TRANSACTION_NOT_FOUND", "Transaction not found: " + transactionRef));

        if (original.isReversed()) {
            throw new BusinessException(
                    "ALREADY_REVERSED", "Transaction " + transactionRef + " has already been reversed");
        }

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REVERSAL_REASON_REQUIRED", "Reversal reason is mandatory per CBS audit rules");
        }

        // CBS: Acquire pessimistic lock on the loan account to prevent concurrent mutations
        // during balance restoration. Per Finacle TRAN_REVERSAL, account balances must be
        // atomically restored to the pre-transaction state within the same DB transaction.
        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(
                        tenantId, original.getLoanAccount().getAccountNumber())
                .orElseThrow(() -> new BusinessException(
                        "ACCOUNT_NOT_FOUND", "Loan account not found for transaction: " + transactionRef));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException(
                    "ACCOUNT_TERMINAL", "Cannot reverse transaction on " + account.getStatus() + " account");
        }

        // Post contra journal entry — exact reverse of original GL lines.
        // Per Finacle TRAN_REVERSAL / Temenos REVERSAL.TRANSACTION, the reversal must
        // mirror the original GL legs with swapped DR/CR. Each transaction type has
        // different GL routing, so we must build reversal lines per type.
        String productType = account.getProductType();
        BigDecimal amount = original.getAmount();

        List<JournalLineRequest> reversalLines = new java.util.ArrayList<>();

        if (original.getTransactionType() == TransactionType.REPAYMENT_PRINCIPAL
                || original.getTransactionType() == TransactionType.PREPAYMENT) {
            // Original: DR Bank Ops / CR Loan Asset + Interest Receivable
            // Reversal: CR Bank Ops / DR Loan Asset + Interest Receivable
            reversalLines.add(new JournalLineRequest(
                    glResolver.getBankOperationsGL(productType),
                    DebitCredit.CREDIT,
                    amount,
                    "REVERSAL: " + transactionRef + " - " + reason));
            if (original.getPrincipalComponent().compareTo(BigDecimal.ZERO) > 0) {
                reversalLines.add(new JournalLineRequest(
                        glResolver.getLoanAssetGL(productType),
                        DebitCredit.DEBIT,
                        original.getPrincipalComponent(),
                        "REVERSAL principal: " + transactionRef));
            }
            if (original.getInterestComponent().compareTo(BigDecimal.ZERO) > 0) {
                reversalLines.add(new JournalLineRequest(
                        glResolver.getInterestReceivableGL(productType),
                        DebitCredit.DEBIT,
                        original.getInterestComponent(),
                        "REVERSAL interest: " + transactionRef));
            }
            if (original.getPenaltyComponent().compareTo(BigDecimal.ZERO) > 0) {
                reversalLines.add(new JournalLineRequest(
                        glResolver.getInterestReceivableGL(productType),
                        DebitCredit.DEBIT,
                        original.getPenaltyComponent(),
                        "REVERSAL penalty: " + transactionRef));
            }
        } else if (original.getTransactionType() == TransactionType.DISBURSEMENT) {
            // Original: DR Loan Asset / CR Bank Ops
            // Reversal: CR Loan Asset / DR Bank Ops
            reversalLines.add(new JournalLineRequest(
                    glResolver.getLoanAssetGL(productType),
                    DebitCredit.CREDIT,
                    amount,
                    "REVERSAL disbursement asset: " + transactionRef));
            reversalLines.add(new JournalLineRequest(
                    glResolver.getBankOperationsGL(productType),
                    DebitCredit.DEBIT,
                    amount,
                    "REVERSAL disbursement bank: " + transactionRef + " - " + reason));
        } else if (original.getTransactionType() == TransactionType.INTEREST_ACCRUAL) {
            // Original: DR Interest Receivable / CR Interest Income
            // Reversal: CR Interest Receivable / DR Interest Income
            reversalLines.add(new JournalLineRequest(
                    glResolver.getInterestReceivableGL(productType),
                    DebitCredit.CREDIT,
                    amount,
                    "REVERSAL accrual receivable: " + transactionRef));
            reversalLines.add(new JournalLineRequest(
                    glResolver.getInterestIncomeGL(productType),
                    DebitCredit.DEBIT,
                    amount,
                    "REVERSAL accrual income: " + transactionRef + " - " + reason));
        } else if (original.getTransactionType() == TransactionType.PENALTY_CHARGE) {
            // Original: DR Interest Receivable / CR Penal Income
            // Reversal: CR Interest Receivable / DR Penal Income
            reversalLines.add(new JournalLineRequest(
                    glResolver.getInterestReceivableGL(productType),
                    DebitCredit.CREDIT,
                    amount,
                    "REVERSAL penal receivable: " + transactionRef));
            reversalLines.add(new JournalLineRequest(
                    glResolver.getPenalIncomeGL(productType),
                    DebitCredit.DEBIT,
                    amount,
                    "REVERSAL penal income: " + transactionRef + " - " + reason));
        } else if (original.getTransactionType() == TransactionType.FEE_CHARGE) {
            // Original: DR Bank Ops / CR Fee Income
            // Reversal: CR Bank Ops / DR Fee Income
            reversalLines.add(new JournalLineRequest(
                    glResolver.getBankOperationsGL(productType),
                    DebitCredit.CREDIT,
                    amount,
                    "REVERSAL fee bank: " + transactionRef));
            reversalLines.add(new JournalLineRequest(
                    glResolver.getFeeIncomeGL(productType),
                    DebitCredit.DEBIT,
                    amount,
                    "REVERSAL fee income: " + transactionRef + " - " + reason));
        } else {
            // Fallback for unknown types — generic reversal (best effort)
            log.warn(
                    "Reversal for unknown transaction type {}: using generic GL routing",
                    original.getTransactionType());
            reversalLines.add(new JournalLineRequest(
                    glResolver.getBankOperationsGL(productType),
                    DebitCredit.CREDIT,
                    amount,
                    "REVERSAL: " + transactionRef + " - " + reason));
            if (original.getPrincipalComponent().compareTo(BigDecimal.ZERO) > 0) {
                reversalLines.add(new JournalLineRequest(
                        glResolver.getLoanAssetGL(productType),
                        DebitCredit.DEBIT,
                        original.getPrincipalComponent(),
                        "REVERSAL principal: " + transactionRef));
            }
            if (original.getInterestComponent().compareTo(BigDecimal.ZERO) > 0) {
                reversalLines.add(new JournalLineRequest(
                        glResolver.getInterestReceivableGL(productType),
                        DebitCredit.DEBIT,
                        original.getInterestComponent(),
                        "REVERSAL interest: " + transactionRef));
            }
        }

        // CBS: ALL financial postings go through TransactionEngine — the single enforcement point.
        TransactionResult txnResult = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("REVERSAL")
                .transactionType("REVERSAL")
                .accountReference(account.getAccountNumber())
                .amount(amount)
                .valueDate(businessDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration("REVERSAL of " + transactionRef + ": " + reason)
                .journalLines(reversalLines)
                .systemGenerated(false)
                .build());

        // CBS: Restore account subsidiary ledger balances to pre-transaction state.
        // Per Finacle TRAN_REVERSAL and Temenos REVERSAL.TRANSACTION, the account's
        // running balance must be atomically restored. GL and subledger must stay in sync.
        BigDecimal principalToRestore = original.getPrincipalComponent();
        BigDecimal interestToRestore = original.getInterestComponent();
        BigDecimal penaltyToRestore = original.getPenaltyComponent();

        if (original.getTransactionType() == TransactionType.REPAYMENT_PRINCIPAL
                || original.getTransactionType() == TransactionType.PREPAYMENT) {
            // Repayment reversal: restore the amounts that were subtracted
            account.setOutstandingPrincipal(account.getOutstandingPrincipal().add(principalToRestore));
            account.setOutstandingInterest(account.getOutstandingInterest().add(interestToRestore));
            account.setAccruedInterest(account.getAccruedInterest().add(interestToRestore));
            if (penaltyToRestore.compareTo(BigDecimal.ZERO) > 0) {
                account.setPenalInterestAccrued(
                        account.getPenalInterestAccrued().add(penaltyToRestore));
            }
        } else if (original.getTransactionType() == TransactionType.DISBURSEMENT) {
            // Disbursement reversal: reduce the amounts that were added
            account.setOutstandingPrincipal(account.getOutstandingPrincipal()
                    .subtract(principalToRestore)
                    .max(BigDecimal.ZERO));
            account.setDisbursedAmount(
                    account.getDisbursedAmount().subtract(principalToRestore).max(BigDecimal.ZERO));
        } else if (original.getTransactionType() == TransactionType.INTEREST_ACCRUAL) {
            // Interest accrual reversal: reduce the accrued amount
            account.setAccruedInterest(
                    account.getAccruedInterest().subtract(interestToRestore).max(BigDecimal.ZERO));
        } else if (original.getTransactionType() == TransactionType.PENALTY_CHARGE) {
            // Penal interest reversal: reduce the penal accrued amount
            account.setPenalInterestAccrued(
                    account.getPenalInterestAccrued().subtract(penaltyToRestore).max(BigDecimal.ZERO));
        }

        account.setUpdatedBy(currentUser);
        accountRepository.save(account);

        // Create reversal transaction — balanceAfter reflects RESTORED account state
        LoanTransaction reversal = new LoanTransaction();
        reversal.setTenantId(tenantId);
        reversal.setTransactionRef(txnResult.getTransactionRef());
        reversal.setLoanAccount(account);
        reversal.setBranch(account.getBranch());
        reversal.setBranchCode(account.getBranch().getBranchCode());
        reversal.setTransactionType(TransactionType.REVERSAL);
        reversal.setAmount(amount);
        reversal.setPrincipalComponent(original.getPrincipalComponent());
        reversal.setInterestComponent(original.getInterestComponent());
        reversal.setPenaltyComponent(original.getPenaltyComponent());
        reversal.setValueDate(businessDate);
        reversal.setPostingDate(txnResult.getPostingDate());
        reversal.setBalanceAfter(account.getTotalOutstanding());
        reversal.setNarration(
                "REVERSAL of " + transactionRef + ": " + reason + " | Voucher: " + txnResult.getVoucherNumber());
        reversal.setJournalEntryId(txnResult.getJournalEntryId());
        reversal.setVoucherNumber(txnResult.getVoucherNumber());
        reversal.setReversedByRef(transactionRef);
        reversal.setCreatedBy(currentUser);
        LoanTransaction savedReversal = transactionRepository.save(reversal);

        // Mark original as reversed (never delete per CBS audit rules)
        original.setReversed(true);
        original.setReversedByRef(savedReversal.getTransactionRef());
        original.setUpdatedBy(currentUser);
        transactionRepository.save(original);

        auditService.logEvent(
                "LoanTransaction",
                original.getId(),
                "REVERSAL",
                transactionRef,
                savedReversal.getTransactionRef(),
                "LOAN_ACCOUNTS",
                "Transaction reversed: " + transactionRef + " -> " + savedReversal.getTransactionRef()
                        + ", reason: " + reason + ", P:" + principalToRestore
                        + " I:" + interestToRestore + " Pen:" + penaltyToRestore);

        log.info(
                "Transaction reversed: original={}, reversal={}, reason={}, balanceRestored={}",
                transactionRef,
                savedReversal.getTransactionRef(),
                reason,
                account.getTotalOutstanding());

        return savedReversal;
    }

    @Override
    @Transactional
    public LoanTransaction chargeFee(
            String accountNumber, BigDecimal feeAmount, String feeType, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository
                .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_CLOSED", "Cannot charge fee on " + account.getStatus() + " account");
        }

        if (feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "Fee amount must be positive");
        }

        // CBS: Delegate to ChargeEngine per P0-1 refactoring.
        // ChargeEngine.applyCharge() handles GL posting, GST calculation, and audit trail.
        // This eliminates duplicate charge logic and centralizes via Finacle CHRG_MASTER pattern.
        //
        // Per Finacle CHRG_MASTER: the baseAmount for charge calculation depends on the
        // calculation type configured in charge_config:
        //   FLAT       → baseAmount is ignored (fixed charge from config)
        //   PERCENTAGE → baseAmount = loan sanctioned amount (charge = % of loan)
        //   SLAB       → baseAmount = loan sanctioned amount (slab lookup by amount)
        // The user-entered feeAmount is used as a fallback baseAmount for FLAT charges
        // where the config determines the actual amount.
        BigDecimal chargeBaseAmount = account.getSanctionedAmount();
        ChargeEngine.ChargeResult chargeResult =
                chargeEngine.applyCharge(accountNumber, feeType, chargeBaseAmount, businessDate);

        // CBS: Use the ACTUAL calculated amount from ChargeEngine (not the user-entered feeAmount).
        // Per Finacle CHRG_MASTER: the ChargeEngine determines the real charge based on
        // FLAT/PERCENTAGE/SLAB config + min/max bounds + GST. The user-entered feeAmount
        // was only used for validation (positive check above); the GL posting uses chargeResult.
        // The LoanTransaction record MUST match what was posted to GL for reconciliation.
        BigDecimal actualChargeTotal = chargeResult.totalAmount();

        // CBS: Create loan transaction record for module-level tracking
        // This links the charge to the loan account transaction history
        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setBranch(account.getBranch());
        txn.setBranchCode(account.getBranch().getBranchCode());
        txn.setTransactionType(TransactionType.FEE_CHARGE);
        txn.setAmount(actualChargeTotal);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(account.getTotalOutstanding());
        txn.setNarration(feeType + ": INR " + actualChargeTotal
                + " (charge=" + chargeResult.chargeAmount()
                + ", GST=" + chargeResult.gstAmount() + ")");
        txn.setCreatedBy(currentUser);
        LoanTransaction savedTxn = transactionRepository.save(txn);

        auditService.logEvent(
                "LoanAccount",
                account.getId(),
                "FEE_CHARGE",
                null,
                savedTxn.getTransactionRef(),
                "LOAN_ACCOUNTS",
                feeType + ": INR " + actualChargeTotal + " for " + accountNumber + " (charge="
                        + chargeResult.chargeAmount() + ", GST=" + chargeResult.gstAmount() + ")");

        log.info(
                "Fee charged: accNo={}, type={}, actualAmount={}, charge={}, gst={}",
                accountNumber,
                feeType,
                actualChargeTotal,
                chargeResult.chargeAmount(),
                chargeResult.gstAmount());

        return savedTxn;
    }

    @Override
    public LoanAccount getAccount(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        LoanAccount account = accountRepository
                .findByTenantIdAndAccountNumber(tenantId, accountNumber)
                .orElseThrow(
                        () -> new BusinessException("ACCOUNT_NOT_FOUND", "Loan account not found: " + accountNumber));
        // CBS Tier-1: Branch access enforcement on read.
        // MAKER/CHECKER can only view loan accounts at their home branch.
        branchAccessValidator.validateAccess(account.getBranch());
        return account;
    }

    @Override
    public List<LoanAccount> getActiveAccounts() {
        return accountRepository.findAllActiveAccounts(TenantContext.getCurrentTenant());
    }
}

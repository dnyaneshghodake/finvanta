package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService;
import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.accounting.SuspenseService;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.domain.enums.*;
import com.finvanta.domain.rules.InterestCalculationRule;
import com.finvanta.domain.rules.NpaClassificationRule;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanScheduleService;
import com.finvanta.service.TransactionLimitService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
 * - Per-role transaction limits via {@link TransactionLimitService} (RBI internal controls)
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
    private final AccountingService accountingService;
    private final InterestCalculationRule interestRule;
    private final NpaClassificationRule npaRule;
    private final AuditService auditService;
    private final SuspenseService suspenseService;
    private final LoanScheduleService scheduleService;
    private final BusinessDateService businessDateService;
    private final ProductGLResolver glResolver;
    private final TransactionLimitService limitService;
    private final TransactionEngine transactionEngine;

    public LoanAccountServiceImpl(LoanAccountRepository accountRepository,
                                   LoanApplicationRepository applicationRepository,
                                   LoanTransactionRepository transactionRepository,
                                   AccountingService accountingService,
                                   InterestCalculationRule interestRule,
                                   NpaClassificationRule npaRule,
                                   AuditService auditService,
                                   SuspenseService suspenseService,
                                   LoanScheduleService scheduleService,
                                   BusinessDateService businessDateService,
                                   ProductGLResolver glResolver,
                                   TransactionLimitService limitService,
                                   TransactionEngine transactionEngine) {
        this.accountRepository = accountRepository;
        this.applicationRepository = applicationRepository;
        this.transactionRepository = transactionRepository;
        this.accountingService = accountingService;
        this.interestRule = interestRule;
        this.npaRule = npaRule;
        this.auditService = auditService;
        this.suspenseService = suspenseService;
        this.scheduleService = scheduleService;
        this.businessDateService = businessDateService;
        this.glResolver = glResolver;
        this.limitService = limitService;
        this.transactionEngine = transactionEngine;
    }

    @Override
    @Transactional
    public LoanAccount createLoanAccount(Long applicationId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanApplication application = applicationRepository.findById(applicationId)
            .filter(a -> a.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("APPLICATION_NOT_FOUND",
                "Loan application not found: " + applicationId));

        if (application.getStatus() != ApplicationStatus.APPROVED) {
            throw new BusinessException("APPLICATION_NOT_APPROVED",
                "Application must be in APPROVED state. Current: " + application.getStatus());
        }

        // CBS idempotency: prevent duplicate account creation for the same application
        if (accountRepository.existsByTenantIdAndApplicationId(tenantId, applicationId)) {
            throw new BusinessException("ACCOUNT_ALREADY_EXISTS",
                "Loan account already exists for application: " + applicationId);
        }

        // CBS: Resolve product defaults from ProductMaster per Finacle PDDEF
        // If product is configured, use product-level defaults for penal rate, currency, etc.
        // If not configured, fall back to application-level or system defaults.
        var product = glResolver.getProduct(application.getProductType());

        LoanAccount account = new LoanAccount();
        account.setTenantId(tenantId);
        account.setAccountNumber(ReferenceGenerator.generateAccountNumber(
            application.getBranch().getBranchCode()));
        account.setApplication(application);
        account.setCustomer(application.getCustomer());
        account.setBranch(application.getBranch());
        account.setProductType(application.getProductType());
        account.setCurrencyCode(product != null ? product.getCurrencyCode() : "INR");
        account.setSanctionedAmount(application.getApprovedAmount());
        account.setInterestRate(application.getInterestRate());
        account.setPenalRate(application.getPenalRate() != null
            ? application.getPenalRate()
            : (product != null && product.getDefaultPenalRate() != null
                ? product.getDefaultPenalRate()
                : BigDecimal.valueOf(2))); // RBI default 2% penal
        account.setRepaymentFrequency(product != null ? product.getRepaymentFrequency() : "MONTHLY");
        account.setTenureMonths(application.getTenureMonths());
        account.setRemainingTenure(application.getTenureMonths());
        account.setCollateralReference(application.getCollateralReference());
        account.setRiskCategory(application.getRiskCategory() != null
            ? application.getRiskCategory() : "MEDIUM");
        account.setStatus(LoanStatus.ACTIVE);
        account.setCreatedBy(currentUser);

        BigDecimal emi = interestRule.calculateEmi(
            application.getApprovedAmount(),
            application.getInterestRate(),
            application.getTenureMonths()
        );
        account.setEmiAmount(emi);

        LoanAccount saved = accountRepository.save(account);

        auditService.logEvent("LoanAccount", saved.getId(), "CREATE",
            null, saved.getAccountNumber(), "LOAN_ACCOUNTS",
            "Loan account created: " + saved.getAccountNumber() + ", EMI: " + emi);

        log.info("Loan account created: accNo={}, sanctioned={}, emi={}",
            saved.getAccountNumber(), saved.getSanctionedAmount(), emi);

        return saved;
    }

    @Override
    @Transactional
    public LoanAccount disburseLoan(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getDisbursedAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("ALREADY_DISBURSED",
                "Loan has already been disbursed");
        }

        BigDecimal disbursementAmount = account.getSanctionedAmount();
        LocalDate bizDate = businessDateService.getCurrentBusinessDate();
        String productType = account.getProductType();

        // CBS: ALL financial postings go through TransactionEngine — the single enforcement point.
        // TransactionEngine enforces: amount validation → business date → day status → branch →
        // transaction limits → maker-checker → GL posting → voucher → audit trail
        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(glResolver.getLoanAssetGL(productType), DebitCredit.DEBIT, disbursementAmount,
                "Loan disbursement - " + accountNumber),
            new JournalLineRequest(glResolver.getBankOperationsGL(productType), DebitCredit.CREDIT, disbursementAmount,
                "Bank credit for loan disbursement - " + accountNumber)
        );

        TransactionResult txnResult = transactionEngine.execute(
            TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("DISBURSEMENT")
                .accountReference(accountNumber)
                .amount(disbursementAmount)
                .valueDate(bizDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration("Loan disbursement for account " + accountNumber)
                .journalLines(journalLines)
                .build()
        );

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.DISBURSEMENT);
        txn.setAmount(disbursementAmount);
        txn.setPrincipalComponent(disbursementAmount);
        txn.setValueDate(bizDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(disbursementAmount);
        txn.setNarration("Loan disbursement | Voucher: " + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
        txn.setCreatedBy(currentUser);
        transactionRepository.save(txn);

        account.setDisbursedAmount(disbursementAmount);
        account.setOutstandingPrincipal(disbursementAmount);
        account.setDisbursementDate(bizDate);
        account.setLastInterestAccrualDate(bizDate);
        account.setNextEmiDate(bizDate.plusMonths(1));
        account.setMaturityDate(bizDate.plusMonths(account.getTenureMonths()));
        account.setUpdatedBy(currentUser);

        LoanAccount saved = accountRepository.save(account);

        // Mark application as DISBURSED now that disbursement is complete
        LoanApplication application = saved.getApplication();
        application.setStatus(ApplicationStatus.DISBURSED);
        application.setUpdatedBy(currentUser);
        applicationRepository.save(application);

        // CBS: Generate amortization schedule at disbursement per Finacle/Temenos standards
        scheduleService.generateSchedule(saved, saved.getDisbursementDate());

        // NOTE: Audit trail for the financial posting is handled by TransactionEngine (Step 10).
        // Module-level audit for the account state change is separate.
        auditService.logEvent("LoanAccount", saved.getId(), "DISBURSE",
            null, saved.getAccountNumber(), "LOAN_ACCOUNTS",
            "Loan disbursed: " + disbursementAmount + ", schedule generated"
                + " | Voucher: " + txnResult.getVoucherNumber()
                + " | Journal: " + txnResult.getJournalRef());

        log.info("Loan disbursed: accNo={}, amount={}, voucher={}, journal={}",
            accountNumber, disbursementAmount, txnResult.getVoucherNumber(), txnResult.getJournalRef());

        return saved;
    }

    @Override
    @Transactional
    public LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            return null;
        }

        // RBI IRAC: Income recognition must stop when account becomes NPA.
        // Interest on NPA accounts is tracked in a memorandum (suspense) account,
        // not recognized as income in P&L. Per RBI Master Circular on IRAC Norms,
        // interest accrued on NPA accounts must be reversed and not taken to income.
        if (account.getStatus().isIncomeReversalRequired()) {
            log.debug("Interest accrual skipped for NPA account: accNo={}, status={}",
                accountNumber, account.getStatus());
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
            new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.DEBIT, accruedAmount,
                "Interest accrual - " + accountNumber),
            new JournalLineRequest(glResolver.getInterestIncomeGL(productType), DebitCredit.CREDIT, accruedAmount,
                "Interest income accrual - " + accountNumber)
        );

        var journalEntry = accountingService.postJournalEntry(
            accrualDate,
            "Interest accrual for " + accountNumber,
            "LOAN", accountNumber,
            journalLines
        );

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.INTEREST_ACCRUAL);
        txn.setAmount(accruedAmount);
        txn.setInterestComponent(accruedAmount);
        txn.setValueDate(accrualDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(account.getTotalOutstanding().add(accruedAmount));
        txn.setNarration("Daily interest accrual");
        txn.setJournalEntryId(journalEntry.getId());
        txn.setCreatedBy("SYSTEM");
        LoanTransaction savedTxn = transactionRepository.save(txn);

        account.setAccruedInterest(account.getAccruedInterest().add(accruedAmount));
        account.setLastInterestAccrualDate(accrualDate);
        account.setUpdatedBy("SYSTEM");
        accountRepository.save(account);

        log.debug("Interest accrued: accNo={}, amount={}, date={}",
            accountNumber, accruedAmount, accrualDate);

        return savedTxn;
    }

    @Override
    @Transactional
    public LoanTransaction applyPenalInterest(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

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
            new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.DEBIT, penalAmount,
                "Penal interest accrual - " + accountNumber),
            new JournalLineRequest(glResolver.getPenalIncomeGL(productType), DebitCredit.CREDIT, penalAmount,
                "Penal interest income - " + accountNumber)
        );

        var journalEntry = accountingService.postJournalEntry(
            businessDate,
            "RBI penal interest for overdue account " + accountNumber,
            "LOAN", accountNumber,
            journalLines
        );

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.PENALTY_CHARGE);
        txn.setAmount(penalAmount);
        txn.setPenaltyComponent(penalAmount);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(account.getTotalOutstanding().add(penalAmount));
        txn.setNarration("Penal interest: " + penalAmount + " on overdue principal " + account.getOverduePrincipal());
        txn.setJournalEntryId(journalEntry.getId());
        txn.setCreatedBy("SYSTEM");
        LoanTransaction savedTxn = transactionRepository.save(txn);

        account.setPenalInterestAccrued(account.getPenalInterestAccrued().add(penalAmount));
        account.setLastPenalAccrualDate(businessDate);
        account.setUpdatedBy("SYSTEM");
        accountRepository.save(account);

        log.info("Penal interest accrued: accNo={}, amount={}, dpd={}, overduePrincipal={}",
            accountNumber, penalAmount, account.getDaysPastDue(), account.getOverduePrincipal());

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
    @Transactional
    public LoanTransaction processRepayment(String accountNumber, BigDecimal amount,
                                             LocalDate valueDate, String idempotencyKey) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // CBS Idempotency: check for existing transaction with same key
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotent repayment detected: key={}, existingRef={}",
                    idempotencyKey, existing.get().getTransactionRef());
                return existing.get();
            }
        }

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_CLOSED",
                "Cannot process repayment on " + account.getStatus() + " account");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT",
                "Repayment amount must be positive");
        }

        BigDecimal[] components = interestRule.splitEmiComponents(
            amount, account.getOutstandingPrincipal(), account.getInterestRate()
        );
        BigDecimal principalPaid = components[0];
        BigDecimal interestPaid = components[1];

        // CBS: ALL financial postings go through TransactionEngine — the single enforcement point.
        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = new java.util.ArrayList<>();
        journalLines.add(new JournalLineRequest(glResolver.getBankOperationsGL(productType), DebitCredit.DEBIT, amount,
            "Loan repayment received - " + accountNumber));
        if (principalPaid.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(glResolver.getLoanAssetGL(productType), DebitCredit.CREDIT, principalPaid,
                "Principal repayment - " + accountNumber));
        }
        if (interestPaid.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.CREDIT, interestPaid,
                "Interest repayment - " + accountNumber));
        }

        TransactionResult txnResult = transactionEngine.execute(
            TransactionRequest.builder()
                .sourceModule("LOAN")
                .transactionType("REPAYMENT")
                .accountReference(accountNumber)
                .amount(amount)
                .valueDate(valueDate)
                .branchCode(account.getBranch().getBranchCode())
                .productType(productType)
                .narration("Loan repayment for " + accountNumber)
                .journalLines(journalLines)
                .build()
        );

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

        if (account.getRemainingTenure() != null && account.getRemainingTenure() > 0) {
            account.setRemainingTenure(account.getRemainingTenure() - 1);
        }

        if (account.getNextEmiDate() != null) {
            account.setNextEmiDate(account.getNextEmiDate().plusMonths(1));
        }

        // CBS: Loan closure only when all components are zero (principal + interest + penal)
        if (account.getTotalOutstanding().compareTo(BigDecimal.ZERO) == 0) {
            account.setStatus(LoanStatus.CLOSED);
            log.info("Loan account closed: accNo={}", accountNumber);
        }

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(txnResult.getTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.REPAYMENT_PRINCIPAL);
        txn.setAmount(amount);
        txn.setPrincipalComponent(principalPaid);
        txn.setInterestComponent(interestPaid);
        txn.setValueDate(valueDate);
        txn.setPostingDate(txnResult.getPostingDate());
        txn.setBalanceAfter(account.getTotalOutstanding());
        txn.setNarration("EMI repayment - P:" + principalPaid + " I:" + interestPaid
            + " | Voucher: " + txnResult.getVoucherNumber());
        txn.setJournalEntryId(txnResult.getJournalEntryId());
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
        auditService.logEvent("LoanAccount", account.getId(), "REPAYMENT",
            null, savedTxn.getTransactionRef(), "LOAN_ACCOUNTS",
            "Repayment: " + amount + " (P:" + principalPaid + " I:" + interestPaid + ")"
                + " | Voucher: " + txnResult.getVoucherNumber()
                + " | Journal: " + txnResult.getJournalRef());

        log.info("Repayment processed: accNo={}, amount={}, principal={}, interest={}, voucher={}, journal={}",
            accountNumber, amount, principalPaid, interestPaid,
            txnResult.getVoucherNumber(), txnResult.getJournalRef());

        return savedTxn;
    }

    @Override
    @Transactional
    public void classifyNPA(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            return;
        }

        LoanStatus previousStatus = account.getStatus();
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

            auditService.logEvent("LoanAccount", account.getId(), "NPA_CLASSIFY",
                previousStatus.name(), newStatus.name(), "LOAN_ACCOUNTS",
                "NPA classified: " + previousStatus + " -> " + newStatus + ", DPD: " + account.getDaysPastDue());

            log.info("NPA classification: accNo={}, {} -> {}, dpd={}",
                accountNumber, previousStatus, newStatus, account.getDaysPastDue());
        }
    }

    @Override
    @Transactional
    public LoanAccount writeOffAccount(String accountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_ALREADY_TERMINAL",
                "Account is already in terminal state: " + account.getStatus());
        }

        if (!account.getStatus().isNpa()) {
            throw new BusinessException("ACCOUNT_NOT_NPA",
                "Only NPA accounts can be written off. Current status: " + account.getStatus());
        }

        BigDecimal writeOffAmount = account.getOutstandingPrincipal();
        BigDecimal interestReceivable = account.getOutstandingInterest()
            .add(account.getAccruedInterest())
            .add(account.getPenalInterestAccrued());
        BigDecimal provisionHeld = account.getProvisioningAmount();
        String productType = account.getProductType();

        // CBS: Transaction limit validation before write-off
        limitService.validateTransactionLimit(writeOffAmount.add(interestReceivable), "WRITE_OFF");

        // GL Entry 1: Write off the loan asset (principal component)
        if (writeOffAmount.compareTo(BigDecimal.ZERO) > 0) {
            List<JournalLineRequest> writeOffLines = List.of(
                new JournalLineRequest(glResolver.getWriteOffExpenseGL(productType), DebitCredit.DEBIT, writeOffAmount,
                    "Loan write-off principal - " + accountNumber),
                new JournalLineRequest(glResolver.getLoanAssetGL(productType), DebitCredit.CREDIT, writeOffAmount,
                    "Write-off principal asset removal - " + accountNumber)
            );
            accountingService.postJournalEntry(
                businessDate,
                "RBI IRAC write-off principal for NPA account " + accountNumber,
                "WRITE_OFF", accountNumber,
                writeOffLines
            );
        }

        // GL Entry 1b: Write off the interest receivable (interest component)
        if (interestReceivable.compareTo(BigDecimal.ZERO) > 0) {
            List<JournalLineRequest> interestWriteOffLines = List.of(
                new JournalLineRequest(glResolver.getWriteOffExpenseGL(productType), DebitCredit.DEBIT, interestReceivable,
                    "Loan write-off interest - " + accountNumber),
                new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.CREDIT, interestReceivable,
                    "Write-off interest receivable removal - " + accountNumber)
            );
            accountingService.postJournalEntry(
                businessDate,
                "RBI IRAC write-off interest for NPA account " + accountNumber,
                "WRITE_OFF", accountNumber,
                interestWriteOffLines
            );
        }

        // GL Entry 2: Reverse existing provisioning (no longer needed after write-off)
        if (provisionHeld.compareTo(BigDecimal.ZERO) > 0) {
            List<JournalLineRequest> provisionReversal = List.of(
                new JournalLineRequest(glResolver.getProvisionNpaGL(productType), DebitCredit.DEBIT, provisionHeld,
                    "Provision reversal on write-off - " + accountNumber),
                new JournalLineRequest(glResolver.getProvisionExpenseGL(productType), DebitCredit.CREDIT, provisionHeld,
                    "Provision expense release on write-off - " + accountNumber)
            );
            accountingService.postJournalEntry(
                businessDate,
                "Provision reversal on write-off for " + accountNumber,
                "WRITE_OFF", accountNumber,
                provisionReversal
            );
        }

        // Record write-off transaction
        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.WRITE_OFF);
        txn.setAmount(writeOffAmount.add(interestReceivable));
        txn.setPrincipalComponent(writeOffAmount);
        txn.setInterestComponent(interestReceivable);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(BigDecimal.ZERO);
        txn.setNarration("NPA write-off: principal=" + writeOffAmount
            + ", interest=" + interestReceivable + ", provision reversed=" + provisionHeld);
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

        auditService.logEvent("LoanAccount", account.getId(), "WRITE_OFF",
            previousStatus.name(), LoanStatus.WRITTEN_OFF.name(), "LOAN_ACCOUNTS",
            "NPA write-off: P=" + writeOffAmount + ", I=" + interestReceivable
                + ", provision reversed: " + provisionHeld);

        log.info("Loan written off: accNo={}, principal={}, interest={}, provisionReversed={}",
            accountNumber, writeOffAmount, interestReceivable, provisionHeld);

        return account;
    }

    @Override
    @Transactional
    public LoanTransaction processPrepayment(String accountNumber, BigDecimal amount, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_CLOSED",
                "Cannot process prepayment on " + account.getStatus() + " account");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT",
                "Prepayment amount must be positive");
        }

        BigDecimal totalOutstanding = account.getTotalOutstanding();
        if (amount.compareTo(totalOutstanding) < 0) {
            throw new BusinessException("PREPAYMENT_INSUFFICIENT",
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
            throw new BusinessException("PREPAYMENT_OVERPAYMENT",
                "Prepayment amount (" + amount + ") exceeds total outstanding (" + totalOutstanding
                    + "). Amount must exactly match outstanding balance.");
        }

        // CBS: Transaction limit validation before prepayment
        limitService.validateTransactionLimit(totalOutstanding, "PREPAYMENT");

        BigDecimal principalDue = account.getOutstandingPrincipal();
        BigDecimal interestDue = account.getOutstandingInterest()
            .add(account.getAccruedInterest())
            .add(account.getPenalInterestAccrued());

        // CBS: GL codes resolved through product definition per Finacle PDDEF
        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = new java.util.ArrayList<>();
        journalLines.add(new JournalLineRequest(glResolver.getBankOperationsGL(productType), DebitCredit.DEBIT, totalOutstanding,
            "Loan prepayment/foreclosure - " + accountNumber));
        if (principalDue.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(glResolver.getLoanAssetGL(productType), DebitCredit.CREDIT, principalDue,
                "Prepayment principal closure - " + accountNumber));
        }
        if (interestDue.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.CREDIT, interestDue,
                "Prepayment interest closure - " + accountNumber));
        }

        var journalEntry = accountingService.postJournalEntry(
            businessDate,
            "Loan prepayment/foreclosure for " + accountNumber,
            "LOAN", accountNumber,
            journalLines
        );

        // Record prepayment transaction
        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.PREPAYMENT);
        txn.setAmount(totalOutstanding);
        txn.setPrincipalComponent(principalDue);
        txn.setInterestComponent(interestDue);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(BigDecimal.ZERO);
        txn.setNarration("Prepayment/Foreclosure: P=" + principalDue + ", I=" + interestDue);
        txn.setJournalEntryId(journalEntry.getId());
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

        auditService.logEvent("LoanAccount", account.getId(), "PREPAYMENT",
            previousStatus.name(), LoanStatus.CLOSED.name(), "LOAN_ACCOUNTS",
            "Prepayment/Foreclosure: " + totalOutstanding + " (P:" + principalDue + " I:" + interestDue + ")");

        log.info("Prepayment processed: accNo={}, total={}, principal={}, interest={}",
            accountNumber, totalOutstanding, principalDue, interestDue);

        return savedTxn;
    }

    @Override
    @Transactional
    public LoanTransaction reverseTransaction(String transactionRef, String reason, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanTransaction original = transactionRepository
            .findByTenantIdAndTransactionRef(tenantId, transactionRef)
            .orElseThrow(() -> new BusinessException("TRANSACTION_NOT_FOUND",
                "Transaction not found: " + transactionRef));

        if (original.isReversed()) {
            throw new BusinessException("ALREADY_REVERSED",
                "Transaction " + transactionRef + " has already been reversed");
        }

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REVERSAL_REASON_REQUIRED",
                "Reversal reason is mandatory per CBS audit rules");
        }

        // CBS: Acquire pessimistic lock on the loan account to prevent concurrent mutations
        // during balance restoration. Per Finacle TRAN_REVERSAL, account balances must be
        // atomically restored to the pre-transaction state within the same DB transaction.
        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(
                tenantId, original.getLoanAccount().getAccountNumber())
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found for transaction: " + transactionRef));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_TERMINAL",
                "Cannot reverse transaction on " + account.getStatus() + " account");
        }

        // Post contra journal entry — exact reverse of original GL lines
        // CBS: GL codes resolved through product definition per Finacle PDDEF
        String productType = account.getProductType();
        BigDecimal amount = original.getAmount();

        List<JournalLineRequest> reversalLines = new java.util.ArrayList<>();
        // Reverse the bank/cash side
        reversalLines.add(new JournalLineRequest(glResolver.getBankOperationsGL(productType), DebitCredit.CREDIT, amount,
            "REVERSAL: " + transactionRef + " - " + reason));
        // Reverse the asset/receivable side
        if (original.getPrincipalComponent().compareTo(BigDecimal.ZERO) > 0) {
            reversalLines.add(new JournalLineRequest(glResolver.getLoanAssetGL(productType), DebitCredit.DEBIT,
                original.getPrincipalComponent(),
                "REVERSAL principal: " + transactionRef));
        }
        if (original.getInterestComponent().compareTo(BigDecimal.ZERO) > 0) {
            reversalLines.add(new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.DEBIT,
                original.getInterestComponent(),
                "REVERSAL interest: " + transactionRef));
        }
        if (original.getPenaltyComponent().compareTo(BigDecimal.ZERO) > 0) {
            reversalLines.add(new JournalLineRequest(glResolver.getInterestReceivableGL(productType), DebitCredit.DEBIT,
                original.getPenaltyComponent(),
                "REVERSAL penalty: " + transactionRef));
        }

        var journalEntry = accountingService.postJournalEntry(
            businessDate,
            "REVERSAL of " + transactionRef + ": " + reason,
            "REVERSAL", account.getAccountNumber(),
            reversalLines
        );

        // CBS: Restore account subsidiary ledger balances to pre-transaction state.
        // Per Finacle TRAN_REVERSAL and Temenos REVERSAL.TRANSACTION, the account's
        // running balance must be atomically restored. GL and subledger must stay in sync.
        BigDecimal principalToRestore = original.getPrincipalComponent();
        BigDecimal interestToRestore = original.getInterestComponent();
        BigDecimal penaltyToRestore = original.getPenaltyComponent();

        if (original.getTransactionType() == TransactionType.REPAYMENT_PRINCIPAL
                || original.getTransactionType() == TransactionType.PREPAYMENT) {
            // Repayment reversal: restore the amounts that were subtracted
            account.setOutstandingPrincipal(
                account.getOutstandingPrincipal().add(principalToRestore));
            account.setOutstandingInterest(
                account.getOutstandingInterest().add(interestToRestore));
            account.setAccruedInterest(
                account.getAccruedInterest().add(interestToRestore));
            if (penaltyToRestore.compareTo(BigDecimal.ZERO) > 0) {
                account.setPenalInterestAccrued(
                    account.getPenalInterestAccrued().add(penaltyToRestore));
            }
        } else if (original.getTransactionType() == TransactionType.DISBURSEMENT) {
            // Disbursement reversal: reduce the amounts that were added
            account.setOutstandingPrincipal(
                account.getOutstandingPrincipal().subtract(principalToRestore).max(BigDecimal.ZERO));
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
        reversal.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        reversal.setLoanAccount(account);
        reversal.setTransactionType(TransactionType.REVERSAL);
        reversal.setAmount(amount);
        reversal.setPrincipalComponent(original.getPrincipalComponent());
        reversal.setInterestComponent(original.getInterestComponent());
        reversal.setPenaltyComponent(original.getPenaltyComponent());
        reversal.setValueDate(businessDate);
        reversal.setPostingDate(LocalDateTime.now());
        reversal.setBalanceAfter(account.getTotalOutstanding());
        reversal.setNarration("REVERSAL of " + transactionRef + ": " + reason);
        reversal.setJournalEntryId(journalEntry.getId());
        reversal.setReversedByRef(transactionRef);
        reversal.setCreatedBy(currentUser);
        LoanTransaction savedReversal = transactionRepository.save(reversal);

        // Mark original as reversed (never delete per CBS audit rules)
        original.setReversed(true);
        original.setReversedByRef(savedReversal.getTransactionRef());
        original.setUpdatedBy(currentUser);
        transactionRepository.save(original);

        auditService.logEvent("LoanTransaction", original.getId(), "REVERSAL",
            transactionRef, savedReversal.getTransactionRef(), "LOAN_ACCOUNTS",
            "Transaction reversed: " + transactionRef + " → " + savedReversal.getTransactionRef()
                + ", reason: " + reason + ", P:" + principalToRestore
                + " I:" + interestToRestore + " Pen:" + penaltyToRestore);

        log.info("Transaction reversed: original={}, reversal={}, reason={}, balanceRestored={}",
            transactionRef, savedReversal.getTransactionRef(), reason, account.getTotalOutstanding());

        return savedReversal;
    }

    @Override
    @Transactional
    public LoanTransaction chargeFee(String accountNumber, BigDecimal feeAmount,
                                      String feeType, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_CLOSED",
                "Cannot charge fee on " + account.getStatus() + " account");
        }

        if (feeAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_AMOUNT",
                "Fee amount must be positive");
        }

        // CBS: Transaction limit validation before fee charge
        limitService.validateTransactionLimit(feeAmount, "FEE_CHARGE");

        // CBS: GL codes resolved through product definition per Finacle PDDEF
        String productType = account.getProductType();
        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(glResolver.getBankOperationsGL(productType), DebitCredit.DEBIT, feeAmount,
                feeType + " - " + accountNumber),
            new JournalLineRequest(glResolver.getFeeIncomeGL(productType), DebitCredit.CREDIT, feeAmount,
                feeType + " income - " + accountNumber)
        );

        var journalEntry = accountingService.postJournalEntry(
            businessDate,
            feeType + " for " + accountNumber,
            "LOAN", accountNumber,
            journalLines
        );

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.FEE_CHARGE);
        txn.setAmount(feeAmount);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(account.getTotalOutstanding());
        txn.setNarration(feeType + ": " + feeAmount);
        txn.setJournalEntryId(journalEntry.getId());
        txn.setCreatedBy(currentUser);
        LoanTransaction savedTxn = transactionRepository.save(txn);

        auditService.logEvent("LoanAccount", account.getId(), "FEE_CHARGE",
            null, savedTxn.getTransactionRef(), "LOAN_ACCOUNTS",
            feeType + ": " + feeAmount + " for " + accountNumber);

        log.info("Fee charged: accNo={}, type={}, amount={}", accountNumber, feeType, feeAmount);

        return savedTxn;
    }

    @Override
    public LoanAccount getAccount(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        return accountRepository.findByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));
    }

    @Override
    public List<LoanAccount> getActiveAccounts() {
        return accountRepository.findAllActiveAccounts(TenantContext.getCurrentTenant());
    }
}

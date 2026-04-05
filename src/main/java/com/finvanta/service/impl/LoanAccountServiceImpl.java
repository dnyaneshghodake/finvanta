package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService;
import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
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
 *
 * All GL codes are centralized in {@link GLConstants} per Finacle guidelines.
 */
@Service
public class LoanAccountServiceImpl implements LoanAccountService {

    private static final Logger log = LoggerFactory.getLogger(LoanAccountServiceImpl.class);

    // GL codes centralized in GLConstants per Finacle/Temenos guidelines
    private static final String GL_LOAN_ASSET = GLConstants.LOAN_ASSET;
    private static final String GL_DISBURSEMENT_BANK = GLConstants.BANK_OPERATIONS;
    private static final String GL_INTEREST_INCOME = GLConstants.INTEREST_INCOME;
    private static final String GL_INTEREST_RECEIVABLE = GLConstants.INTEREST_RECEIVABLE;
    private static final String GL_CASH_BANK = GLConstants.BANK_OPERATIONS;

    private final LoanAccountRepository accountRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanTransactionRepository transactionRepository;
    private final AccountingService accountingService;
    private final InterestCalculationRule interestRule;
    private final NpaClassificationRule npaRule;
    private final AuditService auditService;
    private final SuspenseService suspenseService;
    private final com.finvanta.service.LoanScheduleService scheduleService;
    private final BusinessDateService businessDateService;

    public LoanAccountServiceImpl(LoanAccountRepository accountRepository,
                                   LoanApplicationRepository applicationRepository,
                                   LoanTransactionRepository transactionRepository,
                                   AccountingService accountingService,
                                   InterestCalculationRule interestRule,
                                   NpaClassificationRule npaRule,
                                   AuditService auditService,
                                   SuspenseService suspenseService,
                                   com.finvanta.service.LoanScheduleService scheduleService,
                                   BusinessDateService businessDateService) {
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

        LoanAccount account = new LoanAccount();
        account.setTenantId(tenantId);
        account.setAccountNumber(ReferenceGenerator.generateAccountNumber(
            application.getBranch().getBranchCode()));
        account.setApplication(application);
        account.setCustomer(application.getCustomer());
        account.setBranch(application.getBranch());
        account.setProductType(application.getProductType());
        account.setSanctionedAmount(application.getApprovedAmount());
        account.setInterestRate(application.getInterestRate());
        account.setPenalRate(application.getPenalRate() != null
            ? application.getPenalRate() : BigDecimal.valueOf(2)); // RBI default 2% penal
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

        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(GL_LOAN_ASSET, DebitCredit.DEBIT, disbursementAmount,
                "Loan disbursement - " + accountNumber),
            new JournalLineRequest(GL_DISBURSEMENT_BANK, DebitCredit.CREDIT, disbursementAmount,
                "Bank credit for loan disbursement - " + accountNumber)
        );

        var journalEntry = accountingService.postJournalEntry(
            bizDate,
            "Loan disbursement for account " + accountNumber,
            "LOAN", accountNumber,
            journalLines
        );

        LoanTransaction txn = new LoanTransaction();
        txn.setTenantId(tenantId);
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.DISBURSEMENT);
        txn.setAmount(disbursementAmount);
        txn.setPrincipalComponent(disbursementAmount);
        txn.setValueDate(bizDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(disbursementAmount);
        txn.setNarration("Loan disbursement");
        txn.setJournalEntryId(journalEntry.getId());
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

        auditService.logEvent("LoanAccount", saved.getId(), "DISBURSE",
            null, saved.getAccountNumber(), "LOAN_ACCOUNTS",
            "Loan disbursed: " + disbursementAmount + ", schedule generated");

        log.info("Loan disbursed: accNo={}, amount={}, schedule generated", accountNumber, disbursementAmount);

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

        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(GL_INTEREST_RECEIVABLE, DebitCredit.DEBIT, accruedAmount,
                "Interest accrual - " + accountNumber),
            new JournalLineRequest(GL_INTEREST_INCOME, DebitCredit.CREDIT, accruedAmount,
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

        // Calculate from last accrual date or next EMI date (whichever is the overdue trigger)
        LocalDate fromDate = account.getLastInterestAccrualDate() != null
            ? account.getLastInterestAccrualDate()
            : account.getNextEmiDate();

        if (fromDate == null || !businessDate.isAfter(fromDate)) {
            return null;
        }

        BigDecimal penalAmount = interestRule.calculatePenalInterest(account, fromDate, businessDate);

        if (penalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // GL Entry: DR Interest Receivable (1002) / CR Penal Interest Income (4003)
        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(GL_INTEREST_RECEIVABLE, DebitCredit.DEBIT, penalAmount,
                "Penal interest accrual - " + accountNumber),
            new JournalLineRequest(GLConstants.PENAL_INTEREST_INCOME, DebitCredit.CREDIT, penalAmount,
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
        account.setUpdatedBy("SYSTEM");
        accountRepository.save(account);

        log.info("Penal interest accrued: accNo={}, amount={}, dpd={}, overduePrincipal={}",
            accountNumber, penalAmount, account.getDaysPastDue(), account.getOverduePrincipal());

        return savedTxn;
    }

    @Override
    @Transactional
    public LoanTransaction processRepayment(String accountNumber, BigDecimal amount, LocalDate valueDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

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

        // Build journal lines dynamically — only include non-zero components per CBS GL posting rules
        List<JournalLineRequest> journalLines = new java.util.ArrayList<>();
        journalLines.add(new JournalLineRequest(GL_CASH_BANK, DebitCredit.DEBIT, amount,
            "Loan repayment received - " + accountNumber));
        if (principalPaid.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(GL_LOAN_ASSET, DebitCredit.CREDIT, principalPaid,
                "Principal repayment - " + accountNumber));
        }
        if (interestPaid.compareTo(BigDecimal.ZERO) > 0) {
            journalLines.add(new JournalLineRequest(GL_INTEREST_RECEIVABLE, DebitCredit.CREDIT, interestPaid,
                "Interest repayment - " + accountNumber));
        }

        var journalEntry = accountingService.postJournalEntry(
            valueDate,
            "Loan repayment for " + accountNumber,
            "LOAN", accountNumber,
            journalLines
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

        // RBI IRAC: DPD resets only when all overdue EMIs are cleared.
        // If outstanding principal is fully paid or remaining balance equals
        // or is less than the scheduled outstanding, DPD can be reset.
        // For simplicity: reset DPD when payment >= EMI amount (full EMI paid).
        if (account.getEmiAmount() != null && amount.compareTo(account.getEmiAmount()) >= 0) {
            account.setDaysPastDue(0);
            account.setOverduePrincipal(BigDecimal.ZERO);
            account.setOverdueInterest(BigDecimal.ZERO);
        }
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
        txn.setTransactionRef(ReferenceGenerator.generateTransactionRef());
        txn.setLoanAccount(account);
        txn.setTransactionType(TransactionType.REPAYMENT_PRINCIPAL);
        txn.setAmount(amount);
        txn.setPrincipalComponent(principalPaid);
        txn.setInterestComponent(interestPaid);
        txn.setValueDate(valueDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(account.getTotalOutstanding());
        txn.setNarration("EMI repayment - Principal: " + principalPaid + ", Interest: " + interestPaid);
        txn.setJournalEntryId(journalEntry.getId());
        txn.setCreatedBy(currentUser);
        LoanTransaction savedTxn = transactionRepository.save(txn);

        accountRepository.save(account);

        // CBS: Update amortization schedule — allocate payment to oldest unpaid installment (FIFO)
        try {
            scheduleService.updateInstallmentsOnPayment(account.getId(), amount, valueDate);
        } catch (Exception e) {
            log.warn("Schedule update failed for repayment {}: {}", accountNumber, e.getMessage());
        }

        auditService.logEvent("LoanAccount", account.getId(), "REPAYMENT",
            null, savedTxn.getTransactionRef(), "LOAN_ACCOUNTS",
            "Repayment: " + amount + " (P:" + principalPaid + " I:" + interestPaid + ")");

        log.info("Repayment processed: accNo={}, amount={}, principal={}, interest={}",
            accountNumber, amount, principalPaid, interestPaid);

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
        BigDecimal provisionHeld = account.getProvisioningAmount();

        // GL Entry 1: Write off the loan asset
        // DR Write-Off Expense (5002) / CR Loan Asset (1001)
        if (writeOffAmount.compareTo(BigDecimal.ZERO) > 0) {
            List<JournalLineRequest> writeOffLines = List.of(
                new JournalLineRequest(GLConstants.WRITE_OFF_EXPENSE, DebitCredit.DEBIT, writeOffAmount,
                    "Loan write-off - " + accountNumber),
                new JournalLineRequest(GL_LOAN_ASSET, DebitCredit.CREDIT, writeOffAmount,
                    "Write-off asset removal - " + accountNumber)
            );
            accountingService.postJournalEntry(
                businessDate,
                "RBI IRAC write-off for NPA account " + accountNumber,
                "WRITE_OFF", accountNumber,
                writeOffLines
            );
        }

        // GL Entry 2: Reverse existing provisioning (no longer needed after write-off)
        // DR Provision for NPA (1003) / CR Provision Expense (5001)
        if (provisionHeld.compareTo(BigDecimal.ZERO) > 0) {
            List<JournalLineRequest> provisionReversal = List.of(
                new JournalLineRequest(GLConstants.PROVISION_NPA, DebitCredit.DEBIT, provisionHeld,
                    "Provision reversal on write-off - " + accountNumber),
                new JournalLineRequest(GLConstants.PROVISION_EXPENSE, DebitCredit.CREDIT, provisionHeld,
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
        txn.setAmount(writeOffAmount);
        txn.setPrincipalComponent(writeOffAmount);
        txn.setValueDate(businessDate);
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(BigDecimal.ZERO);
        txn.setNarration("NPA write-off: principal=" + writeOffAmount + ", provision reversed=" + provisionHeld);
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
            "NPA write-off: " + writeOffAmount + ", provision reversed: " + provisionHeld);

        log.info("Loan written off: accNo={}, amount={}, provisionReversed={}",
            accountNumber, writeOffAmount, provisionHeld);

        return account;
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

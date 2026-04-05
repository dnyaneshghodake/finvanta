package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService;
import com.finvanta.accounting.AccountingService.JournalLineRequest;
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

@Service
public class LoanAccountServiceImpl implements LoanAccountService {

    private static final Logger log = LoggerFactory.getLogger(LoanAccountServiceImpl.class);

    private static final String GL_LOAN_ASSET = "1001";
    private static final String GL_DISBURSEMENT_BANK = "1100";
    private static final String GL_INTEREST_INCOME = "4001";
    private static final String GL_INTEREST_RECEIVABLE = "1002";
    private static final String GL_CASH_BANK = "1100";

    private final LoanAccountRepository accountRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanTransactionRepository transactionRepository;
    private final AccountingService accountingService;
    private final InterestCalculationRule interestRule;
    private final NpaClassificationRule npaRule;
    private final AuditService auditService;

    public LoanAccountServiceImpl(LoanAccountRepository accountRepository,
                                   LoanApplicationRepository applicationRepository,
                                   LoanTransactionRepository transactionRepository,
                                   AccountingService accountingService,
                                   InterestCalculationRule interestRule,
                                   NpaClassificationRule npaRule,
                                   AuditService auditService) {
        this.accountRepository = accountRepository;
        this.applicationRepository = applicationRepository;
        this.transactionRepository = transactionRepository;
        this.accountingService = accountingService;
        this.interestRule = interestRule;
        this.npaRule = npaRule;
        this.auditService = auditService;
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
        account.setTenureMonths(application.getTenureMonths());
        account.setRemainingTenure(application.getTenureMonths());
        account.setStatus(LoanStatus.ACTIVE);
        account.setCreatedBy(currentUser);

        BigDecimal emi = interestRule.calculateEmi(
            application.getApprovedAmount(),
            application.getInterestRate(),
            application.getTenureMonths()
        );
        account.setEmiAmount(emi);

        LoanAccount saved = accountRepository.save(account);

        application.setStatus(ApplicationStatus.DISBURSED);
        application.setUpdatedBy(currentUser);
        applicationRepository.save(application);

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

        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(GL_LOAN_ASSET, DebitCredit.DEBIT, disbursementAmount,
                "Loan disbursement - " + accountNumber),
            new JournalLineRequest(GL_DISBURSEMENT_BANK, DebitCredit.CREDIT, disbursementAmount,
                "Bank credit for loan disbursement - " + accountNumber)
        );

        var journalEntry = accountingService.postJournalEntry(
            LocalDate.now(),
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
        txn.setValueDate(LocalDate.now());
        txn.setPostingDate(LocalDateTime.now());
        txn.setBalanceAfter(disbursementAmount);
        txn.setNarration("Loan disbursement");
        txn.setJournalEntryId(journalEntry.getId());
        txn.setCreatedBy(currentUser);
        transactionRepository.save(txn);

        account.setDisbursedAmount(disbursementAmount);
        account.setOutstandingPrincipal(disbursementAmount);
        account.setDisbursementDate(LocalDate.now());
        account.setLastInterestAccrualDate(LocalDate.now());
        account.setNextEmiDate(LocalDate.now().plusMonths(1));
        account.setMaturityDate(LocalDate.now().plusMonths(account.getTenureMonths()));
        account.setUpdatedBy(currentUser);

        LoanAccount saved = accountRepository.save(account);

        auditService.logEvent("LoanAccount", saved.getId(), "DISBURSE",
            null, saved.getAccountNumber(), "LOAN_ACCOUNTS",
            "Loan disbursed: " + disbursementAmount);

        log.info("Loan disbursed: accNo={}, amount={}", accountNumber, disbursementAmount);

        return saved;
    }

    @Override
    @Transactional
    public LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus() == LoanStatus.CLOSED || account.getStatus() == LoanStatus.WRITTEN_OFF) {
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
    public LoanTransaction processRepayment(String accountNumber, BigDecimal amount, LocalDate valueDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus() == LoanStatus.CLOSED) {
            throw new BusinessException("ACCOUNT_CLOSED",
                "Cannot process repayment on closed account");
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

        List<JournalLineRequest> journalLines = List.of(
            new JournalLineRequest(GL_CASH_BANK, DebitCredit.DEBIT, amount,
                "Loan repayment received - " + accountNumber),
            new JournalLineRequest(GL_LOAN_ASSET, DebitCredit.CREDIT, principalPaid,
                "Principal repayment - " + accountNumber),
            new JournalLineRequest(GL_INTEREST_RECEIVABLE, DebitCredit.CREDIT, interestPaid,
                "Interest repayment - " + accountNumber)
        );

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
        account.setDaysPastDue(0);
        account.setUpdatedBy(currentUser);

        if (account.getRemainingTenure() != null && account.getRemainingTenure() > 0) {
            account.setRemainingTenure(account.getRemainingTenure() - 1);
        }

        if (account.getNextEmiDate() != null) {
            account.setNextEmiDate(account.getNextEmiDate().plusMonths(1));
        }

        if (account.getOutstandingPrincipal().compareTo(BigDecimal.ZERO) == 0
                && account.getOutstandingInterest().compareTo(BigDecimal.ZERO) == 0) {
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

        auditService.logEvent("LoanAccount", account.getId(), "REPAYMENT",
            null, savedTxn.getTransactionRef(), "LOAN_ACCOUNTS",
            "Repayment: " + amount + " (P:" + principalPaid + " I:" + interestPaid + ")");

        log.info("Repayment processed: accNo={}, amount={}, principal={}, interest={}",
            accountNumber, amount, principalPaid, interestPaid);

        return savedTxn;
    }

    @Override
    @Transactional
    public void classifyNPA(String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanAccount account = accountRepository.findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus() == LoanStatus.CLOSED || account.getStatus() == LoanStatus.WRITTEN_OFF) {
            return;
        }

        LoanStatus previousStatus = account.getStatus();
        LoanStatus newStatus = npaRule.classify(account);

        if (previousStatus != newStatus) {
            account.setStatus(newStatus);
            if (newStatus.isNpa() && account.getNpaDate() == null) {
                account.setNpaDate(LocalDate.now());
            }
            account.setNpaClassificationDate(LocalDate.now());
            account.setUpdatedBy("SYSTEM");
            accountRepository.save(account);

            auditService.logEvent("LoanAccount", account.getId(), "NPA_CLASSIFY",
                previousStatus.name(), newStatus.name(), "LOAN_ACCOUNTS",
                "NPA classified: " + previousStatus + " -> " + newStatus + ", DPD: " + account.getDaysPastDue());

            log.info("NPA classification: accNo={}, {} -> {}, dpd={}",
                accountNumber, previousStatus, newStatus, account.getDaysPastDue());
        }
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

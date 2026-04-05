package com.finvanta.service;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface LoanAccountService {

    LoanAccount createLoanAccount(Long applicationId);

    LoanAccount disburseLoan(String accountNumber);

    LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate);

    /**
     * RBI Fair Lending Code 2023: Penal interest accrual on overdue accounts.
     * Charged on overdue principal at the penal rate (% p.a.) using Actual/365.
     * GL Entry: DR Interest Receivable (1002) / CR Penal Interest Income (4003)
     *
     * @param accountNumber Loan account number
     * @param businessDate  CBS business date
     * @return Transaction record, or null if no penal applicable
     */
    LoanTransaction applyPenalInterest(String accountNumber, LocalDate businessDate);

    LoanTransaction processRepayment(String accountNumber, BigDecimal amount, LocalDate valueDate);

    /**
     * CBS NPA Classification per RBI IRAC norms.
     * Must receive CBS business date — never use LocalDate.now() for financial operations.
     *
     * @param accountNumber Loan account number
     * @param businessDate  CBS business date from BusinessDateService (NOT system date)
     */
    void classifyNPA(String accountNumber, LocalDate businessDate);

    /**
     * CBS Write-Off per RBI IRAC norms for NPA Loss accounts.
     * GL Entry: DR Write-Off Expense (5002) / CR Loan Asset (1001)
     * Reverses provisioning: DR Provision for NPA (1003) / CR Provision Expense (5001)
     * Account status transitions to WRITTEN_OFF (terminal state).
     *
     * @param accountNumber Loan account number
     * @param businessDate  CBS business date
     * @return The written-off account
     */
    LoanAccount writeOffAccount(String accountNumber, LocalDate businessDate);

    /**
     * CBS Prepayment/Foreclosure per RBI Fair Lending Code 2023.
     * Per RBI circular: No prepayment penalty on floating rate loans.
     * Closes the loan early by paying off all outstanding components.
     * GL Entry: DR Bank Operations (1100) / CR Loan Asset (1001) + Interest Receivable (1002)
     *
     * @param accountNumber Loan account number
     * @param amount        Prepayment amount (must cover total outstanding)
     * @param businessDate  CBS business date
     * @return Transaction record
     */
    LoanTransaction processPrepayment(String accountNumber, BigDecimal amount, LocalDate businessDate);

    /**
     * CBS Transaction Reversal per Finacle/Temenos standards.
     * Creates a contra journal entry that mirrors the original transaction.
     * Per CBS audit rules: original transaction is marked reversed (never deleted).
     * GL entries are the exact reverse of the original posting.
     *
     * @param transactionRef Reference of the transaction to reverse
     * @param reason         Reversal reason (mandatory for audit trail)
     * @param businessDate   CBS business date
     * @return The reversal transaction record
     */
    LoanTransaction reverseTransaction(String transactionRef, String reason, LocalDate businessDate);

    /**
     * CBS Fee Charging per RBI Fair Lending Code.
     * Processing fees, documentation charges, etc. charged at disbursement or ad-hoc.
     * GL Entry: DR Bank Operations (1100) / CR Fee Income (4002)
     *
     * @param accountNumber Loan account number
     * @param feeAmount     Fee amount to charge
     * @param feeType       Fee description (e.g., "Processing Fee", "Documentation Charge")
     * @param businessDate  CBS business date
     * @return Transaction record
     */
    LoanTransaction chargeFee(String accountNumber, BigDecimal feeAmount, String feeType, LocalDate businessDate);

    LoanAccount getAccount(String accountNumber);

    List<LoanAccount> getActiveAccounts();
}

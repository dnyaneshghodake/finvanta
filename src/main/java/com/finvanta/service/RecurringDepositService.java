package com.finvanta.service;

import com.finvanta.domain.entity.RecurringDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS Recurring Deposit Service per Finacle RD_MASTER / Temenos FIXED.DEPOSIT.
 *
 * <p>Per RBI Banking Regulation Act:
 * <ul>
 *   <li>Monthly installment auto-debit from linked CASA on due date</li>
 *   <li>Interest compounded quarterly on cumulative balance (Actual/365)</li>
 *   <li>Missed installments incur penalty interest</li>
 *   <li>3+ consecutive misses → DEFAULTED status</li>
 *   <li>Premature closure with penalty rate deduction</li>
 *   <li>TDS deduction per IT Act Section 194A</li>
 * </ul>
 *
 * <p>GL Flow:
 * <pre>
 *   Installment: DR CASA (2010/2020) / CR RD Deposits (2040)
 *   Accrual:     DR RD Interest Expense (5012) / CR RD Interest Payable (2041)
 *   Maturity:    DR RD Deposits (2040) + RD Interest Payable (2041) / CR CASA
 * </pre>
 *
 * @see com.finvanta.domain.entity.RecurringDeposit
 */
public interface RecurringDepositService {

    /**
     * Books a new Recurring Deposit.
     * GL: DR CASA / CR RD Deposits for first installment.
     *
     * @param customerId     Customer CIF
     * @param branchId       Booking branch
     * @param linkedAccount  CASA account for auto-debit
     * @param installmentAmt Monthly installment amount
     * @param interestRate   Annual interest rate (% p.a.)
     * @param tenureMonths   Tenure in months (= total installments)
     * @param nomineeName    Nominee name (nullable)
     * @param nomineeRel     Nominee relationship (nullable)
     * @return Booked RecurringDeposit
     */
    RecurringDeposit bookRd(
            Long customerId,
            Long branchId,
            String linkedAccount,
            BigDecimal installmentAmt,
            BigDecimal interestRate,
            int tenureMonths,
            String nomineeName,
            String nomineeRel);

    /**
     * Processes a single installment for an RD.
     * Called by EOD batch on nextInstallmentDate.
     * GL: DR CASA / CR RD Deposits.
     *
     * @param rdAccountNumber RD account number
     * @param businessDate    Current business date
     */
    void processInstallment(String rdAccountNumber, LocalDate businessDate);

    /**
     * Accrues daily interest on cumulative deposit balance.
     * Called by EOD batch for all active RDs.
     * Formula: cumulativeDeposit × rate / 36500
     *
     * @param rdAccountNumber RD account number
     * @param businessDate    Current business date
     */
    void accrueInterest(String rdAccountNumber, LocalDate businessDate);

    /**
     * Processes maturity — credits maturity amount to linked CASA.
     * GL: DR RD Deposits + RD Interest Payable / CR CASA.
     *
     * @param rdAccountNumber RD account number
     */
    void processMaturity(String rdAccountNumber);

    /**
     * Premature closure with penalty rate deduction.
     * Effective rate = applicable_rate - premature_penalty_rate.
     * Interest recalculated at effective rate for actual tenure.
     *
     * @param rdAccountNumber RD account number
     * @param reason          Closure reason
     * @return Closed RecurringDeposit
     */
    RecurringDeposit prematureClose(String rdAccountNumber, String reason);
}

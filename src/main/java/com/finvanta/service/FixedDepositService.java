package com.finvanta.service;

import com.finvanta.domain.entity.FixedDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS Fixed Deposit Service per Finacle TD_MASTER / Temenos FIXED.DEPOSIT.
 *
 * All financial operations route through TransactionEngine for:
 * - Double-entry GL posting (FD Deposits 2030, FD Interest 2031/5011)
 * - Business date validation
 * - Branch access enforcement
 * - Audit trail
 *
 * FD Lifecycle per RBI Banking Regulation Act:
 *   Book → Accrue (daily EOD) → Payout (monthly/quarterly) → Mature → Close
 *   Book → Premature Close (with penalty)
 *   Book → Lien Mark → Lien Release → Close
 *   Mature → Auto-Renew (PRINCIPAL_ONLY or PRINCIPAL_AND_INTEREST)
 *
 * Interest Calculation:
 *   COMPOUND_QUARTERLY: A = P × (1 + r/400)^(quarters) — standard for cumulative FDs
 *   SIMPLE: I = P × r × days / 36500 — for payout mode FDs
 *
 * TDS per IT Act Section 194A:
 *   10% if YTD interest > ₹40,000 (₹50,000 for senior citizens aged 60+)
 */
public interface FixedDepositService {

    /**
     * Book a new Fixed Deposit.
     * GL: DR CASA (2010/2020) / CR FD Deposits (2030)
     * Validates: KYC verified, CASA active, sufficient balance.
     */
    FixedDeposit bookFd(
            Long customerId, Long branchId,
            String linkedAccountNumber,
            BigDecimal principalAmount,
            BigDecimal interestRate,
            int tenureDays,
            String interestPayoutMode,
            String autoRenewalMode,
            String nomineeName,
            String nomineeRelationship);

    /**
     * Premature closure with penalty rate reduction.
     * Per RBI Fair Practices: effective rate = applicable_rate - penalty.
     * Interest recalculated at reduced rate for actual tenure held.
     * GL: DR FD Deposits (2030) + DR FD Interest Expense (5011)
     *     / CR CASA (2010/2020)
     */
    FixedDeposit prematureClose(String fdNumber, String reason);

    /**
     * Normal maturity closure — full interest + principal to CASA.
     * GL: DR FD Deposits (2030) + DR FD Interest Payable (2031)
     *     / CR CASA (2010/2020)
     */
    FixedDeposit maturityClose(String fdNumber);

    /**
     * Daily interest accrual (EOD step).
     * COMPOUND_QUARTERLY: daily portion of quarterly compound interest.
     * SIMPLE: P × r × 1 / 36500.
     * GL: DR FD Interest Expense (5011) / CR FD Interest Payable (2031)
     */
    void accrueInterest(String fdNumber, LocalDate businessDate);

    /**
     * EOD batch: process all FDs maturing on or before businessDate.
     * For each: auto-renew or close based on autoRenewalMode.
     * @return count of FDs processed
     */
    int processMaturityBatch(LocalDate businessDate);

    /**
     * Mark lien on FD for loan collateral.
     * Prevents premature closure until lien is released.
     */
    FixedDeposit markLien(
            String fdNumber, BigDecimal lienAmount,
            String loanAccountNumber);

    /** Release lien on FD. */
    FixedDeposit releaseLien(String fdNumber);

    /**
     * Credit accrued interest to linked CASA (monthly/quarterly payout).
     * GL: DR FD Interest Payable (2031) / CR CASA (2010/2020)
     * TDS deducted if YTD threshold exceeded.
     */
    void payoutInterest(String fdNumber, LocalDate businessDate);

    /** Get FD by account number. */
    FixedDeposit getFd(String fdNumber);
}

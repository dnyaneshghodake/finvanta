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
     * Idempotent variant of {@link #bookFd}.
     *
     * <p>The {@code idempotencyKey} is forwarded to {@link
     * com.finvanta.transaction.TransactionRequest}, where {@code TransactionEngine}
     * (Step 1 of its validation chain) looks it up in {@code idempotency_registry}
     * and short-circuits on duplicate submission — preventing a retried FD booking
     * from posting a second DR CASA / CR FD_DEPOSITS voucher and debiting the
     * customer's CASA twice.
     *
     * <p>Follows the same default-delegation pattern as {@link
     * #prematureClose(String, String, String)}: callers may pass a null/blank key
     * to fall back to the non-idempotent path (e.g. system-generated bookings).
     *
     * @param idempotencyKey client-supplied X-Idempotency-Key header value;
     *                       {@code null} or blank → no idempotency protection.
     */
    default FixedDeposit bookFd(
            Long customerId, Long branchId,
            String linkedAccountNumber,
            BigDecimal principalAmount,
            BigDecimal interestRate,
            int tenureDays,
            String interestPayoutMode,
            String autoRenewalMode,
            String nomineeName,
            String nomineeRelationship,
            String idempotencyKey) {
        // Default delegation keeps existing implementations source-compatible
        // until they override this method to plumb idempotencyKey into
        // TransactionRequest.
        return bookFd(customerId, branchId, linkedAccountNumber,
                principalAmount, interestRate, tenureDays,
                interestPayoutMode, autoRenewalMode,
                nomineeName, nomineeRelationship);
    }

    /**
     * Premature closure with penalty rate reduction.
     * Per RBI Fair Practices: effective rate = applicable_rate - penalty.
     * Interest recalculated at reduced rate for actual tenure held.
     * GL: DR FD Deposits (2030) + DR FD Interest Expense (5011)
     *     / CR CASA (2010/2020)
     */
    FixedDeposit prematureClose(String fdNumber, String reason);

    /**
     * Idempotent variant of {@link #prematureClose(String, String)}.
     *
     * <p>Per RBI Cyber Security Framework 2024 §8.4 and blueprint step 1
     * (Idempotency Check), every customer-initiated mutation must be retry-safe.
     * The {@code idempotencyKey} is forwarded to {@code TransactionRequest} so
     * {@code TransactionEngine} can short-circuit on duplicate submissions
     * (e.g. double-click, network retry, BFF replay) and return the previously
     * posted {@code transactionRef} / {@code voucherNumber} instead of posting
     * a second closure voucher.
     *
     * <p>Without this plumbing, a duplicate call would post two
     * DR FD_DEPOSITS / CR CASA vouchers — crediting the customer twice and
     * driving GL 2030 negative. This is the exact failure class that the
     * RD {@code adjustedInterest} cap was added to defend against.
     *
     * @param idempotencyKey client-supplied X-Idempotency-Key header value;
     *                       if {@code null} or blank, behaves like the
     *                       two-arg overload (no idempotency protection).
     */
    default FixedDeposit prematureClose(String fdNumber, String reason, String idempotencyKey) {
        // Default delegation keeps existing implementations source-compatible
        // until they override this method in Chunk 3. Once FixedDepositServiceImpl
        // overrides this method to plumb idempotencyKey into TransactionRequest,
        // the delegation path is no longer taken.
        return prematureClose(fdNumber, reason);
    }

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

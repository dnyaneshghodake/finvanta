package com.finvanta.domain.entity;

import com.finvanta.domain.enums.RdStatus;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Recurring Deposit (RD) Entity per Finacle RD_MASTER / Temenos FIXED.DEPOSIT.
 *
 * <p>Per RBI Banking Regulation Act:
 * Recurring Deposits are monthly installment-based term deposits where the
 * customer deposits a fixed amount every month for a predetermined tenure.
 * Interest is compounded quarterly (same as FD) on the cumulative balance.
 *
 * <p>Interest Calculation:
 *   Maturity Value = P × [(1 + r/4)^(4n) - 1] / [1 - (1 + r/4)^(-1/3)]
 *   Where P = monthly installment, r = annual rate, n = tenure in years
 *   Per RBI: Actual/365 day-count basis, compound quarterly.
 *
 * <p>Installment Processing (EOD):
 *   On nextInstallmentDate, auto-debit linked CASA account.
 *   If CASA has insufficient funds: mark installment as MISSED,
 *   apply penalty interest, increment missedInstallments counter.
 *   Per RBI: 3+ consecutive misses → DEFAULTED status.
 *
 * <p>GL Flow:
 *   Monthly Installment: DR CASA (2010/2020) / CR RD Deposits (2040)
 *   Interest Accrual:    DR RD Interest Expense (5012) / CR RD Interest Payable (2041)
 *   Maturity:            DR RD Deposits (2040) + RD Interest Payable (2041) / CR CASA
 *   TDS:                 DR RD Interest Payable (2041) / CR TDS Payable (2500)
 *
 * @see com.finvanta.domain.entity.FixedDeposit
 */
@Entity
@Table(
        name = "recurring_deposits",
        indexes = {
            @Index(name = "idx_rd_tenant_rdno",
                    columnList = "tenant_id, rd_account_number",
                    unique = true),
            @Index(name = "idx_rd_tenant_customer",
                    columnList = "tenant_id, customer_id"),
            @Index(name = "idx_rd_tenant_status",
                    columnList = "tenant_id, status"),
            @Index(name = "idx_rd_next_installment",
                    columnList = "tenant_id, next_installment_date, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class RecurringDeposit extends BaseEntity {

    // === Identity ===

    @Column(name = "rd_account_number", nullable = false, length = 40)
    private String rdAccountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    // === Installment Configuration ===

    /** Fixed monthly installment amount */
    @Column(name = "installment_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal installmentAmount;

    /** Total number of installments (tenure in months) */
    @Column(name = "total_installments", nullable = false)
    private int totalInstallments;

    /** Number of installments paid so far */
    @Column(name = "paid_installments", nullable = false)
    private int paidInstallments = 0;

    /** Number of consecutive missed installments */
    @Column(name = "missed_installments", nullable = false)
    private int missedInstallments = 0;

    /** Next installment due date */
    @Column(name = "next_installment_date")
    private LocalDate nextInstallmentDate;

    /** Last installment paid date */
    @Column(name = "last_installment_date")
    private LocalDate lastInstallmentDate;

    // === Balance & Interest ===

    /** Cumulative deposits received (installmentAmount × paidInstallments) */
    @Column(name = "cumulative_deposit", nullable = false, precision = 18, scale = 2)
    private BigDecimal cumulativeDeposit = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "INR";

    /** Annual interest rate (% p.a.) */
    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    /** Accrued interest not yet credited */
    @Column(name = "accrued_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    /** Total interest credited to date */
    @Column(name = "total_interest_credited", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalInterestCredited = BigDecimal.ZERO;

    /** YTD interest for TDS calculation (resets on Apr 1) */
    @Column(name = "ytd_interest_credited", nullable = false, precision = 18, scale = 2)
    private BigDecimal ytdInterestCredited = BigDecimal.ZERO;

    /** YTD TDS deducted */
    @Column(name = "ytd_tds_deducted", nullable = false, precision = 18, scale = 2)
    private BigDecimal ytdTdsDeducted = BigDecimal.ZERO;

    /** Last date interest was accrued (for idempotent EOD) */
    @Column(name = "last_accrual_date")
    private LocalDate lastAccrualDate;

    /** Penalty rate for missed installments (% p.a.) */
    @Column(name = "penalty_rate", precision = 8, scale = 4)
    private BigDecimal penaltyRate = new BigDecimal("2.0000");

    /** Premature withdrawal penalty rate reduction (% p.a.) */
    @Column(name = "premature_penalty_rate", precision = 8, scale = 4)
    private BigDecimal prematurePenaltyRate = new BigDecimal("1.0000");

    // === Tenure & Dates ===

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "closure_date")
    private LocalDate closureDate;

    // === Status ===

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private RdStatus status = RdStatus.ACTIVE;

    // === Linked CASA Account ===

    @Column(name = "linked_account_number", nullable = false, length = 40)
    private String linkedAccountNumber;

    // === Product Reference ===

    @Column(name = "product_code", nullable = false, length = 50)
    private String productCode;

    // === Nominee ===

    @Column(name = "nominee_name", length = 200)
    private String nomineeName;

    @Column(name = "nominee_relationship", length = 30)
    private String nomineeRelationship;

    // === GL Journal References ===

    @Column(name = "booking_journal_id")
    private Long bookingJournalId;

    @Column(name = "closure_journal_id")
    private Long closureJournalId;

    // === Helpers ===

    /** Maturity amount = cumulative deposits + accrued interest */
    public BigDecimal getMaturityAmount() {
        return cumulativeDeposit.add(accruedInterest);
    }

    /** Remaining installments */
    public int getRemainingInstallments() {
        return Math.max(0, totalInstallments - paidInstallments);
    }

    /** Whether all installments have been paid */
    public boolean isFullyPaid() {
        return paidInstallments >= totalInstallments;
    }

    public boolean isActive() {
        return status == RdStatus.ACTIVE;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }
}

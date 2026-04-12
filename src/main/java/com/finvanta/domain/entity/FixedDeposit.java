package com.finvanta.domain.entity;

import com.finvanta.domain.enums.FdStatus;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Fixed Deposit (Term Deposit) Entity per Finacle TD_MASTER / Temenos FIXED.DEPOSIT.
 *
 * Per RBI Banking Regulation Act and Finacle/SBI/HDFC standards:
 *
 * Interest Calculation:
 *   Compound quarterly (default): A = P × (1 + r/4)^(4t)
 *   Simple (payout mode): I = P × r × t / 365
 *   Per RBI: FD interest is calculated on Actual/365 day-count basis.
 *
 * Interest Payout Modes:
 *   MATURITY     — Interest accumulated, paid with principal at maturity
 *   MONTHLY      — Interest credited to linked CASA monthly
 *   QUARTERLY    — Interest credited to linked CASA quarterly
 *   REINVEST     — Interest added to FD principal (cumulative FD)
 *
 * Auto-Renewal Modes:
 *   NO_RENEWAL          — Maturity amount credited to CASA
 *   PRINCIPAL_ONLY      — Only principal renewed, interest to CASA
 *   PRINCIPAL_AND_INTEREST — Full amount renewed as new FD
 *
 * Premature Withdrawal:
 *   Per RBI Fair Practices: rate reduced by penalty % from applicable rate.
 *   Interest recalculated at (applicable_rate - penalty_rate) for actual tenure.
 *
 * TDS:
 *   Per IT Act Section 194A: TDS @10% if FD interest exceeds ₹40,000/year
 *   (₹50,000 for senior citizens aged 60+). Tracked via ytdInterestPaid.
 *
 * Lien:
 *   FD can be marked with lien for loan collateral (FD-backed OD/loan).
 *   Lien prevents premature closure until lien is released.
 *
 * GL Flow:
 *   Book FD:      DR CASA (2010/2020) / CR FD Deposits (2030)
 *   Accrue:       DR FD Interest Expense (5011) / CR FD Interest Payable (2031)
 *   Payout:       DR FD Interest Payable (2031) / CR CASA (2010/2020)
 *   Maturity:     DR FD Deposits (2030) / CR CASA (2010/2020)
 *   TDS:          DR FD Interest Payable (2031) / CR TDS Payable (2500)
 */
@Entity
@Table(
        name = "fixed_deposits",
        indexes = {
            @Index(name = "idx_fd_tenant_fdno",
                    columnList = "tenant_id, fd_account_number",
                    unique = true),
            @Index(name = "idx_fd_tenant_customer",
                    columnList = "tenant_id, customer_id"),
            @Index(name = "idx_fd_tenant_branch",
                    columnList = "tenant_id, branch_id"),
            @Index(name = "idx_fd_tenant_status",
                    columnList = "tenant_id, status"),
            @Index(name = "idx_fd_maturity_date",
                    columnList = "tenant_id, maturity_date, status"),
            @Index(name = "idx_fd_linked_casa",
                    columnList = "tenant_id, linked_account_number")
        })
@Getter
@Setter
@NoArgsConstructor
public class FixedDeposit extends BaseEntity {

    // === Identity ===

    @Column(name = "fd_account_number", nullable = false,
            length = 40)
    private String fdAccountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "branch_code", nullable = false,
            length = 20)
    private String branchCode;

    // === Principal & Currency ===

    @Column(name = "principal_amount", nullable = false,
            precision = 18, scale = 2)
    private BigDecimal principalAmount;

    /** Current principal (increases for REINVEST mode) */
    @Column(name = "current_principal", nullable = false,
            precision = 18, scale = 2)
    private BigDecimal currentPrincipal;

    @Column(name = "currency_code", nullable = false,
            length = 3)
    private String currencyCode = "INR";

    // === Interest ===

    /** Annual interest rate (% p.a.) */
    @Column(name = "interest_rate", nullable = false,
            precision = 8, scale = 4)
    private BigDecimal interestRate;

    /**
     * Senior citizen rate enhancement (% p.a.).
     * Per SBI/HDFC: 0.25-0.50% additional for age 60+.
     * Effective rate = interestRate + seniorCitizenBonus.
     */
    @Column(name = "senior_citizen_bonus",
            precision = 8, scale = 4)
    private BigDecimal seniorCitizenBonus = BigDecimal.ZERO;

    /** SIMPLE or COMPOUND_QUARTERLY */
    @Column(name = "interest_calculation_method",
            nullable = false, length = 25)
    private String interestCalculationMethod =
            "COMPOUND_QUARTERLY";

    /**
     * Interest payout mode per Finacle TD_MASTER.
     * MATURITY, MONTHLY, QUARTERLY, REINVEST
     */
    @Column(name = "interest_payout_mode",
            nullable = false, length = 20)
    private String interestPayoutMode = "MATURITY";

    /** Accrued interest not yet paid/credited */
    @Column(name = "accrued_interest", nullable = false,
            precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    /** Total interest paid/credited to date */
    @Column(name = "total_interest_paid", nullable = false,
            precision = 18, scale = 2)
    private BigDecimal totalInterestPaid = BigDecimal.ZERO;

    /** YTD interest for TDS calculation (resets on Apr 1) */
    @Column(name = "ytd_interest_paid", nullable = false,
            precision = 18, scale = 2)
    private BigDecimal ytdInterestPaid = BigDecimal.ZERO;

    /** YTD TDS deducted */
    @Column(name = "ytd_tds_deducted", nullable = false,
            precision = 18, scale = 2)
    private BigDecimal ytdTdsDeducted = BigDecimal.ZERO;

    /** Last date interest was accrued */
    @Column(name = "last_accrual_date")
    private LocalDate lastAccrualDate;

    /** Last date interest was paid/credited to CASA */
    @Column(name = "last_payout_date")
    private LocalDate lastPayoutDate;

    // === Tenure & Dates ===

    @Column(name = "tenure_days", nullable = false)
    private int tenureDays;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "closure_date")
    private LocalDate closureDate;

    // === Status & Lifecycle ===

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private FdStatus status = FdStatus.ACTIVE;

    // === Linked CASA Account ===

    /** CASA account for interest payout and maturity credit */
    @Column(name = "linked_account_number",
            nullable = false, length = 40)
    private String linkedAccountNumber;

    // === Auto-Renewal ===

    /**
     * Auto-renewal mode per Finacle TD_MASTER.
     * NO_RENEWAL, PRINCIPAL_ONLY, PRINCIPAL_AND_INTEREST
     */
    @Column(name = "auto_renewal_mode", nullable = false,
            length = 30)
    private String autoRenewalMode = "NO_RENEWAL";

    /** Number of times this FD has been auto-renewed */
    @Column(name = "renewal_count", nullable = false)
    private int renewalCount = 0;

    /** Original FD number (if this is a renewed FD) */
    @Column(name = "original_fd_number", length = 40)
    private String originalFdNumber;

    // === Premature Withdrawal ===

    /**
     * Penalty rate reduction for premature withdrawal (% p.a.).
     * Per RBI Fair Practices: effective rate = applicable_rate - penaltyRate.
     * Typical: 0.50% - 1.00%.
     */
    @Column(name = "premature_penalty_rate",
            precision = 8, scale = 4)
    private BigDecimal prematurePenaltyRate =
            new BigDecimal("1.0000");

    /** Whether premature withdrawal is allowed */
    @Column(name = "premature_allowed", nullable = false)
    private boolean prematureAllowed = true;

    // === Lien (FD-backed Loan Collateral) ===

    /** Whether a lien is marked on this FD */
    @Column(name = "lien_marked", nullable = false)
    private boolean lienMarked = false;

    /** Lien amount (can be partial lien) */
    @Column(name = "lien_amount", precision = 18, scale = 2)
    private BigDecimal lienAmount = BigDecimal.ZERO;

    /** Reference to the loan account holding the lien */
    @Column(name = "lien_loan_account", length = 40)
    private String lienLoanAccount;

    /** Date when lien was marked */
    @Column(name = "lien_date")
    private LocalDate lienDate;

    // === Product Reference ===

    @Column(name = "product_code", nullable = false,
            length = 50)
    private String productCode;

    // === Nominee ===

    @Column(name = "nominee_name", length = 200)
    private String nomineeName;

    @Column(name = "nominee_relationship", length = 30)
    private String nomineeRelationship;

    // === GL Journal References ===

    /** Journal ID for the booking GL entry */
    @Column(name = "booking_journal_id")
    private Long bookingJournalId;

    /** Journal ID for the closure/maturity GL entry */
    @Column(name = "closure_journal_id")
    private Long closureJournalId;

    // === Helpers ===

    /** Effective interest rate including senior citizen bonus */
    public BigDecimal getEffectiveRate() {
        return interestRate.add(
                seniorCitizenBonus != null
                        ? seniorCitizenBonus
                        : BigDecimal.ZERO);
    }

    /** Maturity amount (principal + total interest for MATURITY/REINVEST) */
    public BigDecimal getMaturityAmount() {
        if ("REINVEST".equals(interestPayoutMode)
                || "MATURITY".equals(interestPayoutMode)) {
            return currentPrincipal.add(accruedInterest);
        }
        return currentPrincipal;
    }

    public boolean isActive() {
        return status == FdStatus.ACTIVE;
    }

    public boolean isMature() {
        return status == FdStatus.MATURED;
    }

    public boolean isClosed() {
        return status == FdStatus.CLOSED
                || status == FdStatus.PREMATURE_CLOSED;
    }

    /** Whether premature closure is blocked by lien */
    public boolean isLienBlocked() {
        return lienMarked && lienAmount != null
                && lienAmount.signum() > 0;
    }
}

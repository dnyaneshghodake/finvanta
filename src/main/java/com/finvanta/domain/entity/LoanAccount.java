package com.finvanta.domain.entity;

import com.finvanta.domain.enums.LoanStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan_accounts", indexes = {
    @Index(name = "idx_loacc_tenant_accno", columnList = "tenant_id, account_number", unique = true),
    @Index(name = "idx_loacc_status", columnList = "tenant_id, status"),
    @Index(name = "idx_loacc_customer", columnList = "tenant_id, customer_id"),
    @Index(name = "idx_loacc_npa", columnList = "tenant_id, status, days_past_due")
})
@Getter
@Setter
@NoArgsConstructor
public class LoanAccount extends BaseEntity {

    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    private LoanApplication application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "product_type", nullable = false, length = 50)
    private String productType;

    /**
     * ISO 4217 currency code for all monetary amounts on this account.
     * Per CBS multi-currency standards, every account must declare its currency.
     * All amounts (sanctioned, outstanding, accrued, etc.) are in this currency.
     * For India-only deployment, defaults to INR. Future multi-currency support
     * requires FCY→LCY conversion at the GL posting level.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "INR";

    @Column(name = "sanctioned_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal sanctionedAmount;

    @Column(name = "disbursed_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal disbursedAmount = BigDecimal.ZERO;

    @Column(name = "outstanding_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingPrincipal = BigDecimal.ZERO;

    @Column(name = "outstanding_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingInterest = BigDecimal.ZERO;

    @Column(name = "accrued_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    @Column(name = "overdue_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal overduePrincipal = BigDecimal.ZERO;

    @Column(name = "overdue_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal overdueInterest = BigDecimal.ZERO;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    /** RBI Fair Lending: Penal rate applied on overdue EMIs (% p.a.) */
    @Column(name = "penal_rate", precision = 8, scale = 4)
    private BigDecimal penalRate = BigDecimal.ZERO;

    @Column(name = "emi_amount", precision = 18, scale = 2)
    private BigDecimal emiAmount;

    /** RBI IRAC: Repayment frequency — MONTHLY, QUARTERLY, BULLET */
    @Column(name = "repayment_frequency", length = 20)
    private String repaymentFrequency = "MONTHLY";

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "remaining_tenure")
    private Integer remainingTenure;

    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "next_emi_date")
    private LocalDate nextEmiDate;

    @Column(name = "last_payment_date")
    private LocalDate lastPaymentDate;

    @Column(name = "last_interest_accrual_date")
    private LocalDate lastInterestAccrualDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(name = "days_past_due", nullable = false)
    private int daysPastDue = 0;

    @Column(name = "npa_date")
    private LocalDate npaDate;

    @Column(name = "npa_classification_date")
    private LocalDate npaClassificationDate;

    /**
     * RBI IRAC: Provisioning amount based on NPA classification.
     * Standard: 0.40%, Sub-standard: 10%, Doubtful: 20-50%, Loss: 100%
     */
    @Column(name = "provisioning_amount", precision = 18, scale = 2)
    private BigDecimal provisioningAmount = BigDecimal.ZERO;

    /** Penal interest accrued on overdue EMIs */
    @Column(name = "penal_interest_accrued", precision = 18, scale = 2)
    private BigDecimal penalInterestAccrued = BigDecimal.ZERO;

    /**
     * RBI Fair Lending: Independent penal interest accrual date tracker.
     * Separate from lastInterestAccrualDate because regular interest and penal interest
     * have different accrual bases (outstanding principal vs overdue principal) and
     * different lifecycle triggers. EOD runs regular accrual before penal accrual,
     * so sharing the same date field would cause penal calculation to always get
     * days=0 (since regular accrual already advanced the date to today).
     */
    @Column(name = "last_penal_accrual_date")
    private LocalDate lastPenalAccrualDate;

    /** Collateral reference for secured loans */
    @Column(name = "collateral_reference", length = 100)
    private String collateralReference;

    /** RBI risk classification: LOW, MEDIUM, HIGH, VERY_HIGH */
    @Column(name = "risk_category", length = 20)
    private String riskCategory;

    /**
     * CBS Disbursement Account per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT.
     *
     * The borrower's CASA (Savings/Current) account number where loan proceeds
     * are credited on disbursement. Copied from LoanApplication at account creation.
     *
     * Per Tier-1 CBS (Finacle/Temenos/BNP):
     *   - Disbursement credits the borrower's operating account (not Bank Ops GL)
     *   - GL: DR Loan Asset (1001) / CR Customer Deposits (SB 2010 / CA 2020)
     *   - CASA subledger balance is updated atomically
     *   - Same account used for all tranches in multi-disbursement mode
     *   - Also used as default debit account for EMI auto-debit (Standing Instruction)
     *
     * Per RBI KYC/AML: Must belong to the same customer CIF.
     * Nullable: If null, falls back to Bank Ops GL (1100) — cash/DD disbursement.
     */
    @Column(name = "disbursement_account_number", length = 40)
    private String disbursementAccountNumber;

    // --- Multi-Disbursement Support (per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT) ---

    /**
     * Disbursement mode per product configuration:
     *   SINGLE       - Full sanctioned amount in one shot (Term Loan, Gold Loan)
     *   MULTI_TRANCHE - Stage-wise linked to milestones (Home Loan, Construction)
     *   DRAWDOWN     - Multiple draws within limit (Working Capital, OD)
     *
     * Per Finacle DISB_MASTER: the disbursement mode determines whether partial
     * disbursements are allowed and how interest accrual/EMI calculation behaves.
     */
    @Column(name = "disbursement_mode", length = 20)
    private String disbursementMode = "SINGLE";

    /** Number of tranches planned (for MULTI_TRANCHE mode) */
    @Column(name = "total_tranches_planned")
    private Integer totalTranchesPlanned;

    /** Number of tranches actually disbursed so far */
    @Column(name = "tranches_disbursed")
    private Integer tranchesDisbursed = 0;

    /**
     * Whether the loan is fully disbursed.
     * For SINGLE mode: true after first disbursement.
     * For MULTI_TRANCHE: true after all planned tranches are disbursed.
     * For DRAWDOWN: always false (draws can happen anytime within limit).
     *
     * Interest accrual runs on disbursedAmount (not sanctionedAmount).
     * EMI schedule is generated/recalculated when fullyDisbursed becomes true.
     */
    @Column(name = "is_fully_disbursed", nullable = false)
    private boolean fullyDisbursed = false;

    /**
     * Returns the undisbursed commitment: sanctioned - disbursed.
     * For SINGLE mode, this is either full sanctioned (before) or zero (after).
     * For MULTI_TRANCHE, this decreases with each tranche.
     */
    public BigDecimal getUndisbursedAmount() {
        return sanctionedAmount.subtract(disbursedAmount).max(BigDecimal.ZERO);
    }

    public BigDecimal getTotalOutstanding() {
        return outstandingPrincipal.add(outstandingInterest).add(accruedInterest).add(penalInterestAccrued);
    }

    public boolean isMultiDisbursement() {
        return "MULTI_TRANCHE".equals(disbursementMode) || "DRAWDOWN".equals(disbursementMode);
    }
}

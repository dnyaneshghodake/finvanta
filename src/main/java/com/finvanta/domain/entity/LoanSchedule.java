package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Loan Amortization Schedule per Finacle/Temenos standards.
 *
 * Generated at disbursement time for the full loan tenure.
 * Each row represents one EMI installment with:
 *   - Scheduled principal/interest split (reducing balance method)
 *   - Due date per repayment frequency
 *   - Actual payment tracking (paid date, paid amount)
 *   - Overdue status for DPD calculation
 *
 * Per RBI IRAC norms, DPD is computed by comparing schedule due dates
 * against actual payment dates — not just the next EMI date.
 *
 * Schedule is immutable after generation. Restructuring creates a new schedule.
 */
@Entity
@Table(
        name = "loan_schedules",
        indexes = {
            @Index(name = "idx_loansched_tenant_account", columnList = "tenant_id, loan_account_id"),
            @Index(name = "idx_loansched_due_date", columnList = "tenant_id, due_date"),
            @Index(name = "idx_loansched_status", columnList = "tenant_id, loan_account_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanSchedule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    /** Installment sequence: 1, 2, 3... up to tenure_months */
    @Column(name = "installment_number", nullable = false)
    private int installmentNumber;

    /** EMI due date per repayment frequency */
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** Total EMI amount (principal + interest) */
    @Column(name = "emi_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal emiAmount;

    /** Scheduled principal component for this installment */
    @Column(name = "principal_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalAmount;

    /** Scheduled interest component for this installment */
    @Column(name = "interest_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestAmount;

    /** Outstanding principal balance after this installment (if paid on time) */
    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance;

    /** Actual amount paid against this installment */
    @Column(name = "paid_amount", precision = 18, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** Actual principal paid */
    @Column(name = "paid_principal", precision = 18, scale = 2)
    private BigDecimal paidPrincipal = BigDecimal.ZERO;

    /** Actual interest paid */
    @Column(name = "paid_interest", precision = 18, scale = 2)
    private BigDecimal paidInterest = BigDecimal.ZERO;

    /** Date when payment was received (null if unpaid) */
    @Column(name = "paid_date")
    private LocalDate paidDate;

    /**
     * Installment status per CBS lifecycle:
     *   SCHEDULED → OVERDUE → PAID / PARTIALLY_PAID
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "SCHEDULED";

    /** Penalty charged on this overdue installment */
    @Column(name = "penalty_amount", precision = 18, scale = 2)
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    /** Business date when this schedule line was generated */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Days past due for this specific installment */
    @Column(name = "days_past_due")
    private int daysPastDue = 0;

    /**
     * Returns true if this installment is overdue (due date passed, not fully paid).
     */
    public boolean isOverdue() {
        return "OVERDUE".equals(status) || "PARTIALLY_PAID".equals(status);
    }

    /**
     * Returns true if this installment is fully paid.
     */
    public boolean isPaid() {
        return "PAID".equals(status);
    }

    /**
     * Returns the remaining amount due on this installment.
     */
    public BigDecimal getRemainingDue() {
        return emiAmount.subtract(paidAmount).max(BigDecimal.ZERO);
    }
}

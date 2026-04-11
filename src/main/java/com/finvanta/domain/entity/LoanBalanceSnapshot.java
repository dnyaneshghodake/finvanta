package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Loan Balance Snapshot per Finacle ACCT_BAL_HIST / Temenos ACCT.BALANCE.HISTORY.
 *
 * Captures the closing balance of every active loan account at EOD for:
 * - Regulatory reporting (sectoral exposure on specific dates per RBI CRILC)
 * - NPA provisioning audit trail (outstanding at classification date)
 * - Customer dispute resolution (balance on a specific date)
 * - AML/CFT monitoring (unusual balance patterns)
 * - RBI Large Exposure Framework (LEF) reporting
 *
 * Per Finacle ACCT_BAL_HIST: every Tier-1 CBS captures daily balance history
 * for ALL account types (CASA + Loan). One row per account per business date.
 *
 * Captured during EOD Step 8.7 after all loan-side balance mutations
 * (interest accrual, penal accrual, repayments via SI) are complete.
 */
@Entity
@Table(
        name = "loan_balance_snapshots",
        indexes = {
            @Index(
                    name = "idx_lbs_tenant_acct_date",
                    columnList = "tenant_id, account_id, business_date",
                    unique = true),
            @Index(name = "idx_lbs_tenant_date", columnList = "tenant_id, business_date"),
            @Index(name = "idx_lbs_tenant_acct", columnList = "tenant_id, account_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class LoanBalanceSnapshot extends BaseEntity {

    /**
     * The loan account this snapshot belongs to.
     * Stored as raw ID (not @ManyToOne) for batch insert performance.
     * Per Finacle ACCT_BAL_HIST: denormalized for batch throughput.
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Account number denormalized for efficient reporting */
    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    /** Branch ID for branch-level exposure reporting (denormalized) */
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    /** CBS business date this snapshot was captured on */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Outstanding principal at EOD */
    @Column(name = "outstanding_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingPrincipal = BigDecimal.ZERO;

    /** Outstanding interest at EOD */
    @Column(name = "outstanding_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingInterest = BigDecimal.ZERO;

    /** Accrued interest not yet posted at EOD */
    @Column(name = "accrued_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    /** Penal interest accrued at EOD */
    @Column(name = "penal_interest_accrued", nullable = false, precision = 18, scale = 2)
    private BigDecimal penalInterestAccrued = BigDecimal.ZERO;

    /** Total outstanding = principal + interest + accrued + penal */
    @Column(name = "total_outstanding", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalOutstanding = BigDecimal.ZERO;

    /** Days past due at snapshot time (for NPA trend analysis) */
    @Column(name = "days_past_due", nullable = false)
    private int daysPastDue = 0;

    /** Loan status at snapshot time (ACTIVE, SMA_0, NPA_SUBSTANDARD, etc.) */
    @Column(name = "loan_status", nullable = false, length = 30)
    private String loanStatus;

    /** Product type (TERM_LOAN, HOME_LOAN, GOLD_LOAN, etc.) */
    @Column(name = "product_type", nullable = false, length = 50)
    private String productType;

    /** Provisioning amount at snapshot time (for provisioning adequacy audit) */
    @Column(name = "provisioning_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal provisioningAmount = BigDecimal.ZERO;
}

package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ApplicationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan_applications", indexes = {
    @Index(name = "idx_loanapp_tenant_appno", columnList = "tenant_id, application_number", unique = true),
    @Index(name = "idx_loanapp_status", columnList = "tenant_id, status"),
    @Index(name = "idx_loanapp_customer", columnList = "tenant_id, customer_id")
})
@Getter
@Setter
@NoArgsConstructor
public class LoanApplication extends BaseEntity {

    @Column(name = "application_number", nullable = false, length = 40)
    private String applicationNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "product_type", nullable = false, length = 50)
    private String productType;

    @Column(name = "requested_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "approved_amount", precision = 18, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "purpose", length = 500)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ApplicationStatus status = ApplicationStatus.DRAFT;

    @Column(name = "application_date")
    private LocalDate applicationDate;

    @Column(name = "verified_by", length = 100)
    private String verifiedBy;

    @Column(name = "verified_date")
    private LocalDate verifiedDate;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_date")
    private LocalDate approvedDate;

    @Column(name = "rejected_by", length = 100)
    private String rejectedBy;

    @Column(name = "rejected_date")
    private LocalDate rejectedDate;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "remarks", length = 1000)
    private String remarks;

    /** Collateral reference for secured loan products */
    @Column(name = "collateral_reference", length = 100)
    private String collateralReference;

    /** RBI risk classification: LOW, MEDIUM, HIGH, VERY_HIGH */
    @Column(name = "risk_category", length = 20)
    private String riskCategory;

    /** RBI Fair Lending: Penal rate (% p.a.) applied on overdue EMIs */
    @Column(name = "penal_rate", precision = 8, scale = 4)
    private BigDecimal penalRate;

    /**
     * CBS Disbursement Account per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT.
     *
     * The borrower's CASA (Savings/Current) account number where loan proceeds
     * will be credited on disbursement. Per Tier-1 CBS standards:
     *   - Loan disbursement MUST credit the borrower's operating account (not cash)
     *   - GL: DR Loan Asset (1001) / CR Customer Deposits (2010/2020)
     *   - CASA subledger is updated atomically within the same transaction
     *   - Account must belong to the same customer (CIF linkage validation)
     *   - Account must be ACTIVE at the time of disbursement
     *
     * Per RBI KYC/AML: Disbursement to third-party accounts is prohibited for
     * retail loans. The CASA account must be in the borrower's name.
     *
     * Nullable: If null, disbursement falls back to Bank Operations GL (1100)
     * for backward compatibility (cash disbursement / demand draft mode).
     */
    @Column(name = "disbursement_account_number", length = 40)
    private String disbursementAccountNumber;
}

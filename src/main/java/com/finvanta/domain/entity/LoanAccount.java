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

    @Column(name = "account_number", nullable = false, length = 20)
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

    @Column(name = "emi_amount", precision = 18, scale = 2)
    private BigDecimal emiAmount;

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

    public BigDecimal getTotalOutstanding() {
        return outstandingPrincipal.add(outstandingInterest).add(accruedInterest);
    }
}

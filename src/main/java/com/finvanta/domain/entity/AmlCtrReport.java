package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Cash Transaction Report (CTR) per PMLA 2002 / FIU-IND.
 *
 * <p>Per RBI KYC Master Direction 2016 Section 28(2): Cash transactions of
 * INR 10 lakh or more (whether in a single transaction or several transactions
 * in a day) must be reported to FIU-IND by the 15th of the following month.
 *
 * <p>CTR Lifecycle: PENDING → BATCHED → FILED → ACKNOWLEDGED
 *
 * <p>Per Finacle AML_CTR / Temenos FC.CTR.REPORT.
 */
@Entity
@Table(
        name = "aml_ctr_reports",
        indexes = {
            @Index(name = "idx_ctr_tenant_month",
                    columnList = "tenant_id, reporting_month, status"),
            @Index(name = "idx_ctr_tenant_customer",
                    columnList = "tenant_id, customer_id")
        },
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_ctr_ref",
                    columnNames = {"tenant_id", "ctr_reference"})
        })
@Getter
@Setter
@NoArgsConstructor
public class AmlCtrReport extends BaseEntity {

    @Column(name = "ctr_reference", nullable = false, length = 40)
    private String ctrReference;

    /** First day of the transaction month — used for monthly batch filing */
    @Column(name = "reporting_month", nullable = false)
    private LocalDate reportingMonth;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "account_reference", nullable = false, length = 40)
    private String accountReference;

    @Column(name = "transaction_ref", length = 40)
    private String transactionRef;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /** CASH_DEPOSIT or CASH_WITHDRAWAL */
    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "INR";

    @Column(name = "branch_code", length = 20)
    private String branchCode;

    /** PENDING, BATCHED, FILED, ACKNOWLEDGED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    /** Monthly batch filing job ID */
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "filing_date")
    private LocalDate filingDate;

    @Column(name = "fiu_acknowledgement", length = 100)
    private String fiuAcknowledgement;
}

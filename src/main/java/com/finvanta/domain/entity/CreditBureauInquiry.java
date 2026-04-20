package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Credit Bureau Inquiry Record per CICRA 2005 / RBI KYC Master Direction.
 *
 * <p>Per Credit Information Companies (Regulation) Act 2005:
 * Banks must check credit bureau reports before sanctioning any credit facility.
 * Monthly data submission to all 4 bureaus (CIBIL, Experian, Equifax, CRIF) is mandatory.
 *
 * <p>This entity records every credit bureau inquiry made by the bank, including
 * the response data (credit score, DPD history, outstanding exposure).
 *
 * <p>Per Finacle BUREAU_INQUIRY / Temenos CR.BUREAU.CHECK.
 */
@Entity
@Table(
        name = "credit_bureau_inquiries",
        indexes = {
            @Index(name = "idx_cbi_customer", columnList = "tenant_id, customer_id"),
            @Index(name = "idx_cbi_app", columnList = "tenant_id, application_id")
        },
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_cbi_ref", columnNames = {"tenant_id", "inquiry_reference"})
        })
@Getter
@Setter
@NoArgsConstructor
public class CreditBureauInquiry extends BaseEntity {

    @Column(name = "inquiry_reference", nullable = false, length = 40)
    private String inquiryReference;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "application_id")
    private Long applicationId;

    /** CIBIL, EXPERIAN, EQUIFAX, CRIF */
    @Column(name = "bureau_name", nullable = false, length = 30)
    private String bureauName;

    @Column(name = "inquiry_date", nullable = false)
    private LocalDateTime inquiryDate;

    /** LOAN_ORIGINATION, REVIEW, RENEWAL, MONITORING */
    @Column(name = "inquiry_purpose", nullable = false, length = 30)
    private String inquiryPurpose;

    /** Credit score returned by bureau (e.g., CIBIL score 300-900) */
    @Column(name = "credit_score")
    private Integer creditScore;

    /** Score version/model (e.g., CIBIL v2.0, Experian v3.1) */
    @Column(name = "score_version", length = 20)
    private String scoreVersion;

    /** Full bureau report as JSON — for audit and dispute resolution */
    @Column(name = "report_data", columnDefinition = "NVARCHAR(MAX)")
    private String reportData;

    /** Bureau response code (SUCCESS, NO_HIT, ERROR, TIMEOUT) */
    @Column(name = "response_code", length = 20)
    private String responseCode;

    /** Maximum DPD in last 12 months across all accounts */
    @Column(name = "dpd_max_last_12m")
    private Integer dpdMaxLast12m;

    /** Maximum DPD in last 24 months across all accounts */
    @Column(name = "dpd_max_last_24m")
    private Integer dpdMaxLast24m;

    /** Number of active credit accounts */
    @Column(name = "active_accounts")
    private Integer activeAccounts;

    /** Number of overdue accounts */
    @Column(name = "overdue_accounts")
    private Integer overdueAccounts;

    /** Total outstanding across all lenders */
    @Column(name = "total_outstanding", precision = 18, scale = 2)
    private BigDecimal totalOutstanding;

    /** Total overdue amount across all lenders */
    @Column(name = "total_overdue", precision = 18, scale = 2)
    private BigDecimal totalOverdue;

    /** Number of bureau inquiries in last 6 months (credit hunger indicator) */
    @Column(name = "enquiry_count_6m")
    private Integer enquiryCount6m;

    /** PENDING, SUCCESS, FAILED, TIMEOUT */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Returns true if the credit score meets minimum threshold for lending */
    public boolean meetsMinimumScore(int minimumScore) {
        return creditScore != null && creditScore >= minimumScore;
    }

    /** Returns true if the borrower has any overdue accounts */
    public boolean hasOverdueAccounts() {
        return overdueAccounts != null && overdueAccounts > 0;
    }

    /** Returns true if the inquiry was successful with a valid score */
    public boolean isSuccessful() {
        return "SUCCESS".equals(responseCode) && creditScore != null;
    }
}

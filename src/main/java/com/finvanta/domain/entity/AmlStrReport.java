package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Suspicious Transaction Report (STR) per PMLA 2002 / FIU-IND.
 *
 * <p>Per RBI KYC Master Direction 2016 Section 29: Banks must report
 * suspicious transactions to FIU-IND within 7 days of detection.
 *
 * <p>STR Lifecycle: DRAFT → UNDER_REVIEW → APPROVED → FILED → ACKNOWLEDGED
 *
 * <p><b>CRITICAL per PMLA Section 66:</b> The existence of an STR must NOT
 * be disclosed to the customer (tipping-off offense). Access restricted
 * to ADMIN and COMPLIANCE roles only.
 *
 * <p>Per Finacle AML_STR / Temenos FC.STR.REPORT.
 */
@Entity
@Table(
        name = "aml_str_reports",
        indexes = {
            @Index(name = "idx_str_tenant_status", columnList = "tenant_id, status"),
            @Index(name = "idx_str_tenant_customer", columnList = "tenant_id, customer_id"),
            @Index(name = "idx_str_detection_date", columnList = "tenant_id, detection_date")
        },
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_str_ref", columnNames = {"tenant_id", "str_reference"})
        })
@Getter
@Setter
@NoArgsConstructor
public class AmlStrReport extends BaseEntity {

    @Column(name = "str_reference", nullable = false, length = 40)
    private String strReference;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "account_reference", length = 40)
    private String accountReference;

    @Column(name = "detection_date", nullable = false)
    private LocalDate detectionDate;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "filing_date")
    private LocalDate filingDate;

    @Column(name = "fiu_acknowledgement", length = 100)
    private String fiuAcknowledgement;

    /** STR category per FIU-IND classification */
    @Column(name = "str_category", nullable = false, length = 50)
    private String strCategory;

    @Column(name = "suspicious_amount", precision = 18, scale = 2)
    private BigDecimal suspiciousAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "INR";

    /** RULE_ENGINE, MANUAL_REFERRAL, BRANCH_REPORT, AUDIT_FINDING */
    @Column(name = "detection_method", nullable = false, length = 30)
    private String detectionMethod;

    @Column(name = "rule_id", length = 50)
    private String ruleId;

    @Column(name = "risk_score")
    private Integer riskScore;

    /** Free-text narrative describing suspicious activity — mandatory per FIU-IND */
    @Column(name = "narrative", nullable = false, length = 4000)
    private String narrative;

    /** DRAFT, UNDER_REVIEW, APPROVED, FILED, ACKNOWLEDGED, CLOSED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "related_ctr_id")
    private Long relatedCtrId;

    /** Returns true if STR has been filed with FIU-IND */
    public boolean isFiled() {
        return "FILED".equals(status) || "ACKNOWLEDGED".equals(status);
    }

    /** Returns true if STR filing deadline is breached (> 7 days from detection) */
    public boolean isDeadlineBreached(LocalDate today) {
        if (isFiled()) return false;
        return today.isAfter(detectionDate.plusDays(7));
    }
}

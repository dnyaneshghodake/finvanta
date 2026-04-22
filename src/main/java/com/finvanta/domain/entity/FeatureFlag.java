package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Feature Flag Entity per Finacle BANK_PARAM / Temenos EB.SYSTEM.STATUS.
 *
 * <p>Per RBI IT Governance Direction 2023: banks must be able to enable/disable
 * payment rails and product modules at runtime without code deployment.
 * RBI can mandate immediate disabling of a payment rail during a security incident.
 *
 * <p>Categories:
 * <ul>
 *   <li>PAYMENT_RAIL — NEFT, RTGS, IMPS, UPI (RBI can mandate disable)</li>
 *   <li>PRODUCT_MODULE — Gold Loan, Education Loan, RD (per license)</li>
 *   <li>SYSTEM_FEATURE — ISO20022, NEFT_24x7, Positive Pay (per readiness)</li>
 *   <li>UI_FEATURE — Dashboard widgets, reports (per tenant config)</li>
 * </ul>
 *
 * <p>Per Finacle BANK_PARAM: feature availability is tenant-scoped.
 * Bank A may have UPI enabled while Bank B does not.
 */
@Entity
@Table(
        name = "feature_flags",
        indexes = {
            @Index(name = "idx_ff_tenant_category",
                    columnList = "tenant_id, category, is_enabled")
        },
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_feature_flag",
                    columnNames = {"tenant_id", "flag_code"})
        })
@Getter
@Setter
@NoArgsConstructor
public class FeatureFlag extends BaseEntity {

    @Column(name = "flag_code", nullable = false, length = 50)
    private String flagCode;

    @Column(name = "flag_name", nullable = false, length = 200)
    private String flagName;

    /** PAYMENT_RAIL, PRODUCT_MODULE, SYSTEM_FEATURE, UI_FEATURE */
    @Column(name = "category", nullable = false, length = 30)
    private String category;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "enabled_by", length = 100)
    private String enabledBy;

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    @Column(name = "disabled_by", length = 100)
    private String disabledBy;

    @Column(name = "disabled_at")
    private LocalDateTime disabledAt;
}

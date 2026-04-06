package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * CBS Charge Configuration per Finacle CHRG_MASTER.
 *
 * Centralized charge configuration supporting:
 * - FLAT charges: Fixed amount (e.g., INR 500)
 * - PERCENTAGE charges: % of base amount (e.g., 1% processing fee)
 * - SLAB charges: Tiered rates by amount range (e.g., STAMP_DUTY slab-based)
 * - GST auto-calculation: If gst_applicable=true, GST is computed and posted separately
 * - Product-specific overrides: If product_code is set, this config applies only to that product
 * - Waiver support: Charges can be waived up to max_waiver_percent
 *
 * GL Posting (via ChargeEngine.applyCharge):
 *   3-leg journal entry:
 *   - DR Customer/Bank Operations (GL 1100)
 *   - CR Charge Income (gl_charge_income — product-specific or global)
 *   - CR GST Payable (gl_gst_payable — if gst_applicable=true)
 *
 * Per RBI Fair Lending Code 2023: All charges are transparent and justified.
 * Per Ind AS standards: Revenue recognized on GL 4002+ codes (income), GST on 2200+ codes (liability).
 */
@Entity
@Table(name = "charge_config", indexes = {
    @Index(name = "idx_chargeconfig_tenant_code", columnList = "tenant_id, charge_code"),
    @Index(name = "idx_chargeconfig_tenant_product", columnList = "tenant_id, product_code"),
    @Index(name = "idx_chargeconfig_tenant_active", columnList = "tenant_id, is_active")
})
@Getter
@Setter
public class ChargeConfig extends BaseEntity {

    @Column(name = "charge_code", length = 50, nullable = false)
    private String chargeCode; // PROCESSING_FEE, LATE_PAYMENT_FEE, STAMP_DUTY, etc.

    @Column(name = "charge_name", length = 200)
    private String chargeName; // Human-readable description

    @Column(name = "event_trigger", length = 50, nullable = false)
    private String eventTrigger; // DISBURSEMENT, OVERDUE_EMI, CHEQUE_RETURN, ACCOUNT_CLOSURE, MANUAL

    @Column(name = "calculation_type", length = 20, nullable = false)
    private String calculationType; // FLAT, PERCENTAGE, SLAB

    @Column(name = "base_amount", precision = 18, scale = 2)
    private BigDecimal baseAmount; // For FLAT charges

    @Column(name = "percentage", precision = 5, scale = 2)
    private BigDecimal percentage; // For PERCENTAGE charges (e.g., 1.00 for 1%)

    @Column(name = "slab_json", columnDefinition = "NVARCHAR(MAX)")
    private String slabJson; // JSON array for SLAB: [{"min":0,"max":100000,"rate":0.5},...]

    @Column(name = "min_amount", precision = 18, scale = 2)
    private BigDecimal minAmount; // Minimum charge amount

    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount; // Maximum charge amount

    @Column(name = "gst_applicable", nullable = false)
    private Boolean gstApplicable; // If true, GST is auto-calculated and posted

    @Column(name = "gst_rate", precision = 5, scale = 2)
    private BigDecimal gstRate; // Default 18.00 for CGST+SGST on most services

    @Column(name = "gl_charge_income", length = 10)
    private String glChargeIncome; // GL code for charge income (e.g., 4002 Fee Income)

    @Column(name = "gl_gst_payable", length = 10)
    private String glGstPayable; // GL code for GST liability (e.g., 2200 CGST Payable)

    @Column(name = "waiver_allowed", nullable = false)
    private Boolean waiverAllowed; // If true, charge can be waived

    @Column(name = "max_waiver_percent", precision = 5, scale = 2)
    private BigDecimal maxWaiverPercent; // Max % of charge that can be waived (e.g., 50.00)

    @Column(name = "product_code", length = 50)
    private String productCode; // If null, applies to all products. Otherwise product-specific.

    @Column(name = "is_active", nullable = false)
    private Boolean isActive; // Soft delete flag
}


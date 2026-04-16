package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

/**
 * CBS Charge Configuration per Finacle CHRG_MASTER / Temenos AA.CHARGE.PARAMETER.
 *
 * Tier-1 CBS charge configuration supporting:
 * - FLAT charges: Fixed amount (e.g., INR 500)
 * - PERCENTAGE charges: % of base amount (e.g., 1% processing fee)
 * - SLAB charges: Tiered rates by amount range (e.g., STAMP_DUTY slab-based)
 * - GST auto-calculation: If gst_applicable=true, GST is computed and posted separately
 * - Product-specific overrides: If product_code is set, this config applies only to that product
 * - Waiver support: Charges can be waived up to max_waiver_percent
 * - Effective date range: Charges are only applicable within valid_from → valid_to
 * - Frequency control: ONE_TIME, PER_OCCURRENCE, RECURRING, MONTHLY, QUARTERLY, ANNUAL
 * - Channel applicability: Different charges per channel (BRANCH, ATM, INTERNET, MOBILE, UPI)
 * - Charge category: FEE, PENALTY, TAX, SERVICE_CHARGE for regulatory reporting
 *
 * GL Posting (via ChargeEngine.applyCharge):
 *   3-leg journal entry:
 *   - DR Customer/Bank Operations (GL 1100)
 *   - CR Charge Income (gl_charge_income — product-specific or global)
 *   - CR GST Payable (gl_gst_payable — if gst_applicable=true)
 *
 * Per RBI Fair Lending Code 2023: All charges are transparent, justified, and communicated.
 * Per Ind AS standards: Revenue recognized on GL 4002+ codes (income), GST on 2200+ codes (liability).
 * Per RBI Digital Banking Framework 2023: Channel-specific charges must be configurable.
 */
@Entity
@Table(
        name = "charge_config",
        indexes = {
            @Index(name = "idx_chargeconfig_tenant_code", columnList = "tenant_id, charge_code"),
            @Index(name = "idx_chargeconfig_tenant_product", columnList = "tenant_id, product_code"),
            @Index(name = "idx_chargeconfig_tenant_active", columnList = "tenant_id, is_active"),
            @Index(name = "idx_chargeconfig_tenant_trigger", columnList = "tenant_id, event_trigger, is_active")
        })
@Getter
@Setter
public class ChargeConfig extends BaseEntity {

    @Column(name = "charge_code", length = 50, nullable = false)
    private String chargeCode; // PROCESSING_FEE, LATE_PAYMENT_FEE, STAMP_DUTY, etc.

    @Column(name = "charge_name", length = 200, nullable = false)
    private String chargeName; // Human-readable description (mandatory per RBI Fair Lending)

    /**
     * Charge category per Finacle CHRG_MASTER.CHARGE_TYPE / RBI Fair Lending Code 2023.
     * Used for regulatory reporting and customer statement classification.
     * Values: FEE, PENALTY, TAX, SERVICE_CHARGE, INSURANCE, STAMP_DUTY
     */
    @Column(name = "charge_category", length = 30)
    private String chargeCategory;

    @Column(name = "event_trigger", length = 50, nullable = false)
    private String eventTrigger; // DISBURSEMENT, OVERDUE_EMI, CHEQUE_RETURN, ACCOUNT_CLOSURE, MANUAL

    @Column(name = "calculation_type", length = 20, nullable = false)
    private String calculationType; // FLAT, PERCENTAGE, SLAB

    /**
     * Charge frequency per Finacle CHRG_MASTER.FREQUENCY.
     * Controls how often the charge can be applied:
     *   ONE_TIME        — Applied once per account lifecycle (e.g., processing fee)
     *   PER_OCCURRENCE  — Applied each time the event triggers (e.g., cheque return)
     *   MONTHLY         — Applied monthly (e.g., account maintenance)
     *   QUARTERLY       — Applied quarterly
     *   ANNUAL          — Applied annually (e.g., annual maintenance charge)
     */
    @Column(name = "frequency", length = 20)
    private String frequency;

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

    /** ISO 4217 currency code. Default INR. Per CBS multi-currency standards. */
    @Column(name = "currency_code", length = 3)
    private String currencyCode;

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

    /**
     * Channel applicability per RBI Digital Banking Framework 2023.
     * Values: BRANCH, ATM, INTERNET, MOBILE, UPI, NEFT, RTGS, IMPS, ALL
     * NULL or 'ALL' = applies to all channels (backward compatible).
     */
    @Column(name = "channel", length = 20)
    private String channel;

    /**
     * Effective start date per Finacle CHRG_MASTER.EFFECTIVE_DATE.
     * Charge is only applicable from this date. NULL = immediately effective.
     * Per RBI Fair Lending Code 2023: charge rate changes must have a prospective
     * effective date — banks cannot retroactively apply new charge rates.
     */
    @Column(name = "valid_from")
    private java.time.LocalDate validFrom;

    /**
     * Effective end date per Finacle CHRG_MASTER.EXPIRY_DATE.
     * Charge expires after this date. NULL = no expiry (perpetual).
     * Per Finacle: expired charges are not applied but retained for audit history.
     */
    @Column(name = "valid_to")
    private java.time.LocalDate validTo;

    /**
     * Customer-facing description per RBI Fair Lending Code 2023.
     * This text appears on customer statements and fee disclosure documents.
     * Per RBI: banks must communicate charge details in clear, simple language.
     */
    @Column(name = "customer_description", length = 500)
    private String customerDescription;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive; // Soft delete flag
}

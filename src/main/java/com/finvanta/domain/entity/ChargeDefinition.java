package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ChargeEventType;

import jakarta.persistence.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Charge Definition per Finacle CHG_MASTER / Temenos FT.COMMISSION.TYPE.
 *
 * Defines the fee schedule for each chargeable event. Each definition specifies:
 * - Whether the charge is a flat amount or percentage of transaction amount
 * - Minimum and maximum charge caps (for percentage-based)
 * - GST applicability (CGST + SGST per GST Act 2017)
 * - GL codes for fee income and customer debit
 * - Product-level scoping (null productCode = applies to all products)
 *
 * Per RBI Fair Practices Code 2023:
 * - All charges must be filed with RBI in the approved schedule of charges
 * - Charges must be transparently disclosed to customers before debit
 * - Penal charges on loans must be "reasonable" per RBI circular Aug 2023
 *
 * Charge Calculation:
 *   If chargeType = FLAT: chargeAmount is the fee
 *   If chargeType = PERCENTAGE: fee = txnAmount * chargePercentage / 100
 *     capped between minCharge and maxCharge
 *   GST (if gstApplicable): CGST = fee * 9%, SGST = fee * 9%
 *   Total debit = fee + CGST + SGST
 *
 * GL Flow:
 *   DR Customer Account (2010/2020) — total debit
 *   CR Fee Income (4002) — base fee
 *   CR CGST Payable (2200) — CGST component
 *   CR SGST Payable (2201) — SGST component
 */
@Entity
@Table(
        name = "charge_definitions",
        indexes = {
            @Index(name = "idx_chgdef_tenant_event",
                    columnList = "tenant_id, event_type"),
            @Index(name = "idx_chgdef_tenant_product_event",
                    columnList = "tenant_id, product_code, event_type")
        })
@Getter
@Setter
@NoArgsConstructor
public class ChargeDefinition extends BaseEntity {

    /** Chargeable event this definition applies to */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ChargeEventType eventType;

    /**
     * Product code this charge applies to. Null = applies to ALL products.
     * Per Finacle CHG_MASTER: charges can be product-specific (e.g., SAVINGS
     * accounts have different cheque book fees than CURRENT accounts).
     */
    @Column(name = "product_code", length = 50)
    private String productCode;

    /** Human-readable charge name for customer statements */
    @Column(name = "charge_name", nullable = false, length = 200)
    private String chargeName;

    /** FLAT or PERCENTAGE */
    @Column(name = "charge_type", nullable = false, length = 15)
    private String chargeType;

    /** Flat fee amount (used when chargeType = FLAT) */
    @Column(name = "charge_amount", precision = 18, scale = 2)
    private BigDecimal chargeAmount = BigDecimal.ZERO;

    /** Percentage of transaction amount (used when chargeType = PERCENTAGE) */
    @Column(name = "charge_percentage", precision = 8, scale = 4)
    private BigDecimal chargePercentage = BigDecimal.ZERO;

    /** Minimum charge cap for percentage-based (floor) */
    @Column(name = "min_charge", precision = 18, scale = 2)
    private BigDecimal minCharge = BigDecimal.ZERO;

    /** Maximum charge cap for percentage-based (ceiling) */
    @Column(name = "max_charge", precision = 18, scale = 2)
    private BigDecimal maxCharge;

    /** Whether GST (CGST 9% + SGST 9%) applies to this charge */
    @Column(name = "gst_applicable", nullable = false)
    private boolean gstApplicable = true;

    /** GL code for fee income credit — typically 4002 */
    @Column(name = "gl_fee_income", nullable = false, length = 20)
    private String glFeeIncome;

    /** Whether this charge definition is active */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Whether this charge can be waived by authorized users.
     * Per RBI Fair Practices: certain regulatory charges (like GST)
     * cannot be waived, but bank-imposed service charges can be.
     */
    @Column(name = "waivable", nullable = false)
    private boolean waivable = true;
}

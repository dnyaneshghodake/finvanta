package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Tenant (Bank/Institution) Master per Finacle BANK_MASTER / Temenos COMPANY.
 *
 * Per RBI Banking Regulation Act 1949 and IT Governance Direction 2023:
 * Every banking institution operating in India must be uniquely identifiable by:
 * - RBI-assigned bank code (4 digits, used in CRILC/SLR/CRR reporting)
 * - IFSC prefix (4 chars, used in NEFT/RTGS/IMPS payment routing)
 * - RBI banking license number (regulatory compliance)
 *
 * Per Finacle multi-bank deployment (Infosys FI Platform):
 * - BANK_ID is the primary partition key across all tables (maps to tenant_code)
 * - Each tenant represents one banking institution with its own GL, products, users
 *
 * Per Temenos Transact (T24) COMPANY entity:
 * - COMPANY.ID format: CC0010001 (country + institution number)
 * - Carries base currency, timezone, regulatory category
 *
 * Column length: tenant_code varchar(20) accommodates both Finacle BANK_ID (4-8 chars)
 * and Temenos COMPANY.ID (10-12 chars) with room for prefixed formats.
 */
@Entity
@Table(
        name = "tenants",
        indexes = {@Index(name = "idx_tenant_code", columnList = "tenant_code", unique = true)})
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique tenant identifier used as partition key across all tables.
     * Per Finacle BANK_ID / Temenos COMPANY.ID.
     * Format: alphanumeric, 4-20 chars (e.g., "FNVT", "SBIN", "IN0010001").
     */
    @Column(name = "tenant_code", nullable = false, unique = true, length = 20)
    private String tenantCode;

    /** Full legal name of the banking institution per RBI registration */
    @Column(name = "tenant_name", nullable = false, length = 200)
    private String tenantName;

    /** License type: ENTERPRISE, STANDARD, TRIAL (deployment licensing) */
    @Column(name = "license_type", length = 50)
    private String licenseType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Database schema for tenant isolation (schema-per-tenant strategy) */
    @Column(name = "db_schema", length = 100)
    private String dbSchema;

    // === RBI Regulatory Fields per Banking Regulation Act 1949 ===

    /**
     * RBI-assigned 4-digit bank code. Used in:
     * - CRILC (Central Repository of Information on Large Credits) reporting
     * - SLR/CRR regulatory returns
     * - RBI OSMOS (Off-site Monitoring and Surveillance System)
     * Example: "0001" (SBI), "0002" (PNB)
     */
    @Column(name = "rbi_bank_code", length = 10)
    private String rbiBankCode;

    /**
     * 4-character IFSC prefix assigned by RBI for payment routing.
     * Used in NEFT/RTGS/IMPS/UPI. First 4 chars of all branch IFSC codes.
     * Example: "SBIN" (SBI), "HDFC" (HDFC Bank), "FNVT" (Finvanta Demo)
     */
    @Column(name = "ifsc_prefix", length = 4)
    private String ifscPrefix;

    /**
     * RBI banking license number per Banking Regulation Act 1949.
     * Format varies by institution type (SCB/UCB/RRB/SFB/PB).
     */
    @Column(name = "license_number", length = 50)
    private String licenseNumber;

    /** License validity date for compliance monitoring */
    @Column(name = "license_expiry")
    private LocalDate licenseExpiry;

    /**
     * RBI regulatory category — determines applicable prudential norms.
     * Per RBI: different categories have different CRR/SLR/NPA/provisioning rules.
     * SCB = Scheduled Commercial Bank
     * UCB = Urban Cooperative Bank
     * RRB = Regional Rural Bank
     * SFB = Small Finance Bank
     * PB  = Payments Bank
     */
    @Column(name = "regulatory_category", length = 20)
    private String regulatoryCategory;

    /** ISO 3166-1 alpha-2 country code (IN for India) */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    /**
     * Base operating currency per ISO 4217 (INR for Indian banks).
     * All GL balances and regulatory reporting in this currency.
     */
    @Column(name = "base_currency", length = 3)
    private String baseCurrency;

    /**
     * IANA timezone for EOD scheduling and business date management.
     * Per Finacle DAYCTRL: EOD trigger time is timezone-dependent.
     * Example: "Asia/Kolkata" for Indian banks
     */
    @Column(name = "timezone", length = 50)
    private String timezone;

    /** Date of incorporation / RBI registration */
    @Column(name = "incorporation_date")
    private LocalDate incorporationDate;

    // === Fiscal Year & Regulatory Configuration (per RBI / Finacle BANK_PARAM) ===

    /**
     * Fiscal year start month (1-12). Per RBI: Indian banks follow April-March FY.
     * Default: 4 (April). Used for:
     * - YTD interest/TDS reset (DepositAccountServiceImpl)
     * - Annual P&L GL zeroing (year-end close)
     * - Regulatory return periods (CRILC quarterly, SLR fortnightly)
     */
    @Column(name = "fiscal_year_start_month", nullable = false)
    private int fiscalYearStartMonth = 4;

    /**
     * Cash Reserve Ratio (CRR) percentage per RBI Monetary Policy.
     * Per RBI Act 1934 §42: scheduled banks must maintain CRR with RBI.
     * Current (2024): 4.50%. Updated via RBI monetary policy announcements.
     * Used for daily CRR compliance reporting and NDTL calculation.
     */
    @Column(name = "crr_percentage", precision = 8, scale = 4)
    private java.math.BigDecimal crrPercentage = new java.math.BigDecimal("4.5000");

    /**
     * Statutory Liquidity Ratio (SLR) percentage per RBI.
     * Per RBI Act 1934 §24: banks must maintain SLR in approved securities.
     * Current (2024): 18.00%. Used for SLR compliance monitoring.
     */
    @Column(name = "slr_percentage", precision = 8, scale = 4)
    private java.math.BigDecimal slrPercentage = new java.math.BigDecimal("18.0000");

    /**
     * Tier-1 capital base amount (INR) for large exposure limit calculation.
     * Per RBI Large Exposure Framework 2019: single borrower limit = 20% of Tier-1.
     * Group borrower limit = 25% of Tier-1. Updated quarterly from capital adequacy returns.
     */
    @Column(name = "tier1_capital_base", precision = 18, scale = 2)
    private java.math.BigDecimal tier1CapitalBase = java.math.BigDecimal.ZERO;

    /**
     * Business day policy: which days are default working days.
     * Per RBI NI Act: Indian banks typically follow MON-FRI or MON-SAT.
     * Values: MON_TO_FRI, MON_TO_SAT
     * Used by calendar generation to auto-mark weekends.
     */
    @Column(name = "business_day_policy", length = 20, nullable = false)
    private String businessDayPolicy = "MON_TO_SAT";

    /**
     * Regulatory reporting template version for XBRL/OSMOS returns.
     * Per RBI: template versions change with regulatory updates.
     * Example: "OSMOS_V4.2", "CRILC_V3.0"
     */
    @Column(name = "regulatory_template_version", length = 30)
    private String regulatoryTemplateVersion;

    /**
     * Maximum value date back-days allowed for transactions.
     * Per Finacle BANK_PARAM / Temenos COMPANY: configurable per tenant.
     * Default: 2 (T-2). Overrides the application.properties default.
     */
    @Column(name = "value_date_back_days", nullable = false)
    private int valueDateBackDays = 2;

    /**
     * Maximum value date forward-days allowed for transactions.
     * Default: 0 (no future dating). Per RBI: future-dated postings
     * require explicit authorization.
     */
    @Column(name = "value_date_forward_days", nullable = false)
    private int valueDateForwardDays = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}

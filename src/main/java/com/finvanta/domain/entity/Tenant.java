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

package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ProductCategory;
import com.finvanta.domain.enums.ProductStatus;

import jakarta.persistence.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Product Master per Finacle PDDEF (Product Definition) / Temenos AA.PRODUCT.CATALOG.
 *
 * In Tier-1 CBS platforms, GL codes are NEVER hardcoded. Every transaction type
 * maps to GL codes through the product definition. This enables:
 * - Different GL codes per product type (Term Loan vs Home Loan vs Gold Loan)
 * - Product-specific interest calculation methods
 * - Product-specific fee schedules
 * - Product-specific repayment allocation priority
 * - Multi-currency product support
 *
 * Lifecycle per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG:
 *   DRAFT     → Product being configured (GL mapping may be incomplete)
 *   ACTIVE    → Live product (new origination allowed, EOD operations run)
 *   SUSPENDED → Temporarily paused (no new origination, existing accounts continue)
 *   RETIRED   → Permanently closed (no new origination, existing run to maturity)
 *
 * Only ACTIVE products can be used for new loan/account origination.
 * SUSPENDED and RETIRED products allow EOD operations on existing accounts.
 *
 * Example:
 *   Product: TERM_LOAN_SECURED
 *   GL Mapping: Disbursement → DR 1001 / CR 1100
 *   Interest Method: Actual/365 Reducing Balance
 *   Penal Rate: 2% p.a.
 *   Min Amount: ₹1,00,000, Max Amount: ₹5,00,00,000
 *   Min Tenure: 12 months, Max Tenure: 84 months
 */
@Entity
@Table(
        name = "product_master",
        indexes = {
            @Index(name = "idx_product_tenant_code", columnList = "tenant_id, product_code", unique = true),
            @Index(name = "idx_product_tenant_active", columnList = "tenant_id, is_active")
        })
@Getter
@Setter
@NoArgsConstructor
public class ProductMaster extends BaseEntity {

    /** Unique product code: TERM_LOAN, HOME_LOAN, GOLD_LOAN, etc. */
    @Column(name = "product_code", nullable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /**
     * Product category per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
     * Determines GL accounting semantics (loan=ASSET vs deposit=LIABILITY).
     * IMMUTABLE after creation — changing category would corrupt GL postings
     * for all existing accounts under this product.
     *
     * Stored as VARCHAR via @Enumerated(EnumType.STRING) for readability and
     * backward compatibility with existing seed data (e.g., 'TERM_LOAN').
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_category", nullable = false, length = 50)
    private ProductCategory productCategory;

    @Column(name = "description", length = 500)
    private String description;

    /** Currency code per ISO 4217 — INR, USD, EUR, etc. */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "INR";

    // --- Interest Configuration ---

    /** Interest calculation method: ACTUAL_365, ACTUAL_360, ACTUAL_ACTUAL, 30_360 */
    @Column(name = "interest_method", nullable = false, length = 30)
    private String interestMethod = "ACTUAL_365";

    /** Interest type: FIXED, FLOATING, HYBRID */
    @Column(name = "interest_type", nullable = false, length = 20)
    private String interestType = "FIXED";

    @Column(name = "min_interest_rate", precision = 8, scale = 4)
    private BigDecimal minInterestRate;

    @Column(name = "max_interest_rate", precision = 8, scale = 4)
    private BigDecimal maxInterestRate;

    /** RBI Fair Lending: Default penal rate (% p.a.) for overdue EMIs */
    @Column(name = "default_penal_rate", precision = 8, scale = 4)
    private BigDecimal defaultPenalRate = new BigDecimal("2.0000");

    // --- Amount & Tenure Limits ---

    @Column(name = "min_loan_amount", precision = 18, scale = 2)
    private BigDecimal minLoanAmount;

    @Column(name = "max_loan_amount", precision = 18, scale = 2)
    private BigDecimal maxLoanAmount;

    @Column(name = "min_tenure_months")
    private Integer minTenureMonths;

    @Column(name = "max_tenure_months")
    private Integer maxTenureMonths;

    /** Repayment frequency: MONTHLY, QUARTERLY, BULLET */
    @Column(name = "repayment_frequency", nullable = false, length = 20)
    private String repaymentFrequency = "MONTHLY";

    // --- GL Code Mapping (Product → GL) ---
    // Per Finacle PDDEF, each product defines which GL codes to hit for each transaction type.

    /** GL code for loan asset (principal outstanding) — typically 1001 */
    @Column(name = "gl_loan_asset", nullable = false, length = 20)
    private String glLoanAsset;

    /** GL code for interest receivable (accrued interest) — typically 1002 */
    @Column(name = "gl_interest_receivable", nullable = false, length = 20)
    private String glInterestReceivable;

    /** GL code for bank operations (disbursement/collection) — typically 1100 */
    @Column(name = "gl_bank_operations", nullable = false, length = 20)
    private String glBankOperations;

    /** GL code for interest income — typically 4001 */
    @Column(name = "gl_interest_income", nullable = false, length = 20)
    private String glInterestIncome;

    /** GL code for fee income — typically 4002 */
    @Column(name = "gl_fee_income", nullable = false, length = 20)
    private String glFeeIncome;

    /** GL code for penal interest income — typically 4003 */
    @Column(name = "gl_penal_income", nullable = false, length = 20)
    private String glPenalIncome;

    /** GL code for provision expense — typically 5001 */
    @Column(name = "gl_provision_expense", nullable = false, length = 20)
    private String glProvisionExpense;

    /** GL code for provision for NPA (contra-asset) — typically 1003 */
    @Column(name = "gl_provision_npa", nullable = false, length = 20)
    private String glProvisionNpa;

    /** GL code for write-off expense — typically 5002 */
    @Column(name = "gl_write_off_expense", nullable = false, length = 20)
    private String glWriteOffExpense;

    /** GL code for interest suspense (NPA) — typically 2100 */
    @Column(name = "gl_interest_suspense", nullable = false, length = 20)
    private String glInterestSuspense;

    // --- Product Status ---

    /**
     * Product lifecycle status per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
     * Controls origination gating and EOD operation eligibility.
     * Stored as VARCHAR via @Enumerated(EnumType.STRING) for readability.
     *
     * CBS Backward Compatibility: existing seed data has is_active=1 but no
     * product_status column. Hibernate ddl-auto=update adds the column with
     * default 'ACTIVE'. The isActive() method returns true for ACTIVE status,
     * maintaining compatibility with all existing code that checks isActive().
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_status", nullable = false, length = 20)
    private ProductStatus productStatus = ProductStatus.ACTIVE;

    /**
     * CBS Backward Compatibility: retained for existing code that checks isActive().
     * Derived from productStatus — ACTIVE status = active=true, all others = false.
     * Per Finacle PDDEF: use productStatus for lifecycle management, isActive() for
     * simple origination-allowed checks.
     */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Repayment allocation priority: INTEREST_FIRST, PRINCIPAL_FIRST, PRO_RATA */
    @Column(name = "repayment_allocation", nullable = false, length = 30)
    private String repaymentAllocation = "INTEREST_FIRST";

    /**
     * CBS Tier-1 Gap #3: Product configuration version counter per Finacle PDDEF.
     *
     * Incremented on every product update (parameter or GL change). This provides:
     *   1. Deterministic version tracking — auditors can query "what was version N?"
     *      by correlating configVersion with the audit_log before/after state
     *   2. Optimistic concurrency beyond JPA @Version — configVersion is business-visible
     *      and included in the audit trail, while JPA version is infrastructure-only
     *   3. Account-level traceability — LoanAccount/DepositAccount can store the
     *      configVersion at origination time to answer "which product version was this
     *      account opened under?" for RBI inspection queries
     *
     * Per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG: every product modification
     * increments the configuration version. The audit_log entry for that version
     * contains the complete before/after field-level diff (Gap #8).
     * Together, configVersion + audit_log = full version history without a separate table.
     */
    @Column(name = "config_version", nullable = false)
    private int configVersion = 1;

    /** Whether prepayment penalty applies (false for floating rate per RBI) */
    @Column(name = "prepayment_penalty_applicable", nullable = false)
    private boolean prepaymentPenaltyApplicable = false;

    /** Processing fee percentage (charged at disbursement) */
    @Column(name = "processing_fee_pct", precision = 8, scale = 4)
    private BigDecimal processingFeePct = BigDecimal.ZERO;

    // === Floating Rate Configuration (per RBI EBLR/MCLR Framework) ===

    /**
     * Default benchmark rate name for floating rate products: EBLR, MCLR, RLLR, T_BILL.
     * Per RBI: all new floating rate retail loans must be linked to an external benchmark
     * (EBLR) since October 2019. Null for fixed-rate products.
     */
    @Column(name = "default_benchmark_name", length = 20)
    private String defaultBenchmarkName;

    /**
     * Default rate reset frequency for floating rate products.
     * Per RBI EBLR framework: reset at least quarterly for EBLR-linked loans.
     * Values: QUARTERLY, HALF_YEARLY, YEARLY. Null for fixed-rate products.
     */
    @Column(name = "default_rate_reset_frequency", length = 20)
    private String defaultRateResetFrequency;

    /**
     * Default spread over benchmark (% p.a.) for this product.
     * Actual spread per account may differ based on credit assessment.
     */
    @Column(name = "default_spread", precision = 8, scale = 4)
    private BigDecimal defaultSpread;

    // === CASA Interest Tiering (per Finacle INTDEF) ===

    /**
     * Whether this product uses balance-based interest tiering.
     * If true, interest rates vary by balance slab (e.g., 3% on first 1L, 3.5% on 1-5L).
     * Tiering slabs are stored in a separate configuration (future enhancement).
     * If false, the flat rate from minInterestRate is used.
     */
    @Column(name = "interest_tiering_enabled", nullable = false)
    private boolean interestTieringEnabled = false;

    /**
     * Interest tiering slabs as JSON for CASA products.
     * Format: [{"min":0,"max":100000,"rate":3.0},{"min":100001,"max":500000,"rate":3.5}]
     * Null if interestTieringEnabled = false.
     */
    @Column(name = "interest_tiering_json", length = 2000)
    private String interestTieringJson;
}

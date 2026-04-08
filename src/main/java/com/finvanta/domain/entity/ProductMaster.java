package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

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
 * Lifecycle: DRAFT → ACTIVE → SUSPENDED → RETIRED
 * Only ACTIVE products can be used for new loan origination.
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
@Table(name = "product_master", indexes = {
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

    /** Product category: TERM_LOAN, OVERDRAFT, CASH_CREDIT, DEMAND_LOAN */
    @Column(name = "product_category", nullable = false, length = 50)
    private String productCategory;

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

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Repayment allocation priority: INTEREST_FIRST, PRINCIPAL_FIRST, PRO_RATA */
    @Column(name = "repayment_allocation", nullable = false, length = 30)
    private String repaymentAllocation = "INTEREST_FIRST";

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

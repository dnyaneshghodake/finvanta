package com.finvanta.domain.rules;

import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.util.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * CBS Loan Eligibility Rule per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * Validates loan applications against:
 *   1. Customer eligibility (KYC, CIBIL, active status)
 *   2. Product-specific limits (amount, tenure, rate) from product_master
 *   3. System-wide fallback limits when no product is configured
 *
 * Per Tier-1 CBS standards, validation limits are ALWAYS product-driven.
 * The hardcoded constants are fallbacks for backward compatibility only --
 * when a product is configured in product_master, its limits take precedence.
 *
 * Usage:
 *   validate(application, customer)          -- uses fallback limits
 *   validate(application, customer, product) -- uses product-specific limits
 */
@Component
public class LoanEligibilityRule {

    // --- System-wide fallback limits (used when product_master is not configured) ---
    private static final int MIN_CIBIL_SCORE = 650;
    private static final BigDecimal FALLBACK_MAX_LOAN_AMOUNT = new BigDecimal("50000000.00");
    private static final BigDecimal FALLBACK_MIN_LOAN_AMOUNT = new BigDecimal("10000.00");
    private static final int FALLBACK_MAX_TENURE_MONTHS = 360;
    private static final int FALLBACK_MIN_TENURE_MONTHS = 3;
    private static final BigDecimal FALLBACK_MAX_INTEREST_RATE = new BigDecimal("36.0000");
    private static final BigDecimal FALLBACK_MIN_INTEREST_RATE = new BigDecimal("1.0000");

    /**
     * Validates with system-wide fallback limits (backward compatible).
     * Used when product_master is not available or not configured.
     */
    public void validate(LoanApplication application, Customer customer) {
        validate(application, customer, null);
    }

    /**
     * Validates with product-specific limits from product_master.
     * Falls back to system-wide defaults for any limit not configured on the product.
     *
     * Per Finacle PDDEF: every product defines its own amount, tenure, and rate bounds.
     * A GOLD_LOAN may allow INR 50K-50L with 3-36 months, while a HOME_LOAN allows
     * INR 5L-5Cr with 12-360 months. Using the same hardcoded limits for all products
     * is not Tier-1 compliant.
     *
     * @param application The loan application to validate
     * @param customer    The applicant customer
     * @param product     The product master (null = use fallback limits)
     */
    public void validate(LoanApplication application, Customer customer, ProductMaster product) {
        // --- Customer eligibility (product-independent) ---
        validateCustomerEligibility(customer);

        // --- Resolve limits: product-specific or fallback ---
        BigDecimal minAmount = (product != null && product.getMinLoanAmount() != null)
            ? product.getMinLoanAmount() : FALLBACK_MIN_LOAN_AMOUNT;
        BigDecimal maxAmount = (product != null && product.getMaxLoanAmount() != null)
            ? product.getMaxLoanAmount() : FALLBACK_MAX_LOAN_AMOUNT;
        int minTenure = (product != null && product.getMinTenureMonths() != null)
            ? product.getMinTenureMonths() : FALLBACK_MIN_TENURE_MONTHS;
        int maxTenure = (product != null && product.getMaxTenureMonths() != null)
            ? product.getMaxTenureMonths() : FALLBACK_MAX_TENURE_MONTHS;
        BigDecimal minRate = (product != null && product.getMinInterestRate() != null)
            ? product.getMinInterestRate() : FALLBACK_MIN_INTEREST_RATE;
        BigDecimal maxRate = (product != null && product.getMaxInterestRate() != null)
            ? product.getMaxInterestRate() : FALLBACK_MAX_INTEREST_RATE;

        String productLabel = (product != null) ? product.getProductCode() : "SYSTEM";

        // --- Amount validation ---
        if (application.getRequestedAmount().compareTo(minAmount) < 0) {
            throw new BusinessException("ELIGIBILITY_AMOUNT_TOO_LOW",
                "Requested amount INR " + application.getRequestedAmount()
                    + " is below minimum INR " + minAmount + " for product " + productLabel);
        }
        if (application.getRequestedAmount().compareTo(maxAmount) > 0) {
            throw new BusinessException("ELIGIBILITY_AMOUNT_TOO_HIGH",
                "Requested amount INR " + application.getRequestedAmount()
                    + " exceeds maximum INR " + maxAmount + " for product " + productLabel);
        }

        // --- Tenure validation ---
        if (application.getTenureMonths() < minTenure || application.getTenureMonths() > maxTenure) {
            throw new BusinessException("ELIGIBILITY_TENURE_INVALID",
                "Tenure " + application.getTenureMonths() + " months is outside allowed range "
                    + minTenure + "-" + maxTenure + " months for product " + productLabel);
        }

        // --- Interest rate validation ---
        if (application.getInterestRate().compareTo(minRate) < 0
                || application.getInterestRate().compareTo(maxRate) > 0) {
            throw new BusinessException("ELIGIBILITY_RATE_INVALID",
                "Interest rate " + application.getInterestRate() + "% is outside allowed range "
                    + minRate + "%-" + maxRate + "% for product " + productLabel);
        }
    }

    /**
     * Customer-level eligibility checks (product-independent).
     * Per RBI KYC norms and CIBIL guidelines.
     */
    private void validateCustomerEligibility(Customer customer) {
        if (!customer.isKycVerified()) {
            throw new BusinessException("ELIGIBILITY_KYC_FAILED",
                "Customer KYC verification is mandatory before loan application");
        }

        if (!customer.isActive()) {
            throw new BusinessException("ELIGIBILITY_CUSTOMER_INACTIVE",
                "Customer account is not active");
        }

        if (customer.getCibilScore() != null && customer.getCibilScore() < MIN_CIBIL_SCORE) {
            throw new BusinessException("ELIGIBILITY_CIBIL_LOW",
                "CIBIL score " + customer.getCibilScore()
                    + " is below minimum threshold of " + MIN_CIBIL_SCORE);
        }
    }
}

package com.finvanta.domain.rules;

import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.util.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class LoanEligibilityRule {

    private static final int MIN_CIBIL_SCORE = 650;
    private static final BigDecimal MAX_LOAN_AMOUNT = new BigDecimal("50000000.00");
    private static final BigDecimal MIN_LOAN_AMOUNT = new BigDecimal("10000.00");
    private static final int MAX_TENURE_MONTHS = 360;
    private static final int MIN_TENURE_MONTHS = 3;
    private static final BigDecimal MAX_INTEREST_RATE = new BigDecimal("36.0000");
    private static final BigDecimal MIN_INTEREST_RATE = new BigDecimal("1.0000");

    public void validate(LoanApplication application, Customer customer) {
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
                "CIBIL score " + customer.getCibilScore() + " is below minimum threshold of " + MIN_CIBIL_SCORE);
        }

        if (application.getRequestedAmount().compareTo(MIN_LOAN_AMOUNT) < 0) {
            throw new BusinessException("ELIGIBILITY_AMOUNT_TOO_LOW",
                "Requested amount is below minimum of " + MIN_LOAN_AMOUNT);
        }

        if (application.getRequestedAmount().compareTo(MAX_LOAN_AMOUNT) > 0) {
            throw new BusinessException("ELIGIBILITY_AMOUNT_TOO_HIGH",
                "Requested amount exceeds maximum of " + MAX_LOAN_AMOUNT);
        }

        if (application.getTenureMonths() < MIN_TENURE_MONTHS || application.getTenureMonths() > MAX_TENURE_MONTHS) {
            throw new BusinessException("ELIGIBILITY_TENURE_INVALID",
                "Tenure must be between " + MIN_TENURE_MONTHS + " and " + MAX_TENURE_MONTHS + " months");
        }

        if (application.getInterestRate().compareTo(MIN_INTEREST_RATE) < 0
                || application.getInterestRate().compareTo(MAX_INTEREST_RATE) > 0) {
            throw new BusinessException("ELIGIBILITY_RATE_INVALID",
                "Interest rate must be between " + MIN_INTEREST_RATE + "% and " + MAX_INTEREST_RATE + "%");
        }
    }
}

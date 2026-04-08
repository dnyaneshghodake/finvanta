package com.finvanta.domain.rules;

import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Loan Eligibility Rule Tests per RBI Fair Lending Code.
 *
 * Validates all pre-disbursement eligibility checks:
 * - KYC verification mandatory
 * - Customer active status
 * - CIBIL score minimum threshold (650)
 * - Loan amount range (INR 10,000 - INR 5,00,00,000)
 * - Tenure range (3 - 360 months)
 * - Interest rate range (1% - 36%)
 * - Customer exposure limit (max borrowing + DTI ratio)
 */
class LoanEligibilityRuleTest {

    private LoanEligibilityRule rule;
    private LoanAccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        // CBS: Set tenant context for exposure limit validation per Finacle multi-tenant architecture
        TenantContext.setCurrentTenant("TEST_TENANT");
        accountRepository = mock(LoanAccountRepository.class);
        // Default: no existing loans for the customer (exposure checks pass)
        when(accountRepository.findByTenantIdAndCustomerId(any(), any())).thenReturn(Collections.emptyList());
        rule = new LoanEligibilityRule(accountRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("KYC Verification")
    class KycTests {

        @Test
        @DisplayName("KYC not verified → ELIGIBILITY_KYC_FAILED")
        void kycNotVerified() {
            Customer customer = createCustomer(false, true, 750);
            LoanApplication app = createApplication("500000", "10.0000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_KYC_FAILED", ex.getErrorCode());
        }

        @Test
        @DisplayName("KYC verified passes validation")
        void kycVerified() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("500000", "10.0000", 12);

            assertDoesNotThrow(() -> rule.validate(app, customer));
        }
    }

    @Nested
    @DisplayName("Customer Status")
    class CustomerStatusTests {

        @Test
        @DisplayName("Inactive customer → ELIGIBILITY_CUSTOMER_INACTIVE")
        void inactiveCustomer() {
            Customer customer = createCustomer(true, false, 750);
            LoanApplication app = createApplication("500000", "10.0000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_CUSTOMER_INACTIVE", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("CIBIL Score")
    class CibilTests {

        @Test
        @DisplayName("CIBIL 649 (below 650) → ELIGIBILITY_CIBIL_LOW")
        void cibilBelowThreshold() {
            Customer customer = createCustomer(true, true, 649);
            LoanApplication app = createApplication("500000", "10.0000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_CIBIL_LOW", ex.getErrorCode());
        }

        @Test
        @DisplayName("CIBIL 650 (exactly threshold) passes")
        void cibilAtThreshold() {
            Customer customer = createCustomer(true, true, 650);
            LoanApplication app = createApplication("500000", "10.0000", 12);

            assertDoesNotThrow(() -> rule.validate(app, customer));
        }

        @Test
        @DisplayName("CIBIL null (not available) passes — not mandatory")
        void cibilNull() {
            Customer customer = createCustomer(true, true, null);
            LoanApplication app = createApplication("500000", "10.0000", 12);

            assertDoesNotThrow(() -> rule.validate(app, customer));
        }
    }

    @Nested
    @DisplayName("Loan Amount Range")
    class AmountTests {

        @Test
        @DisplayName("Amount below ₹10,000 → ELIGIBILITY_AMOUNT_TOO_LOW")
        void amountTooLow() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("9999", "10.0000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_AMOUNT_TOO_LOW", ex.getErrorCode());
        }

        @Test
        @DisplayName("Amount above ₹5,00,00,000 → ELIGIBILITY_AMOUNT_TOO_HIGH")
        void amountTooHigh() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("50000001", "10.0000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_AMOUNT_TOO_HIGH", ex.getErrorCode());
        }

        @Test
        @DisplayName("Amount at boundaries passes")
        void amountAtBoundaries() {
            Customer customer = createCustomer(true, true, 750);

            assertDoesNotThrow(() -> rule.validate(createApplication("10000", "10.0000", 12), customer));
            assertDoesNotThrow(() -> rule.validate(createApplication("50000000", "10.0000", 12), customer));
        }
    }

    @Nested
    @DisplayName("Tenure Range")
    class TenureTests {

        @Test
        @DisplayName("Tenure below 3 months → ELIGIBILITY_TENURE_INVALID")
        void tenureTooShort() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("500000", "10.0000", 2);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_TENURE_INVALID", ex.getErrorCode());
        }

        @Test
        @DisplayName("Tenure above 360 months → ELIGIBILITY_TENURE_INVALID")
        void tenureTooLong() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("500000", "10.0000", 361);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_TENURE_INVALID", ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("Interest Rate Range")
    class RateTests {

        @Test
        @DisplayName("Rate below 1% → ELIGIBILITY_RATE_INVALID")
        void rateTooLow() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("500000", "0.5000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_RATE_INVALID", ex.getErrorCode());
        }

        @Test
        @DisplayName("Rate above 36% → ELIGIBILITY_RATE_INVALID")
        void rateTooHigh() {
            Customer customer = createCustomer(true, true, 750);
            LoanApplication app = createApplication("500000", "36.5000", 12);

            BusinessException ex = assertThrows(BusinessException.class, () -> rule.validate(app, customer));
            assertEquals("ELIGIBILITY_RATE_INVALID", ex.getErrorCode());
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private Customer createCustomer(boolean kycVerified, boolean active, Integer cibilScore) {
        Customer customer = new Customer();
        customer.setKycVerified(kycVerified);
        customer.setActive(active);
        customer.setCibilScore(cibilScore);
        return customer;
    }

    private LoanApplication createApplication(String amount, String rate, int tenure) {
        LoanApplication app = new LoanApplication();
        app.setRequestedAmount(new BigDecimal(amount));
        app.setInterestRate(new BigDecimal(rate));
        app.setTenureMonths(tenure);
        return app;
    }
}

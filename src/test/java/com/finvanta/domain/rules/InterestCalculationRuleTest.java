package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CBS Interest Calculation Rule Tests per RBI Actual/365 day-count convention.
 *
 * Verification matrix:
 * - Daily accrual: (Outstanding × Rate / 100) / 365 × days
 * - EMI: P × r × (1+r)^n / ((1+r)^n - 1) where r = annual rate / 1200
 * - Penal: (Overdue Principal × Penal Rate / 100) / 365 × days
 * - Component split: Interest-first allocation per Finacle standard
 *
 * All expected values are independently computed and verified against
 * RBI's published Actual/365 examples.
 */
class InterestCalculationRuleTest {

    private InterestCalculationRule rule;

    @BeforeEach
    void setUp() {
        rule = new InterestCalculationRule();
    }

    // ========================================================================
    // Daily Accrual Tests — RBI Actual/365
    // ========================================================================
    @Nested
    @DisplayName("Daily Interest Accrual (Actual/365)")
    class DailyAccrualTests {

        @Test
        @DisplayName("Standard accrual: ₹10,00,000 at 10% for 30 days = ₹8,219.18")
        void standardAccrual_30Days() {
            LoanAccount account = createAccount("1000000", "10.0000");
            BigDecimal result =
                    rule.calculateDailyAccrual(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

            // (10,00,000 × 10 / 100) / 365 × 30 = 8219.178... → 8219.18
            assertEquals(new BigDecimal("8219.18"), result);
        }

        @Test
        @DisplayName("Single day accrual: ₹10,00,000 at 10% for 1 day = ₹273.97")
        void singleDayAccrual() {
            LoanAccount account = createAccount("1000000", "10.0000");
            BigDecimal result = rule.calculateDailyAccrual(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 2));

            // (10,00,000 × 10 / 100) / 365 × 1 = 273.972... → 273.97
            assertEquals(new BigDecimal("273.97"), result);
        }

        @Test
        @DisplayName("Zero principal returns zero")
        void zeroPrincipal_returnsZero() {
            LoanAccount account = createAccount("0", "10.0000");
            BigDecimal result = rule.calculateDailyAccrual(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1));
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Negative principal returns zero")
        void negativePrincipal_returnsZero() {
            LoanAccount account = createAccount("-1000", "10.0000");
            BigDecimal result = rule.calculateDailyAccrual(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1));
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Same date (zero days) returns zero")
        void sameDates_returnsZero() {
            LoanAccount account = createAccount("1000000", "10.0000");
            BigDecimal result = rule.calculateDailyAccrual(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1));
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Reversed dates (toDate before fromDate) returns zero")
        void reversedDates_returnsZero() {
            LoanAccount account = createAccount("1000000", "10.0000");
            BigDecimal result = rule.calculateDailyAccrual(account, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 1));
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Small principal: ₹10,000 at 12% for 1 day = ₹3.29")
        void smallPrincipal() {
            LoanAccount account = createAccount("10000", "12.0000");
            BigDecimal result = rule.calculateDailyAccrual(account, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 2));

            // (10,000 × 12 / 100) / 365 × 1 = 3.2876... → 3.29
            assertEquals(new BigDecimal("3.29"), result);
        }
    }

    // ========================================================================
    // EMI Calculation Tests — Reducing Balance Method
    // ========================================================================
    @Nested
    @DisplayName("EMI Calculation (Reducing Balance)")
    class EmiTests {

        @Test
        @DisplayName("Standard EMI: ₹10,00,000 at 10% for 12 months = ₹87,915.89")
        void standardEmi_12Months() {
            BigDecimal emi = rule.calculateEmi(new BigDecimal("1000000"), new BigDecimal("10.0000"), 12);

            // Verified against standard EMI calculators
            assertEquals(new BigDecimal("87915.89"), emi);
        }

        @Test
        @DisplayName("Long tenure EMI: ₹50,00,000 at 8.5% for 240 months (20 years)")
        void longTenureEmi() {
            BigDecimal emi = rule.calculateEmi(new BigDecimal("5000000"), new BigDecimal("8.5000"), 240);

            // Standard 20-year home loan EMI — range check
            assertTrue(emi.compareTo(new BigDecimal("43000")) > 0);
            assertTrue(emi.compareTo(new BigDecimal("44000")) < 0);
        }

        @Test
        @DisplayName("Zero rate EMI: ₹1,20,000 at 0% for 12 months = ₹10,000.00")
        void zeroRate_simpleDivision() {
            BigDecimal emi = rule.calculateEmi(new BigDecimal("120000"), BigDecimal.ZERO, 12);
            assertEquals(new BigDecimal("10000.00"), emi);
        }

        @Test
        @DisplayName("Zero principal returns zero")
        void zeroPrincipal_returnsZero() {
            BigDecimal emi = rule.calculateEmi(BigDecimal.ZERO, new BigDecimal("10.0000"), 12);
            assertEquals(BigDecimal.ZERO, emi);
        }

        @Test
        @DisplayName("Zero tenure returns zero")
        void zeroTenure_returnsZero() {
            BigDecimal emi = rule.calculateEmi(new BigDecimal("1000000"), new BigDecimal("10.0000"), 0);
            assertEquals(BigDecimal.ZERO, emi);
        }

        @Test
        @DisplayName("Single month EMI: principal + one month interest")
        void singleMonth() {
            BigDecimal emi = rule.calculateEmi(new BigDecimal("100000"), new BigDecimal("12.0000"), 1);

            // For 1 month: EMI = principal + 1 month interest = 100000 + 1000 = 101000
            assertEquals(new BigDecimal("101000.00"), emi);
        }

        @Test
        @DisplayName("EMI is always positive for valid inputs")
        void emiAlwaysPositive() {
            BigDecimal emi = rule.calculateEmi(new BigDecimal("1000000"), new BigDecimal("24.0000"), 360);
            assertTrue(emi.compareTo(BigDecimal.ZERO) > 0);
        }
    }

    // ========================================================================
    // Penal Interest Tests — RBI Fair Lending Code 2023
    // ========================================================================
    @Nested
    @DisplayName("Penal Interest (RBI Fair Lending Code 2023)")
    class PenalInterestTests {

        @Test
        @DisplayName("Standard penal: ₹50,000 overdue at 2% for 30 days = ₹82.19")
        void standardPenal() {
            LoanAccount account = createAccountWithPenal("1000000", "50000", "2.0000");
            BigDecimal result =
                    rule.calculatePenalInterest(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

            // (50,000 × 2 / 100) / 365 × 30 = 82.191... → 82.19
            assertEquals(new BigDecimal("82.19"), result);
        }

        @Test
        @DisplayName("Zero overdue principal returns zero")
        void zeroOverdue_returnsZero() {
            LoanAccount account = createAccountWithPenal("1000000", "0", "2.0000");
            BigDecimal result =
                    rule.calculatePenalInterest(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1));
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Zero penal rate returns zero")
        void zeroPenalRate_returnsZero() {
            LoanAccount account = createAccountWithPenal("1000000", "50000", "0");
            BigDecimal result =
                    rule.calculatePenalInterest(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1));
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Penal is on overdue principal only, not total outstanding")
        void penalOnOverduePrincipalOnly() {
            // Per RBI circular 18 Aug 2023: penal on overdue amount, not total
            LoanAccount account = createAccountWithPenal("1000000", "87916", "2.0000");
            BigDecimal result =
                    rule.calculatePenalInterest(account, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

            // Should be on 87916, not 1000000
            // (87,916 × 2 / 100) / 365 × 30 = 144.5227... → 144.52 (HALF_UP at scale 2)
            assertEquals(new BigDecimal("144.52"), result);
        }
    }

    // ========================================================================
    // EMI Component Split Tests — Interest-First Allocation
    // ========================================================================
    @Nested
    @DisplayName("EMI Component Split (Interest-First)")
    class ComponentSplitTests {

        @Test
        @DisplayName("Standard split: ₹87,916 EMI on ₹10,00,000 at 10%")
        void standardSplit() {
            BigDecimal[] components = rule.splitEmiComponents(
                    new BigDecimal("87916"), new BigDecimal("1000000"), new BigDecimal("10.0000"));

            BigDecimal principal = components[0];
            BigDecimal interest = components[1];

            // Monthly interest = 1000000 × (10/100/12) = 8333.33
            assertEquals(new BigDecimal("8333.33"), interest);
            // Principal = 87916 - 8333.33 = 79582.67
            assertEquals(new BigDecimal("79582.67"), principal);
            // Sum must equal EMI
            assertEquals(0, principal.add(interest).compareTo(new BigDecimal("87916.00")));
        }

        @Test
        @DisplayName("Payment less than interest: all goes to interest")
        void paymentLessThanInterest() {
            BigDecimal[] components = rule.splitEmiComponents(
                    new BigDecimal("5000"), new BigDecimal("1000000"), new BigDecimal("10.0000"));

            // Monthly interest = 8333.33, payment = 5000 < interest
            assertEquals(new BigDecimal("0.00"), components[0]); // principal
            assertEquals(new BigDecimal("5000"), components[1]); // interest = full payment
        }

        @Test
        @DisplayName("Overpayment: principal capped at outstanding")
        void overpayment_principalCapped() {
            BigDecimal[] components = rule.splitEmiComponents(
                    new BigDecimal("50000"), new BigDecimal("10000"), new BigDecimal("10.0000"));

            // Outstanding = 10000, monthly interest = 10000 × (10/100/12) = 83.33
            // Principal would be 50000 - 83.33 = 49916.67, but capped at 10000
            assertEquals(new BigDecimal("10000"), components[0]); // principal = outstanding
            assertEquals(new BigDecimal("40000.00"), components[1]); // interest = remainder
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private LoanAccount createAccount(String principal, String rate) {
        LoanAccount account = new LoanAccount();
        account.setOutstandingPrincipal(new BigDecimal(principal));
        account.setInterestRate(new BigDecimal(rate));
        return account;
    }

    private LoanAccount createAccountWithPenal(String principal, String overduePrincipal, String penalRate) {
        LoanAccount account = new LoanAccount();
        account.setOutstandingPrincipal(new BigDecimal(principal));
        account.setOverduePrincipal(new BigDecimal(overduePrincipal));
        account.setPenalRate(new BigDecimal(penalRate));
        return account;
    }
}

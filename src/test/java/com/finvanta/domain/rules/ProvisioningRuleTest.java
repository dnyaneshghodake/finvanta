package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBI IRAC Provisioning Rule Tests.
 *
 * Per RBI Master Circular on Prudential Norms:
 *   Standard (ACTIVE, SMA-0/1/2): 0.40% of outstanding
 *   Restructured:                  5.00% (RBI CDR norms — first 2 years)
 *   Sub-Standard:                 10.00%
 *   Doubtful (simplified avg):    40.00%
 *   Loss:                        100.00%
 *   Closed/Written-Off:            0.00%
 */
class ProvisioningRuleTest {

    private ProvisioningRule rule;

    @BeforeEach
    void setUp() {
        rule = new ProvisioningRule();
    }

    // ========================================================================
    // Provisioning Rate Tests — All Asset Classifications
    // ========================================================================
    @Nested
    @DisplayName("Provisioning Rates by Status")
    class RateTests {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            "ACTIVE, 0.0040",
            "SMA_0, 0.0040",
            "SMA_1, 0.0040",
            "SMA_2, 0.0040",
            "RESTRUCTURED, 0.05",
            "NPA_SUBSTANDARD, 0.10",
            "NPA_DOUBTFUL, 0.40",
            "NPA_LOSS, 1",
            "CLOSED, 0",
            "WRITTEN_OFF, 0"
        })
        void provisioningRateByStatus(LoanStatus status, BigDecimal expectedRate) {
            assertEquals(0, expectedRate.compareTo(rule.getProvisioningRate(status)),
                status + " should have rate " + expectedRate);
        }
    }

    // ========================================================================
    // Provisioning Amount Calculation Tests
    // ========================================================================
    @Nested
    @DisplayName("Provisioning Amount Calculation")
    class AmountTests {

        @Test
        @DisplayName("Standard: ₹10,00,000 × 0.40% = ₹4,000.00")
        void standardProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.ACTIVE);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("4000.00"), result);
        }

        @Test
        @DisplayName("SMA-2: ₹10,00,000 × 0.40% = ₹4,000.00 (same as standard)")
        void sma2Provisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.SMA_2);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("4000.00"), result);
        }

        @Test
        @DisplayName("Restructured: ₹10,00,000 × 5% = ₹50,000.00")
        void restructuredProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.RESTRUCTURED);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("50000.00"), result);
        }

        @Test
        @DisplayName("Sub-Standard: ₹10,00,000 × 10% = ₹1,00,000.00")
        void subStandardProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.NPA_SUBSTANDARD);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("100000.00"), result);
        }

        @Test
        @DisplayName("Doubtful: ₹10,00,000 × 40% = ₹4,00,000.00")
        void doubtfulProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.NPA_DOUBTFUL);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("400000.00"), result);
        }

        @Test
        @DisplayName("Loss: ₹10,00,000 × 100% = ₹10,00,000.00")
        void lossProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.NPA_LOSS);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("1000000.00"), result);
        }

        @Test
        @DisplayName("Closed account: provisioning = ₹0.00")
        void closedProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.CLOSED);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("0.00"), result);
        }

        @Test
        @DisplayName("Written-off account: provisioning = ₹0.00")
        void writtenOffProvisioning() {
            LoanAccount account = createAccount("1000000", LoanStatus.WRITTEN_OFF);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("0.00"), result);
        }

        @Test
        @DisplayName("Zero outstanding: provisioning = ₹0.00 regardless of status")
        void zeroOutstanding() {
            LoanAccount account = createAccount("0", LoanStatus.NPA_LOSS);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(BigDecimal.ZERO, result);
        }

        @Test
        @DisplayName("Small outstanding: ₹10,000 Sub-Standard = ₹1,000.00")
        void smallOutstanding() {
            LoanAccount account = createAccount("10000", LoanStatus.NPA_SUBSTANDARD);
            BigDecimal result = rule.calculateProvisioning(account);
            assertEquals(new BigDecimal("1000.00"), result);
        }
    }

    // ========================================================================
    // Provisioning Delta Tests (for GL posting verification)
    // ========================================================================
    @Nested
    @DisplayName("Provisioning Delta (Upgrade/Downgrade)")
    class DeltaTests {

        @Test
        @DisplayName("Upgrade from Sub-Standard to Doubtful: delta = +₹3,00,000")
        void upgradeToDoubtful() {
            LoanAccount subStd = createAccount("1000000", LoanStatus.NPA_SUBSTANDARD);
            LoanAccount doubtful = createAccount("1000000", LoanStatus.NPA_DOUBTFUL);

            BigDecimal subStdProv = rule.calculateProvisioning(subStd);
            BigDecimal doubtfulProv = rule.calculateProvisioning(doubtful);
            BigDecimal delta = doubtfulProv.subtract(subStdProv);

            assertEquals(new BigDecimal("300000.00"), delta);
            assertTrue(delta.compareTo(BigDecimal.ZERO) > 0, "Worsening should increase provisioning");
        }

        @Test
        @DisplayName("Downgrade from Sub-Standard to Active: delta = -₹96,000")
        void downgradeToActive() {
            LoanAccount subStd = createAccount("1000000", LoanStatus.NPA_SUBSTANDARD);
            LoanAccount active = createAccount("1000000", LoanStatus.ACTIVE);

            BigDecimal subStdProv = rule.calculateProvisioning(subStd);
            BigDecimal activeProv = rule.calculateProvisioning(active);
            BigDecimal delta = activeProv.subtract(subStdProv);

            assertEquals(new BigDecimal("-96000.00"), delta);
            assertTrue(delta.compareTo(BigDecimal.ZERO) < 0, "Improvement should decrease provisioning");
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private LoanAccount createAccount(String outstanding, LoanStatus status) {
        LoanAccount account = new LoanAccount();
        account.setOutstandingPrincipal(new BigDecimal(outstanding));
        account.setStatus(status);
        return account;
    }
}

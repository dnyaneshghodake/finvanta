package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBI IRAC Asset Classification Tests.
 *
 * Per RBI Master Circular on Prudential Norms on Income Recognition,
 * Asset Classification and Provisioning (IRAC):
 *
 * DPD thresholds:
 *   0 DPD         → ACTIVE (Standard)
 *   1-30 DPD      → SMA-0 (Special Mention Account — Early Warning)
 *   31-60 DPD     → SMA-1
 *   61-90 DPD     → SMA-2
 *   91-365 DPD    → NPA Sub-Standard
 *   366-1095 DPD  → NPA Doubtful
 *   >1095 DPD     → NPA Loss
 *
 * Key RBI rules tested:
 * 1. SMA status is fluid (improves/worsens with DPD changes)
 * 2. NPA status is sticky (can only worsen, never auto-downgrade)
 * 3. Restructured accounts retain status unless crossing NPA threshold
 * 4. Terminal states (CLOSED, WRITTEN_OFF) are never reclassified
 */
class NpaClassificationRuleTest {

    private NpaClassificationRule rule;

    @BeforeEach
    void setUp() {
        rule = new NpaClassificationRule();
    }

    // ========================================================================
    // DPD-Based Classification — Boundary Tests
    // ========================================================================
    @Nested
    @DisplayName("DPD-Based Classification Boundaries")
    class DpdBoundaryTests {

        @ParameterizedTest(name = "DPD {0} → {1}")
        @CsvSource({
            "0, ACTIVE",
            "1, SMA_0",
            "30, SMA_0",
            "31, SMA_1",
            "60, SMA_1",
            "61, SMA_2",
            "90, SMA_2",
            "91, NPA_SUBSTANDARD",
            "365, NPA_SUBSTANDARD",
            "366, NPA_DOUBTFUL",
            "1095, NPA_DOUBTFUL",
            "1096, NPA_LOSS",
            "2000, NPA_LOSS"
        })
        @DisplayName("DPD boundary classification")
        void dpdBoundaries(int dpd, LoanStatus expected) {
            LoanAccount account = createAccount(dpd, LoanStatus.ACTIVE);
            LoanStatus result = rule.classify(account);
            assertEquals(expected, result, "DPD " + dpd + " should classify as " + expected);
        }
    }

    // ========================================================================
    // SMA Fluidity — Can Improve and Worsen
    // ========================================================================
    @Nested
    @DisplayName("SMA Status Fluidity")
    class SmaFluidityTests {

        @Test
        @DisplayName("SMA-2 improves to SMA-0 when DPD drops to 15")
        void sma2_improvesToSma0() {
            LoanAccount account = createAccount(15, LoanStatus.SMA_2);
            assertEquals(LoanStatus.SMA_0, rule.classify(account));
        }

        @Test
        @DisplayName("SMA-0 worsens to SMA-2 when DPD rises to 75")
        void sma0_worsensToSma2() {
            LoanAccount account = createAccount(75, LoanStatus.SMA_0);
            assertEquals(LoanStatus.SMA_2, rule.classify(account));
        }

        @Test
        @DisplayName("SMA returns to ACTIVE when DPD drops to 0")
        void sma_returnsToActive() {
            LoanAccount account = createAccount(0, LoanStatus.SMA_1);
            assertEquals(LoanStatus.ACTIVE, rule.classify(account));
        }
    }

    // ========================================================================
    // NPA Stickiness — Can Only Worsen, Never Auto-Downgrade
    // ========================================================================
    @Nested
    @DisplayName("NPA Sticky Classification (RBI IRAC)")
    class NpaStickinessTests {

        @Test
        @DisplayName("NPA Sub-Standard stays NPA even if DPD drops to 50 (SMA-1 range)")
        void npaSubstandard_staysNpa_whenDpdDrops() {
            LoanAccount account = createAccount(50, LoanStatus.NPA_SUBSTANDARD);
            // DPD 50 would normally be SMA-1, but NPA is sticky
            assertEquals(LoanStatus.NPA_SUBSTANDARD, rule.classify(account));
        }

        @Test
        @DisplayName("NPA Doubtful stays Doubtful even if DPD drops to 100 (Sub-Standard range)")
        void npaDoubtful_staysDoubtful_whenDpdDrops() {
            LoanAccount account = createAccount(100, LoanStatus.NPA_DOUBTFUL);
            // DPD 100 would be Sub-Standard, but Doubtful is more severe — stays
            assertEquals(LoanStatus.NPA_DOUBTFUL, rule.classify(account));
        }

        @Test
        @DisplayName("NPA Loss stays Loss regardless of DPD")
        void npaLoss_staysLoss() {
            LoanAccount account = createAccount(0, LoanStatus.NPA_LOSS);
            assertEquals(LoanStatus.NPA_LOSS, rule.classify(account));
        }

        @Test
        @DisplayName("NPA Sub-Standard worsens to Doubtful when DPD crosses 365")
        void npaSubstandard_worsensToDoubtful() {
            LoanAccount account = createAccount(400, LoanStatus.NPA_SUBSTANDARD);
            assertEquals(LoanStatus.NPA_DOUBTFUL, rule.classify(account));
        }

        @Test
        @DisplayName("NPA Doubtful worsens to Loss when DPD crosses 1095")
        void npaDoubtful_worsensToLoss() {
            LoanAccount account = createAccount(1200, LoanStatus.NPA_DOUBTFUL);
            assertEquals(LoanStatus.NPA_LOSS, rule.classify(account));
        }
    }

    // ========================================================================
    // Restructured Account Rules — RBI CDR/SDR Framework
    // ========================================================================
    @Nested
    @DisplayName("Restructured Account Classification (RBI CDR)")
    class RestructuredTests {

        @Test
        @DisplayName("Restructured stays Restructured when DPD < 91")
        void restructured_staysRestructured_belowNpaThreshold() {
            LoanAccount account = createAccount(50, LoanStatus.RESTRUCTURED);
            assertEquals(LoanStatus.RESTRUCTURED, rule.classify(account));
        }

        @Test
        @DisplayName("Restructured transitions to NPA when DPD crosses 91")
        void restructured_becomesNpa_atThreshold() {
            LoanAccount account = createAccount(91, LoanStatus.RESTRUCTURED);
            assertEquals(LoanStatus.NPA_SUBSTANDARD, rule.classify(account));
        }

        @Test
        @DisplayName("Restructured with DPD 0 stays Restructured (not downgraded to ACTIVE)")
        void restructured_staysRestructured_atZeroDpd() {
            LoanAccount account = createAccount(0, LoanStatus.RESTRUCTURED);
            assertEquals(LoanStatus.RESTRUCTURED, rule.classify(account));
        }
    }

    // ========================================================================
    // Terminal States — Never Reclassified
    // ========================================================================
    @Nested
    @DisplayName("Terminal States")
    class TerminalStateTests {

        @Test
        @DisplayName("CLOSED account is never reclassified")
        void closed_neverReclassified() {
            LoanAccount account = createAccount(100, LoanStatus.CLOSED);
            assertEquals(LoanStatus.CLOSED, rule.classify(account));
        }

        @Test
        @DisplayName("WRITTEN_OFF account is never reclassified")
        void writtenOff_neverReclassified() {
            LoanAccount account = createAccount(0, LoanStatus.WRITTEN_OFF);
            assertEquals(LoanStatus.WRITTEN_OFF, rule.classify(account));
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private LoanAccount createAccount(int dpd, LoanStatus status) {
        LoanAccount account = new LoanAccount();
        account.setDaysPastDue(dpd);
        account.setStatus(status);
        return account;
    }
}

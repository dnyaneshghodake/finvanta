package com.finvanta.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.finvanta.domain.entity.FixedDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the 11-arg {@code bookFd} default method on
 * {@link FixedDepositService} forwards every argument verbatim to the
 * 10-arg overload, regardless of whether the {@code idempotencyKey} is
 * a real value, {@code null}, or blank.
 *
 * <p>Pins down the documented contract at
 * {@code src/main/java/com/finvanta/service/FixedDepositService.java:65-83}:
 * "callers may pass a null/blank key to fall back to the non-idempotent path".
 */
class FixedDepositServiceDefaultBookFdTest {

    /**
     * Minimal recording stub: every call to the 10-arg bookFd captures its
     * arguments; all other interface methods throw to surface accidental use.
     */
    private static final class Recorder implements FixedDepositService {
        final AtomicInteger bookFdCalls = new AtomicInteger();
        final AtomicReference<Object[]> lastArgs = new AtomicReference<>();
        final FixedDeposit stubReturn = new FixedDeposit();

        @Override
        public FixedDeposit bookFd(Long customerId, Long branchId,
                                   String linkedAccountNumber,
                                   BigDecimal principalAmount,
                                   BigDecimal interestRate,
                                   int tenureDays,
                                   String interestPayoutMode,
                                   String autoRenewalMode,
                                   String nomineeName,
                                   String nomineeRelationship) {
            bookFdCalls.incrementAndGet();
            lastArgs.set(new Object[] {customerId, branchId, linkedAccountNumber,
                    principalAmount, interestRate, tenureDays,
                    interestPayoutMode, autoRenewalMode,
                    nomineeName, nomineeRelationship});
            return stubReturn;
        }

        @Override public FixedDeposit prematureClose(String f, String r) { return fail("unexpected"); }
        @Override public FixedDeposit maturityClose(String f) { return fail("unexpected"); }
        @Override public void accrueInterest(String f, LocalDate d) { fail("unexpected"); }
        @Override public int processMaturityBatch(LocalDate d) { return fail("unexpected"); }
        @Override public FixedDeposit markLien(String f, BigDecimal a, String l) { return fail("unexpected"); }
        @Override public FixedDeposit releaseLien(String f) { return fail("unexpected"); }
        @Override public void payoutInterest(String f, LocalDate d) { fail("unexpected"); }
        @Override public FixedDeposit getFd(String f) { return fail("unexpected"); }
    }

    @Test
    @DisplayName("11-arg bookFd default forwards all args to 10-arg overload")
    void defaultBookFd_delegates_withRealIdempotencyKey() {
        Recorder svc = new Recorder();

        FixedDeposit result = svc.bookFd(
                101L, 7L, "SB0001",
                new BigDecimal("100000.00"),
                new BigDecimal("6.75"),
                365,
                "ON_MATURITY", "NO_RENEWAL",
                "Jane Doe", "SPOUSE",
                "idem-key-abc-123");

        assertThat(result).isSameAs(svc.stubReturn);
        assertThat(svc.bookFdCalls).hasValue(1);
        assertThat(svc.lastArgs.get()).containsExactly(
                101L, 7L, "SB0001",
                new BigDecimal("100000.00"),
                new BigDecimal("6.75"),
                365,
                "ON_MATURITY", "NO_RENEWAL",
                "Jane Doe", "SPOUSE");
    }

    @Test
    @DisplayName("null idempotencyKey delegates to 10-arg overload (no blank check)")
    void defaultBookFd_delegates_withNullIdempotencyKey() {
        Recorder svc = new Recorder();

        svc.bookFd(1L, 1L, "SB1", BigDecimal.ONE, BigDecimal.ONE, 30,
                "ON_MATURITY", "NO_RENEWAL", "N", "SELF", null);

        assertThat(svc.bookFdCalls).hasValue(1);
    }

    @Test
    @DisplayName("blank idempotencyKey delegates to 10-arg overload")
    void defaultBookFd_delegates_withBlankIdempotencyKey() {
        Recorder svc = new Recorder();

        svc.bookFd(1L, 1L, "SB1", BigDecimal.ONE, BigDecimal.ONE, 30,
                "ON_MATURITY", "NO_RENEWAL", "N", "SELF", "   ");

        assertThat(svc.bookFdCalls).hasValue(1);
    }
}

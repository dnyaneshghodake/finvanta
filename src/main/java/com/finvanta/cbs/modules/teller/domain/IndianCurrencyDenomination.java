package com.finvanta.cbs.modules.teller.domain;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * CBS Indian Currency Denomination enum per RBI Currency Management Department.
 *
 * <p>Enumerates the legal-tender denominations recognized by RBI for over-the-counter
 * teller transactions. Values align with currently-circulating Indian Rupee notes and
 * coins. The {@code value} is the rupee face value of one unit of this denomination
 * and is used to compute {@code denomination.value() * count} for cash totalization.
 *
 * <p>Per RBI Master Direction on Counterfeit Notes (FICN): the ₹2000 note has been
 * withdrawn from active circulation but remains legal tender and is accepted at
 * branches. Cooperative and scheduled banks must continue to accept ₹2000 notes for
 * deposit into customer accounts and route them to the nearest currency chest.
 *
 * <p>Coins are aggregated into a single bucket per CBS coin-handling pragmatics --
 * teller workflows track coin VALUE (e.g., INR 500 worth of coins), not coin count by
 * denomination, because per-denomination coin sorting at branch level is impractical.
 *
 * <p>This enum is the single source of truth for valid denominations across the
 * teller module. Hardcoded literals (e.g., {@code 2000}, {@code "FIVE_HUNDRED"})
 * outside this enum are an architecture violation and should be enforced by ArchUnit.
 */
public enum IndianCurrencyDenomination {

    /** INR 2000 note -- withdrawn from active circulation but still legal tender. */
    NOTE_2000(new BigDecimal("2000"), DenominationKind.NOTE, true),

    /** INR 500 note -- highest active-circulation note post-2023 withdrawal. */
    NOTE_500(new BigDecimal("500"), DenominationKind.NOTE, true),

    /** INR 200 note. */
    NOTE_200(new BigDecimal("200"), DenominationKind.NOTE, true),

    /** INR 100 note. */
    NOTE_100(new BigDecimal("100"), DenominationKind.NOTE, true),

    /** INR 50 note. */
    NOTE_50(new BigDecimal("50"), DenominationKind.NOTE, true),

    /** INR 20 note. */
    NOTE_20(new BigDecimal("20"), DenominationKind.NOTE, true),

    /** INR 10 note. */
    NOTE_10(new BigDecimal("10"), DenominationKind.NOTE, true),

    /** INR 5 note (rare in circulation; primarily coins now). */
    NOTE_5(new BigDecimal("5"), DenominationKind.NOTE, false),

    /**
     * Aggregated coin bucket. The teller captures the total VALUE of coins (in rupees)
     * rather than per-denomination counts. The {@code count} field on a coin
     * {@link CashDenomination} therefore represents the count of one-rupee-equivalent
     * units, i.e. the total coin value.
     */
    COIN_BUCKET(BigDecimal.ONE, DenominationKind.COIN, true);

    private final BigDecimal value;
    private final DenominationKind kind;
    private final boolean primaryCirculation;

    IndianCurrencyDenomination(BigDecimal value, DenominationKind kind, boolean primaryCirculation) {
        this.value = value;
        this.kind = kind;
        this.primaryCirculation = primaryCirculation;
    }

    /** Face value of one unit of this denomination, in INR. */
    public BigDecimal value() {
        return value;
    }

    public DenominationKind kind() {
        return kind;
    }

    /**
     * True if this denomination is part of RBI's primary circulation set.
     * Withdrawn-but-legal-tender denominations (e.g. NOTE_2000) return true
     * because they remain acceptable at the counter; only fully-demonetized
     * denominations would return false.
     */
    public boolean isPrimaryCirculation() {
        return primaryCirculation;
    }

    /**
     * Computes the total INR value contributed by {@code count} units of this
     * denomination. Coins use {@code count} as the rupee value directly because
     * COIN_BUCKET.value() == 1.
     */
    public BigDecimal totalFor(long count) {
        if (count < 0) {
            throw new IllegalArgumentException(
                    "Denomination count cannot be negative: " + count + " for " + this);
        }
        return value.multiply(BigDecimal.valueOf(count));
    }

    /**
     * Returns the denomination matching the given rupee face value. Throws
     * {@link IllegalArgumentException} for unrecognized values so the validator
     * can fail fast on bad operator input.
     */
    public static IndianCurrencyDenomination fromValue(BigDecimal rupeeValue) {
        if (rupeeValue == null) {
            throw new IllegalArgumentException("Denomination value cannot be null");
        }
        return Arrays.stream(values())
                .filter(d -> d.value.compareTo(rupeeValue) == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unrecognized denomination value: INR " + rupeeValue
                                + ". Allowed: " + Arrays.toString(values())));
    }

    /** Whether this denomination is a paper note (vs a coin bucket). */
    public enum DenominationKind {
        NOTE,
        COIN
    }
}

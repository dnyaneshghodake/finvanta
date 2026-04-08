package com.finvanta.domain.enums;

/**
 * Standing Instruction execution frequency per Finacle SI_MASTER / Temenos STANDING.ORDER.
 *
 * Per RBI Payment Systems: SI frequency must match the underlying obligation.
 * For loan EMI: MONTHLY (aligned with repayment_frequency on LoanAccount).
 * For SIP/RD: MONTHLY or QUARTERLY.
 * For utility: MONTHLY, QUARTERLY, or ANNUAL.
 */
public enum SIFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    HALF_YEARLY,
    ANNUAL;

    /**
     * Returns the number of months to add for next execution date calculation.
     * For DAILY and WEEKLY, returns 0 (handled separately with day arithmetic).
     */
    public int getMonthsIncrement() {
        return switch (this) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case HALF_YEARLY -> 6;
            case ANNUAL -> 12;
            default -> 0;
        };
    }

    /**
     * Returns the number of days to add for DAILY/WEEKLY frequencies.
     * For month-based frequencies, returns 0 (handled via plusMonths).
     */
    public int getDaysIncrement() {
        return switch (this) {
            case DAILY -> 1;
            case WEEKLY -> 7;
            default -> 0;
        };
    }
}

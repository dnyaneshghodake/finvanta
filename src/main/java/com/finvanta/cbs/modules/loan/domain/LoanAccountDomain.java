package com.finvanta.cbs.modules.loan.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * CBS Loan Account Domain Logic per Finacle LOANLIMIT / Temenos ARRANGEMENT.
 *
 * <p>This is a DOMAIN class (not a JPA entity). Per DDD standards, domain
 * classes contain business logic and invariant enforcement, while entities
 * are persistence-layer objects. In the refactored architecture, the entity
 * {@code LoanAccount} handles JPA mapping, and this domain class handles
 * business calculations.
 *
 * <p>Business rules implemented:
 * <ul>
 *   <li>EMI calculation (reducing balance / flat rate)</li>
 *   <li>Prepayment penalty calculation per RBI Fair Practices Code 2024</li>
 *   <li>LTV (Loan-to-Value) ratio calculation per RBI collateral norms</li>
 *   <li>DPD (Days Past Due) classification per RBI IRAC norms</li>
 *   <li>Interest accrual on NPA accounts (income recognition stop)</li>
 * </ul>
 *
 * <p>Per RBI Master Circular on Income Recognition, Asset Classification,
 * and Provisioning (IRAC) 2024:
 * <ul>
 *   <li>91+ DPD = NPA (Sub-Standard)</li>
 *   <li>366+ DPD = NPA (Doubtful)</li>
 *   <li>1096+ DPD = NPA (Loss)</li>
 *   <li>Income recognition stops when account is classified NPA</li>
 * </ul>
 */
public class LoanAccountDomain {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** RBI Fair Practices Code 2024: max prepayment penalty for floating rate = 0% */
    private static final BigDecimal MAX_FLOATING_PREPAYMENT_PENALTY_RATE = BigDecimal.ZERO;
    /** RBI Fair Practices Code 2024: max prepayment penalty for fixed rate = 2% */
    private static final BigDecimal MAX_FIXED_PREPAYMENT_PENALTY_RATE = new BigDecimal("0.02");

    private LoanAccountDomain() {
        // Domain utility class
    }

    /**
     * Calculates EMI using the reducing balance method.
     *
     * <p>Formula: EMI = P * r * (1+r)^n / ((1+r)^n - 1)
     * where P = principal, r = monthly rate, n = tenure in months.
     *
     * <p>Per Finacle LNEMI: this is the standard CBS EMI calculation used
     * across all loan products (Home, Vehicle, Personal, Gold, LAP).
     *
     * @param principal    sanctioned/outstanding principal amount
     * @param annualRate   annual interest rate as percentage (e.g., 12.50 for 12.5%)
     * @param tenureMonths loan tenure in months
     * @return monthly EMI amount rounded to 2 decimal places
     */
    public static BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRate,
            int tenureMonths) {
        if (principal == null || principal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (annualRate == null || annualRate.compareTo(BigDecimal.ZERO) <= 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), SCALE, ROUNDING);
        }
        if (tenureMonths <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal monthlyRate = annualRate.divide(
                BigDecimal.valueOf(1200), 10, ROUNDING);

        // (1 + r)^n
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal power = onePlusR.pow(tenureMonths);

        // EMI = P * r * (1+r)^n / ((1+r)^n - 1)
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(power);
        BigDecimal denominator = power.subtract(BigDecimal.ONE);

        return numerator.divide(denominator, SCALE, ROUNDING);
    }

    /**
     * Calculates daily interest accrual on outstanding principal.
     *
     * <p>Formula: Daily Interest = (Outstanding Principal * Annual Rate) / (Days in Year)
     *
     * <p>Per Finacle LNINTCALC: uses actual/365 day count convention for INR loans
     * (as mandated by RBI for INR-denominated products).
     *
     * @param outstandingPrincipal current outstanding principal
     * @param annualRate           annual interest rate as percentage
     * @param daysInYear           365 or 366 (leap year)
     * @return daily interest amount
     */
    public static BigDecimal calculateDailyInterest(BigDecimal outstandingPrincipal,
            BigDecimal annualRate, int daysInYear) {
        if (outstandingPrincipal == null || outstandingPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = annualRate.divide(BigDecimal.valueOf(100), 10, ROUNDING);
        return outstandingPrincipal.multiply(rate)
                .divide(BigDecimal.valueOf(daysInYear), SCALE, ROUNDING);
    }

    /**
     * Calculates prepayment penalty per RBI Fair Practices Code 2024.
     *
     * <p>RBI Mandate: No prepayment penalty on floating-rate loans disbursed
     * to individual borrowers. Fixed-rate loans may carry max 2% penalty.
     *
     * @param prepaymentAmount amount being prepaid
     * @param isFloatingRate   true if the loan has floating interest rate
     * @param penaltyRate      contractual penalty rate (capped per RBI norms)
     * @return penalty amount (may be ZERO per RBI mandate)
     */
    public static BigDecimal calculatePrepaymentPenalty(BigDecimal prepaymentAmount,
            boolean isFloatingRate, BigDecimal penaltyRate) {
        if (isFloatingRate) {
            return BigDecimal.ZERO;
        }
        BigDecimal effectiveRate = penaltyRate.min(MAX_FIXED_PREPAYMENT_PENALTY_RATE);
        return prepaymentAmount.multiply(effectiveRate).setScale(SCALE, ROUNDING);
    }

    /**
     * Calculates Loan-to-Value ratio for collateral assessment.
     *
     * <p>Per RBI Collateral Norms:
     * <ul>
     *   <li>Home Loan: max LTV 90% (up to INR 30L), 80% (30L-75L), 75% (above 75L)</li>
     *   <li>Gold Loan: max LTV 75%</li>
     *   <li>LAP: max LTV 65%</li>
     * </ul>
     *
     * @param loanAmount      sanctioned loan amount
     * @param collateralValue market value of collateral
     * @return LTV ratio as percentage (e.g., 75.00 for 75%)
     */
    public static BigDecimal calculateLtv(BigDecimal loanAmount, BigDecimal collateralValue) {
        if (collateralValue == null || collateralValue.compareTo(BigDecimal.ZERO) <= 0) {
            return new BigDecimal("100.00");
        }
        return loanAmount.multiply(BigDecimal.valueOf(100))
                .divide(collateralValue, SCALE, ROUNDING);
    }

    /**
     * Calculates Days Past Due from the last due date.
     *
     * @param lastDueDate the date of the oldest unpaid installment
     * @param currentDate current business date
     * @return DPD count (0 if no overdue)
     */
    public static int calculateDpd(LocalDate lastDueDate, LocalDate currentDate) {
        if (lastDueDate == null || !currentDate.isAfter(lastDueDate)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(lastDueDate, currentDate);
    }

    /**
     * Determines if interest accrual should be suspended per RBI IRAC norms.
     *
     * <p>Per RBI Master Circular: income recognition (interest accrual to P&L)
     * must stop when a loan is classified as NPA. Interest continues to be
     * tracked in a memorandum account but is NOT recognized as income until
     * the account is upgraded back to Standard.
     *
     * @param daysPastDue current DPD of the loan
     * @return true if interest accrual to P&L should be suspended
     */
    public static boolean shouldSuspendIncomeRecognition(int daysPastDue) {
        return daysPastDue >= 91; // NPA threshold per RBI IRAC
    }
}

package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

/**
 * CBS Interest Calculation Engine per Finacle INTDEF / Temenos AA.INTEREST.
 *
 * Supports multiple day count conventions per product configuration:
 *   ACTUAL_365  — Actual days / 365 (RBI default for INR loans, per 2009 circular)
 *   ACTUAL_360  — Actual days / 360 (common for USD/EUR money market)
 *   ACTUAL_ACTUAL — Actual days / actual days in year (365 or 366 for leap year)
 *   THIRTY_360  — 30 days per month / 360 (US corporate bonds, some NBFC products)
 *
 * Per RBI circular on interest rate computation (2009):
 *   "For the purpose of equated installments, interest shall be calculated on
 *    daily reducing balance on actual number of days in the year (365/366 days)."
 *
 * EMI computation uses standard reducing balance formula:
 *   EMI = P × r × (1+r)^n / ((1+r)^n - 1)
 *   where r = annual rate / 12 (monthly rate)
 *
 * Penal interest per RBI Fair Lending Code 2023:
 *   Penal charges on overdue principal only (not on interest/EMI amount).
 */
@Component
public class InterestCalculationRule {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final MathContext MC = new MathContext(18, ROUNDING);

    /**
     * Calculates daily interest accrual using the specified day count convention.
     *
     * @param account       Loan account with outstanding principal and rate
     * @param fromDate      Accrual start date (exclusive — last accrual date)
     * @param toDate        Accrual end date (inclusive — current business date)
     * @param interestMethod Day count convention: ACTUAL_365, ACTUAL_360, ACTUAL_ACTUAL, THIRTY_360
     * @return Accrued interest for the period
     */
    public BigDecimal calculateDailyAccrual(
            LoanAccount account, LocalDate fromDate, LocalDate toDate, String interestMethod) {
        if (account.getOutstandingPrincipal().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }

        int daysInYear = getDaysInYear(interestMethod, fromDate, toDate);

        BigDecimal ratePerDay = account.getInterestRate()
                .divide(BigDecimal.valueOf(100), MC)
                .divide(BigDecimal.valueOf(daysInYear), MC);

        return account.getOutstandingPrincipal()
                .multiply(ratePerDay, MC)
                .multiply(BigDecimal.valueOf(days), MC)
                .setScale(SCALE, ROUNDING);
    }

    /**
     * Backward-compatible overload — defaults to ACTUAL_365 (RBI standard for INR).
     */
    public BigDecimal calculateDailyAccrual(LoanAccount account, LocalDate fromDate, LocalDate toDate) {
        return calculateDailyAccrual(account, fromDate, toDate, "ACTUAL_365");
    }

    /**
     * Returns the denominator (days in year) for the given day count convention.
     *
     * @param interestMethod Day count convention from ProductMaster.interestMethod
     * @param fromDate       Period start date (used for ACTUAL_ACTUAL leap year check)
     * @param toDate         Period end date
     * @return Days in year for the convention
     */
    private int getDaysInYear(String interestMethod, LocalDate fromDate, LocalDate toDate) {
        if (interestMethod == null) return 365; // Default
        return switch (interestMethod) {
            case "ACTUAL_365" -> 365;
            case "ACTUAL_360", "THIRTY_360" -> 360;
            case "ACTUAL_ACTUAL" -> toDate.isLeapYear() ? 366 : 365;
            default -> 365;
        };
    }

    public BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRate, int tenureMonths) {
        if (principal.compareTo(BigDecimal.ZERO) <= 0 || tenureMonths <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(100), MC).divide(BigDecimal.valueOf(12), MC);

        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), SCALE, ROUNDING);
        }

        // EMI = P * r * (1+r)^n / ((1+r)^n - 1)
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate, MC);
        BigDecimal onePlusRPowN = onePlusR.pow(tenureMonths, MC);

        BigDecimal numerator = principal.multiply(monthlyRate, MC).multiply(onePlusRPowN, MC);
        BigDecimal denominator = onePlusRPowN.subtract(BigDecimal.ONE, MC);

        return numerator.divide(denominator, SCALE, ROUNDING);
    }

    /**
     * RBI Fair Lending Code 2023: Penal interest calculation on overdue EMIs.
     * Penal interest is charged on the overdue principal component only (not on interest).
     * Per RBI circular dated 18 Aug 2023: "Penal charges shall be levied on the
     * outstanding loan amount and not on the EMI/installment amount."
     *
     * Daily penal = (Overdue Principal × Penal Rate) / 365
     *
     * @param account  The loan account with overdue EMIs
     * @param fromDate Start date for penal calculation
     * @param toDate   End date (business date)
     * @return Penal interest amount for the period
     */
    public BigDecimal calculatePenalInterest(LoanAccount account, LocalDate fromDate, LocalDate toDate) {
        BigDecimal overduePrincipal = account.getOverduePrincipal();
        BigDecimal penalRate = account.getPenalRate();

        if (overduePrincipal.compareTo(BigDecimal.ZERO) <= 0 || penalRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }

        // Penal interest always uses Actual/365 per RBI Fair Lending Code 2023
        BigDecimal ratePerDay = penalRate.divide(BigDecimal.valueOf(100), MC).divide(BigDecimal.valueOf(365), MC);

        return overduePrincipal
                .multiply(ratePerDay, MC)
                .multiply(BigDecimal.valueOf(days), MC)
                .setScale(SCALE, ROUNDING);
    }

    public BigDecimal[] splitEmiComponents(
            BigDecimal emiAmount, BigDecimal outstandingPrincipal, BigDecimal annualRate) {
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(100), MC).divide(BigDecimal.valueOf(12), MC);

        BigDecimal interestComponent =
                outstandingPrincipal.multiply(monthlyRate, MC).setScale(SCALE, ROUNDING);

        BigDecimal principalComponent = emiAmount.subtract(interestComponent).setScale(SCALE, ROUNDING);

        if (principalComponent.compareTo(BigDecimal.ZERO) < 0) {
            // Payment is less than interest due — all goes to interest
            interestComponent = emiAmount;
            principalComponent = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        } else if (principalComponent.compareTo(outstandingPrincipal) > 0) {
            principalComponent = outstandingPrincipal;
            interestComponent = emiAmount.subtract(principalComponent).setScale(SCALE, ROUNDING);
        }

        return new BigDecimal[] {principalComponent, interestComponent};
    }
}

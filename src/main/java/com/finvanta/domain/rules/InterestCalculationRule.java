package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Interest calculation using 30/360 day-count convention (Indian banking standard).
 * Supports daily accrual and EMI computation using reducing balance method.
 */
@Component
public class InterestCalculationRule {

    private static final int DAYS_IN_YEAR = 360;
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final MathContext MC = new MathContext(18, ROUNDING);

    public BigDecimal calculateDailyAccrual(LoanAccount account, LocalDate fromDate, LocalDate toDate) {
        if (account.getOutstandingPrincipal().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long days = ChronoUnit.DAYS.between(fromDate, toDate);
        if (days <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal ratePerDay = account.getInterestRate()
            .divide(BigDecimal.valueOf(100), MC)
            .divide(BigDecimal.valueOf(DAYS_IN_YEAR), MC);

        return account.getOutstandingPrincipal()
            .multiply(ratePerDay, MC)
            .multiply(BigDecimal.valueOf(days), MC)
            .setScale(SCALE, ROUNDING);
    }

    public BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRate, int tenureMonths) {
        if (principal.compareTo(BigDecimal.ZERO) <= 0 || tenureMonths <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal monthlyRate = annualRate
            .divide(BigDecimal.valueOf(100), MC)
            .divide(BigDecimal.valueOf(12), MC);

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

    public BigDecimal[] splitEmiComponents(BigDecimal emiAmount, BigDecimal outstandingPrincipal, BigDecimal annualRate) {
        BigDecimal monthlyRate = annualRate
            .divide(BigDecimal.valueOf(100), MC)
            .divide(BigDecimal.valueOf(12), MC);

        BigDecimal interestComponent = outstandingPrincipal
            .multiply(monthlyRate, MC)
            .setScale(SCALE, ROUNDING);

        BigDecimal principalComponent = emiAmount.subtract(interestComponent).setScale(SCALE, ROUNDING);

        if (principalComponent.compareTo(BigDecimal.ZERO) < 0) {
            // Payment is less than interest due — all goes to interest
            interestComponent = emiAmount;
            principalComponent = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        } else if (principalComponent.compareTo(outstandingPrincipal) > 0) {
            principalComponent = outstandingPrincipal;
            interestComponent = emiAmount.subtract(principalComponent).setScale(SCALE, ROUNDING);
        }

        return new BigDecimal[]{principalComponent, interestComponent};
    }
}

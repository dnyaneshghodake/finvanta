package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * RBI IRAC Provisioning Norms (Master Circular on Prudential Norms):
 *
 * Standard Assets:     0.40% of outstanding
 * Sub-Standard:       10.00% of outstanding (unsecured: 10%, secured: 10%)
 * Doubtful (1yr):     20.00% of outstanding
 * Doubtful (2yr):     30.00% of outstanding
 * Doubtful (3yr+):    50.00% of outstanding (100% of unsecured portion)
 * Loss:              100.00% of outstanding
 *
 * For simplicity, this implementation uses:
 * Sub-Standard: 10%, Doubtful: 40% (average), Loss: 100%
 */
@Component
public class ProvisioningRule {

    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.0040");
    private static final BigDecimal SUBSTANDARD_RATE = new BigDecimal("0.10");
    private static final BigDecimal DOUBTFUL_RATE = new BigDecimal("0.40");
    private static final BigDecimal LOSS_RATE = BigDecimal.ONE;

    /**
     * Calculates provisioning amount based on NPA classification.
     * Per RBI IRAC norms, provisioning is computed on outstanding principal.
     */
    public BigDecimal calculateProvisioning(LoanAccount account) {
        BigDecimal outstanding = account.getOutstandingPrincipal();
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal rate = getProvisioningRate(account.getStatus());
        return outstanding.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getProvisioningRate(LoanStatus status) {
        return switch (status) {
            case ACTIVE, SMA_0, OVERDUE, RESTRUCTURED -> STANDARD_RATE;
            case SMA_1, SMA_2 -> STANDARD_RATE; // SMA still Standard asset provisioning
            case NPA_SUBSTANDARD -> SUBSTANDARD_RATE;
            case NPA_DOUBTFUL -> DOUBTFUL_RATE;
            case NPA_LOSS -> LOSS_RATE;
            case CLOSED, WRITTEN_OFF -> BigDecimal.ZERO;
        };
    }

    /**
     * Returns the provisioning percentage for display purposes.
     */
    public String getProvisioningPercentage(LoanStatus status) {
        return switch (status) {
            case ACTIVE, SMA_0, SMA_1, SMA_2, OVERDUE, RESTRUCTURED -> "0.40%";
            case NPA_SUBSTANDARD -> "10%";
            case NPA_DOUBTFUL -> "40%";
            case NPA_LOSS -> "100%";
            case CLOSED, WRITTEN_OFF -> "0%";
        };
    }
}

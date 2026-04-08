package com.finvanta.domain.rules;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * RBI IRAC Provisioning Norms (Master Circular on Prudential Norms).
 *
 * Standard provisioning rates:
 *   Standard Assets (ACTIVE, SMA-0/1/2): 0.40% of outstanding
 *   Restructured (first 2 years):        5.00% of outstanding (RBI CDR norms)
 *   Sub-Standard:                        10.00% of outstanding
 *   Doubtful (average):                  40.00% of outstanding
 *   Loss:                               100.00% of outstanding
 *
 * Detailed RBI Doubtful provisioning (not yet implemented):
 *   Doubtful (1yr):  20% secured / 100% unsecured portion
 *   Doubtful (2yr):  30% secured / 100% unsecured portion
 *   Doubtful (3yr+): 50% secured / 100% unsecured portion
 *
 * This implementation uses simplified rates. For production, doubtful
 * provisioning should factor in collateral coverage (secured vs unsecured).
 */
@Component
public class ProvisioningRule {

    private static final BigDecimal STANDARD_RATE = new BigDecimal("0.0040");
    /** RBI CDR: Restructured accounts carry 5% provisioning for first 2 years */
    private static final BigDecimal RESTRUCTURED_RATE = new BigDecimal("0.05");

    private static final BigDecimal SUBSTANDARD_RATE = new BigDecimal("0.10");
    private static final BigDecimal DOUBTFUL_RATE = new BigDecimal("0.40");
    private static final BigDecimal LOSS_RATE = BigDecimal.ONE;

    /**
     * Calculates provisioning amount based on asset classification.
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

    /**
     * Returns the provisioning rate for a given loan status.
     * Per RBI IRAC Master Circular:
     * - Standard assets (ACTIVE, SMA): 0.40%
     * - Restructured: 5.00% (higher than standard per CDR norms)
     * - Sub-Standard: 10%
     * - Doubtful: 40% (simplified average)
     * - Loss: 100%
     */
    public BigDecimal getProvisioningRate(LoanStatus status) {
        return switch (status) {
            case ACTIVE, SMA_0, SMA_1, SMA_2 -> STANDARD_RATE;
            case RESTRUCTURED -> RESTRUCTURED_RATE;
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
            case ACTIVE, SMA_0, SMA_1, SMA_2 -> "0.40%";
            case RESTRUCTURED -> "5%";
            case NPA_SUBSTANDARD -> "10%";
            case NPA_DOUBTFUL -> "40%";
            case NPA_LOSS -> "100%";
            case CLOSED, WRITTEN_OFF -> "0%";
        };
    }
}

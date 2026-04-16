package com.finvanta.charge;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * CBS GST Split Resolver per GST Act 2017 §5, §8, §12.
 *
 * <p>Determines whether a service charge is an <b>intra-state</b> (CGST + SGST split 9%/9%)
 * or <b>inter-state</b> (IGST 18%) supply, per Finacle CHG_MASTER tax configuration
 * and Temenos FT.COMMISSION tax resolution. The place of supply for banking services
 * is the recipient's billing address (customer's registered state).
 *
 * <p>Per RBI Fair Practices Code 2023 / RBI Master Direction on Service Charges
 * 2020: GST split MUST reflect the true place of supply -- mis-classification is a
 * regulatory violation and causes ITC (input tax credit) mismatches at the customer's
 * end during GST reconciliation.
 *
 * <p><b>Rounding:</b> each leg is rounded HALF_UP to 2 decimals. For intra-state
 * supplies where the raw split would be {@code baseFee * 18% / 2}, we compute CGST
 * first (HALF_UP) and then derive SGST as {@code totalGst - CGST} so the sum always
 * equals the canonical 18% total and auditors can tie CGST + SGST = 18% of base fee
 * to the last paisa.
 */
@Component
public class GstTaxResolver {

    /** Total GST rate per Finance Act 2018 for banking services (18%). */
    public static final BigDecimal TOTAL_GST_RATE = new BigDecimal("18");

    /** CGST rate for intra-state supplies (9%). */
    public static final BigDecimal CGST_RATE = new BigDecimal("9");

    /** SGST rate for intra-state supplies (9%). */
    public static final BigDecimal SGST_RATE = new BigDecimal("9");

    /** IGST rate for inter-state supplies (18%). */
    public static final BigDecimal IGST_RATE = new BigDecimal("18");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Resolve the GST split for a given base fee, applying IGST for inter-state
     * supplies and CGST + SGST for intra-state supplies.
     *
     * @param baseFee           Base fee amount (before GST). Must be {@code >= 0}.
     * @param gstApplicable     {@code true} if this charge is GST-applicable per the
     *                          charge definition. If {@code false}, all three components
     *                          are zero.
     * @param branchStateCode   State code of the bank branch (e.g., {@code "MH"}, {@code "KA"}).
     *                          Treated as supplier state per GST Act §12.
     * @param customerStateCode State code of the customer's registered billing address.
     *                          If {@code null} or blank, falls back to intra-state split
     *                          (CGST + SGST) -- the conservative default when we cannot
     *                          establish place of supply, chosen because an incorrect
     *                          IGST classification would block customer ITC claims.
     * @return Immutable {@link GstSplit} result with (cgst, sgst, igst) components.
     */
    public GstSplit resolve(
            BigDecimal baseFee,
            boolean gstApplicable,
            String branchStateCode,
            String customerStateCode) {
        if (!gstApplicable || baseFee == null || baseFee.signum() <= 0) {
            return GstSplit.ZERO;
        }

        boolean interState = branchStateCode != null
                && !branchStateCode.isBlank()
                && customerStateCode != null
                && !customerStateCode.isBlank()
                && !branchStateCode.equalsIgnoreCase(customerStateCode);

        if (interState) {
            BigDecimal igst = baseFee
                    .multiply(IGST_RATE)
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            return new GstSplit(BigDecimal.ZERO, BigDecimal.ZERO, igst);
        }

        // Intra-state: CGST 9% + SGST 9%. Compute total GST first, then derive each
        // leg so CGST + SGST == total GST (prevents 1-paisa rounding drift).
        BigDecimal totalGst = baseFee
                .multiply(TOTAL_GST_RATE)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal cgst = baseFee
                .multiply(CGST_RATE)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal sgst = totalGst.subtract(cgst);
        return new GstSplit(cgst, sgst, BigDecimal.ZERO);
    }

    /**
     * Immutable GST component result. Exactly one of {@code (cgst + sgst)} or {@code igst}
     * is non-zero per GST Act 2017; both are zero when the charge is not GST-applicable.
     */
    public record GstSplit(BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {

        public static final GstSplit ZERO =
                new GstSplit(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        /** Total GST across all components. */
        public BigDecimal totalGst() {
            return cgst.add(sgst).add(igst);
        }

        /** {@code true} if this supply is classified as inter-state (IGST only). */
        public boolean isInterState() {
            return igst.signum() > 0;
        }
    }
}

package com.finvanta.accounting;

import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.PaymentRail;

/**
 * CBS Clearing GL Resolver per Finacle CLG_MASTER / Temenos CLEARING.GL.
 *
 * Resolves the correct suspense GL code based on payment rail and direction.
 * Per RBI Payment Systems: each rail MUST have independent suspense GLs
 * for separate reconciliation of NEFT, RTGS, IMPS, and UPI.
 *
 * GL Mapping:
 *   NEFT OUTWARD → 2600    NEFT INWARD → 2601
 *   RTGS OUTWARD → 2610    RTGS INWARD → 2611
 *   IMPS OUTWARD → 2620    IMPS INWARD → 2621
 *   UPI  OUTWARD → 2630    UPI  INWARD → 2631
 *   RBI Settlement (Nostro) → 1400
 *
 * In production, this would be loaded from a clearing_gl_config table.
 * For this implementation, it uses centralized constants from GLConstants.
 */
public final class ClearingGLResolver {

    private ClearingGLResolver() {}

    /**
     * Resolves the suspense GL code for a payment rail and direction.
     *
     * @param rail      Payment rail (NEFT/RTGS/IMPS/UPI)
     * @param direction Clearing direction (INWARD/OUTWARD)
     * @return GL code for the suspense account
     */
    public static String getSuspenseGL(PaymentRail rail, ClearingDirection direction) {
        return switch (rail) {
            case NEFT -> direction == ClearingDirection.OUTWARD
                    ? GLConstants.NEFT_OUTWARD_SUSPENSE : GLConstants.NEFT_INWARD_SUSPENSE;
            case RTGS -> direction == ClearingDirection.OUTWARD
                    ? GLConstants.RTGS_OUTWARD_SUSPENSE : GLConstants.RTGS_INWARD_SUSPENSE;
            case IMPS -> direction == ClearingDirection.OUTWARD
                    ? GLConstants.IMPS_OUTWARD_SUSPENSE : GLConstants.IMPS_INWARD_SUSPENSE;
            case UPI -> direction == ClearingDirection.OUTWARD
                    ? GLConstants.UPI_OUTWARD_SUSPENSE : GLConstants.UPI_INWARD_SUSPENSE;
        };
    }

    /** RBI Settlement GL (Nostro) — bank's account with RBI */
    public static String getRbiSettlementGL() {
        return GLConstants.RBI_SETTLEMENT;
    }

    /**
     * Returns all suspense GL codes for a specific rail (both inward + outward).
     * Used by EOD reconciliation to verify per-rail suspense balances.
     */
    public static String[] getSuspenseGLsForRail(PaymentRail rail) {
        return new String[] {
                getSuspenseGL(rail, ClearingDirection.OUTWARD),
                getSuspenseGL(rail, ClearingDirection.INWARD)
        };
    }

    /**
     * Returns ALL clearing suspense GL codes across all rails.
     * Used by EOD to verify total clearing suspense = 0.
     */
    public static String[] getAllSuspenseGLs() {
        return new String[] {
                GLConstants.NEFT_OUTWARD_SUSPENSE, GLConstants.NEFT_INWARD_SUSPENSE,
                GLConstants.RTGS_OUTWARD_SUSPENSE, GLConstants.RTGS_INWARD_SUSPENSE,
                GLConstants.IMPS_OUTWARD_SUSPENSE, GLConstants.IMPS_INWARD_SUSPENSE,
                GLConstants.UPI_OUTWARD_SUSPENSE, GLConstants.UPI_INWARD_SUSPENSE
        };
    }
}

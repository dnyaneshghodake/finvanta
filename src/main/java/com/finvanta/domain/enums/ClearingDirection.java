package com.finvanta.domain.enums;

/**
 * CBS Clearing Direction per Finacle CLG_MASTER / Temenos CLEARING.DIRECTION.
 *
 * OUTWARD: Customer at THIS bank sends money to another bank.
 *   GL Flow: DR Customer Account / CR Outward Suspense GL
 *   Then on settlement: DR Outward Suspense GL / CR RBI Settlement GL
 *
 * INWARD: Customer at another bank sends money to THIS bank's customer.
 *   GL Flow: DR RBI Settlement GL / CR Inward Suspense GL
 *   Then credit: DR Inward Suspense GL / CR Customer Account
 *
 * Per RBI: inward and outward clearing MUST use separate suspense GLs
 * for independent reconciliation and regulatory reporting.
 */
public enum ClearingDirection {

    /** Customer sends money OUT to another bank */
    OUTWARD,

    /** Customer receives money IN from another bank */
    INWARD
}

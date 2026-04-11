package com.finvanta.config;

/**
 * @deprecated Replaced by {@link CbsLayoutAdvice} which provides:
 * - ADMIN branch switch support (allBranches model attribute)
 * - Correct AUDITOR role display via hasRole() instead of stream().findFirst()
 * - Consistent pre-auth fallback behavior ("--" for all attributes)
 * - Branch-switch-aware branch code (switched branch takes priority over home branch)
 *
 * Per Finacle/Temenos Tier-1 CBS standards: there must be exactly ONE authoritative
 * source for topbar context attributes. Having two @ControllerAdvice beans setting
 * the same model attributes (businessDate, userRole, userBranchCode) without @Order
 * creates nondeterministic behavior — whichever runs last wins.
 *
 * This class is retained as an empty shell to prevent compilation errors in any
 * code that may reference it. It will be removed in the next release cycle.
 */
@Deprecated(forRemoval = true)
public class CommonModelAdvice {
    // All functionality moved to CbsLayoutAdvice.
    // This class is intentionally empty — the @ControllerAdvice annotation
    // and @ModelAttribute methods have been removed to eliminate the duplicate
    // model attribute conflict.
}

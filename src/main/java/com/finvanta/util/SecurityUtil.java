package com.finvanta.util;

import com.finvanta.config.BranchAwareUserDetails;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * CBS Security Utility — extracts current user context from Spring Security.
 *
 * Per Finacle/Temenos standards, every financial operation must be traceable
 * to a specific user, role, and branch. This utility provides:
 * - Username for audit trail (who performed the action)
 * - Role for transaction limit validation (what limits apply)
 * - Branch ID/Code for branch-level data isolation
 *
 * Branch isolation rule per Finacle BRANCH_CONTEXT:
 * - MAKER/CHECKER: restricted to their home branch
 * - ADMIN: exempt from branch filtering (sees all branches)
 * - AUDITOR: read-only, sees all branches
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "SYSTEM";
        }
        return auth.getName();
    }

    /**
     * Returns the current user's CBS role for transaction limit resolution.
     * Extracts from Spring Security GrantedAuthority, stripping the "ROLE_" prefix.
     *
     * Per CBS transactional role hierarchy (most restrictive first):
     *   TELLER < MAKER < CHECKER < ADMIN
     *
     * TELLER is the most restrictive transactional role — it is a specialization
     * of MAKER confined to the over-the-counter cash channel (see the
     * {@code com.finvanta.cbs.modules.teller} package). Per RBI Internal Controls,
     * the teller's per-transaction and daily aggregate limits are deliberately
     * tighter than MAKER because cash tellerage carries higher operational risk
     * (physical cash handling, counterfeit exposure, FICN workflow).
     *
     * AUDITOR is excluded — it is a read-only audit role that should never initiate
     * financial transactions. Including AUDITOR would cause the limit lookup to find
     * no configured limits (AUDITOR has no transaction limits configured), which
     * silently bypasses all limit checks via the "no limit configured" fallback path
     * in TransactionLimitService.
     *
     * RBI Segregation of Duties Compliance:
     * When a user has multiple transactional roles (e.g., ROLE_TELLER + ROLE_MAKER),
     * the LOWEST-privilege transactional role is returned. This ensures transaction
     * limits are checked against the most restrictive role, enforcing the principle
     * of least privilege per RBI Master Direction on IT Governance (2023) §8.3.
     *
     * @return Lowest-privilege transactional role without "ROLE_" prefix, or null if no role found
     */
    public static String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return null;
        }
        // CBS transactional role hierarchy: TELLER is most restrictive, ADMIN is least.
        // AUDITOR is excluded — it is read-only and has no transaction limits configured.
        // Returning AUDITOR would cause limit lookup to find nothing → bypass all limits.
        List<String> leastPrivilegeFirst = List.of("TELLER", "MAKER", "CHECKER", "ADMIN");
        Set<String> userRoles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toSet());

        return leastPrivilegeFirst.stream()
                .filter(userRoles::contains)
                .findFirst()
                .orElse(null);
    }

    // === Branch Context Keys for Session-Based Branch Switch ===
    // Per Finacle SOL_SWITCH / Temenos COMPANY.SWITCH: ADMIN users can switch
    // their working branch context without re-logging in. The override is stored
    // in the HTTP session and takes precedence over the home branch.

    /** Session attribute key for switched branch ID (ADMIN only) */
    public static final String SWITCHED_BRANCH_ID = "CBS_SWITCHED_BRANCH_ID";
    /** Session attribute key for switched branch code (ADMIN only) */
    public static final String SWITCHED_BRANCH_CODE = "CBS_SWITCHED_BRANCH_CODE";

    /**
     * Returns the current effective branch ID — switched branch if active, else home branch.
     *
     * Per Finacle SOL_SWITCH: ADMIN users can switch their working branch context
     * via session override. This method checks the session first, then falls back
     * to the home branch from BranchAwareUserDetails.
     *
     * MAKER/CHECKER always get their home branch (cannot switch).
     *
     * @return Effective branch ID, or null if not available
     */
    public static Long getCurrentUserBranchId() {
        // Check session override first (ADMIN branch switch)
        Long switchedBranchId = getSessionAttribute(SWITCHED_BRANCH_ID, Long.class);
        if (switchedBranchId != null) {
            return switchedBranchId;
        }
        // Fall back to home branch from authentication principal
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BranchAwareUserDetails) {
            return ((BranchAwareUserDetails) auth.getPrincipal()).getBranchId();
        }
        return null;
    }

    /**
     * Returns the current effective branch code — switched branch if active, else home branch.
     */
    public static String getCurrentUserBranchCode() {
        String switchedCode = getSessionAttribute(SWITCHED_BRANCH_CODE, String.class);
        if (switchedCode != null) {
            return switchedCode;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BranchAwareUserDetails) {
            return ((BranchAwareUserDetails) auth.getPrincipal()).getBranchCode();
        }
        return null;
    }

    /**
     * Returns the user's HOME branch ID (ignoring any session switch).
     * Used for audit trail — always records the user's actual home branch.
     */
    public static Long getHomeBranchId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BranchAwareUserDetails) {
            return ((BranchAwareUserDetails) auth.getPrincipal()).getBranchId();
        }
        return null;
    }

    /**
     * Returns true if the user is currently operating in a switched branch context.
     */
    public static boolean isBranchSwitched() {
        return getSessionAttribute(SWITCHED_BRANCH_ID, Long.class) != null;
    }

    /** Helper to read a typed attribute from the current HTTP session. */
    private static <T> T getSessionAttribute(String key, Class<T> type) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                var session = servletAttrs.getRequest().getSession(false);
                if (session != null) {
                    Object val = session.getAttribute(key);
                    if (type.isInstance(val)) {
                        return type.cast(val);
                    }
                }
            }
        } catch (Exception e) {
            // Outside HTTP request context (e.g., async thread) — return null
        }
        return null;
    }

    /**
     * Returns true if the current user has ADMIN role.
     * ADMIN is exempt from branch-level data isolation per Finacle standards.
     */
    public static boolean isAdminRole() {
        return "ADMIN".equals(getCurrentUserRole());
    }

    /**
     * Returns true if the current user has the specified role authority.
     * Checks directly against Spring Security authorities (not via getCurrentUserRole()).
     *
     * This is needed because getCurrentUserRole() intentionally excludes AUDITOR
     * from its return values (to prevent transaction limit bypass). For access control
     * decisions (not transaction limits), we need to check authorities directly.
     *
     * Per RBI IT Governance Direction 2023: AUDITOR has read-only access to all branches
     * for compliance inspection. This method enables BranchAccessValidator to correctly
     * identify AUDITOR users and exempt them from branch isolation.
     *
     * @param role The role to check (without ROLE_ prefix), e.g., "AUDITOR", "ADMIN"
     * @return true if the user has the specified role
     */
    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return false;
        }
        String authority = "ROLE_" + role;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    /**
     * Returns true if the current user has AUDITOR role.
     * AUDITOR has read-only access to all branches per RBI audit requirements.
     * Uses hasRole() instead of getCurrentUserRole() because the latter excludes AUDITOR.
     */
    public static boolean isAuditorRole() {
        return hasRole("AUDITOR");
    }
}

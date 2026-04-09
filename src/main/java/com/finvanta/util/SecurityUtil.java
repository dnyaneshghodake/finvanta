package com.finvanta.util;

import com.finvanta.config.BranchAwareUserDetails;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
     *   MAKER < CHECKER < ADMIN
     *
     * AUDITOR is excluded — it is a read-only audit role that should never initiate
     * financial transactions. Including AUDITOR would cause the limit lookup to find
     * no configured limits (AUDITOR has no transaction limits configured), which
     * silently bypasses all limit checks via the "no limit configured" fallback path
     * in TransactionLimitService.
     *
     * RBI Segregation of Duties Compliance:
     * When a user has multiple transactional roles (e.g., ROLE_MAKER + ROLE_ADMIN),
     * the LOWEST-privilege transactional role is returned. This ensures transaction
     * limits are checked against the most restrictive role, enforcing the principle
     * of least privilege per:
     *   - RBI Master Direction on IT Governance (2023) Section 8.3
     *   - Finacle TRAN_AUTH: "dual-role users are subject to the lower limit"
     *
     * @return Lowest-privilege transactional role without "ROLE_" prefix, or null if no role found
     */
    public static String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return null;
        }
        // CBS transactional role hierarchy: MAKER is most restrictive, ADMIN is least.
        // AUDITOR is excluded — it is read-only and has no transaction limits configured.
        // Returning AUDITOR would cause limit lookup to find nothing → bypass all limits.
        List<String> leastPrivilegeFirst = List.of("MAKER", "CHECKER", "ADMIN");
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

    /**
     * Returns the current user's home branch ID from the security context.
     * Per Finacle BRANCH_CONTEXT: every user is assigned to a home branch at creation.
     * This ID is used for branch-level data isolation in MAKER/CHECKER queries.
     *
     * @return Branch ID, or null if not available (system user or no branch assigned)
     */
    public static Long getCurrentUserBranchId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BranchAwareUserDetails) {
            return ((BranchAwareUserDetails) auth.getPrincipal()).getBranchId();
        }
        return null;
    }

    /**
     * Returns the current user's home branch code.
     * Used for display, voucher generation, and audit trail.
     */
    public static String getCurrentUserBranchCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BranchAwareUserDetails) {
            return ((BranchAwareUserDetails) auth.getPrincipal()).getBranchCode();
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

package com.finvanta.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CBS Security Utility — extracts current user context from Spring Security.
 *
 * Per Finacle/Temenos standards, every financial operation must be traceable
 * to a specific user and role. This utility provides:
 * - Username for audit trail (who performed the action)
 * - Role for transaction limit validation (what limits apply)
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "SYSTEM";
        }
        return auth.getName();
    }

    /**
     * Returns the current user's highest-privilege CBS role for transaction limit resolution.
     * Extracts from Spring Security GrantedAuthority, stripping the "ROLE_" prefix.
     *
     * Per CBS role hierarchy (highest privilege first):
     *   ADMIN > CHECKER > MAKER > AUDITOR
     *
     * When a user has multiple roles (e.g., ROLE_MAKER + ROLE_ADMIN), the highest-privilege
     * role is returned. This ensures transaction limits are checked against the most
     * permissive role, per Finacle/Temenos dual-role user support.
     *
     * @return Highest-privilege role string without "ROLE_" prefix, or null if no role found
     */
    public static String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return null;
        }
        // CBS role hierarchy: ADMIN has highest limits, then CHECKER, MAKER, AUDITOR
        java.util.List<String> hierarchy = java.util.List.of("ADMIN", "CHECKER", "MAKER", "AUDITOR");
        java.util.Set<String> userRoles = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .collect(java.util.stream.Collectors.toSet());

        return hierarchy.stream()
            .filter(userRoles::contains)
            .findFirst()
            .orElse(null);
    }
}

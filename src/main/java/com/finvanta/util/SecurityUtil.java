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
     * Returns the current user's CBS role (MAKER, CHECKER, ADMIN, AUDITOR).
     * Extracts from Spring Security GrantedAuthority, stripping the "ROLE_" prefix.
     *
     * Per CBS role matrix:
     *   ROLE_MAKER   → "MAKER"
     *   ROLE_CHECKER → "CHECKER"
     *   ROLE_ADMIN   → "ADMIN"
     *   ROLE_AUDITOR → "AUDITOR"
     *
     * @return Role string without "ROLE_" prefix, or null if no role found
     */
    public static String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return null;
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5)) // Strip "ROLE_" prefix
            .findFirst()
            .orElse(null);
    }
}

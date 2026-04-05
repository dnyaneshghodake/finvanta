package com.finvanta.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
     * Returns the current user's CBS role for transaction limit resolution.
     * Extracts from Spring Security GrantedAuthority, stripping the "ROLE_" prefix.
     *
     * Per CBS role hierarchy (most restrictive first for limit enforcement):
     *   AUDITOR < MAKER < CHECKER < ADMIN
     *
     * RBI Segregation of Duties Compliance:
     * When a user has multiple roles (e.g., ROLE_MAKER + ROLE_ADMIN), the LOWEST-privilege
     * role is returned. This ensures transaction limits are checked against the most
     * restrictive role, enforcing the principle of least privilege per:
     *   - RBI Master Direction on IT Governance (2023) Section 8.3
     *   - RBI Guidelines on Internal Controls in Banks (segregation of duties)
     *   - Finacle TRAN_AUTH: "dual-role users are subject to the lower limit"
     *
     * Rationale: If a user has both MAKER and ADMIN roles, they should be subject to
     * MAKER limits when initiating transactions. ADMIN limits only apply when performing
     * administrative functions. This prevents a MAKER from exploiting an incidental
     * ADMIN role to bypass per-transaction limits.
     *
     * @return Lowest-privilege role string without "ROLE_" prefix, or null if no role found
     */
    public static String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getAuthorities() == null) {
            return null;
        }
        // CBS role hierarchy: AUDITOR is most restrictive, ADMIN is least restrictive.
        // Return the LOWEST-privilege role the user holds (principle of least privilege).
        List<String> leastPrivilegeFirst = List.of("AUDITOR", "MAKER", "CHECKER", "ADMIN");
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
}

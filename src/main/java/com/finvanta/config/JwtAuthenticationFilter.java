package com.finvanta.config;

import com.finvanta.util.TenantContext;

import io.jsonwebtoken.Claims;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CBS JWT Authentication Filter per Finacle Connect / Temenos IRIS.
 *
 * Extracts and validates JWT from the Authorization header for all
 * /api/v1/** requests. Sets Spring Security context with tenant,
 * role, and branch from JWT claims — enabling @PreAuthorize and
 * BranchAccessValidator without session state.
 *
 * Per RBI IT Governance Direction 2023 §8.3:
 * - Only ACCESS tokens are accepted (REFRESH tokens rejected)
 * - Tenant context set from JWT claim (not from session)
 * - MDC populated for structured logging traceability
 * - No session created (stateless per SecurityConfig)
 *
 * Filter Chain Position:
 *   TenantFilter → JwtAuthenticationFilter → @PreAuthorize → Controller
 *
 * CBS SECURITY: This filter runs ONLY on /api/v1/** paths.
 * Thymeleaf UI paths use session-based auth (separate SecurityFilterChain).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);

        if (authHeader == null
                || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(
                BEARER_PREFIX.length());
        Claims claims = jwtTokenService.validateToken(token);

        if (claims == null) {
            // Invalid/expired token — let Spring Security
            // handle as unauthenticated (401)
            filterChain.doFilter(request, response);
            return;
        }

        // CBS SECURITY: Only ACCESS tokens can authorize
        // financial API operations. REFRESH tokens carry
        // no role/branch and MUST be rejected here.
        if (!jwtTokenService.isAccessToken(claims)) {
            log.warn("Non-ACCESS token used for API call: "
                    + "user={}, type={}",
                    claims.getSubject(),
                    claims.get("type"));
            filterChain.doFilter(request, response);
            return;
        }

        String username =
                jwtTokenService.getUsername(claims);
        String tenantId =
                jwtTokenService.getTenantId(claims);
        String role = jwtTokenService.getRole(claims);
        String branchCode =
                jwtTokenService.getBranchCode(claims);

        // Set tenant context from JWT (overrides
        // TenantFilter's session/header resolution)
        TenantContext.setCurrentTenant(tenantId);

        // Set MDC for structured logging
        MDC.put("tenantId", tenantId);
        MDC.put("username", username);
        if (branchCode != null) {
            MDC.put("branchCode", branchCode);
        }

        // Build Spring Security authentication with
        // role authority and branch-aware principal
        var authorities = Collections.singletonList(
                new SimpleGrantedAuthority(
                        "ROLE_" + role));

        // Use BranchAwareUserDetails as principal so
        // SecurityUtil.getCurrentUserBranchId() works
        // for branch isolation in service layer
        BranchAwareUserDetails principal =
                new BranchAwareUserDetails(
                        username,
                        "", // no password needed for JWT
                        true, // accountNonLocked
                        false, // passwordExpired=false
                        authorities,
                        null, // branchId resolved by code
                        branchCode,
                        false); // mfaRequired=false

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        principal, null, authorities);
        auth.setDetails(
                new WebAuthenticationDetailsSource()
                        .buildDetails(request));

        SecurityContextHolder.getContext()
                .setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    /**
     * Only apply this filter to /api/v1/** paths.
     * Thymeleaf UI paths use session-based auth.
     */
    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/api/v1/");
    }
}

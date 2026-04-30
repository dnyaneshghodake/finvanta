package com.finvanta.config;

import com.finvanta.util.TenantContext;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

/**
 * CBS Tenant & MDC Context Filter per Finacle/Temenos Tier-1 standards.
 *
 * Sets two critical contexts for every HTTP request:
 *
 * 1. TenantContext (ThreadLocal) — Multi-tenant data isolation.
 *    Every repository query filters by tenantId to prevent cross-tenant data leaks.
 *    Resolved from: X-Tenant-Id header → session → DEFAULT fallback.
 *
 * 2. SLF4J MDC (Mapped Diagnostic Context) — Structured logging traceability.
 *    Per RBI IT Governance Direction 2023 Section 7.4: all log entries must carry
 *    tenant, branch, and user context for SIEM/SOC correlation and forensic analysis.
 *    MDC keys: tenantId, branchCode, username (read by logback-spring.xml pattern).
 *
 * Per Finacle BRANCH_CONTEXT / Temenos USER.CONTEXT:
 * Every operation in a Tier-1 CBS must be traceable to a specific tenant, branch,
 * and user. This filter is the single entry point that establishes both contexts.
 *
 * IMPORTANT: MDC is set AFTER chain.doFilter starts (because Spring Security
 * populates the SecurityContext during filter chain processing). The MDC is
 * populated from SecurityContext which is available after authentication.
 * For pre-auth requests (login page, static resources), MDC falls back to defaults.
 */
@Component
@Order(1)
public class TenantFilter implements Filter {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String TENANT_SESSION_KEY = "TENANT_ID";

    /**
     * CBS Tenant ID validation regex per Finacle BANK_ID / Temenos COMPANY.ID.
     * Allows alphanumeric characters only, 1-20 chars.
     * Prevents injection of SQL/JPQL special characters via X-Tenant-Id header.
     * Per RBI IT Governance Direction 2023: all external input must be validated.
     */
    private static final Pattern TENANT_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{1,20}$");

    /** MDC keys matching logback-spring.xml pattern: %X{tenantId}/%X{branchCode}/%X{username} */
    private static final String MDC_TENANT = "tenantId";
    private static final String MDC_BRANCH = "branchCode";
    private static final String MDC_USER = "username";

    /**
     * CBS Tier-1: Request correlation ID per RBI IT Governance Direction 2023 §7.4.
     * Unique per HTTP request for SIEM correlation and support escalation.
     * Exposed to error pages via request attribute so users can quote it to support.
     * Per Finacle TRAN_REF / Temenos OFS.ID: every operation has a traceable reference.
     */
    private static final String MDC_REQUEST_ID = "requestId";
    private static final String REQUEST_ATTR_REQUEST_ID = "fvRequestId";

    /** Monotonic counter for unique request IDs — survives servlet request object reuse. */
    private static final AtomicLong REQUEST_COUNTER = new AtomicLong(0);

    /**
     * Populate username and branchCode MDC keys from the HTTP session's SecurityContext.
     *
     * Per Finacle/Temenos: reads SPRING_SECURITY_CONTEXT directly from the session
     * instead of using SecurityContextHolder, because this filter runs BEFORE
     * Spring Security's SecurityContextPersistenceFilter restores the context.
     * For the first request (login POST), the session has no security context yet,
     * so MDC falls back to logback defaults (SYSTEM/NONE) — this is correct behavior.
     */
    private void populateUserMdc(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);
            if (session == null) {
                return;
            }
            Object ctxObj = session.getAttribute("SPRING_SECURITY_CONTEXT");
            if (ctxObj instanceof SecurityContext securityContext) {
                Authentication auth = securityContext.getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    MDC.put(MDC_USER, auth.getName());

                    if (auth.getPrincipal() instanceof BranchAwareUserDetails userDetails) {
                        if (userDetails.getBranchCode() != null) {
                            MDC.put(MDC_BRANCH, userDetails.getBranchCode());
                        }
                    }
                }
            }
        } catch (Exception e) {
            // MDC population must never break the request — silently ignore
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            String path = httpRequest.getRequestURI();
            String contextPath = httpRequest.getContextPath();
            // CBS API tenant enforcement covers both /api/v1/** and /api/v2/**
            // (Tier-1 DDD modular controllers under /api/v2/teller/**, etc.).
            // Must stay in lockstep with SecurityConfig.apiSecurityFilterChain
            // securityMatcher and JwtAuthenticationFilter.shouldNotFilter so all
            // three filters agree on which paths are API.
            boolean isApiRequest = contextPath != null
                    && path != null
                    && (path.startsWith(contextPath + "/api/v1/")
                            || path.startsWith(contextPath + "/api/v2/"));

            // === Resolve Tenant ID ===
            // Priority: X-Tenant-Id header → session → DEFAULT fallback (UI chain only).
            // Per Finacle BANK_MASTER: tenant ID is resolved once at login and
            // stored in session. The header is for API/service-to-service calls.
            String rawHeader = httpRequest.getHeader("X-Tenant-Id");
            boolean headerProvided = rawHeader != null && !rawHeader.isBlank();
            String tenantId = headerProvided ? rawHeader : null;

            if (tenantId == null) {
                HttpSession session = httpRequest.getSession(false);
                if (session != null) {
                    Object sessionTenant = session.getAttribute(TENANT_SESSION_KEY);
                    if (sessionTenant != null) {
                        tenantId = sessionTenant.toString();
                    }
                }
            }

            // CBS Security: Validate tenant ID format to prevent injection attacks.
            // Per RBI IT Governance Direction 2023 §8.1: all external input must be
            // validated against expected format before use. An attacker could send
            // X-Tenant-Id with SQL/JPQL special characters to manipulate queries.
            // Only alphanumeric + underscore, max 20 chars (matches DB column length).
            boolean tenantIsMalformed = tenantId != null
                    && !TENANT_ID_PATTERN.matcher(tenantId).matches();

            // CBS API chain: a malformed or missing header on /api/v1/** must fail-fast
            // with HTTP 400. Silent fallback to DEFAULT for machine-to-machine traffic
            // would cause cross-tenant data leaks for any client that mistyped the
            // header name. Per Finacle Connect / Temenos IRIS API standards.
            if (isApiRequest && (tenantIsMalformed || !headerProvided)) {
                String errorCode = tenantIsMalformed ? "INVALID_TENANT_ID" : "MISSING_TENANT_ID";
                String message = tenantIsMalformed
                        ? "X-Tenant-Id header is malformed. Must match [A-Za-z0-9_]{1,20}."
                        : "X-Tenant-Id header is required for /api/v1/** requests.";
                httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                httpResponse.setContentType("application/json");
                httpResponse.getWriter().write(
                        "{\"status\":\"ERROR\",\"errorCode\":\""
                                + errorCode
                                + "\",\"message\":\""
                                + message
                                + "\"}");
                return;
            }

            // UI chain retains lenient fallback: a malformed or missing header is
            // tolerated (returns DEFAULT) so that login/static/error pages still work.
            if (tenantIsMalformed || tenantId == null || tenantId.isBlank()) {
                tenantId = DEFAULT_TENANT;
            }

            TenantContext.setCurrentTenant(tenantId);

            // === Set MDC for structured logging ===
            // Tenant ID is always available at this point (resolved above).
            MDC.put(MDC_TENANT, tenantId);

            // CBS Tier-1: Generate unique request ID for correlation.
            // Format: yyyyMMddHHmmss-NNNNN (compact, sortable, human-readable, guaranteed unique).
            // Uses AtomicLong counter instead of System.identityHashCode because servlet
            // containers reuse request objects from a pool — identityHashCode is NOT unique.
            // Exposed as request attribute for error pages and as MDC key for log lines.
            String requestId = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + "-" + Long.toHexString(REQUEST_COUNTER.incrementAndGet()).toUpperCase();
            MDC.put(MDC_REQUEST_ID, requestId);
            httpRequest.setAttribute(REQUEST_ATTR_REQUEST_ID, requestId);

            // CBS: Username and branch MDC are set BEFORE chain.doFilter().
            // For the FIRST request (login POST), SecurityContext is empty here
            // because Spring Security's SecurityContextPersistenceFilter hasn't
            // run yet — MDC will show SYSTEM/NONE for the login request itself.
            //
            // For ALL SUBSEQUENT requests (after login), the SecurityContext IS
            // restorable from the HTTP session. We read it directly from the session
            // to avoid depending on Spring Security's filter ordering.
            // This ensures every log line after login carries the correct user/branch.
            populateUserMdc(httpRequest);

            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            // Only remove the MDC keys this filter added — do NOT call
            // MDC.clear() which would wipe correlationId set by the outer
            // CorrelationIdMdcFilter (Order 0) during filter-chain unwinding.
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_BRANCH);
            MDC.remove(MDC_USER);
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}

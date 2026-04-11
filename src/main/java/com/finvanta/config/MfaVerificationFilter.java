package com.finvanta.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CBS MFA Verification Filter per RBI IT Governance Direction 2023 §8.4.
 *
 * Intercepts ALL authenticated requests and checks if MFA verification is pending.
 * If the user has authenticated with password but NOT yet verified their TOTP code,
 * this filter redirects them to /mfa/verify — blocking access to all other pages.
 *
 * Allowed paths when MFA is pending:
 *   /mfa/verify  — TOTP verification form + submission
 *   /logout      — Allow user to log out if they can't complete MFA
 *   /css/**, /js/**, /fonts/**, /img/**  — Static resources for the verification page
 *   /error/**    — Error pages
 *   /login       — Login page itself
 *
 * Per Finacle/Temenos: the session is NOT fully authorized until both factors are verified.
 * This is defense-in-depth — even if a URL is guessed, the filter blocks access.
 *
 * IMPORTANT: This filter is NOT a @Component. It is registered explicitly in
 * SecurityConfig via http.addFilterAfter(UsernamePasswordAuthenticationFilter.class)
 * to ensure it runs INSIDE the Spring Security filter chain, AFTER authentication.
 */
public class MfaVerificationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        HttpSession session = request.getSession(false);
        if (session == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Object mfaVerified = session.getAttribute(MfaAuthenticationSuccessHandler.MFA_VERIFIED_ATTR);

        // If MFA_VERIFIED is null (non-MFA user or session without the attribute) → check password expiry
        // If MFA_VERIFIED is true → MFA completed, check password expiry
        // If MFA_VERIFIED is false → MFA pending, restrict access
        String path = request.getRequestURI().substring(request.getContextPath().length());

        // Static resources and infrastructure paths — always allowed regardless of state.
        // Per Finacle/Temenos Tier-1 deployment: health endpoints (/actuator/health,
        // /actuator/info) are infrastructure paths for Docker/K8s liveness/readiness
        // probes and MUST NOT be blocked by MFA or password-expiry gates.
        // Per RBI IT Governance: operational monitoring must not be disrupted by auth gates.
        if (path.startsWith("/logout")
                || path.startsWith("/login")
                || path.startsWith("/actuator/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/fonts/")
                || path.startsWith("/img/")
                || path.startsWith("/error")) {
            filterChain.doFilter(request, response);
            return;
        }

        // === MFA PENDING GATE ===
        // When MFA_VERIFIED=false, ONLY /mfa/verify is allowed.
        // /password/change is NOT whitelisted here — an attacker who knows the password
        // but lacks the TOTP device must NOT be able to change the victim's password
        // without completing the second factor.
        if (Boolean.FALSE.equals(mfaVerified)) {
            if (path.startsWith("/mfa/verify")) {
                filterChain.doFilter(request, response);
                return;
            }
            response.sendRedirect(request.getContextPath() + "/mfa/verify");
            return;
        }

        // === PASSWORD EXPIRY GATE (runs after MFA is verified or not required) ===
        // Per RBI IT Governance Direction 2023 §8.2: expired passwords must force
        // a password change before granting access to any CBS functionality.
        // This gate runs for ALL authenticated users (MFA and non-MFA).
        Object passwordExpired = session.getAttribute(MfaAuthenticationSuccessHandler.PASSWORD_EXPIRED_ATTR);
        if (Boolean.TRUE.equals(passwordExpired)) {
            if (path.startsWith("/password/change")) {
                filterChain.doFilter(request, response);
                return;
            }
            response.sendRedirect(request.getContextPath() + "/password/change?expired=true");
            return;
        }

        // MFA verified (or not required) AND password not expired — allow all requests
        filterChain.doFilter(request, response);
    }
}

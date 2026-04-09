package com.finvanta.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
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
 *
 * Per Finacle/Temenos: the session is NOT fully authorized until both factors are verified.
 * This is defense-in-depth — even if a URL is guessed, the filter blocks access.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
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

        // If MFA_VERIFIED is null (non-MFA user or session without the attribute) → allow
        // If MFA_VERIFIED is true → allow (MFA completed)
        // If MFA_VERIFIED is false → MFA pending, restrict access
        if (mfaVerified == null || Boolean.TRUE.equals(mfaVerified)) {
            filterChain.doFilter(request, response);
            return;
        }

        // MFA is pending — check if the request is to an allowed path
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (path.startsWith("/mfa/verify")
                || path.startsWith("/logout")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/fonts/")
                || path.startsWith("/img/")
                || path.startsWith("/error")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Block access — redirect to MFA verification page
        response.sendRedirect(request.getContextPath() + "/mfa/verify");
    }
}

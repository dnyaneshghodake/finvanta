package com.finvanta.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * CBS MFA-Aware Authentication Success Handler per RBI IT Governance Direction 2023 §8.4.
 *
 * After successful password authentication (first factor), this handler checks
 * if the user requires MFA (TOTP second factor). If so, the user is redirected
 * to the TOTP verification page instead of the dashboard. The session is marked
 * as "MFA_PENDING" until the TOTP code is verified.
 *
 * Per Finacle USER_MASTER MFA / Temenos USER.MFA:
 * - Password authentication is only the first factor
 * - Users with mfa_enabled=true AND valid mfa_secret must complete TOTP verification
 * - Until TOTP is verified, the session has limited access (only /mfa/verify and /logout)
 * - The MfaVerificationFilter enforces this restriction on all other endpoints
 */
@Component
public class MfaAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    /** Session attribute key for MFA verification state */
    public static final String MFA_VERIFIED_ATTR = "MFA_VERIFIED";

    public MfaAuthenticationSuccessHandler() {
        setDefaultTargetUrl("/dashboard");
        setAlwaysUseDefaultTargetUrl(false);
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        if (authentication.getPrincipal() instanceof BranchAwareUserDetails userDetails) {
            if (userDetails.isMfaRequired()) {
                // MFA required: mark session as pending, redirect to TOTP verification
                request.getSession().setAttribute(MFA_VERIFIED_ATTR, false);
                getRedirectStrategy().sendRedirect(request, response,
                        request.getContextPath() + "/mfa/verify");
                return;
            }
        }

        // No MFA required: mark as verified (or N/A), proceed to dashboard
        request.getSession().setAttribute(MFA_VERIFIED_ATTR, true);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}

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

    /** Session attribute key for password-expired state */
    public static final String PASSWORD_EXPIRED_ATTR = "PASSWORD_EXPIRED";

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        if (authentication.getPrincipal() instanceof BranchAwareUserDetails userDetails) {
            // CBS: Check MFA requirement first (MFA gate runs before password expiry redirect)
            if (userDetails.isMfaRequired()) {
                request.getSession().setAttribute(MFA_VERIFIED_ATTR, false);
                getRedirectStrategy().sendRedirect(request, response,
                        request.getContextPath() + "/mfa/verify");
                return;
            }

            // CBS: Check if credentials are expired (password rotation enforcement)
            // Per RBI IT Governance Direction 2023 §8.2: expired passwords must force
            // a password change before granting access to any CBS functionality.
            // Spring Security allows login with expired credentials (credentialsNonExpired=false
            // only sets a flag), so we must enforce the redirect here.
            if (!userDetails.isCredentialsNonExpired()) {
                request.getSession().setAttribute(MFA_VERIFIED_ATTR, true);
                request.getSession().setAttribute(PASSWORD_EXPIRED_ATTR, true);
                getRedirectStrategy().sendRedirect(request, response,
                        request.getContextPath() + "/password/change?expired=true");
                return;
            }
        }

        // No MFA required, password not expired: proceed to dashboard
        request.getSession().setAttribute(MFA_VERIFIED_ATTR, true);
        request.getSession().setAttribute(PASSWORD_EXPIRED_ATTR, false);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}

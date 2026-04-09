package com.finvanta.controller;

import com.finvanta.config.MfaAuthenticationSuccessHandler;
import com.finvanta.service.MfaService;
import com.finvanta.util.SecurityUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * CBS MFA Login Verification Controller per RBI IT Governance Direction 2023 §8.4.
 *
 * Handles the second factor (TOTP) verification during login.
 * After password authentication succeeds, users with MFA enabled are redirected
 * here to enter their 6-digit TOTP code before gaining full session access.
 *
 * Per Finacle USER_MASTER MFA / Temenos USER.MFA:
 * - This page is the ONLY accessible page while MFA is pending (enforced by MfaVerificationFilter)
 * - Failed TOTP attempts are tracked in session and logged for security monitoring
 * - After MAX_MFA_ATTEMPTS failures, session is invalidated (forces re-login)
 * - After successful verification, session attribute MFA_VERIFIED is set to true
 * - User is then redirected to the dashboard
 *
 * Per RBI IT Governance Direction 2023: MFA must have brute-force protection
 * equivalent to password lockout (max 5 failed attempts per session).
 */
@Controller
@RequestMapping("/mfa")
public class MfaLoginController {

    private static final Logger log = LoggerFactory.getLogger(MfaLoginController.class);

    /** Maximum failed TOTP attempts before session is invalidated per RBI IT Governance */
    private static final int MAX_MFA_ATTEMPTS = 5;

    /** Session attribute key for tracking failed MFA attempts */
    private static final String MFA_ATTEMPTS_ATTR = "MFA_FAILED_ATTEMPTS";

    private final MfaService mfaService;

    public MfaLoginController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    /** Show TOTP verification form with remaining attempts */
    @GetMapping("/verify")
    public ModelAndView showVerifyForm(HttpServletRequest request) {
        ModelAndView mav = new ModelAndView("mfa/verify");
        mav.addObject("username", SecurityUtil.getCurrentUsername());
        HttpSession session = request.getSession(false);
        int attempts = 0;
        if (session != null && session.getAttribute(MFA_ATTEMPTS_ATTR) != null) {
            attempts = (int) session.getAttribute(MFA_ATTEMPTS_ATTR);
        }
        mav.addObject("remainingAttempts", MAX_MFA_ATTEMPTS - attempts);
        return mav;
    }

    /** Verify TOTP code submitted by user with brute-force protection */
    @PostMapping("/verify")
    public String verifyTotp(
            @RequestParam(required = false) String totpCode,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String username = SecurityUtil.getCurrentUsername();

        // CBS: Server-side input validation — defense-in-depth per OWASP / RBI
        if (totpCode == null || totpCode.isBlank()) {
            redirectAttributes.addFlashAttribute("error",
                    "Please enter the 6-digit code from your authenticator app.");
            return "redirect:/mfa/verify";
        }

        if (!totpCode.matches("\\d{6}")) {
            redirectAttributes.addFlashAttribute("error",
                    "TOTP code must be exactly 6 digits (0-9). You entered: " + totpCode.length() + " characters.");
            return "redirect:/mfa/verify";
        }

        boolean valid = mfaService.verifyLoginTotp(username, totpCode);

        if (valid) {
            // Reset attempt counter and mark session as MFA-verified
            request.getSession().removeAttribute(MFA_ATTEMPTS_ATTR);
            request.getSession().setAttribute(MfaAuthenticationSuccessHandler.MFA_VERIFIED_ATTR, true);
            log.info("MFA login verification successful for user: {}", username);

            // CBS CRITICAL: Check password expiry AFTER MFA completion.
            // Per RBI IT Governance Direction 2023 §8.2: expired passwords must force
            // a password change before granting access to any CBS functionality.
            // The PASSWORD_EXPIRED_ATTR was set by MfaAuthenticationSuccessHandler
            // BEFORE the MFA redirect, so it's available in the session now.
            Object passwordExpired = request.getSession().getAttribute(
                    MfaAuthenticationSuccessHandler.PASSWORD_EXPIRED_ATTR);
            if (Boolean.TRUE.equals(passwordExpired)) {
                log.info("MFA verified but password expired for user: {} — redirecting to password change", username);
                return "redirect:/password/change?expired=true";
            }

            return "redirect:/dashboard";
        }

        // Failed attempt — track and enforce lockout
        HttpSession session = request.getSession();
        int attempts = 1;
        if (session.getAttribute(MFA_ATTEMPTS_ATTR) != null) {
            attempts = (int) session.getAttribute(MFA_ATTEMPTS_ATTR) + 1;
        }
        session.setAttribute(MFA_ATTEMPTS_ATTR, attempts);

        log.warn("MFA verification FAILED for user: {} (attempt {}/{})", username, attempts, MAX_MFA_ATTEMPTS);

        if (attempts >= MAX_MFA_ATTEMPTS) {
            // Per RBI IT Governance: invalidate session after max failed attempts
            // User must re-authenticate with password + TOTP from scratch
            log.error("MFA LOCKOUT: user {} exceeded {} failed TOTP attempts — session invalidated",
                    username, MAX_MFA_ATTEMPTS);
            SecurityContextHolder.clearContext();
            session.invalidate();
            redirectAttributes.addFlashAttribute("error",
                    "Too many failed TOTP attempts. Your session has been terminated for security. Please login again.");
            return "redirect:/login";
        }

        int remaining = MAX_MFA_ATTEMPTS - attempts;
        redirectAttributes.addFlashAttribute("error",
                "Invalid TOTP code. " + remaining + " attempt(s) remaining before session lockout.");
        return "redirect:/mfa/verify";
    }
}

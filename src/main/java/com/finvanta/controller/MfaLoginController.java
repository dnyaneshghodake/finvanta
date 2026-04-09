package com.finvanta.controller;

import com.finvanta.config.MfaAuthenticationSuccessHandler;
import com.finvanta.service.MfaService;
import com.finvanta.util.SecurityUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * CBS MFA Login Verification Controller per RBI IT Governance Direction 2023 §8.4.
 *
 * Handles the second factor (TOTP) verification during login.
 * After password authentication succeeds, users with MFA enabled are redirected
 * here to enter their 6-digit TOTP code before gaining full session access.
 *
 * Per Finacle USER_MASTER MFA / Temenos USER.MFA:
 * - This page is the ONLY accessible page while MFA is pending (enforced by MfaVerificationFilter)
 * - Failed TOTP attempts are logged for security monitoring
 * - After successful verification, session attribute MFA_VERIFIED is set to true
 * - User is then redirected to the dashboard
 */
@Controller
@RequestMapping("/mfa")
public class MfaLoginController {

    private static final Logger log = LoggerFactory.getLogger(MfaLoginController.class);

    private final MfaService mfaService;

    public MfaLoginController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    /** Show TOTP verification form */
    @GetMapping("/verify")
    public ModelAndView showVerifyForm() {
        ModelAndView mav = new ModelAndView("mfa/verify");
        mav.addObject("username", SecurityUtil.getCurrentUsername());
        return mav;
    }

    /** Verify TOTP code submitted by user */
    @PostMapping("/verify")
    public String verifyTotp(
            @RequestParam String totpCode,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String username = SecurityUtil.getCurrentUsername();

        boolean valid = mfaService.verifyLoginTotp(username, totpCode);

        if (valid) {
            // Mark session as MFA-verified — MfaVerificationFilter will now allow all requests
            request.getSession().setAttribute(MfaAuthenticationSuccessHandler.MFA_VERIFIED_ATTR, true);
            log.info("MFA login verification successful for user: {}", username);
            return "redirect:/dashboard";
        } else {
            log.warn("MFA login verification FAILED for user: {} (invalid TOTP code)", username);
            redirectAttributes.addFlashAttribute("error",
                    "Invalid TOTP code. Please enter the current 6-digit code from your authenticator app.");
            return "redirect:/mfa/verify";
        }
    }
}

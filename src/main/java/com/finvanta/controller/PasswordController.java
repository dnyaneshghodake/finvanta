package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Password Management Controller per RBI IT Governance Direction 2023 Section 8.2.
 *
 * Handles:
 * 1. Self-service password change (any authenticated user)
 * 2. Forced password change on expiry (redirected from login)
 *
 * Per Finacle USER_MASTER / Temenos USER password policy:
 * - Minimum 8 characters with complexity (upper + lower + digit + special)
 * - Cannot reuse last 3 passwords (history check)
 * - Password expires every 90 days (forced change on next login)
 * - All password changes audited via AuditService
 *
 * Accessible by ALL authenticated users (not restricted to ADMIN).
 */
@Controller
@RequestMapping("/password")
public class PasswordController {

    private static final Logger log = LoggerFactory.getLogger(PasswordController.class);

    /** Minimum password length per RBI IT Governance */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Password complexity regex per RBI IT Governance Direction 2023 Section 8.2.
     * Requires at least: 1 uppercase, 1 lowercase, 1 digit, 1 special character.
     */
    private static final String PASSWORD_COMPLEXITY_REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()\\-_=+])[A-Za-z\\d@$!%*?&#^()\\-_=+]{8,}$";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public PasswordController(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * Show password change form.
     * Used for both self-service change and forced change on expiry.
     *
     * @param expired If true, shows "your password has expired" message
     */
    @GetMapping("/change")
    public ModelAndView showChangeForm(@RequestParam(required = false) Boolean expired) {
        ModelAndView mav = new ModelAndView("password/change");
        mav.addObject("username", SecurityUtil.getCurrentUsername());
        mav.addObject("expired", Boolean.TRUE.equals(expired));
        return mav;
    }

    /**
     * Process password change with full CBS validation.
     *
     * Per RBI IT Governance Direction 2023 Section 8.2:
     * 1. Current password must be verified (prevents unauthorized change)
     * 2. New password must meet complexity requirements
     * 3. New password cannot match any of the last 3 passwords (history check)
     * 4. Password expiry date is reset to +90 days
     * 5. Change is audited via AuditService
     */
    @PostMapping("/change")
    @Transactional
    public String changePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String username = SecurityUtil.getCurrentUsername();
        String tenantId = TenantContext.getCurrentTenant();

        try {
            // Validate inputs
            if (newPassword == null || newPassword.isBlank()) {
                throw new BusinessException("EMPTY_PASSWORD", "New password cannot be empty.");
            }
            if (!newPassword.equals(confirmPassword)) {
                throw new BusinessException("PASSWORD_MISMATCH", "New password and confirmation do not match.");
            }
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new BusinessException("WEAK_PASSWORD",
                        "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }
            if (!newPassword.matches(PASSWORD_COMPLEXITY_REGEX)) {
                throw new BusinessException("WEAK_PASSWORD",
                        "Password must contain at least 1 uppercase letter, 1 lowercase letter, "
                                + "1 digit, and 1 special character (@$!%*?&#^()-_=+).");
            }

            AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                    .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + username));

            // Verify current password
            if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new BusinessException("WRONG_PASSWORD", "Current password is incorrect.");
            }

            // Check new password is not same as current
            if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
                throw new BusinessException("PASSWORD_REUSE",
                        "New password cannot be the same as your current password.");
            }

            // Check password history (last 3 passwords per RBI)
            if (user.getPasswordHistory() != null && !user.getPasswordHistory().isBlank()) {
                String[] history = user.getPasswordHistory().split("\\|");
                for (String oldHash : history) {
                    if (oldHash != null && !oldHash.isBlank() && passwordEncoder.matches(newPassword, oldHash)) {
                        throw new BusinessException("PASSWORD_REUSE",
                                "Cannot reuse any of your last 3 passwords per RBI IT Governance policy.");
                    }
                }
            }

            // All validations passed — change password
            user.changePassword(passwordEncoder.encode(newPassword));
            user.setUpdatedBy(username);
            userRepository.save(user);

            auditService.logEvent(
                    "AppUser", user.getId(), "PASSWORD_CHANGED", null, null,
                    "USER_MANAGEMENT",
                    "Password changed by user: " + username + " (self-service)");

            log.info("Password changed: user={} (self-service)", username);

            // CBS Tier-1: Invalidate session after password change per Finacle/Temenos standards.
            // Per RBI IT Governance Direction 2023 §8.2:
            // - The current session was authenticated with the OLD (expired) password
            // - A session authenticated with compromised/expired credentials must not continue
            // - User must re-login with the new password to prove they remember it
            // - This also ensures all session attributes (MFA_VERIFIED, PASSWORD_EXPIRED) are reset
            // Per Finacle USER_MASTER: forced password reset terminates session → re-login required
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            request.getSession().invalidate();

            // CBS: Use query parameter instead of flash attribute because session.invalidate()
            // destroys the session — flash attributes are stored in the session via FlashMap
            // and would be lost. The login page checks for ?password_changed parameter.
            // This matches the pattern used by MfaLoginController for ?mfa_locked.
            return "redirect:/login?password_changed";

        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/password/change";
        }
    }
}

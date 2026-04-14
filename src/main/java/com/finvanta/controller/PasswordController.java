package com.finvanta.controller;

import com.finvanta.service.UserService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
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
 * CBS Code Quality: No @Transactional on controller methods.
 * All business logic (validation, encoding, history check, audit) is managed
 * by UserService.changeSelfServicePassword(). Controller only handles
 * HTTP-layer concerns: session invalidation and redirect.
 *
 * Accessible by ALL authenticated users (not restricted to ADMIN).
 */
@Controller
@RequestMapping("/password")
public class PasswordController {

    private final UserService userService;

    public PasswordController(UserService userService) {
        this.userService = userService;
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
     * Process password change — delegates to UserService for all business logic.
     *
     * Per RBI IT Governance Direction 2023 Section 8.2:
     * All validation, encoding, history check, and audit are in UserService.
     * Controller only handles session invalidation (HTTP-layer concern).
     */
    @PostMapping("/change")
    public String changePassword(
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        try {
            String username = SecurityUtil.getCurrentUsername();

            // CBS: All business logic delegated to UserService.
            userService.changeSelfServicePassword(username, currentPassword, newPassword, confirmPassword);

            // CBS Tier-1: Invalidate session after password change per Finacle/Temenos standards.
            // Per RBI IT Governance Direction 2023 §8.2:
            // - The current session was authenticated with the OLD (expired) password
            // - A session authenticated with compromised/expired credentials must not continue
            // - User must re-login with the new password to prove they remember it
            // - This also ensures all session attributes (MFA_VERIFIED, PASSWORD_EXPIRED) are reset
            // Per Finacle USER_MASTER: forced password reset terminates session → re-login required
            SecurityContextHolder.clearContext();
            request.getSession().invalidate();

            // CBS: Use query parameter instead of flash attribute because session.invalidate()
            // destroys the session — flash attributes are stored in the session via FlashMap
            // and would be lost. The login page checks for ?password_changed parameter.
            return "redirect:/login?password_changed";

        } catch (BusinessException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/password/change";
        }
    }
}

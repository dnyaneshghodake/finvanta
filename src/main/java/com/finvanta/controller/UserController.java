package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.enums.UserRole;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.BranchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS User Management Controller per Finacle USER_MASTER / Temenos USER.
 *
 * Per RBI IT Governance Direction 2023:
 * - User creation/modification requires ADMIN authorization
 * - Password must be bcrypt-hashed (never plaintext in production)
 * - Account lockout after configurable failed attempts
 * - All user lifecycle events must be audited
 *
 * ADMIN-only access (enforced in SecurityConfig /admin/** rule).
 */
@Controller
@RequestMapping("/admin/users")
public class UserController {

    private final AppUserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserController(
            AppUserRepository userRepository,
            BranchRepository branchRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.branchRepository = branchRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /** List all users for the tenant */
    @GetMapping
    public ModelAndView listUsers() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/users");
        mav.addObject("users", userRepository.findByTenantIdOrderByRoleAscUsernameAsc(tenantId));
        mav.addObject("roles", UserRole.values());
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("pageTitle", "User Management");
        return mav;
    }

    /** Create a new user — password is bcrypt-hashed */
    @PostMapping("/create")
    @Transactional
    public String createUser(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String fullName,
            @RequestParam(required = false) String email,
            @RequestParam String role,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            if (userRepository.existsByTenantIdAndUsername(tenantId, username)) {
                throw new BusinessException("DUPLICATE_USERNAME", "Username already exists: " + username);
            }
            if (password == null || password.length() < 8) {
                throw new BusinessException(
                        "WEAK_PASSWORD", "Password must be at least 8 characters per RBI IT Governance");
            }

            Branch branch = branchRepository
                    .findById(branchId)
                    .filter(b -> b.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found"));

            AppUser user = new AppUser();
            user.setTenantId(tenantId);
            user.setUsername(username);
            // CBS: Use changePassword() to set password with expiry and history tracking.
            // Per RBI IT Governance Direction 2023: password rotation every 90 days.
            user.changePassword(passwordEncoder.encode(password));
            user.setFullName(fullName);
            user.setEmail(email);
            user.setRole(UserRole.valueOf(role));
            user.setBranch(branch);
            user.setActive(true);
            user.setLocked(false);
            user.setFailedLoginAttempts(0);
            user.setCreatedBy(SecurityUtil.getCurrentUsername());

            AppUser saved = userRepository.save(user);
            auditService.logEvent(
                    "AppUser",
                    saved.getId(),
                    "USER_CREATED",
                    null,
                    username,
                    "USER_MANAGEMENT",
                    "User created: " + username + " | Role: " + role + " | Branch: " + branch.getBranchCode()
                            + " | By: " + SecurityUtil.getCurrentUsername());

            redirectAttributes.addFlashAttribute("success", "User created: " + username);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Toggle user active/inactive status */
    @PostMapping("/toggle-active/{id}")
    @Transactional
    public String toggleActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            AppUser user = userRepository
                    .findById(id)
                    .filter(u -> u.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
            boolean newState = !user.isActive();
            user.setActive(newState);
            user.setUpdatedBy(SecurityUtil.getCurrentUsername());
            userRepository.save(user);
            auditService.logEvent(
                    "AppUser",
                    user.getId(),
                    newState ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                    String.valueOf(!newState),
                    String.valueOf(newState),
                    "USER_MANAGEMENT",
                    "User " + (newState ? "activated" : "deactivated") + ": " + user.getUsername() + " by "
                            + SecurityUtil.getCurrentUsername());
            redirectAttributes.addFlashAttribute(
                    "success", "User " + (newState ? "activated" : "deactivated") + ": " + user.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Unlock a locked user account */
    @PostMapping("/unlock/{id}")
    @Transactional
    public String unlockUser(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            AppUser user = userRepository
                    .findById(id)
                    .filter(u -> u.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
            user.setLocked(false);
            user.setFailedLoginAttempts(0);
            user.setUpdatedBy(SecurityUtil.getCurrentUsername());
            userRepository.save(user);
            auditService.logEvent(
                    "AppUser",
                    user.getId(),
                    "USER_UNLOCKED",
                    "LOCKED",
                    "UNLOCKED",
                    "USER_MANAGEMENT",
                    "User unlocked: " + user.getUsername() + " by " + SecurityUtil.getCurrentUsername());
            redirectAttributes.addFlashAttribute("success", "User unlocked: " + user.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Reset user password — bcrypt-hashed */
    @PostMapping("/reset-password/{id}")
    @Transactional
    public String resetPassword(
            @PathVariable Long id, @RequestParam String newPassword, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            if (newPassword == null || newPassword.length() < 8) {
                throw new BusinessException(
                        "WEAK_PASSWORD", "Password must be at least 8 characters per RBI IT Governance");
            }
            AppUser user = userRepository
                    .findById(id)
                    .filter(u -> u.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));
            // CBS: Use changePassword() to track password history and set expiry.
            // Per RBI IT Governance: users cannot reuse last 3 passwords.
            user.changePassword(passwordEncoder.encode(newPassword));
            user.setUpdatedBy(SecurityUtil.getCurrentUsername());
            userRepository.save(user);
            auditService.logEvent(
                    "AppUser",
                    user.getId(),
                    "PASSWORD_RESET",
                    null,
                    null,
                    "USER_MANAGEMENT",
                    "Password reset for: " + user.getUsername() + " by " + SecurityUtil.getCurrentUsername());
            redirectAttributes.addFlashAttribute("success", "Password reset for: " + user.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}

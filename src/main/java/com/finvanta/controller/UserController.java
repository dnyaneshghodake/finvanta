package com.finvanta.controller;

import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.enums.UserRole;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.service.BranchService;
import com.finvanta.service.UserService;
import com.finvanta.util.TenantContext;

import org.springframework.stereotype.Controller;
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
 * CBS Code Quality: No @Transactional on controller methods.
 * All business logic and transactions are managed by UserService.
 * Controller only handles HTTP request/response mapping and view delegation.
 *
 * ADMIN-only access (enforced in SecurityConfig /admin/** rule).
 */
@Controller
@RequestMapping("/admin/users")
public class UserController {

    private final UserService userService;
    private final BranchService branchService;
    private final AppUserRepository appUserRepository;

    public UserController(UserService userService, BranchService branchService, AppUserRepository appUserRepository) {
        this.userService = userService;
        this.branchService = branchService;
        this.appUserRepository = appUserRepository;
    }

    /** List all users for the tenant */
    @GetMapping
    public ModelAndView listUsers() {
        ModelAndView mav = new ModelAndView("admin/users");
        mav.addObject("users", userService.listUsers());
        mav.addObject("roles", UserRole.values());
        mav.addObject("branches", branchService.listActiveBranches());
        mav.addObject("pageTitle", "User Management");
        return mav;
    }

    /**
     * CBS User Search per Finacle USER_INQUIRY / RBI IT Governance §8.2.
     * Searches by username, full name, email, role, or branch code.
     * Per RBI IT Governance Direction 2023 §8.2: audit of user access must be searchable.
     * ADMIN-only (enforced in SecurityConfig). Tenant-scoped.
     */
    @GetMapping("/search")
    public ModelAndView searchUsers(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/users");
        if (q != null && !q.isBlank() && q.trim().length() >= 2) {
            mav.addObject("users", appUserRepository.searchUsers(tenantId, q.trim()));
            mav.addObject("searchQuery", q);
        } else {
            mav.addObject("users", userService.listUsers());
        }
        mav.addObject("roles", UserRole.values());
        mav.addObject("branches", branchService.listActiveBranches());
        mav.addObject("pageTitle", "User Management");
        return mav;
    }

    /** Create a new user — delegated to UserService */
    @PostMapping("/create")
    public String createUser(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String fullName,
            @RequestParam(required = false) String email,
            @RequestParam String role,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        try {
            AppUser saved = userService.createUser(username, password, fullName, email, role, branchId);
            redirectAttributes.addFlashAttribute("success", "User created: " + saved.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Toggle user active/inactive status */
    @PostMapping("/toggle-active/{id}")
    public String toggleActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            AppUser user = userService.toggleActive(id);
            redirectAttributes.addFlashAttribute(
                    "success", "User " + (user.isActive() ? "activated" : "deactivated") + ": " + user.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Unlock a locked user account */
    @PostMapping("/unlock/{id}")
    public String unlockUser(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            AppUser user = userService.unlockUser(id);
            redirectAttributes.addFlashAttribute("success", "User unlocked: " + user.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Reset user password — delegated to UserService */
    @PostMapping("/reset-password/{id}")
    public String resetPassword(
            @PathVariable Long id, @RequestParam String newPassword, RedirectAttributes redirectAttributes) {
        try {
            AppUser user = userService.resetPassword(id, newPassword);
            redirectAttributes.addFlashAttribute("success", "Password reset for: " + user.getUsername());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}

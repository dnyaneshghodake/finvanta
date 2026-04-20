package com.finvanta.api;

import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.service.UserService;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS User Management REST API per Finacle USER_MASTER / Temenos EB.USER.
 *
 * <p>Thin orchestration layer over {@link UserService} — no business logic here.
 * All validation (password complexity, duplicate check, history check, lockout
 * management) and audit trail reside in UserService.
 *
 * <p>Per RBI IT Governance Direction 2023 §8.2:
 * <ul>
 *   <li>User lifecycle events must be audited (create, activate, deactivate, unlock, reset)</li>
 *   <li>Password must meet complexity requirements (upper+lower+digit+special, min 8)</li>
 *   <li>Last 3 passwords cannot be reused</li>
 *   <li>All user operations are ADMIN-only</li>
 * </ul>
 *
 * <p>CBS Role Matrix: ALL endpoints are ADMIN-only.
 * Per Finacle: user management is a privileged operation requiring
 * ADMIN role (branch manager level or above).
 *
 * <p>CBS SECURITY: User responses NEVER expose password hashes, MFA secrets,
 * or password history. Only operational metadata is returned.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserApiController {

    private final UserService userService;
    private final AppUserRepository appUserRepository;

    public UserApiController(
            UserService userService,
            AppUserRepository appUserRepository) {
        this.userService = userService;
        this.appUserRepository = appUserRepository;
    }

    // === Inquiry ===

    /**
     * List all users for the tenant, ordered by role then username.
     * Per Finacle USER_INQUIRY: ADMIN sees all users across all branches.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>>
            listUsers() {
        var users = userService.listUsers();
        var items = users.stream()
                .map(UserResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Search users by username, full name, email, role, or branch code.
     * Per RBI IT Governance §8.2: ADMIN must locate users for access
     * management and RBI inspection queries.
     */
    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<UserResponse>>>
            searchUsers(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        List<AppUser> users;
        if (q != null && q.trim().length() >= 2) {
            users = appUserRepository.searchUsers(
                    tenantId, q.trim());
        } else {
            users = userService.listUsers();
        }
        var items = users.stream()
                .map(UserResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Mutations ===

    /**
     * Create a new CBS user. ADMIN only.
     * Per RBI IT Governance §8.2: password complexity enforced by UserService.
     * Duplicate username check is tenant-scoped.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>>
            createUser(
                    @Valid @RequestBody CreateUserRequest req) {
        AppUser saved = userService.createUser(
                req.username(), req.password(),
                req.fullName(), req.email(),
                req.role(), req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                UserResponse.from(saved),
                "User created: " + saved.getUsername()));
    }

    /**
     * Toggle user active/inactive status. ADMIN only.
     * Per Finacle USER_MASTER: deactivated users cannot log in
     * but their data is retained for audit trail.
     */
    @PostMapping("/{id}/toggle-active")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>>
            toggleActive(@PathVariable Long id) {
        AppUser user = userService.toggleActive(id);
        String action = user.isActive()
                ? "activated" : "deactivated";
        return ResponseEntity.ok(ApiResponse.success(
                UserResponse.from(user),
                "User " + action + ": "
                        + user.getUsername()));
    }

    /**
     * Unlock a locked user account. ADMIN only.
     * Per RBI IT Governance §8.2: locked accounts can be unlocked
     * by ADMIN before the auto-unlock duration expires.
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>>
            unlockUser(@PathVariable Long id) {
        AppUser user = userService.unlockUser(id);
        return ResponseEntity.ok(ApiResponse.success(
                UserResponse.from(user),
                "User unlocked: " + user.getUsername()));
    }

    /**
     * Admin password reset. ADMIN only.
     * Per RBI IT Governance §8.2: password complexity and history
     * check enforced by UserService. The target user must re-login
     * with the new password.
     */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserResponse>>
            resetPassword(@PathVariable Long id,
                    @Valid @RequestBody ResetPasswordRequest req) {
        AppUser user = userService.resetPassword(
                id, req.newPassword());
        return ResponseEntity.ok(ApiResponse.success(
                UserResponse.from(user),
                "Password reset for: "
                        + user.getUsername()));
    }

    // === Request DTOs ===

    public record CreateUserRequest(
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String fullName,
            String email,
            @NotBlank String role,
            @NotNull Long branchId) {}

    public record ResetPasswordRequest(
            @NotBlank String newPassword) {}

    // === Response DTOs ===

    /**
     * User response DTO per Finacle USER_INQUIRY.
     * CBS SECURITY: NEVER exposes passwordHash, mfaSecret, or passwordHistory.
     * Only operational metadata for admin management screens.
     */
    public record UserResponse(
            Long id,
            String username,
            String fullName,
            String email,
            String role,
            String branchCode,
            String branchName,
            boolean active,
            boolean locked,
            boolean mfaEnabled,
            boolean passwordExpired,
            int failedLoginAttempts,
            String lastLoginAt,
            String lastPasswordChange,
            String passwordExpiryDate,
            String createdAt) {
        static UserResponse from(AppUser u) {
            return new UserResponse(
                    u.getId(),
                    u.getUsername(),
                    u.getFullName(),
                    u.getEmail(),
                    u.getRole() != null
                            ? u.getRole().name() : null,
                    u.getBranch() != null
                            ? u.getBranch().getBranchCode()
                            : null,
                    u.getBranch() != null
                            ? u.getBranch().getBranchName()
                            : null,
                    u.isActive(),
                    u.isLocked(),
                    u.isMfaEnabled(),
                    u.isPasswordExpired(),
                    u.getFailedLoginAttempts(),
                    u.getLastLoginAt() != null
                            ? u.getLastLoginAt().toString()
                            : null,
                    u.getLastPasswordChange() != null
                            ? u.getLastPasswordChange()
                            .toString() : null,
                    u.getPasswordExpiryDate() != null
                            ? u.getPasswordExpiryDate()
                            .toString() : null,
                    u.getCreatedAt() != null
                            ? u.getCreatedAt().toString()
                            : null);
        }
    }
}

package com.finvanta.api;

import com.finvanta.service.UserService;
import com.finvanta.util.SecurityUtil;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Self-Service Password Change REST API per Finacle USER_PWDCHG / Temenos EB.PASSWORD.
 *
 * <p>Thin orchestration layer over {@link UserService#changeSelfServicePassword}.
 * All validation (current password verification, complexity check, history check,
 * confirm-match, audit trail) resides in UserService.
 *
 * <p>Per RBI IT Governance Direction 2023 §8.2:
 * <ul>
 *   <li>Password must meet complexity requirements (upper+lower+digit+special, min 8)</li>
 *   <li>Last 3 passwords cannot be reused</li>
 *   <li>Password rotation every 90 days (enforced at login via PASSWORD_EXPIRED)</li>
 *   <li>Current password must be verified before allowing change</li>
 * </ul>
 *
 * <p>CBS Role Matrix: Any authenticated user can change their own password.
 * Admin password reset for other users is in {@link UserApiController}.
 *
 * <p>CBS SECURITY: After successful password change, the API returns a
 * directive to re-authenticate. The BFF must clear the current session
 * and redirect to login. Per Finacle USER_MASTER: a session authenticated
 * with the old (now-changed) password must not continue.
 *
 * <p>This endpoint is placed under {@code /api/v1/auth/password} (within the
 * auth namespace) because it is a credential management operation, not a
 * business operation. However, unlike {@code /api/v1/auth/token}, it requires
 * a valid JWT (authenticated user changing their own password).
 */
@RestController
@RequestMapping("/api/v1/auth/password")
public class PasswordApiController {

    private final UserService userService;

    public PasswordApiController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Self-service password change for the authenticated user.
     *
     * <p>Per RBI IT Governance §8.2 and Finacle USER_PWDCHG:
     * <ol>
     *   <li>Verify current password (prevents unauthorized change if session is hijacked)</li>
     *   <li>Validate new password complexity (upper+lower+digit+special, min 8)</li>
     *   <li>Check new password is not in last 3 history entries</li>
     *   <li>Verify newPassword == confirmPassword</li>
     *   <li>Update password hash, expiry date, and history</li>
     *   <li>Audit log the change event</li>
     * </ol>
     *
     * <p>On success, the response instructs the BFF to clear the session
     * and redirect to login. The current JWT tokens remain technically valid
     * until expiry, but the BFF should discard them immediately per CBS
     * security policy: a session authenticated with old credentials must
     * not continue after a password change.
     */
    @PostMapping("/change")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<PasswordChangeResponse>>
            changePassword(
                    @Valid @RequestBody PasswordChangeRequest req) {
        String username = SecurityUtil.getCurrentUsername();

        userService.changeSelfServicePassword(
                username,
                req.currentPassword(),
                req.newPassword(),
                req.confirmPassword());

        return ResponseEntity.ok(ApiResponse.success(
                new PasswordChangeResponse(
                        true,
                        "Password changed successfully. "
                                + "Please sign in again with "
                                + "your new password."),
                "Password changed — re-authentication required"));
    }

    // === Request DTOs ===

    public record PasswordChangeRequest(
            @NotBlank(message = "Current password is required")
            String currentPassword,
            @NotBlank(message = "New password is required")
            String newPassword,
            @NotBlank(message = "Password confirmation is required")
            String confirmPassword) {}

    // === Response DTOs ===

    /**
     * Password change response per Finacle USER_PWDCHG.
     * {@code requireReAuth} is always true — the BFF must clear
     * the session and redirect to login after a successful change.
     */
    public record PasswordChangeResponse(
            boolean requireReAuth,
            String message) {}
}

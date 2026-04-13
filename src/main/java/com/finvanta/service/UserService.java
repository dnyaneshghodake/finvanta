package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.enums.UserRole;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.BranchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS User Service per Finacle USER_MASTER / Temenos USER.
 *
 * Encapsulates all user lifecycle business rules:
 * - User creation with duplicate check, password complexity, branch validation
 * - Account activation/deactivation with audit
 * - Account unlock with audit
 * - Password reset with history reuse check
 *
 * Per RBI IT Governance Direction 2023:
 * - Password must meet complexity requirements (upper+lower+digit+special, min 8)
 * - Password history: last 3 passwords cannot be reused
 * - All user lifecycle events must be audited
 * - Account lockout after 5 consecutive failed login attempts
 *
 * Per Finacle USER_MASTER: all user operations go through a service
 * layer with validation rules — controllers only delegate.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Minimum password length per RBI IT Governance */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Password complexity regex per RBI IT Governance Direction 2023 Section 8.2.
     * Requires at least: 1 uppercase, 1 lowercase, 1 digit, 1 special character.
     */
    private static final String PASSWORD_COMPLEXITY_REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()\\-_=+])[A-Za-z\\d@$!%*?&#^()\\-_=+]{8,}$";

    private final AppUserRepository userRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(
            AppUserRepository userRepository,
            BranchRepository branchRepository,
            PasswordEncoder passwordEncoder,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.branchRepository = branchRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * Returns all users for the current tenant, ordered by role then username.
     */
    public List<AppUser> listUsers() {
        String tenantId = TenantContext.getCurrentTenant();
        return userRepository.findByTenantIdOrderByRoleAscUsernameAsc(tenantId);
    }

    /**
     * Creates a new CBS user with full validation.
     *
     * Per Finacle USER_MASTER / RBI IT Governance Direction 2023:
     * - Username must be unique within tenant
     * - Password must meet complexity requirements
     * - Branch must exist and belong to the same tenant
     * - All fields validated before persistence
     *
     * @return Created user
     */
    @Transactional
    public AppUser createUser(String username, String password, String fullName,
            String email, String role, Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // CBS Validation: Duplicate username within tenant
        if (userRepository.existsByTenantIdAndUsername(tenantId, username)) {
            throw new BusinessException("DUPLICATE_USERNAME", "Username already exists: " + username);
        }

        // CBS Validation: Password complexity per RBI IT Governance
        validatePasswordComplexity(password);

        // CBS Validation: Branch must exist and belong to tenant
        Branch branch = branchRepository
                .findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found"));

        AppUser user = new AppUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        // CBS: New user creation — no history check needed (first password).
        user.changePassword(passwordEncoder.encode(password));
        user.setFullName(fullName);
        user.setEmail(email);
        user.setRole(UserRole.valueOf(role));
        user.setBranch(branch);
        user.setActive(true);
        user.setLocked(false);
        user.setFailedLoginAttempts(0);
        user.setCreatedBy(currentUser);

        AppUser saved = userRepository.save(user);

        auditService.logEvent(
                "AppUser", saved.getId(), "USER_CREATED", null, username,
                "USER_MANAGEMENT",
                "User created: " + username + " | Role: " + role
                        + " | Branch: " + branch.getBranchCode()
                        + " | By: " + currentUser);

        log.info("User created: username={}, role={}, branch={}, by={}",
                username, role, branch.getBranchCode(), currentUser);

        return saved;
    }

    /**
     * Toggles user active/inactive status.
     * Per Finacle USER_MASTER: soft-delete pattern — users are never physically deleted.
     *
     * @return Updated user
     */
    @Transactional
    public AppUser toggleActive(Long userId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        AppUser user = userRepository
                .findById(userId)
                .filter(u -> u.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));

        boolean newState = !user.isActive();
        user.setActive(newState);
        user.setUpdatedBy(currentUser);
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(),
                newState ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                String.valueOf(!newState), String.valueOf(newState),
                "USER_MANAGEMENT",
                "User " + (newState ? "activated" : "deactivated") + ": "
                        + user.getUsername() + " by " + currentUser);

        log.info("User {}: username={}, by={}",
                newState ? "activated" : "deactivated", user.getUsername(), currentUser);

        return user;
    }

    /**
     * Unlocks a locked user account.
     * Per RBI IT Governance Direction 2023: ADMIN can manually unlock
     * accounts locked due to failed login attempts.
     *
     * @return Updated user
     */
    @Transactional
    public AppUser unlockUser(Long userId) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        AppUser user = userRepository
                .findById(userId)
                .filter(u -> u.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));

        user.setLocked(false);
        user.setFailedLoginAttempts(0);
        user.setLockoutTime(null);
        user.setUpdatedBy(currentUser);
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(), "USER_UNLOCKED",
                "LOCKED", "UNLOCKED",
                "USER_MANAGEMENT",
                "User unlocked: " + user.getUsername() + " by " + currentUser);

        log.info("User unlocked: username={}, by={}", user.getUsername(), currentUser);

        return user;
    }

    /**
     * Resets a user's password with CBS-grade validation.
     *
     * Per RBI IT Governance Direction 2023 §8.2:
     * - New password must meet complexity requirements
     * - Cannot reuse any of the last 3 passwords
     * - Password expiry is reset to +90 days
     * - Change is audited
     *
     * @return Updated user
     */
    @Transactional
    public AppUser resetPassword(Long userId, String newPassword) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        // CBS Validation: Password complexity
        validatePasswordComplexity(newPassword);

        AppUser user = userRepository
                .findById(userId)
                .filter(u -> u.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found"));

        // CBS: Check password reuse per RBI IT Governance Direction 2023 §8.2.
        if (user.isPasswordInHistory(newPassword, passwordEncoder)) {
            throw new BusinessException("PASSWORD_REUSED",
                    "Cannot reuse any of the last 3 passwords per RBI IT Governance policy. "
                            + "Please choose a different password.");
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        user.setUpdatedBy(currentUser);
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(), "PASSWORD_RESET", null, null,
                "USER_MANAGEMENT",
                "Password reset for: " + user.getUsername() + " by " + currentUser);

        log.info("Password reset: username={}, by={}", user.getUsername(), currentUser);

        return user;
    }

    /**
     * Self-service password change with full CBS validation.
     *
     * Per RBI IT Governance Direction 2023 §8.2 / Finacle USER_MASTER:
     * 1. Current password must be verified (prevents unauthorized change)
     * 2. New password must meet complexity requirements
     * 3. New password and confirmation must match
     * 4. New password cannot match any of the last 3 passwords (history check)
     * 5. Password expiry date is reset to +90 days
     * 6. Change is audited
     *
     * NOTE: Session invalidation after password change is handled by the
     * controller (HTTP-layer concern). This method only handles the business
     * logic and persistence.
     *
     * @param username        The authenticated user's username
     * @param currentPassword The user's current password (plaintext for verification)
     * @param newPassword     The new password (plaintext, will be encoded)
     * @param confirmPassword Confirmation of the new password
     * @return Updated user
     * @throws BusinessException on validation failure
     */
    @Transactional
    public AppUser changeSelfServicePassword(String username, String currentPassword,
            String newPassword, String confirmPassword) {
        String tenantId = TenantContext.getCurrentTenant();

        // CBS Validation: Input completeness
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException("EMPTY_PASSWORD", "New password cannot be empty.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("PASSWORD_MISMATCH",
                    "New password and confirmation do not match.");
        }

        // CBS Validation: Password complexity per RBI IT Governance
        validatePasswordComplexity(newPassword);

        AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND",
                        "User not found: " + username));

        // CBS Validation: Verify current password (prevents unauthorized change)
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException("WRONG_PASSWORD", "Current password is incorrect.");
        }

        // CBS Validation: Password reuse check per RBI IT Governance Direction 2023 §8.2.
        if (user.isPasswordInHistory(newPassword, passwordEncoder)) {
            throw new BusinessException("PASSWORD_REUSE",
                    "Cannot reuse any of your last 3 passwords per RBI IT Governance policy.");
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

        return user;
    }

    /**
     * Validates password complexity per RBI IT Governance Direction 2023 §8.2.
     * Centralized validation used by createUser, resetPassword, and changeSelfServicePassword.
     *
     * @throws BusinessException if password does not meet requirements
     */
    private void validatePasswordComplexity(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must be at least " + MIN_PASSWORD_LENGTH
                            + " characters per RBI IT Governance");
        }
        if (!password.matches(PASSWORD_COMPLEXITY_REGEX)) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must contain uppercase, lowercase, digit, "
                            + "and special character per RBI policy");
        }
    }
}

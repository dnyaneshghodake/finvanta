package com.finvanta.domain.entity;

import com.finvanta.domain.enums.UserRole;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS User Entity per Finacle USER_MASTER / Temenos USER.
 *
 * Per RBI IT Governance Direction 2023:
 * - Password rotation every 90 days (configurable)
 * - Account lockout after 5 consecutive failed login attempts
 * - Auto-unlock after 30 minutes (or manual unlock by ADMIN)
 * - Login history tracked for audit (last login IP, timestamp)
 * - Password history: last 3 passwords cannot be reused
 */
@Entity
@Table(
        name = "app_users",
        indexes = {@Index(name = "idx_user_tenant_username", columnList = "tenant_id, username", unique = true)})
@Getter
@Setter
@NoArgsConstructor
public class AppUser extends BaseEntity {

    /** Maximum consecutive failed login attempts before lockout */
    public static final int MAX_FAILED_ATTEMPTS = 5;
    /** Auto-unlock duration in minutes after lockout */
    public static final int LOCKOUT_DURATION_MINUTES = 30;
    /** Password rotation period in days per RBI IT Governance */
    public static final int PASSWORD_EXPIRY_DAYS = 90;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "email", length = 200)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    // === Password Expiry (RBI IT Governance Direction 2023) ===

    /**
     * Date when the current password was last changed.
     * Used to enforce 90-day password rotation.
     * Set on user creation and every password change/reset.
     */
    @Column(name = "last_password_change")
    private LocalDate lastPasswordChange;

    /**
     * Date when the password expires (lastPasswordChange + 90 days).
     * After this date, the user must change their password on next login.
     * Null = no expiry (for system/service accounts).
     */
    @Column(name = "password_expiry_date")
    private LocalDate passwordExpiryDate;

    /**
     * Password history — stores hashes of last 3 passwords (pipe-delimited).
     * Per RBI IT Governance: users cannot reuse recent passwords.
     * Format: "hash1|hash2|hash3" (most recent first).
     */
    @Column(name = "password_history", length = 1000)
    private String passwordHistory;

    // === Account Lockout (RBI IT Governance Direction 2023) ===

    /**
     * Timestamp when the account was locked due to failed login attempts.
     * Used for auto-unlock: if lockoutTime + LOCKOUT_DURATION_MINUTES < now, auto-unlock.
     * Null = not locked (or manually unlocked).
     */
    @Column(name = "lockout_time")
    private LocalDateTime lockoutTime;

    // === Login Audit (RBI IT Governance Direction 2023) ===

    /** Timestamp of last successful login */
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** IP address of last successful login */
    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    /**
     * Last activity date — tracks the most recent user-initiated action.
     * Per RBI IT Governance Direction 2023 §8.3: user accounts with no activity
     * for 90+ days must be automatically locked (dormant user lockout).
     *
     * Updated on: successful login, any financial transaction initiation,
     * password change, MFA verification. NOT updated on system-generated
     * operations (EOD batch running under SYSTEM user).
     *
     * Distinct from lastLoginAt: a user who logs in but performs no transactions
     * still has lastLoginAt updated. lastActivityDate tracks actual CBS operations.
     * For dormancy purposes, lastLoginAt is the primary indicator — if the user
     * hasn't even logged in for 90 days, the account is clearly dormant.
     *
     * Null = never active (newly created account, or pre-existing account
     * before this field was added). Treated as dormant if account is > 90 days old.
     */
    @Column(name = "last_activity_date")
    private LocalDate lastActivityDate;

    /** Dormant user lockout period in days per RBI IT Governance */
    public static final int USER_DORMANCY_DAYS = 90;

    // === Helpers ===

    /** Returns true if password has expired (past expiry date) */
    public boolean isPasswordExpired() {
        if (passwordExpiryDate == null) return false;
        return LocalDate.now().isAfter(passwordExpiryDate);
    }

    /**
     * Returns true if account is locked but eligible for auto-unlock.
     * Per RBI: auto-unlock after LOCKOUT_DURATION_MINUTES (default 30 min).
     */
    public boolean isAutoUnlockEligible() {
        if (!locked || lockoutTime == null) return false;
        return LocalDateTime.now().isAfter(lockoutTime.plusMinutes(LOCKOUT_DURATION_MINUTES));
    }

    /**
     * Increments failed login attempts and locks account if threshold reached.
     * Returns true if account was just locked.
     */
    public boolean recordFailedLogin() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
            this.locked = true;
            this.lockoutTime = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /** Resets failed attempts and unlocks account (on successful login or admin unlock) */
    public void resetLoginAttempts() {
        this.failedLoginAttempts = 0;
        this.locked = false;
        this.lockoutTime = null;
    }

    /** Records successful login metadata and updates activity tracking */
    public void recordSuccessfulLogin(String ipAddress) {
        this.lastLoginAt = LocalDateTime.now();
        this.lastLoginIp = ipAddress;
        this.lastActivityDate = LocalDate.now();
        resetLoginAttempts();
    }

    /**
     * Returns true if this user account is dormant (no activity for 90+ days).
     * Per RBI IT Governance Direction 2023 §8.3: dormant user accounts must be
     * automatically locked during EOD batch processing.
     *
     * Uses lastActivityDate as primary indicator. Falls back to lastLoginAt
     * for accounts created before lastActivityDate was added. Falls back to
     * createdAt for accounts that have never logged in.
     *
     * @param businessDate CBS business date (not system date) for comparison
     * @return true if account has been inactive for >= USER_DORMANCY_DAYS
     */
    public boolean isDormantUser(LocalDate businessDate) {
        if (!active || locked) return false; // Already inactive/locked
        LocalDate lastActive = lastActivityDate;
        if (lastActive == null && lastLoginAt != null) {
            lastActive = lastLoginAt.toLocalDate();
        }
        if (lastActive == null && getCreatedAt() != null) {
            lastActive = getCreatedAt().toLocalDate();
        }
        if (lastActive == null) return false; // Cannot determine — skip
        return lastActive.plusDays(USER_DORMANCY_DAYS).isBefore(businessDate);
    }

    // === MFA / Two-Factor Authentication (RBI IT Governance Direction 2023) ===

    /**
     * TOTP (Time-based One-Time Password) secret for MFA.
     * Per RBI IT Governance Direction 2023 Section 8.4: privileged users (ADMIN)
     * must use multi-factor authentication. TOTP is the most widely supported
     * MFA method (Google Authenticator, Microsoft Authenticator, Authy).
     *
     * The secret is Base32-encoded and used to generate 6-digit OTPs that change
     * every 30 seconds. Null = MFA not enrolled for this user.
     *
     * Encrypted at rest using AES-256-GCM via {@link com.finvanta.config.MfaSecretEncryptor}.
     * A compromised database dump with plaintext secrets would allow an attacker
     * to generate valid MFA codes for every enrolled user, completely defeating MFA.
     * Per RBI IT Governance Direction 2023: MFA secrets are authentication credentials
     * equivalent to password hashes and require PII-level encryption.
     *
     * Key management:
     *   DEV: Default key in mfa.encryption.key property (deterministic for H2)
     *   PROD: Override via environment variable or secrets manager (AWS KMS / Vault)
     *
     * Column length 200 accommodates Base64(IV[12] + ciphertext + tag[16]) overhead.
     */
    @Convert(converter = com.finvanta.config.MfaSecretEncryptor.class)
    @Column(name = "mfa_secret", length = 200)
    private String mfaSecret;

    /**
     * Whether MFA is enabled for this user.
     * Per RBI: mandatory for ADMIN, optional for MAKER/CHECKER.
     * When enabled, login requires both password AND TOTP code.
     */
    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    /**
     * MFA enrollment date — when the user first set up their authenticator.
     */
    @Column(name = "mfa_enrolled_date")
    private LocalDate mfaEnrolledDate;

    /**
     * Last successfully verified TOTP time step.
     * Per RFC 6238 §5.2: implementations SHOULD track the last successful time step
     * and reject any code for a step <= the last verified step. This prevents replay
     * attacks where an intercepted TOTP code is reused within the ±1 tolerance window.
     *
     * Stored as the raw time step value (System.currentTimeMillis() / 1000 / 30).
     * Null = no prior verification (first login after enrollment).
     */
    @Column(name = "last_totp_time_step")
    private Long lastTotpTimeStep;

    /** Returns true if MFA is required but not yet enrolled */
    public boolean isMfaEnrollmentRequired() {
        return mfaEnabled && (mfaSecret == null || mfaSecret.isBlank());
    }

    /**
     * Checks if a plaintext password matches the current password or any of the
     * last 3 passwords in history. Uses PasswordEncoder.matches() which correctly
     * handles BCrypt's random salt (same plaintext → different hashes).
     *
     * Per RBI IT Governance Direction 2023 §8.2: users cannot reuse recent passwords.
     * Checks current passwordHash + up to 3 entries in passwordHistory (pipe-delimited).
     *
     * @param rawPassword The plaintext password to check against history
     * @param encoder     The password encoder (BCrypt) to use for matching
     * @return true if the password matches the current or any of the last 3 passwords
     */
    public boolean isPasswordInHistory(String rawPassword,
            org.springframework.security.crypto.password.PasswordEncoder encoder) {
        // Check against current password
        if (this.passwordHash != null && encoder.matches(rawPassword, this.passwordHash)) {
            return true;
        }
        // Check against password history
        if (this.passwordHistory != null && !this.passwordHistory.isBlank()) {
            String[] historyHashes = this.passwordHistory.split("\\|");
            for (String historyHash : historyHashes) {
                if (historyHash != null && !historyHash.isBlank()
                        && encoder.matches(rawPassword, historyHash)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sets password with expiry and history tracking.
     * Per RBI IT Governance Direction 2023 §8.2:
     * - Password rotation every 90 days
     * - Last 3 passwords cannot be reused (history stored)
     *
     * IMPORTANT: Callers MUST check password reuse via {@link #isPasswordInHistory}
     * BEFORE calling this method. This method only manages the history storage
     * and expiry tracking — it does not validate reuse because it receives an
     * already-encoded hash (cannot reverse BCrypt to check against history).
     */
    public void changePassword(String newPasswordHash) {
        // Add current password to history (keep last 3)
        if (this.passwordHash != null) {
            String history = this.passwordHash;
            if (this.passwordHistory != null && !this.passwordHistory.isBlank()) {
                String[] parts = this.passwordHistory.split("\\|");
                history = this.passwordHash + "|" + (parts.length > 0 ? parts[0] : "");
                if (parts.length > 1) {
                    history += "|" + parts[1];
                }
            }
            this.passwordHistory = history;
        }
        this.passwordHash = newPasswordHash;
        this.lastPasswordChange = LocalDate.now();
        this.passwordExpiryDate = LocalDate.now().plusDays(PASSWORD_EXPIRY_DAYS);
    }
}

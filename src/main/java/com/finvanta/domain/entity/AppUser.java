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

    /** Records successful login metadata */
    public void recordSuccessfulLogin(String ipAddress) {
        this.lastLoginAt = LocalDateTime.now();
        this.lastLoginIp = ipAddress;
        resetLoginAttempts();
    }

    /** Sets password with expiry and history tracking */
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

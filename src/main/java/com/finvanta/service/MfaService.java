package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.enums.UserRole;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.security.SecureRandom;
import java.time.LocalDate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS MFA (Multi-Factor Authentication) Service per RBI IT Governance Direction 2023 Section 8.4.
 *
 * Implements TOTP (Time-based One-Time Password) per RFC 6238:
 * - 160-bit secret (20 bytes, Base32-encoded to 32 chars)
 * - 6-digit OTP, 30-second time step
 * - HMAC-SHA1 as per RFC 6238 / RFC 4226
 * - +/-1 time step tolerance (clock skew window of +/-30 seconds)
 *
 * Per Finacle USER_MASTER MFA / Temenos USER.MFA:
 *   ADMIN users: MFA mandatory per RBI IT Governance Direction 2023
 *   MAKER/CHECKER: MFA optional (can be enabled by ADMIN)
 *   AUDITOR: MFA optional (read-only role)
 *
 * Security:
 * - Secret stored encrypted via MfaSecretEncryptor (AES-256-GCM)
 * - All MFA state changes audited via AuditService
 * - Brute-force protection via existing account lockout (5 failed attempts)
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private static final int SECRET_LENGTH_BYTES = 20;
    private static final int TIME_STEP_SECONDS = 30;
    private static final int OTP_DIGITS = 6;
    private static final int OTP_MODULUS = 1_000_000;
    private static final int TOLERANCE_STEPS = 1;
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final AppUserRepository userRepository;
    private final AuditService auditService;

    public MfaService(AppUserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** Enable MFA requirement for a user (ADMIN only). */
    @Transactional
    public boolean enableMfa(String username) {
        String tenantId = TenantContext.getCurrentTenant();
        AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + username));

        if (user.isMfaEnabled()) {
            return false;
        }

        user.setMfaEnabled(true);
        user.setUpdatedBy(SecurityUtil.getCurrentUsername());
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(), "MFA_ENABLED", "mfa_enabled=false", "mfa_enabled=true",
                "USER_MANAGEMENT",
                "MFA enabled for user: " + username + " | By: " + SecurityUtil.getCurrentUsername());

        log.info("MFA enabled for user: {} by: {}", username, SecurityUtil.getCurrentUsername());
        return true;
    }

    /** Generate TOTP secret for MFA enrollment. Returns Base32 secret for QR code. */
    @Transactional
    public String enrollMfa(String username) {
        String tenantId = TenantContext.getCurrentTenant();
        AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + username));

        if (!user.isMfaEnabled()) {
            throw new BusinessException("MFA_NOT_ENABLED",
                    "MFA is not enabled for user: " + username + ". Enable MFA first.");
        }
        if (user.getMfaSecret() != null && !user.getMfaSecret().isBlank()) {
            throw new BusinessException("MFA_ALREADY_ENROLLED",
                    "MFA already enrolled for user: " + username + ". Disable and re-enable to re-enroll.");
        }

        byte[] secretBytes = new byte[SECRET_LENGTH_BYTES];
        new SecureRandom().nextBytes(secretBytes);
        String base32Secret = encodeBase32(secretBytes);

        user.setMfaSecret(base32Secret);
        user.setUpdatedBy(SecurityUtil.getCurrentUsername());
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(), "MFA_SECRET_GENERATED", null, "secret_generated",
                "USER_MANAGEMENT",
                "MFA secret generated for enrollment: " + username
                        + " | By: " + SecurityUtil.getCurrentUsername());

        log.info("MFA secret generated for user: {} (pending verification)", username);
        return base32Secret;
    }

    /**
     * Build otpauth:// URI for QR code generation per RFC 6238 key URI format.
     *
     * Per Google Authenticator Key URI Format spec:
     * - The label (issuer:account) in the path segment MUST be percent-encoded
     * - The issuer query parameter MUST also be percent-encoded
     * - Spaces, colons, and special characters in username must be encoded
     *
     * Without encoding, spaces in "Finvanta CBS" and special chars in usernames
     * break QR code parsing on some authenticator apps (especially non-Google ones).
     */
    public String buildOtpAuthUri(String username, String base32Secret) {
        String issuer = "Finvanta CBS";
        String encodedIssuer = issuer.replace(" ", "%20");
        String encodedUsername = username.replace(" ", "%20")
                .replace("@", "%40")
                .replace(":", "%3A");
        return "otpauth://totp/" + encodedIssuer + ":" + encodedUsername
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                + "&digits=" + OTP_DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    /** Verify TOTP code and activate MFA enrollment. */
    @Transactional
    public boolean verifyAndActivateMfa(String username, String totpCode) {
        String tenantId = TenantContext.getCurrentTenant();
        AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + username));

        if (user.getMfaSecret() == null || user.getMfaSecret().isBlank()) {
            throw new BusinessException("MFA_NOT_ENROLLED", "MFA secret not generated. Call enrollMfa first.");
        }

        if (!verifyTotp(user.getMfaSecret(), totpCode)) {
            log.warn("MFA verification failed for user: {} (invalid TOTP code)", username);
            auditService.logEvent(
                    "AppUser", user.getId(), "MFA_VERIFICATION_FAILED", null, "invalid_code",
                    "USER_MANAGEMENT", "MFA verification failed for user: " + username);
            return false;
        }

        user.setMfaEnrolledDate(LocalDate.now());
        user.setUpdatedBy(SecurityUtil.getCurrentUsername());
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(), "MFA_ENROLLED", null, "enrolled",
                "USER_MANAGEMENT",
                "MFA enrollment verified and activated for user: " + username
                        + " | By: " + SecurityUtil.getCurrentUsername());

        log.info("MFA enrollment activated for user: {}", username);
        return true;
    }

    /**
     * Verify TOTP code for an already-enrolled user (called during login).
     * Per RFC 6238 §5.2: tracks last verified time step to prevent replay attacks.
     */
    @Transactional
    public boolean verifyLoginTotp(String username, String totpCode) {
        String tenantId = TenantContext.getCurrentTenant();
        AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + username));

        if (!user.isMfaEnabled() || user.getMfaSecret() == null) {
            return true;
        }

        // CBS: Verify TOTP with replay protection per RFC 6238 §5.2.
        // Returns the matched time step, or -1 if verification failed.
        long matchedStep = verifyTotpWithReplayProtection(
                user.getMfaSecret(), totpCode, user.getLastTotpTimeStep());

        if (matchedStep < 0) {
            return false;
        }

        // Record the successful time step to prevent replay of the same code
        user.setLastTotpTimeStep(matchedStep);
        user.setUpdatedBy("SYSTEM_AUTH");
        userRepository.save(user);
        return true;
    }

    /** Disable MFA for a user. Per RBI: ADMIN users cannot have MFA disabled. */
    @Transactional
    public void disableMfa(String username, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        AppUser user = userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User not found: " + username));

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED",
                    "Reason is mandatory for MFA disable per RBI audit norms");
        }
        if (user.getRole() == UserRole.ADMIN) {
            throw new BusinessException("MFA_MANDATORY_FOR_ADMIN",
                    "Cannot disable MFA for ADMIN users per RBI IT Governance Direction 2023 Section 8.4");
        }

        String previousState = "mfa_enabled=" + user.isMfaEnabled()
                + ", enrolled=" + (user.getMfaEnrolledDate() != null);

        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaEnrolledDate(null);
        user.setUpdatedBy(SecurityUtil.getCurrentUsername());
        userRepository.save(user);

        auditService.logEvent(
                "AppUser", user.getId(), "MFA_DISABLED", previousState, "mfa_disabled",
                "USER_MANAGEMENT",
                "MFA disabled for user: " + username + " | Reason: " + reason
                        + " | By: " + SecurityUtil.getCurrentUsername());

        log.info("MFA disabled for user: {} reason: {}", username, reason);
    }

    // === TOTP Algorithm per RFC 6238 / RFC 4226 ===

    /**
     * Verify TOTP without replay protection (used for enrollment verification only).
     * Enrollment is a one-time operation — replay protection is not needed.
     */
    private boolean verifyTotp(String base32Secret, String code) {
        return verifyTotpWithReplayProtection(base32Secret, code, null) >= 0;
    }

    /**
     * Verify TOTP with replay protection per RFC 6238 §5.2.
     *
     * @param base32Secret The TOTP secret (Base32-encoded)
     * @param code         The 6-digit code to verify
     * @param lastStep     The last successfully verified time step (null = no prior verification)
     * @return The matched time step (>= 0) if valid, or -1 if invalid/replayed
     */
    private long verifyTotpWithReplayProtection(String base32Secret, String code, Long lastStep) {
        if (code == null || code.length() != OTP_DIGITS) {
            return -1;
        }
        try {
            int providedCode = Integer.parseInt(code);
            byte[] secretBytes = decodeBase32(base32Secret);
            long currentTimeStep = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;

            for (int i = -TOLERANCE_STEPS; i <= TOLERANCE_STEPS; i++) {
                long candidateStep = currentTimeStep + i;

                // RFC 6238 §5.2: Reject codes for time steps <= the last verified step.
                // This prevents an intercepted code from being replayed within the
                // ±1 tolerance window (90-second total window).
                if (lastStep != null && candidateStep <= lastStep) {
                    continue; // Skip already-used time steps
                }

                int expectedCode = generateTotp(secretBytes, candidateStep);
                if (expectedCode == providedCode) {
                    return candidateStep; // Return the matched step for recording
                }
            }
            return -1;
        } catch (NumberFormatException e) {
            return -1;
        } catch (Exception e) {
            log.error("TOTP verification error: {}", e.getMessage());
            return -1;
        }
    }

    private int generateTotp(byte[] secret, long timeStep) throws Exception {
        byte[] timeBytes = new byte[8];
        long value = timeStep;
        for (int i = 7; i >= 0; i--) {
            timeBytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] hash = mac.doFinal(timeBytes);

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        return binary % OTP_MODULUS;
    }

    // === Base32 Encoding/Decoding per RFC 4648 ===

    private String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_CHARS.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_CHARS.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    private byte[] decodeBase32(String encoded) {
        int buffer = 0;
        int bitsLeft = 0;
        byte[] result = new byte[encoded.length() * 5 / 8];
        int index = 0;
        for (char c : encoded.toCharArray()) {
            int val = BASE32_CHARS.indexOf(Character.toUpperCase(c));
            if (val < 0) continue;
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return result;
    }
}

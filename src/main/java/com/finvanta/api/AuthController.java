package com.finvanta.api;

import com.finvanta.audit.AuditService;
import com.finvanta.config.JwtTokenService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.entity.RevokedRefreshToken;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.RevokedRefreshTokenRepository;
import com.finvanta.service.MfaService;
import com.finvanta.service.SessionContextService;
import com.finvanta.service.auth.RefreshTokenRotationService;
import com.finvanta.util.MfaRequiredException;
import com.finvanta.util.TenantContext;

import io.jsonwebtoken.Claims;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * CBS API Authentication Controller per Finacle Connect / Temenos IRIS.
 *
 * Issues JWT tokens for stateless API authentication.
 * This is the ONLY endpoint that accepts username/password for API clients.
 * All subsequent API calls use the JWT token in the Authorization header.
 *
 * Endpoints:
 *   POST /api/v1/auth/token   — Authenticate and get access + refresh tokens
 *   POST /api/v1/auth/refresh — Exchange refresh token for new access token
 *
 * Per RBI IT Governance Direction 2023 §8.3:
 * - Credentials validated against app_users (same as UI login)
 * - Locked/inactive/expired accounts rejected
 * - Failed login attempts tracked (same counter as UI)
 * - Tenant context from X-Tenant-Id header (required)
 *
 * CBS SECURITY: This endpoint is NOT behind JWT auth (it issues tokens).
 * It is rate-limited and audited. Brute-force protection via account lockout.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log =
            LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenService jwtTokenService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RevokedRefreshTokenRepository revokedRefreshTokenRepository;
    private final RefreshTokenRotationService refreshTokenRotationService;
    private final AuditService auditService;
    private final MfaService mfaService;
    private final SessionContextService sessionContextService;

    public AuthController(
            JwtTokenService jwtTokenService,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RevokedRefreshTokenRepository revokedRefreshTokenRepository,
            RefreshTokenRotationService refreshTokenRotationService,
            AuditService auditService,
            MfaService mfaService,
            SessionContextService sessionContextService) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.revokedRefreshTokenRepository = revokedRefreshTokenRepository;
        this.refreshTokenRotationService = refreshTokenRotationService;
        this.auditService = auditService;
        this.mfaService = mfaService;
        this.sessionContextService = sessionContextService;
    }

    /**
     * Authenticate user and issue JWT tokens.
     * Per Finacle Connect: validates credentials against USER_MASTER,
     * checks account status, and returns access + refresh token pair.
     */
    @PostMapping("/token")
    @Transactional
    public ResponseEntity<ApiResponse<LoginSessionContext>>
            authenticate(
                    @Valid @RequestBody TokenRequest req) {
        String tenantId = TenantContext.getCurrentTenant();

        AppUser user = userRepository
                .findByTenantIdAndUsername(tenantId,
                        req.username())
                .orElse(null);

        if (user == null) {
            log.warn("API auth failed: user not found: {}",
                    req.username());
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "AUTH_FAILED",
                            "Invalid credentials"));
        }

        // CBS: Check account status before password
        if (!user.isActive()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "ACCOUNT_DISABLED",
                            "Account is disabled"));
        }
        if (user.isLocked()
                && !user.isAutoUnlockEligible()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "ACCOUNT_LOCKED",
                            "Account locked. Try after "
                                    + AppUser
                                    .LOCKOUT_DURATION_MINUTES
                                    + " minutes"));
        }

        // CBS: Auto-unlock if eligible
        if (user.isLocked()
                && user.isAutoUnlockEligible()) {
            user.resetLoginAttempts();
            userRepository.save(user);
        }

        // CBS: Validate password
        if (!passwordEncoder.matches(
                req.password(), user.getPasswordHash())) {
            boolean locked = user.recordFailedLogin();
            userRepository.save(user);
            log.warn("API auth failed: bad password: "
                    + "user={}, attempts={}, locked={}",
                    req.username(),
                    user.getFailedLoginAttempts(),
                    locked);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "AUTH_FAILED",
                            "Invalid credentials"));
        }

        // CBS: Check password expiry
        if (user.isPasswordExpired()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "PASSWORD_EXPIRED",
                            "Password expired. Change via "
                                    + "UI before API access"));
        }

        // CBS MFA gate per RBI IT Governance Direction 2023 §8.3: if the
        // user has MFA enabled, password success alone is NOT enough. We
        // return 428 MFA_REQUIRED with a signed challenge token so the
        // Next.js BFF can prompt for a TOTP code and complete login via
        // POST /api/v1/auth/mfa/verify. No access/refresh token is issued
        // at this stage -- keeping step-up resistant to password-only theft.
        if (user.isMfaEnabled() && user.getMfaSecret() != null) {
            String challenge = jwtTokenService.generateMfaChallengeToken(
                    user.getUsername(), tenantId);
            log.info("API auth pending MFA step-up: user={}",
                    user.getUsername());
            throw new MfaRequiredException(
                    challenge, "TOTP",
                    "MFA step-up required to complete sign-in");
        }

        return ResponseEntity.ok(ApiResponse.success(
                issueTokens(user, tenantId, "PASSWORD")));
    }

    /**
     * Complete MFA step-up login by exchanging the 428 challenge token plus a
     * valid TOTP code for access + refresh tokens.
     *
     * <p>Per RBI IT Governance Direction 2023 §8.3 and NPCI step-up:
     * the challenge token is single-use (persisted in the refresh-token
     * rotation denylist by {@code jti} on success) and expires in 5 minutes.
     * TOTP validation uses the same replay-protected path as the JSP UI
     * so an OTP consumed on one channel cannot be replayed on another.
     */
    @PostMapping("/mfa/verify")
    @Transactional
    public ResponseEntity<ApiResponse<LoginSessionContext>>
            mfaVerify(
                    @Valid @RequestBody MfaVerifyRequest req) {
        Claims claims =
                jwtTokenService.validateToken(req.challengeId());
        if (claims == null
                || !jwtTokenService.isMfaChallengeToken(claims)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "INVALID_MFA_CHALLENGE",
                            "MFA challenge invalid or expired. "
                                    + "Please sign in again."));
        }

        String tenantId =
                jwtTokenService.getTenantId(claims);
        String username =
                jwtTokenService.getUsername(claims);
        String challengeJti =
                jwtTokenService.getJti(claims);

        // CBS: challenge tokens MUST carry a jti for single-use enforcement.
        // A null jti means the token was crafted or corrupted — reject early
        // rather than relying on the DB NOT NULL constraint for flow control.
        if (challengeJti == null || challengeJti.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "INVALID_MFA_CHALLENGE",
                            "MFA challenge is malformed. "
                                    + "Please sign in again."));
        }

        // CBS single-use challenge per OWASP ASVS 2.2.7: reject reuse.
        if (refreshTokenRotationService.isAlreadyRevoked(
                        tenantId, challengeJti)) {
            auditService.logEvent(
                    "MFA_CHALLENGE",
                    0L,
                    "MFA_CHALLENGE_REUSED",
                    null,
                    java.util.Map.of(
                            "username", username != null
                                    ? username : "UNKNOWN",
                            "jti", challengeJti),
                    "AUTH",
                    "MFA challenge reuse detected");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "MFA_CHALLENGE_REUSED",
                            "This MFA challenge has already been used."));
        }

        // Tenant context is set by the TenantFilter from X-Tenant-Id;
        // double-check the challenge tenant matches the request tenant
        // so a challenge issued for tenant A cannot be burned in tenant B.
        if (tenantId == null
                || !tenantId.equals(TenantContext.getCurrentTenant())) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "INVALID_MFA_CHALLENGE",
                            "MFA challenge tenant mismatch."));
        }

        AppUser user = userRepository
                .findByTenantIdAndUsername(tenantId, username)
                .orElse(null);
        if (user == null || !user.isActive()
                || user.isLocked()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "ACCOUNT_INVALID",
                            "Account no longer valid"));
        }

        boolean otpValid;
        try {
            otpValid = mfaService.verifyLoginTotp(
                    username, req.otp());
        } catch (RuntimeException ex) {
            log.warn("MFA verify error: user={}, error={}",
                    username, ex.getMessage());
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "MFA_VERIFICATION_FAILED",
                            "Invalid OTP code"));
        }

        if (!otpValid) {
            // CBS: count failed MFA attempts toward account lockout so an
            // attacker who knows the password cannot brute-force OTPs
            // across unlimited 5-minute challenge windows.
            boolean locked = user.recordFailedLogin();
            userRepository.save(user);
            auditService.logEvent(
                    "AppUser", user.getId(),
                    "MFA_VERIFICATION_FAILED", null, "invalid_code",
                    "AUTH",
                    "API MFA step-up failed: " + username);
            if (locked) {
                log.warn("Account locked after MFA failures: user={}",
                        username);
            }
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "MFA_VERIFICATION_FAILED",
                            "Invalid OTP code"));
        }

        // Burn the challenge by placing its jti on the denylist so it
        // cannot be exchanged a second time.
        RevokedRefreshToken burn = new RevokedRefreshToken();
        burn.setTenantId(tenantId);
        burn.setJti(challengeJti);
        burn.setSubject(username);
        burn.setRevokedAt(LocalDateTime.now());
        Instant challengeExp = jwtTokenService.getExpiration(claims);
        burn.setExpiresAt(challengeExp != null
                ? LocalDateTime.ofInstant(challengeExp,
                        ZoneId.systemDefault())
                : LocalDateTime.now().plusMinutes(10));
        // CBS: RevokedRefreshToken.reason is VARCHAR(20); keep value <= 20 chars.
        burn.setReason("MFA_CONSUMED");
        try {
            refreshTokenRotationService.revoke(burn);
        } catch (DataIntegrityViolationException race) {
            log.warn("MFA challenge concurrent consume: jti={}",
                    challengeJti);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "MFA_CHALLENGE_REUSED",
                            "This MFA challenge has already been used."));
        }

        return ResponseEntity.ok(ApiResponse.success(
                issueTokens(user, tenantId, "MFA")));
    }

    /**
     * Shared token issuance + COC assembly path. Centralised to avoid
     * drift between {@link #authenticate(TokenRequest)} and
     * {@link #mfaVerify(MfaVerifyRequest)}.
     *
     * <p>Per Finacle USER_SESSION / Temenos EB.USER.CONTEXT: the login
     * response must establish the full Controlled Operational Context
     * (identity, branch, business day, role/permission matrix, financial
     * authority limits, operational config) in a single round-trip so the
     * Next.js BFF can hydrate its server-side session without N+1 calls.
     *
     * <p>Token issuance (JWT generation + login recording) remains here;
     * COC assembly is delegated to {@link SessionContextService} which
     * runs in a read-only transaction against the domain model.
     */
    private LoginSessionContext issueTokens(AppUser user, String tenantId,
            String flow) {
        String role = user.getRole().name();
        String branchCode = user.getBranch() != null
                ? user.getBranch().getBranchCode() : null;

        String accessToken =
                jwtTokenService.generateAccessToken(
                        user.getUsername(), tenantId,
                        role, branchCode);
        JwtTokenService.RefreshTokenIssue refresh =
                jwtTokenService.generateRefreshToken(
                        user.getUsername(), tenantId);

        user.recordSuccessfulLogin("API");
        userRepository.save(user);

        long expiresAt = jwtTokenService
                .parseToken(accessToken)
                .getExpiration()
                .getTime() / 1000;

        log.info("API token issued: user={}, role={}, branch={}, "
                + "refreshJti={}, flow={}",
                user.getUsername(), role, branchCode,
                refresh.jti(), flow);

        // CBS Tier-1: assemble the full Controlled Operational Context
        // (identity + branch + business day + permissions + limits + config)
        // alongside the tokens so the BFF gets everything in one response.
        return sessionContextService.assemble(
                user, tenantId,
                accessToken, refresh.token(),
                expiresAt, flow);
    }

    /**
     * Refresh access token using a valid refresh token.
     *
     * <p>Per RBI IT Governance Direction 2023 §8.3 and RFC 6749 §10.4, refresh tokens
     * are <b>rotated</b> on every exchange: the presented token is denylisted and a
     * brand new refresh token (new {@code jti}) is issued alongside the new access
     * token. Any subsequent attempt to replay the old token is rejected with
     * {@code REFRESH_TOKEN_REUSED} -- this is the canonical detection signal for
     * a stolen refresh token per OWASP JWT Cheat Sheet.
     *
     * <p>Per RBI: refresh tokens can only obtain new access tokens, never perform
     * financial operations directly. Both tokens remain tenant-scoped.
     *
     * <p><b>CBS atomicity:</b> the denylist check ({@code existsByTenantIdAndJti})
     * and the denylist insert ({@code save(revoked)}) MUST run in the same
     * transaction so that two concurrent refresh requests presenting the same
     * jti see a consistent snapshot. Without {@code @Transactional} each repo
     * call auto-commits and both callers can pass the existence check before
     * either writes -- turning the {@code uq_revoked_tenant_jti} unique
     * constraint into a HTTP 500 instead of a clean {@code REFRESH_TOKEN_REUSED}
     * 401. The {@code DataIntegrityViolationException} catch below is the
     * defence-in-depth safety net for the residual race window.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>>
            refresh(
                    @Valid @RequestBody RefreshRequest req) {
        Claims claims =
                jwtTokenService.validateToken(
                        req.refreshToken());

        if (claims == null) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "INVALID_REFRESH_TOKEN",
                            "Refresh token invalid or "
                                    + "expired"));
        }

        if (!jwtTokenService.isRefreshToken(claims)) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "NOT_REFRESH_TOKEN",
                            "Provided token is not a "
                                    + "refresh token"));
        }

        String tenantId =
                jwtTokenService.getTenantId(claims);
        String username =
                jwtTokenService.getUsername(claims);
        String oldJti = jwtTokenService.getJti(claims);

        // CBS refresh-token rotation: reject replays of an already-consumed refresh
        // token. If we see the same jti twice, the token was stolen and replayed --
        // audit with REFRESH_TOKEN_REUSED so the SOC can pivot on the subject.
        if (oldJti == null || oldJti.isBlank()) {
            // Legacy refresh token issued before jti-rotation was deployed.
            // Reject -- the client must re-authenticate with username/password.
            auditService.logEvent(
                    "REFRESH_TOKEN",
                    0L,
                    "REFRESH_TOKEN_REJECTED",
                    null,
                    java.util.Map.of(
                            "username", username != null ? username : "UNKNOWN",
                            "reason", "LEGACY_NO_JTI"),
                    "AUTH",
                    "Refresh token lacks jti claim -- re-authenticate required");
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "LEGACY_REFRESH_TOKEN",
                            "Refresh token predates rotation policy. "
                                    + "Please re-authenticate via /api/v1/auth/token."));
        }

        if (refreshTokenRotationService
                .isAlreadyRevoked(tenantId, oldJti)) {
            auditService.logEvent(
                    "REFRESH_TOKEN",
                    0L,
                    "REFRESH_TOKEN_REUSED",
                    null,
                    java.util.Map.of(
                            "username", username != null ? username : "UNKNOWN",
                            "jti", oldJti),
                    "AUTH",
                    "Replayed refresh token detected -- possible token theft");
            log.warn("API refresh reuse detected: user={}, jti={}",
                    username, oldJti);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "REFRESH_TOKEN_REUSED",
                            "Refresh token has already been used. "
                                    + "Re-authenticate via /api/v1/auth/token."));
        }

        // Re-fetch user to get current role/branch/status
        AppUser user = userRepository
                .findByTenantIdAndUsername(tenantId,
                        username)
                .orElse(null);

        if (user == null || !user.isActive()
                || user.isLocked()) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "ACCOUNT_INVALID",
                            "Account no longer valid"));
        }

        String role = user.getRole().name();
        String branchCode = user.getBranch() != null
                ? user.getBranch().getBranchCode() : null;

        // CBS rotation: revoke the presented (old) refresh token BEFORE issuing a new
        // one. This narrows the replay window to zero and keeps the denylist and new
        // token issuance in the same transaction so a crash cannot leave the old token
        // live while the new one is already in the client's hands.
        RevokedRefreshToken revoked = new RevokedRefreshToken();
        revoked.setTenantId(tenantId);
        revoked.setJti(oldJti);
        revoked.setSubject(username);
        revoked.setRevokedAt(LocalDateTime.now());
        Instant oldExpiresAt = jwtTokenService.getExpiration(claims);
        revoked.setExpiresAt(oldExpiresAt != null
                ? LocalDateTime.ofInstant(oldExpiresAt, ZoneId.systemDefault())
                : LocalDateTime.now().plusDays(1));
        revoked.setReason("ROTATION");
        // CBS: Invoke the dedicated service so its @Transactional boundary is
        // inside the call, not around this controller method. A concurrent
        // rotation surfaces as DataIntegrityViolationException out of the
        // service boundary, and the catch below runs OUTSIDE any rollback-only
        // transaction scope -- producing a clean HTTP 401 instead of the
        // UnexpectedRollbackException -> HTTP 500 that would result from
        // catching the exception inside the same @Transactional method.
        try {
            refreshTokenRotationService.revoke(revoked);
        } catch (DataIntegrityViolationException dup) {
            // CBS defence-in-depth: the unique constraint uq_revoked_tenant_jti
            // caught a concurrent rotation of the same jti. The other caller has
            // already consumed this refresh token -- treat the current request as
            // a replay per OWASP JWT Cheat Sheet and RFC 6749 §10.4.
            auditService.logEvent(
                    "REFRESH_TOKEN",
                    0L,
                    "REFRESH_TOKEN_REUSED",
                    null,
                    // CBS: Map.of throws NPE on null values -- the two earlier
                    // audit calls in this method (lines 258, 278) already guard
                    // username for the same reason. Preserve parity here so a
                    // crafted refresh token whose subject claim resolves null
                    // cannot turn the intended HTTP 401 REFRESH_TOKEN_REUSED
                    // into an NPE-driven HTTP 500.
                    java.util.Map.of(
                            "username", username != null ? username : "UNKNOWN",
                            "jti", oldJti,
                            "detection", "UNIQUE_CONSTRAINT"),
                    "AUTH",
                    "Concurrent refresh rotation -- unique constraint tripped");
            log.warn("API refresh race detected: user={}, jti={}",
                    username, oldJti);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "REFRESH_TOKEN_REUSED",
                            "Refresh token has already been used. "
                                    + "Re-authenticate via /api/v1/auth/token."));
        }

        String newAccessToken =
                jwtTokenService.generateAccessToken(
                        username, tenantId,
                        role, branchCode);
        JwtTokenService.RefreshTokenIssue newRefresh =
                jwtTokenService.generateRefreshToken(
                        username, tenantId);

        log.info("API token rotated: user={}, oldJti={}, newJti={}",
                username, oldJti, newRefresh.jti());

        return ResponseEntity.ok(ApiResponse.success(
                new TokenResponse(
                        newAccessToken,
                        newRefresh.token(),
                        "Bearer",
                        jwtTokenService
                                .parseToken(newAccessToken)
                                .getExpiration()
                                .getTime()
                                / 1000)));
    }

    // === Request DTOs ===

    public record TokenRequest(
            @NotBlank(message = "Username is required")
            String username,
            @NotBlank(message = "Password is required")
            String password) {}

    public record RefreshRequest(
            @NotBlank(message = "Refresh token is required")
            String refreshToken) {}

    public record MfaVerifyRequest(
            @NotBlank(message = "Challenge id is required")
            String challengeId,
            @NotBlank(message = "OTP code is required")
            String otp) {}

    // === Response DTOs ===

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresAt) {}
}

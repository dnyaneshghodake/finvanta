package com.finvanta.api;

import com.finvanta.config.JwtTokenService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.TenantContext;

import io.jsonwebtoken.Claims;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
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
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log =
            LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenService jwtTokenService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(
            JwtTokenService jwtTokenService,
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticate user and issue JWT tokens.
     * Per Finacle Connect: validates credentials against USER_MASTER,
     * checks account status, and returns access + refresh token pair.
     */
    @PostMapping("/token")
    public ResponseEntity<ApiResponse<TokenResponse>>
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

        // CBS: Generate tokens
        String role = user.getRole().name();
        String branchCode = user.getBranch() != null
                ? user.getBranch().getBranchCode() : null;

        String accessToken =
                jwtTokenService.generateAccessToken(
                        user.getUsername(), tenantId,
                        role, branchCode);
        String refreshToken =
                jwtTokenService.generateRefreshToken(
                        user.getUsername(), tenantId);

        // Record successful auth
        user.recordSuccessfulLogin("API");
        userRepository.save(user);

        log.info("API token issued: user={}, role={}, "
                + "branch={}",
                user.getUsername(), role, branchCode);

        return ResponseEntity.ok(ApiResponse.success(
                new TokenResponse(
                        accessToken, refreshToken,
                        "Bearer",
                        jwtTokenService
                                .parseToken(accessToken)
                                .getExpiration()
                                .getTime()
                                / 1000)));
    }

    /**
     * Refresh access token using a valid refresh token.
     * Per RBI: refresh tokens can only get new access tokens,
     * not perform financial operations directly.
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

        String newAccessToken =
                jwtTokenService.generateAccessToken(
                        username, tenantId,
                        role, branchCode);

        log.info("API token refreshed: user={}", username);

        return ResponseEntity.ok(ApiResponse.success(
                new TokenResponse(
                        newAccessToken,
                        req.refreshToken(),
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

    // === Response DTOs ===

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresAt) {}
}

package com.finvanta.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * CBS JWT Token Service per Finacle Connect / Temenos IRIS.
 *
 * Generates and validates HMAC-SHA256 signed JWT tokens for stateless
 * API authentication. Used by mobile banking, NPCI adapters, Account
 * Aggregator (AA) framework, and third-party fintech integrations.
 *
 * Token Types:
 *   ACCESS  — 15 min, carries username/role/tenant/branch for authorization
 *   REFRESH — 8 hours, carries only username/tenant for token renewal
 *
 * Per RBI IT Governance Direction 2023 §8.3:
 * - Access tokens are short-lived to limit exposure window
 * - Refresh tokens cannot be used for financial operations (type-checked)
 * - All tokens are tenant-scoped (multi-tenant isolation in JWT claims)
 * - Signing key MUST be overridden in production via CBS_JWT_SECRET env var
 *
 * CBS SECURITY: The signing key is the root of trust for all API auth.
 * A compromised key allows forging tokens for any user/role/tenant.
 * In production, use AWS KMS / HashiCorp Vault for key management.
 */
@Service
public class JwtTokenService {

    private static final Logger log =
            LoggerFactory.getLogger(JwtTokenService.class);

    private static final String CLAIM_TENANT = "tenant";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_BRANCH = "branch";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "ACCESS";
    private static final String TYPE_REFRESH = "REFRESH";
    private static final String TYPE_MFA_CHALLENGE = "MFA_CHALLENGE";
    private static final long MFA_CHALLENGE_EXPIRY_MS = 5L * 60L * 1000L;

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;
    private final String issuer;

    public JwtTokenService(
            @Value("${cbs.jwt.secret}") String secret,
            @Value("${cbs.jwt.access-token-expiry-minutes}")
            long accessMinutes,
            @Value("${cbs.jwt.refresh-token-expiry-hours}")
            long refreshHours,
            @Value("${cbs.jwt.issuer}") String issuer) {
        this.signingKey = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = accessMinutes * 60 * 1000;
        this.refreshTokenExpiryMs = refreshHours * 60 * 60 * 1000;
        this.issuer = issuer;
    }

    /**
     * Generate an ACCESS token with full authorization claims.
     * Per Finacle Connect: access tokens carry role and branch
     * for @PreAuthorize and BranchAccessValidator enforcement.
     */
    public String generateAccessToken(
            String username, String tenantId,
            String role, String branchCode) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .claim(CLAIM_TENANT, tenantId)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_BRANCH, branchCode)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessTokenExpiryMs))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generate a REFRESH token with minimal claims.
     * Per RBI: refresh tokens MUST NOT carry role/branch —
     * they can only be exchanged for a new access token,
     * not used directly for financial operations.
     *
     * <p>Per RFC 6749 §10.4 / OWASP JWT Cheat Sheet: every refresh token carries
     * a unique {@code jti} (JWT ID) claim so the authorization server can track
     * and revoke individual tokens (refresh-token rotation denylist). The
     * {@code jti} is the primary key used by
     * {@link com.finvanta.repository.RevokedRefreshTokenRepository} to detect replay.
     */
    public RefreshTokenIssue generateRefreshToken(
            String username, String tenantId) {
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiresAt = new Date(now + refreshTokenExpiryMs);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .id(jti)
                .claim(CLAIM_TENANT, tenantId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .signWith(signingKey)
                .compact();
        return new RefreshTokenIssue(token, jti, expiresAt.toInstant());
    }

    /**
     * Immutable result of {@link #generateRefreshToken(String, String)} carrying
     * the serialized JWT plus its canonical {@code jti} and expiry -- both needed
     * by {@code AuthController.refresh} to persist the consumed token in the
     * rotation denylist.
     */
    public record RefreshTokenIssue(String token, String jti, Instant expiresAt) {}

    /**
     * Parse and validate a JWT token. Returns claims if valid.
     * Throws JwtException on invalid/expired/tampered tokens.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Extract username (subject) from token */
    public String getUsername(Claims claims) {
        return claims.getSubject();
    }

    /** Extract tenant ID from token */
    public String getTenantId(Claims claims) {
        return claims.get(CLAIM_TENANT, String.class);
    }

    /** Extract role from token (ACCESS tokens only) */
    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    /** Extract branch code from token (ACCESS tokens only) */
    public String getBranchCode(Claims claims) {
        return claims.get(CLAIM_BRANCH, String.class);
    }

    /** Check if token is an ACCESS token (vs REFRESH) */
    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(
                claims.get(CLAIM_TYPE, String.class));
    }

    /** Check if token is a REFRESH token */
    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(
                claims.get(CLAIM_TYPE, String.class));
    }

    /**
     * Generate a short-lived MFA step-up challenge token.
     *
     * <p>Per RBI IT Governance Direction 2023 §8.3 / NPCI step-up guidelines:
     * when an API login succeeds on password but the user has MFA enabled,
     * the server returns 428 Precondition Required with this challenge token
     * as the opaque {@code challengeId}. The caller submits it back to
     * {@code POST /api/v1/auth/mfa/verify} along with the TOTP code; the
     * server validates the challenge, verifies the OTP, and only then
     * issues access + refresh tokens.
     *
     * <p>The challenge is a signed JWT (not stored server-side) so it
     * remains horizontally scalable; its 5-minute expiry limits replay,
     * and its {@code jti} is single-use per successful verify.
     */
    public String generateMfaChallengeToken(String username, String tenantId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TENANT, tenantId)
                .claim(CLAIM_TYPE, TYPE_MFA_CHALLENGE)
                .issuedAt(new Date(now))
                .expiration(new Date(now + MFA_CHALLENGE_EXPIRY_MS))
                .signWith(signingKey)
                .compact();
    }

    /** Check if token is an MFA step-up challenge token. */
    public boolean isMfaChallengeToken(Claims claims) {
        return TYPE_MFA_CHALLENGE.equals(
                claims.get(CLAIM_TYPE, String.class));
    }

    /** Extract the {@code jti} (JWT ID) claim -- required by the refresh-token rotation denylist. */
    public String getJti(Claims claims) {
        return claims.getId();
    }

    /** Extract the token expiration as an {@link Instant} -- for denylist persistence. */
    public Instant getExpiration(Claims claims) {
        Date exp = claims.getExpiration();
        return exp != null ? exp.toInstant() : null;
    }

    /**
     * Validate token and return claims, or null if invalid.
     * Does NOT throw — returns null for any validation failure.
     * Used by the JWT filter where we want silent rejection.
     */
    public Claims validateToken(String token) {
        try {
            return parseToken(token);
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}",
                    e.getMessage());
        } catch (Exception e) {
            log.warn("JWT parse error: {}", e.getMessage());
        }
        return null;
    }
}

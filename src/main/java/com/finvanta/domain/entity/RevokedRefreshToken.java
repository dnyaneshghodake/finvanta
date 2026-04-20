package com.finvanta.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

import org.hibernate.annotations.Filter;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Revoked Refresh Token registry per Finacle Connect / Temenos IRIS.
 *
 * <p>Implements the denylist backing for RFC 6749 §10.4 refresh-token rotation:
 * every time a refresh token is exchanged at {@code POST /api/v1/auth/refresh},
 * the consumed token's {@code jti} is persisted here and rejected on any
 * subsequent reuse. This detects replay attacks (stolen refresh tokens) per
 * RBI IT Governance Direction 2023 Section 8.3.
 *
 * <p><b>Why NOT BaseEntity:</b> this is an infrastructure table indexed on
 * {@code (tenantId, jti)} with a narrow write profile -- one row per refresh
 * rotation. Hibernate {@code @Filter} on {@code tenant_id} is applied manually
 * (see {@link #tenantId}) to keep the record visible for tenant-scoped reads,
 * but we skip version/audit columns because denylist rows are never mutated
 * after insert and never participate in financial transactions.
 *
 * <p><b>Retention:</b> {@link #expiresAt} equals the original refresh token's
 * expiry. A cleanup job MAY delete rows where {@code expiresAt < now()} since
 * expired tokens are already rejected by JWT validation; retaining them is
 * harmless but wastes storage. Absence of cleanup is not a security risk.
 */
@Entity
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(
        name = "revoked_refresh_tokens",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_revoked_tenant_jti",
                    columnNames = {"tenant_id", "jti"})
        },
        indexes = {
            @Index(name = "idx_revoked_tenant_user", columnList = "tenant_id, subject"),
            @Index(name = "idx_revoked_expires_at", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class RevokedRefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    /** JWT ID (UUID) of the revoked refresh token. */
    @Column(name = "jti", nullable = false, length = 64)
    private String jti;

    /** Subject (username) of the revoked token -- for forensic / per-user revocation queries. */
    @Column(name = "subject", nullable = false, length = 100)
    private String subject;

    /** Wall-clock time when the token was revoked. */
    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    /**
     * Original refresh token's expiry time. After this instant, the token is
     * already invalid via JWT expiry and the denylist row can be garbage-collected.
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * Why the token was revoked. Values:
     * <ul>
     *   <li>{@code ROTATION}   -- consumed normally at /auth/refresh</li>
     *   <li>{@code REPLAY}     -- denylist hit -> forced revocation of entire family</li>
     *   <li>{@code LOGOUT}     -- explicit user logout</li>
     *   <li>{@code ADMIN_KILL} -- administrator terminated the session</li>
     * </ul>
     */
    @Column(name = "reason", nullable = false, length = 20)
    private String reason;
}

package com.finvanta.repository;

import com.finvanta.domain.entity.RevokedRefreshToken;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Revoked Refresh Token repository per Finacle Connect / Temenos IRIS.
 *
 * <p>Backs the JWT refresh-token rotation denylist. Reads are on the hot path
 * ({@code POST /api/v1/auth/refresh}) so the unique index
 * {@code uq_revoked_tenant_jti} supports an O(1) existence check.
 */
@Repository
public interface RevokedRefreshTokenRepository extends JpaRepository<RevokedRefreshToken, Long> {

    /**
     * Returns {@code true} iff the given {@code (tenantId, jti)} is on the
     * denylist. Used by {@code AuthController.refresh} to reject replays.
     */
    boolean existsByTenantIdAndJti(String tenantId, String jti);

    /**
     * Purges denylist rows whose original refresh token has already expired.
     * These rows are safe to delete because JWT validation already rejects
     * expired tokens. Intended to be called by a scheduled EOD maintenance job.
     *
     * @param cutoff {@code expiresAt < cutoff} are removed
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM RevokedRefreshToken r WHERE r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}

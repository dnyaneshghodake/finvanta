package com.finvanta.service.auth;

import com.finvanta.domain.entity.RevokedRefreshToken;
import com.finvanta.repository.RevokedRefreshTokenRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Refresh-Token Rotation Service per RFC 6749 §10.4 / OWASP JWT Cheat Sheet.
 *
 * <p>Encapsulates the <b>check-then-revoke</b> operation for a refresh token
 * rotation in a single transactional boundary, so the denylist check and the
 * denylist insert see a consistent snapshot and the unique constraint
 * {@code uq_revoked_tenant_jti} catches the residual race window cleanly.
 *
 * <p><b>Why this service exists:</b> when the {@code @Transactional} boundary
 * lives on the controller method instead, a {@link DataIntegrityViolationException}
 * thrown by {@code saveAndFlush} marks the transaction as rollback-only. The
 * controller's catch block returns a {@code ResponseEntity} with HTTP 401, but
 * Spring's {@code TransactionInterceptor} then attempts to commit the
 * rollback-only transaction and throws {@code UnexpectedRollbackException},
 * which the {@code DispatcherServlet} surfaces as HTTP 500. Extracting the
 * write here lets the exception propagate OUT of the transactional boundary
 * so the controller's catch runs outside the rollback-only scope.
 *
 * <p>Per Finacle AUTH_REVOKE / Temenos EB.SIGN.ON.TOKEN: rotation is the only
 * way the denylist grows, so one tight service is the right place for it.
 */
@Service
public class RefreshTokenRotationService {

    private final RevokedRefreshTokenRepository revokedRepo;

    public RefreshTokenRotationService(RevokedRefreshTokenRepository revokedRepo) {
        this.revokedRepo = revokedRepo;
    }

    /**
     * Check whether the presented jti is already on the denylist for the tenant.
     * Read-only transaction so it can be called ahead of the write to emit an
     * explanatory REFRESH_TOKEN_REUSED audit event before the unique constraint
     * even fires.
     *
     * @return {@code true} if the jti has already been consumed (replay).
     */
    @Transactional(readOnly = true)
    public boolean isAlreadyRevoked(String tenantId, String jti) {
        return revokedRepo.existsByTenantIdAndJti(tenantId, jti);
    }

    /**
     * Insert the denylist row for a refresh token that is being rotated.
     *
     * <p>Runs in its own transaction and {@code saveAndFlush}es so the unique
     * constraint fires eagerly instead of on commit. A concurrent rotation of
     * the same {@code (tenantId, jti)} propagates as
     * {@link DataIntegrityViolationException} to the caller; since the caller
     * is the controller (outside this transactional boundary), it is free to
     * translate the exception into a clean HTTP 401 without the rollback-only
     * pitfall.
     *
     * @throws DataIntegrityViolationException on concurrent rotation of the
     *         same jti (unique constraint {@code uq_revoked_tenant_jti}).
     */
    @Transactional
    public void revoke(RevokedRefreshToken revoked) {
        revokedRepo.saveAndFlush(revoked);
    }
}

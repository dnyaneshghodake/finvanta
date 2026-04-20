package com.finvanta.accounting;

import com.finvanta.domain.entity.TenantLedgerState;
import com.finvanta.repository.TenantLedgerStateRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Tenant Ledger State Bootstrap per Finacle LEDGER_STATE.
 *
 * <p>Isolates the one-shot {@code tenant_ledger_state} INSERT into a
 * {@link Propagation#REQUIRES_NEW} boundary so a PK-collision on a concurrent
 * first-posting cannot poison the outer ledger-posting transaction.
 *
 * <p><b>Why this exists:</b> {@code LedgerService.postToLedger} is annotated
 * {@code @Transactional}. If the sentinel INSERT were performed inside that
 * same transaction via {@code repository.saveAndFlush(...)} and a concurrent
 * posting inserted first (PK collision), Spring's repository-proxy
 * {@code TransactionInterceptor} would mark the enclosing transaction
 * {@code rollback-only} BEFORE the {@link DataIntegrityViolationException}
 * surfaces to the caller. Even a clean catch would still doom the outer
 * transaction -- {@code AccountingService.postJournalEntry} would later throw
 * {@code UnexpectedRollbackException} and the entire journal posting +
 * GL balance update would be rolled back. The sentinel pattern would defeat
 * its own purpose on the very first concurrent posting for a new tenant.
 *
 * <p>By carving the INSERT into its own {@code REQUIRES_NEW} boundary, the
 * PK-collision only rolls back THIS nested transaction, leaving the outer
 * posting transaction intact. The caller then re-locks the sentinel (now
 * seeded by the winning thread) and proceeds.
 *
 * <p><b>Why no try/catch inside the method body:</b> if we caught
 * {@link DataIntegrityViolationException} here, the saveAndFlush would have
 * already marked THIS inner REQUIRES_NEW transaction as rollback-only (JPA
 * spec §3.3.2: a failed flush marks the active {@code EntityTransaction}
 * rollback-only). Catching then returning normally causes Spring's
 * {@code TransactionInterceptor} to attempt a commit on the inner TX, which
 * in turn throws {@link org.springframework.transaction.UnexpectedRollbackException}.
 * That exception propagates uncaught to the outer posting transaction and
 * rolls it back -- the very failure this bootstrap was designed to avoid.
 *
 * <p>Instead, we allow the {@code DataIntegrityViolationException} to escape.
 * Spring rolls back the inner REQUIRES_NEW transaction (standard rollback,
 * not an "unexpected" one), re-throws the original DIVE, and the caller
 * ({@code LedgerService.ensureAndLockSentinel}) catches it. The outer
 * posting transaction is never touched. Same pattern as
 * {@code RefreshTokenRotationService} which documents this pitfall.
 *
 * <p>Per Finacle LEDGER_STATE / Temenos EB.LEDGER: tenant provisioning is the
 * preferred place to seed the sentinel, but this bootstrap is the defence
 * against legacy tenants that predate the sentinel table.
 */
@Service
public class TenantLedgerStateBootstrap {

    private static final Logger log =
            LoggerFactory.getLogger(TenantLedgerStateBootstrap.class);

    private final TenantLedgerStateRepository repository;

    public TenantLedgerStateBootstrap(TenantLedgerStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Insert the sentinel row for {@code tenantId} in a <b>separate</b>
     * transaction so a PK collision cannot poison the calling transaction.
     *
     * <p>The caller is expected to invoke this only when
     * {@code findAndLock(tenantId)} returned empty. A concurrent insert by
     * another thread surfaces as {@link DataIntegrityViolationException};
     * the exception is allowed to propagate so Spring rolls back the inner
     * REQUIRES_NEW transaction cleanly. The caller must catch it -- catching
     * here would cause {@code UnexpectedRollbackException} on the inner
     * commit (JPA spec §3.3.2).
     *
     * @param tenantId tenant to seed
     * @throws DataIntegrityViolationException if another thread inserts first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertIfAbsent(String tenantId) {
        repository.saveAndFlush(new TenantLedgerState(tenantId));
        log.debug("Tenant ledger sentinel bootstrapped: tenant={}", tenantId);
    }
}

package com.finvanta.accounting;

import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.GLBranchBalance;
import com.finvanta.repository.GLBranchBalanceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS GL Branch Balance Bootstrap per Finacle GL_BRANCH / Temenos EB.GL.BRANCH.
 *
 * <p>Isolates the first-ever {@code gl_branch_balance} INSERT for a branch+GL
 * combination into a {@link Propagation#REQUIRES_NEW} boundary so a unique
 * constraint collision on concurrent first-postings cannot poison the outer
 * GL posting transaction.
 *
 * <p><b>Why this exists:</b> {@code AccountingService.updateGLBalances()} runs
 * inside the posting transaction (which holds PESSIMISTIC_WRITE locks on
 * GLMaster rows). On the first-ever posting to a branch+GL combination,
 * {@code findAndLock} returns empty. Two concurrent first-postings both see
 * empty, both try to INSERT, one wins and the other gets a
 * {@link DataIntegrityViolationException} on {@code uq idx_glbb_tenant_branch_gl}.
 *
 * <p>If the INSERT were performed inside the outer transaction via
 * {@code repository.saveAndFlush(...)}, Spring's repository-proxy
 * {@code TransactionInterceptor} would mark the enclosing transaction
 * {@code rollback-only} BEFORE the exception surfaces to the caller. Even a
 * clean catch would doom the outer transaction — the subsequent GL balance
 * update and ledger write would all be rolled back on commit with
 * {@code UnexpectedRollbackException}.
 *
 * <p>By carving the INSERT into its own {@code REQUIRES_NEW} boundary, the
 * constraint violation only rolls back THIS nested transaction, leaving the
 * outer posting transaction intact. The caller then re-locks the row (now
 * inserted by the winning thread) and proceeds.
 *
 * <p>Same pattern as {@link TenantLedgerStateBootstrap} — see its Javadoc
 * for the full explanation of why the exception must NOT be caught inside
 * this method.
 *
 * @see TenantLedgerStateBootstrap
 * @see AccountingService#updateGLBalances
 */
@Service
public class GLBranchBalanceBootstrap {

    private static final Logger log =
            LoggerFactory.getLogger(GLBranchBalanceBootstrap.class);

    private final GLBranchBalanceRepository repository;

    public GLBranchBalanceBootstrap(GLBranchBalanceRepository repository) {
        this.repository = repository;
    }

    /**
     * Insert a new {@code GLBranchBalance} row in a <b>separate</b> transaction
     * so a unique constraint collision cannot poison the calling transaction.
     *
     * <p>The caller must invoke this only when {@code findAndLock} returned empty.
     * A concurrent insert by another thread surfaces as
     * {@link DataIntegrityViolationException}; the exception is allowed to
     * propagate so Spring rolls back the inner REQUIRES_NEW transaction cleanly.
     * The caller must catch it — catching here would cause
     * {@code UnexpectedRollbackException} on the inner commit (JPA spec §3.3.2).
     *
     * @param tenantId tenant scope
     * @param branch   the branch entity
     * @param glCode   GL code
     * @param glName   GL name (denormalized for reporting)
     * @throws DataIntegrityViolationException if another thread inserts first
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertIfAbsent(String tenantId, Branch branch, String glCode, String glName) {
        GLBranchBalance bb = new GLBranchBalance();
        bb.setTenantId(tenantId);
        bb.setBranch(branch);
        bb.setGlCode(glCode);
        bb.setGlName(glName);
        repository.saveAndFlush(bb);
        log.debug("GLBranchBalance bootstrapped: branch={}, gl={}", branch.getBranchCode(), glCode);
    }
}

package com.finvanta.cbs.modules.teller.repository;

import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.domain.TellerTillStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Teller Till Repository per CBS TELLER_TILL standard.
 *
 * <p>Provides both eager-fetched read queries (for service-layer business logic
 * that needs branch metadata) and a {@link LockModeType#PESSIMISTIC_WRITE}
 * locking variant used by every mutation path (cash deposit, cash withdrawal,
 * till close). The lock-and-fetch pattern mirrors
 * {@code DepositAccountRepository.findAndLockByTenantIdAndAccountNumber}.
 */
@Repository
public interface TellerTillRepository extends JpaRepository<TellerTill, Long> {

    /**
     * Locates the till for a specific teller on a specific business date.
     * JOIN FETCH branch so callers can read branch code/name without
     * LazyInitializationException when OSIV is disabled.
     */
    @Query("SELECT t FROM TellerTill t JOIN FETCH t.branch "
            + "WHERE t.tenantId = :tenantId "
            + "AND t.tellerUserId = :tellerUserId "
            + "AND t.businessDate = :businessDate")
    Optional<TellerTill> findByTellerAndDate(
            @Param("tenantId") String tenantId,
            @Param("tellerUserId") String tellerUserId,
            @Param("businessDate") LocalDate businessDate);

    /**
     * Acquires PESSIMISTIC_WRITE on the till row before reading. MUST be used by
     * every mutation path (cash deposit, withdrawal, close) so concurrent
     * transactions on the same till serialize cleanly. 30s lock timeout per
     * CBS TRAN_LOCK standard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT t FROM TellerTill t JOIN FETCH t.branch "
            + "WHERE t.tenantId = :tenantId "
            + "AND t.tellerUserId = :tellerUserId "
            + "AND t.businessDate = :businessDate")
    Optional<TellerTill> findAndLockByTellerAndDate(
            @Param("tenantId") String tenantId,
            @Param("tellerUserId") String tellerUserId,
            @Param("businessDate") LocalDate businessDate);

    /**
     * Loads a till by primary key with the {@code branch} relation eagerly
     * fetched. MUST be used by service methods that read a till by ID and
     * subsequently flow the entity to a mapper which dereferences
     * {@code till.getBranch().getBranchName()} (e.g. supervisor approval
     * endpoints). The default {@link JpaRepository#findById} returns a
     * lazy-proxy on {@code branch}, which throws
     * {@link org.hibernate.LazyInitializationException} once the
     * {@code @Transactional} boundary closes -- OSIV is disabled in v2 per
     * the Tier-1 architecture refactor.
     */
    @Query("SELECT t FROM TellerTill t JOIN FETCH t.branch "
            + "WHERE t.tenantId = :tenantId AND t.id = :id")
    Optional<TellerTill> findByIdWithBranch(
            @Param("tenantId") String tenantId,
            @Param("id") Long id);

    /**
     * Acquires PESSIMISTIC_WRITE on the till row by ID before reading. MUST be
     * used by supervisor approve/reject endpoints
     * ({@code approveTillOpen}, {@code approveTillClose}, {@code rejectTillOpen},
     * {@code rejectTillClose}) so two concurrent supervisor clicks on the same
     * till serialize cleanly at the DB layer. The first thread holds the row
     * lock until commit; the second blocks, then re-reads the (now OPEN /
     * CLOSED / OPEN-after-recoverable-reject) status and falls through the
     * {@code isPendingOpen()} / {@code isPendingClose()} guard with a clean
     * {@code TELLER_TILL_INVALID_STATE} domain error.
     *
     * <p>Without this lock, both threads would pass the status guard, mutate
     * the till, and only the second {@code save()} would fail with an opaque
     * {@code OptimisticLockingFailureException} (mapped to HTTP 409
     * {@code VERSION_CONFLICT}) -- a poor operator UX for a known race.
     *
     * <p>30s lock timeout per CBS TRAN_LOCK standard. JOIN FETCH branch so
     * the mapper can read {@code till.getBranch().getBranchName()} after the
     * {@code @Transactional} boundary closes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT t FROM TellerTill t JOIN FETCH t.branch "
            + "WHERE t.tenantId = :tenantId AND t.id = :id")
    Optional<TellerTill> findAndLockById(
            @Param("tenantId") String tenantId,
            @Param("id") Long id);

    /**
     * Lists all tills at a branch in a given lifecycle status. Used by the
     * supervisor pipeline (PENDING_OPEN approval, PENDING_CLOSE sign-off) and
     * by the EOD orchestrator to detect tills that did not close before BOD.
     */
    @Query("SELECT t FROM TellerTill t JOIN FETCH t.branch "
            + "WHERE t.tenantId = :tenantId "
            + "AND t.branch.id = :branchId "
            + "AND t.businessDate = :businessDate "
            + "AND t.status = :status "
            + "ORDER BY t.openedAt ASC")
    List<TellerTill> findByBranchDateAndStatus(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("businessDate") LocalDate businessDate,
            @Param("status") TellerTillStatus status);

    /**
     * Returns the count of OPEN tills at a branch as of {@code businessDate}.
     * Used by the EOD orchestrator: a branch cannot transition to DAY_CLOSED
     * while any of its tills are still OPEN or PENDING_CLOSE.
     */
    @Query("SELECT COUNT(t) FROM TellerTill t "
            + "WHERE t.tenantId = :tenantId "
            + "AND t.branch.id = :branchId "
            + "AND t.businessDate = :businessDate "
            + "AND t.status IN (com.finvanta.cbs.modules.teller.domain.TellerTillStatus.OPEN, "
            + "com.finvanta.cbs.modules.teller.domain.TellerTillStatus.PENDING_OPEN, "
            + "com.finvanta.cbs.modules.teller.domain.TellerTillStatus.PENDING_CLOSE)")
    long countActiveAtBranch(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("businessDate") LocalDate businessDate);
}

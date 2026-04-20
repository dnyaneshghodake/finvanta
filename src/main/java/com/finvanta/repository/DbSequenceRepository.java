package com.finvanta.repository;

import com.finvanta.domain.entity.DbSequence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Sequence Repository with pessimistic locking for multi-instance safety.
 *
 * Per Finacle SEQ_MASTER pattern: the sequence row is locked with PESSIMISTIC_WRITE
 * before incrementing, serializing all concurrent allocations. This guarantees
 * globally unique sequence values across all JVM instances sharing the same DB.
 *
 * <p><b>Lock timeout:</b> 10 seconds (shorter than GL/account locks because sequence
 * allocation is a lightweight operation — if it takes >10s, something is stuck).
 */
@Repository
public interface DbSequenceRepository extends JpaRepository<DbSequence, Long> {

    /**
     * Finds and locks a sequence row for atomic increment.
     * PESSIMISTIC_WRITE ensures no two transactions read the same currentValue.
     * 10s lock timeout — sequence allocation is lightweight; longer waits indicate a stuck TX.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("SELECT s FROM DbSequence s WHERE s.tenantId = :tenantId AND s.sequenceName = :seqName")
    Optional<DbSequence> findAndLockByTenantIdAndSequenceName(
            @Param("tenantId") String tenantId, @Param("seqName") String sequenceName);

    /** Non-locking lookup for read-only queries (e.g., admin display). */
    Optional<DbSequence> findByTenantIdAndSequenceName(String tenantId, String sequenceName);
}

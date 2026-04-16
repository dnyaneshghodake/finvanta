package com.finvanta.repository;

import com.finvanta.domain.entity.TenantLedgerState;

import jakarta.persistence.LockModeType;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Tenant Ledger State Repository per Finacle LEDGER_STATE.
 *
 * <p>Serves a single purpose: fetch the per-tenant ledger sentinel row with a
 * pessimistic write lock so every {@code postToLedger} call serializes behind the
 * same row -- including the first-ever posting on an empty ledger.
 */
@Repository
public interface TenantLedgerStateRepository extends JpaRepository<TenantLedgerState, String> {

    /**
     * Fetch the sentinel row with {@code SELECT ... FOR UPDATE}.
     * The caller is responsible for creating the row if it does not yet exist
     * (via {@link #save(Object)}), then re-locking it. This two-step pattern is
     * required because {@code SELECT ... FOR UPDATE} on a non-existent row is a
     * no-op, not an insert.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM TenantLedgerState s WHERE s.tenantId = :tenantId")
    Optional<TenantLedgerState> findAndLock(@Param("tenantId") String tenantId);
}

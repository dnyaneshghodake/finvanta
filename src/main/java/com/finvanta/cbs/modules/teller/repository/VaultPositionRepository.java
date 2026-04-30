package com.finvanta.cbs.modules.teller.repository;

import com.finvanta.cbs.modules.teller.domain.VaultPosition;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Vault Position Repository per CBS VAULT_POS standard.
 *
 * <p>Provides both eager-fetched read queries (for the vault dashboard) and
 * a {@link LockModeType#PESSIMISTIC_WRITE} variant used by every vault
 * mutation (buy/sell cash movements). The lock pattern mirrors
 * {@code TellerTillRepository.findAndLockByTellerAndDate}.
 */
@Repository
public interface VaultPositionRepository extends JpaRepository<VaultPosition, Long> {

    /** Read-only lookup for the vault dashboard / till-open screen. */
    @Query("SELECT v FROM VaultPosition v JOIN FETCH v.branch "
            + "WHERE v.tenantId = :tenantId "
            + "AND v.branch.id = :branchId "
            + "AND v.businessDate = :businessDate")
    Optional<VaultPosition> findByBranchAndDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("businessDate") LocalDate businessDate);

    /**
     * Acquires PESSIMISTIC_WRITE on the vault row. MUST be used by every
     * mutation (buy/sell cash movements) so concurrent movements on the
     * same vault serialize cleanly. 30s lock timeout per CBS TRAN_LOCK.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT v FROM VaultPosition v JOIN FETCH v.branch "
            + "WHERE v.tenantId = :tenantId "
            + "AND v.branch.id = :branchId "
            + "AND v.businessDate = :businessDate")
    Optional<VaultPosition> findAndLockByBranchAndDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("businessDate") LocalDate businessDate);
}

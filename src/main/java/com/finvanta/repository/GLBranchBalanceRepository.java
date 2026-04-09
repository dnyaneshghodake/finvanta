package com.finvanta.repository;

import com.finvanta.domain.entity.GLBranchBalance;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Branch GL Balance Repository per Finacle GL_BRANCH / Temenos ACCT.BALANCE.
 *
 * Provides branch-level GL balance queries with pessimistic locking for
 * concurrent posting serialization. Every GL posting must update both the
 * tenant-level GLMaster (via GLMasterRepository) AND the branch-level
 * GLBranchBalance (via this repository) atomically within the same transaction.
 *
 * Reconciliation invariant (verified at EOD Step 7.2):
 *   GLMaster.debitBalance  == SUM(GLBranchBalance.debitBalance) for all branches
 *   GLMaster.creditBalance == SUM(GLBranchBalance.creditBalance) for all branches
 */
@Repository
public interface GLBranchBalanceRepository extends JpaRepository<GLBranchBalance, Long> {

    /**
     * Find branch GL balance by tenant + branch + GL code.
     * Used for read-only queries (trial balance display, reporting).
     */
    Optional<GLBranchBalance> findByTenantIdAndBranchIdAndGlCode(String tenantId, Long branchId, String glCode);

    /**
     * Find and lock branch GL balance for posting.
     * PESSIMISTIC_WRITE lock serializes concurrent postings to the same branch+GL.
     * Per Finacle GL_BRANCH: concurrent postings to the same branch+GL are serialized
     * to prevent lost-update on running balances.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT glb FROM GLBranchBalance glb WHERE glb.tenantId = :tenantId "
            + "AND glb.branch.id = :branchId AND glb.glCode = :glCode")
    Optional<GLBranchBalance> findAndLockByTenantIdAndBranchIdAndGlCode(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("glCode") String glCode);

    /**
     * All GL balances for a specific branch — used for branch trial balance.
     * Per Finacle: branch trial balance must independently balance.
     */
    @Query("SELECT glb FROM GLBranchBalance glb WHERE glb.tenantId = :tenantId "
            + "AND glb.branch.id = :branchId ORDER BY glb.glCode")
    List<GLBranchBalance> findAllByTenantIdAndBranchId(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId);

    /**
     * All branch balances for a specific GL code — used for HO consolidation reconciliation.
     * Per Finacle: SUM of branch balances must equal tenant-level GLMaster balance.
     */
    @Query("SELECT glb FROM GLBranchBalance glb WHERE glb.tenantId = :tenantId "
            + "AND glb.glCode = :glCode ORDER BY glb.branch.branchCode")
    List<GLBranchBalance> findAllByTenantIdAndGlCode(
            @Param("tenantId") String tenantId, @Param("glCode") String glCode);

    /**
     * Sum of debit balances across all branches for a GL code.
     * Used for reconciliation: must equal GLMaster.debitBalance.
     */
    @Query("SELECT COALESCE(SUM(glb.debitBalance), 0) FROM GLBranchBalance glb "
            + "WHERE glb.tenantId = :tenantId AND glb.glCode = :glCode")
    java.math.BigDecimal sumDebitBalanceByGlCode(
            @Param("tenantId") String tenantId, @Param("glCode") String glCode);

    /**
     * Sum of credit balances across all branches for a GL code.
     * Used for reconciliation: must equal GLMaster.creditBalance.
     */
    @Query("SELECT COALESCE(SUM(glb.creditBalance), 0) FROM GLBranchBalance glb "
            + "WHERE glb.tenantId = :tenantId AND glb.glCode = :glCode")
    java.math.BigDecimal sumCreditBalanceByGlCode(
            @Param("tenantId") String tenantId, @Param("glCode") String glCode);
}

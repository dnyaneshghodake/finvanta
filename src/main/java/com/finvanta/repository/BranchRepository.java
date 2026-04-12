package com.finvanta.repository;

import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.enums.BranchType;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Branch Repository per Finacle BRANCH_MASTER (SOL) / Temenos COMPANY.
 *
 * Provides queries for branch hierarchy navigation, operational branch lookup,
 * and zone/region aggregation. Per Finacle SOL architecture:
 *   HEAD_OFFICE → ZONAL_OFFICE → REGIONAL_OFFICE → BRANCH
 */
@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByTenantIdAndBranchCode(String tenantId, String branchCode);

    List<Branch> findByTenantIdAndActiveTrue(String tenantId);

    boolean existsByTenantIdAndBranchCode(String tenantId, String branchCode);

    // === Branch Hierarchy Queries (per Finacle SOL architecture) ===

    /** Find the Head Office branch for a tenant (exactly one per tenant) */
    @Query("SELECT b FROM Branch b WHERE b.tenantId = :tenantId AND b.headOffice = true")
    Optional<Branch> findHeadOffice(@Param("tenantId") String tenantId);

    /** Find all operational branches (type=BRANCH) — excludes HO/ZO/RO */
    @Query("SELECT b FROM Branch b WHERE b.tenantId = :tenantId "
            + "AND b.branchType = 'BRANCH' AND b.active = true ORDER BY b.branchCode")
    List<Branch> findAllOperationalBranches(@Param("tenantId") String tenantId);

    /** Find branches by type (HEAD_OFFICE, ZONAL_OFFICE, REGIONAL_OFFICE, BRANCH) */
    List<Branch> findByTenantIdAndBranchTypeAndActiveTrue(String tenantId, BranchType branchType);

    /** Find child branches under a parent (for hierarchy traversal) */
    @Query("SELECT b FROM Branch b WHERE b.tenantId = :tenantId "
            + "AND b.parentBranch.id = :parentId AND b.active = true ORDER BY b.branchCode")
    List<Branch> findChildBranches(@Param("tenantId") String tenantId, @Param("parentId") Long parentId);

    /** Find all branches in a zone (for zone-level aggregation) */
    @Query("SELECT b FROM Branch b WHERE b.tenantId = :tenantId "
            + "AND b.zoneCode = :zoneCode AND b.branchType = 'BRANCH' AND b.active = true ORDER BY b.branchCode")
    List<Branch> findBranchesByZone(@Param("tenantId") String tenantId, @Param("zoneCode") String zoneCode);

    /** Find all branches in a region (for region-level aggregation) */
    @Query("SELECT b FROM Branch b WHERE b.tenantId = :tenantId "
            + "AND b.regionCode = :regionCode AND b.branchType = 'BRANCH' AND b.active = true ORDER BY b.branchCode")
    List<Branch> findBranchesByRegion(@Param("tenantId") String tenantId, @Param("regionCode") String regionCode);

    /** Count operational branches for a tenant (for EOD completion tracking) */
    @Query("SELECT COUNT(b) FROM Branch b WHERE b.tenantId = :tenantId "
            + "AND b.branchType = 'BRANCH' AND b.active = true")
    long countOperationalBranches(@Param("tenantId") String tenantId);
}

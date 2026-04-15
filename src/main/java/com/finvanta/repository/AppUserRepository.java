package com.finvanta.repository;

import com.finvanta.domain.entity.AppUser;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS User Repository per Finacle USER_MASTER / Temenos USER.
 *
 * Per RBI IT Governance Direction 2023:
 * - User accounts with no activity for 90+ days must be auto-locked
 * - All user queries are tenant-scoped (multi-tenant isolation)
 * - Branch-level user management for ADMIN operations
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTenantIdAndUsername(String tenantId, String username);

    boolean existsByTenantIdAndUsername(String tenantId, String username);

    /** All users for a tenant (for user management UI) */
    List<AppUser> findByTenantIdOrderByRoleAscUsernameAsc(String tenantId);

    /** Users by branch (for branch-level user management) */
    List<AppUser> findByTenantIdAndBranchIdOrderByRoleAscUsernameAsc(String tenantId, Long branchId);

    /** Check if username already exists (for duplicate prevention) */
    boolean existsByTenantIdAndEmail(String tenantId, String email);

    /**
     * Find active, unlocked users who have not logged in since the cutoff date.
     * Per RBI IT Governance Direction 2023 §8.3: user accounts with no activity
     * for 90+ days must be automatically locked during EOD batch processing.
     *
     * Uses lastLoginAt as the primary dormancy indicator because:
     * 1. It exists for all users who have ever logged in
     * 2. lastActivityDate may be null for pre-existing accounts
     * 3. A user who hasn't logged in for 90 days is clearly dormant
     *
     * Excludes system/service accounts (username starting with 'SYSTEM').
     */
    @Query("SELECT u FROM AppUser u WHERE u.tenantId = :tenantId "
            + "AND u.active = true AND u.locked = false "
            + "AND u.username NOT LIKE 'SYSTEM%' "
            + "AND (u.lastLoginAt IS NULL OR u.lastLoginAt < :cutoffDateTime)")
    List<AppUser> findDormantUserCandidates(
            @Param("tenantId") String tenantId,
            @Param("cutoffDateTime") java.time.LocalDateTime cutoffDateTime);

    // === CBS USER_INQUIRY: User Search per Finacle USER_INQUIRY / RBI IT Governance §8.2 ===

    /**
     * Search users by username, full name, email, role, or branch code.
     * Per RBI IT Governance Direction 2023 §8.2: audit of user access must be searchable.
     * Per Finacle USER_INQUIRY: ADMIN must locate users instantly for access management,
     * RBI inspection queries (e.g., "show all CHECKER users at branch BR003").
     * Tenant-scoped. ADMIN-only (enforced in SecurityConfig).
     */
    @Query("SELECT u FROM AppUser u WHERE u.tenantId = :tenantId AND ("
            + "LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(CAST(u.role AS string)) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(u.branch.branchCode) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY u.role ASC, u.username ASC")
    List<AppUser> searchUsers(
            @Param("tenantId") String tenantId, @Param("query") String query);
}

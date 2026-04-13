package com.finvanta.repository;

import com.finvanta.domain.entity.RolePermission;
import com.finvanta.domain.enums.UserRole;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Role-Permission Repository per Finacle AUTH_ROLE_PERM.
 *
 * Provides efficient permission lookups for the CbsPermissionEvaluator.
 * Per Finacle/Temenos: permission checks happen on EVERY request —
 * queries must be optimized with proper indexing.
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    /** All active permission mappings for a role (for permission evaluation) */
    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.permission p "
            + "WHERE rp.tenantId = :tenantId AND rp.role = :role "
            + "AND rp.active = true AND p.active = true")
    List<RolePermission> findActivePermissionsByRole(
            @Param("tenantId") String tenantId,
            @Param("role") UserRole role);

    /** Check if a specific role has a specific permission (fast existence check) */
    @Query("SELECT CASE WHEN COUNT(rp) > 0 THEN true ELSE false END "
            + "FROM RolePermission rp JOIN rp.permission p "
            + "WHERE rp.tenantId = :tenantId AND rp.role = :role "
            + "AND p.permissionCode = :permissionCode "
            + "AND rp.active = true AND p.active = true "
            + "AND rp.grantType = 'ALLOW'")
    boolean hasPermission(
            @Param("tenantId") String tenantId,
            @Param("role") UserRole role,
            @Param("permissionCode") String permissionCode);

    /** Check if a specific permission is explicitly denied for a role */
    @Query("SELECT CASE WHEN COUNT(rp) > 0 THEN true ELSE false END "
            + "FROM RolePermission rp JOIN rp.permission p "
            + "WHERE rp.tenantId = :tenantId AND rp.role = :role "
            + "AND p.permissionCode = :permissionCode "
            + "AND rp.active = true AND rp.grantType = 'DENY'")
    boolean isDenied(
            @Param("tenantId") String tenantId,
            @Param("role") UserRole role,
            @Param("permissionCode") String permissionCode);

    /** All mappings for a role (for admin UI — includes inactive) */
    List<RolePermission> findByTenantIdAndRoleOrderByPermission_ModuleAsc(
            String tenantId, UserRole role);
}

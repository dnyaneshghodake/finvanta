package com.finvanta.repository;

import com.finvanta.domain.entity.Permission;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Permission Repository per Finacle AUTH_PERMISSION.
 */
@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByTenantIdAndPermissionCode(
            String tenantId, String permissionCode);

    List<Permission> findByTenantIdAndActiveOrderByModuleAscPermissionCodeAsc(
            String tenantId, boolean active);

    List<Permission> findByTenantIdAndModuleAndActiveOrderByPermissionCodeAsc(
            String tenantId, String module, boolean active);

    boolean existsByTenantIdAndPermissionCode(
            String tenantId, String permissionCode);
}

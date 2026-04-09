package com.finvanta.repository;

import com.finvanta.domain.entity.AppUser;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}

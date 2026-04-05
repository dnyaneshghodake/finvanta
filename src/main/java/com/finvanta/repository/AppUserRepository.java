package com.finvanta.repository;

import com.finvanta.domain.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTenantIdAndUsername(String tenantId, String username);

    boolean existsByTenantIdAndUsername(String tenantId, String username);
}

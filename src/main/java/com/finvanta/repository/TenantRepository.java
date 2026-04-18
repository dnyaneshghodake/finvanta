package com.finvanta.repository;

import com.finvanta.domain.entity.Tenant;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findByTenantCode(String tenantCode);

    boolean existsByTenantCode(String tenantCode);

    /** All active tenants — used by OutboxEventProcessor to process events for every tenant. */
    List<Tenant> findByActiveTrue();
}

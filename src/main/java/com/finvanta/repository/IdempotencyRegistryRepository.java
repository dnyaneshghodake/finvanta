package com.finvanta.repository;

import com.finvanta.domain.entity.IdempotencyRegistry;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * CBS Tier-1 Idempotency Registry Repository.
 * Engine-level cross-module duplicate detection.
 *
 * @see com.finvanta.domain.entity.IdempotencyRegistry
 */
@Repository
public interface IdempotencyRegistryRepository extends JpaRepository<IdempotencyRegistry, Long> {

    /**
     * Find existing idempotency entry by tenant and key.
     * Used by TransactionEngine Step 1 to detect duplicate requests.
     */
    Optional<IdempotencyRegistry> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}

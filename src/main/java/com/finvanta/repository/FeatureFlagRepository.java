package com.finvanta.repository;

import com.finvanta.domain.entity.FeatureFlag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * CBS Feature Flag Repository per Finacle BANK_PARAM.
 *
 * Per RBI IT Governance Direction 2023: feature availability
 * must be queryable at runtime for payment rail enable/disable,
 * product module availability, and system feature readiness.
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {

    Optional<FeatureFlag> findByTenantIdAndFlagCode(String tenantId, String flagCode);

    List<FeatureFlag> findByTenantIdAndEnabled(String tenantId, boolean enabled);

    List<FeatureFlag> findByTenantIdAndCategory(String tenantId, String category);

    List<FeatureFlag> findByTenantId(String tenantId);
}

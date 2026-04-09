package com.finvanta.repository;

import com.finvanta.domain.entity.ChargeConfig;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for ChargeConfig — Finacle CHRG_MASTER equivalence.
 */
@Repository
public interface ChargeConfigRepository extends JpaRepository<ChargeConfig, Long> {

    /**
     * Find charge config by tenant and charge code.
     * Includes product-specific override resolution:
     * 1. If product_code matches given productCode (and is_active=true), return that
     * 2. Else if product_code=null (global, is_active=true), return that
     * 3. Else not found
     */
    @Query("SELECT c FROM ChargeConfig c WHERE c.tenantId = :tenantId AND c.chargeCode = :chargeCode "
            + "AND c.isActive = true ORDER BY CASE WHEN c.productCode = :productCode THEN 0 ELSE 1 END LIMIT 1")
    Optional<ChargeConfig> findByTenantAndChargeCodeAndProduct(
            @Param("tenantId") String tenantId,
            @Param("chargeCode") String chargeCode,
            @Param("productCode") String productCode);

    /**
     * Find global charge config (product_code = null).
     */
    Optional<ChargeConfig> findByTenantIdAndChargeCodeAndProductCodeIsNullAndIsActiveTrue(
            String tenantId, String chargeCode);

    /**
     * Find all active charge configs for a tenant.
     */
    List<ChargeConfig> findByTenantIdAndIsActiveTrueOrderByEventTriggerAsc(String tenantId);

    /**
     * Find configs by event trigger (for EOD batch processing).
     */
    List<ChargeConfig> findByTenantIdAndEventTriggerAndIsActiveTrueOrderByChargeCode(
            String tenantId, String eventTrigger);
}

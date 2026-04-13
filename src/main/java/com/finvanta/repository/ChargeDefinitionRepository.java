package com.finvanta.repository;

import com.finvanta.domain.entity.ChargeDefinition;
import com.finvanta.domain.enums.ChargeEventType;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Charge Definition Repository per Finacle CHG_MASTER.
 *
 * Resolution order for charge lookup:
 * 1. Product-specific + event type (most specific)
 * 2. Global (null productCode) + event type (fallback)
 * Per Finacle CHG_MASTER: product-level overrides take precedence.
 */
@Repository
public interface ChargeDefinitionRepository
        extends JpaRepository<ChargeDefinition, Long> {

    /**
     * Find active charge definition for a specific product and event.
     * Returns product-specific first, then global fallback.
     * ORDER BY: non-null productCode first (product-specific wins).
     */
    @Query("SELECT cd FROM ChargeDefinition cd "
            + "WHERE cd.tenantId = :tenantId "
            + "AND cd.eventType = :eventType "
            + "AND cd.active = true "
            + "AND (cd.productCode = :productCode "
            + "     OR cd.productCode IS NULL) "
            + "ORDER BY cd.productCode DESC NULLS LAST")
    List<ChargeDefinition> findApplicableCharges(
            @Param("tenantId") String tenantId,
            @Param("eventType") ChargeEventType eventType,
            @Param("productCode") String productCode);

    /** Find all active definitions for a tenant (admin UI) */
    List<ChargeDefinition> findByTenantIdAndActiveOrderByEventType(
            String tenantId, boolean active);

    /** Find by event type (for reporting) */
    List<ChargeDefinition>
            findByTenantIdAndEventTypeAndActive(
                    String tenantId,
                    ChargeEventType eventType,
                    boolean active);

    /** Check if a definition exists for event + product */
    boolean existsByTenantIdAndEventTypeAndProductCode(
            String tenantId,
            ChargeEventType eventType,
            String productCode);
}

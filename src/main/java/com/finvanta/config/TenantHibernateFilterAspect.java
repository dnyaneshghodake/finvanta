package com.finvanta.config;

import com.finvanta.util.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CBS Hibernate Tenant Filter Activator per Finacle/Temenos Tier-1 Multi-Tenant Isolation.
 *
 * CRITICAL INFRASTRUCTURE: This aspect enables the Hibernate @Filter("tenantFilter")
 * declared on BaseEntity for EVERY repository operation. Without this, the @FilterDef
 * and @Filter annotations on BaseEntity are purely decorative — Hibernate ignores them
 * unless explicitly enabled on the Session.
 *
 * Per RBI IT Governance Direction 2023 §8.1:
 * Multi-tenant systems must enforce data isolation at the INFRASTRUCTURE level,
 * not just the application level. This aspect provides defense-in-depth:
 *
 * Layer 1: Repository queries include WHERE tenant_id = :tenantId (application level)
 * Layer 2: Hibernate @Filter automatically appends tenant_id condition (infrastructure level)
 * Layer 3: DB column NOT NULL constraint on tenant_id (database level)
 *
 * Even if a developer writes a custom JPQL/HQL query without tenant filtering,
 * the Hibernate filter ensures only the current tenant's data is returned.
 *
 * Architecture:
 *   TenantFilter (servlet) sets TenantContext ThreadLocal
 *     → TenantHibernateFilterAspect enables Hibernate filter on Session
 *       → Every SELECT query gets: AND tenant_id = :tenantId appended
 *
 * Scope: Intercepts ALL Spring Data JPA repository method calls.
 * The @Before advice runs before every repository method, ensuring the filter
 * is active for the current Hibernate Session. This is idempotent — enabling
 * an already-enabled filter is a no-op in Hibernate.
 *
 * Per Finacle BANK_MASTER / Temenos COMPANY: tenant isolation is non-negotiable.
 * A single cross-tenant data leak in a regulated bank is a RBI reportable incident.
 */
@Aspect
@Component
public class TenantHibernateFilterAspect {

    private static final Logger log =
            LoggerFactory.getLogger(TenantHibernateFilterAspect.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Enables the Hibernate tenant filter before every JPA repository method call.
     *
     * Pointcut: all methods in any interface extending JpaRepository
     * (covers all Spring Data repositories in com.finvanta.repository).
     *
     * Per Hibernate docs: Session.enableFilter() is idempotent — calling it
     * multiple times with the same parameter is safe and has no overhead.
     * The filter remains active for the duration of the Hibernate Session
     * (typically one per @Transactional boundary or OpenSessionInView scope).
     *
     * CBS Safety: If TenantContext is not set (e.g., during application startup,
     * Flyway migrations, or health checks), the filter is NOT enabled.
     * This prevents startup failures while maintaining runtime protection.
     */
    @Before("execution(* org.springframework.data.jpa.repository.JpaRepository+.*(..))")
    public void enableTenantFilter() {
        if (!TenantContext.isSet()) {
            // Pre-authentication or system startup — skip filter activation.
            // TenantFilter (servlet) has not yet set the context.
            return;
        }

        try {
            String tenantId = TenantContext.getCurrentTenant();
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter")
                    .setParameter("tenantId", tenantId);
        } catch (Exception e) {
            // CBS CRITICAL: Fail-closed per RBI IT Governance Direction 2023 §8.1.
            // If the Hibernate tenant filter cannot be activated, proceeding would
            // allow cross-tenant data leakage — the repository query would return
            // ALL tenants' data. This is a non-negotiable security boundary.
            //
            // Per Finacle/Temenos: infrastructure-level tenant isolation failure
            // is a fatal condition — the request MUST be rejected.
            log.error("CRITICAL: Failed to enable Hibernate tenant filter. "
                    + "Rejecting repository call to prevent cross-tenant data leakage: {}",
                    e.getMessage());
            throw new IllegalStateException(
                    "CBS SECURITY: Tenant isolation filter activation failed. "
                    + "Repository access denied to prevent cross-tenant data leakage.", e);
        }
    }
}

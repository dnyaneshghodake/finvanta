package com.finvanta.config;

import com.finvanta.util.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * CBS Hibernate Tenant Filter Interceptor — enables the Hibernate @Filter
 * on every request so that ALL JPA queries are automatically scoped to
 * the current tenant's data.
 *
 * This works in conjunction with:
 *   1. {@link com.finvanta.config.TenantFilter} — sets TenantContext from request
 *   2. {@link com.finvanta.domain.entity.BaseEntity} — defines @FilterDef/@Filter
 *   3. This interceptor — enables the filter on the Hibernate Session
 *
 * Without this, the @Filter annotation on BaseEntity is defined but NOT active.
 * Hibernate filters are disabled by default and must be explicitly enabled
 * per-session via session.enableFilter().
 *
 * Per RBI IT Governance Direction 2023: multi-tenant data isolation must be
 * enforced at the infrastructure level (ORM/DB), not just application logic.
 *
 * IMPORTANT: This filter applies to Hibernate Session queries (JPQL, Criteria,
 * findAll, etc.). Native SQL queries bypass Hibernate filters — any native
 * query must still include WHERE tenant_id = ? manually.
 */
@Component
public class HibernateTenantFilterInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HibernateTenantFilterInterceptor.class);

    private final EntityManager entityManager;

    public HibernateTenantFilterInterceptor(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!TenantContext.isSet()) {
            // TenantContext not set (e.g., static resources, login page) — skip filter.
            // These requests don't access tenant-scoped data.
            return true;
        }
        try {
            String tenantId = TenantContext.getCurrentTenant();
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
        } catch (Exception e) {
            // CBS CRITICAL: Fail-closed per RBI IT Governance Direction 2023 §8.1.
            // If TenantContext IS set but filter activation fails, this is a genuine
            // infrastructure failure — not a pre-auth request. Proceeding would allow
            // unfiltered queries to return cross-tenant data.
            log.error("CRITICAL: Hibernate tenant filter activation failed for tenant-scoped request: {}",
                    e.getMessage());
            throw new IllegalStateException(
                    "CBS SECURITY: Tenant filter activation failed. Request rejected.", e);
        }
        return true;
    }
}

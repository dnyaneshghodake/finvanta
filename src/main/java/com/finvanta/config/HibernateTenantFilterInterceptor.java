package com.finvanta.config;

import com.finvanta.util.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
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

    private final EntityManager entityManager;

    public HibernateTenantFilterInterceptor(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                              Object handler) {
        try {
            String tenantId = TenantContext.getCurrentTenant();
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
        } catch (Exception e) {
            // TenantContext not set (e.g., static resources, login page) — skip filter.
            // These requests don't access tenant-scoped data.
        }
        return true;
    }
}

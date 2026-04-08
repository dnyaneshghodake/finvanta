package com.finvanta.domain.entity;

import com.finvanta.util.TenantContext;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.Getter;
import lombok.Setter;

/**
 * CBS Base Entity with automatic tenant isolation.
 *
 * All entities extending BaseEntity are automatically filtered by tenant_id
 * via Hibernate @Filter. This provides defense-in-depth against cross-tenant
 * data leakage: even if a custom query forgets to include WHERE tenant_id = ?,
 * the Hibernate filter ensures only the current tenant's data is returned.
 *
 * The filter is enabled per-session by {@link com.finvanta.config.TenantFilter}.
 *
 * Additionally, @PrePersist auto-sets tenant_id from TenantContext if not
 * already set, preventing orphaned entities with null/wrong tenant_id.
 *
 * Per RBI IT Governance Direction 2023: multi-tenant systems must enforce
 * data isolation at the infrastructure level, not just the application level.
 */
@MappedSuperclass
@Getter
@Setter
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = String.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    /**
     * CBS Tenant Safety: Auto-set tenant_id from TenantContext on persist.
     * Prevents orphaned entities if any code path forgets to set tenantId.
     * If tenantId is already set (e.g., by service code), this is a no-op.
     */
    @PrePersist
    protected void prePersistTenant() {
        if (this.tenantId == null || this.tenantId.isBlank()) {
            String currentTenant = TenantContext.getCurrentTenant();
            if (currentTenant != null) {
                this.tenantId = currentTenant;
            }
        }
    }
}

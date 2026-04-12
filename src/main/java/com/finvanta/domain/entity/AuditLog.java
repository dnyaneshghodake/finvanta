package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import org.hibernate.annotations.Filter;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(
        name = "audit_logs",
        indexes = {
            @Index(name = "idx_audit_tenant_entity", columnList = "tenant_id, entity_type, entity_id"),
            @Index(name = "idx_audit_timestamp", columnList = "tenant_id, event_timestamp"),
            @Index(name = "idx_audit_user", columnList = "tenant_id, performed_by"),
            @Index(name = "idx_audit_branch", columnList = "tenant_id, branch_id, event_timestamp")
        })
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 20)
    private String tenantId;

    /**
     * Branch where this audit event originated.
     * Per Finacle AUDIT_TRAIL / RBI IT Governance Direction 2023:
     * Every audit record must be traceable to a specific branch for
     * branch-level audit reports and regulatory inspection.
     * Null for tenant-level system events (e.g., tenant configuration changes).
     */
    @Column(name = "branch_id")
    private Long branchId;

    /** Branch code denormalized for efficient audit report filtering. */
    @Column(name = "branch_code", length = 20)
    private String branchCode;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /**
     * Entity ID for the audited record. Per Finacle AUDIT_TRAIL / Temenos AUDIT.RECORD:
     * this column is NEVER null. For system-level events (calendar generation, holiday
     * management, HO settlement) that don't reference a specific entity row, the
     * AuditService normalizes null to 0L before persistence.
     *
     * Per Tier-1 CBS: NOT NULL constraints are the database-level safety net.
     * The service layer (AuditService.logEvent) guarantees a value is always provided.
     * 0L = system-level event (no specific entity).
     */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "before_snapshot", columnDefinition = "TEXT")
    private String beforeSnapshot;

    @Column(name = "after_snapshot", columnDefinition = "TEXT")
    private String afterSnapshot;

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "event_timestamp", nullable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    @Column(name = "module", length = 50)
    private String module;

    @Column(name = "description", length = 1000)
    private String description;
}

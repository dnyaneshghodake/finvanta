package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_tenant_entity", columnList = "tenant_id, entity_type, entity_id"),
    @Index(name = "idx_audit_timestamp", columnList = "tenant_id, event_timestamp"),
    @Index(name = "idx_audit_user", columnList = "tenant_id, performed_by")
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

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

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

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "module", length = 50)
    private String module;

    @Column(name = "description", length = 1000)
    private String description;
}

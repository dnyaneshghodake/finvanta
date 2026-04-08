package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ApprovalStatus;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "approval_workflows",
        indexes = {
            @Index(name = "idx_wf_tenant_entity", columnList = "tenant_id, entity_type, entity_id"),
            @Index(name = "idx_wf_status", columnList = "tenant_id, status"),
            @Index(name = "idx_wf_checker", columnList = "tenant_id, checker_user_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ApprovalWorkflow extends BaseEntity {

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ApprovalStatus status = ApprovalStatus.PENDING_APPROVAL;

    @Column(name = "maker_user_id", nullable = false, length = 100)
    private String makerUserId;

    @Column(name = "checker_user_id", length = 100)
    private String checkerUserId;

    @Column(name = "maker_remarks", length = 1000)
    private String makerRemarks;

    @Column(name = "checker_remarks", length = 1000)
    private String checkerRemarks;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    @Column(name = "payload_snapshot", columnDefinition = "TEXT")
    private String payloadSnapshot;
}

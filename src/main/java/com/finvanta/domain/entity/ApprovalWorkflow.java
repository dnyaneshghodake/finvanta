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

    // === SLA / Escalation (per Finacle WORKFLOW_SLA / Temenos LIMIT.CHECK) ===

    /**
     * SLA deadline for this approval. If not actioned before this time,
     * the workflow is eligible for escalation to a higher authority.
     * Per RBI Fair Practices Code: loan applications must be processed within
     * defined TAT (Turn Around Time). Configurable per action type.
     * Null = no SLA (backward compatible with existing data).
     */
    @Column(name = "sla_deadline")
    private LocalDateTime slaDeadline;

    /**
     * Number of times this workflow has been escalated.
     * Per Finacle WORKFLOW_SLA: after SLA breach, workflow is auto-escalated
     * to ADMIN. Multiple escalation levels are tracked.
     */
    @Column(name = "escalation_count", nullable = false)
    private int escalationCount = 0;

    /**
     * User to whom the workflow was escalated (null if not escalated).
     * Per Finacle: escalation target is typically the branch ADMIN.
     */
    @Column(name = "escalated_to", length = 100)
    private String escalatedTo;

    /**
     * Timestamp when the workflow was last escalated.
     */
    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    /** Returns true if the SLA deadline has passed and the workflow is still pending */
    public boolean isSlaBreached() {
        return slaDeadline != null
                && status == ApprovalStatus.PENDING_APPROVAL
                && LocalDateTime.now().isAfter(slaDeadline);
    }
}

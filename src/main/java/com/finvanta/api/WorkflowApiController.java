package com.finvanta.api;

import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.workflow.ApprovalWorkflowService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Maker-Checker Workflow REST API per Finacle WFAPI / Temenos AA.ARRANGEMENT.ACTIVITY.
 *
 * <p>Thin orchestration layer over {@link ApprovalWorkflowService} — no business
 * logic here. All maker-checker enforcement (self-approval prevention, state
 * validation, SLA tracking, audit trail) resides in the service layer.
 *
 * <p>Per RBI IT Governance Direction 2023 §8.3 and Finacle Maker-Checker:
 * <ul>
 *   <li>Every financial operation requires dual authorization (maker != checker)</li>
 *   <li>Maker cannot approve their own submission (WORKFLOW_SELF_APPROVAL)</li>
 *   <li>Only PENDING_APPROVAL items can be approved/rejected</li>
 *   <li>SLA tracking: breached workflows are escalated to ADMIN</li>
 *   <li>All workflow actions are immutably audited</li>
 * </ul>
 *
 * <p>CBS Role Matrix:
 * <ul>
 *   <li>CHECKER/ADMIN → view pending queue, approve, reject</li>
 *   <li>ADMIN → additionally: view SLA breaches, trigger escalation</li>
 * </ul>
 *
 * <p>Used by the Next.js BFF for:
 * <ul>
 *   <li>Pending approvals badge count (dashboard widget)</li>
 *   <li>Approval queue screen (CHECKER's primary work screen)</li>
 *   <li>Approve/reject actions with remarks</li>
 *   <li>SLA breach monitoring (ADMIN)</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/workflow")
public class WorkflowApiController {

    private final ApprovalWorkflowService workflowService;

    public WorkflowApiController(
            ApprovalWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    // === Inquiry ===

    /**
     * Get pending approval queue for the current tenant.
     * Per Finacle WFAPI: the CHECKER's primary work screen — shows all
     * items awaiting dual authorization.
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>>
            getPendingApprovals() {
        var items = workflowService.getPendingApprovals()
                .stream().map(WorkflowResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Get workflow history for a specific entity.
     * Per Finacle: shows the full approval chain for audit trail.
     */
    @GetMapping("/history/{entityType}/{entityId}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>>
            getWorkflowHistory(
                    @PathVariable String entityType,
                    @PathVariable Long entityId) {
        var items = workflowService
                .getWorkflowHistory(entityType, entityId)
                .stream().map(WorkflowResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Get SLA-breached workflows. ADMIN only.
     * Per Finacle SLA: workflows past their deadline need escalation.
     */
    @GetMapping("/sla-breached")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>>
            getSlaBreached() {
        var items = workflowService.getSlaBreachedWorkflows()
                .stream().map(WorkflowResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Actions ===

    /**
     * Approve a pending workflow item. CHECKER/ADMIN.
     * Per RBI maker-checker: the approver CANNOT be the same user who
     * submitted the item (enforced by ApprovalWorkflowService).
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<WorkflowResponse>>
            approve(@PathVariable Long id,
                    @Valid @RequestBody WorkflowActionRequest req) {
        ApprovalWorkflow wf = workflowService
                .approve(id, req.remarks());
        return ResponseEntity.ok(ApiResponse.success(
                WorkflowResponse.from(wf),
                "Approved: " + wf.getEntityType()
                        + "/" + wf.getEntityId()));
    }

    /**
     * Reject a pending workflow item with mandatory reason. CHECKER/ADMIN.
     * Per RBI: rejection reason is mandatory for audit trail.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<WorkflowResponse>>
            reject(@PathVariable Long id,
                    @Valid @RequestBody WorkflowActionRequest req) {
        ApprovalWorkflow wf = workflowService
                .reject(id, req.remarks());
        return ResponseEntity.ok(ApiResponse.success(
                WorkflowResponse.from(wf),
                "Rejected: " + wf.getEntityType()
                        + "/" + wf.getEntityId()));
    }

    /**
     * Trigger SLA escalation for breached workflows. ADMIN only.
     * Per Finacle SLA: escalates overdue items to ADMIN with audit trail.
     */
    @PostMapping("/escalate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<EscalationResponse>>
            escalate() {
        int count = workflowService
                .escalateBreachedWorkflows();
        return ResponseEntity.ok(ApiResponse.success(
                new EscalationResponse(count),
                count + " workflows escalated"));
    }

    // === Request DTOs ===

    public record WorkflowActionRequest(
            @NotBlank(message = "Remarks are required")
            String remarks) {}

    // === Response DTOs ===

    /**
     * Workflow item response per Finacle WFAPI.
     * Carries enough context for the CHECKER to understand what
     * they're approving without navigating to the source entity.
     */
    public record WorkflowResponse(
            Long id,
            String entityType,
            Long entityId,
            String actionType,
            String status,
            String makerUserId,
            String checkerUserId,
            String makerRemarks,
            String checkerRemarks,
            String submittedAt,
            String actionedAt,
            boolean slaBreached,
            String slaDeadline,
            int escalationCount,
            String escalatedTo) {
        static WorkflowResponse from(ApprovalWorkflow w) {
            return new WorkflowResponse(
                    w.getId(),
                    w.getEntityType(),
                    w.getEntityId(),
                    w.getActionType(),
                    w.getStatus() != null
                            ? w.getStatus().name() : null,
                    w.getMakerUserId(),
                    w.getCheckerUserId(),
                    w.getMakerRemarks(),
                    w.getCheckerRemarks(),
                    w.getSubmittedAt() != null
                            ? w.getSubmittedAt().toString()
                            : null,
                    w.getActionedAt() != null
                            ? w.getActionedAt().toString()
                            : null,
                    w.isSlaBreached(),
                    w.getSlaDeadline() != null
                            ? w.getSlaDeadline().toString()
                            : null,
                    w.getEscalationCount(),
                    w.getEscalatedTo());
        }
    }

    public record EscalationResponse(
            int escalatedCount) {}
}

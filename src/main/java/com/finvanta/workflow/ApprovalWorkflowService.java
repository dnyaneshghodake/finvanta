package com.finvanta.workflow;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.domain.enums.ApprovalStatus;
import com.finvanta.repository.ApprovalWorkflowRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApprovalWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorkflowService.class);

    private final ApprovalWorkflowRepository workflowRepository;
    private final AuditService auditService;

    public ApprovalWorkflowService(ApprovalWorkflowRepository workflowRepository,
                                    AuditService auditService) {
        this.workflowRepository = workflowRepository;
        this.auditService = auditService;
    }

    @Transactional
    public ApprovalWorkflow initiateApproval(String entityType, Long entityId,
                                              String actionType, String remarks,
                                              String payloadSnapshot) {
        String tenantId = TenantContext.getCurrentTenant();
        String makerUserId = SecurityUtil.getCurrentUsername();

        workflowRepository.findByTenantIdAndEntityTypeAndEntityIdAndStatus(
            tenantId, entityType, entityId, ApprovalStatus.PENDING_APPROVAL
        ).ifPresent(existing -> {
            throw new BusinessException("WORKFLOW_DUPLICATE",
                "A pending approval already exists for " + entityType + "/" + entityId);
        });

        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setTenantId(tenantId);
        workflow.setEntityType(entityType);
        workflow.setEntityId(entityId);
        workflow.setActionType(actionType);
        workflow.setStatus(ApprovalStatus.PENDING_APPROVAL);
        workflow.setMakerUserId(makerUserId);
        workflow.setMakerRemarks(remarks);
        workflow.setSubmittedAt(LocalDateTime.now());
        workflow.setPayloadSnapshot(payloadSnapshot);
        workflow.setCreatedBy(makerUserId);

        ApprovalWorkflow saved = workflowRepository.save(workflow);

        auditService.logEvent("ApprovalWorkflow", saved.getId(), "INITIATE",
            null, saved, "WORKFLOW",
            "Approval initiated for " + entityType + "/" + entityId + " by " + makerUserId);

        log.info("Approval initiated: entity={}/{}, action={}, maker={}",
            entityType, entityId, actionType, makerUserId);

        return saved;
    }

    @Transactional
    public ApprovalWorkflow approve(Long workflowId, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String checkerUserId = SecurityUtil.getCurrentUsername();

        ApprovalWorkflow workflow = workflowRepository.findById(workflowId)
            .filter(w -> w.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("WORKFLOW_NOT_FOUND",
                "Approval workflow not found: " + workflowId));

        if (workflow.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException("WORKFLOW_INVALID_STATE",
                "Workflow is not in PENDING_APPROVAL state. Current: " + workflow.getStatus());
        }

        if (workflow.getMakerUserId().equals(checkerUserId)) {
            throw new BusinessException("WORKFLOW_SELF_APPROVAL",
                "Maker and Checker cannot be the same person. Maker: " + workflow.getMakerUserId());
        }

        ApprovalStatus previousStatus = workflow.getStatus();
        workflow.setStatus(ApprovalStatus.APPROVED);
        workflow.setCheckerUserId(checkerUserId);
        workflow.setCheckerRemarks(remarks);
        workflow.setActionedAt(LocalDateTime.now());
        workflow.setUpdatedBy(checkerUserId);

        ApprovalWorkflow saved = workflowRepository.save(workflow);

        auditService.logEvent("ApprovalWorkflow", saved.getId(), "APPROVE",
            previousStatus.name(), saved, "WORKFLOW",
            "Approved by " + checkerUserId + " for " + workflow.getEntityType() + "/" + workflow.getEntityId());

        log.info("Approval approved: workflow={}, entity={}/{}, checker={}",
            workflowId, workflow.getEntityType(), workflow.getEntityId(), checkerUserId);

        return saved;
    }

    @Transactional
    public ApprovalWorkflow reject(Long workflowId, String remarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String checkerUserId = SecurityUtil.getCurrentUsername();

        ApprovalWorkflow workflow = workflowRepository.findById(workflowId)
            .filter(w -> w.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("WORKFLOW_NOT_FOUND",
                "Approval workflow not found: " + workflowId));

        if (workflow.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException("WORKFLOW_INVALID_STATE",
                "Workflow is not in PENDING_APPROVAL state. Current: " + workflow.getStatus());
        }

        if (workflow.getMakerUserId().equals(checkerUserId)) {
            throw new BusinessException("WORKFLOW_SELF_REJECTION",
                "Maker and Checker cannot be the same person");
        }

        ApprovalStatus previousStatus = workflow.getStatus();
        workflow.setStatus(ApprovalStatus.REJECTED);
        workflow.setCheckerUserId(checkerUserId);
        workflow.setCheckerRemarks(remarks);
        workflow.setActionedAt(LocalDateTime.now());
        workflow.setUpdatedBy(checkerUserId);

        ApprovalWorkflow saved = workflowRepository.save(workflow);

        auditService.logEvent("ApprovalWorkflow", saved.getId(), "REJECT",
            previousStatus.name(), saved, "WORKFLOW",
            "Rejected by " + checkerUserId + ": " + remarks);

        log.info("Approval rejected: workflow={}, entity={}/{}, checker={}",
            workflowId, workflow.getEntityType(), workflow.getEntityId(), checkerUserId);

        return saved;
    }

    public List<ApprovalWorkflow> getPendingApprovals() {
        String tenantId = TenantContext.getCurrentTenant();
        return workflowRepository.findByTenantIdAndStatus(tenantId, ApprovalStatus.PENDING_APPROVAL);
    }

    public List<ApprovalWorkflow> getWorkflowHistory(String entityType, Long entityId) {
        String tenantId = TenantContext.getCurrentTenant();
        return workflowRepository.findByTenantIdAndEntityTypeAndEntityId(tenantId, entityType, entityId);
    }
}

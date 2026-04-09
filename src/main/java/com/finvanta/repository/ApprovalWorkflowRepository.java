package com.finvanta.repository;

import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.domain.enums.ApprovalStatus;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalWorkflowRepository extends JpaRepository<ApprovalWorkflow, Long> {

    List<ApprovalWorkflow> findByTenantIdAndStatus(String tenantId, ApprovalStatus status);

    Optional<ApprovalWorkflow> findByTenantIdAndEntityTypeAndEntityIdAndStatus(
            String tenantId, String entityType, Long entityId, ApprovalStatus status);

    List<ApprovalWorkflow> findByTenantIdAndEntityTypeAndEntityId(String tenantId, String entityType, Long entityId);

    List<ApprovalWorkflow> findByTenantIdAndCheckerUserIdAndStatus(
            String tenantId, String checkerUserId, ApprovalStatus status);
}

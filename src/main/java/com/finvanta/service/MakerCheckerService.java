package com.finvanta.service;

import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.domain.enums.ApprovalStatus;
import com.finvanta.repository.ApprovalWorkflowRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Maker-Checker Service per Finacle TRAN_AUTH / Temenos OFS.AUTHORIZATION.
 *
 * Per RBI Internal Controls Guidelines:
 * - All financial transactions above a configurable threshold require dual authorization
 * - Maker (initiator) and Checker (approver) must be different users
 * - Transactions below threshold are auto-approved (single authorization)
 * - System-generated transactions (EOD batch) bypass maker-checker entirely
 *
 * Threshold resolution:
 *   Uses the per_transaction_limit from transaction_limits table as the maker-checker
 *   threshold. Transactions WITHIN the limit are auto-approved. Transactions ABOVE
 *   the limit require checker approval before GL posting.
 *
 * Flow for above-threshold transactions:
 *   1. TransactionEngine Step 7 calls requiresApproval() -- returns true
 *   2. Engine creates ApprovalWorkflow record with PENDING_APPROVAL status
 *   3. Engine returns TransactionResult with status=PENDING_APPROVAL (no GL posting)
 *   4. Checker reviews and calls approveTransaction()
 *   5. On approval, the original TransactionRequest is re-executed through the engine
 *      with a flag indicating it's pre-approved (skips Step 7)
 *
 * Per Finacle/Temenos: maker-checker is enforced at the transaction engine level,
 * not at the module level. This ensures uniform enforcement across all CBS modules.
 */
@Service
public class MakerCheckerService {

    private static final Logger log = LoggerFactory.getLogger(MakerCheckerService.class);

    private final ApprovalWorkflowRepository workflowRepository;
    private final TransactionLimitRepository limitRepository;

    public MakerCheckerService(
            ApprovalWorkflowRepository workflowRepository, TransactionLimitRepository limitRepository) {
        this.workflowRepository = workflowRepository;
        this.limitRepository = limitRepository;
    }

    /**
     * CBS Tier-1: Transaction types that ALWAYS require maker-checker approval
     * regardless of amount threshold. Per Finacle TRAN_AUTH / RBI Internal Controls:
     * reversals and write-offs are higher-risk than original postings because they
     * undo committed financial state. A single operator must not be able to reverse
     * a transaction unilaterally — this would enable embezzlement (post + reverse
     * to pocket the difference). Per Finacle TRAN_REVERSAL: reversal requires
     * dual authorization even for amounts below the normal threshold.
     */
    private static final Set<String> ALWAYS_REQUIRE_APPROVAL = Set.of(
            "REVERSAL", "WRITE_OFF", "WRITE_OFF_RECOVERY");

    /**
     * Determines if a transaction requires checker approval based on amount
     * and the user's role-specific per-transaction limit.
     *
     * <p>Transactions AT or BELOW the limit are auto-approved.
     * Transactions ABOVE the limit require checker approval.
     * If no limit is configured, the transaction is auto-approved (backward compatible).
     *
     * <p>Transaction types in {@link #ALWAYS_REQUIRE_APPROVAL} (REVERSAL, WRITE_OFF)
     * always return {@code true} regardless of amount — these are high-risk operations
     * that require dual authorization per RBI Internal Controls.
     *
     * @param amount          Transaction amount
     * @param transactionType Transaction type (DISBURSEMENT, REPAYMENT, REVERSAL, etc.)
     * @return true if checker approval is required
     */
    public boolean requiresApproval(BigDecimal amount, String transactionType) {
        String tenantId = TenantContext.getCurrentTenant();
        String role = SecurityUtil.getCurrentUserRole();

        if (role == null) {
            // No transactional role -- this will be caught by TransactionLimitService
            return false;
        }

        // CBS Tier-1: Reversals and write-offs ALWAYS require dual authorization
        // regardless of amount. Per Finacle TRAN_REVERSAL / RBI Internal Controls:
        // these are higher-risk operations that undo committed financial state.
        if (ALWAYS_REQUIRE_APPROVAL.contains(transactionType)) {
            log.info(
                    "MAKER_CHECKER: {} always requires approval (high-risk operation). amount={}, role={}",
                    transactionType, amount, role);
            return true;
        }

        // Resolve the per-transaction limit as the maker-checker threshold
        TransactionLimit limit = limitRepository
                .findByRoleAndType(tenantId, role, transactionType)
                .or(() -> limitRepository.findByRoleForAllTypes(tenantId, role))
                .orElse(null);

        if (limit == null || limit.getPerTransactionLimit() == null) {
            // No limit configured -- auto-approve
            return false;
        }

        boolean exceeds = amount.compareTo(limit.getPerTransactionLimit()) > 0;
        if (exceeds) {
            log.info(
                    "MAKER_CHECKER: Transaction requires approval. amount={}, limit={}, role={}, type={}",
                    amount,
                    limit.getPerTransactionLimit(),
                    role,
                    transactionType);
        }
        return exceeds;
    }

    /**
     * Creates a pending approval workflow for a transaction that exceeds
     * the maker-checker threshold.
     *
     * @param entityType      Entity type (e.g., "Transaction")
     * @param entityId        Entity ID (journal entry ID or 0 if not yet created)
     * @param actionType      Action type (e.g., "DISBURSEMENT")
     * @param payloadSnapshot JSON snapshot of the TransactionRequest for re-execution
     * @return The created workflow record
     */
    @Transactional
    public ApprovalWorkflow createPendingApproval(
            String entityType, Long entityId, String actionType, String payloadSnapshot) {
        String tenantId = TenantContext.getCurrentTenant();
        String maker = SecurityUtil.getCurrentUsername();

        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setTenantId(tenantId);
        workflow.setEntityType(entityType);
        workflow.setEntityId(entityId != null ? entityId : 0L);
        workflow.setActionType(actionType);
        workflow.setStatus(ApprovalStatus.PENDING_APPROVAL);
        workflow.setMakerUserId(maker);
        workflow.setSubmittedAt(LocalDateTime.now());
        workflow.setPayloadSnapshot(payloadSnapshot);
        workflow.setCreatedBy(maker);

        ApprovalWorkflow saved = workflowRepository.save(workflow);

        log.info(
                "Maker-checker workflow created: id={}, type={}, action={}, maker={}",
                saved.getId(),
                entityType,
                actionType,
                maker);

        return saved;
    }

    /**
     * Approves a pending transaction. Checker must be different from maker.
     *
     * @param workflowId     Workflow record ID
     * @param checkerRemarks Checker's remarks
     * @return The approved workflow record
     */
    @Transactional
    public ApprovalWorkflow approveTransaction(Long workflowId, String checkerRemarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String checker = SecurityUtil.getCurrentUsername();

        ApprovalWorkflow workflow = workflowRepository
                .findById(workflowId)
                .filter(w -> w.getTenantId().equals(tenantId))
                .orElseThrow(() ->
                        new BusinessException("WORKFLOW_NOT_FOUND", "Approval workflow not found: " + workflowId));

        if (workflow.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException(
                    "WORKFLOW_NOT_PENDING",
                    "Workflow " + workflowId + " is not pending approval. Status: " + workflow.getStatus());
        }

        // RBI: Maker and Checker must be different users
        if (checker.equals(workflow.getMakerUserId())) {
            throw new BusinessException(
                    "SAME_MAKER_CHECKER", "Maker and Checker must be different users. Both are: " + checker);
        }

        workflow.setStatus(ApprovalStatus.APPROVED);
        workflow.setCheckerUserId(checker);
        workflow.setCheckerRemarks(checkerRemarks);
        workflow.setActionedAt(LocalDateTime.now());
        workflow.setUpdatedBy(checker);

        ApprovalWorkflow saved = workflowRepository.save(workflow);

        log.info(
                "Transaction approved: workflowId={}, maker={}, checker={}",
                workflowId,
                workflow.getMakerUserId(),
                checker);

        return saved;
    }

    /**
     * Rejects a pending transaction.
     *
     * @param workflowId     Workflow record ID
     * @param checkerRemarks Rejection reason (mandatory)
     * @return The rejected workflow record
     */
    @Transactional
    public ApprovalWorkflow rejectTransaction(Long workflowId, String checkerRemarks) {
        String tenantId = TenantContext.getCurrentTenant();
        String checker = SecurityUtil.getCurrentUsername();

        if (checkerRemarks == null || checkerRemarks.isBlank()) {
            throw new BusinessException(
                    "REJECTION_REASON_REQUIRED", "Rejection reason is mandatory per CBS audit rules");
        }

        ApprovalWorkflow workflow = workflowRepository
                .findById(workflowId)
                .filter(w -> w.getTenantId().equals(tenantId))
                .orElseThrow(() ->
                        new BusinessException("WORKFLOW_NOT_FOUND", "Approval workflow not found: " + workflowId));

        if (workflow.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException(
                    "WORKFLOW_NOT_PENDING",
                    "Workflow " + workflowId + " is not pending. Status: " + workflow.getStatus());
        }

        if (checker.equals(workflow.getMakerUserId())) {
            throw new BusinessException(
                    "SAME_MAKER_CHECKER", "Maker and Checker must be different users. Both are: " + checker);
        }

        workflow.setStatus(ApprovalStatus.REJECTED);
        workflow.setCheckerUserId(checker);
        workflow.setCheckerRemarks(checkerRemarks);
        workflow.setActionedAt(LocalDateTime.now());
        workflow.setUpdatedBy(checker);

        ApprovalWorkflow saved = workflowRepository.save(workflow);

        log.info(
                "Transaction rejected: workflowId={}, maker={}, checker={}, reason={}",
                workflowId,
                workflow.getMakerUserId(),
                checker,
                checkerRemarks);

        return saved;
    }
}

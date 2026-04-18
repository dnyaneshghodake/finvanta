package com.finvanta.transaction;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.domain.enums.ApprovalStatus;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.ApprovalWorkflowRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Tier-1 Transaction Re-Execution Service per Finacle TRAN_AUTH / Temenos OFS.AUTHORIZATION.
 *
 * <p><b>Purpose:</b> When a CHECKER approves a PENDING_APPROVAL transaction workflow,
 * this service deserializes the original TransactionRequest from the workflow's
 * JSON payload and re-submits it through TransactionEngine.execute() with the
 * {@code preApproved=true} flag — bypassing Step 7 (maker-checker gate) while
 * executing ALL other validation steps (business date, limits, GL, etc.).
 *
 * <p><b>Why this exists:</b> Before this service, approving a workflow only set
 * status=APPROVED on the ApprovalWorkflow record. Nothing re-executed the
 * transaction — GL was never posted, account balance was never updated,
 * voucher was never generated. Every high-value transaction sat in
 * PENDING_APPROVAL permanently.
 *
 * <p><b>Value date handling:</b> The original value date from the maker's
 * submission is used. If the business date has changed (e.g., maker submitted
 * on Day 1, checker approves on Day 3), the engine's Step 2 (business date
 * validation) and Step 2.5 (value date window) will validate whether the
 * original date is still within the allowed window. If not, the re-execution
 * fails with INVALID_VALUE_DATE — the checker must reject and the maker
 * must re-submit with the current date.
 *
 * <p><b>Subledger update:</b> This service handles GL posting only (via the
 * engine). Module-specific subledger updates (account balance, loan outstanding)
 * must be handled by the module's approval handler. For DEPOSIT module, the
 * DepositAccountServiceImpl must check for APPROVED workflows and apply
 * the balance update. This is the same pattern as ProductMasterServiceImpl.
 *
 * @see TransactionEngine
 * @see com.finvanta.workflow.ApprovalWorkflowService
 */
@Service
public class TransactionReExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionReExecutionService.class);

    private final TransactionEngine transactionEngine;
    private final ApprovalWorkflowRepository workflowRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public TransactionReExecutionService(
            TransactionEngine transactionEngine,
            ApprovalWorkflowRepository workflowRepository,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.transactionEngine = transactionEngine;
        this.workflowRepository = workflowRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Re-executes a transaction after checker approval.
     *
     * <p>Flow:
     * <ol>
     *   <li>Validate the workflow is in APPROVED state</li>
     *   <li>Deserialize the JSON payload into a TransactionRequest</li>
     *   <li>Set preApproved=true to bypass Step 7</li>
     *   <li>Call TransactionEngine.execute()</li>
     *   <li>Mark the workflow as CONSUMED (prevent replay)</li>
     *   <li>Return the TransactionResult for module-level subledger update</li>
     * </ol>
     *
     * @param workflowId The approved workflow ID
     * @return TransactionResult from the engine (POSTED status)
     * @throws BusinessException if workflow is not APPROVED, payload is invalid, or posting fails
     */
    @Transactional
    public TransactionResult reExecuteApprovedTransaction(Long workflowId) {
        String tenantId = TenantContext.getCurrentTenant();
        String checker = SecurityUtil.getCurrentUsername();

        // Step 1: Load and validate the workflow
        ApprovalWorkflow workflow = workflowRepository.findById(workflowId)
                .filter(w -> w.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                        "WORKFLOW_NOT_FOUND", "Approval workflow not found: " + workflowId));

        if (workflow.getStatus() != ApprovalStatus.APPROVED) {
            throw new BusinessException(
                    "WORKFLOW_NOT_APPROVED",
                    "Workflow " + workflowId + " is not in APPROVED state. Current: " + workflow.getStatus());
        }

        if (!"Transaction".equals(workflow.getEntityType())) {
            throw new BusinessException(
                    "WORKFLOW_NOT_TRANSACTION",
                    "Workflow " + workflowId + " is not a Transaction workflow. Type: " + workflow.getEntityType());
        }

        String payload = workflow.getPayloadSnapshot();
        if (payload == null || payload.isBlank()) {
            throw new BusinessException(
                    "WORKFLOW_NO_PAYLOAD",
                    "Workflow " + workflowId + " has no payload snapshot — cannot re-execute.");
        }

        // Step 2: Deserialize the JSON payload into a TransactionRequest
        TransactionRequest request;
        try {
            request = deserializePayload(payload, workflow);
        } catch (Exception e) {
            throw new BusinessException(
                    "WORKFLOW_PAYLOAD_INVALID",
                    "Cannot deserialize workflow " + workflowId + " payload: " + e.getMessage());
        }

        log.info("Re-executing approved transaction: workflowId={}, type={}, amount={}, account={}, "
                + "originalMaker={}, checker={}",
                workflowId, request.getTransactionType(), request.getAmount(),
                request.getAccountReference(), workflow.getMakerUserId(), checker);

        // Step 3: Execute through the engine with preApproved=true
        TransactionResult result = transactionEngine.execute(request);

        // Step 4: Mark workflow as CONSUMED (prevent replay)
        workflow.setStatus(ApprovalStatus.CONSUMED);
        workflow.setCheckerRemarks(
                (workflow.getCheckerRemarks() != null ? workflow.getCheckerRemarks() + " | " : "")
                        + "Transaction posted: " + result.getTransactionRef()
                        + " | Voucher: " + result.getVoucherNumber());
        workflow.setUpdatedBy(checker);
        workflowRepository.save(workflow);

        // Step 5: Audit the re-execution
        auditService.logEvent(
                "Transaction",
                result.getJournalEntryId() != null ? result.getJournalEntryId() : 0L,
                "MAKER_CHECKER_POSTED",
                "APPROVED",
                result.getTransactionRef(),
                "TRANSACTION_ENGINE",
                "Maker-checker transaction posted: " + request.getTransactionType()
                        + " INR " + request.getAmount()
                        + " for " + request.getAccountReference()
                        + " | Maker: " + workflow.getMakerUserId()
                        + " | Checker: " + checker
                        + " | Journal: " + result.getJournalRef()
                        + " | Voucher: " + result.getVoucherNumber()
                        + " | WorkflowId: " + workflowId);

        log.info("Maker-checker transaction POSTED: workflowId={}, txnRef={}, voucher={}, journal={}",
                workflowId, result.getTransactionRef(), result.getVoucherNumber(), result.getJournalRef());

        return result;
    }

    /**
     * Deserializes the JSON payload from ApprovalWorkflow into a TransactionRequest.
     * Sets preApproved=true and preserves the original maker as initiatedBy.
     */
    private TransactionRequest deserializePayload(String payload, ApprovalWorkflow workflow) throws Exception {
        JsonNode root = objectMapper.readTree(payload);

        // Extract journal lines
        List<JournalLineRequest> journalLines = new ArrayList<>();
        JsonNode linesNode = root.get("journalLines");
        if (linesNode != null && linesNode.isArray()) {
            for (JsonNode lineNode : linesNode) {
                journalLines.add(new JournalLineRequest(
                        lineNode.get("glCode").asText(),
                        DebitCredit.valueOf(lineNode.get("debitCredit").asText()),
                        new BigDecimal(lineNode.get("amount").asText()),
                        lineNode.has("narration") && !lineNode.get("narration").isNull()
                                ? lineNode.get("narration").asText() : ""));
            }
        }

        if (journalLines.size() < 2) {
            throw new BusinessException("WORKFLOW_PAYLOAD_INVALID",
                    "Payload has fewer than 2 journal lines — cannot reconstruct double-entry");
        }

        // Build the request with preApproved=true
        var builder = TransactionRequest.builder()
                .sourceModule(getTextOrNull(root, "sourceModule"))
                .transactionType(getTextOrNull(root, "transactionType"))
                .accountReference(getTextOrNull(root, "accountReference"))
                .amount(new BigDecimal(root.get("amount").asText()))
                .valueDate(LocalDate.parse(root.get("valueDate").asText()))
                .branchCode(getTextOrNull(root, "branchCode"))
                .narration(getTextOrNull(root, "narration"))
                .journalLines(journalLines)
                .preApproved(true) // CBS CRITICAL: bypass Step 7 on re-execution
                .initiatedBy(workflow.getMakerUserId()); // Preserve original maker for audit

        // Optional fields
        if (root.has("productType") && !root.get("productType").isNull()) {
            builder.productType(root.get("productType").asText());
        }
        if (root.has("currencyCode") && !root.get("currencyCode").isNull()) {
            builder.currencyCode(root.get("currencyCode").asText());
        }
        // CBS: Generate a NEW idempotency key for re-execution to avoid collision
        // with the original PENDING_APPROVAL entry in the idempotency registry.
        String originalKey = getTextOrNull(root, "idempotencyKey");
        if (originalKey != null) {
            builder.idempotencyKey(originalKey + "_APPROVED");
        }

        return builder.build();
    }

    /** Safely extract a text value from a JSON node, returning null if missing. */
    private String getTextOrNull(JsonNode root, String field) {
        return root.has(field) && !root.get(field).isNull() ? root.get(field).asText() : null;
    }
}

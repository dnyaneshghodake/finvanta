package com.finvanta.controller;

import com.finvanta.cbs.modules.teller.service.TellerService;
import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.transaction.TransactionReExecutionService;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.workflow.ApprovalWorkflowService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Tier-1 Maker-Checker Workflow Controller.
 *
 * <p>Per Finacle TRAN_AUTH / Temenos OFS.AUTHORIZATION:
 * When a CHECKER approves a Transaction workflow, the controller:
 * 1. Calls ApprovalWorkflowService.approve() — sets status=APPROVED
 * 2. Calls TransactionReExecutionService.reExecuteApprovedTransaction() — posts the GL
 * 3. Reports success/failure to the checker
 *
 * <p>Non-Transaction workflows (e.g., ACCOUNT_OPENING, PRODUCT_GL_CHANGE) are
 * approved without re-execution — their consuming logic is in the respective
 * module services (DepositAccountServiceImpl, ProductMasterServiceImpl).
 */
@Controller
@RequestMapping("/workflow")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final ApprovalWorkflowService workflowService;
    private final TransactionReExecutionService reExecutionService;
    private final DepositAccountService depositService;
    private final TellerService tellerService;
    private final BusinessDateService businessDateService;
    private final ObjectMapper objectMapper;

    public WorkflowController(
            ApprovalWorkflowService workflowService,
            TransactionReExecutionService reExecutionService,
            DepositAccountService depositService,
            TellerService tellerService,
            BusinessDateService businessDateService,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.reExecutionService = reExecutionService;
        this.depositService = depositService;
        this.tellerService = tellerService;
        this.businessDateService = businessDateService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/pending")
    public ModelAndView pendingApprovals() {
        ModelAndView mav = new ModelAndView("workflow/pending");
        mav.addObject("pendingItems", workflowService.getPendingApprovals());
        return mav;
    }

    @PostMapping("/approve/{id}")
    public String approve(@PathVariable Long id,
            @RequestParam String remarks,
            @RequestParam(required = false) Long version,
            RedirectAttributes redirectAttributes) {
        try {
            // Step 1: Set workflow status to APPROVED (validates maker≠checker, status=PENDING).
            // CBS Optimistic Lock: pass JSP-supplied version for friendly mismatch error
            // (see ApprovalWorkflowService#approve(Long, Long, String) for rationale).
            // Null when the JSP has not been redeployed -- service falls back to the JPA
            // safety net.
            ApprovalWorkflow workflow = workflowService.approve(id, version, remarks);

            // Step 2: If this is a Transaction workflow, trigger GL re-execution.
            // CBS Tier-1: This is the critical link that was previously missing —
            // approved transactions must actually post to the GL.
            if ("Transaction".equals(workflow.getEntityType())) {
                try {
                    TransactionResult result = reExecutionService.reExecuteApprovedTransaction(id);

                    // CBS Tier-1: Apply subledger (account balance) update for DEPOSIT module.
                    // The GL is posted by reExecutionService; now apply the account balance effect.
                    // Parse the workflow payload to extract accountReference, amount, transactionType.
                    try {
                        String payload = workflow.getPayloadSnapshot();
                        if (payload != null && payload.startsWith("{")) {
                            JsonNode root = objectMapper.readTree(payload);
                            String sourceModule = root.has("sourceModule") ? root.get("sourceModule").asText() : null;
                            if ("DEPOSIT".equals(sourceModule)) {
                                String accountRef = root.get("accountReference").asText();
                                java.math.BigDecimal amount = new java.math.BigDecimal(root.get("amount").asText());
                                String txnType = root.get("transactionType").asText();
                                java.time.LocalDate valueDate = java.time.LocalDate.parse(root.get("valueDate").asText());
                                depositService.applyApprovedTransaction(accountRef, amount, txnType, result, valueDate);
                                log.info("Subledger applied for DEPOSIT approval: account={}, type={}, amount={}",
                                        accountRef, txnType, amount);
                            } else if ("TELLER".equals(sourceModule)) {
                                // CBS Teller module: apply the subledger effect (customer
                                // ledger + till balance) for a checker-approved teller cash
                                // deposit or withdrawal. The GL was already posted by
                                // reExecutionService above; this step mutates the subledger.
                                //
                                // The payload carries the same fields as DEPOSIT plus a
                                // "tillId" that identifies the originating till. The till
                                // balance must be incremented (deposit) or decremented
                                // (withdrawal) alongside the customer ledger.
                                //
                                // CBS NOTE (reachability): under the current engine
                                // configuration this branch is not exercised — teller cash
                                // transactions are either posted directly or hard-rejected
                                // at TransactionEngine Step 6 with TRANSACTION_LIMIT_EXCEEDED;
                                // none flow into a PENDING_APPROVAL workflow. See
                                // TellerServiceImpl class Javadoc for the deliberate design
                                // rationale. Kept wired so the path remains correct the
                                // moment the bank adds a teller-sourced type (e.g. CASH_REVERSAL)
                                // to MakerCheckerService.ALWAYS_REQUIRE_APPROVAL.
                                // The maker ≠ checker guard fires upstream in
                                // ApprovalWorkflowService.approve() — no additional check here.
                                String accountRef = root.get("accountReference").asText();
                                java.math.BigDecimal amount = new java.math.BigDecimal(root.get("amount").asText());
                                String txnType = root.get("transactionType").asText();
                                java.time.LocalDate valueDate = java.time.LocalDate.parse(root.get("valueDate").asText());
                                // Resolve the original teller's till via their username
                                // (from workflow.makerUserId) + the value date. A teller
                                // has at most one till per business date (unique index
                                // uq_till_tenant_teller_date), so this is deterministic.
                                tellerService.applyApprovedTellerTransaction(
                                        accountRef, amount, txnType,
                                        workflow.getMakerUserId(),
                                        result, valueDate);
                                log.info("Subledger applied for TELLER approval: account={}, type={}, amount={}",
                                        accountRef, txnType, amount);
                            }
                            // Future: add LOAN, CLEARING module handlers here
                        }
                    } catch (Exception subledgerError) {
                        // CBS CRITICAL: GL is already posted but subledger failed.
                        // Log as ERROR for immediate investigation. The GL↔subledger
                        // reconciliation will catch this during EOD.
                        log.error("GL posted but SUBLEDGER UPDATE FAILED for workflow {}: {}",
                                id, subledgerError.getMessage(), subledgerError);
                    }

                    redirectAttributes.addFlashAttribute("success",
                            "Approved and POSTED: Txn " + result.getTransactionRef()
                                    + " | Voucher: " + result.getVoucherNumber()
                                    + " | Journal: " + result.getJournalRef());
                    log.info("Workflow {} approved and GL posted: txnRef={}, voucher={}",
                            id, result.getTransactionRef(), result.getVoucherNumber());
                } catch (Exception postingError) {
                    // CBS: GL posting failed after approval — log the failure but keep
                    // the workflow in APPROVED state (not CONSUMED). The checker or admin
                    // can retry by re-approving or investigate the failure.
                    log.error("Workflow {} approved but GL posting FAILED: {}", id, postingError.getMessage(), postingError);
                    redirectAttributes.addFlashAttribute("error",
                            "Approved but GL posting FAILED: " + postingError.getMessage()
                                    + ". The transaction can be retried from the workflow screen.");
                }
            } else {
                // Non-transaction workflows (ACCOUNT_OPENING, PRODUCT_GL_CHANGE, etc.)
                // are consumed by their respective module services.
                redirectAttributes.addFlashAttribute("success", "Approved successfully");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/workflow/pending";
    }

    @PostMapping("/reject/{id}")
    public String reject(@PathVariable Long id,
            @RequestParam String remarks,
            @RequestParam(required = false) Long version,
            RedirectAttributes redirectAttributes) {
        try {
            // CBS Optimistic Lock: see approve() above for rationale.
            workflowService.reject(id, version, remarks);
            redirectAttributes.addFlashAttribute("success", "Rejected successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/workflow/pending";
    }
}

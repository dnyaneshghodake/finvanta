package com.finvanta.controller;

import com.finvanta.domain.entity.ApprovalWorkflow;
import com.finvanta.transaction.TransactionReExecutionService;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.workflow.ApprovalWorkflowService;

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

    public WorkflowController(
            ApprovalWorkflowService workflowService,
            TransactionReExecutionService reExecutionService) {
        this.workflowService = workflowService;
        this.reExecutionService = reExecutionService;
    }

    @GetMapping("/pending")
    public ModelAndView pendingApprovals() {
        ModelAndView mav = new ModelAndView("workflow/pending");
        mav.addObject("pendingItems", workflowService.getPendingApprovals());
        return mav;
    }

    @PostMapping("/approve/{id}")
    public String approve(@PathVariable Long id, @RequestParam String remarks, RedirectAttributes redirectAttributes) {
        try {
            // Step 1: Set workflow status to APPROVED (validates maker≠checker, status=PENDING)
            ApprovalWorkflow workflow = workflowService.approve(id, remarks);

            // Step 2: If this is a Transaction workflow, trigger GL re-execution.
            // CBS Tier-1: This is the critical link that was previously missing —
            // approved transactions must actually post to the GL.
            if ("Transaction".equals(workflow.getEntityType())) {
                try {
                    TransactionResult result = reExecutionService.reExecuteApprovedTransaction(id);
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
    public String reject(@PathVariable Long id, @RequestParam String remarks, RedirectAttributes redirectAttributes) {
        try {
            workflowService.reject(id, remarks);
            redirectAttributes.addFlashAttribute("success", "Rejected successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/workflow/pending";
    }
}

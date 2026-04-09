package com.finvanta.controller;

import com.finvanta.batch.BatchService;
import com.finvanta.batch.EodOrchestrator;
import com.finvanta.batch.EodTrialService;
import com.finvanta.batch.EodTrialService.EodCheckResult;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.service.TransactionBatchService;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS EOD Batch Controller per Finacle EOD_TRIAL + EOD_APPLY / Temenos COB_VERIFY + COB_RUN.
 *
 * Per Tier-1 CBS standards, EOD has two phases:
 *   1. TRIAL RUN (GET/POST /batch/eod/trial) — Read-only validation checklist.
 *      Shows blockers (red), warnings (amber), and passed checks (green).
 *      ADMIN reviews before proceeding to Apply.
 *   2. APPLY RUN (POST /batch/eod/apply) — Actual EOD execution.
 *      Only allowed after reviewing trial results. Blockers prevent Apply.
 *
 * Per RBI IT Governance Direction 2023 Section 7.3:
 * - EOD must have a verification step before execution
 * - All validation results must be visible to the operations team
 * - Blockers must be resolved before Apply is allowed
 */
@Controller
@RequestMapping("/batch")
public class BatchController {

    private final EodOrchestrator eodOrchestrator;
    private final BatchService batchService;
    private final EodTrialService eodTrialService;
    private final TransactionBatchService transactionBatchService;
    private final BusinessCalendarRepository calendarRepository;

    public BatchController(
            EodOrchestrator eodOrchestrator,
            BatchService batchService,
            EodTrialService eodTrialService,
            TransactionBatchService transactionBatchService,
            BusinessCalendarRepository calendarRepository) {
        this.eodOrchestrator = eodOrchestrator;
        this.batchService = batchService;
        this.eodTrialService = eodTrialService;
        this.transactionBatchService = transactionBatchService;
        this.calendarRepository = calendarRepository;
    }

    /** EOD dashboard — shows trial/apply forms and batch history. */
    @GetMapping("/eod")
    public ModelAndView eodPage() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("batch/eod");
        mav.addObject("batchHistory", batchService.getBatchHistory());
        mav.addObject(
                "currentBusinessDate",
                calendarRepository.findCurrentBusinessDate(tenantId).orElse(null));
        return mav;
    }

    /**
     * EOD Trial Run — read-only validation checklist.
     * Per Finacle EOD_TRIAL: no data is mutated. Shows checklist for ADMIN review.
     */
    @PostMapping("/eod/trial")
    public ModelAndView runTrial(@RequestParam String businessDate, RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            List<EodCheckResult> trialResults = eodTrialService.runTrial(date);
            boolean trialClean = eodTrialService.isTrialClean(trialResults);

            ModelAndView mav = new ModelAndView("batch/eod");
            mav.addObject("batchHistory", batchService.getBatchHistory());
            mav.addObject(
                    "currentBusinessDate",
                    calendarRepository.findCurrentBusinessDate(TenantContext.getCurrentTenant()).orElse(null));
            mav.addObject("trialResults", trialResults);
            mav.addObject("trialClean", trialClean);
            mav.addObject("trialDate", businessDate);
            return mav;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Trial run failed: " + e.getMessage());
            return new ModelAndView("redirect:/batch/eod");
        }
    }

    /**
     * EOD Apply Run — actual EOD execution.
     * Per Finacle EOD_APPLY: executes all EOD steps (interest, NPA, reconciliation, etc.).
     * Pre-validates batches are closed. Trial run is recommended but not strictly required
     * (ADMIN can proceed with explicit confirmation).
     */
    @PostMapping("/eod/apply")
    public String runEodApply(@RequestParam String businessDate, RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);

            // CBS: Pre-EOD validation — all intra-day batches must be closed.
            // Per Finacle/Temenos: EOD MUST NOT start if any batch is still OPEN.
            transactionBatchService.validateAllBatchesClosed(date);

            eodOrchestrator.executeEod(date);
            redirectAttributes.addFlashAttribute("success", "EOD batch completed for " + businessDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "EOD failed: " + e.getMessage());
        }
        return "redirect:/batch/eod";
    }

    /**
     * @deprecated Use /eod/trial + /eod/apply instead. Kept for backward compatibility.
     */
    @Deprecated
    @PostMapping("/eod/run")
    public String runEod(@RequestParam String businessDate, RedirectAttributes redirectAttributes) {
        return runEodApply(businessDate, redirectAttributes);
    }
}

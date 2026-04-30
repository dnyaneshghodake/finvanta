package com.finvanta.controller;

import com.finvanta.batch.EodOrchestrator;
import com.finvanta.batch.EodTrialService;
import com.finvanta.batch.EodTrialService.EodCheckResult;
import com.finvanta.cbs.modules.teller.service.TellerEodValidationService;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.TransactionBatchService;

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
    private final EodTrialService eodTrialService;
    private final TransactionBatchService transactionBatchService;
    private final TellerEodValidationService tellerEodValidationService;
    private final BusinessDateService businessDateService;

    public BatchController(
            EodOrchestrator eodOrchestrator,
            EodTrialService eodTrialService,
            TransactionBatchService transactionBatchService,
            TellerEodValidationService tellerEodValidationService,
            BusinessDateService businessDateService) {
        this.eodOrchestrator = eodOrchestrator;
        this.eodTrialService = eodTrialService;
        this.transactionBatchService = transactionBatchService;
        this.tellerEodValidationService = tellerEodValidationService;
        this.businessDateService = businessDateService;
    }

    /** EOD dashboard — shows trial/apply forms and batch history. */
    @GetMapping("/eod")
    public ModelAndView eodPage() {
        ModelAndView mav = new ModelAndView("batch/eod");
        // CBS: read batch history via EodOrchestrator (the canonical EOD service).
        // Previously went through com.finvanta.legacy.BatchService which violated
        // the ArchUnit rule legacyPackage_notDependedOnFromProduction.
        mav.addObject("batchHistory", eodOrchestrator.getBatchHistory());
        // CBS: Use getOpenDayOrNull() which returns the DAY_OPEN calendar entry.
        // The deprecated findCurrentBusinessDate() returned the MAX non-holiday date
        // where EOD is not complete — which could be April 30 instead of April 1.
        mav.addObject("currentBusinessDate", businessDateService.getOpenDayOrNull());
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
            mav.addObject("batchHistory", eodOrchestrator.getBatchHistory());
            mav.addObject("currentBusinessDate", businessDateService.getOpenDayOrNull());
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

            // CBS Tier-1: Pre-EOD validation -- teller cash custody chain must be
            // closed. Per RBI Master Circular on Cash Management at Branches §4.3:
            // every till must be CLOSED (with supervisor sign-off) and every branch
            // vault must be CLOSED before EOD generates balance snapshots and runs
            // subledger reconciliation. Otherwise the day's snapshots assert against
            // a live mid-shift cash subledger, hiding variances and breaking the
            // invariant SUM(till) + vault == GL BANK_OPERATIONS @ branch. The
            // validation runs in the canonical order (tills before vault) so the
            // operator gets the most upstream-actionable error message.
            tellerEodValidationService.validateTellerEodReadiness(date);

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

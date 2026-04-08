package com.finvanta.controller;

import com.finvanta.batch.BatchService;
import com.finvanta.batch.EodOrchestrator;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.service.TransactionBatchService;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * CBS EOD Batch Controller.
 *
 * Uses {@link EodOrchestrator} as the sole EOD entry point (Phase 2).
 * {@link BatchService#runEodBatch} is deprecated — do NOT call it from any new code.
 *
 * Pre-EOD validation: all intra-day transaction batches must be closed before EOD.
 * This is enforced by {@link TransactionBatchService#validateAllBatchesClosed}.
 */
@Controller
@RequestMapping("/batch")
public class BatchController {

    private final EodOrchestrator eodOrchestrator;
    private final BatchService batchService;
    private final TransactionBatchService transactionBatchService;
    private final BusinessCalendarRepository calendarRepository;

    public BatchController(EodOrchestrator eodOrchestrator,
                            BatchService batchService,
                            TransactionBatchService transactionBatchService,
                            BusinessCalendarRepository calendarRepository) {
        this.eodOrchestrator = eodOrchestrator;
        this.batchService = batchService;
        this.transactionBatchService = transactionBatchService;
        this.calendarRepository = calendarRepository;
    }

    @GetMapping("/eod")
    public ModelAndView eodPage() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("batch/eod");
        mav.addObject("batchHistory", batchService.getBatchHistory());
        mav.addObject("currentBusinessDate",
            calendarRepository.findCurrentBusinessDate(tenantId).orElse(null));
        return mav;
    }

    @PostMapping("/eod/run")
    public String runEod(@RequestParam String businessDate, RedirectAttributes redirectAttributes) {
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
}

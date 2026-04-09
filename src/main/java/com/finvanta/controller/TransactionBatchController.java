package com.finvanta.controller;

import com.finvanta.service.BusinessDateService;
import com.finvanta.service.TransactionBatchService;

import java.time.LocalDate;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Transaction Batch Management Controller per Finacle/Temenos standards.
 * Supports: Open batch, Close batch, View batches for a business date.
 * ADMIN-only access (enforced in SecurityConfig).
 */
@Controller
@RequestMapping("/batch/txn")
public class TransactionBatchController {

    private final TransactionBatchService batchService;
    private final BusinessDateService businessDateService;

    public TransactionBatchController(
            TransactionBatchService batchService, BusinessDateService businessDateService) {
        this.batchService = batchService;
        this.businessDateService = businessDateService;
    }

    @GetMapping("/list")
    public ModelAndView listBatches(@RequestParam(required = false) String businessDate) {
        // CBS: Default to the currently DAY_OPEN business date from the user's branch.
        // The deprecated findCurrentBusinessDate() returned MAX(date) where eodComplete=false
        // which could be April 30 instead of April 1 — showing "no batches" when OPEN
        // batches exist on the actual current business date.
        LocalDate date;
        if (businessDate != null) {
            date = LocalDate.parse(businessDate);
        } else {
            var openDay = businessDateService.getOpenDayOrNull();
            date = openDay != null ? openDay.getBusinessDate() : LocalDate.now();
        }

        ModelAndView mav = new ModelAndView("batch/txn-batches");
        mav.addObject("batches", batchService.getBatchesForDate(date));
        mav.addObject("businessDate", date);
        return mav;
    }

    @PostMapping("/open")
    public String openBatch(
            @RequestParam String businessDate,
            @RequestParam String batchName,
            @RequestParam String batchType,
            @RequestParam(required = false) Long branchId,
            RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            batchService.openBatch(date, batchName, batchType, branchId);
            redirectAttributes.addFlashAttribute("success", "Batch opened: " + batchName + " (" + batchType + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/batch/txn/list?businessDate=" + businessDate;
    }

    @PostMapping("/close/{id}")
    public String closeBatch(
            @PathVariable Long id, @RequestParam String businessDate, RedirectAttributes redirectAttributes) {
        try {
            batchService.closeBatch(id);
            redirectAttributes.addFlashAttribute("success", "Batch closed successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/batch/txn/list?businessDate=" + businessDate;
    }
}

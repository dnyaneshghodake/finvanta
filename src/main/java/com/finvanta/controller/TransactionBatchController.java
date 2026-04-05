package com.finvanta.controller;

import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.service.TransactionBatchService;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * CBS Transaction Batch Management Controller per Finacle/Temenos standards.
 * Supports: Open batch, Close batch, View batches for a business date.
 * ADMIN-only access (enforced in SecurityConfig).
 */
@Controller
@RequestMapping("/batch/txn")
public class TransactionBatchController {

    private final TransactionBatchService batchService;
    private final BusinessCalendarRepository calendarRepository;

    public TransactionBatchController(TransactionBatchService batchService,
                                       BusinessCalendarRepository calendarRepository) {
        this.batchService = batchService;
        this.calendarRepository = calendarRepository;
    }

    @GetMapping("/list")
    public ModelAndView listBatches(@RequestParam(required = false) String businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate date = businessDate != null ? LocalDate.parse(businessDate)
            : calendarRepository.findCurrentBusinessDate(tenantId)
                .map(bc -> bc.getBusinessDate()).orElse(LocalDate.now());

        ModelAndView mav = new ModelAndView("batch/txn-batches");
        mav.addObject("batches", batchService.getBatchesForDate(date));
        mav.addObject("businessDate", date);
        return mav;
    }

    @PostMapping("/open")
    public String openBatch(@RequestParam String businessDate,
                             @RequestParam String batchName,
                             @RequestParam String batchType,
                             @RequestParam(required = false) Long branchId,
                             RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            batchService.openBatch(date, batchName, batchType, branchId);
            redirectAttributes.addFlashAttribute("success",
                "Batch opened: " + batchName + " (" + batchType + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/batch/txn/list?businessDate=" + businessDate;
    }

    @PostMapping("/close/{id}")
    public String closeBatch(@PathVariable Long id,
                              @RequestParam String businessDate,
                              RedirectAttributes redirectAttributes) {
        try {
            batchService.closeBatch(id);
            redirectAttributes.addFlashAttribute("success", "Batch closed successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/batch/txn/list?businessDate=" + businessDate;
    }
}
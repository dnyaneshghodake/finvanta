package com.finvanta.controller;

import com.finvanta.batch.BatchService;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
@RequestMapping("/batch")
public class BatchController {

    private final BatchService batchService;
    private final BusinessCalendarRepository calendarRepository;

    public BatchController(BatchService batchService,
                            BusinessCalendarRepository calendarRepository) {
        this.batchService = batchService;
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
            batchService.runEodBatch(date);
            redirectAttributes.addFlashAttribute("success", "EOD batch completed for " + businessDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "EOD failed: " + e.getMessage());
        }
        return "redirect:/batch/eod";
    }
}

package com.finvanta.controller;

import com.finvanta.accounting.AccountingReconciliationEngine;
import com.finvanta.service.BusinessDateService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;

/**
 * CBS GL Reconciliation Controller.
 * Provides reconciliation report UI for CHECKER/ADMIN.
 * Per RBI audit requirements, reconciliation must be reviewed before Day Close.
 */
@Controller
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final AccountingReconciliationEngine reconciliationService;
    private final BusinessDateService businessDateService;

    public ReconciliationController(AccountingReconciliationEngine reconciliationService,
                                     BusinessDateService businessDateService) {
        this.reconciliationService = reconciliationService;
        this.businessDateService = businessDateService;
    }

    @GetMapping("/report")
    public ModelAndView reconciliationReport(@RequestParam(required = false) String businessDate) {
        LocalDate date;
        try {
            date = businessDate != null ? LocalDate.parse(businessDate)
                : businessDateService.getCurrentBusinessDate();
        } catch (Exception e) {
            date = LocalDate.now();
        }

        ModelAndView mav = new ModelAndView("reconciliation/report");
        mav.addObject("reconResult", reconciliationService.runReconciliation(date));
        mav.addObject("businessDate", date);
        return mav;
    }
}
package com.finvanta.controller;

import com.finvanta.accounting.AccountingReconciliationEngine;
import com.finvanta.batch.SubledgerReconciliationService;
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
 *
 * Two reconciliation types:
 * 1. GL vs Journal: GL master balances match sum of journal postings
 * 2. Subledger vs GL: Loan/CASA account balances match GL balances
 */
@Controller
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final AccountingReconciliationEngine reconciliationService;
    private final SubledgerReconciliationService subledgerReconciliationService;
    private final BusinessDateService businessDateService;

    public ReconciliationController(AccountingReconciliationEngine reconciliationService,
                                     SubledgerReconciliationService subledgerReconciliationService,
                                     BusinessDateService businessDateService) {
        this.reconciliationService = reconciliationService;
        this.subledgerReconciliationService = subledgerReconciliationService;
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
        // CBS Sprint 0.3: Subledger-to-GL reconciliation results
        mav.addObject("subledgerResult", subledgerReconciliationService.reconcile());
        mav.addObject("businessDate", date);
        return mav;
    }
}
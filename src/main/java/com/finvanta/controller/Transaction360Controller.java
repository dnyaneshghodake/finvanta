package com.finvanta.controller;

import com.finvanta.service.Transaction360Service;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Transaction 360 Controller per Finacle TI / Temenos TRANSACTION.360.
 *
 * Provides dedicated lookup endpoints for Loan transaction inquiry.
 * Accessible by all authenticated roles — read-only, no mutations.
 *
 * Lookup paths (Loan-specific, used by txn360/view.jsp):
 *   GET /txn360/{transactionRef}        — by transaction reference
 *   GET /txn360/voucher/{voucherNumber} — by voucher number
 *   GET /txn360/journal/{journalRef}    — by journal reference
 *
 * Unified cross-module search (CASA + Loan) is handled by
 * {@link Txn360Controller} at GET /txn360/search.
 */
@Controller
@RequestMapping("/txn360")
public class Transaction360Controller {

    private final Transaction360Service transaction360Service;

    public Transaction360Controller(Transaction360Service transaction360Service) {
        this.transaction360Service = transaction360Service;
    }

    /** Transaction 360 by transaction reference */
    @GetMapping("/{transactionRef}")
    public ModelAndView viewByTransactionRef(@PathVariable String transactionRef) {
        ModelAndView mav = new ModelAndView("txn360/view");
        mav.addAllObjects(transaction360Service.getTransaction360(transactionRef));
        mav.addObject("lookupType", "Transaction Ref");
        mav.addObject("lookupValue", transactionRef);
        return mav;
    }

    /**
     * Transaction 360 by voucher number.
     *
     * Voucher format contains slashes: VCH/branch/YYYYMMDD/seq
     * Spring MVC treats '/' as path delimiter, so we use '**' wildcard
     * and extract the full voucher from the request path.
     */
    @GetMapping("/voucher/**")
    public ModelAndView viewByVoucher(jakarta.servlet.http.HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        // Extract everything after /txn360/voucher/
        String voucherNumber = fullPath.substring((contextPath + "/txn360/voucher/").length());

        // CBS: Validate extracted voucher number to prevent path traversal.
        // Voucher format: VCH/{branchCode}/{YYYYMMDD}/{sequence}
        // Only alphanumeric, forward slashes, and hyphens are valid.
        if (voucherNumber.isEmpty() || !voucherNumber.matches("[A-Za-z0-9/_-]+") || voucherNumber.contains("..")) {
            ModelAndView mav = new ModelAndView("txn360/view");
            mav.addObject("lookupType", "Voucher");
            mav.addObject("lookupValue", "");
            return mav;
        }

        ModelAndView mav = new ModelAndView("txn360/view");
        mav.addAllObjects(transaction360Service.getByVoucher(voucherNumber));
        mav.addObject("lookupType", "Voucher");
        mav.addObject("lookupValue", voucherNumber);
        return mav;
    }

    /** Transaction 360 by journal reference */
    @GetMapping("/journal/{journalRef}")
    public ModelAndView viewByJournal(@PathVariable String journalRef) {
        ModelAndView mav = new ModelAndView("txn360/view");
        mav.addAllObjects(transaction360Service.getByJournalRef(journalRef));
        mav.addObject("lookupType", "Journal Ref");
        mav.addObject("lookupValue", journalRef);
        return mav;
    }

    // CBS: The /search endpoint has been moved to Txn360Controller which provides
    // unified cross-module search (CASA Deposit + Loan) per Finacle TRAN_INQUIRY.
    // This controller retains the dedicated lookup endpoints (/{ref}, /voucher/**,
    // /journal/{ref}) which are used by txn360/view.jsp for direct reference lookups.
    // The smartSearch method was removed to resolve the ambiguous mapping conflict
    // with Txn360Controller#search which maps the same GET /txn360/search endpoint.
}

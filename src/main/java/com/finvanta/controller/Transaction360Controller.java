package com.finvanta.controller;

import com.finvanta.service.Transaction360Service;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Transaction 360 Controller per Finacle TI / Temenos TRANSACTION.360.
 *
 * Provides a unified inquiry screen for any financial transaction.
 * Accessible by all authenticated roles — read-only, no mutations.
 *
 * Lookup paths:
 *   GET /txn360/{transactionRef}        — by transaction reference
 *   GET /txn360/voucher/{voucherNumber} — by voucher number
 *   GET /txn360/journal/{journalRef}    — by journal reference
 *   GET /txn360/search?q=...            — smart search (auto-detects type)
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
        String voucherNumber = fullPath.substring(
            (contextPath + "/txn360/voucher/").length());
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

    /**
     * Smart search — auto-detects lookup type from input prefix:
     *   TXN... → transaction ref
     *   VCH... → voucher number
     *   JRN... → journal ref
     *
     * Input is sanitized to prevent path traversal and open redirect attacks.
     * Only alphanumeric characters, hyphens, underscores, and forward slashes are allowed
     * (forward slash is needed for voucher format VCH/branch/date/seq).
     */
    @GetMapping("/search")
    public String smartSearch(@RequestParam String q) {
        String trimmed = q.trim();
        // CBS: Sanitize input to prevent path traversal / CRLF injection
        if (!trimmed.matches("[A-Za-z0-9/_-]+")) {
            return "redirect:/txn360/search";
        }
        if (trimmed.startsWith("VCH")) {
            return "redirect:/txn360/voucher/" + trimmed;
        } else if (trimmed.startsWith("JRN")) {
            return "redirect:/txn360/journal/" + trimmed;
        } else {
            return "redirect:/txn360/" + trimmed;
        }
    }
}

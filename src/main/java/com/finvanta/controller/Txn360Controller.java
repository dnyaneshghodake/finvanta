package com.finvanta.controller;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.service.Transaction360Service;
import com.finvanta.service.txn360.TxnRefResolver;
import com.finvanta.service.txn360.TxnRefResolverRegistry;
import com.finvanta.util.TenantContext;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Transaction 360 Controller per Finacle TRAN_INQUIRY / Temenos STMT.ENTRY.
 *
 * <p>Single unified controller for ALL transaction inquiry paths under
 * {@code /txn360/**}. Per Finacle/Temenos Tier-1 standards, inquiry endpoints
 * must be served by ONE controller to avoid ambiguous Spring MVC mappings and
 * to simplify cross-module traceability.
 *
 * <p>Mapped endpoints (all GET, authenticated, read-only):
 * <ul>
 *   <li>{@code GET /txn360/search?q=...}     -- Unified cross-module search (Deposit + Loan + Journal)</li>
 *   <li>{@code GET /txn360/voucher/**}       -- By voucher number (VCH/branch/YYYYMMDD/seq)</li>
 *   <li>{@code GET /txn360/journal/{ref}}    -- By journal reference (JRN...)</li>
 *   <li>{@code GET /txn360/{ref}}            -- Loan-specific transaction-ref lookup (catch-all, declared last)</li>
 * </ul>
 *
 * <p>Per RBI IT Governance Direction 2023 Section 7.4: every financial transaction
 * must be fully traceable subledger -> GL -> ledger. This controller is the
 * single entry point for that end-to-end traceability view.
 *
 * <p><b>CBS design note:</b> the {@code /{ref}} catch-all path variable MUST be
 * declared AFTER the more specific {@code /search}, {@code /voucher/**}, and
 * {@code /journal/{ref}} paths so Spring MVC's RequestMapping resolver picks the
 * specific mapping first. Consolidating the previously-split
 * {@code Transaction360Controller} lookup paths into this class eliminates the
 * ambiguous-mapping surface.
 *
 * <p>Reference-type dispatch on the unified {@code /search} endpoint is delegated
 * to a {@link TxnRefResolverRegistry} -- each {@link TxnRefResolver} claims a
 * prefix (VCH/TXN/JRN/fallback) and returns a resolution, so adding a new
 * reference family (e.g. CLR for clearing, IB for inter-branch) is a pure
 * additive change with no controller edit.
 */
@Controller
@RequestMapping("/txn360")
public class Txn360Controller {

    private final DepositTransactionRepository depositTxnRepository;
    private final LoanTransactionRepository loanTxnRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TxnRefResolverRegistry txnRefResolverRegistry;
    private final Transaction360Service transaction360Service;

    public Txn360Controller(
            DepositTransactionRepository depositTxnRepository,
            LoanTransactionRepository loanTxnRepository,
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            TxnRefResolverRegistry txnRefResolverRegistry,
            Transaction360Service transaction360Service) {
        this.depositTxnRepository = depositTxnRepository;
        this.loanTxnRepository = loanTxnRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.txnRefResolverRegistry = txnRefResolverRegistry;
        this.transaction360Service = transaction360Service;
    }

    /**
     * Unified transaction search -- resolves TXN ref, VCH number, or JRN ref to
     * the complete transaction lifecycle across CASA and Loan modules.
     */
    @GetMapping("/search")
    public ModelAndView search(@RequestParam(required = false) String q) {
        ModelAndView mav = new ModelAndView("txn360/search");
        mav.addObject("query", q != null ? q : "");

        if (q == null || q.isBlank()) {
            return mav;
        }

        String tenantId = TenantContext.getCurrentTenant();
        String ref = q.trim();

        // CBS: Delegate reference-type detection to the strategy registry. Each
        // TxnRefResolver claims a prefix (VCH/TXN/JRN/fallback) and returns a
        // resolution that the controller assembles into the view model. Adding
        // a new reference family (e.g. CLR for clearing, IB for inter-branch)
        // is a pure additive change -- publish a new TxnRefResolver bean, no
        // controller edit required. Per Finacle TRAN_INQUIRY dispatcher pattern.
        TxnRefResolver.TxnRefResolution resolution =
                txnRefResolverRegistry.resolve(tenantId, ref);

        DepositTransaction depositTxn = resolution.depositTxn();
        LoanTransaction loanTxn = resolution.loanTxn();
        JournalEntry journalEntry = resolution.journalEntry();
        List<LedgerEntry> ledgerEntries = List.of();

        // Resolve journal entry from subledger transaction
        Long journalEntryId = null;
        if (depositTxn != null) {
            mav.addObject("depositTxn", depositTxn);
            mav.addObject("sourceModule", resolution.sourceModule());
            journalEntryId = depositTxn.getJournalEntryId();
        } else if (loanTxn != null) {
            mav.addObject("loanTxn", loanTxn);
            mav.addObject("sourceModule", resolution.sourceModule());
            journalEntryId = loanTxn.getJournalEntryId();
        }

        if (journalEntryId != null && journalEntry == null) {
            journalEntry = journalEntryRepository.findById(journalEntryId).orElse(null);
        }

        // Resolve ledger entries from journal entry
        if (journalEntry != null) {
            mav.addObject("journalEntry", journalEntry);
            ledgerEntries = ledgerEntryRepository
                    .findByTenantIdAndJournalEntryIdOrderByLedgerSequenceAsc(
                            tenantId, journalEntry.getId());
        }

        if (!ledgerEntries.isEmpty()) {
            mav.addObject("ledgerEntries", ledgerEntries);
        }

        if (resolution.isEmpty()) {
            mav.addObject("error", "No transaction found for reference: " + ref);
        }

        return mav;
    }

    /**
     * Transaction 360 by voucher number.
     *
     * <p>Voucher format contains slashes: {@code VCH/{branchCode}/{YYYYMMDD}/{seq}}. Spring
     * MVC treats '/' as a path delimiter, so we use {@code /voucher/**} and extract the full
     * voucher from the request URI after the {@code /txn360/voucher/} prefix.
     *
     * <p>Per Finacle VCH_INQUIRY: the voucher register is the branch-level daily book of
     * record; every fund transfer creates two subledger legs under one voucher number.
     */
    @GetMapping("/voucher/**")
    public ModelAndView viewByVoucher(HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        String voucherNumber = fullPath.substring(
                (contextPath + "/txn360/voucher/").length());

        // CBS: Validate extracted voucher number to prevent path traversal.
        // Voucher format: VCH/{branchCode}/{YYYYMMDD}/{sequence}
        // Only alphanumeric, forward slashes, hyphens, and underscores are valid.
        ModelAndView mav = new ModelAndView("txn360/view");
        if (voucherNumber.isEmpty()
                || !voucherNumber.matches("[A-Za-z0-9/_-]+")
                || voucherNumber.contains("..")) {
            mav.addObject("lookupType", "Voucher");
            mav.addObject("lookupValue", "");
            return mav;
        }
        mav.addAllObjects(transaction360Service.getByVoucher(voucherNumber));
        mav.addObject("lookupType", "Voucher");
        mav.addObject("lookupValue", voucherNumber);
        return mav;
    }

    /**
     * Transaction 360 by journal reference (JRN...).
     * Per Finacle GL_INQUIRY: looks up a GL journal entry and resolves the linked subledger.
     */
    @GetMapping("/journal/{journalRef}")
    public ModelAndView viewByJournal(@PathVariable String journalRef) {
        ModelAndView mav = new ModelAndView("txn360/view");
        mav.addAllObjects(transaction360Service.getByJournalRef(journalRef));
        mav.addObject("lookupType", "Journal Ref");
        mav.addObject("lookupValue", journalRef);
        return mav;
    }

    /**
     * Transaction 360 by loan transaction reference.
     *
     * <p><b>CBS: this catch-all mapping MUST be declared LAST</b> so Spring MVC's
     * RequestMapping resolver picks the more specific {@code /search},
     * {@code /voucher/**}, and {@code /journal/{ref}} mappings first.
     */
    @GetMapping("/{transactionRef}")
    public ModelAndView viewByTransactionRef(@PathVariable String transactionRef) {
        ModelAndView mav = new ModelAndView("txn360/view");
        mav.addAllObjects(transaction360Service.getTransaction360(transactionRef));
        mav.addObject("lookupType", "Transaction Ref");
        mav.addObject("lookupValue", transactionRef);
        return mav;
    }
}

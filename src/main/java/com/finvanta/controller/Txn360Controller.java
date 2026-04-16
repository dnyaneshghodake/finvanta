package com.finvanta.controller;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.service.txn360.TxnRefResolver;
import com.finvanta.service.txn360.TxnRefResolverRegistry;
import com.finvanta.util.TenantContext;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Transaction 360 Controller per Finacle TRAN_INQUIRY / Temenos STMT.ENTRY.
 *
 * Provides a unified transaction lifecycle view by searching across ALL modules
 * (CASA Deposit + Loan) and all reference types:
 *   - Transaction Ref (TXN...) — subledger transaction reference
 *   - Voucher Number (VCH/...) — GL voucher for reconciliation
 *   - Journal Ref (JRN...)     — double-entry journal reference
 *
 * Per Finacle TRAN_INQUIRY: a single search box resolves any CBS reference to the
 * complete transaction lifecycle — subledger entry, GL journal, ledger postings,
 * and audit trail. This is essential for branch operations, customer dispute
 * resolution, and regulatory inspection.
 *
 * Per RBI IT Governance Direction 2023 Section 7.4:
 * Every financial transaction must be fully traceable from subledger → GL → ledger.
 */
@Controller
@RequestMapping("/txn360")
public class Txn360Controller {

    private final DepositTransactionRepository depositTxnRepository;
    private final LoanTransactionRepository loanTxnRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TxnRefResolverRegistry txnRefResolverRegistry;

    public Txn360Controller(
            DepositTransactionRepository depositTxnRepository,
            LoanTransactionRepository loanTxnRepository,
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository,
            TxnRefResolverRegistry txnRefResolverRegistry) {
        this.depositTxnRepository = depositTxnRepository;
        this.loanTxnRepository = loanTxnRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.txnRefResolverRegistry = txnRefResolverRegistry;
    }

    /**
     * Unified transaction search — resolves TXN ref, VCH number, or JRN ref
     * to the complete transaction lifecycle across CASA and Loan modules.
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
}

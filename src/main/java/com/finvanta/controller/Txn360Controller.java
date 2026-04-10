package com.finvanta.controller;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LedgerEntryRepository;
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
 * Provides a unified transaction lifecycle view by searching across all reference types:
 *   - Transaction Ref (TXN...) — subledger transaction reference
 *   - Voucher Number (VCH/...) — GL voucher for reconciliation
 *   - Journal Ref (JRN...)     — double-entry journal reference
 *
 * Per Finacle TRAN_INQUIRY: a single search box resolves any CBS reference to the
 * complete transaction lifecycle — subledger entry, GL journal, ledger postings,
 * and audit trail. This is essential for branch operations, customer dispute
 * resolution, and regulatory inspection.
 */
@Controller
@RequestMapping("/txn360")
public class Txn360Controller {

    private final DepositTransactionRepository depositTxnRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public Txn360Controller(
            DepositTransactionRepository depositTxnRepository,
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository) {
        this.depositTxnRepository = depositTxnRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * Unified transaction search — resolves TXN ref, VCH number, or JRN ref
     * to the complete transaction lifecycle.
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

        // CBS: Detect reference type by prefix and search the appropriate field.
        // Per Finacle TRAN_INQUIRY: the system auto-detects the reference type.
        DepositTransaction depositTxn = null;
        JournalEntry journalEntry = null;
        List<LedgerEntry> ledgerEntries = List.of();

        if (ref.startsWith("VCH")) {
            // Voucher Number lookup → find deposit transaction by voucher
            depositTxn = depositTxnRepository
                    .findByTenantIdAndVoucherNumber(tenantId, ref).orElse(null);
        } else if (ref.startsWith("TXN")) {
            // Transaction Ref lookup → find deposit transaction by txn ref
            depositTxn = depositTxnRepository
                    .findByTenantIdAndTransactionRef(tenantId, ref).orElse(null);
        } else if (ref.startsWith("JRN")) {
            // Journal Ref lookup → find journal entry by ref
            journalEntry = journalEntryRepository
                    .findByTenantIdAndJournalRef(tenantId, ref).orElse(null);
        } else {
            // Unknown prefix — try all three in order
            depositTxn = depositTxnRepository
                    .findByTenantIdAndTransactionRef(tenantId, ref).orElse(null);
            if (depositTxn == null) {
                depositTxn = depositTxnRepository
                        .findByTenantIdAndVoucherNumber(tenantId, ref).orElse(null);
            }
            if (depositTxn == null) {
                journalEntry = journalEntryRepository
                        .findByTenantIdAndJournalRef(tenantId, ref).orElse(null);
            }
        }

        // If we found a deposit transaction, resolve its journal entry and ledger entries
        if (depositTxn != null) {
            mav.addObject("depositTxn", depositTxn);
            if (depositTxn.getJournalEntryId() != null) {
                journalEntry = journalEntryRepository
                        .findById(depositTxn.getJournalEntryId()).orElse(null);
            }
        }

        // If we have a journal entry, resolve its ledger entries
        if (journalEntry != null) {
            mav.addObject("journalEntry", journalEntry);
            ledgerEntries = ledgerEntryRepository
                    .findByTenantIdAndJournalEntryIdOrderByLedgerSequenceAsc(
                            tenantId, journalEntry.getId());
        }

        if (!ledgerEntries.isEmpty()) {
            mav.addObject("ledgerEntries", ledgerEntries);
        }

        // If nothing found at all
        if (depositTxn == null && journalEntry == null) {
            mav.addObject("error", "No transaction found for reference: " + ref);
        }

        return mav;
    }
}
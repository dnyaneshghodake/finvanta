package com.finvanta.controller;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.LedgerEntry;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.repository.LoanTransactionRepository;
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

    public Txn360Controller(
            DepositTransactionRepository depositTxnRepository,
            LoanTransactionRepository loanTxnRepository,
            JournalEntryRepository journalEntryRepository,
            LedgerEntryRepository ledgerEntryRepository) {
        this.depositTxnRepository = depositTxnRepository;
        this.loanTxnRepository = loanTxnRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
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

        // CBS: Detect reference type by prefix and search the appropriate field.
        // Per Finacle TRAN_INQUIRY: the system auto-detects the reference type
        // and searches across ALL modules (Deposit + Loan).
        DepositTransaction depositTxn = null;
        LoanTransaction loanTxn = null;
        JournalEntry journalEntry = null;
        List<LedgerEntry> ledgerEntries = List.of();

        if (ref.startsWith("VCH")) {
            // CBS: Voucher lookup returns List because fund transfers create two subledger
            // entries (TRANSFER_DEBIT + TRANSFER_CREDIT) sharing the same voucher number.
            // Per Finacle TRAN_INQUIRY: show the first match; the full lifecycle is visible
            // via the shared journal entry which links to both legs.
            var depositVchResults = depositTxnRepository
                    .findByTenantIdAndVoucherNumber(tenantId, ref);
            depositTxn = depositVchResults.isEmpty() ? null : depositVchResults.get(0);
            if (depositTxn == null) {
                loanTxn = loanTxnRepository
                        .findByTenantIdAndVoucherNumber(tenantId, ref).orElse(null);
            }
        } else if (ref.startsWith("TXN")) {
            depositTxn = depositTxnRepository
                    .findByTenantIdAndTransactionRef(tenantId, ref).orElse(null);
            if (depositTxn == null) {
                loanTxn = loanTxnRepository
                        .findByTenantIdAndTransactionRef(tenantId, ref).orElse(null);
            }
        } else if (ref.startsWith("JRN")) {
            journalEntry = journalEntryRepository
                    .findByTenantIdAndJournalRef(tenantId, ref).orElse(null);
        } else {
            // Unknown prefix — try all in order: deposit → loan → journal
            depositTxn = depositTxnRepository
                    .findByTenantIdAndTransactionRef(tenantId, ref).orElse(null);
            if (depositTxn == null) {
                loanTxn = loanTxnRepository
                        .findByTenantIdAndTransactionRef(tenantId, ref).orElse(null);
            }
            if (depositTxn == null && loanTxn == null) {
                var depVchFallback = depositTxnRepository
                        .findByTenantIdAndVoucherNumber(tenantId, ref);
                depositTxn = depVchFallback.isEmpty() ? null : depVchFallback.get(0);
            }
            if (depositTxn == null && loanTxn == null) {
                loanTxn = loanTxnRepository
                        .findByTenantIdAndVoucherNumber(tenantId, ref).orElse(null);
            }
            if (depositTxn == null && loanTxn == null) {
                journalEntry = journalEntryRepository
                        .findByTenantIdAndJournalRef(tenantId, ref).orElse(null);
            }
        }

        // Resolve journal entry from subledger transaction
        Long journalEntryId = null;
        if (depositTxn != null) {
            mav.addObject("depositTxn", depositTxn);
            mav.addObject("sourceModule", "DEPOSIT");
            journalEntryId = depositTxn.getJournalEntryId();
        } else if (loanTxn != null) {
            mav.addObject("loanTxn", loanTxn);
            mav.addObject("sourceModule", "LOAN");
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

        // If nothing found at all
        if (depositTxn == null && loanTxn == null && journalEntry == null) {
            mav.addObject("error", "No transaction found for reference: " + ref);
        }

        return mav;
    }
}
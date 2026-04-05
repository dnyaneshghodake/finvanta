package com.finvanta.service;

import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * CBS Transaction 360 View Service per Finacle TI (Transaction Inquiry) /
 * Temenos TRANSACTION.360 standards.
 *
 * Provides a unified, read-only view of any financial transaction across
 * all CBS subsystems: subledger, GL, voucher, and audit trail.
 *
 * Per RBI audit requirements, every financial posting must be traceable
 * from any entry point (transaction ref, voucher, journal ref, account)
 * to its complete lifecycle including GL legs and audit trail.
 *
 * Lookup paths supported:
 *   1. Transaction Reference (TXN...) → direct lookup
 *   2. Voucher Number (VCH/...) → voucher register lookup
 *   3. Journal Reference (JRN...) → GL posting lookup
 *   4. Account Number → all transactions for account
 */
@Service
public class Transaction360Service {

    private final LoanTransactionRepository transactionRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LoanAccountRepository accountRepository;

    public Transaction360Service(LoanTransactionRepository transactionRepository,
                                  JournalEntryRepository journalEntryRepository,
                                  LoanAccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Builds the complete Transaction 360 view for a single transaction.
     * Returns a map containing all linked data: transaction details, account context,
     * GL posting (journal + lines), voucher, and audit trail references.
     */
    public Map<String, Object> getTransaction360(String transactionRef) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanTransaction txn = transactionRepository.findByTenantIdAndTransactionRef(tenantId, transactionRef)
            .orElseThrow(() -> new BusinessException("TRANSACTION_NOT_FOUND",
                "Transaction not found: " + transactionRef));

        return buildTransaction360(txn, tenantId);
    }

    /**
     * Lookup by voucher number — per CBS branch-level daily voucher register.
     */
    public Map<String, Object> getByVoucher(String voucherNumber) {
        String tenantId = TenantContext.getCurrentTenant();

        LoanTransaction txn = transactionRepository.findByTenantIdAndVoucherNumber(tenantId, voucherNumber)
            .orElseThrow(() -> new BusinessException("VOUCHER_NOT_FOUND",
                "No transaction found for voucher: " + voucherNumber));

        return buildTransaction360(txn, tenantId);
    }

    /**
     * Lookup by journal reference — per GL posting inquiry.
     */
    public Map<String, Object> getByJournalRef(String journalRef) {
        String tenantId = TenantContext.getCurrentTenant();

        JournalEntry journal = journalEntryRepository.findByTenantIdAndJournalRef(tenantId, journalRef)
            .orElseThrow(() -> new BusinessException("JOURNAL_NOT_FOUND",
                "Journal entry not found: " + journalRef));

        // Find the transaction linked to this journal
        List<LoanTransaction> txns = transactionRepository
            .findByTenantIdAndJournalEntryId(tenantId, journal.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journal", journal);
        result.put("journalLines", journal.getLines());

        if (!txns.isEmpty()) {
            result.putAll(buildTransaction360(txns.get(0), tenantId));
        }

        return result;
    }

    /**
     * Core builder: assembles the complete 360 view for a transaction.
     */
    private Map<String, Object> buildTransaction360(LoanTransaction txn, String tenantId) {
        Map<String, Object> view = new LinkedHashMap<>();

        // 1. Transaction Details
        view.put("transaction", txn);

        // 2. Account Context
        LoanAccount account = txn.getLoanAccount();
        view.put("account", account);
        view.put("customer", account.getCustomer());
        view.put("branch", account.getBranch());

        // 3. GL Posting (Journal + Lines)
        // For compound transactions (e.g., write-off), the primary journalEntryId points to
        // the first journal entry only. All compound journals share the same sourceModule and
        // sourceRef (account number), so we query all related journals for complete 360 view.
        if (txn.getJournalEntryId() != null) {
            journalEntryRepository.findById(txn.getJournalEntryId()).ifPresent(journal -> {
                view.put("journal", journal);
                view.put("journalLines", journal.getLines());

                // CBS Compound Journal: find all related journals for this transaction
                List<JournalEntry> relatedJournals = journalEntryRepository
                    .findByTenantIdAndSourceModuleAndSourceRef(
                        tenantId, journal.getSourceModule(), journal.getSourceRef());
                if (relatedJournals.size() > 1) {
                    view.put("compoundJournals", relatedJournals);
                }
            });
        }

        // 4. Reversal linkage
        if (txn.isReversed() && txn.getReversedByRef() != null) {
            transactionRepository.findByTenantIdAndTransactionRef(tenantId, txn.getReversedByRef())
                .ifPresent(rev -> view.put("reversalTransaction", rev));
        }

        // 5. If this IS a reversal, link to original
        if (txn.getReversedByRef() != null && !txn.isReversed()) {
            transactionRepository.findByTenantIdAndTransactionRef(tenantId, txn.getReversedByRef())
                .ifPresent(orig -> view.put("originalTransaction", orig));
        }

        return view;
    }
}

package com.finvanta.service.txn360;

import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.entity.LoanTransaction;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LoanTransactionRepository;

import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CBS Fallback resolver: probes every lookup path for references that match no
 * known prefix. Declared with the lowest order so it runs after VCH / TXN / JRN
 * strategies have all declined. Probe order: deposit txn-ref -> loan txn-ref ->
 * deposit voucher -> loan voucher -> journal ref. Preserves the legacy
 * {@code Txn360Controller} behaviour for unknown-prefix references.
 */
@Component
@Order(100)
public class FallbackRefResolver implements TxnRefResolver {

    private final DepositTransactionRepository depositTxnRepository;
    private final LoanTransactionRepository loanTxnRepository;
    private final JournalEntryRepository journalEntryRepository;

    public FallbackRefResolver(
            DepositTransactionRepository depositTxnRepository,
            LoanTransactionRepository loanTxnRepository,
            JournalEntryRepository journalEntryRepository) {
        this.depositTxnRepository = depositTxnRepository;
        this.loanTxnRepository = loanTxnRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public String prefix() {
        return "FALLBACK";
    }

    @Override
    public boolean supports(String reference) {
        return true;
    }

    @Override
    public TxnRefResolution resolve(String tenantId, String reference) {
        DepositTransaction dep = depositTxnRepository
                .findByTenantIdAndTransactionRef(tenantId, reference).orElse(null);
        if (dep != null) {
            return TxnRefResolution.deposit(dep);
        }
        LoanTransaction loan = loanTxnRepository
                .findByTenantIdAndTransactionRef(tenantId, reference).orElse(null);
        if (loan != null) {
            return TxnRefResolution.loan(loan);
        }
        List<DepositTransaction> depVch = depositTxnRepository
                .findByTenantIdAndVoucherNumber(tenantId, reference);
        if (!depVch.isEmpty()) {
            return TxnRefResolution.deposit(depVch.get(0));
        }
        LoanTransaction loanVch = loanTxnRepository
                .findByTenantIdAndVoucherNumber(tenantId, reference).orElse(null);
        if (loanVch != null) {
            return TxnRefResolution.loan(loanVch);
        }
        JournalEntry je = journalEntryRepository
                .findByTenantIdAndJournalRef(tenantId, reference).orElse(null);
        return je == null ? TxnRefResolution.empty() : TxnRefResolution.journal(je);
    }
}

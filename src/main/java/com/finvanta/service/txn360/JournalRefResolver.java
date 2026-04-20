package com.finvanta.service.txn360;

import com.finvanta.repository.JournalEntryRepository;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CBS Journal-ref resolver (JRN prefix) per Finacle GL_INQUIRY.
 */
@Component
@Order(30)
public class JournalRefResolver implements TxnRefResolver {

    private final JournalEntryRepository journalEntryRepository;

    public JournalRefResolver(JournalEntryRepository journalEntryRepository) {
        this.journalEntryRepository = journalEntryRepository;
    }

    @Override
    public String prefix() {
        return "JRN";
    }

    @Override
    public boolean supports(String reference) {
        return reference.startsWith("JRN");
    }

    @Override
    public TxnRefResolution resolve(String tenantId, String reference) {
        return journalEntryRepository
                .findByTenantIdAndJournalRef(tenantId, reference)
                .map(TxnRefResolution::journal)
                .orElseGet(TxnRefResolution::empty);
    }
}

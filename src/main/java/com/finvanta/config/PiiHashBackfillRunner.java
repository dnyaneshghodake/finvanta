package com.finvanta.config;

import com.finvanta.domain.entity.Customer;
import com.finvanta.repository.CustomerRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS PII Hash Backfill Runner per Finacle DATA_INTEGRITY_CHECK / Temenos COB.VERIFY.
 *
 * On application startup, detects customers with non-null PAN/Aadhaar but NULL
 * pan_hash/aadhaar_hash and backfills the SHA-256 hashes. This handles:
 *
 * 1. Seed data (data.sql) — raw SQL INSERT bypasses JPA lifecycle, so
 *    computePanHash()/computeAadhaarHash() are never called. Without hashes,
 *    duplicate PAN/Aadhaar detection via existsByTenantIdAndPanHash() silently
 *    fails (returns false for all checks since seed hashes are NULL).
 *
 * 2. Data migration — if pan_hash/aadhaar_hash columns were added to an
 *    existing database, all pre-existing customers need hash backfill.
 *
 * Per Finacle CIF_MASTER DATA_INTEGRITY_CHECK:
 * - Runs once at startup (idempotent — skips customers with existing hashes)
 * - Logs count of backfilled records for audit trail
 * - Does NOT modify any other customer fields (hash-only update)
 *
 * Per RBI KYC Master Direction 2016: duplicate CIF detection (one PAN = one CIF)
 * must work correctly at all times. NULL hashes defeat this critical control.
 */
@Component
public class PiiHashBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(PiiHashBackfillRunner.class);

    private final CustomerRepository customerRepo;

    public PiiHashBackfillRunner(CustomerRepository customerRepo) {
        this.customerRepo = customerRepo;
    }

    /**
     * CBS: Backfill missing PII hashes on startup.
     * Uses @EventListener(ApplicationReadyEvent) — runs AFTER all beans are initialized
     * and the application is ready to serve requests. This ensures JPA EntityManager,
     * transaction manager, and Hibernate filters are fully configured.
     *
     * Idempotent: only processes customers where hash is NULL but PII value is non-null.
     */
    /**
     * CBS: Page size for backfill processing. Keeps JVM heap bounded even with
     * millions of customers. Per Finacle BATCH_ENGINE: batch operations must
     * process in configurable page sizes to prevent OOM on large datasets.
     */
    private static final int BACKFILL_PAGE_SIZE = 500;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void backfillMissingHashes() {
        int panBackfilled = 0;
        int aadhaarBackfilled = 0;
        int pageNumber = 0;

        // CBS: Paginated processing — loads BACKFILL_PAGE_SIZE customers at a time.
        // Per Finacle DATA_INTEGRITY_CHECK / Temenos COB.VERIFY: batch operations
        // must use pagination to prevent OOM on production databases with millions
        // of customer records. Each page is processed within the same transaction
        // (Hibernate first-level cache is bounded by page size).
        Page<Customer> page;
        do {
            page = customerRepo.findAll(PageRequest.of(pageNumber, BACKFILL_PAGE_SIZE));

            for (Customer c : page.getContent()) {
                boolean updated = false;

                // Backfill PAN hash if PAN exists but hash is missing
                if (c.getPanNumber() != null && !c.getPanNumber().isBlank() && c.getPanHash() == null) {
                    c.computePanHash();
                    updated = true;
                    panBackfilled++;
                }

                // Backfill Aadhaar hash if Aadhaar exists but hash is missing
                if (c.getAadhaarNumber() != null && !c.getAadhaarNumber().isBlank()
                        && c.getAadhaarHash() == null) {
                    c.computeAadhaarHash();
                    updated = true;
                    aadhaarBackfilled++;
                }

                if (updated) {
                    customerRepo.save(c);
                }
            }

            pageNumber++;
        } while (page.hasNext());

        if (panBackfilled > 0 || aadhaarBackfilled > 0) {
            log.info("CBS PII Hash Backfill: {} PAN hashes, {} Aadhaar hashes backfilled across {} page(s)",
                    panBackfilled, aadhaarBackfilled, pageNumber);
        } else {
            log.debug("CBS PII Hash Backfill: all hashes up-to-date, no backfill needed");
        }
    }
}

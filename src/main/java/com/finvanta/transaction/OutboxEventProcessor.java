package com.finvanta.transaction;

import com.finvanta.domain.entity.Tenant;
import com.finvanta.domain.entity.TransactionOutbox;
import com.finvanta.repository.TenantRepository;
import com.finvanta.repository.TransactionOutboxRepository;
import com.finvanta.util.TenantContext;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Tier-1 Outbox Event Processor per Finacle EVENT_DISPATCHER / Temenos EB.EVENT.PROCESSOR.
 *
 * <p>Polls the {@code transaction_outbox} table for PENDING events and dispatches them
 * to downstream consumers. This is the async half of the Outbox Pattern — the sync half
 * (INSERT into outbox) happens inside TransactionEngine.executeInternal().
 *
 * <p><b>Dispatch targets:</b>
 * <ul>
 *   <li>TRANSACTION_POSTED → reconciliation trigger, notification service</li>
 *   <li>CTR_REPORTABLE → FIU-IND CTR batch reporting queue</li>
 *   <li>LARGE_VALUE → enhanced monitoring / risk dashboard</li>
 * </ul>
 *
 * <p><b>Reliability:</b> Each event is processed in its own transaction. Failed events
 * are retried up to 3 times before being marked FAILED for manual investigation.
 * The processor runs every 30 seconds via {@code @Scheduled}.
 *
 * <p><b>Multi-tenant:</b> In the current implementation, the processor runs for the
 * DEFAULT tenant. In production, it should iterate over all active tenants or use
 * a tenant-agnostic query.
 *
 * @see TransactionOutbox
 * @see TransactionEngine#publishOutboxEvent
 */
@Service
public class OutboxEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventProcessor.class);

    /** Maximum events to process per poll cycle */
    private static final int BATCH_SIZE = 100;

    /** Maximum retry attempts before marking event as FAILED */
    private static final int MAX_RETRIES = 3;

    private final TransactionOutboxRepository outboxRepository;
    private final TenantRepository tenantRepository;

    public OutboxEventProcessor(TransactionOutboxRepository outboxRepository,
                                TenantRepository tenantRepository) {
        this.outboxRepository = outboxRepository;
        this.tenantRepository = tenantRepository;
    }

    /**
     * Polls and processes PENDING outbox events every 30 seconds.
     *
     * <p>Per Finacle EVENT_DISPATCHER: the processor runs as a background daemon
     * that continuously drains the outbox queue. In production with Kafka/RabbitMQ,
     * this would be replaced by a CDC (Change Data Capture) connector.
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000) // 30s interval, 60s startup delay
    public void processOutboxEvents() {
        // CBS Tier-1: Process ALL active tenants, not just DEFAULT.
        // Per RBI PMLA: CTR-reportable events for every tenant must be dispatched
        // within 15 days. A DEFAULT-only processor would leave non-default tenants'
        // CTR events in PENDING state indefinitely — regulatory violation.
        try {
            List<Tenant> activeTenants = tenantRepository.findByActiveTrue();
            for (Tenant tenant : activeTenants) {
                try {
                    TenantContext.setCurrentTenant(tenant.getTenantCode());
                    processForTenant(tenant.getTenantCode());
                } catch (Exception e) {
                    log.debug("Outbox processor skipped for tenant {}: {}",
                            tenant.getTenantCode(), e.getMessage());
                } finally {
                    TenantContext.clear();
                }
            }
        } catch (Exception e) {
            log.debug("Outbox processor cycle skipped: {}", e.getMessage());
        }
    }

    /**
     * Process PENDING events for a specific tenant.
     */
    private void processForTenant(String tenantId) {
        List<TransactionOutbox> events = outboxRepository.findPendingEvents(
                tenantId, PageRequest.of(0, BATCH_SIZE));

        if (events.isEmpty()) return;

        int processed = 0;
        int failed = 0;

        for (TransactionOutbox event : events) {
            try {
                dispatchEvent(event);
                event.setStatus("PUBLISHED");
                event.setPublishedAt(LocalDateTime.now());
                outboxRepository.save(event);
                processed++;
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "Unknown error");
                if (event.getRetryCount() >= MAX_RETRIES) {
                    event.setStatus("FAILED");
                    log.error("Outbox event FAILED after {} retries: id={}, type={}, txnRef={}",
                            MAX_RETRIES, event.getId(), event.getEventType(), event.getTransactionRef());
                    failed++;
                }
                outboxRepository.save(event);
            }
        }

        if (processed > 0 || failed > 0) {
            log.info("Outbox processor: tenant={}, processed={}, failed={}, remaining={}",
                    tenantId, processed, failed, events.size() - processed - failed);
        }
    }

    /**
     * Dispatch a single outbox event to downstream consumers.
     *
     * <p>In the current implementation, this is a no-op logger that marks events as
     * processed. In production, this would:
     * <ul>
     *   <li>Publish to Kafka/RabbitMQ for async consumers</li>
     *   <li>Call notification service for customer SMS/email</li>
     *   <li>Queue CTR events for FIU-IND batch file generation</li>
     *   <li>Feed risk scoring engine for real-time fraud detection</li>
     * </ul>
     */
    private void dispatchEvent(TransactionOutbox event) {
        switch (event.getEventType()) {
            case "TRANSACTION_POSTED":
                log.debug("Dispatching TRANSACTION_POSTED: txnRef={}, module={}, amount={}",
                        event.getTransactionRef(), event.getSourceModule(), event.getAmount());
                // Future: publish to Kafka topic "cbs.transactions.posted"
                break;

            case "CTR_REPORTABLE":
                log.info("Dispatching CTR_REPORTABLE for FIU-IND: txnRef={}, type={}, amount={}, account={}",
                        event.getTransactionRef(), event.getTransactionType(),
                        event.getAmount(), event.getAccountReference());
                // Future: queue for FIU-IND CTR batch file generation
                // Per RBI PMLA: CTR must be filed within 15 days
                break;

            case "LARGE_VALUE":
                log.info("Dispatching LARGE_VALUE alert: txnRef={}, amount={}, account={}",
                        event.getTransactionRef(), event.getAmount(), event.getAccountReference());
                // Future: feed to risk scoring engine
                break;

            default:
                log.debug("Dispatching event: type={}, txnRef={}",
                        event.getEventType(), event.getTransactionRef());
                break;
        }
    }
}

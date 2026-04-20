package com.finvanta.repository;

import com.finvanta.domain.entity.TransactionOutbox;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Tier-1 Transaction Outbox Repository.
 * Supports the outbox pattern for reliable post-commit event publishing.
 *
 * @see com.finvanta.domain.entity.TransactionOutbox
 */
@Repository
public interface TransactionOutboxRepository extends JpaRepository<TransactionOutbox, Long> {

    /**
     * Find pending outbox events for processing.
     * Ordered by creation time (FIFO) with configurable batch size.
     */
    @Query("SELECT o FROM TransactionOutbox o WHERE o.tenantId = :tenantId AND o.status = 'PENDING' "
            + "ORDER BY o.createdAt ASC")
    List<TransactionOutbox> findPendingEvents(@Param("tenantId") String tenantId, Pageable pageable);

    /**
     * Find CTR-reportable events for FIU-IND batch reporting.
     * Per RBI PMLA: CTR must be filed within 15 days of transaction.
     */
    @Query("SELECT o FROM TransactionOutbox o WHERE o.tenantId = :tenantId "
            + "AND o.eventType = 'CTR_REPORTABLE' AND o.status = 'PENDING' "
            + "ORDER BY o.createdAt ASC")
    List<TransactionOutbox> findPendingCtrEvents(@Param("tenantId") String tenantId);

    /** Count pending events for monitoring dashboard */
    long countByTenantIdAndStatus(String tenantId, String status);
}

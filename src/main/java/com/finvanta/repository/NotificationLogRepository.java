package com.finvanta.repository;

import com.finvanta.domain.entity.NotificationLog;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Notification Log Repository per Finacle ALERT_LOG.
 */
@Repository
public interface NotificationLogRepository
        extends JpaRepository<NotificationLog, Long> {

    /** Notifications for a customer (audit trail) */
    List<NotificationLog> findByTenantIdAndCustomerIdOrderByCreatedAtDesc(
            String tenantId, Long customerId);

    /** Notifications for an account (transaction alerts) */
    List<NotificationLog> findByTenantIdAndAccountReferenceOrderByCreatedAtDesc(
            String tenantId, String accountReference);

    /** Failed notifications for retry */
    @Query("SELECT n FROM NotificationLog n "
            + "WHERE n.tenantId = :tenantId "
            + "AND n.deliveryStatus = 'FAILED' "
            + "AND n.createdAt > :since "
            + "ORDER BY n.createdAt ASC")
    List<NotificationLog> findFailedForRetry(
            @Param("tenantId") String tenantId,
            @Param("since") LocalDateTime since);

    /** Count by status for dashboard */
    @Query("SELECT n.deliveryStatus, COUNT(n) "
            + "FROM NotificationLog n "
            + "WHERE n.tenantId = :tenantId "
            + "AND n.createdAt > :since "
            + "GROUP BY n.deliveryStatus")
    List<Object[]> countByStatusSince(
            @Param("tenantId") String tenantId,
            @Param("since") LocalDateTime since);

    /** Idempotency: check if notification already sent for this txn */
    boolean existsByTenantIdAndTransactionReferenceAndChannel(
            String tenantId, String transactionReference,
            NotificationChannel channel);
}

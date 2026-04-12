package com.finvanta.repository;

import com.finvanta.domain.entity.NotificationLog;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;
import com.finvanta.domain.enums.NotificationStatus;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Notification Log Repository per Finacle ALERT_LOG.
 *
 * Provides queries for:
 * - Customer notification audit trail (RBI 8-year retention)
 * - Failed notification retry processing
 * - Delivery status dashboard metrics
 * - Idempotency checks (prevent duplicate alerts)
 * - Account-level notification history
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

    /**
     * Failed notifications eligible for retry.
     * Per RBI: financial alerts must be retried at least 3 times within 24 hours.
     * Only returns notifications with retryCount < MAX_RETRY_ATTEMPTS.
     */
    @Query("SELECT n FROM NotificationLog n "
            + "WHERE n.tenantId = :tenantId "
            + "AND n.deliveryStatus = :failedStatus "
            + "AND n.retryCount < :maxRetries "
            + "AND n.createdAt > :since "
            + "ORDER BY n.createdAt ASC")
    List<NotificationLog> findRetryableNotifications(
            @Param("tenantId") String tenantId,
            @Param("failedStatus") NotificationStatus failedStatus,
            @Param("maxRetries") int maxRetries,
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

    /** Idempotency: check if notification already sent for this txn+channel */
    boolean existsByTenantIdAndTransactionReferenceAndChannel(
            String tenantId, String transactionReference,
            NotificationChannel channel);

    /** Notifications by status for monitoring */
    List<NotificationLog>
            findByTenantIdAndDeliveryStatusOrderByCreatedAtAsc(
                    String tenantId,
                    NotificationStatus deliveryStatus);

    /** Count pending notifications (queue depth monitoring) */
    @Query("SELECT COUNT(n) FROM NotificationLog n "
            + "WHERE n.tenantId = :tenantId "
            + "AND n.deliveryStatus = :status")
    long countByStatus(
            @Param("tenantId") String tenantId,
            @Param("status") NotificationStatus status);
}

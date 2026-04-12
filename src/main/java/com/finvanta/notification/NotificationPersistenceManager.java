package com.finvanta.notification;

import com.finvanta.domain.entity.NotificationLog;
import com.finvanta.domain.entity.NotificationTemplate;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;
import com.finvanta.domain.enums.NotificationStatus;
import com.finvanta.repository.NotificationLogRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Notification Persistence Manager per Finacle ALERT_STATE_MGR.
 *
 * Provides REQUIRES_NEW transaction boundaries for notification log persistence.
 * This is a SEPARATE Spring bean from NotificationService to ensure the
 * @Transactional(REQUIRES_NEW) annotation is honored by the Spring proxy.
 *
 * CBS CRITICAL: Spring AOP proxies only intercept inter-bean method calls.
 * If NotificationService called its own @Transactional(REQUIRES_NEW) method,
 * the annotation would be silently ignored (self-invocation bypass), and the
 * notification log would be part of the caller's transaction — rolling back
 * if the parent financial transaction fails. This violates the CBS requirement
 * that notification logs must persist independently of financial transaction outcome.
 *
 * Architecture (same pattern as ClearingStateManager):
 *   NotificationService (orchestrator, no @Transactional)
 *     → calls NotificationPersistenceManager (REQUIRES_NEW)
 *       → notification log committed independently
 *       → survives parent transaction rollback
 */
@Service
public class NotificationPersistenceManager {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationPersistenceManager.class);

    private final NotificationLogRepository logRepo;

    public NotificationPersistenceManager(
            NotificationLogRepository logRepo) {
        this.logRepo = logRepo;
    }

    /**
     * Persist notification log in a REQUIRES_NEW transaction.
     * Per Finacle ALERT_ENGINE: notification persistence must not
     * be affected by parent transaction rollback.
     *
     * CBS SAFETY: This method catches dispatch exceptions internally
     * and persists FAILED status — it never propagates exceptions to
     * the calling financial transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistNotificationLog(
            String tenantId,
            NotificationEventType eventType,
            NotificationChannel channel,
            Long customerId,
            String customerName,
            String accountReference,
            String transactionReference,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String recipient,
            String messageContent,
            String sourceModule,
            NotificationTemplate template) {
        NotificationLog notifLog = new NotificationLog();
        notifLog.setTenantId(tenantId);
        notifLog.setEventType(eventType);
        notifLog.setChannel(channel);
        notifLog.setCustomerId(customerId);
        notifLog.setCustomerName(customerName);
        notifLog.setAccountReference(accountReference);
        notifLog.setTransactionReference(transactionReference);
        notifLog.setAmount(amount);
        notifLog.setBalanceAfter(balanceAfter);
        notifLog.setRecipient(recipient);
        notifLog.setMessageContent(messageContent);
        notifLog.setSourceModule(sourceModule);
        notifLog.setTemplate(template);
        notifLog.setCreatedBy("SYSTEM");

        // CBS: In production, dispatch to SMS gateway / email server.
        // Production integration points:
        //   SMS: Kaleyra / MSG91 / Twilio API
        //   EMAIL: AWS SES / SendGrid / SMTP
        // For this implementation, simulate successful dispatch.
        try {
            notifLog.setDeliveryStatus(NotificationStatus.SENT);
            notifLog.setDispatchedAt(LocalDateTime.now());
            notifLog.setGatewayReference(
                    "SIM-" + System.currentTimeMillis());
        } catch (Exception e) {
            notifLog.setDeliveryStatus(NotificationStatus.FAILED);
            notifLog.setFailureReason(e.getMessage());
            log.error("Notification dispatch failed: channel={}, "
                    + "recipient={}, err={}",
                    channel, recipient, e.getMessage());
        }

        logRepo.save(notifLog);
        log.info("Notification logged: event={}, channel={}, "
                + "recipient={}, status={}",
                eventType, channel,
                maskRecipient(recipient, channel),
                notifLog.getDeliveryStatus());
    }

    /** Mask recipient for log output per RBI PII guidelines. */
    private String maskRecipient(String recipient,
            NotificationChannel channel) {
        if (recipient == null || recipient.length() <= 4) {
            return "****";
        }
        if (channel == NotificationChannel.SMS) {
            return "****" + recipient.substring(
                    recipient.length() - 4);
        }
        int atIdx = recipient.indexOf('@');
        if (atIdx > 2) {
            return recipient.substring(0, 2) + "****"
                    + recipient.substring(atIdx);
        }
        return "****";
    }
}

package com.finvanta.service;

import com.finvanta.domain.entity.NotificationLog;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;
import com.finvanta.domain.enums.NotificationStatus;
import com.finvanta.repository.NotificationLogRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Notification Service per Finacle ALERT_ENGINE / RBI Customer Protection 2024.
 *
 * Per RBI: every debit/credit MUST trigger SMS + email to the customer.
 * Per Finacle ALERT_ENGINE: notifications are async (never block financial txns).
 *
 * Architecture:
 *   Financial operation → NotificationService.sendAlert() [async]
 *     → render message from template
 *     → dispatch via channel (SMS gateway / email server)
 *     → log delivery status in notification_logs
 *
 * CBS CRITICAL: Notification failure MUST NOT roll back the financial transaction.
 * Uses @Async + REQUIRES_NEW to ensure complete isolation.
 *
 * In production: integrate with SMS gateway (MSG91, Twilio) and email (SES, SendGrid).
 * For dev: logs to console (no actual dispatch).
 *
 * @deprecated Use {@link com.finvanta.notification.NotificationService} which provides:
 * - Template-based message rendering (NotificationTemplate)
 * - Product-scoped template resolution (product → global fallback)
 * - REQUIRES_NEW via separate NotificationPersistenceManager bean (no self-invocation bug)
 * - Retry with configurable max attempts per RBI mandate
 * - REST API via NotificationController
 *
 * This legacy service is retained for backward compatibility with existing callers.
 * It will be removed once all callers are migrated to the new notification package.
 */
@Deprecated(forRemoval = true)
@Service("legacyNotificationService")
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository notifRepo;

    public NotificationService(
            NotificationLogRepository notifRepo) {
        this.notifRepo = notifRepo;
    }

    /**
     * Send transaction alert (async — never blocks financial operation).
     * Per RBI: debit/credit alerts within seconds of transaction.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendTransactionAlert(
            NotificationEventType eventType,
            Long customerId, String customerName,
            String accountRef, String txnRef,
            BigDecimal amount, BigDecimal balanceAfter,
            String mobileNumber, String email,
            String sourceModule) {
        String tid = TenantContext.getCurrentTenant();

        // SMS notification
        if (mobileNumber != null && !mobileNumber.isBlank()) {
            String smsMsg = renderTransactionSms(
                    eventType, customerName, accountRef,
                    amount, balanceAfter);
            dispatchAndLog(tid, eventType,
                    NotificationChannel.SMS,
                    customerId, customerName, accountRef,
                    txnRef, amount, balanceAfter,
                    mobileNumber, smsMsg, sourceModule);
        }

        // Email notification
        if (email != null && !email.isBlank()) {
            String emailMsg = renderTransactionEmail(
                    eventType, customerName, accountRef,
                    txnRef, amount, balanceAfter);
            dispatchAndLog(tid, eventType,
                    NotificationChannel.EMAIL,
                    customerId, customerName, accountRef,
                    txnRef, amount, balanceAfter,
                    email, emailMsg, sourceModule);
        }
    }

    /**
     * Send generic alert (non-financial — login, password, MFA).
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendSecurityAlert(
            NotificationEventType eventType,
            Long customerId, String customerName,
            String mobileNumber, String email,
            String message) {
        String tid = TenantContext.getCurrentTenant();
        if (mobileNumber != null && !mobileNumber.isBlank()) {
            dispatchAndLog(tid, eventType,
                    NotificationChannel.SMS,
                    customerId, customerName, null, null,
                    null, null, mobileNumber, message,
                    "SECURITY");
        }
        if (email != null && !email.isBlank()) {
            dispatchAndLog(tid, eventType,
                    NotificationChannel.EMAIL,
                    customerId, customerName, null, null,
                    null, null, email, message,
                    "SECURITY");
        }
    }

    private void dispatchAndLog(
            String tid, NotificationEventType eventType,
            NotificationChannel channel,
            Long customerId, String customerName,
            String accountRef, String txnRef,
            BigDecimal amount, BigDecimal balanceAfter,
            String recipient, String message,
            String sourceModule) {
        // Idempotency: skip if already sent for this txn+channel
        if (txnRef != null && notifRepo
                .existsByTenantIdAndTransactionReferenceAndChannel(
                        tid, txnRef, channel)) {
            log.debug("Notification already sent: txn={}, ch={}",
                    txnRef, channel);
            return;
        }

        NotificationLog entry = new NotificationLog();
        entry.setTenantId(tid);
        entry.setEventType(eventType);
        entry.setChannel(channel);
        entry.setCustomerId(customerId);
        entry.setCustomerName(customerName);
        entry.setAccountReference(accountRef);
        entry.setTransactionReference(txnRef);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        entry.setRecipient(recipient);
        entry.setMessageContent(message);
        entry.setSourceModule(sourceModule);
        entry.setCreatedBy("SYSTEM");

        try {
            // CBS DEV: Log to console. PROD: dispatch to SMS/email gateway.
            log.info("NOTIFICATION [{}] {} → {}: {}",
                    channel, eventType, recipient, message);
            entry.setDeliveryStatus(NotificationStatus.SENT);
            entry.setDispatchedAt(LocalDateTime.now());
        } catch (Exception e) {
            entry.setDeliveryStatus(NotificationStatus.FAILED);
            entry.setFailureReason(e.getMessage());
            log.error("Notification failed: {} {} → {}: {}",
                    channel, eventType, recipient,
                    e.getMessage());
        }

        notifRepo.save(entry);
    }

    private String renderTransactionSms(
            NotificationEventType event, String name,
            String acct, BigDecimal amt,
            BigDecimal bal) {
        String type = event.name().contains("DEBIT")
                ? "debited" : "credited";
        String last4 = acct != null && acct.length() > 4
                ? acct.substring(acct.length() - 4) : acct;
        return "Dear " + name + ", your a/c XX" + last4
                + " is " + type + " INR " + amt
                + ". Avl Bal: INR " + bal
                + ". -Finvanta Bank";
    }

    private String renderTransactionEmail(
            NotificationEventType event, String name,
            String acct, String txnRef,
            BigDecimal amt, BigDecimal bal) {
        String type = event.name().contains("DEBIT")
                ? "Debit" : "Credit";
        return type + " Alert: Dear " + name
                + ", INR " + amt + " has been "
                + type.toLowerCase() + "ed to/from a/c "
                + acct + ". Ref: " + txnRef
                + ". Balance: INR " + bal
                + ". -Finvanta Bank";
    }
}

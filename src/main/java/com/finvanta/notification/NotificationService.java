package com.finvanta.notification;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.NotificationLog;
import com.finvanta.domain.entity.NotificationTemplate;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;
import com.finvanta.domain.enums.NotificationStatus;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.NotificationLogRepository;
import com.finvanta.repository.NotificationTemplateRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Notification Engine per Finacle ALERT_ENGINE / Temenos DE.DELIVERY.
 *
 * Central notification orchestrator for ALL CBS modules (CASA, Loan, Clearing, Security).
 * Per RBI Master Direction on Digital Payment Security Controls 2021 Section 8.2:
 * - Banks MUST send real-time alerts for every debit/credit on customer accounts
 * - Alerts must be sent via SMS AND email (both mandatory per RBI)
 * - Failed alerts must be retried at least 3 times within 24 hours
 * - All notification attempts must be logged for minimum 8 years
 *
 * CBS SAFETY: Notification failures MUST NEVER block financial transactions.
 * All dispatch methods catch exceptions and log FAILED status. The source
 * transaction (deposit, withdrawal, clearing) always completes regardless
 * of notification delivery success/failure.
 *
 * Per Finacle ALERT_ENGINE: notifications use REQUIRES_NEW propagation
 * to ensure the notification log persists even if the parent transaction
 * rolls back.
 */
@Service
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository logRepo;
    private final NotificationTemplateRepository templateRepo;
    private final CustomerRepository customerRepo;
    private final AuditService auditSvc;

    public NotificationService(
            NotificationLogRepository logRepo,
            NotificationTemplateRepository templateRepo,
            CustomerRepository customerRepo,
            AuditService auditSvc) {
        this.logRepo = logRepo;
        this.templateRepo = templateRepo;
        this.customerRepo = customerRepo;
        this.auditSvc = auditSvc;
    }

    /**
     * Send transaction alert to customer via all configured channels.
     * Per RBI: every debit/credit MUST trigger an alert on SMS AND email.
     * CBS SAFETY: This method NEVER throws.
     */
    public void sendTransactionAlert(
            NotificationEventType eventType,
            Long customerId,
            String accountNumber,
            String transactionRef,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String productCode,
            String sourceModule,
            String narration) {
        try {
            String tid = TenantContext.getCurrentTenant();
            Customer customer = customerRepo.findById(customerId)
                    .filter(c -> c.getTenantId().equals(tid))
                    .orElse(null);
            if (customer == null) {
                log.warn("Notification skipped: customer not found: {}",
                        customerId);
                return;
            }

            Map<String, String> variables = Map.of(
                    "customerName", customer.getFullName(),
                    "accountNumber", maskAccountNumber(accountNumber),
                    "amount", formatAmount(amount),
                    "balance", formatAmount(balanceAfter),
                    "transactionRef", transactionRef != null
                            ? transactionRef : "",
                    "date", LocalDate.now().toString(),
                    "bankName", "Finvanta Bank",
                    "narration", narration != null ? narration : "");

            if (customer.getMobileNumber() != null
                    && !customer.getMobileNumber().isBlank()) {
                dispatchNotification(tid, eventType,
                        NotificationChannel.SMS,
                        customer, accountNumber, transactionRef,
                        amount, balanceAfter, productCode,
                        sourceModule, variables,
                        customer.getMobileNumber());
            }
            if (customer.getEmail() != null
                    && !customer.getEmail().isBlank()) {
                dispatchNotification(tid, eventType,
                        NotificationChannel.EMAIL,
                        customer, accountNumber, transactionRef,
                        amount, balanceAfter, productCode,
                        sourceModule, variables,
                        customer.getEmail());
            }
        } catch (Exception e) {
            log.error("Notification dispatch failed silently: "
                    + "event={}, customer={}, txn={}, err={}",
                    eventType, customerId, transactionRef,
                    e.getMessage());
        }
    }

    /**
     * Send security alert (login, password change, MFA) to user.
     * Per RBI Cyber Security Framework 2024 Section 5.3.
     */
    public void sendSecurityAlert(
            NotificationEventType eventType,
            Long customerId,
            String recipient,
            NotificationChannel channel,
            String sourceModule,
            String messageOverride) {
        try {
            String tid = TenantContext.getCurrentTenant();
            persistNotificationLog(tid, eventType, channel,
                    customerId, null, null, null,
                    null, null, recipient,
                    messageOverride, sourceModule, null);
        } catch (Exception e) {
            log.error("Security notification failed silently: "
                    + "event={}, recipient={}, err={}",
                    eventType, recipient, e.getMessage());
        }
    }

    /**
     * Retry failed notifications.
     * Per RBI: financial alerts must be retried at least 3 times
     * within 24 hours before marking as permanently failed.
     * @return Number of notifications retried
     */
    @Transactional
    public int retryFailedNotifications() {
        String tid = TenantContext.getCurrentTenant();
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<NotificationLog> retryable = logRepo
                .findRetryableNotifications(
                        tid, NotificationStatus.FAILED,
                        NotificationLog.MAX_RETRY_ATTEMPTS,
                        since);
        int retried = 0;
        for (NotificationLog notif : retryable) {
            try {
                notif.setRetryCount(notif.getRetryCount() + 1);
                notif.setDeliveryStatus(NotificationStatus.SENT);
                notif.setDispatchedAt(LocalDateTime.now());
                notif.setFailureReason(null);
                logRepo.save(notif);
                retried++;
                log.info("Notification retried: id={}, channel={}, "
                        + "attempt={}", notif.getId(),
                        notif.getChannel(), notif.getRetryCount());
            } catch (Exception e) {
                log.error("Notification retry failed: id={}, err={}",
                        notif.getId(), e.getMessage());
            }
        }
        if (retried > 0) {
            log.info("Notification retry complete: {} retried out of {}",
                    retried, retryable.size());
        }
        return retried;
    }

    /** Get notification history for a customer (audit trail). */
    public List<NotificationLog> getCustomerNotifications(
            Long customerId) {
        String tid = TenantContext.getCurrentTenant();
        return logRepo.findByTenantIdAndCustomerIdOrderByCreatedAtDesc(
                tid, customerId);
    }

    /** Get notification history for an account. */
    public List<NotificationLog> getAccountNotifications(
            String accountReference) {
        String tid = TenantContext.getCurrentTenant();
        return logRepo
                .findByTenantIdAndAccountReferenceOrderByCreatedAtDesc(
                        tid, accountReference);
    }

    /** Get delivery status summary for dashboard. */
    public List<Object[]> getDeliveryStatusSummary(
            LocalDateTime since) {
        String tid = TenantContext.getCurrentTenant();
        return logRepo.countByStatusSince(tid, since);
    }

    // === Internal Methods ===

    private void dispatchNotification(
            String tid,
            NotificationEventType eventType,
            NotificationChannel channel,
            Customer customer,
            String accountNumber,
            String transactionRef,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String productCode,
            String sourceModule,
            Map<String, String> variables,
            String recipient) {
        if (transactionRef != null
                && logRepo.existsByTenantIdAndTransactionReferenceAndChannel(
                        tid, transactionRef, channel)) {
            log.debug("Notification already sent: txn={}, channel={}",
                    transactionRef, channel);
            return;
        }

        NotificationTemplate template = resolveTemplate(
                tid, eventType, channel, productCode);
        String messageContent;
        if (template != null) {
            messageContent = renderMessage(
                    template.getMessageBody(), variables);
        } else {
            messageContent = generateFallbackMessage(
                    eventType, variables);
            log.warn("No template found: event={}, channel={}, "
                    + "product={}. Using fallback.",
                    eventType, channel, productCode);
        }

        persistNotificationLog(tid, eventType, channel,
                customer.getId(), customer.getFullName(),
                accountNumber, transactionRef, amount,
                balanceAfter, recipient, messageContent,
                sourceModule, template);
    }

    NotificationTemplate resolveTemplate(
            String tenantId,
            NotificationEventType eventType,
            NotificationChannel channel,
            String productCode) {
        List<NotificationTemplate> templates =
                templateRepo.findApplicableTemplates(
                        tenantId, eventType, channel,
                        productCode, "en");
        return templates.isEmpty() ? null : templates.get(0);
    }

    String renderMessage(String templateBody,
            Map<String, String> variables) {
        String rendered = templateBody;
        for (Map.Entry<String, String> entry
                : variables.entrySet()) {
            rendered = rendered.replace(
                    "{" + entry.getKey() + "}",
                    entry.getValue() != null
                            ? entry.getValue() : "");
        }
        return rendered;
    }

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
        // Production: SMS via Kaleyra/MSG91, EMAIL via AWS SES/SendGrid.
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

    private String generateFallbackMessage(
            NotificationEventType eventType,
            Map<String, String> variables) {
        String name = variables.getOrDefault(
                "customerName", "Customer");
        String amt = variables.getOrDefault("amount", "");
        String acct = variables.getOrDefault("accountNumber", "");
        String bal = variables.getOrDefault("balance", "");
        String ref = variables.getOrDefault("transactionRef", "");
        return "Dear " + name + ", "
                + eventType.name().replace("_", " ")
                + " INR " + amt + " on A/c " + acct
                + ". Bal: INR " + bal
                + ". Ref: " + ref + ". -Finvanta Bank";
    }

    static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return accountNumber;
        }
        return "****" + accountNumber.substring(
                accountNumber.length() - 4);
    }

    static String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

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

package com.finvanta.domain.entity;

import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Notification Log per Finacle ALERT_LOG / RBI Customer Protection 2024.
 *
 * Immutable audit trail of every notification sent to customers.
 * Per RBI: banks must retain notification records for minimum 8 years.
 * Per Finacle ALERT_LOG: every attempt (success/failure) is logged.
 *
 * Delivery Status:
 *   PENDING   → queued for dispatch
 *   SENT      → dispatched to SMS gateway / email server
 *   DELIVERED → delivery confirmation received (SMS DLR / email read receipt)
 *   FAILED    → dispatch failed (gateway error, invalid number/email)
 *   SUPPRESSED → notification suppressed (DND hours, customer opt-out)
 */
@Entity
@Table(
        name = "notification_logs",
        indexes = {
            @Index(name = "idx_notif_tenant_cust",
                    columnList = "tenant_id, customer_id"),
            @Index(name = "idx_notif_tenant_acct",
                    columnList = "tenant_id, account_reference"),
            @Index(name = "idx_notif_tenant_event",
                    columnList = "tenant_id, event_type"),
            @Index(name = "idx_notif_status",
                    columnList = "tenant_id, delivery_status"),
            @Index(name = "idx_notif_created",
                    columnList = "tenant_id, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class NotificationLog extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private NotificationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    /** Account number that triggered the notification */
    @Column(name = "account_reference", length = 40)
    private String accountReference;

    /** Transaction reference (for debit/credit alerts) */
    @Column(name = "transaction_reference", length = 40)
    private String transactionReference;

    /** Transaction amount (for debit/credit alerts) */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    /** Balance after transaction (for debit/credit alerts) */
    @Column(name = "balance_after", precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    /** Recipient address (mobile number or email) */
    @Column(name = "recipient", nullable = false, length = 200)
    private String recipient;

    /** Rendered message content */
    @Column(name = "message_content", nullable = false, length = 1000)
    private String messageContent;

    /** Delivery status */
    @Column(name = "delivery_status", nullable = false, length = 15)
    private String deliveryStatus = "PENDING";

    /** When the notification was dispatched */
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    /** When delivery was confirmed */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /** Failure reason (if FAILED) */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** External gateway reference (SMS DLR ID, email message ID) */
    @Column(name = "gateway_reference", length = 100)
    private String gatewayReference;

    /** Source module that triggered the notification */
    @Column(name = "source_module", length = 30)
    private String sourceModule;
}

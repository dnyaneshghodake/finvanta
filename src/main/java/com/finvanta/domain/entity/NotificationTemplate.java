package com.finvanta.domain.entity;

import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Notification Template per Finacle ALERT_TEMPLATE / Temenos DE.FORMAT.
 *
 * Defines the message template for each notification event type and channel.
 * Templates use placeholder variables that are resolved at runtime:
 *   {customerName}   → Customer full name
 *   {accountNumber}  → Account number (masked: ****1234)
 *   {amount}         → Transaction amount (formatted with INR symbol)
 *   {balance}        → Balance after transaction
 *   {transactionRef} → Transaction reference number
 *   {date}           → CBS business date
 *   {bankName}       → Bank name from tenant config
 *   {narration}      → Transaction narration/purpose
 *
 * Per RBI Master Direction on Digital Payment Security Controls 2021 §8.2:
 * - Transaction alerts MUST include: amount, account (masked), balance, date, reference
 * - Account numbers must be partially masked (show only last 4 digits)
 * - Templates must be pre-approved by compliance before activation
 *
 * Per Finacle ALERT_TEMPLATE: templates are tenant-scoped and product-scoped.
 * A product-specific template (productCode != null) takes precedence over
 * a global template (productCode = null) for the same event+channel.
 *
 * Per TRAI DND Regulations 2018: SMS templates must be registered with
 * the telecom operator's DLT (Distributed Ledger Technology) platform.
 * The dltTemplateId field stores the DLT registration ID.
 */
@Entity
@Table(
        name = "notification_templates",
        indexes = {
            @Index(name = "idx_notiftpl_tenant_event_channel",
                    columnList = "tenant_id, event_type, channel"),
            @Index(name = "idx_notiftpl_tenant_product_event",
                    columnList = "tenant_id, product_code, event_type, channel")
        })
@Getter
@Setter
@NoArgsConstructor
public class NotificationTemplate extends BaseEntity {

    /** Notification event this template applies to */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private NotificationEventType eventType;

    /** Delivery channel this template is for */
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private NotificationChannel channel;

    /**
     * Product code this template applies to. Null = applies to ALL products.
     * Per Finacle ALERT_TEMPLATE: product-level overrides take precedence.
     */
    @Column(name = "product_code", length = 50)
    private String productCode;

    /** Human-readable template name for admin UI */
    @Column(name = "template_name", nullable = false, length = 200)
    private String templateName;

    /**
     * Message body template with placeholder variables.
     * Placeholders: {customerName}, {accountNumber}, {amount}, {balance},
     * {transactionRef}, {date}, {bankName}, {narration}
     *
     * Example SMS: "Dear {customerName}, INR {amount} debited from A/c {accountNumber}.
     * Balance: INR {balance}. Ref: {transactionRef}. -Finvanta Bank"
     */
    @Column(name = "message_body", nullable = false, length = 1000)
    private String messageBody;

    /**
     * Email subject template (only for EMAIL channel, null for SMS/PUSH).
     * Supports same placeholder variables as messageBody.
     */
    @Column(name = "subject_template", length = 300)
    private String subjectTemplate;

    /**
     * DLT Template ID for SMS registration per TRAI DND Regulations 2018.
     * Every SMS template must be registered on the telecom operator's DLT
     * platform before it can be sent. Null for EMAIL/PUSH channels.
     */
    @Column(name = "dlt_template_id", length = 30)
    private String dltTemplateId;

    /** Whether this template is active */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Language code per ISO 639-1 (en, hi, mr, ta, etc.) */
    @Column(name = "language_code", nullable = false, length = 5)
    private String languageCode = "en";
}

package com.finvanta.domain.enums;

/**
 * CBS Notification Delivery Channel per Finacle ALERT_MASTER / RBI Customer Protection 2024.
 *
 * Per RBI: banks MUST offer SMS and email alerts. Push notifications are optional.
 * Per Finacle ALERT_CONFIG: each customer can opt-in/out per channel.
 */
public enum NotificationChannel {
    /** SMS to registered mobile number (mandatory per RBI) */
    SMS,
    /** Email to registered email address (mandatory per RBI) */
    EMAIL,
    /** Mobile app push notification (optional, future) */
    PUSH;
}

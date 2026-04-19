package com.finvanta.api;

import com.finvanta.domain.entity.NotificationLog;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;
import com.finvanta.notification.NotificationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Notification REST API per Finacle ALERT_API / Temenos DE.MESSAGE.
 *
 * Thin orchestration layer over NotificationService.
 * Per Finacle API standards: request DTOs, response DTOs, role-based access.
 *
 * CBS Role Matrix for Notifications:
 *   MAKER   → send transaction alerts (via CASA/Loan/Clearing modules)
 *   CHECKER → view notification history, retry failed
 *   ADMIN   → all operations + template management + retry
 */
@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(
            NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Send a transaction alert manually. MAKER/ADMIN. */
    @PostMapping("/send")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<String>>
            sendTransactionAlert(
                    @Valid @RequestBody SendAlertRequest req) {
        notificationService.sendTransactionAlert(
                NotificationEventType.valueOf(req.eventType()),
                req.customerId(),
                req.accountNumber(),
                req.transactionRef(),
                req.amount(),
                req.balanceAfter(),
                req.productCode(),
                req.sourceModule(),
                req.narration());
        return ResponseEntity.ok(ApiResponse.success(
                "Notification dispatched",
                "Alert sent for " + req.eventType()));
    }

    /** Retry failed notifications. ADMIN only. */
    @PostMapping("/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<RetryResponse>>
            retryFailed() {
        int retried = notificationService
                .retryFailedNotifications();
        return ResponseEntity.ok(ApiResponse.success(
                new RetryResponse(retried),
                retried + " notifications retried"));
    }

    /** Get notification history for a customer. */
    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<NotifLogResponse>>>
            getCustomerNotifications(
                    @PathVariable Long customerId) {
        var logs = notificationService
                .getCustomerNotifications(customerId);
        var items = logs.stream()
                .map(NotifLogResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    /** Get notification history for an account. */
    @GetMapping("/account/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<NotifLogResponse>>>
            getAccountNotifications(
                    @PathVariable String accountNumber) {
        var logs = notificationService
                .getAccountNotifications(accountNumber);
        var items = logs.stream()
                .map(NotifLogResponse::from).toList();
        return ResponseEntity.ok(
                ApiResponse.success(items));
    }

    /** Get delivery status summary for dashboard. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<Object[]>>>
            getDeliveryStatusSummary(
                    @RequestParam(defaultValue = "24")
                    int hoursBack) {
        LocalDateTime since = LocalDateTime.now()
                .minusHours(hoursBack);
        var summary = notificationService
                .getDeliveryStatusSummary(since);
        return ResponseEntity.ok(
                ApiResponse.success(summary));
    }

    // === Request DTOs ===

    public record SendAlertRequest(
            @NotBlank String eventType,
            @NotNull Long customerId,
            @NotBlank String accountNumber,
            String transactionRef,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String productCode,
            @NotBlank String sourceModule,
            String narration) {}

    // === Response DTOs ===

    public record RetryResponse(int retriedCount) {}

    public record NotifLogResponse(
            Long id,
            String eventType,
            String channel,
            Long customerId,
            String customerName,
            String accountReference,
            String transactionReference,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String recipient,
            String messageContent,
            String deliveryStatus,
            String dispatchedAt,
            String deliveredAt,
            String failureReason,
            String gatewayReference,
            String sourceModule,
            int retryCount,
            String createdAt) {
        static NotifLogResponse from(NotificationLog n) {
            return new NotifLogResponse(
                    n.getId(),
                    n.getEventType().name(),
                    n.getChannel().name(),
                    n.getCustomerId(),
                    n.getCustomerName(),
                    n.getAccountReference(),
                    n.getTransactionReference(),
                    n.getAmount(),
                    n.getBalanceAfter(),
                    maskRecipient(n.getRecipient(),
                            n.getChannel()),
                    n.getMessageContent(),
                    n.getDeliveryStatus().name(),
                    n.getDispatchedAt() != null
                            ? n.getDispatchedAt().toString()
                            : null,
                    n.getDeliveredAt() != null
                            ? n.getDeliveredAt().toString()
                            : null,
                    n.getFailureReason(),
                    n.getGatewayReference(),
                    n.getSourceModule(),
                    n.getRetryCount(),
                    n.getCreatedAt() != null
                            ? n.getCreatedAt().toString()
                            : null);
        }

        /** Mask recipient per RBI: never expose full mobile/email in API */
        private static String maskRecipient(
                String recipient,
                NotificationChannel channel) {
            if (recipient == null
                    || recipient.length() <= 4) {
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
}

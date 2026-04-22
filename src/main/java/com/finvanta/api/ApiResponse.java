package com.finvanta.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

import org.slf4j.MDC;

/**
 * CBS Tier-1 API Response Envelope per Finacle API / Temenos IRIS / FLEXCUBE REST.
 *
 * <p>Every API response follows this structure regardless of success/failure.
 * Per RBI IT Governance Direction 2023 §8.5: API responses must include
 * timestamps, correlation IDs, and API version for audit trail traceability.
 *
 * <h3>Tier-1 CBS Envelope Contract:</h3>
 * <pre>
 * Success:
 * {
 *   "status": "SUCCESS",
 *   "data": {...},
 *   "message": "Account opened in PENDING_ACTIVATION",
 *   "meta": {
 *     "apiVersion": "v1",
 *     "correlationId": "550e8400-e29b-41d4-a716-446655440000",
 *     "timestamp": "2026-04-20T10:30:00"
 *   }
 * }
 *
 * Error:
 * {
 *   "status": "ERROR",
 *   "error": {
 *     "code": "CBS-ACCT-007",
 *     "message": "Insufficient account balance",
 *     "severity": "HIGH",
 *     "action": "Verify available balance before initiating transfer"
 *   },
 *   "meta": {
 *     "apiVersion": "v1",
 *     "correlationId": "550e8400-e29b-41d4-a716-446655440000",
 *     "timestamp": "2026-04-20T10:30:00"
 *   }
 * }
 * </pre>
 *
 * <p>Per ISO 20022 alignment: error codes follow {@code CBS-MODULE-NNN} format
 * for machine-readable classification. The {@code severity} field enables the
 * Next.js BFF to render appropriate UI treatment (toast vs modal vs block).
 * The {@code action} field provides remediation guidance per RBI Fair Practices Code.
 *
 * <p><b>Backward compatibility:</b> The legacy flat fields ({@code errorCode},
 * {@code message}, {@code timestamp}) are retained alongside the new structured
 * {@code error} and {@code meta} objects. Existing BFF clients that read
 * {@code response.errorCode} continue to work; new clients should prefer
 * {@code response.error.code} and {@code response.meta.correlationId}.
 *
 * @param <T> Type of the response data payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** API version constant — incremented only on breaking contract changes. */
    private static final String API_VERSION = "v1";

    private final String status;
    private final T data;
    private final String errorCode;
    private final String message;
    private final LocalDateTime timestamp;
    private final ErrorDetail error;
    private final Meta meta;

    private ApiResponse(String status, T data, String errorCode, String message,
            ErrorDetail error) {
        this.status = status;
        this.data = data;
        this.errorCode = errorCode;
        this.message = message;
        this.timestamp = LocalDateTime.now();
        this.error = error;
        this.meta = Meta.capture();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", data, null, null, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("SUCCESS", data, null, message, null);
    }

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>("ERROR", null, errorCode, message,
                new ErrorDetail(errorCode, message, null, null));
    }

    /**
     * CBS Tier-1 error with severity and remediation action.
     *
     * <p>Per RBI Fair Practices Code 2023: error responses to customers must
     * include actionable guidance. The {@code severity} enables the BFF to
     * render appropriate UI treatment:
     * <ul>
     *   <li>{@code LOW} — informational toast, auto-dismiss</li>
     *   <li>{@code MEDIUM} — warning modal, user acknowledges</li>
     *   <li>{@code HIGH} — blocking error, requires corrective action</li>
     *   <li>{@code CRITICAL} — system-level, contact support</li>
     * </ul>
     */
    public static <T> ApiResponse<T> error(String errorCode, String message,
            String severity, String action) {
        return new ApiResponse<>("ERROR", null, errorCode, message,
                new ErrorDetail(errorCode, message, severity, action));
    }

    /**
     * CBS error with machine-readable data payload (e.g. MFA_REQUIRED
     * returns the opaque challengeId so the Next.js BFF can resume the
     * original action after step-up verification). Per RBI IT Governance
     * Direction 2023 §8.5: error envelopes MAY carry additional structured
     * fields provided they contain no PII.
     */
    public static <T> ApiResponse<T> errorWithData(String errorCode, String message, T data) {
        return new ApiResponse<>("ERROR", data, errorCode, message,
                new ErrorDetail(errorCode, message, null, null));
    }

    public String getStatus() {
        return status;
    }

    public T getData() {
        return data;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public ErrorDetail getError() {
        return error;
    }

    public Meta getMeta() {
        return meta;
    }

    /**
     * CBS Tier-1 Structured Error Detail per Finacle API / ISO 20022 alignment.
     *
     * <p>Error code format: {@code CBS-MODULE-NNN} where MODULE is the CBS domain
     * (CUST, ACCT, TXN, AUTH, GL, LOAN, FD, WF) and NNN is a sequential number.
     * Legacy flat error codes (e.g. {@code ACCOUNT_NOT_FOUND}) are preserved in
     * the parent {@code errorCode} field for backward compatibility.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            String severity,
            String action) {}

    /**
     * CBS Tier-1 Response Metadata per RBI IT Governance Direction 2023 §8.5.
     *
     * <p>Captures the API version, correlation ID (from MDC, set by
     * {@link com.finvanta.config.CorrelationIdMdcFilter}), and timestamp
     * at response construction time. The correlation ID enables end-to-end
     * traceability from BFF → CBS API → audit log → SIEM.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(
            String apiVersion,
            String correlationId,
            LocalDateTime timestamp) {

        /**
         * Captures metadata from the current request context.
         * Reads correlationId from SLF4J MDC (set by CorrelationIdMdcFilter).
         */
        static Meta capture() {
            String correlationId = MDC.get("correlationId");
            return new Meta(API_VERSION, correlationId, LocalDateTime.now());
        }
    }
}

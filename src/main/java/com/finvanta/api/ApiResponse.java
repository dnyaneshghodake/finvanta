package com.finvanta.api;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * CBS Standardized API Response Envelope per Finacle API / Temenos IRIS.
 *
 * Every API response follows this structure regardless of success/failure.
 * Per RBI IT Governance Direction 2023 §8.5: API responses must include
 * timestamps and correlation IDs for audit trail traceability.
 *
 * Success: { status: "SUCCESS", data: {...}, timestamp: "..." }
 * Error:   { status: "ERROR", errorCode: "...", message: "...", timestamp: "..." }
 *
 * @param <T> Type of the response data payload
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final String status;
    private final T data;
    private final String errorCode;
    private final String message;
    private final LocalDateTime timestamp;

    private ApiResponse(String status, T data,
            String errorCode, String message) {
        this.status = status;
        this.data = data;
        this.errorCode = errorCode;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", data,
                null, null);
    }

    public static <T> ApiResponse<T> success(
            T data, String message) {
        return new ApiResponse<>("SUCCESS", data,
                null, message);
    }

    public static <T> ApiResponse<T> error(
            String errorCode, String message) {
        return new ApiResponse<>("ERROR", null,
                errorCode, message);
    }

    public String getStatus() { return status; }
    public T getData() { return data; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
}

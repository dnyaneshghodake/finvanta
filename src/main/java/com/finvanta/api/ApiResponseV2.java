package com.finvanta.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CBS Enhanced API Response for React + Next.js Frontend
 *
 * Per RBI IT Governance Direction 2023 §8.5:
 * - Every API response includes timestamp and correlation ID
 * - Status indicates success/error/validation-error
 * - Optional pagination metadata for list endpoints
 * - Field-level validation errors for forms
 *
 * Used by REST API endpoints (/api/v1/**) exclusively.
 * Web MVC endpoints continue using existing response types.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponseV2<T> {

    private final String status;                    // SUCCESS | ERROR | VALIDATION_ERROR
    private final T data;                           // Response payload
    private final String errorCode;                 // Error code for frontend mapping
    private final String message;                   // User-friendly message
    private final String requestId;                 // UUID for tracing & audit
    private final LocalDateTime timestamp;          // Server timestamp UTC

    // Pagination metadata (for list endpoints)
    private final Integer page;
    private final Integer pageSize;
    private final Long total;
    private final Integer totalPages;
    private final Boolean hasNextPage;
    private final Boolean hasPreviousPage;

    // Field-level validation errors (for forms)
    private final Map<String, String> fieldErrors;

    private ApiResponseV2(
            String status, T data, String errorCode, String message,
            Integer page, Integer pageSize, Long total, Integer totalPages,
            Map<String, String> fieldErrors) {
        this.status = status;
        this.data = data;
        this.errorCode = errorCode;
        this.message = message;
        this.requestId = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = totalPages;
        this.hasNextPage = page != null && pageSize != null && total != null
            ? (page * pageSize) < total : null;
        this.hasPreviousPage = page != null ? page > 1 : null;
        this.fieldErrors = fieldErrors;
    }

    // === Builder Methods ===

    /**
     * Create success response
     */
    public static <T> ApiResponseV2<T> success(T data) {
        return new ApiResponseV2<>("SUCCESS", data, null, null,
            null, null, null, null, null);
    }

    /**
     * Create success response with message
     */
    public static <T> ApiResponseV2<T> success(T data, String message) {
        return new ApiResponseV2<>("SUCCESS", data, null, message,
            null, null, null, null, null);
    }

    /**
     * Create success response with pagination metadata
     * Per CBS standards: Used by all list endpoints that support pagination
     */
    public static <T> ApiResponseV2<T> successWithPagination(
            T data, Integer page, Integer pageSize, Long total) {
        if (page < 1) page = 1;
        if (pageSize < 1 || pageSize > 100) pageSize = 10;
        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new ApiResponseV2<>("SUCCESS", data, null, null,
            page, pageSize, total, totalPages, null);
    }

    /**
     * Create error response
     * Used by error handlers when exception occurs
     */
    public static <T> ApiResponseV2<T> error(String errorCode, String message) {
        return new ApiResponseV2<>("ERROR", null, errorCode, message,
            null, null, null, null, null);
    }

    /**
     * Create validation error response
     * Used when @Valid fails on @RequestBody DTOs
     */
    public static <T> ApiResponseV2<T> validationError(Map<String, String> fieldErrors) {
        return new ApiResponseV2<>("VALIDATION_ERROR", null, "VALIDATION_FAILED",
            "Validation failed", null, null, null, null, fieldErrors);
    }

    // === Getters for Jackson Serialization ===

    public String getStatus() { return status; }
    public T getData() { return data; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public String getRequestId() { return requestId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Integer getPage() { return page; }
    public Integer getPageSize() { return pageSize; }
    public Long getTotal() { return total; }
    public Integer getTotalPages() { return totalPages; }
    public Boolean getHasNextPage() { return hasNextPage; }
    public Boolean getHasPreviousPage() { return hasPreviousPage; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}


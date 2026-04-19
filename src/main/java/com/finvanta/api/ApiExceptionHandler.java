package com.finvanta.api;

import com.finvanta.util.BusinessException;
import com.finvanta.util.MfaRequiredException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * CBS Global API Exception Handler per Finacle API / Temenos IRIS.
 *
 * Converts all exceptions to standardized ApiResponse error format.
 * Per RBI IT Governance Direction 2023: error responses must never
 * expose stack traces, internal class names, or SQL details.
 *
 * Only applies to @RestController endpoints (not @Controller views).
 */
@RestControllerAdvice(basePackages = "com.finvanta.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMfaRequired(MfaRequiredException ex) {
        // Per RBI IT Governance Direction 2023 §8.3: step-up MFA failures
        // return 428 Precondition Required so the Next.js BFF can prompt
        // the user for MFA and resume the original action with a fresh
        // idempotency key. No PII is carried in the body.
        log.info("API mfa step-up required: challenge={}", ex.getChallengeId());
        Map<String, String> payload = new HashMap<>();
        payload.put("challengeId", ex.getChallengeId());
        payload.put("channel", ex.getChannel());
        return ResponseEntity.status(428).body(ApiResponse.errorWithData(ex.getErrorCode(), ex.getMessage(), payload));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("API business error: {} — {}", ex.getErrorCode(), ex.getMessage());
        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        // Per Finacle/Temenos maker-checker invariant: a checker operating
        // on a stale @Version must receive a deterministic 409 with
        // errorCode VERSION_CONFLICT so the UI can surface "Record changed
        // since you loaded it — please reload." No merge, no partial write.
        log.warn("API optimistic lock failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "VERSION_CONFLICT", "Record was modified by another user. " + "Please reload and retry."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccess(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("ACCESS_DENIED", "Insufficient privileges for this " + "operation"));
    }

    /**
     * Handles @Valid validation failures on @RequestBody DTOs.
     * Per Finacle API: returns structured field-level error messages
     * so API consumers can fix specific fields without guessing.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_FAILED", errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("INVALID_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        // CBS SECURITY: Never expose internal details
        log.error("API unhandled error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred. " + "Contact support."));
    }

    /**
     * Maps CBS error codes to HTTP status codes.
     * Per Finacle API standards: business validation errors
     * return 400/409, not-found returns 404, auth returns 403.
     */
    private HttpStatus mapErrorCodeToStatus(String code) {
        if (code == null) return HttpStatus.BAD_REQUEST;
        return switch (code) {
            case "CLEARING_NOT_FOUND",
                    "ACCOUNT_NOT_FOUND",
                    "BENEFICIARY_NOT_FOUND",
                    "BRANCH_NOT_FOUND",
                    "CHARGE_NOT_FOUND",
                    "CYCLE_NOT_FOUND",
                    "LOAN_NOT_FOUND",
                    "APPLICATION_NOT_FOUND",
                    "CUSTOMER_NOT_FOUND",
                    "TRANSACTION_NOT_FOUND",
                    "FD_NOT_FOUND",
                    "GL_NOT_FOUND",
                    "NOTIFICATION_NOT_FOUND",
                    "TEMPLATE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "DUPLICATE_CLEARING_REF",
                    "ALREADY_TERMINAL",
                    "ALREADY_WAIVED",
                    "ALREADY_REVERSED",
                    "ALREADY_DISBURSED",
                    "ALREADY_CLOSED",
                    "CLEARING_IN_PROGRESS",
                    "DUPLICATE_TRANSACTION" -> HttpStatus.CONFLICT;
            case "INSUFFICIENT_BALANCE",
                    "ACCOUNT_FROZEN",
                    "ACCOUNT_CLOSED",
                    "ACCOUNT_DORMANT",
                    "DEBIT_NOT_ALLOWED",
                    "CREDIT_NOT_ALLOWED",
                    "FD_NOT_ACTIVE",
                    "PREMATURE_NOT_ALLOWED",
                    "LIEN_BLOCKED",
                    "KYC_NOT_VERIFIED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "WORKFLOW_SELF_APPROVAL", "BRANCH_ACCESS_DENIED" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}

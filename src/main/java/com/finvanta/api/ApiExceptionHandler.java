package com.finvanta.api;

import com.finvanta.util.BusinessException;
import com.finvanta.util.MfaRequiredException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
        log.info("API mfa step-up required: challenge={}", ex.getChallengeId());
        exposeErrorCode(ex.getErrorCode());
        Map<String, String> payload = new HashMap<>();
        payload.put("challengeId", ex.getChallengeId());
        payload.put("channel", ex.getChannel());
        return ResponseEntity.status(428).body(ApiResponse.errorWithData(ex.getErrorCode(), ex.getMessage(), payload));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("API business error: {} — {}", ex.getErrorCode(), ex.getMessage());
        exposeErrorCode(ex.getErrorCode());
        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        String severity = mapErrorCodeToSeverity(ex.getErrorCode());
        String action = mapErrorCodeToAction(ex.getErrorCode());
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage(), severity, action));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("API optimistic lock failure: {}", ex.getMessage());
        exposeErrorCode("VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "VERSION_CONFLICT",
                        "Record was modified by another user. Please reload and retry.",
                        "MEDIUM", "Reload the page and retry your operation"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccess(AccessDeniedException ex) {
        exposeErrorCode("ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        "ACCESS_DENIED",
                        "Insufficient privileges for this operation",
                        "HIGH", "Contact branch manager for role elevation"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        exposeErrorCode("VALIDATION_FAILED");
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_FAILED", errors,
                        "LOW", "Correct the highlighted fields and resubmit"));
    }

    /**
     * CBS: Missing required query/path parameter (e.g., ?q= omitted on search).
     * Per Tier-1 CBS: returns 400 with the parameter name so the BFF can
     * identify which parameter was missing without guessing.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        exposeErrorCode("MISSING_PARAMETER");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("MISSING_PARAMETER",
                        "Required parameter '" + ex.getParameterName() + "' is missing",
                        "LOW", "Include the '" + ex.getParameterName() + "' query parameter and retry"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadArg(IllegalArgumentException ex) {
        exposeErrorCode("INVALID_REQUEST");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", ex.getMessage(),
                        "LOW", "Verify request parameters and retry"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("API unhandled error: {}", ex.getMessage(), ex);
        exposeErrorCode("INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "INTERNAL_ERROR",
                        "An unexpected error occurred. Contact support.",
                        "CRITICAL", "Note the correlation ID and contact support"));
    }

    /**
     * CBS: Expose the error code as a request attribute so the
     * JwtAuthenticationFilter's access log can include it without
     * reading the response body. Per Finacle TRAN_LOG: every API
     * error must carry the error code in the access log for SOC.
     */
    private void exposeErrorCode(String errorCode) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                attrs.getRequest().setAttribute("fvErrorCode", errorCode);
            }
        } catch (Exception ignored) {
            // Never fail the error response for logging
        }
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

    /**
     * Maps CBS error codes to severity levels per Tier-1 CBS API standards.
     *
     * <p>Per RBI Fair Practices Code 2023: error severity drives the BFF's
     * UI treatment — LOW (toast), MEDIUM (modal), HIGH (blocking), CRITICAL (support).
     *
     * <p>Financial safety errors (INSUFFICIENT_BALANCE, ACCOUNT_FROZEN, LIEN_BLOCKED)
     * are always HIGH because they block a financial operation and the user must
     * take corrective action before retrying.
     */
    private String mapErrorCodeToSeverity(String code) {
        if (code == null) return "MEDIUM";
        return switch (code) {
            case "INSUFFICIENT_BALANCE",
                    "ACCOUNT_FROZEN",
                    "ACCOUNT_CLOSED",
                    "DEBIT_NOT_ALLOWED",
                    "CREDIT_NOT_ALLOWED",
                    "LIEN_BLOCKED",
                    "WORKFLOW_SELF_APPROVAL",
                    "BRANCH_ACCESS_DENIED" -> "HIGH";
            case "DUPLICATE_CLEARING_REF",
                    "ALREADY_TERMINAL",
                    "ALREADY_WAIVED",
                    "ALREADY_REVERSED",
                    "ALREADY_DISBURSED",
                    "ALREADY_CLOSED",
                    "CLEARING_IN_PROGRESS",
                    "DUPLICATE_TRANSACTION" -> "MEDIUM";
            case "ACCOUNT_DORMANT",
                    "FD_NOT_ACTIVE",
                    "PREMATURE_NOT_ALLOWED",
                    "KYC_NOT_VERIFIED" -> "MEDIUM";
            default -> "LOW";
        };
    }

    /**
     * Maps CBS error codes to user-facing remediation actions.
     *
     * <p>Per RBI Fair Practices Code 2023 §7.1 and Finacle API standards:
     * every error response to the customer must include actionable guidance
     * so the user knows what corrective step to take. The action text is
     * designed for display in the Next.js BFF error modal.
     */
    private String mapErrorCodeToAction(String code) {
        if (code == null) return null;
        return switch (code) {
            case "INSUFFICIENT_BALANCE" -> "Verify available balance or arrange funds before retrying";
            case "ACCOUNT_FROZEN" -> "Contact branch to request account unfreeze";
            case "ACCOUNT_CLOSED" -> "This account is closed. Open a new account if needed";
            case "ACCOUNT_DORMANT" -> "Visit the branch with ID proof to reactivate the account";
            case "DEBIT_NOT_ALLOWED", "CREDIT_NOT_ALLOWED" -> "Account restrictions apply. Contact branch";
            case "LIEN_BLOCKED" -> "A lien is placed on this account. Contact branch for details";
            case "KYC_NOT_VERIFIED" -> "Complete KYC verification before proceeding";
            case "WORKFLOW_SELF_APPROVAL" -> "A different user must approve this operation";
            case "BRANCH_ACCESS_DENIED" -> "You do not have access to this branch's data";
            case "DUPLICATE_TRANSACTION" -> "This transaction was already processed. Check your statement";
            case "ALREADY_WAIVED" -> "This charge has already been waived. No further action needed";
            case "CLEARING_IN_PROGRESS" -> "A clearing cycle is in progress. Wait for completion before retrying";
            case "DUPLICATE_CLEARING_REF" -> "This clearing reference already exists. Verify and use a unique reference";
            case "ALREADY_TERMINAL" -> "This record is in a terminal state and cannot be modified";
            case "ALREADY_REVERSED" -> "This transaction has already been reversed";
            case "ALREADY_DISBURSED" -> "This loan has already been disbursed";
            case "ALREADY_CLOSED" -> "This account/record is already closed";
            default -> null;
        };
    }
}

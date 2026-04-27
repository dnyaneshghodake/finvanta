package com.finvanta.cbs.common.exception;

import com.finvanta.api.ApiResponse;
import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.util.BusinessException;
import com.finvanta.util.MfaRequiredException;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
 * CBS Tier-1 API Exception Handler scoped to the refactored
 * {@code com.finvanta.cbs..} controllers per CBS API_ERR_HANDLER standard.
 *
 * <p>The legacy {@code com.finvanta.api.ApiExceptionHandler} is scoped via
 * {@code @RestControllerAdvice(basePackages = "com.finvanta.api")} and therefore
 * does NOT see exceptions thrown from the v2 controllers under
 * {@code com.finvanta.cbs.modules..controller}. Without this advice, a
 * {@link BusinessException} carrying a CBS-prefixed code (e.g. CBS-ACCT-001)
 * would fall through Spring's default handler and surface as a generic 500 with
 * an internal stack trace -- a Tier-1 violation per RBI IT Governance Direction
 * 2023 SS8.5 ("error responses must never expose stack traces or internal class
 * names").
 *
 * <p>Same wire format as the legacy handler ({@link ApiResponse#error}) so the
 * Next.js BFF i18n layer treats v1 and v2 responses identically.
 * {@code HIGHEST_PRECEDENCE} so this advice wins for CBS controllers; the
 * legacy advice still wins for {@code com.finvanta.api..} because that path
 * falls outside this advice's basePackages filter.
 */
@RestControllerAdvice(basePackages = "com.finvanta.cbs")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CbsApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CbsApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("CBS-API business error: {} -- {}", ex.getErrorCode(), ex.getMessage());
        exposeErrorCode(ex.getErrorCode());
        HttpStatus status = mapErrorCodeToStatus(ex.getErrorCode());
        String severity = mapErrorCodeToSeverity(ex.getErrorCode());
        String action = mapErrorCodeToAction(ex.getErrorCode());
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage(), severity, action));
    }

    /**
     * MFA step-up challenge per RBI IT Governance Direction 2023 §8.4.
     * Mirrors the legacy handler so v1 and v2 callers see identical 428 semantics
     * with the {@code challengeId} / {@code channel} payload.
     */
    @ExceptionHandler(MfaRequiredException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMfaRequired(MfaRequiredException ex) {
        log.info("CBS-API mfa step-up required: challenge={}", ex.getChallengeId());
        exposeErrorCode(ex.getErrorCode());
        Map<String, String> payload = new HashMap<>();
        payload.put("challengeId", ex.getChallengeId());
        payload.put("channel", ex.getChannel());
        return ResponseEntity.status(428).body(ApiResponse.errorWithData(ex.getErrorCode(), ex.getMessage(), payload));
    }

    /**
     * JPA optimistic-lock conflict (concurrent mutation of a {@code @Version}-tracked
     * row). Surfaces as 409 with the same {@code VERSION_CONFLICT} code as the
     * legacy handler so the BFF i18n layer treats both API versions identically.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("CBS-API optimistic lock failure: {}", ex.getMessage());
        exposeErrorCode("VERSION_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "VERSION_CONFLICT",
                        "Record was modified by another user. Please reload and retry.",
                        CbsErrorCodes.SEVERITY_MEDIUM,
                        "Reload the page and retry your operation"));
    }

    /**
     * Spring Security {@code AccessDeniedException} -- triggered by failing
     * {@code @PreAuthorize} checks on v2 controllers. Without this dedicated
     * handler the generic Exception catch would convert legitimate authorization
     * failures into 500 Internal Server Errors.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccess(AccessDeniedException ex) {
        log.warn("CBS-API access denied: {}", ex.getMessage());
        exposeErrorCode("ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        "ACCESS_DENIED",
                        "Insufficient privileges for this operation",
                        CbsErrorCodes.SEVERITY_HIGH,
                        "Contact branch manager for role elevation"));
    }

    /**
     * Jakarta Bean Validation failure on {@code @RequestBody} -- aggregates all
     * field errors into a single 400 response so the BFF can highlight every
     * invalid field at once. Without this handler, validation failures would
     * surface as a generic 500 stack trace.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        exposeErrorCode("VALIDATION_FAILED");
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("CBS-API validation failed: {}", errors);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_FAILED", errors,
                        CbsErrorCodes.SEVERITY_LOW, "Correct the highlighted fields and resubmit"));
    }

    /**
     * Missing required query/path parameter (e.g. {@code ?q=} omitted on a
     * search endpoint). 400 with the parameter name so the BFF can identify
     * exactly which input was missing.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        exposeErrorCode("MISSING_PARAMETER");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("MISSING_PARAMETER",
                        "Required parameter '" + ex.getParameterName() + "' is missing",
                        CbsErrorCodes.SEVERITY_LOW,
                        "Include the '" + ex.getParameterName() + "' query parameter and retry"));
    }

    /**
     * {@code IllegalArgumentException} from path-variable / query-param parsing
     * (e.g. invalid enum value on a path variable). 400 with the underlying
     * message instead of letting the generic handler convert it to 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadArg(IllegalArgumentException ex) {
        exposeErrorCode("INVALID_REQUEST");
        log.warn("CBS-API invalid request: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", ex.getMessage(),
                        CbsErrorCodes.SEVERITY_LOW, "Verify request parameters and retry"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("CBS-API unhandled error: {}", ex.getMessage(), ex);
        exposeErrorCode("CBS-INTERNAL");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "CBS-INTERNAL",
                        "An unexpected error occurred. Contact support.",
                        CbsErrorCodes.SEVERITY_CRITICAL,
                        "Note the correlation ID and contact support"));
    }

    /**
     * Exposes the error code as a request attribute so the access-log filter
     * can include it without reading the response body. Same attribute key
     * ({@code fvErrorCode}) as the legacy handler so existing log enrichment
     * continues to work.
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
     * Maps CBS error codes to HTTP status codes per ISO 20022 / CBS API standards.
     *
     * <p>404 NOT_FOUND for entity-not-found; 409 CONFLICT for duplicate /
     * terminal-state; 422 UNPROCESSABLE_ENTITY for business rules that block a
     * financial operation; 403 FORBIDDEN for access / self-approval; 400
     * BAD_REQUEST as the input-validation default.
     */
    private HttpStatus mapErrorCodeToStatus(String code) {
        if (code == null) return HttpStatus.BAD_REQUEST;
        return switch (code) {
            case CbsErrorCodes.CUST_NOT_FOUND,
                    CbsErrorCodes.ACCT_NOT_FOUND,
                    CbsErrorCodes.TXN_BRANCH_INVALID,
                    CbsErrorCodes.LOAN_NOT_FOUND,
                    CbsErrorCodes.LOAN_APPLICATION_NOT_FOUND,
                    CbsErrorCodes.GL_ACCOUNT_NOT_FOUND,
                    CbsErrorCodes.WF_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CbsErrorCodes.ACCT_CLOSED,
                    CbsErrorCodes.ACCT_DUPLICATE_NUMBER,
                    CbsErrorCodes.TXN_IDEMPOTENCY_DUPLICATE,
                    CbsErrorCodes.TXN_PENDING_APPROVAL,
                    CbsErrorCodes.LOAN_ALREADY_CLOSED,
                    CbsErrorCodes.WF_ALREADY_PROCESSED,
                    "WORKFLOW_VERSION_MISMATCH" -> HttpStatus.CONFLICT;
            case CbsErrorCodes.ACCT_INSUFFICIENT_BALANCE,
                    CbsErrorCodes.ACCT_FROZEN,
                    CbsErrorCodes.ACCT_DORMANT,
                    CbsErrorCodes.ACCT_MINIMUM_BALANCE_BREACH,
                    CbsErrorCodes.ACCT_OD_LIMIT_EXCEEDED,
                    CbsErrorCodes.ACCT_HOLD_AMOUNT_EXCEEDED,
                    CbsErrorCodes.ACCT_INACTIVE,
                    CbsErrorCodes.CUST_KYC_EXPIRED,
                    CbsErrorCodes.CUST_KYC_NOT_VERIFIED,
                    CbsErrorCodes.CUST_DEACTIVATED,
                    CbsErrorCodes.LOAN_NPA_CLASSIFIED,
                    CbsErrorCodes.LOAN_ELIGIBILITY_FAILED,
                    CbsErrorCodes.LOAN_COLLATERAL_INSUFFICIENT,
                    CbsErrorCodes.TXN_LIMIT_EXCEEDED,
                    CbsErrorCodes.TXN_DAY_NOT_OPEN,
                    CbsErrorCodes.GL_POSTING_INTEGRITY_FAIL,
                    CbsErrorCodes.COMP_AML_FLAG -> HttpStatus.UNPROCESSABLE_ENTITY;
            // 403 FORBIDDEN per RFC 9110 §15.5.4: authenticated user lacks
            // required privilege / branch access / maker-checker separation;
            // or the account itself is locked/inactive (caller must contact
            // admin, not retry credentials).
            case CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED,
                    CbsErrorCodes.WF_SELF_APPROVAL,
                    CbsErrorCodes.AUTH_ACCOUNT_LOCKED,
                    CbsErrorCodes.AUTH_ACCOUNT_INACTIVE -> HttpStatus.FORBIDDEN;
            // 401 UNAUTHORIZED per RFC 9110 §15.5.2: authentication failed
            // (wrong credentials, not a privilege-elevation issue). 403 here
            // would prevent BFF clients from re-prompting for credentials and
            // instead show an "access denied" message.
            case CbsErrorCodes.AUTH_INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * Maps CBS error codes to severity levels for BFF UI treatment.
     *
     * <p>HIGH for financial-safety blockers (insufficient balance, frozen,
     * branch access). MEDIUM for state conflicts the user can resolve by
     * retrying or contacting branch. LOW for input validation. CRITICAL is
     * reserved for {@code handleGeneric} and is not returned from here.
     */
    private String mapErrorCodeToSeverity(String code) {
        if (code == null) return CbsErrorCodes.SEVERITY_MEDIUM;
        return switch (code) {
            case CbsErrorCodes.ACCT_INSUFFICIENT_BALANCE,
                    CbsErrorCodes.ACCT_FROZEN,
                    CbsErrorCodes.ACCT_CLOSED,
                    CbsErrorCodes.ACCT_HOLD_AMOUNT_EXCEEDED,
                    CbsErrorCodes.ACCT_MINIMUM_BALANCE_BREACH,
                    CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED,
                    CbsErrorCodes.WF_SELF_APPROVAL,
                    CbsErrorCodes.LOAN_NPA_CLASSIFIED,
                    CbsErrorCodes.COMP_AML_FLAG,
                    CbsErrorCodes.GL_POSTING_INTEGRITY_FAIL -> CbsErrorCodes.SEVERITY_HIGH;
            case CbsErrorCodes.ACCT_DUPLICATE_NUMBER,
                    CbsErrorCodes.TXN_IDEMPOTENCY_DUPLICATE,
                    CbsErrorCodes.TXN_PENDING_APPROVAL,
                    CbsErrorCodes.LOAN_ALREADY_CLOSED,
                    CbsErrorCodes.WF_ALREADY_PROCESSED,
                    CbsErrorCodes.ACCT_DORMANT,
                    CbsErrorCodes.CUST_KYC_EXPIRED,
                    CbsErrorCodes.CUST_KYC_NOT_VERIFIED -> CbsErrorCodes.SEVERITY_MEDIUM;
            default -> CbsErrorCodes.SEVERITY_LOW;
        };
    }

    /**
     * Maps CBS error codes to user-facing remediation actions per RBI Fair
     * Practices Code 2023 §7.1: every error response must include actionable
     * guidance so the user knows what corrective step to take. Action text is
     * displayed in the BFF error modal.
     */
    private String mapErrorCodeToAction(String code) {
        if (code == null) return null;
        return switch (code) {
            case CbsErrorCodes.ACCT_INSUFFICIENT_BALANCE ->
                    "Verify available balance or arrange funds before retrying";
            case CbsErrorCodes.ACCT_FROZEN ->
                    "Contact branch to request account unfreeze";
            case CbsErrorCodes.ACCT_CLOSED ->
                    "This account is closed. Open a new account if needed";
            case CbsErrorCodes.ACCT_DORMANT ->
                    "Visit the branch with ID proof to reactivate the account";
            case CbsErrorCodes.ACCT_MINIMUM_BALANCE_BREACH ->
                    "Maintain the required minimum balance and retry";
            case CbsErrorCodes.ACCT_HOLD_AMOUNT_EXCEEDED ->
                    "An active hold/lien blocks this operation. Contact branch";
            case CbsErrorCodes.ACCT_DUPLICATE_NUMBER ->
                    "Customer already has this account type at this branch";
            case CbsErrorCodes.ACCT_INVALID_TYPE ->
                    "Select a valid account type from the product catalogue";
            case CbsErrorCodes.ACCT_INVALID_FREEZE_TYPE ->
                    "Use one of: DEBIT_FREEZE, CREDIT_FREEZE, TOTAL_FREEZE";
            case CbsErrorCodes.CUST_KYC_EXPIRED ->
                    "Re-KYC required per RBI Master Direction. Visit branch with current OVDs";
            case CbsErrorCodes.CUST_KYC_NOT_VERIFIED ->
                    "Complete KYC verification before proceeding";
            case CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED ->
                    "You do not have access to this branch's data";
            case CbsErrorCodes.TXN_IDEMPOTENCY_DUPLICATE ->
                    "This transaction was already processed. Check your statement";
            case CbsErrorCodes.TXN_LIMIT_EXCEEDED ->
                    "Transaction exceeds the configured daily limit";
            case CbsErrorCodes.TXN_DAY_NOT_OPEN ->
                    "Business day is not open. Wait for branch BOD before retrying";
            case CbsErrorCodes.TXN_PENDING_APPROVAL ->
                    "Reload the page and retry your operation";
            case CbsErrorCodes.LOAN_NPA_CLASSIFIED ->
                    "Loan is classified NPA. Contact branch for resolution path";
            case CbsErrorCodes.LOAN_ALREADY_CLOSED ->
                    "This loan has already been closed";
            case CbsErrorCodes.LOAN_ELIGIBILITY_FAILED ->
                    "Loan eligibility criteria not met. Review the rejection notes";
            case CbsErrorCodes.WF_SELF_APPROVAL ->
                    "A different user must approve this operation";
            case CbsErrorCodes.WF_ALREADY_PROCESSED ->
                    "This workflow item is already approved or rejected";
            case CbsErrorCodes.COMP_AML_FLAG ->
                    "Transaction flagged for AML review. Contact compliance team";
            case CbsErrorCodes.AUTH_INVALID_CREDENTIALS,
                    CbsErrorCodes.AUTH_ACCOUNT_LOCKED,
                    CbsErrorCodes.AUTH_ACCOUNT_INACTIVE ->
                    "Authentication failed. Contact your administrator";
            default -> null;
        };
    }
}

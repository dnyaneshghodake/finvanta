package com.finvanta.cbs.common.exception;

import com.finvanta.api.ApiResponse;
import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.cbs.modules.teller.exception.FicnDetectedException;
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

    /**
     * CBS FICN handler per RBI Master Direction on Counterfeit Notes.
     *
     * <p>Returns HTTP 422 (UNPROCESSABLE_ENTITY) with the printable
     * {@link com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse
     * acknowledgement slip} as the response body so the BFF / JSP can print
     * the customer-facing receipt directly from the API error response.
     *
     * <p>This handler MUST be registered in addition to the generic
     * {@link #handleBusiness} -- although {@link FicnDetectedException}
     * extends {@link BusinessException}, only this handler exposes the slip
     * payload via {@link ApiResponse#errorWithData}. The generic handler
     * would surface the same status / error code but without the impounded
     * denomination details, which would defeat the purpose of the receipt.
     *
     * <p>Severity is hard-coded HIGH (not delegated to the severity-mapping
     * switch) because every FICN incident is high-priority by RBI mandate --
     * the customer must be handed the slip and informed about FIR / chest
     * dispatch implications regardless of count.
     *
     * <p>Per the same status/severity/action mapping as the generic handler,
     * {@code TELLER_COUNTERFEIT_DETECTED} maps to 422 + HIGH + "Counterfeit
     * notes detected. Issue FICN acknowledgement to customer and route to
     * FICN review" -- those are the right defaults for a row of the BFF
     * error log, but the response body adds the impounded-denomination
     * detail the operator screen needs.
     */
    @ExceptionHandler(FicnDetectedException.class)
    public ResponseEntity<ApiResponse<com.finvanta.cbs.modules.teller.dto.response.FicnAcknowledgementResponse>>
            handleFicnDetected(FicnDetectedException ex) {
        log.warn("CBS-API FICN detected: code={} register={} fir-required={} -- {}",
                ex.getErrorCode(),
                ex.getAcknowledgement().registerRef(),
                ex.getAcknowledgement().firRequired(),
                ex.getMessage());
        exposeErrorCode(ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.errorWithData(
                        ex.getErrorCode(),
                        ex.getMessage(),
                        ex.getAcknowledgement()));
    }

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
                    CbsErrorCodes.TXN_NOT_FOUND,
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
                    CbsErrorCodes.TXN_ALREADY_REVERSED,
                    CbsErrorCodes.TXN_TRANSFER_REVERSAL_REQUIRED,
                    // Teller state conflicts: till is not in the expected
                    // lifecycle state for the requested operation. 409 lets
                    // the BFF distinguish from input-validation 400s and
                    // from financial-blocker 422s, since the resolution
                    // (open till / wait for supervisor / use a different
                    // till) is qualitatively different from a balance issue.
                    CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                    CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    CbsErrorCodes.TELLER_TILL_DUPLICATE,
                    "WORKFLOW_VERSION_MISMATCH" -> HttpStatus.CONFLICT;
            case CbsErrorCodes.ACCT_INSUFFICIENT_BALANCE,
                    CbsErrorCodes.ACCT_FROZEN,
                    CbsErrorCodes.ACCT_DORMANT,
                    CbsErrorCodes.ACCT_MINIMUM_BALANCE_BREACH,
                    CbsErrorCodes.ACCT_OD_LIMIT_EXCEEDED,
                    CbsErrorCodes.ACCT_HOLD_AMOUNT_EXCEEDED,
                    CbsErrorCodes.ACCT_DAILY_LIMIT_EXCEEDED,
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
                    CbsErrorCodes.COMP_AML_FLAG,
                    // Teller financial blockers: till has insufficient cash
                    // for an outflow, or counterfeit notes were detected
                    // (FICN per RBI Master Direction). Both are unprocessable
                    // because the request is well-formed but the financial
                    // state of the till / tendered cash blocks completion.
                    // CTR threshold breach (PMLA Rule 9: PAN/Form60 missing)
                    // also lands here -- it is a regulatory blocker, not a
                    // syntax error.
                    CbsErrorCodes.TELLER_TILL_INSUFFICIENT_CASH,
                    CbsErrorCodes.TELLER_COUNTERFEIT_DETECTED,
                    CbsErrorCodes.COMP_CTR_THRESHOLD -> HttpStatus.UNPROCESSABLE_ENTITY;
            // 403 FORBIDDEN per RFC 9110 §15.5.4: authenticated user lacks
            // required privilege / branch access / maker-checker separation;
            // or the account itself is locked/inactive (caller must contact
            // admin, not retry credentials).
            case CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED,
                    CbsErrorCodes.WF_SELF_APPROVAL,
                    CbsErrorCodes.AUTH_ACCOUNT_LOCKED,
                    CbsErrorCodes.AUTH_ACCOUNT_INACTIVE,
                    // Teller ownership: the authenticated user is not the
                    // owner of the targeted till. Per RBI Internal Controls
                    // (segregation of duties) only the assigned teller may
                    // post to their own till; another teller's request is
                    // forbidden, not a state conflict, so 403 not 409.
                    CbsErrorCodes.TELLER_TILL_OWNERSHIP -> HttpStatus.FORBIDDEN;
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
                    CbsErrorCodes.ACCT_DAILY_LIMIT_EXCEEDED,
                    CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED,
                    CbsErrorCodes.WF_SELF_APPROVAL,
                    CbsErrorCodes.LOAN_NPA_CLASSIFIED,
                    CbsErrorCodes.COMP_AML_FLAG,
                    CbsErrorCodes.GL_POSTING_INTEGRITY_FAIL,
                    // Teller financial-safety blockers: counterfeit notes
                    // (FICN), till cash exhausted, segregation-of-duties
                    // breach. All warrant a HIGH severity in the BFF UI so
                    // the supervisor gets a blocking modal, not a toast.
                    CbsErrorCodes.TELLER_COUNTERFEIT_DETECTED,
                    CbsErrorCodes.TELLER_TILL_INSUFFICIENT_CASH,
                    CbsErrorCodes.TELLER_TILL_OWNERSHIP -> CbsErrorCodes.SEVERITY_HIGH;
            case CbsErrorCodes.ACCT_DUPLICATE_NUMBER,
                    CbsErrorCodes.TXN_IDEMPOTENCY_DUPLICATE,
                    CbsErrorCodes.TXN_PENDING_APPROVAL,
                    CbsErrorCodes.TXN_ALREADY_REVERSED,
                    CbsErrorCodes.TXN_TRANSFER_REVERSAL_REQUIRED,
                    CbsErrorCodes.LOAN_ALREADY_CLOSED,
                    CbsErrorCodes.WF_ALREADY_PROCESSED,
                    CbsErrorCodes.ACCT_DORMANT,
                    CbsErrorCodes.CUST_KYC_EXPIRED,
                    CbsErrorCodes.CUST_KYC_NOT_VERIFIED,
                    // Teller state / regulatory conflicts -- recoverable by
                    // the operator (open till, supply PAN/Form60, wait for
                    // supervisor) so MEDIUM severity is correct.
                    CbsErrorCodes.TELLER_TILL_NOT_OPEN,
                    CbsErrorCodes.TELLER_TILL_INVALID_STATE,
                    CbsErrorCodes.TELLER_TILL_DUPLICATE,
                    CbsErrorCodes.TELLER_TILL_LIMIT_EXCEEDED,
                    CbsErrorCodes.COMP_CTR_THRESHOLD -> CbsErrorCodes.SEVERITY_MEDIUM;
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
            case CbsErrorCodes.ACCT_DAILY_LIMIT_EXCEEDED ->
                    "Daily limit on this account is exhausted. Retry tomorrow or request a limit increase at the branch";
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
            case CbsErrorCodes.TXN_ALREADY_REVERSED ->
                    "This transaction has already been reversed. No further action needed";
            case CbsErrorCodes.TXN_TRANSFER_REVERSAL_REQUIRED ->
                    "Transfer reversals require both legs (debit + credit) to be reversed atomically. Use the transfer reversal flow";
            case CbsErrorCodes.COMP_AML_FLAG ->
                    "Transaction flagged for AML review. Contact compliance team";
            case CbsErrorCodes.AUTH_INVALID_CREDENTIALS,
                    CbsErrorCodes.AUTH_ACCOUNT_LOCKED,
                    CbsErrorCodes.AUTH_ACCOUNT_INACTIVE ->
                    "Authentication failed. Contact your administrator";
            // Teller channel remediation per RBI Fair Practices Code §7.1.
            // Each action describes the exact next step the teller / supervisor
            // must take so the BFF error modal does not leave the operator
            // guessing.
            case CbsErrorCodes.TELLER_TILL_NOT_OPEN ->
                    "Open your till for the day before posting cash transactions";
            case CbsErrorCodes.TELLER_TILL_INVALID_STATE ->
                    "Till is not in OPEN status. Wait for supervisor approval or open a new till";
            case CbsErrorCodes.TELLER_TILL_OWNERSHIP ->
                    "You can only operate your own till. Contact branch manager if reassignment is needed";
            case CbsErrorCodes.TELLER_DENOM_SUM_MISMATCH ->
                    "Denomination breakdown does not match the amount. Recount the cash and resubmit";
            case CbsErrorCodes.TELLER_DENOM_INVALID ->
                    "One or more denominations are not recognized. Use only the listed RBI legal-tender values";
            case CbsErrorCodes.TELLER_TILL_INSUFFICIENT_CASH ->
                    "Till has insufficient cash. Request a vault buy before paying out";
            case CbsErrorCodes.TELLER_TILL_LIMIT_EXCEEDED ->
                    "Till cash limit reached. Sell cash to vault before accepting more deposits";
            case CbsErrorCodes.TELLER_COUNTERFEIT_DETECTED ->
                    "Counterfeit notes detected. Issue FICN acknowledgement to customer and route to FICN review";
            case CbsErrorCodes.TELLER_TILL_DATE_INVALID ->
                    "Till business date is invalid. Verify branch business date and retry";
            case CbsErrorCodes.TELLER_TILL_DUPLICATE ->
                    "A till already exists for you on this business date";
            case CbsErrorCodes.COMP_CTR_THRESHOLD ->
                    "Cash deposit at or above CTR threshold requires PAN or Form 60/61 per PMLA Rule 9";
            default -> null;
        };
    }
}

package com.finvanta.cbs.common.exception;

import com.finvanta.api.ApiResponse;
import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.util.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
                    CbsErrorCodes.WF_ALREADY_PROCESSED -> HttpStatus.CONFLICT;
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
            case CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED,
            case CbsErrorCodes.CUST_BRANCH_ACCESS_DENIED,
                    CbsErrorCodes.WF_SELF_APPROVAL,
                    CbsErrorCodes.AUTH_ACCOUNT_LOCKED,
                    CbsErrorCodes.AUTH_ACCOUNT_INACTIVE -> HttpStatus.FORBIDDEN;
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

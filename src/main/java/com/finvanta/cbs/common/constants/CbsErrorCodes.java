package com.finvanta.cbs.common.constants;

/**
 * CBS Tier-1 Error Code Registry per ISO 20022 CBS ERROR_MASTER standard.
 *
 * <p>Format: {@code CBS-MODULE-NNN} where MODULE is a 4-letter bounded context
 * abbreviation and NNN is a sequential code within the module.
 *
 * <p>Per RBI IT Governance Direction 2023 SS8.5: error codes must be
 * machine-readable, documented, and consistent across all API responses.
 * The Next.js BFF uses these codes for i18n error message resolution.
 *
 * <p>Severity mapping (consumed by BFF for UI treatment):
 * <ul>
 *   <li>{@code LOW} -- informational toast, auto-dismiss</li>
 *   <li>{@code MEDIUM} -- warning modal, user acknowledges</li>
 *   <li>{@code HIGH} -- blocking error, requires corrective action</li>
 *   <li>{@code CRITICAL} -- system-level, contact support</li>
 * </ul>
 */
public final class CbsErrorCodes {

    private CbsErrorCodes() {
        // Constants-only class
    }

    // =====================================================================
    // CUSTOMER MODULE (CBS-CUST-xxx)
    // =====================================================================
    public static final String CUST_NOT_FOUND = "CBS-CUST-001";
    public static final String CUST_DUPLICATE_PAN = "CBS-CUST-002";
    public static final String CUST_DUPLICATE_AADHAAR = "CBS-CUST-003";
    public static final String CUST_KYC_EXPIRED = "CBS-CUST-004";
    public static final String CUST_KYC_NOT_VERIFIED = "CBS-CUST-005";
    public static final String CUST_IMMUTABLE_FIELD = "CBS-CUST-006";
    public static final String CUST_BRANCH_ACCESS_DENIED = "CBS-CUST-007";
    public static final String CUST_DEACTIVATED = "CBS-CUST-008";

    // =====================================================================
    // ACCOUNT MODULE (CBS-ACCT-xxx)
    // =====================================================================
    public static final String ACCT_NOT_FOUND = "CBS-ACCT-001";
    public static final String ACCT_INACTIVE = "CBS-ACCT-002";
    public static final String ACCT_FROZEN = "CBS-ACCT-003";
    public static final String ACCT_CLOSED = "CBS-ACCT-004";
    public static final String ACCT_DORMANT = "CBS-ACCT-005";
    public static final String ACCT_INSUFFICIENT_BALANCE = "CBS-ACCT-006";
    public static final String ACCT_MINIMUM_BALANCE_BREACH = "CBS-ACCT-007";
    public static final String ACCT_DUPLICATE_NUMBER = "CBS-ACCT-008";
    public static final String ACCT_OD_LIMIT_EXCEEDED = "CBS-ACCT-009";
    public static final String ACCT_HOLD_AMOUNT_EXCEEDED = "CBS-ACCT-010";
    public static final String ACCT_SAME_ACCOUNT_TRANSFER = "CBS-ACCT-011";
    public static final String ACCT_INVALID_FREEZE_TYPE = "CBS-ACCT-012";
    public static final String ACCT_INVALID_TYPE = "CBS-ACCT-013";

    // =====================================================================
    // TRANSACTION MODULE (CBS-TXN-xxx)
    // =====================================================================
    public static final String TXN_IDEMPOTENCY_DUPLICATE = "CBS-TXN-001";
    public static final String TXN_BUSINESS_DATE_INVALID = "CBS-TXN-002";
    public static final String TXN_DAY_NOT_OPEN = "CBS-TXN-003";
    public static final String TXN_AMOUNT_INVALID = "CBS-TXN-004";
    public static final String TXN_BRANCH_INVALID = "CBS-TXN-005";
    public static final String TXN_LIMIT_EXCEEDED = "CBS-TXN-006";
    public static final String TXN_PENDING_APPROVAL = "CBS-TXN-007";
    public static final String TXN_GL_POSTING_FAILED = "CBS-TXN-008";
    public static final String TXN_BATCH_NOT_OPEN = "CBS-TXN-009";
    public static final String TXN_VALUE_DATE_OUT_OF_WINDOW = "CBS-TXN-010";
    public static final String TXN_POSTING_SUSPENDED = "CBS-TXN-011";
    /**
     * Transfer reversals cannot be performed via the single-leg reversal path --
     * both DEBIT and CREDIT legs must be reversed atomically via the dedicated
     * transfer-reversal service. Thrown by {@code DepositAccountModuleServiceImpl
     * .reverseTransaction} when the target txn is TRANSFER_DEBIT / TRANSFER_CREDIT.
     */
    public static final String TXN_TRANSFER_REVERSAL_REQUIRED = "CBS-TXN-012";
    /**
     * The target transaction has already been reversed; a second reversal would
     * double-post the GL and corrupt the subledger balance. Thrown by
     * {@code DepositAccountModuleServiceImpl.reverseTransaction} after the
     * PESSIMISTIC_WRITE lock on the transaction row confirms {@code isReversed==true}.
     *
     * <p>Distinct from {@link #TXN_IDEMPOTENCY_DUPLICATE} which means "retry of
     * an in-flight txn using the same idempotency key" -- that error returns
     * the prior result, while this one is a hard rejection per CBS audit rules.
     */
    public static final String TXN_ALREADY_REVERSED = "CBS-TXN-013";
    /**
     * The target transaction reference does not exist for the current tenant.
     * Thrown by {@code DepositAccountModuleServiceImpl.reverseTransaction} when
     * {@code findAndLockByTenantIdAndTransactionRef} returns empty.
     *
     * <p>Distinct from {@link #ACCT_NOT_FOUND} (missing account) and
     * {@link #TXN_BUSINESS_DATE_INVALID} (CBS-TXN-002, business-date validation):
     * this code maps to HTTP 404 and surfaces a transaction-specific
     * "not found" message, while CBS-TXN-002 maps to 400 with date-validation
     * remediation text.
     */
    public static final String TXN_NOT_FOUND = "CBS-TXN-014";

    // =====================================================================
    // LOAN MODULE (CBS-LOAN-xxx)
    // =====================================================================
    public static final String LOAN_NOT_FOUND = "CBS-LOAN-001";
    public static final String LOAN_APPLICATION_NOT_FOUND = "CBS-LOAN-002";
    public static final String LOAN_ALREADY_CLOSED = "CBS-LOAN-003";
    public static final String LOAN_NPA_CLASSIFIED = "CBS-LOAN-004";
    public static final String LOAN_ELIGIBILITY_FAILED = "CBS-LOAN-005";
    public static final String LOAN_COLLATERAL_INSUFFICIENT = "CBS-LOAN-006";
    public static final String LOAN_DISBURSEMENT_SCHEDULE_INVALID = "CBS-LOAN-007";

    // =====================================================================
    // GL MODULE (CBS-GL-xxx)
    // =====================================================================
    public static final String GL_ACCOUNT_NOT_FOUND = "CBS-GL-001";
    public static final String GL_DEBIT_CREDIT_MISMATCH = "CBS-GL-002";
    public static final String GL_POSTING_INTEGRITY_FAIL = "CBS-GL-003";
    public static final String GL_SUSPENSE_THRESHOLD = "CBS-GL-004";
    public static final String GL_BRANCH_BALANCE_MISMATCH = "CBS-GL-005";

    // =====================================================================
    // AUTH MODULE (CBS-AUTH-xxx)
    // =====================================================================
    public static final String AUTH_INVALID_CREDENTIALS = "CBS-AUTH-001";
    public static final String AUTH_ACCOUNT_LOCKED = "CBS-AUTH-002";
    public static final String AUTH_ACCOUNT_INACTIVE = "CBS-AUTH-003";
    public static final String AUTH_PASSWORD_EXPIRED = "CBS-AUTH-004";
    public static final String AUTH_MFA_REQUIRED = "CBS-AUTH-005";
    public static final String AUTH_MFA_INVALID = "CBS-AUTH-006";
    public static final String AUTH_TOKEN_EXPIRED = "CBS-AUTH-007";
    public static final String AUTH_REFRESH_TOKEN_REUSED = "CBS-AUTH-008";

    // =====================================================================
    // WORKFLOW MODULE (CBS-WF-xxx)
    // =====================================================================
    public static final String WF_SELF_APPROVAL = "CBS-WF-001";
    public static final String WF_ALREADY_PROCESSED = "CBS-WF-002";
    public static final String WF_NOT_FOUND = "CBS-WF-003";

    // =====================================================================
    // COMPLIANCE MODULE (CBS-COMP-xxx)
    // =====================================================================
    public static final String COMP_AML_FLAG = "CBS-COMP-001";
    public static final String COMP_CTR_THRESHOLD = "CBS-COMP-002";
    public static final String COMP_PSL_VIOLATION = "CBS-COMP-003";

    // =====================================================================
    // SEVERITY CONSTANTS
    // =====================================================================
    public static final String SEVERITY_LOW = "LOW";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_CRITICAL = "CRITICAL";
}

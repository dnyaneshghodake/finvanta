package com.finvanta.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * CBS Controlled Operational Context (COC) per Finacle USER_SESSION / Temenos
 * EB.USER.CONTEXT — the complete security and operational envelope returned
 * to the Next.js BFF on successful API login.
 *
 * <p>Per RBI IT Governance Direction 2023 §8.1, §8.3, §8.4: a user is not
 * merely authenticated — they are activated within a specific tenant, branch,
 * business day, role scope, financial authority perimeter, and compliance
 * boundary. This record carries the <b>sanitized</b> subset of that context
 * safe for the BFF to cache in its server-side session.
 *
 * <h3>What is NOT included (server-controlled only):</h3>
 * <ul>
 *   <li>Full permission deny list (DENY grants stay server-side)</li>
 *   <li>Override privilege flags</li>
 *   <li>Compliance flags (AML/sanction — future)</li>
 *   <li>Password hash, MFA secret, or any authentication credential</li>
 * </ul>
 *
 * <h3>Design rationale:</h3>
 * <ul>
 *   <li>Single round-trip hydration — BFF calls POST /auth/token (or /mfa/verify)
 *       once and receives tokens + full COC. No N+1 API calls post-login.</li>
 *   <li>Immutable record — no setters, no mutation after construction.</li>
 *   <li>Null-safe serialization via {@code @JsonInclude(NON_NULL)}.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginSessionContext(
        TokenInfo token,
        UserContext user,
        BranchContext branch,
        BusinessDayContext businessDay,
        RoleContext role,
        LimitsContext limits,
        OperationalConfig operationalConfig,
        List<FeatureFlagEntry> featureFlags) {

    // === A. Token Info (transport layer) ===

    public record TokenInfo(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresAt) {}

    // === B. Identity + Organizational Context ===

    /**
     * Sanitized user identity per Finacle USER_MASTER.
     * Per RBI IT Governance Direction 2023 §8.1: no PII beyond display name.
     * employeeId is included for HR linkage on internal CBS UIs.
     */
    public record UserContext(
            Long userId,
            String username,
            String displayName,
            String authenticationLevel,
            LocalDateTime loginTimestamp,
            LocalDateTime lastLoginTimestamp,
            LocalDate passwordExpiryDate,
            boolean mfaEnabled) {}

    // === C. Branch Context ===

    /**
     * Operational branch envelope per Finacle SOL / Temenos COMPANY.
     * Per RBI Banking Regulation Act 1949 §23: every operation must be
     * traceable to a specific licensed branch.
     */
    public record BranchContext(
            Long branchId,
            String branchCode,
            String branchName,
            String ifscCode,
            String branchType,
            String zoneCode,
            String regionCode,
            boolean headOffice) {}

    // === D. Business Calendar Context (CRITICAL) ===

    /**
     * Per Finacle DAYCTRL / Temenos COB: system date ≠ business date.
     * The UI must render controls based on dayStatus:
     *   DAY_OPEN     → transactions allowed
     *   EOD_RUNNING  → read-only banner
     *   DAY_CLOSED   → disable all posting buttons
     *   NOT_OPENED   → prompt admin to open day
     *
     * previousBusinessDate is needed for reversal handling.
     * nextBusinessDate is needed for forward-dating validation.
     */
    public record BusinessDayContext(
            LocalDate businessDate,
            String dayStatus,
            boolean isHoliday,
            LocalDate previousBusinessDate,
            LocalDate nextBusinessDate) {}

    // === E. Role & Authorization Matrix ===

    /**
     * Granular permission matrix per Finacle AUTH_ROLE_PERM.
     * Per RBI IT Governance Direction 2023 §8.3: segregation of duties
     * must be enforced — MAKER cannot see approval screens, CHECKER
     * cannot see create screens.
     *
     * <p>permissionsByModule groups ALLOW-granted permission codes by
     * module so the UI can render module-level menu visibility and
     * action-level button visibility without additional API calls.
     *
     * <p>makerCheckerRole is derived: if user has any *_CREATE but no
     * *_APPROVE → MAKER. If *_APPROVE but no *_CREATE → CHECKER.
     * If both → BOTH (ADMIN). If neither → VIEWER (AUDITOR).
     */
    public record RoleContext(
            String role,
            String makerCheckerRole,
            Map<String, List<String>> permissionsByModule,
            List<String> allowedModules) {}

    // === F. Financial Authority Limits ===

    /**
     * Per Finacle TRAN_AUTH / Temenos LIMIT.CHECK: financial authority
     * limits loaded dynamically from the limit engine. The UI uses these
     * for client-side pre-validation (highlight amount fields, show
     * warnings) but the server re-validates on every transaction.
     *
     * <p>Per RBI Internal Controls: limits are role-based, branch-based,
     * and currency-aware. Only the user's applicable limits are returned.
     */
    public record LimitsContext(
            List<TransactionLimitEntry> transactionLimits) {}

    public record TransactionLimitEntry(
            String transactionType,
            String channel,
            BigDecimal perTransactionLimit,
            BigDecimal dailyAggregateLimit) {}

    // === G. Operational Configuration ===

    /**
     * Tenant-level operational parameters per Finacle BANK_PARAM.
     * The UI needs these for currency formatting, decimal precision,
     * and rounding in amount input fields.
     */
    public record OperationalConfig(
            String baseCurrency,
            int decimalPrecision,
            String roundingMode,
            int fiscalYearStartMonth,
            String businessDayPolicy) {}

    // === H. Feature Flags ===

    /**
     * Per RBI IT Governance Direction 2023: runtime feature availability.
     * The BFF uses these to:
     * <ul>
     *   <li>Show/hide payment rail options (NEFT, RTGS, IMPS, UPI)</li>
     *   <li>Show/hide product modules (Gold Loan, RD, Education Loan)</li>
     *   <li>Enable/disable system features (ISO20022, NEFT_24x7)</li>
     * </ul>
     *
     * <p>Per RBI: banks must be able to immediately disable a payment rail
     * during a security incident. The BFF must re-fetch feature flags on
     * bootstrap refresh to pick up runtime changes.
     */
    public record FeatureFlagEntry(
            String flagCode,
            String category,
            boolean enabled) {}
}

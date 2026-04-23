package com.finvanta.service;

import com.finvanta.api.LoginSessionContext;
import com.finvanta.api.LoginSessionContext.BranchContext;
import com.finvanta.api.LoginSessionContext.BusinessDayContext;
import com.finvanta.api.LoginSessionContext.FeatureFlagEntry;
import com.finvanta.api.LoginSessionContext.LimitsContext;
import com.finvanta.api.LoginSessionContext.OperationalConfig;
import com.finvanta.api.LoginSessionContext.RoleContext;
import com.finvanta.api.LoginSessionContext.TokenInfo;
import com.finvanta.api.LoginSessionContext.TransactionLimitEntry;
import com.finvanta.api.LoginSessionContext.UserContext;
import com.finvanta.domain.entity.FeatureFlag;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.RolePermission;
import com.finvanta.domain.entity.Tenant;
import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.FeatureFlagRepository;
import com.finvanta.repository.RolePermissionRepository;
import com.finvanta.repository.TenantRepository;
import com.finvanta.repository.TransactionLimitRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Session Context Assembler per Finacle USER_SESSION / Temenos EB.USER.CONTEXT.
 *
 * <p>Hydrates the full Controlled Operational Context (COC) for a successfully
 * authenticated user. Called exactly once per login (or MFA verify) — the result
 * is returned in the token response so the Next.js BFF can establish its
 * server-side session in a single round-trip.
 *
 * <h3>Tier-1 CBS Design Principles:</h3>
 * <ul>
 *   <li><b>Single round-trip:</b> no N+1 API calls post-login.</li>
 *   <li><b>Server-authoritative:</b> every field is loaded from the database,
 *       not from the JWT claims.</li>
 *   <li><b>Read-only transaction:</b> COC assembly is a pure read operation.
 *       {@code @Transactional(readOnly = true)} enables Hibernate dirty-check
 *       bypass and connection routing to read replicas in production.</li>
 *   <li><b>Graceful degradation:</b> if business calendar or limits are not
 *       yet configured for a branch, the COC still returns with null/empty
 *       sections rather than failing the login.</li>
 * </ul>
 *
 * <h3>Performance budget (1M+ daily logins):</h3>
 * <p>4-5 indexed queries, total &lt;10ms on PostgreSQL. No N+1. No full-table scans.
 */
@Service
public class SessionContextService {

    private static final Logger log = LoggerFactory.getLogger(SessionContextService.class);

    private final AppUserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final BusinessCalendarRepository calendarRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final TransactionLimitRepository transactionLimitRepository;
    private final FeatureFlagRepository featureFlagRepository;

    public SessionContextService(
            AppUserRepository userRepository,
            TenantRepository tenantRepository,
            BusinessCalendarRepository calendarRepository,
            RolePermissionRepository rolePermissionRepository,
            TransactionLimitRepository transactionLimitRepository,
            FeatureFlagRepository featureFlagRepository) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.calendarRepository = calendarRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.transactionLimitRepository = transactionLimitRepository;
        this.featureFlagRepository = featureFlagRepository;
    }

    /**
     * Assemble the full Controlled Operational Context for a successfully
     * authenticated user. Called from the login path where AppUser + tokens
     * are already available.
     *
     * @param user         The authenticated AppUser (with branch eagerly loaded)
     * @param tenantId     The resolved tenant code
     * @param accessToken  The issued JWT access token
     * @param refreshToken The issued JWT refresh token
     * @param expiresAt    Access token expiry epoch seconds
     * @param authLevel    Authentication level: "PASSWORD" or "MFA"
     * @return Complete COC for the BFF — never null
     */
    @Transactional(readOnly = true)
    public LoginSessionContext assemble(
            AppUser user, String tenantId,
            String accessToken, String refreshToken,
            long expiresAt, String authLevel) {

        return new LoginSessionContext(
                buildTokenInfo(accessToken, refreshToken, expiresAt),
                buildUserContext(user, authLevel),
                buildBranchContext(user.getBranch()),
                buildBusinessDayContext(tenantId, user.getBranch()),
                buildRoleContext(tenantId, user),
                buildLimitsContext(tenantId, user),
                buildOperationalConfig(tenantId),
                buildFeatureFlags(tenantId));
    }

    /**
     * Assemble the COC from the current SecurityContext (JWT claims).
     *
     * <p>Called by {@code GET /api/v1/context/bootstrap} AFTER login.
     * Per Tier-1 CBS: login returns only identity + tokens; the operational
     * context is fetched separately via this dedicated endpoint.
     *
     * <p>The JWT carries username/tenant — we re-fetch the AppUser from DB
     * to get current role, branch, and status (not stale JWT claims).
     *
     * <p>Token fields are null in the bootstrap response because the BFF
     * already has the tokens from the login response. The COC carries
     * everything EXCEPT tokens.
     */
    @Transactional(readOnly = true)
    public LoginSessionContext assembleFromSecurityContext() {
        String tenantId = com.finvanta.util.TenantContext.getCurrentTenant();
        String username = com.finvanta.util.SecurityUtil.getCurrentUsername();

        AppUser user = userRepository
                .findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new com.finvanta.util.BusinessException(
                        "USER_NOT_FOUND",
                        "User not found: " + username));

        // No tokens in bootstrap response — BFF already has them from login.
        return new LoginSessionContext(
                null, // tokens already held by BFF
                buildUserContext(user, "SESSION"),
                buildBranchContext(user.getBranch()),
                buildBusinessDayContext(tenantId, user.getBranch()),
                buildRoleContext(tenantId, user),
                buildLimitsContext(tenantId, user),
                buildOperationalConfig(tenantId),
                buildFeatureFlags(tenantId));
    }

    // ========================================================================
    // A. Token Info
    // ========================================================================

    private TokenInfo buildTokenInfo(String accessToken, String refreshToken,
            long expiresAt) {
        return new TokenInfo(accessToken, refreshToken, "Bearer", expiresAt);
    }

    // ========================================================================
    // B. User Identity Context
    // ========================================================================

    private UserContext buildUserContext(AppUser user, String authLevel) {
        return new UserContext(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                authLevel,
                LocalDateTime.now(),
                user.getLastLoginAt(),
                user.getPasswordExpiryDate(),
                user.isMfaEnabled());
    }

    // ========================================================================
    // C. Branch Context
    // ========================================================================

    private BranchContext buildBranchContext(Branch branch) {
        if (branch == null) {
            // HO/system users may not have a branch assignment.
            // Per Finacle: HO users operate in "all-branch" mode.
            return null;
        }
        return new BranchContext(
                branch.getId(),
                branch.getBranchCode(),
                branch.getBranchName(),
                branch.getIfscCode(),
                branch.getBranchType() != null
                        ? branch.getBranchType().name() : null,
                branch.getZoneCode(),
                branch.getRegionCode(),
                branch.isHO());
    }

    // ========================================================================
    // D. Business Calendar Context (CRITICAL — system date != business date)
    // ========================================================================

    /**
     * Per Finacle DAYCTRL: business date is PER BRANCH. If no day is open,
     * the UI must show "Day not opened" and disable transaction buttons.
     * This method never throws — it returns null fields for missing data
     * so the login itself does not fail.
     */
    private BusinessDayContext buildBusinessDayContext(String tenantId,
            Branch branch) {
        if (branch == null) {
            return null;
        }

        Long branchId = branch.getId();

        // Current open day at this branch
        BusinessCalendar openDay = calendarRepository
                .findOpenDayByBranch(tenantId, branchId)
                .orElse(null);

        if (openDay == null) {
            // No day open — return minimal context so UI can show warning.
            return new BusinessDayContext(
                    null, "NOT_OPENED", false, null, null);
        }

        LocalDate businessDate = openDay.getBusinessDate();

        // Previous business date: last completed EOD date at this branch.
        // Per Finacle: needed for reversal handling and T-1 reference.
        LocalDate previousBusinessDate = calendarRepository
                .findLastCompletedEodDateByBranch(tenantId, branchId)
                .orElse(null);

        // Next business date: next non-holiday date at this branch.
        // Per Finacle: needed for forward-dating validation.
        LocalDate nextBusinessDate = calendarRepository
                .findNextBusinessDayOnOrAfterByBranch(
                        tenantId, branchId,
                        businessDate.plusDays(1))
                .orElse(null);

        return new BusinessDayContext(
                businessDate,
                openDay.getDayStatus().name(),
                openDay.isHoliday(),
                previousBusinessDate,
                nextBusinessDate);
    }

    // ========================================================================
    // E. Role & Permission Matrix
    // ========================================================================

    /**
     * Per Finacle AUTH_ROLE_PERM: load all ALLOW-granted permissions for the
     * user's role, group by module, and derive the maker-checker classification.
     */
    private RoleContext buildRoleContext(String tenantId, AppUser user) {
        String roleName = user.getRole().name();

        List<RolePermission> grants = rolePermissionRepository
                .findActivePermissionsByRole(tenantId, user.getRole());

        // Group ALLOW-granted permission codes by module.
        // DENY grants stay server-side (CbsPermissionEvaluator).
        Map<String, List<String>> permissionsByModule = grants.stream()
                .filter(rp -> "ALLOW".equals(rp.getGrantType()))
                .collect(Collectors.groupingBy(
                        rp -> rp.getPermission().getModule(),
                        LinkedHashMap::new,
                        Collectors.mapping(
                                rp -> rp.getPermission().getPermissionCode(),
                                Collectors.toList())));

        List<String> allowedModules = new ArrayList<>(permissionsByModule.keySet());

        // Derive maker-checker classification from permission codes.
        List<String> allCodes = permissionsByModule.values().stream()
                .flatMap(List::stream)
                .toList();
        boolean hasCreate = allCodes.stream()
                .anyMatch(c -> c.contains("_CREATE") || c.contains("_INITIATE")
                        || c.contains("_OPEN") || c.contains("_DEPOSIT")
                        || c.contains("_WITHDRAW") || c.contains("_TRANSFER"));
        boolean hasApprove = allCodes.stream()
                .anyMatch(c -> c.contains("_APPROVE") || c.contains("_VERIFY")
                        || c.contains("_ACTIVATE") || c.contains("_SETTLE"));

        String makerCheckerRole;
        if (hasCreate && hasApprove) {
            makerCheckerRole = "BOTH";
        } else if (hasCreate) {
            makerCheckerRole = "MAKER";
        } else if (hasApprove) {
            makerCheckerRole = "CHECKER";
        } else {
            makerCheckerRole = "VIEWER";
        }

        return new RoleContext(roleName, makerCheckerRole,
                permissionsByModule, allowedModules);
    }

    // ========================================================================
    // F. Financial Authority Limits
    // ========================================================================

    /**
     * Per Finacle TRAN_AUTH: load all active limits for the user's role.
     * The UI uses these for client-side pre-validation only — the server
     * re-validates via {@code TransactionLimitService} on every transaction.
     */
    private LimitsContext buildLimitsContext(String tenantId, AppUser user) {
        String roleName = user.getRole().name();

        List<TransactionLimit> limits = transactionLimitRepository
                .findActiveByTenantIdAndRole(tenantId, roleName);

        if (limits.isEmpty()) {
            return new LimitsContext(List.of());
        }

        List<TransactionLimitEntry> entries = limits.stream()
                .map(tl -> new TransactionLimitEntry(
                        tl.getTransactionType(),
                        tl.getChannel(),
                        tl.getPerTransactionLimit(),
                        tl.getDailyAggregateLimit()))
                .toList();

        return new LimitsContext(entries);
    }

    // ========================================================================
    // G. Operational Configuration
    // ========================================================================

    /**
     * Per Finacle BANK_PARAM / Temenos COMPANY: tenant-level operational
     * parameters for currency formatting, precision, and fiscal year.
     */
    private OperationalConfig buildOperationalConfig(String tenantId) {
        Tenant tenant = tenantRepository.findByTenantCode(tenantId)
                .orElse(null);

        if (tenant == null) {
            // Fallback for DEFAULT tenant or missing config.
            return new OperationalConfig("INR", 2, "HALF_UP", 4, "MON_TO_SAT");
        }

        return new OperationalConfig(
                tenant.getBaseCurrency() != null
                        ? tenant.getBaseCurrency() : "INR",
                2, // Per RBI: INR precision is always 2 decimal places
                "HALF_UP", // Per RBI rounding rules for INR
                tenant.getFiscalYearStartMonth(),
                tenant.getBusinessDayPolicy());
    }

    // ========================================================================
    // H. Feature Flags
    // ========================================================================

    /**
     * Per RBI IT Governance Direction 2023: load all feature flags for the
     * tenant so the BFF can show/hide payment rails, product modules, and
     * system features based on runtime configuration.
     */
    private List<FeatureFlagEntry> buildFeatureFlags(String tenantId) {
        List<FeatureFlag> flags = featureFlagRepository.findByTenantId(tenantId);
        return flags.stream()
                .map(ff -> new FeatureFlagEntry(
                        ff.getFlagCode(),
                        ff.getCategory(),
                        ff.isEnabled()))
                .toList();
    }
}

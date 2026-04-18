package com.finvanta.config;

import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.repository.BranchRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.format.DateTimeFormatter;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;

/**
 * CBS Layout Model Advice — populates global model attributes for the topbar/sidebar.
 *
 * Per Finacle/Temenos Tier-1 CBS standards: every page must display:
 * - Current tenant ID (per RBI IT Governance §8.3 multi-tenant visibility)
 * - Current branch code (with switch capability for ADMIN)
 * - Current business date (from DAY_OPEN calendar entry)
 * - User role
 * - Branch list for ADMIN branch switch dropdown
 *
 * This @ControllerAdvice runs for every controller method and adds these
 * attributes to the model so the layout JSP can render them consistently.
 */
@ControllerAdvice
public class CbsLayoutAdvice {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final BusinessDateService businessDateService;
    private final BranchRepository branchRepository;

    public CbsLayoutAdvice(BusinessDateService businessDateService, BranchRepository branchRepository) {
        this.businessDateService = businessDateService;
        this.branchRepository = branchRepository;
    }

    /**
     * Current tenant ID for topbar display.
     * Per RBI IT Governance Direction 2023 §8.3: multi-tenant systems must always
     * display the active tenant so operators confirm they are in the correct context.
     * Matches Finacle TENANT_BANNER / Temenos COMPANY.ID header display.
     */
    @ModelAttribute("currentTenantId")
    public String currentTenantId() {
        try {
            if (TenantContext.isSet()) {
                return TenantContext.getCurrentTenant();
            }
        } catch (Exception e) {
            // Pre-auth — return default
        }
        return "--";
    }

    /** Current effective branch code (switched or home) */
    @ModelAttribute("userBranchCode")
    public String userBranchCode() {
        String code = SecurityUtil.getCurrentUserBranchCode();
        return code != null ? code : "--";
    }

    /**
     * Current user role for display.
     * Uses hasRole() to check all roles including AUDITOR, because
     * getCurrentUserRole() intentionally excludes AUDITOR from its return
     * values (to prevent transaction limit bypass). For display purposes,
     * we need to show the actual role.
     */
    @ModelAttribute("userRole")
    public String userRole() {
        if (SecurityUtil.hasRole("ADMIN")) return "ADMIN";
        if (SecurityUtil.hasRole("CHECKER")) return "CHECKER";
        if (SecurityUtil.hasRole("MAKER")) return "MAKER";
        if (SecurityUtil.hasRole("AUDITOR")) return "AUDITOR";
        return "USER";
    }

    /** Current business date formatted for display */
    @ModelAttribute("businessDate")
    public String businessDate() {
        try {
            BusinessCalendar openDay = businessDateService.getOpenDayOrNull();
            if (openDay != null) {
                return openDay.getBusinessDate().format(DATE_FORMAT);
            }
        } catch (Exception e) {
            // Pre-auth or no branch — return default
        }
        return "--";
    }

    /**
     * Server-side session timeout in seconds for the JS session countdown.
     *
     * Per Finacle/Temenos Tier-1: the client-side session warning timer MUST
     * match the server-side session timeout. Without this, the JS uses a
     * hardcoded fallback (1800s) which diverges from production (15m = 900s),
     * causing the warning to appear AFTER the server has already expired the
     * session — making the countdown useless.
     *
     * Reads from HttpSession.getMaxInactiveInterval() which reflects the
     * active profile's server.servlet.session.timeout value.
     */
    @ModelAttribute("sessionTimeoutSeconds")
    public int sessionTimeoutSeconds(HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            if (session != null) {
                return session.getMaxInactiveInterval();
            }
        } catch (Exception e) {
            // Pre-auth — return default
        }
        return 1800; // 30 min fallback
    }

    /**
     * All active branches for ADMIN branch switch dropdown.
     * Per Finacle SOL_SWITCH: only populated for ADMIN users to avoid
     * unnecessary DB queries for MAKER/CHECKER on every page load.
     */
    @ModelAttribute("allBranches")
    public Object allBranches(HttpServletRequest request) {
        try {
            if (request.isUserInRole("ROLE_ADMIN") && TenantContext.isSet()) {
                return branchRepository.findByTenantIdAndActiveTrue(TenantContext.getCurrentTenant());
            }
        } catch (Exception e) {
            // Pre-auth or tenant not set — return null
        }
        return null;
    }
}

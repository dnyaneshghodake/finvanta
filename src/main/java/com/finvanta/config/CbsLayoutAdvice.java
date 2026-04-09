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

    /** Current effective branch code (switched or home) */
    @ModelAttribute("userBranchCode")
    public String userBranchCode() {
        String code = SecurityUtil.getCurrentUserBranchCode();
        return code != null ? code : "--";
    }

    /** Current user role for display */
    @ModelAttribute("userRole")
    public String userRole() {
        String role = SecurityUtil.getCurrentUserRole();
        return role != null ? role : "USER";
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

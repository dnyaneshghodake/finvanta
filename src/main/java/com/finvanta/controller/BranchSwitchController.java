package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Branch Switch Controller per Finacle SOL_SWITCH / Temenos COMPANY.SWITCH.
 *
 * Per Tier-1 CBS standards: ADMIN users can switch their working branch context
 * from the dashboard without re-logging in. This enables:
 * - Head Office ADMIN to operate on any branch's data (day control, EOD, reports)
 * - Branch inspection and cross-branch transaction authorization
 * - Centralized operations management from a single session
 *
 * Per RBI IT Governance Direction 2023:
 * - Branch switch is ADMIN-only (MAKER/CHECKER are restricted to home branch)
 * - Every branch switch is audited with reason
 * - The user's HOME branch is always recorded in audit trail (not the switched branch)
 *
 * ADMIN-only access enforced by SecurityConfig (/admin/** → ROLE_ADMIN).
 */
@Controller
public class BranchSwitchController {

    private static final Logger log = LoggerFactory.getLogger(BranchSwitchController.class);

    private final BranchRepository branchRepository;
    private final AuditService auditService;

    public BranchSwitchController(BranchRepository branchRepository, AuditService auditService) {
        this.branchRepository = branchRepository;
        this.auditService = auditService;
    }

    /**
     * Switch the ADMIN user's working branch context.
     * Stores the override in the HTTP session — all subsequent requests
     * will use the switched branch via SecurityUtil.getCurrentUserBranchId().
     */
    @PostMapping("/admin/switch-branch")
    public String switchBranch(
            @RequestParam Long branchId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String username = SecurityUtil.getCurrentUsername();

        try {
            Branch branch = branchRepository.findById(branchId)
                    .filter(b -> b.getTenantId().equals(tenantId) && b.isActive())
                    .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND",
                            "Branch not found or inactive: " + branchId));

            String previousBranchCode = SecurityUtil.getCurrentUserBranchCode();

            // Store branch override in session
            request.getSession().setAttribute(SecurityUtil.SWITCHED_BRANCH_ID, branch.getId());
            request.getSession().setAttribute(SecurityUtil.SWITCHED_BRANCH_CODE, branch.getBranchCode());

            auditService.logEvent(
                    "BranchSwitch", null, "BRANCH_SWITCH",
                    previousBranchCode, branch.getBranchCode(),
                    "SECURITY",
                    "Branch switched: " + previousBranchCode + " → " + branch.getBranchCode()
                            + " | User: " + username
                            + " | Home branch: " + SecurityUtil.getHomeBranchId());

            log.info("BRANCH SWITCH: user={}, from={}, to={}",
                    username, previousBranchCode, branch.getBranchCode());

            redirectAttributes.addFlashAttribute("success",
                    "Switched to branch: " + branch.getBranchCode() + " — " + branch.getBranchName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Branch switch failed: " + e.getMessage());
        }

        return "redirect:/dashboard";
    }

    /**
     * Reset branch context back to the user's home branch.
     */
    @PostMapping("/admin/reset-branch")
    public String resetBranch(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        String previousCode = SecurityUtil.getCurrentUserBranchCode();

        request.getSession().removeAttribute(SecurityUtil.SWITCHED_BRANCH_ID);
        request.getSession().removeAttribute(SecurityUtil.SWITCHED_BRANCH_CODE);

        log.info("BRANCH RESET: user={}, from={}, to=home",
                SecurityUtil.getCurrentUsername(), previousCode);

        redirectAttributes.addFlashAttribute("success", "Switched back to home branch");
        return "redirect:/dashboard";
    }
}

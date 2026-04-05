package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import com.finvanta.util.SecurityUtil;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Branch Management Controller.
 * Per Finacle/Temenos, branch is the primary organizational unit.
 * Supports: List, Add, View (with cross-links), Edit.
 */
@Controller
@RequestMapping("/branch")
public class BranchController {

    private final BranchRepository branchRepository;
    private final CustomerRepository customerRepository;
    private final LoanAccountRepository accountRepository;
    private final AuditService auditService;

    public BranchController(BranchRepository branchRepository,
                             CustomerRepository customerRepository,
                             LoanAccountRepository accountRepository,
                             AuditService auditService) {
        this.branchRepository = branchRepository;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    @GetMapping("/list")
    public ModelAndView listBranches() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("branch/list");
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        ModelAndView mav = new ModelAndView("branch/add");
        mav.addObject("branch", new Branch());
        return mav;
    }

    @PostMapping("/add")
    @Transactional
    public String addBranch(@ModelAttribute Branch branch, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            branch.setTenantId(tenantId);
            branch.setCreatedBy(currentUser);
            Branch saved = branchRepository.save(branch);

            auditService.logEvent("Branch", saved.getId(), "CREATE",
                null, saved.getBranchCode(), "BRANCH",
                "Branch created: " + saved.getBranchCode() + " — " + saved.getBranchName());

            redirectAttributes.addFlashAttribute("success", "Branch added: " + branch.getBranchCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/branch/list";
    }

    /** CBS Branch View — shows branch details with customer, account, and portfolio cross-links */
    @GetMapping("/view/{id}")
    public ModelAndView viewBranch(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        Branch branch = branchRepository.findById(id)
            .filter(b -> b.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException(
                "BRANCH_NOT_FOUND", "Branch not found: " + id));
        ModelAndView mav = new ModelAndView("branch/view");
        mav.addObject("branch", branch);
        mav.addObject("customers",
            customerRepository.findByTenantIdAndBranchIdAndActiveTrue(tenantId, id));
        mav.addObject("totalOutstanding",
            accountRepository.calculateTotalOutstandingByBranch(tenantId, id));

        // CBS Branch Portfolio: loan accounts at this branch with cross-links
        var branchAccounts = accountRepository.findByTenantIdAndBranchId(tenantId, id);
        mav.addObject("loanAccounts", branchAccounts);

        // CBS Branch NPA Summary: count by status for branch-level risk view
        long npaCount = branchAccounts.stream()
            .filter(a -> a.getStatus().isNpa()).count();
        long smaCount = branchAccounts.stream()
            .filter(a -> a.getStatus().isSma()).count();
        mav.addObject("npaCount", npaCount);
        mav.addObject("smaCount", smaCount);
        mav.addObject("activeCount", branchAccounts.stream()
            .filter(a -> !a.getStatus().isTerminal()).count());

        return mav;
    }

    /** CBS Branch Edit — ADMIN only */
    @GetMapping("/edit/{id}")
    public ModelAndView showEditForm(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        Branch branch = branchRepository.findById(id)
            .filter(b -> b.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException(
                "BRANCH_NOT_FOUND", "Branch not found: " + id));
        ModelAndView mav = new ModelAndView("branch/edit");
        mav.addObject("branch", branch);
        return mav;
    }

    @PostMapping("/edit/{id}")
    @Transactional
    public String updateBranch(@PathVariable Long id,
                                @ModelAttribute Branch updated,
                                RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Branch existing = branchRepository.findById(id)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                    "BRANCH_NOT_FOUND", "Branch not found: " + id));

            existing.setBranchName(updated.getBranchName());
            existing.setIfscCode(updated.getIfscCode());
            existing.setAddress(updated.getAddress());
            existing.setCity(updated.getCity());
            existing.setState(updated.getState());
            existing.setPinCode(updated.getPinCode());
            existing.setRegion(updated.getRegion());
            existing.setUpdatedBy(currentUser);
            branchRepository.save(existing);

            auditService.logEvent("Branch", existing.getId(), "UPDATE",
                null, existing.getBranchCode(), "BRANCH",
                "Branch updated by " + currentUser);

            redirectAttributes.addFlashAttribute("success",
                "Branch updated: " + existing.getBranchCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/branch/view/" + id;
    }
}

package com.finvanta.controller;

import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.BranchService;
import com.finvanta.util.TenantContext;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Branch Management Controller.
 * Per Finacle/Temenos, branch is the primary organizational unit.
 * Supports: List, Add, View (with cross-links), Edit.
 *
 * CBS Code Quality: No @Transactional on controller methods.
 * All business logic and transactions are managed by BranchService.
 * Controller only handles HTTP request/response mapping and view delegation.
 */
@Controller
@RequestMapping("/branch")
public class BranchController {

    private final BranchService branchService;
    private final CustomerRepository customerRepository;
    private final LoanAccountRepository accountRepository;

    public BranchController(
            BranchService branchService,
            CustomerRepository customerRepository,
            LoanAccountRepository accountRepository) {
        this.branchService = branchService;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/list")
    public ModelAndView listBranches() {
        ModelAndView mav = new ModelAndView("branch/list");
        mav.addObject("branches", branchService.listActiveBranches());
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        ModelAndView mav = new ModelAndView("branch/add");
        mav.addObject("branch", new Branch());
        return mav;
    }

    @PostMapping("/add")
    public String addBranch(@ModelAttribute Branch branch, RedirectAttributes redirectAttributes) {
        try {
            Branch saved = branchService.createBranch(branch);
            redirectAttributes.addFlashAttribute("success", "Branch added: " + saved.getBranchCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/branch/list";
    }

    /** CBS Branch View — shows branch details with customer, account, and portfolio cross-links */
    @GetMapping("/view/{id}")
    public ModelAndView viewBranch(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        Branch branch = branchService.getBranch(id);
        ModelAndView mav = new ModelAndView("branch/view");
        mav.addObject("branch", branch);
        mav.addObject("customers", customerRepository.findByTenantIdAndBranchIdAndActiveTrue(tenantId, id));
        mav.addObject("totalOutstanding", accountRepository.calculateTotalOutstandingByBranch(tenantId, id));

        // CBS Branch Portfolio: loan accounts at this branch with cross-links
        var branchAccounts = accountRepository.findByTenantIdAndBranchId(tenantId, id);
        mav.addObject("loanAccounts", branchAccounts);

        // CBS Branch NPA Summary: count by status for branch-level risk view
        long npaCount =
                branchAccounts.stream().filter(a -> a.getStatus().isNpa()).count();
        long smaCount =
                branchAccounts.stream().filter(a -> a.getStatus().isSma()).count();
        mav.addObject("npaCount", npaCount);
        mav.addObject("smaCount", smaCount);
        mav.addObject(
                "activeCount",
                branchAccounts.stream().filter(a -> !a.getStatus().isTerminal()).count());

        return mav;
    }

    /** CBS Branch Edit — ADMIN only */
    @GetMapping("/edit/{id}")
    public ModelAndView showEditForm(@PathVariable Long id) {
        Branch branch = branchService.getBranch(id);
        ModelAndView mav = new ModelAndView("branch/edit");
        mav.addObject("branch", branch);
        return mav;
    }

    @PostMapping("/edit/{id}")
    public String updateBranch(
            @PathVariable Long id, @ModelAttribute Branch updated, RedirectAttributes redirectAttributes) {
        try {
            Branch saved = branchService.updateBranch(id, updated);
            redirectAttributes.addFlashAttribute("success", "Branch updated: " + saved.getBranchCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/branch/view/" + id;
    }
}

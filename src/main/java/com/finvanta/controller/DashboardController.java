package com.finvanta.controller;

import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class DashboardController {

    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final ApprovalWorkflowService workflowService;

    public DashboardController(LoanApplicationRepository applicationRepository,
                                LoanAccountRepository accountRepository,
                                ApprovalWorkflowService workflowService) {
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.workflowService = workflowService;
    }

    @GetMapping("/dashboard")
    public ModelAndView dashboard() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("dashboard/index");

        mav.addObject("pendingApplications",
            applicationRepository.findByTenantIdAndStatus(tenantId, ApplicationStatus.SUBMITTED).size());
        mav.addObject("activeLoans",
            accountRepository.findByTenantIdAndStatus(tenantId, LoanStatus.ACTIVE).size());
        mav.addObject("npaAccounts",
            accountRepository.findByTenantIdAndStatus(tenantId, LoanStatus.NPA_SUBSTANDARD).size()
            + accountRepository.findByTenantIdAndStatus(tenantId, LoanStatus.NPA_DOUBTFUL).size()
            + accountRepository.findByTenantIdAndStatus(tenantId, LoanStatus.NPA_LOSS).size());
        mav.addObject("pendingApprovals",
            workflowService.getPendingApprovals().size());
        mav.addObject("totalOutstanding",
            accountRepository.calculateTotalOutstandingPrincipal(tenantId));

        return mav;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}

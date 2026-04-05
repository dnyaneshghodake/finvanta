package com.finvanta.controller;

import com.finvanta.repository.CustomerRepository;
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
    private final CustomerRepository customerRepository;
    private final ApprovalWorkflowService workflowService;

    public DashboardController(LoanApplicationRepository applicationRepository,
                                LoanAccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                ApprovalWorkflowService workflowService) {
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.workflowService = workflowService;
    }

    @GetMapping("/dashboard")
    public ModelAndView dashboard() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("dashboard/index");

        mav.addObject("totalCustomers",
            customerRepository.countByTenantIdAndActiveTrue(tenantId));
        mav.addObject("pendingApplications",
            applicationRepository.countByTenantIdAndStatus(tenantId, ApplicationStatus.SUBMITTED));
        mav.addObject("activeLoans",
            accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.ACTIVE));
        mav.addObject("npaAccounts",
            accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.NPA_SUBSTANDARD)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.NPA_DOUBTFUL)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.NPA_LOSS));
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

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
            accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.ACTIVE)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.SMA_0)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.SMA_1)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.SMA_2)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.RESTRUCTURED));
        mav.addObject("smaAccounts",
            accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.SMA_0)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.SMA_1)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.SMA_2));
        mav.addObject("npaAccounts",
            accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.NPA_SUBSTANDARD)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.NPA_DOUBTFUL)
            + accountRepository.countByTenantIdAndStatus(tenantId, LoanStatus.NPA_LOSS));
        mav.addObject("pendingApprovals",
            workflowService.getPendingApprovals().size());
        java.math.BigDecimal totalOutstanding = accountRepository.calculateTotalOutstandingPrincipal(tenantId);
        java.math.BigDecimal npaOutstanding = accountRepository.calculateTotalNpaOutstanding(tenantId);
        java.math.BigDecimal totalProvisioning = accountRepository.calculateTotalProvisioning(tenantId);

        mav.addObject("totalOutstanding", totalOutstanding);
        mav.addObject("npaOutstanding", npaOutstanding);
        mav.addObject("totalProvisioning", totalProvisioning);

        // RBI Key Ratios: Gross NPA % and Provision Coverage %
        mav.addObject("grossNpaRatio",
            totalOutstanding.compareTo(java.math.BigDecimal.ZERO) > 0
                ? npaOutstanding.multiply(java.math.BigDecimal.valueOf(100))
                    .divide(totalOutstanding, 2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO);
        mav.addObject("provisionCoverage",
            npaOutstanding.compareTo(java.math.BigDecimal.ZERO) > 0
                ? totalProvisioning.multiply(java.math.BigDecimal.valueOf(100))
                    .divide(npaOutstanding, 2, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO);

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

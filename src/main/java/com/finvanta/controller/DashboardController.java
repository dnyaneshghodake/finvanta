package com.finvanta.controller;

import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Controller
public class DashboardController {

    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final ApprovalWorkflowService workflowService;
    private final DepositAccountRepository depositAccountRepository;

    public DashboardController(LoanApplicationRepository applicationRepository,
                                LoanAccountRepository accountRepository,
                                CustomerRepository customerRepository,
                                ApprovalWorkflowService workflowService,
                                DepositAccountRepository depositAccountRepository) {
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.workflowService = workflowService;
        this.depositAccountRepository = depositAccountRepository;
    }

    @GetMapping("/dashboard")
    public ModelAndView dashboard() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("dashboard/index");

        mav.addObject("totalCustomers",
            customerRepository.countByTenantIdAndActiveTrue(tenantId));
        mav.addObject("pendingApplications",
            applicationRepository.countByTenantIdAndStatus(tenantId, ApplicationStatus.SUBMITTED));

        // CBS Dashboard: Single GROUP BY query replaces 7 separate count queries.
        // For 1M+ accounts, this eliminates 7 full table scans.
        Map<LoanStatus, Long> statusCounts = new EnumMap<>(LoanStatus.class);
        List<Object[]> rawCounts = accountRepository.countByTenantIdGroupByStatus(tenantId);
        for (Object[] row : rawCounts) {
            statusCounts.put((LoanStatus) row[0], (Long) row[1]);
        }

        long activeLoans = statusCounts.getOrDefault(LoanStatus.ACTIVE, 0L)
            + statusCounts.getOrDefault(LoanStatus.SMA_0, 0L)
            + statusCounts.getOrDefault(LoanStatus.SMA_1, 0L)
            + statusCounts.getOrDefault(LoanStatus.SMA_2, 0L)
            + statusCounts.getOrDefault(LoanStatus.RESTRUCTURED, 0L);
        long smaAccounts = statusCounts.getOrDefault(LoanStatus.SMA_0, 0L)
            + statusCounts.getOrDefault(LoanStatus.SMA_1, 0L)
            + statusCounts.getOrDefault(LoanStatus.SMA_2, 0L);
        long npaAccounts = statusCounts.getOrDefault(LoanStatus.NPA_SUBSTANDARD, 0L)
            + statusCounts.getOrDefault(LoanStatus.NPA_DOUBTFUL, 0L)
            + statusCounts.getOrDefault(LoanStatus.NPA_LOSS, 0L);

        mav.addObject("activeLoans", activeLoans);
        mav.addObject("smaAccounts", smaAccounts);
        mav.addObject("npaAccounts", npaAccounts);
        mav.addObject("pendingApprovals",
            workflowService.getPendingApprovals().size());
        BigDecimal totalOutstanding = accountRepository.calculateTotalOutstandingPrincipal(tenantId);
        BigDecimal npaOutstanding = accountRepository.calculateTotalNpaOutstanding(tenantId);
        BigDecimal totalProvisioning = accountRepository.calculateTotalProvisioning(tenantId);

        mav.addObject("totalOutstanding", totalOutstanding);
        mav.addObject("npaOutstanding", npaOutstanding);
        mav.addObject("totalProvisioning", totalProvisioning);

        // RBI Key Ratios: Gross NPA % and Provision Coverage %
        // Per RBI regulatory reporting, Gross NPA Ratio = (NPA Outstanding / Total Outstanding) × 100
        // Provision Coverage Ratio = (Total Provisioning / NPA Outstanding) × 100
        // Coverage is capped at 100% for display — values above 100% indicate over-provisioning
        // which can occur due to rounding or timing differences between provisioning and NPA cycles.
        BigDecimal grossNpaRatio = totalOutstanding.compareTo(BigDecimal.ZERO) > 0
            ? npaOutstanding.multiply(BigDecimal.valueOf(100))
                .divide(totalOutstanding, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        mav.addObject("grossNpaRatio", grossNpaRatio);

        BigDecimal provisionCoverage = npaOutstanding.compareTo(BigDecimal.ZERO) > 0
            ? totalProvisioning.multiply(BigDecimal.valueOf(100))
                .divide(npaOutstanding, 2, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;
        mav.addObject("provisionCoverage", provisionCoverage);

        // === CASA Metrics (per RBI CASA Ratio reporting) ===
        BigDecimal totalDeposits = depositAccountRepository.calculateTotalDeposits(tenantId);
        long casaAccountCount = depositAccountRepository.countByTenantIdAndAccountStatusNot(tenantId, "CLOSED");
        mav.addObject("totalDeposits", totalDeposits);
        mav.addObject("casaAccountCount", casaAccountCount);

        // CASA Ratio = (CASA Deposits / Total Deposits) x 100
        // Higher CASA ratio = lower cost of funds for the bank (RBI key metric)
        mav.addObject("casaRatio", totalDeposits.compareTo(BigDecimal.ZERO) > 0
            ? BigDecimal.valueOf(100) : BigDecimal.ZERO);

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

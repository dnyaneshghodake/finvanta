package com.finvanta.controller;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanApplicationService;
import com.finvanta.util.TenantContext;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

@Controller
@RequestMapping("/loan")
public class LoanController {

    private final LoanApplicationService applicationService;
    private final LoanAccountService accountService;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final LoanTransactionRepository transactionRepository;

    public LoanController(LoanApplicationService applicationService,
                           LoanAccountService accountService,
                           CustomerRepository customerRepository,
                           BranchRepository branchRepository,
                           LoanTransactionRepository transactionRepository) {
        this.applicationService = applicationService;
        this.accountService = accountService;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/apply")
    public ModelAndView showApplicationForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("loan/apply");
        mav.addObject("application", new LoanApplication());
        mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    @PostMapping("/apply")
    public ModelAndView submitApplication(@Valid @ModelAttribute("application") LoanApplication application,
                                          BindingResult result,
                                          @RequestParam Long customerId,
                                          @RequestParam Long branchId,
                                          RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            String tenantId = TenantContext.getCurrentTenant();
            ModelAndView mav = new ModelAndView("loan/apply");
            mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
            mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
            return mav;
        }

        try {
            LoanApplication saved = applicationService.createApplication(application, customerId, branchId);
            redirectAttributes.addFlashAttribute("success",
                "Application created: " + saved.getApplicationNumber());
            return new ModelAndView("redirect:/loan/applications");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return new ModelAndView("redirect:/loan/apply");
        }
    }

    @GetMapping("/applications")
    public ModelAndView listApplications() {
        ModelAndView mav = new ModelAndView("loan/applications");
        mav.addObject("applications",
            applicationService.getApplicationsByStatus(ApplicationStatus.SUBMITTED));
        mav.addObject("verifiedApplications",
            applicationService.getApplicationsByStatus(ApplicationStatus.VERIFIED));
        mav.addObject("approvedApplications",
            applicationService.getApplicationsByStatus(ApplicationStatus.APPROVED));
        return mav;
    }

    @GetMapping("/verify/{id}")
    public ModelAndView showVerifyForm(@PathVariable Long id) {
        ModelAndView mav = new ModelAndView("loan/verify");
        mav.addObject("application", applicationService.getApplication(id));
        return mav;
    }

    @PostMapping("/verify/{id}")
    public String verifyApplication(@PathVariable Long id,
                                     @RequestParam String remarks,
                                     RedirectAttributes redirectAttributes) {
        try {
            applicationService.verifyApplication(id, remarks);
            redirectAttributes.addFlashAttribute("success", "Application verified successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/applications";
    }

    @GetMapping("/approve/{id}")
    public ModelAndView showApproveForm(@PathVariable Long id) {
        ModelAndView mav = new ModelAndView("loan/approve");
        mav.addObject("application", applicationService.getApplication(id));
        return mav;
    }

    @PostMapping("/approve/{id}")
    public String approveApplication(@PathVariable Long id,
                                      @RequestParam String remarks,
                                      RedirectAttributes redirectAttributes) {
        try {
            applicationService.approveApplication(id, remarks);
            redirectAttributes.addFlashAttribute("success", "Application approved successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/applications";
    }

    @PostMapping("/reject/{id}")
    public String rejectApplication(@PathVariable Long id,
                                     @RequestParam String reason,
                                     RedirectAttributes redirectAttributes) {
        try {
            applicationService.rejectApplication(id, reason);
            redirectAttributes.addFlashAttribute("success", "Application rejected");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/applications";
    }

    @GetMapping("/accounts")
    public ModelAndView listAccounts() {
        ModelAndView mav = new ModelAndView("loan/accounts");
        mav.addObject("accounts", accountService.getActiveAccounts());
        return mav;
    }

    @GetMapping("/account/{accountNumber}")
    public ModelAndView accountDetails(@PathVariable String accountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        LoanAccount account = accountService.getAccount(accountNumber);
        ModelAndView mav = new ModelAndView("loan/account-details");
        mav.addObject("account", account);
        mav.addObject("transactions",
            transactionRepository.findByTenantIdAndLoanAccountIdOrderByPostingDateDesc(tenantId, account.getId()));
        return mav;
    }

    @PostMapping("/disburse/{accountNumber}")
    public String disburseLoan(@PathVariable String accountNumber,
                                RedirectAttributes redirectAttributes) {
        try {
            accountService.disburseLoan(accountNumber);
            redirectAttributes.addFlashAttribute("success", "Loan disbursed successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }

    @PostMapping("/repayment/{accountNumber}")
    public String processRepayment(@PathVariable String accountNumber,
                                    @RequestParam BigDecimal amount,
                                    RedirectAttributes redirectAttributes) {
        try {
            accountService.processRepayment(accountNumber, amount, LocalDate.now());
            redirectAttributes.addFlashAttribute("success", "Repayment processed successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }

    @PostMapping("/create-account/{applicationId}")
    public String createAccount(@PathVariable Long applicationId,
                                 RedirectAttributes redirectAttributes) {
        try {
            LoanAccount account = accountService.createLoanAccount(applicationId);
            redirectAttributes.addFlashAttribute("success",
                "Account created: " + account.getAccountNumber());
            return "redirect:/loan/account/" + account.getAccountNumber();
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/loan/applications";
        }
    }
}

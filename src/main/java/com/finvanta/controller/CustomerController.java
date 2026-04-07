package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.TenantContext;
import com.finvanta.util.SecurityUtil;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Customer Information File (CIF) Controller.
 * Per Finacle/Temenos, CIF is the master record for all customer interactions.
 * Supports: Create (auto-number), View, Edit, KYC Verify, Deactivate.
 */
@Controller
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final AuditService auditService;
    private final BusinessDateService businessDateService;

    public CustomerController(CustomerRepository customerRepository,
                               BranchRepository branchRepository,
                               LoanApplicationRepository applicationRepository,
                               LoanAccountRepository accountRepository,
                               AuditService auditService,
                               BusinessDateService businessDateService) {
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.businessDateService = businessDateService;
    }

    /**
     * CBS Customer List with branch isolation per Finacle BRANCH_CONTEXT.
     * MAKER/CHECKER: see only customers at their home branch.
     * ADMIN: sees all customers across all branches.
     */
    @GetMapping("/list")
    public ModelAndView listCustomers() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/list");

        if (SecurityUtil.isAdminRole()) {
            mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                mav.addObject("customers",
                    customerRepository.findByTenantIdAndBranchIdAndActiveTrue(tenantId, branchId));
            } else {
                // CBS: No branch assigned — show empty list per fail-safe principle.
                // Per RBI Operational Risk: no-branch users must not see all data.
                mav.addObject("customers", java.util.Collections.emptyList());
            }
        }
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/add");
        mav.addObject("customer", new Customer());
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    /**
     * CBS CIF Creation — auto-generates customer number per Finacle convention.
     * Format: CUST + branchCode + timestamp + sequence
     */
    @PostMapping("/add")
    @Transactional
    public String addCustomer(@ModelAttribute Customer customer,
                               @RequestParam Long branchId,
                               RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Branch branch = branchRepository.findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                    "BRANCH_NOT_FOUND", "Branch not found: " + branchId));

            customer.setCustomerNumber(ReferenceGenerator.generateCustomerNumber(branch.getBranchCode()));
            customer.setTenantId(tenantId);
            customer.setBranch(branch);
            customer.setCreatedBy(currentUser);
            Customer saved = customerRepository.save(customer);

            auditService.logEvent("Customer", saved.getId(), "CREATE",
                null, saved.getCustomerNumber(), "CIF",
                "Customer created: " + saved.getFullName() + " at branch " + branch.getBranchCode());

            redirectAttributes.addFlashAttribute("success",
                "Customer created: " + saved.getCustomerNumber() + " - " + saved.getFullName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/list";
    }

    @GetMapping("/view/{id}")
    public ModelAndView viewCustomer(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/view");
        Customer customer = customerRepository.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException(
                "CUSTOMER_NOT_FOUND", "Customer not found: " + id));
        mav.addObject("customer", customer);
        mav.addObject("loanApplications",
            applicationRepository.findByTenantIdAndCustomerId(tenantId, id));
        mav.addObject("loanAccounts",
            accountRepository.findByTenantIdAndCustomerId(tenantId, id));
        return mav;
    }

    /** CBS CIF Edit — pre-populated form for mutable fields */
    @GetMapping("/edit/{id}")
    public ModelAndView showEditForm(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        Customer customer = customerRepository.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException(
                "CUSTOMER_NOT_FOUND", "Customer not found: " + id));
        ModelAndView mav = new ModelAndView("customer/edit");
        mav.addObject("customer", customer);
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    /**
     * CBS CIF Update — mutable fields only.
     * Customer number, PAN, Aadhaar are immutable after creation per RBI KYC norms.
     */
    @PostMapping("/edit/{id}")
    @Transactional
    public String updateCustomer(@PathVariable Long id,
                                  @ModelAttribute Customer updated,
                                  @RequestParam Long branchId,
                                  RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Customer existing = customerRepository.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                    "CUSTOMER_NOT_FOUND", "Customer not found: " + id));

            Branch branch = branchRepository.findById(branchId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                    "BRANCH_NOT_FOUND", "Branch not found: " + branchId));

            String beforeState = existing.getFullName() + "|" + existing.getMobileNumber();

            existing.setFirstName(updated.getFirstName());
            existing.setLastName(updated.getLastName());
            existing.setDateOfBirth(updated.getDateOfBirth());
            existing.setMobileNumber(updated.getMobileNumber());
            existing.setEmail(updated.getEmail());
            existing.setAddress(updated.getAddress());
            existing.setCity(updated.getCity());
            existing.setState(updated.getState());
            existing.setPinCode(updated.getPinCode());
            existing.setCustomerType(updated.getCustomerType());
            existing.setCibilScore(updated.getCibilScore());
            existing.setMonthlyIncome(updated.getMonthlyIncome());
            existing.setMaxBorrowingLimit(updated.getMaxBorrowingLimit());
            existing.setEmploymentType(updated.getEmploymentType());
            existing.setEmployerName(updated.getEmployerName());
            existing.setBranch(branch);
            existing.setUpdatedBy(currentUser);
            customerRepository.save(existing);

            auditService.logEvent("Customer", existing.getId(), "UPDATE",
                beforeState, existing.getFullName() + "|" + existing.getMobileNumber(), "CIF",
                "Customer updated by " + currentUser);

            redirectAttributes.addFlashAttribute("success",
                "Customer updated: " + existing.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }

    /** CBS KYC Verification — CHECKER/ADMIN only (enforced in SecurityConfig) */
    @PostMapping("/verify-kyc/{id}")
    @Transactional
    public String verifyKyc(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Customer customer = customerRepository.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                    "CUSTOMER_NOT_FOUND", "Customer not found: " + id));

            customer.setKycVerified(true);
            customer.setKycVerifiedDate(businessDateService.getCurrentBusinessDate());
            customer.setKycVerifiedBy(currentUser);
            customer.setUpdatedBy(currentUser);
            customerRepository.save(customer);

            auditService.logEvent("Customer", customer.getId(), "KYC_VERIFY",
                "KYC_PENDING", "KYC_VERIFIED", "CIF",
                "KYC verified by " + currentUser);

            redirectAttributes.addFlashAttribute("success",
                "KYC verified for customer: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }

    /**
     * CBS CIF Deactivation — soft-delete per RBI data retention.
     * Blocks if customer has active (non-terminal) loan accounts.
     */
    @PostMapping("/deactivate/{id}")
    @Transactional
    public String deactivateCustomer(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Customer customer = customerRepository.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException(
                    "CUSTOMER_NOT_FOUND", "Customer not found: " + id));

            long activeAccounts = accountRepository.findByTenantIdAndCustomerId(tenantId, id)
                .stream().filter(a -> !a.getStatus().isTerminal()).count();
            if (activeAccounts > 0) {
                throw new BusinessException("CUSTOMER_HAS_ACTIVE_ACCOUNTS",
                    "Cannot deactivate customer with " + activeAccounts + " active loan account(s)");
            }

            customer.setActive(false);
            customer.setUpdatedBy(currentUser);
            customerRepository.save(customer);

            auditService.logEvent("Customer", customer.getId(), "DEACTIVATE",
                "ACTIVE", "INACTIVE", "CIF",
                "Customer deactivated by " + currentUser);

            redirectAttributes.addFlashAttribute("success",
                "Customer deactivated: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }
}

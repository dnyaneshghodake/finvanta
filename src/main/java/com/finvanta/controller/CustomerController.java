package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.BranchAccessValidator;
import com.finvanta.util.BusinessException;
import com.finvanta.util.PiiMaskingUtil;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

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
    private final BranchAccessValidator branchAccessValidator;

    public CustomerController(
            CustomerRepository customerRepository,
            BranchRepository branchRepository,
            LoanApplicationRepository applicationRepository,
            LoanAccountRepository accountRepository,
            AuditService auditService,
            BusinessDateService businessDateService,
            BranchAccessValidator branchAccessValidator) {
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.businessDateService = businessDateService;
        this.branchAccessValidator = branchAccessValidator;
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
                mav.addObject(
                        "customers", customerRepository.findByTenantIdAndBranchIdAndActiveTrue(tenantId, branchId));
            } else {
                // CBS: No branch assigned — show empty list per fail-safe principle.
                // Per RBI Operational Risk: no-branch users must not see all data.
                mav.addObject("customers", java.util.Collections.emptyList());
            }
        }
        return mav;
    }

    /**
     * CBS Customer Search with branch isolation per Finacle CIF_SEARCH + BRANCH_CONTEXT.
     * Searches by name, customer number, mobile, or PAN.
     * MAKER/CHECKER: search restricted to their home branch.
     * ADMIN: searches across all branches.
     * Essential for branch operations — staff must locate customers quickly.
     */
    @GetMapping("/search")
    public ModelAndView searchCustomers(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/list");
        if (q != null && !q.isBlank() && q.length() >= 2) {
            // CBS: Apply branch isolation to search results per Finacle BRANCH_CONTEXT.
            // Without this, MAKER/CHECKER could search across all branches, bypassing
            // the branch isolation enforced in listCustomers().
            if (SecurityUtil.isAdminRole()) {
                mav.addObject("customers", customerRepository.searchCustomers(tenantId, q.trim()));
            } else {
                Long branchId = SecurityUtil.getCurrentUserBranchId();
                if (branchId != null) {
                    mav.addObject(
                            "customers", customerRepository.searchCustomersByBranch(tenantId, branchId, q.trim()));
                } else {
                    mav.addObject("customers", java.util.Collections.emptyList());
                }
            }
            mav.addObject("searchQuery", q);
        } else {
            // Show all if no query (same as list)
            if (SecurityUtil.isAdminRole()) {
                mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
            } else {
                Long branchId = SecurityUtil.getCurrentUserBranchId();
                if (branchId != null) {
                    mav.addObject(
                            "customers", customerRepository.findByTenantIdAndBranchIdAndActiveTrue(tenantId, branchId));
                } else {
                    mav.addObject("customers", java.util.Collections.emptyList());
                }
            }
        }
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/add");
        mav.addObject("customer", new Customer());
        // CBS Tier-1: MAKER/CHECKER can only create customers at their home branch.
        // ADMIN sees all branches. Per Finacle CIF_MASTER: customer is always created
        // at the originating branch. Branch transfer is a separate maker-checker workflow.
        if (SecurityUtil.isAdminRole()) {
            mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        } else {
            Long branchId = SecurityUtil.getCurrentUserBranchId();
            if (branchId != null) {
                branchRepository.findById(branchId)
                        .filter(b -> b.getTenantId().equals(tenantId))
                        .ifPresent(b -> mav.addObject("branches", java.util.List.of(b)));
            }
        }
        return mav;
    }

    /**
     * CBS CIF Creation — auto-generates customer number per Finacle convention.
     * Format: CUST + branchCode + timestamp + sequence
     */
    @PostMapping("/add")
    @Transactional
    public String addCustomer(
            @ModelAttribute Customer customer, @RequestParam Long branchId, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Branch branch = branchRepository
                    .findById(branchId)
                    .filter(b -> b.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found: " + branchId));

            // P1 Gap 5.2: Duplicate CIF detection per RBI KYC norms.
            // Per RBI: one PAN = one CIF. Duplicate CIFs cause exposure miscalculation.
            if (customer.getPanNumber() != null && !customer.getPanNumber().isBlank()) {
                if (customerRepository.existsByTenantIdAndPanNumber(tenantId, customer.getPanNumber())) {
                    throw new BusinessException(
                            "DUPLICATE_PAN",
                            "Customer with PAN " + customer.getPanNumber() + " already exists. "
                                    + "Per RBI KYC norms, one PAN = one CIF.");
                }
            }
            if (customer.getAadhaarNumber() != null
                    && !customer.getAadhaarNumber().isBlank()) {
                if (customerRepository.existsByTenantIdAndAadhaarNumber(tenantId, customer.getAadhaarNumber())) {
                    throw new BusinessException(
                            "DUPLICATE_AADHAAR",
                            "Customer with Aadhaar already exists. Duplicate CIFs are prohibited per RBI KYC.");
                }
            }

            customer.setCustomerNumber(ReferenceGenerator.generateCustomerNumber(branch.getBranchCode()));
            customer.setTenantId(tenantId);
            customer.setBranch(branch);
            customer.setCreatedBy(currentUser);

            // CBS: Compute PII hashes for de-duplication per RBI KYC norms.
            // Since PAN/Aadhaar are encrypted (AES-256-GCM), ciphertext comparison
            // doesn't work for duplicate detection. SHA-256 hash enables DB-level
            // uniqueness checks without decryption.
            customer.computePanHash();
            customer.computeAadhaarHash();

            // CBS: Set default KYC risk category based on customer type.
            // PEP customers are always HIGH risk per FATF Recommendation 12.
            if (customer.isPep()) {
                customer.setKycRiskCategory("HIGH");
            }

            Customer saved = customerRepository.save(customer);

            auditService.logEvent(
                    "Customer",
                    saved.getId(),
                    "CREATE",
                    null,
                    saved.getCustomerNumber(),
                    "CIF",
                    "Customer created: " + saved.getFullName() + " at branch " + branch.getBranchCode());

            redirectAttributes.addFlashAttribute(
                    "success", "Customer created: " + saved.getCustomerNumber() + " - " + saved.getFullName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/list";
    }

    @GetMapping("/view/{id}")
    public ModelAndView viewCustomer(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/view");
        Customer customer = customerRepository
                .findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Customer not found: " + id));
        // CBS Tier-1: Branch access enforcement on customer view.
        // MAKER/CHECKER can only view customers at their home branch.
        branchAccessValidator.validateAccess(customer.getBranch());
        mav.addObject("customer", customer);
        // CBS: PII masking per RBI IT Governance Direction 2023 / UIDAI Aadhaar Act 2016.
        // Full PII is never exposed in UI — only masked values (last 4 digits visible).
        mav.addObject("maskedPan", PiiMaskingUtil.maskPan(customer.getPanNumber()));
        mav.addObject("maskedAadhaar", PiiMaskingUtil.maskAadhaar(customer.getAadhaarNumber()));
        mav.addObject("maskedMobile", PiiMaskingUtil.maskMobile(customer.getMobileNumber()));
        mav.addObject("loanApplications", applicationRepository.findByTenantIdAndCustomerId(tenantId, id));
        mav.addObject("loanAccounts", accountRepository.findByTenantIdAndCustomerId(tenantId, id));
        return mav;
    }

    /** CBS CIF Edit — pre-populated form for mutable fields */
    @GetMapping("/edit/{id}")
    public ModelAndView showEditForm(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        Customer customer = customerRepository
                .findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Customer not found: " + id));
        // CBS Tier-1: Branch access enforcement on customer edit.
        branchAccessValidator.validateAccess(customer.getBranch());
        ModelAndView mav = new ModelAndView("customer/edit");
        mav.addObject("customer", customer);
        // CBS: PII masking for immutable disabled fields in edit form
        mav.addObject("maskedPan", PiiMaskingUtil.maskPan(customer.getPanNumber()));
        mav.addObject("maskedAadhaar", PiiMaskingUtil.maskAadhaar(customer.getAadhaarNumber()));
        // CBS Tier-1: Branch dropdown restricted per role (same as add form).
        // MAKER/CHECKER can only see their home branch. ADMIN sees all.
        // Per Finacle: branch transfer requires a separate approval workflow.
        if (SecurityUtil.isAdminRole()) {
            mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        } else {
            Long userBranchId = SecurityUtil.getCurrentUserBranchId();
            if (userBranchId != null) {
                branchRepository.findById(userBranchId)
                        .filter(b -> b.getTenantId().equals(tenantId))
                        .ifPresent(b -> mav.addObject("branches", java.util.List.of(b)));
            }
        }
        return mav;
    }

    /**
     * CBS CIF Update — mutable fields only.
     * Customer number, PAN, Aadhaar are immutable after creation per RBI KYC norms.
     */
    @PostMapping("/edit/{id}")
    @Transactional
    public String updateCustomer(
            @PathVariable Long id,
            @ModelAttribute Customer updated,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();
        try {
            Customer existing = customerRepository
                    .findById(id)
                    .filter(c -> c.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Customer not found: " + id));

            Branch branch = branchRepository
                    .findById(branchId)
                    .filter(b -> b.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "Branch not found: " + branchId));

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
            // CBS Sprint 1.2: KYC risk category and PEP flag from edit form
            if (updated.getKycRiskCategory() != null) {
                existing.setKycRiskCategory(updated.getKycRiskCategory());
                // Recompute KYC expiry if risk category changed (different renewal period)
                existing.computeKycExpiry();
            }
            existing.setPep(updated.isPep());
            if (updated.isPep()) {
                existing.setKycRiskCategory("HIGH"); // PEP always HIGH per FATF
                existing.computeKycExpiry();
            }
            existing.setBranch(branch);
            existing.setUpdatedBy(currentUser);
            customerRepository.save(existing);

            auditService.logEvent(
                    "Customer",
                    existing.getId(),
                    "UPDATE",
                    beforeState,
                    existing.getFullName() + "|" + existing.getMobileNumber(),
                    "CIF",
                    "Customer updated by " + currentUser);

            redirectAttributes.addFlashAttribute("success", "Customer updated: " + existing.getCustomerNumber());
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
            Customer customer = customerRepository
                    .findById(id)
                    .filter(c -> c.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Customer not found: " + id));
            // CBS Tier-1: Branch access enforcement on KYC verification.
            branchAccessValidator.validateAccess(customer.getBranch());

            customer.setKycVerified(true);
            customer.setKycVerifiedDate(businessDateService.getCurrentBusinessDate());
            customer.setKycVerifiedBy(currentUser);

            // CBS: Compute KYC expiry based on risk category per RBI KYC Section 16.
            // LOW=10yr, MEDIUM=8yr, HIGH=2yr from verification date.
            // This enables EOD batch to detect expired KYC customers for re-verification.
            customer.computeKycExpiry();
            customer.setRekycDue(false); // Clear re-KYC flag on fresh verification

            customer.setUpdatedBy(currentUser);
            customerRepository.save(customer);

            auditService.logEvent(
                    "Customer",
                    customer.getId(),
                    "KYC_VERIFY",
                    "KYC_PENDING",
                    "KYC_VERIFIED",
                    "CIF",
                    "KYC verified by " + currentUser
                            + " | Risk: " + customer.getKycRiskCategory()
                            + " | Expiry: " + customer.getKycExpiryDate());

            redirectAttributes.addFlashAttribute(
                    "success", "KYC verified for customer: " + customer.getCustomerNumber());
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
            Customer customer = customerRepository
                    .findById(id)
                    .filter(c -> c.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Customer not found: " + id));
            // CBS Tier-1: Branch access enforcement on customer deactivation.
            branchAccessValidator.validateAccess(customer.getBranch());

            long activeAccounts = accountRepository.findByTenantIdAndCustomerId(tenantId, id).stream()
                    .filter(a -> !a.getStatus().isTerminal())
                    .count();
            if (activeAccounts > 0) {
                throw new BusinessException(
                        "CUSTOMER_HAS_ACTIVE_ACCOUNTS",
                        "Cannot deactivate customer with " + activeAccounts + " active loan account(s)");
            }

            customer.setActive(false);
            customer.setUpdatedBy(currentUser);
            customerRepository.save(customer);

            auditService.logEvent(
                    "Customer",
                    customer.getId(),
                    "DEACTIVATE",
                    "ACTIVE",
                    "INACTIVE",
                    "CIF",
                    "Customer deactivated by " + currentUser);

            redirectAttributes.addFlashAttribute("success", "Customer deactivated: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }
}

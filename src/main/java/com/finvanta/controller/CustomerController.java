package com.finvanta.controller;

import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.CustomerDocument;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.service.CustomerCifService;
import com.finvanta.service.CustomerDocumentService;
import com.finvanta.util.PiiMaskingUtil;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Customer Information File (CIF) Controller — Thin HTTP Mapping Layer.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller → Service → Repository
 *   Controller has NO @Transactional, NO direct repository writes, NO business logic.
 *   All business logic is in CustomerCifService and CustomerDocumentService.
 *
 * Repository reads (applicationRepository, accountRepository, depositAccountRepository)
 * are retained for view-layer data assembly only — no writes, no business logic.
 */
@Controller
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerCifService customerService;
    private final CustomerDocumentService documentService;
    private final BranchRepository branchRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final DepositAccountRepository depositAccountRepository;

    public CustomerController(
            CustomerCifService customerService,
            CustomerDocumentService documentService,
            BranchRepository branchRepository,
            LoanApplicationRepository applicationRepository,
            LoanAccountRepository accountRepository,
            DepositAccountRepository depositAccountRepository) {
        this.customerService = customerService;
        this.documentService = documentService;
        this.branchRepository = branchRepository;
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.depositAccountRepository = depositAccountRepository;
    }

    /**
     * CBS Customer List — delegates search/list to CustomerCifService.
     * Branch isolation enforced in service layer.
     */
    @GetMapping("/list")
    public ModelAndView listCustomers() {
        ModelAndView mav = new ModelAndView("customer/list");
        mav.addObject("customers", customerService.searchCustomers(""));
        return mav;
    }

    /**
     * CBS Customer Search — delegates to CustomerCifService with branch isolation.
     */
    @GetMapping("/search")
    public ModelAndView searchCustomers(@RequestParam(required = false) String q) {
        ModelAndView mav = new ModelAndView("customer/list");
        if (q != null && !q.isBlank() && q.length() >= 2) {
            mav.addObject("customers", customerService.searchCustomers(q));
            mav.addObject("searchQuery", q);
        } else {
            mav.addObject("customers", customerService.searchCustomers(""));
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
        // CBS: Default branch ID = user's current operating branch (home or switched)
        // Per Finacle CIF_MASTER: branch dropdown pre-selects the user's branch
        mav.addObject("defaultBranchId", SecurityUtil.getCurrentUserBranchId());
        return mav;
    }

    /**
     * CBS CIF Creation — delegates to CustomerCifService.
     * Uses createCustomerFromEntity() which accepts the full Customer entity
     * with all CKYC/demographic fields populated by Spring MVC @ModelAttribute binding.
     */
    @PostMapping("/add")
    public Object addCustomer(
            @ModelAttribute Customer customer, @RequestParam Long branchId, RedirectAttributes redirectAttributes) {
        try {
            Customer saved = customerService.createCustomerFromEntity(customer, branchId);
            redirectAttributes.addFlashAttribute(
                    "success", "Customer created: " + saved.getCustomerNumber() + " - " + saved.getFullName());
            return "redirect:/customer/list";
        } catch (Exception e) {
            // CBS: On validation failure, re-display the add form with entered data preserved.
            // Per Finacle CIF_MASTER: user should not re-enter all fields after a single validation error.
            String tenantId = TenantContext.getCurrentTenant();
            ModelAndView mav = new ModelAndView("customer/add");
            mav.addObject("customer", customer);
            mav.addObject("error", e.getMessage());
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
            mav.addObject("defaultBranchId", SecurityUtil.getCurrentUserBranchId());
            return mav;
        }
    }

    /** CBS Customer View — delegates to CustomerCifService for branch access enforcement */
    @GetMapping("/view/{id}")
    public ModelAndView viewCustomer(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/view");
        Customer customer = customerService.getCustomer(id);
        mav.addObject("customer", customer);
        mav.addObject("maskedPan", PiiMaskingUtil.maskPan(customer.getPanNumber()));
        mav.addObject("maskedAadhaar", PiiMaskingUtil.maskAadhaar(customer.getAadhaarNumber()));
        mav.addObject("maskedMobile", PiiMaskingUtil.maskMobile(customer.getMobileNumber()));
        mav.addObject("loanApplications", applicationRepository.findByTenantIdAndCustomerId(tenantId, id));
        mav.addObject("loanAccounts", accountRepository.findByTenantIdAndCustomerId(tenantId, id));
        mav.addObject("depositAccounts", depositAccountRepository.findByTenantIdAndCustomerId(tenantId, id));
        mav.addObject("documents", documentService.getDocumentsForCustomer(id));
        return mav;
    }

    /** CBS CIF Edit — pre-populated form for mutable fields */
    @GetMapping("/edit/{id}")
    public ModelAndView showEditForm(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        Customer customer = customerService.getCustomer(id);
        ModelAndView mav = new ModelAndView("customer/edit");
        mav.addObject("customer", customer);
        mav.addObject("maskedPan", PiiMaskingUtil.maskPan(customer.getPanNumber()));
        mav.addObject("maskedAadhaar", PiiMaskingUtil.maskAadhaar(customer.getAadhaarNumber()));
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
     * CBS CIF Update — delegates to CustomerCifService.
     * PAN, Aadhaar, and customer number are IMMUTABLE after creation per RBI KYC norms.
     */
    @PostMapping("/edit/{id}")
    public String updateCustomer(
            @PathVariable Long id,
            @ModelAttribute Customer updated,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        try {
            Customer saved = customerService.updateCustomer(id, updated, branchId);
            redirectAttributes.addFlashAttribute("success", "Customer updated: " + saved.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }

    /** CBS KYC Verification — delegates to CustomerCifService */
    @PostMapping("/verify-kyc/{id}")
    public String verifyKyc(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            Customer customer = customerService.verifyKyc(id);
            redirectAttributes.addFlashAttribute(
                    "success", "KYC verified for customer: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }

    /** CBS CIF Deactivation — delegates to CustomerCifService */
    @PostMapping("/deactivate/{id}")
    public String deactivateCustomer(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            Customer customer = customerService.deactivateCustomer(id);
            redirectAttributes.addFlashAttribute("success", "Customer deactivated: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }

    // ========================================================================
    // DOCUMENT MANAGEMENT — Thin delegation to CustomerDocumentService.
    // Per Finacle DOC_MASTER: all business logic in service layer.
    // Controller only handles HTTP request/response mapping.
    // ========================================================================

    /** CBS Document Upload — delegates to CustomerDocumentService */
    @PostMapping("/document/upload/{customerId}")
    public String uploadDocument(
            @PathVariable Long customerId,
            @RequestParam("file") MultipartFile file,
            @RequestParam String documentType,
            @RequestParam(required = false) String documentNumber,
            @RequestParam(required = false) String remarks,
            RedirectAttributes redirectAttributes) {
        try {
            if (file.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Please select a file to upload.");
                return "redirect:/customer/view/" + customerId;
            }
            documentService.uploadDocument(customerId, documentType,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    file.getBytes(), documentNumber, remarks);
            redirectAttributes.addFlashAttribute("success",
                    "Document uploaded: " + documentType + " (" + file.getOriginalFilename() + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + customerId;
    }

    /** CBS Document Download — delegates to CustomerDocumentService */
    @GetMapping("/document/download/{docId}")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable Long docId) {
        CustomerDocument doc = documentService.getDocument(docId);
        byte[] fileContent = documentService.retrieveFileContent(doc);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + doc.getFileName() + "\"")
                .header("Content-Type", doc.getContentType())
                .body(fileContent);
    }

    /** CBS Document Verification — delegates to CustomerDocumentService */
    @PostMapping("/document/verify/{docId}")
    public String verifyDocument(
            @PathVariable Long docId,
            @RequestParam String action,
            @RequestParam(required = false) String rejectionReason,
            RedirectAttributes redirectAttributes) {
        Long customerId = 0L;
        try {
            CustomerDocument doc = documentService.verifyDocument(docId, action, rejectionReason);
            customerId = doc.getCustomer().getId();
            redirectAttributes.addFlashAttribute("success",
                    "Document " + doc.getVerificationStatus().name().toLowerCase()
                            + ": " + doc.getDocumentType().getDisplayName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            // Attempt to resolve customerId for redirect even on error
            try {
                customerId = documentService.getDocument(docId).getCustomer().getId();
            } catch (Exception ignored) {
                // If document not found, redirect to list
            }
        }
        return customerId > 0 ? "redirect:/customer/view/" + customerId : "redirect:/customer/list";
    }
}

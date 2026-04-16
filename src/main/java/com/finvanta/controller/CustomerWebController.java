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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
public class CustomerWebController {

    private final CustomerCifService customerService;
    private final CustomerDocumentService documentService;
    private final BranchRepository branchRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final DepositAccountRepository depositAccountRepository;

    public CustomerWebController(
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

    /** CBS: Default page size per Finacle CIF_LIST / Temenos ENQUIRY */
    private static final int DEFAULT_PAGE_SIZE = 25;

    /** CBS: Max page size cap to prevent OOM on large result sets */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * CBS Customer List with pagination per Finacle CIF_LIST / Temenos ENQUIRY.
     * Branch isolation enforced in service layer.
     *
     * @param page Page number (0-based, default 0)
     * @param size Page size (default 25 per CBS standard)
     */
    @GetMapping("/list")
    public ModelAndView listCustomers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        ModelAndView mav = new ModelAndView("customer/list");
        // CBS: Sanitize pagination params — negative page/zero size causes IllegalArgumentException
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(Math.min(size, MAX_PAGE_SIZE), 1);
        Page<Customer> customerPage = customerService.searchCustomers("",
                PageRequest.of(safePage, safeSize, Sort.by("customerNumber").ascending()));
        mav.addObject("customers", customerPage.getContent());
        mav.addObject("customerPage", customerPage);
        return mav;
    }

    /**
     * CBS Customer Search with pagination per Finacle CIF_SEARCH.
     * Branch isolation enforced in service layer.
     */
    @GetMapping("/search")
    public ModelAndView searchCustomers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        ModelAndView mav = new ModelAndView("customer/list");
        // CBS: Sanitize pagination params — negative page/zero size causes IllegalArgumentException
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(Math.min(size, MAX_PAGE_SIZE), 1);
        Page<Customer> customerPage;
        if (q != null && !q.isBlank() && q.length() >= 2) {
            customerPage = customerService.searchCustomers(q,
                    PageRequest.of(safePage, safeSize, Sort.by("customerNumber").ascending()));
            mav.addObject("searchQuery", q);
        } else {
            customerPage = customerService.searchCustomers("",
                    PageRequest.of(safePage, safeSize, Sort.by("customerNumber").ascending()));
        }
        mav.addObject("customers", customerPage.getContent());
        mav.addObject("customerPage", customerPage);
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        ModelAndView mav = new ModelAndView("customer/add");
        mav.addObject("customer", new Customer());
        populateBranchDropdown(mav);
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
            ModelAndView mav = new ModelAndView("customer/add");
            mav.addObject("customer", customer);
            mav.addObject("error", e.getMessage());
            populateBranchDropdown(mav);
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
        Customer customer = customerService.getCustomer(id);
        ModelAndView mav = new ModelAndView("customer/edit");
        mav.addObject("customer", customer);
        mav.addObject("maskedPan", PiiMaskingUtil.maskPan(customer.getPanNumber()));
        mav.addObject("maskedAadhaar", PiiMaskingUtil.maskAadhaar(customer.getAadhaarNumber()));
        populateBranchDropdown(mav);
        return mav;
    }

    /**
     * CBS CIF Update — delegates to CustomerCifService.
     * PAN, Aadhaar, and customer number are IMMUTABLE after creation per RBI KYC norms.
     *
     * Per Finacle CIF_MASTER: on validation failure, re-display the edit form with
     * entered data preserved. User should not re-enter all fields after a single error.
     * Same pattern as addCustomer() — returns ModelAndView on error, redirect on success.
     */
    @PostMapping("/edit/{id}")
    public Object updateCustomer(
            @PathVariable Long id,
            @ModelAttribute Customer updated,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        try {
            Customer saved = customerService.updateCustomer(id, updated, branchId);
            redirectAttributes.addFlashAttribute("success", "Customer updated: " + saved.getCustomerNumber());
            return "redirect:/customer/view/" + id;
        } catch (Exception e) {
            // CBS: On validation failure, re-display the edit form with entered data preserved.
            // Per Finacle CIF_MASTER: user should not re-enter all fields after a single validation error.
            // Reload immutable fields (customer number, masked PAN/Aadhaar) from the persisted entity.
            ModelAndView mav = new ModelAndView("customer/edit");
            try {
                Customer existing = customerService.getCustomer(id);
                // Merge: use entered mutable data but restore immutable identifiers
                updated.setId(existing.getId());
                updated.setCustomerNumber(existing.getCustomerNumber());
                updated.setBranch(existing.getBranch());
                mav.addObject("customer", updated);
                mav.addObject("maskedPan", PiiMaskingUtil.maskPan(existing.getPanNumber()));
                mav.addObject("maskedAadhaar", PiiMaskingUtil.maskAadhaar(existing.getAadhaarNumber()));
            } catch (Exception ex) {
                // Fallback: if customer can't be loaded, redirect to view with error
                redirectAttributes.addFlashAttribute("error", e.getMessage());
                return "redirect:/customer/view/" + id;
            }
            mav.addObject("error", e.getMessage());
            populateBranchDropdown(mav);
            return mav;
        }
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
        // CBS Security: Sanitize filename in Content-Disposition header to prevent
        // HTTP response splitting (CVE-class). Strip CR/LF/quotes/backslash.
        // The service layer already sanitizes on upload, but defense-in-depth
        // requires re-sanitizing at the HTTP boundary per OWASP guidelines.
        String safeFileName = doc.getFileName().replaceAll("[\\r\\n\"\\\\]", "_");
        // CBS Security: Re-validate Content-Type at download boundary (defense-in-depth).
        // Upload validates magic bytes, but DB-stored contentType could be tampered.
        String safeContentType = doc.getContentType();
        if (!"application/pdf".equals(safeContentType)
                && !"image/jpeg".equals(safeContentType)
                && !"image/png".equals(safeContentType)) {
            safeContentType = "application/octet-stream"; // Force download for unknown types
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + safeFileName + "\"")
                .header("Content-Type", safeContentType)
                // CBS Security: Prevent MIME sniffing — browser must honor declared Content-Type.
                // Without this, a browser could re-interpret file content (e.g., SVG with JS).
                .header("X-Content-Type-Options", "nosniff")
                // CBS Security: Sandbox user-uploaded content to prevent embedded JS execution.
                // Per OWASP: uploaded PDFs can contain JavaScript that executes in browser context.
                // 'sandbox' isolates the content from the application's origin/cookies/session.
                .header("Content-Security-Policy", "sandbox")
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

    // ========================================================================
    // PRIVATE HELPERS — DRY per Finacle/Temenos coding standards.
    // ========================================================================

    /**
     * CBS: Populates branch dropdown for customer forms per Finacle BRANCH_CONTEXT.
     * MAKER/CHECKER: restricted to their home branch (single-item list).
     * ADMIN: sees all active branches across the tenant.
     * Per Finacle CIF_MASTER: customer is always created at the originating branch.
     * Branch transfer is a separate maker-checker workflow.
     *
     * Extracted to eliminate 4x duplication across showAddForm, addCustomer (error),
     * showEditForm, and updateCustomer (error) — DRY per CBS coding standards.
     */
    private void populateBranchDropdown(ModelAndView mav) {
        String tenantId = TenantContext.getCurrentTenant();
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
    }
}

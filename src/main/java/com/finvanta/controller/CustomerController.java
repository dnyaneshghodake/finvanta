package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.CustomerDocument;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerDocumentRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.LoanApplicationRepository;
import com.finvanta.service.CustomerCifService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.PiiMaskingUtil;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Customer Information File (CIF) Controller.
 * Per Finacle/Temenos, CIF is the master record for all customer interactions.
 * Supports: Create (auto-number), View, Edit, KYC Verify, Deactivate.
 *
 * CBS Code Quality: No @Transactional on controller methods.
 * All business logic and transactions are managed by CustomerCifService.
 * Controller only handles HTTP request/response mapping and view delegation.
 */
@Controller
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerCifService customerService;
    private final BranchRepository branchRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanAccountRepository accountRepository;
    private final CustomerDocumentRepository documentRepository;
    private final AuditService auditService;

    public CustomerController(
            CustomerCifService customerService,
            BranchRepository branchRepository,
            LoanApplicationRepository applicationRepository,
            LoanAccountRepository accountRepository,
            CustomerDocumentRepository documentRepository,
            AuditService auditService) {
        this.customerService = customerService;
        this.branchRepository = branchRepository;
        this.applicationRepository = applicationRepository;
        this.accountRepository = accountRepository;
        this.documentRepository = documentRepository;
        this.auditService = auditService;
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
    public String addCustomer(
            @ModelAttribute Customer customer, @RequestParam Long branchId, RedirectAttributes redirectAttributes) {
        try {
            Customer saved = customerService.createCustomerFromEntity(customer, branchId);
            redirectAttributes.addFlashAttribute(
                    "success", "Customer created: " + saved.getCustomerNumber() + " - " + saved.getFullName());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/list";
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
        mav.addObject("documents", documentRepository.findByCustomer(tenantId, id));
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
    // DOCUMENT MANAGEMENT (per Finacle DOC_MASTER / RBI KYC Direction)
    // ========================================================================

    /** CBS Document Upload — stores KYC document with metadata */
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
                throw new BusinessException("FILE_EMPTY", "Please select a file to upload.");
            }
            // CBS: Max 5MB per document per RBI IT Governance
            if (file.getSize() > 5 * 1024 * 1024) {
                throw new BusinessException("FILE_TOO_LARGE",
                        "File size exceeds 5MB limit. Please compress or resize the document.");
            }
            // CBS: Allowed formats only (PDF, JPG, PNG)
            String contentType = file.getContentType();
            if (contentType == null || (!contentType.equals("application/pdf")
                    && !contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
                throw new BusinessException("INVALID_FORMAT",
                        "Only PDF, JPG, and PNG files are allowed.");
            }

            Customer customer = customerService.getCustomer(customerId);
            String tenantId = TenantContext.getCurrentTenant();

            CustomerDocument doc = new CustomerDocument();
            doc.setTenantId(tenantId);
            doc.setCustomer(customer);
            doc.setDocumentType(documentType);
            doc.setFileName(file.getOriginalFilename());
            doc.setContentType(contentType);
            doc.setFileSize(file.getSize());
            doc.setFileData(file.getBytes());
            doc.setDocumentNumber(documentNumber);
            doc.setRemarks(remarks);
            doc.setVerificationStatus("UPLOADED");
            doc.setCreatedBy(SecurityUtil.getCurrentUsername());

            documentRepository.save(doc);

            auditService.logEvent("CustomerDocument", doc.getId(), "UPLOAD", null,
                    documentType, "CIF",
                    "Document uploaded: " + documentType + " for customer " + customer.getCustomerNumber()
                            + " | File: " + file.getOriginalFilename()
                            + " | Size: " + file.getSize() + " bytes");

            redirectAttributes.addFlashAttribute("success",
                    "Document uploaded: " + documentType + " (" + file.getOriginalFilename() + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + customerId;
    }

    /** CBS Document Download — serves document file for viewing/download */
    @GetMapping("/document/download/{docId}")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable Long docId) {
        String tenantId = TenantContext.getCurrentTenant();
        CustomerDocument doc = documentRepository.findById(docId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "Document not found"));

        // CBS: Branch access enforcement — user must have access to the customer's branch
        customerService.getCustomer(doc.getCustomer().getId());

        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + doc.getFileName() + "\"")
                .header("Content-Type", doc.getContentType())
                .body(doc.getFileData());
    }

    /** CBS Document Verification — CHECKER/ADMIN marks document as verified or rejected */
    @PostMapping("/document/verify/{docId}")
    public String verifyDocument(
            @PathVariable Long docId,
            @RequestParam String action,
            @RequestParam(required = false) String rejectionReason,
            RedirectAttributes redirectAttributes) {
        try {
            String tenantId = TenantContext.getCurrentTenant();
            CustomerDocument doc = documentRepository.findById(docId)
                    .filter(d -> d.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "Document not found"));

            // CBS: Branch access enforcement — user must have access to the customer's branch
            customerService.getCustomer(doc.getCustomer().getId());

            // CBS: Prevent re-verification of already-verified/rejected documents
            // Per Finacle DOC_MASTER: documents are immutable once verified.
            if (!"UPLOADED".equals(doc.getVerificationStatus())) {
                throw new BusinessException("DOC_ALREADY_PROCESSED",
                        "Document already " + doc.getVerificationStatus().toLowerCase()
                                + ". Upload a new version if correction is needed.");
            }

            if ("VERIFY".equals(action)) {
                doc.setVerificationStatus("VERIFIED");
            } else if ("REJECT".equals(action)) {
                if (rejectionReason == null || rejectionReason.isBlank()) {
                    throw new BusinessException("REASON_REQUIRED",
                            "Rejection reason is mandatory per RBI audit norms.");
                }
                doc.setVerificationStatus("REJECTED");
                doc.setRejectionReason(rejectionReason);
            } else {
                throw new BusinessException("INVALID_ACTION", "Action must be VERIFY or REJECT.");
            }
            doc.setVerifiedBy(SecurityUtil.getCurrentUsername());
            doc.setVerifiedDate(LocalDate.now());
            doc.setUpdatedBy(SecurityUtil.getCurrentUsername());
            documentRepository.save(doc);

            auditService.logEvent("CustomerDocument", doc.getId(),
                    "VERIFY".equals(action) ? "DOC_VERIFIED" : "DOC_REJECTED",
                    "UPLOADED", doc.getVerificationStatus(), "CIF",
                    "Document " + doc.getVerificationStatus().toLowerCase() + ": " + doc.getDocumentType()
                            + " | By: " + SecurityUtil.getCurrentUsername());

            redirectAttributes.addFlashAttribute("success",
                    "Document " + doc.getVerificationStatus().toLowerCase() + ": " + doc.getDocumentType());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        Long customerId = documentRepository.findById(docId)
                .map(d -> d.getCustomer().getId()).orElse(0L);
        return "redirect:/customer/view/" + customerId;
    }
}

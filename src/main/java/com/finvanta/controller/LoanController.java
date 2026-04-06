package com.finvanta.controller;

import com.finvanta.domain.entity.Collateral;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.entity.LoanDocument;
import com.finvanta.domain.enums.ApplicationStatus;
import com.finvanta.domain.enums.CollateralType;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CollateralRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.LoanDocumentRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CollateralService;
import com.finvanta.service.LoanAccountService;
import com.finvanta.service.LoanApplicationService;
import com.finvanta.service.LoanScheduleService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/loan")
public class LoanController {

    private final LoanApplicationService applicationService;
    private final LoanAccountService accountService;
    private final LoanScheduleService scheduleService;
    private final CollateralService collateralService;
    private final BusinessDateService businessDateService;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final CollateralRepository collateralRepository;
    private final LoanDocumentRepository documentRepository;
    private final LoanTransactionRepository transactionRepository;
    private final ProductMasterRepository productRepository;

    public LoanController(LoanApplicationService applicationService,
                           LoanAccountService accountService,
                           LoanScheduleService scheduleService,
                           CollateralService collateralService,
                           BusinessDateService businessDateService,
                           CustomerRepository customerRepository,
                           BranchRepository branchRepository,
                           CollateralRepository collateralRepository,
                           LoanDocumentRepository documentRepository,
                           LoanTransactionRepository transactionRepository,
                           ProductMasterRepository productRepository) {
        this.applicationService = applicationService;
        this.accountService = accountService;
        this.scheduleService = scheduleService;
        this.collateralService = collateralService;
        this.businessDateService = businessDateService;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.collateralRepository = collateralRepository;
        this.documentRepository = documentRepository;
        this.transactionRepository = transactionRepository;
        this.productRepository = productRepository;
    }

    @GetMapping("/apply")
    public ModelAndView showApplicationForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("loan/apply");
        mav.addObject("application", new LoanApplication());
        mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        mav.addObject("products", productRepository.findActiveProducts(tenantId));
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
            mav.addObject("products", productRepository.findActiveProducts(tenantId));
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
        String tenantId = TenantContext.getCurrentTenant();
        LoanApplication app = applicationService.getApplication(id);
        ModelAndView mav = new ModelAndView("loan/verify");
        mav.addObject("application", app);
        mav.addObject("collaterals", collateralRepository.findByTenantIdAndLoanApplicationId(tenantId, id));
        mav.addObject("documents", documentRepository.findByTenantIdAndLoanApplicationId(tenantId, id));
        mav.addObject("collateralTypes", CollateralType.values());
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
        String tenantId = TenantContext.getCurrentTenant();
        LoanApplication app = applicationService.getApplication(id);
        ModelAndView mav = new ModelAndView("loan/approve");
        mav.addObject("application", app);
        mav.addObject("collaterals", collateralRepository.findByTenantIdAndLoanApplicationId(tenantId, id));
        mav.addObject("documents", documentRepository.findByTenantIdAndLoanApplicationId(tenantId, id));
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
        mav.addObject("schedule", scheduleService.getSchedule(account.getId()));

        // CBS: Collateral and document data for account view
        mav.addObject("collaterals",
            collateralRepository.findByTenantIdAndLoanApplicationId(tenantId, account.getApplication().getId()));
        mav.addObject("documents",
            documentRepository.findByTenantIdAndLoanApplicationId(tenantId, account.getApplication().getId()));

        // Cross-module linkage: resolve product ID for "View GL Config" link
        productRepository.findByTenantIdAndProductCode(tenantId, account.getProductType())
            .ifPresent(p -> mav.addObject("productId", p.getId()));

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
            accountService.processRepayment(accountNumber, amount,
                businessDateService.getCurrentBusinessDate());
            redirectAttributes.addFlashAttribute("success", "Repayment processed successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }

    /** CBS Prepayment/Foreclosure — MAKER/ADMIN. Pays off total outstanding and closes loan. */
    @PostMapping("/prepayment/{accountNumber}")
    public String processPrepayment(@PathVariable String accountNumber,
                                     @RequestParam BigDecimal amount,
                                     RedirectAttributes redirectAttributes) {
        try {
            accountService.processPrepayment(accountNumber, amount,
                businessDateService.getCurrentBusinessDate());
            redirectAttributes.addFlashAttribute("success",
                "Loan prepaid/foreclosed successfully: " + accountNumber);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }

    /** CBS Write-Off — ADMIN only (enforced in SecurityConfig). NPA accounts only. */
    @PostMapping("/write-off/{accountNumber}")
    public String writeOffAccount(@PathVariable String accountNumber,
                                   RedirectAttributes redirectAttributes) {
        try {
            accountService.writeOffAccount(accountNumber,
                businessDateService.getCurrentBusinessDate());
            redirectAttributes.addFlashAttribute("success",
                "Loan written off successfully: " + accountNumber);
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

    /**
     * CBS Transaction Reversal — CHECKER/ADMIN only.
     * Per Finacle TRAN_REVERSAL: creates contra GL entries and restores account balances.
     * Original transaction is marked reversed (never deleted per CBS audit rules).
     */
    @PostMapping("/reversal/{transactionRef}")
    public String reverseTransaction(@PathVariable String transactionRef,
                                      @RequestParam String reason,
                                      @RequestParam String accountNumber,
                                      RedirectAttributes redirectAttributes) {
        try {
            accountService.reverseTransaction(transactionRef, reason,
                businessDateService.getCurrentBusinessDate());
            redirectAttributes.addFlashAttribute("success",
                "Transaction reversed: " + transactionRef);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }

    /**
     * CBS Fee Charging — MAKER/ADMIN.
     * Processing fees, documentation charges, etc.
     * GL Entry: DR Bank Operations / CR Fee Income
     */
    @PostMapping("/fee/{accountNumber}")
    public String chargeFee(@PathVariable String accountNumber,
                             @RequestParam BigDecimal feeAmount,
                             @RequestParam String feeType,
                             RedirectAttributes redirectAttributes) {
        try {
            accountService.chargeFee(accountNumber, feeAmount, feeType,
                businessDateService.getCurrentBusinessDate());
            redirectAttributes.addFlashAttribute("success",
                feeType + " charged: INR " + feeAmount);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }

    // ================================================================
    // CBS Collateral Management Endpoints (Finacle COLMAS)
    // ================================================================

    /** Register collateral against a loan application */
    @PostMapping("/collateral/{applicationId}")
    public String registerCollateral(@PathVariable Long applicationId,
                                      @RequestParam String collateralType,
                                      @RequestParam String ownerName,
                                      @RequestParam(required = false) String ownerRelationship,
                                      @RequestParam(required = false) String goldPurity,
                                      @RequestParam(required = false) BigDecimal goldWeightGrams,
                                      @RequestParam(required = false) BigDecimal goldNetWeightGrams,
                                      @RequestParam(required = false) BigDecimal goldRatePerGram,
                                      @RequestParam(required = false) String propertyAddress,
                                      @RequestParam(required = false) String propertyType,
                                      @RequestParam(required = false) BigDecimal propertyAreaSqft,
                                      @RequestParam(required = false) String registrationNumber,
                                      @RequestParam(required = false) String vehicleRegistration,
                                      @RequestParam(required = false) String vehicleMake,
                                      @RequestParam(required = false) String vehicleModel,
                                      @RequestParam(required = false) String fdNumber,
                                      @RequestParam(required = false) String fdBankName,
                                      @RequestParam(required = false) BigDecimal fdAmount,
                                      @RequestParam(required = false) BigDecimal marketValue,
                                      @RequestParam(required = false) String description,
                                      RedirectAttributes redirectAttributes) {
        try {
            Collateral collateral = new Collateral();
            collateral.setCollateralType(CollateralType.valueOf(collateralType));
            collateral.setOwnerName(ownerName);
            collateral.setOwnerRelationship(ownerRelationship != null ? ownerRelationship : "SELF");
            collateral.setDescription(description);
            // Gold fields
            collateral.setGoldPurity(goldPurity);
            collateral.setGoldWeightGrams(goldWeightGrams);
            collateral.setGoldNetWeightGrams(goldNetWeightGrams != null ? goldNetWeightGrams : goldWeightGrams);
            collateral.setGoldRatePerGram(goldRatePerGram);
            // Property fields
            collateral.setPropertyAddress(propertyAddress);
            collateral.setPropertyType(propertyType);
            collateral.setPropertyAreaSqft(propertyAreaSqft);
            collateral.setRegistrationNumber(registrationNumber);
            // Vehicle fields
            collateral.setVehicleRegistration(vehicleRegistration);
            collateral.setVehicleMake(vehicleMake);
            collateral.setVehicleModel(vehicleModel);
            // FD fields
            collateral.setFdNumber(fdNumber);
            collateral.setFdBankName(fdBankName);
            collateral.setFdAmount(fdAmount);
            // General
            collateral.setMarketValue(marketValue);

            Collateral saved = collateralService.registerCollateral(collateral, applicationId);
            redirectAttributes.addFlashAttribute("success",
                "Collateral registered: " + saved.getCollateralRef()
                    + " (" + saved.getCollateralType() + ")");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/verify/" + applicationId;
    }

    // ================================================================
    // CBS Document Management Endpoints (Finacle DOCMAS)
    // ================================================================

    /** Upload document for a loan application */
    @PostMapping("/document/{applicationId}")
    public String uploadDocument(@PathVariable Long applicationId,
                                  @RequestParam String documentType,
                                  @RequestParam String documentName,
                                  @RequestParam(required = false) String remarks,
                                  @RequestParam(defaultValue = "false") boolean mandatory,
                                  RedirectAttributes redirectAttributes) {
        try {
            String tenantId = TenantContext.getCurrentTenant();
            LoanApplication app = applicationService.getApplication(applicationId);

            LoanDocument doc = new LoanDocument();
            doc.setTenantId(tenantId);
            doc.setLoanApplication(app);
            doc.setDocumentType(documentType);
            doc.setDocumentName(documentName);
            // In production, file upload would set these from MultipartFile
            doc.setFileName(documentName.replaceAll("\\s+", "_") + ".pdf");
            doc.setFilePath("/documents/" + tenantId + "/" + applicationId + "/" + doc.getFileName());
            doc.setContentType("application/pdf");
            doc.setMandatory(mandatory);
            doc.setRemarks(remarks);
            doc.setCreatedBy(SecurityUtil.getCurrentUsername());

            documentRepository.save(doc);
            redirectAttributes.addFlashAttribute("success",
                "Document uploaded: " + documentName);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/verify/" + applicationId;
    }

    /** Verify a document — CHECKER/ADMIN */
    @PostMapping("/document/verify/{documentId}")
    public String verifyDocument(@PathVariable Long documentId,
                                  @RequestParam Long applicationId,
                                  RedirectAttributes redirectAttributes) {
        try {
            LoanDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
            doc.setVerificationStatus("VERIFIED");
            doc.setVerifiedBy(SecurityUtil.getCurrentUsername());
            doc.setVerifiedDate(businessDateService.getCurrentBusinessDate());
            doc.setUpdatedBy(SecurityUtil.getCurrentUsername());
            documentRepository.save(doc);
            redirectAttributes.addFlashAttribute("success", "Document verified");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/verify/" + applicationId;
    }

    /** Reject a document — CHECKER/ADMIN */
    @PostMapping("/document/reject/{documentId}")
    public String rejectDocument(@PathVariable Long documentId,
                                  @RequestParam Long applicationId,
                                  @RequestParam String rejectionReason,
                                  RedirectAttributes redirectAttributes) {
        try {
            LoanDocument doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found"));
            doc.setVerificationStatus("REJECTED");
            doc.setVerifiedBy(SecurityUtil.getCurrentUsername());
            doc.setVerifiedDate(businessDateService.getCurrentBusinessDate());
            doc.setRejectionReason(rejectionReason);
            doc.setUpdatedBy(SecurityUtil.getCurrentUsername());
            documentRepository.save(doc);
            redirectAttributes.addFlashAttribute("success", "Document rejected");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/verify/" + applicationId;
    }

    // ================================================================
    // CBS Multi-Disbursement Tranche Endpoint
    // ================================================================

    /** Disburse a specific tranche amount for multi-disbursement products */
    @PostMapping("/disburse-tranche/{accountNumber}")
    public String disburseTranche(@PathVariable String accountNumber,
                                   @RequestParam BigDecimal trancheAmount,
                                   @RequestParam(required = false) String narration,
                                   RedirectAttributes redirectAttributes) {
        try {
            accountService.disburseTranche(accountNumber, trancheAmount, narration);
            redirectAttributes.addFlashAttribute("success",
                "Tranche disbursed: INR " + trancheAmount);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/loan/account/" + accountNumber;
    }
}

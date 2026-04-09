package com.finvanta.controller;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.batch.InterBranchSettlementService;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.ChargeConfigRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.service.MfaService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Admin Controller — Product Master, Transaction Limits, and Charge Config management.
 *
 * ADMIN-only access (enforced in SecurityConfig).
 *
 * Per Finacle/Temenos:
 * - Product Master (PDDEF): configures GL codes, interest methods, limits per product
 * - Transaction Limits: per-role, per-type amount controls for operational risk
 * - Charge Config (CHRG_MASTER): fee schedules with FLAT/PERCENTAGE/SLAB + GST
 *
 * IMPORTANT: When product_master GL codes are modified, the ProductGLResolver cache
 * must be evicted to prevent stale GL codes from being used in financial postings.
 * The evictCache endpoint is provided for this purpose.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ProductMasterRepository productRepository;
    private final TransactionLimitRepository limitRepository;
    private final ChargeConfigRepository chargeConfigRepository;
    private final GLMasterRepository glMasterRepository;
    private final ProductGLResolver glResolver;
    private final AuditService auditService;
    private final MfaService mfaService;
    private final AppUserRepository appUserRepository;
    private final InterBranchSettlementService settlementService;

    public AdminController(
            ProductMasterRepository productRepository,
            TransactionLimitRepository limitRepository,
            ChargeConfigRepository chargeConfigRepository,
            GLMasterRepository glMasterRepository,
            ProductGLResolver glResolver,
            AuditService auditService,
            MfaService mfaService,
            AppUserRepository appUserRepository,
            InterBranchSettlementService settlementService) {
        this.productRepository = productRepository;
        this.limitRepository = limitRepository;
        this.chargeConfigRepository = chargeConfigRepository;
        this.glMasterRepository = glMasterRepository;
        this.glResolver = glResolver;
        this.auditService = auditService;
        this.mfaService = mfaService;
        this.appUserRepository = appUserRepository;
        this.settlementService = settlementService;
    }

    // ========================================================================
    // Product Master Management
    // ========================================================================

    /** List all products */
    @GetMapping("/products")
    public ModelAndView listProducts() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/products");
        mav.addObject("products", productRepository.findByTenantIdOrderByProductCode(tenantId));
        return mav;
    }

    /** View product details — validates tenant ownership to prevent cross-tenant data leak */
    @GetMapping("/products/{id}")
    public ModelAndView viewProduct(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        var product = productRepository
                .findById(id)
                .filter(p -> p.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + id));
        ModelAndView mav = new ModelAndView("admin/product-detail");
        mav.addObject("product", product);
        return mav;
    }

    /** Product creation form — shows GL codes for mapping */
    @GetMapping("/products/create")
    public ModelAndView showCreateProductForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/product-create");
        mav.addObject("glAccounts", glMasterRepository.findAllPostableAccounts(tenantId));
        mav.addObject("pageTitle", "Create Product");
        return mav;
    }

    /**
     * Create a new product per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
     * Per CBS standards: every product must have complete GL mapping before activation.
     */
    @PostMapping("/products/create")
    public String createProduct(
            @RequestParam String productCode,
            @RequestParam String productName,
            @RequestParam String productCategory,
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "ACTUAL_365") String interestMethod,
            @RequestParam(defaultValue = "FIXED") String interestType,
            @RequestParam BigDecimal minInterestRate,
            @RequestParam BigDecimal maxInterestRate,
            @RequestParam(defaultValue = "2.0000") BigDecimal defaultPenalRate,
            @RequestParam BigDecimal minLoanAmount,
            @RequestParam BigDecimal maxLoanAmount,
            @RequestParam int minTenureMonths,
            @RequestParam int maxTenureMonths,
            @RequestParam(defaultValue = "MONTHLY") String repaymentFrequency,
            @RequestParam String glLoanAsset,
            @RequestParam String glInterestReceivable,
            @RequestParam String glBankOperations,
            @RequestParam String glInterestIncome,
            @RequestParam String glFeeIncome,
            @RequestParam String glPenalIncome,
            @RequestParam String glProvisionExpense,
            @RequestParam String glProvisionNpa,
            @RequestParam String glWriteOffExpense,
            @RequestParam String glInterestSuspense,
            RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            if (productRepository
                    .findByTenantIdAndProductCode(tenantId, productCode)
                    .isPresent()) {
                throw new BusinessException("DUPLICATE_PRODUCT", "Product code already exists: " + productCode);
            }

            ProductMaster p = new ProductMaster();
            p.setTenantId(tenantId);
            p.setProductCode(productCode);
            p.setProductName(productName);
            p.setProductCategory(productCategory);
            p.setDescription(description);
            p.setCurrencyCode("INR");
            p.setInterestMethod(interestMethod);
            p.setInterestType(interestType);
            p.setMinInterestRate(minInterestRate);
            p.setMaxInterestRate(maxInterestRate);
            p.setDefaultPenalRate(defaultPenalRate);
            p.setMinLoanAmount(minLoanAmount);
            p.setMaxLoanAmount(maxLoanAmount);
            p.setMinTenureMonths(minTenureMonths);
            p.setMaxTenureMonths(maxTenureMonths);
            p.setRepaymentFrequency(repaymentFrequency);
            p.setGlLoanAsset(glLoanAsset);
            p.setGlInterestReceivable(glInterestReceivable);
            p.setGlBankOperations(glBankOperations);
            p.setGlInterestIncome(glInterestIncome);
            p.setGlFeeIncome(glFeeIncome);
            p.setGlPenalIncome(glPenalIncome);
            p.setGlProvisionExpense(glProvisionExpense);
            p.setGlProvisionNpa(glProvisionNpa);
            p.setGlWriteOffExpense(glWriteOffExpense);
            p.setGlInterestSuspense(glInterestSuspense);
            p.setActive(true);
            p.setRepaymentAllocation("INTEREST_FIRST");
            p.setCreatedBy(SecurityUtil.getCurrentUsername());

            ProductMaster saved = productRepository.save(p);
            // Evict GL cache so new product's GL codes are loaded
            glResolver.evictCache();

            auditService.logEvent(
                    "ProductMaster",
                    saved.getId(),
                    "PRODUCT_CREATED",
                    null,
                    productCode,
                    "PRODUCT_MASTER",
                    "Product created: " + productCode + " — " + productName + " | Category: " + productCategory
                            + " | By: " + SecurityUtil.getCurrentUsername());

            redirectAttributes.addFlashAttribute("success", "Product created: " + productCode);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/products";
    }

    /**
     * Evicts the ProductGLResolver cache.
     * Must be called after any product_master GL code modification to prevent
     * stale GL codes from being used in financial postings. Per CBS audit standards,
     * posting to a wrong GL code is an audit-critical finding.
     */
    @PostMapping("/products/evict-cache")
    public String evictProductCache(RedirectAttributes redirectAttributes) {
        glResolver.evictCache();
        redirectAttributes.addFlashAttribute(
                "success", "Product GL cache evicted. New GL codes will be loaded on next transaction.");
        return "redirect:/admin/products";
    }

    // ========================================================================
    // Transaction Limit Management
    // ========================================================================

    /** List all transaction limits */
    @GetMapping("/limits")
    public ModelAndView listLimits() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/limits");
        mav.addObject("limits", limitRepository.findByTenantIdOrderByRoleAscTransactionTypeAsc(tenantId));
        return mav;
    }

    // ========================================================================
    // Charge Configuration Management (Finacle CHRG_MASTER)
    // ========================================================================

    /** List all charge configurations */
    @GetMapping("/charges")
    public ModelAndView listCharges() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/charges");
        mav.addObject("charges", chargeConfigRepository.findByTenantIdAndIsActiveTrueOrderByEventTriggerAsc(tenantId));
        return mav;
    }

    // ========================================================================
    // MFA Management (per RBI IT Governance Direction 2023 Section 8.4)
    // ========================================================================

    /** MFA management dashboard — lists all users with MFA status */
    @GetMapping("/mfa")
    public ModelAndView mfaDashboard() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/mfa");
        mav.addObject("users", appUserRepository.findByTenantIdOrderByRoleAscUsernameAsc(tenantId));
        return mav;
    }

    /**
     * Enable MFA for a user. Per RBI IT Governance Direction 2023 Section 8.4:
     * mandatory for ADMIN, optional for MAKER/CHECKER.
     * After enabling, user must complete enrollment before login is allowed.
     */
    @PostMapping("/mfa/enable")
    public String enableMfa(@RequestParam String username, RedirectAttributes redirectAttributes) {
        try {
            boolean enabled = mfaService.enableMfa(username);
            if (enabled) {
                redirectAttributes.addFlashAttribute("success",
                        "MFA enabled for " + username + ". User must complete enrollment to log in.");
            } else {
                redirectAttributes.addFlashAttribute("info", "MFA already enabled for " + username);
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/mfa";
    }

    /**
     * Enroll MFA — generates TOTP secret and returns QR code URI.
     * Per Finacle MFA_ENROLL: secret generated server-side, displayed as QR code.
     */
    @PostMapping("/mfa/enroll")
    public ModelAndView enrollMfa(@RequestParam String username, RedirectAttributes redirectAttributes) {
        try {
            String base32Secret = mfaService.enrollMfa(username);
            String otpAuthUri = mfaService.buildOtpAuthUri(username, base32Secret);

            ModelAndView mav = new ModelAndView("admin/mfa-enroll");
            mav.addObject("username", username);
            mav.addObject("secret", base32Secret);
            mav.addObject("otpAuthUri", otpAuthUri);
            return mav;
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return new ModelAndView("redirect:/admin/mfa");
        }
    }

    /**
     * Verify TOTP code to complete MFA enrollment.
     * Per Finacle MFA_ENROLL: enrollment is only complete after successful TOTP verification.
     */
    @PostMapping("/mfa/verify")
    public String verifyMfa(
            @RequestParam String username,
            @RequestParam String totpCode,
            RedirectAttributes redirectAttributes) {
        try {
            boolean verified = mfaService.verifyAndActivateMfa(username, totpCode);
            if (verified) {
                redirectAttributes.addFlashAttribute("success",
                        "MFA enrollment verified for " + username + ". User can now log in with TOTP.");
            } else {
                redirectAttributes.addFlashAttribute("error",
                        "Invalid TOTP code for " + username + ". Please try again with a fresh code.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/mfa";
    }

    /**
     * Disable MFA for a user. Per RBI: ADMIN users cannot have MFA disabled.
     * Requires mandatory reason for audit trail.
     */
    @PostMapping("/mfa/disable")
    public String disableMfa(
            @RequestParam String username,
            @RequestParam String reason,
            RedirectAttributes redirectAttributes) {
        try {
            mfaService.disableMfa(username, reason);
            redirectAttributes.addFlashAttribute("success", "MFA disabled for " + username);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/mfa";
    }

    // ========================================================================
    // Inter-Branch Settlement — HO Manual Settle (per Finacle IB_SETTLEMENT)
    // ========================================================================

    /**
     * IB Settlement dashboard — shows stale PENDING transaction count.
     * Per Finacle IB_SETTLEMENT: HO must review and authorize cross-date settlement.
     */
    @GetMapping("/ib-settlement")
    public ModelAndView ibSettlementDashboard() {
        ModelAndView mav = new ModelAndView("admin/ib-settlement");
        mav.addObject("stalePendingCount", settlementService.countStalePending());
        return mav;
    }

    /**
     * HO Manual Settlement — settle stale PENDING IB transactions from prior dates.
     *
     * Per Finacle IB_SETTLEMENT / Temenos IB.NETTING:
     * Cross-date PENDING transactions require explicit HO authorization because:
     * 1. The original EOD failed — root cause must be investigated
     * 2. Cross-date settlement affects prior-day GL balances (audit implications)
     * 3. Regulatory reporting for the prior date may already have been submitted
     *
     * Mandatory: reason + HO authorization reference number for audit trail.
     */
    @PostMapping("/ib-settlement/manual-settle")
    public String manualSettleStalePending(
            @RequestParam String reason,
            @RequestParam String hoAuthorizationRef,
            RedirectAttributes redirectAttributes) {
        try {
            int[] result = settlementService.manualSettleStalePending(reason, hoAuthorizationRef);
            redirectAttributes.addFlashAttribute("success",
                    "HO manual settlement completed: settled=" + result[0] + ", failed=" + result[1]
                            + ", hoAuth=" + hoAuthorizationRef);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/ib-settlement";
    }
}

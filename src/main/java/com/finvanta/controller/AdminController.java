package com.finvanta.controller;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.batch.InterBranchSettlementService;
import com.finvanta.domain.entity.ChargeConfig;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.AdminService;
import com.finvanta.service.MfaService;
import com.finvanta.service.ProductMasterService;
import com.finvanta.service.QrCodeGenerator;
import com.finvanta.util.TenantContext;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS Admin Controller — delegates all business logic to AdminService.
 *
 * Per architecture rules: controllers MUST NOT directly call repositories or mutate entities.
 * All TransactionLimit and ChargeConfig mutations are delegated to AdminService,
 * which enforces tenant isolation, audit logging, and @Transactional atomicity.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ProductMasterService productService;
    private final ProductMasterRepository productRepository;
    private final AdminService adminService;
    private final GLMasterRepository glMasterRepository;
    private final ProductGLResolver glResolver;
    private final AuditService auditService;
    private final MfaService mfaService;
    private final QrCodeGenerator qrCodeGenerator;
    private final AppUserRepository appUserRepository;
    private final InterBranchSettlementService settlementService;

    public AdminController(
            ProductMasterService productService,
            ProductMasterRepository productRepository,
            AdminService adminService,
            GLMasterRepository glMasterRepository,
            ProductGLResolver glResolver,
            AuditService auditService,
            MfaService mfaService,
            QrCodeGenerator qrCodeGenerator,
            AppUserRepository appUserRepository,
            InterBranchSettlementService settlementService) {
        this.productService = productService;
        this.productRepository = productRepository;
        this.adminService = adminService;
        this.glMasterRepository = glMasterRepository;
        this.glResolver = glResolver;
        this.auditService = auditService;
        this.mfaService = mfaService;
        this.qrCodeGenerator = qrCodeGenerator;
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

    /**
     * CBS Product Search per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
     * Searches by product code, name, category, or lifecycle status.
     * ADMIN-only (enforced in SecurityConfig). Tenant-scoped.
     */
    @GetMapping("/products/search")
    public ModelAndView searchProducts(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/products");
        if (q != null && !q.isBlank() && q.trim().length() >= 2) {
            mav.addObject("products", productRepository.searchProducts(tenantId, q.trim()));
            mav.addObject("searchQuery", q);
        } else {
            mav.addObject("products", productRepository.findByTenantIdOrderByProductCode(tenantId));
        }
        return mav;
    }

    /** View product details with active account count (GAP 5) */
    @GetMapping("/products/{id}")
    public ModelAndView viewProduct(@PathVariable Long id) {
        ProductMaster product = productService.getProduct(id);
        long activeAccounts = productService.countActiveAccounts(id);
        ModelAndView mav = new ModelAndView("admin/product-detail");
        mav.addObject("product", product);
        mav.addObject("activeAccountCount", activeAccounts);
        return mav;
    }

    /** Product edit form — pre-populated with current values, immutable fields disabled */
    @GetMapping("/products/{id}/edit")
    public ModelAndView showEditProductForm(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        ProductMaster product = productService.getProduct(id);
        long activeAccounts = productService.countActiveAccounts(id);
        ModelAndView mav = new ModelAndView("admin/product-edit");
        mav.addObject("product", product);
        mav.addObject("activeAccountCount", activeAccounts);
        mav.addObject("glAccounts", glMasterRepository.findAllPostableAccounts(tenantId));
        return mav;
    }

    /** Update product — delegates to ProductMasterService with full validation */
    @PostMapping("/products/{id}/edit")
    public Object updateProduct(@PathVariable Long id, @ModelAttribute ProductMaster updated,
            RedirectAttributes redirectAttributes) {
        try {
            ProductMaster saved = productService.updateProduct(id, updated);
            redirectAttributes.addFlashAttribute("success", "Product updated: " + saved.getProductCode());
            return "redirect:/admin/products/" + id;
        } catch (Exception e) {
            String tenantId = TenantContext.getCurrentTenant();
            ModelAndView mav = new ModelAndView("admin/product-edit");
            try {
                ProductMaster existing = productService.getProduct(id);
                updated.setId(existing.getId());
                updated.setProductCode(existing.getProductCode());
                updated.setProductCategory(existing.getProductCategory());
                mav.addObject("product", updated);
                mav.addObject("activeAccountCount", productService.countActiveAccounts(id));
            } catch (Exception ex) {
                redirectAttributes.addFlashAttribute("error", e.getMessage());
                return "redirect:/admin/products";
            }
            mav.addObject("error", e.getMessage());
            mav.addObject("glAccounts", glMasterRepository.findAllPostableAccounts(tenantId));
            return mav;
        }
    }

    /** Product lifecycle status change — ACTIVE/SUSPENDED/RETIRED */
    @PostMapping("/products/{id}/status")
    public String changeProductStatus(@PathVariable Long id, @RequestParam String status,
            RedirectAttributes redirectAttributes) {
        try {
            ProductMaster product = productService.changeStatus(id, status);
            redirectAttributes.addFlashAttribute("success",
                    "Product " + product.getProductCode() + " status changed to " + status);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/products/" + id;
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
     * Create product — delegates to ProductMasterService with full validation.
     * Per CBS Tier-1: on error, re-displays form with entered data preserved (GAP 4).
     */
    @PostMapping("/products/create")
    public Object createProduct(@ModelAttribute ProductMaster product, RedirectAttributes redirectAttributes) {
        try {
            ProductMaster saved = productService.createProduct(product);
            redirectAttributes.addFlashAttribute("success", "Product created: " + saved.getProductCode());
            return "redirect:/admin/products";
        } catch (Exception e) {
            // GAP 4: Preserve entered data on validation failure
            String tenantId = TenantContext.getCurrentTenant();
            ModelAndView mav = new ModelAndView("admin/product-create");
            mav.addObject("product", product);
            mav.addObject("error", e.getMessage());
            mav.addObject("glAccounts", glMasterRepository.findAllPostableAccounts(tenantId));
            return mav;
        }
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
        ModelAndView mav = new ModelAndView("admin/limits");
        mav.addObject("limits", adminService.listTransactionLimits());
        return mav;
    }

    /**
     * CBS Transaction Limit Create per Finacle LIMDEF / Temenos LIMIT.CHECK.
     * Delegates to AdminService which enforces tenant isolation, audit, and @Transactional.
     */
    @PostMapping("/limits/create")
    public String createLimit(
            @RequestParam String role,
            @RequestParam String transactionType,
            @RequestParam(required = false) BigDecimal perTransactionLimit,
            @RequestParam(required = false) BigDecimal dailyAggregateLimit,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {
        try {
            TransactionLimit created = adminService.createTransactionLimit(
                    role, transactionType, perTransactionLimit, dailyAggregateLimit, description);
            redirectAttributes.addFlashAttribute("success",
                    "Limit created: " + created.getRole() + " / " + created.getTransactionType());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/limits";
    }

    /** CBS Transaction Limit Edit per Finacle LIMDEF. Delegates to AdminService. */
    @PostMapping("/limits/{id}/edit")
    public String updateLimit(
            @PathVariable Long id,
            @RequestParam(required = false) BigDecimal perTransactionLimit,
            @RequestParam(required = false) BigDecimal dailyAggregateLimit,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {
        try {
            TransactionLimit updated = adminService.updateTransactionLimit(
                    id, perTransactionLimit, dailyAggregateLimit, description);
            redirectAttributes.addFlashAttribute("success",
                    "Limit updated: " + updated.getRole() + " / " + updated.getTransactionType());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/limits";
    }

    /** CBS Transaction Limit Activate/Deactivate per Finacle LIMDEF. Delegates to AdminService. */
    @PostMapping("/limits/{id}/toggle-active")
    public String toggleLimitActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            TransactionLimit toggled = adminService.toggleTransactionLimitActive(id);
            redirectAttributes.addFlashAttribute("success",
                    "Limit " + (toggled.isActive() ? "activated" : "deactivated")
                            + ": " + toggled.getRole() + " / " + toggled.getTransactionType());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/limits";
    }

    // ========================================================================
    // Charge Configuration Management (Finacle CHRG_MASTER)
    // ========================================================================

    /** List all charge configurations (active only) */
    @GetMapping("/charges")
    public ModelAndView listCharges() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("admin/charges");
        mav.addObject("charges", adminService.listChargeConfigs());
        mav.addObject("glAccounts", glMasterRepository.findAllPostableAccounts(tenantId));
        mav.addObject("products", productRepository.findByTenantIdOrderByProductCode(tenantId));
        return mav;
    }

    /**
     * CBS Charge Config Create per Finacle CHRG_MASTER. Delegates to AdminService.
     */
    @PostMapping("/charges/create")
    public String createCharge(
            @RequestParam String chargeCode,
            @RequestParam String chargeName,
            @RequestParam(required = false) String chargeCategory,
            @RequestParam String eventTrigger,
            @RequestParam String calculationType,
            @RequestParam(required = false) String frequency,
            @RequestParam(required = false) BigDecimal baseAmount,
            @RequestParam(required = false) BigDecimal percentage,
            @RequestParam(required = false) String slabJson,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(defaultValue = "false") boolean gstApplicable,
            @RequestParam(required = false) BigDecimal gstRate,
            @RequestParam String glChargeIncome,
            @RequestParam(required = false) String glGstPayable,
            @RequestParam(defaultValue = "false") boolean waiverAllowed,
            @RequestParam(required = false) BigDecimal maxWaiverPercent,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String validFrom,
            @RequestParam(required = false) String validTo,
            @RequestParam(required = false) String customerDescription,
            RedirectAttributes redirectAttributes) {
        try {
            ChargeConfig created = adminService.createChargeConfig(
                    chargeCode, chargeName, chargeCategory, eventTrigger, calculationType,
                    frequency, baseAmount, percentage, slabJson, minAmount, maxAmount, currencyCode,
                    gstApplicable, gstRate, glChargeIncome, glGstPayable, waiverAllowed,
                    maxWaiverPercent, productCode, channel,
                    validFrom != null && !validFrom.isBlank() ? LocalDate.parse(validFrom) : null,
                    validTo != null && !validTo.isBlank() ? LocalDate.parse(validTo) : null,
                    customerDescription);
            redirectAttributes.addFlashAttribute("success", "Charge created: " + created.getChargeCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/charges";
    }

    /** CBS Charge Config Edit per Finacle CHRG_MASTER. Delegates to AdminService. */
    @PostMapping("/charges/{id}/edit")
    public String updateCharge(
            @PathVariable Long id,
            @RequestParam String chargeName,
            @RequestParam(required = false) String chargeCategory,
            @RequestParam String eventTrigger,
            @RequestParam String calculationType,
            @RequestParam(required = false) String frequency,
            @RequestParam(required = false) BigDecimal baseAmount,
            @RequestParam(required = false) BigDecimal percentage,
            @RequestParam(required = false) String slabJson,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(defaultValue = "false") boolean gstApplicable,
            @RequestParam(required = false) BigDecimal gstRate,
            @RequestParam String glChargeIncome,
            @RequestParam(required = false) String glGstPayable,
            @RequestParam(defaultValue = "false") boolean waiverAllowed,
            @RequestParam(required = false) BigDecimal maxWaiverPercent,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String validFrom,
            @RequestParam(required = false) String validTo,
            @RequestParam(required = false) String customerDescription,
            RedirectAttributes redirectAttributes) {
        try {
            ChargeConfig updated = adminService.updateChargeConfig(
                    id, chargeName, chargeCategory, eventTrigger, calculationType,
                    frequency, baseAmount, percentage, slabJson, minAmount, maxAmount, currencyCode,
                    gstApplicable, gstRate, glChargeIncome, glGstPayable, waiverAllowed,
                    maxWaiverPercent, productCode, channel,
                    validFrom != null && !validFrom.isBlank() ? LocalDate.parse(validFrom) : null,
                    validTo != null && !validTo.isBlank() ? LocalDate.parse(validTo) : null,
                    customerDescription);
            redirectAttributes.addFlashAttribute("success", "Charge updated: " + updated.getChargeCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/charges";
    }

    /** CBS Charge Config Activate/Deactivate per Finacle CHRG_MASTER. Delegates to AdminService. */
    @PostMapping("/charges/{id}/toggle-active")
    public String toggleChargeActive(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            ChargeConfig toggled = adminService.toggleChargeConfigActive(id);
            redirectAttributes.addFlashAttribute("success",
                    "Charge " + (toggled.getIsActive() ? "activated" : "deactivated")
                            + ": " + toggled.getChargeCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/charges";
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

            // CBS: Generate QR code server-side as Base64 PNG data URI.
            // Per Finacle/Temenos Tier-1 standards: bank networks are air-gapped,
            // so QR rendering must be server-side (no CDN/JS library dependency).
            // The QR image is embedded inline via data:image/png;base64,... — no
            // external image hosting, no client-side processing of the TOTP secret.
            String qrCodeDataUri = qrCodeGenerator.generateDataUri(otpAuthUri);

            ModelAndView mav = new ModelAndView("admin/mfa-enroll");
            mav.addObject("username", username);
            mav.addObject("secret", base32Secret);
            mav.addObject("otpAuthUri", otpAuthUri);
            mav.addObject("qrCodeDataUri", qrCodeDataUri);
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

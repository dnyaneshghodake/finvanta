package com.finvanta.controller;

import com.finvanta.accounting.ProductGLResolver;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.repository.ChargeConfigRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.TransactionLimitRepository;
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

    public AdminController(ProductMasterRepository productRepository,
                            TransactionLimitRepository limitRepository,
                            ChargeConfigRepository chargeConfigRepository,
                            GLMasterRepository glMasterRepository,
                            ProductGLResolver glResolver,
                            AuditService auditService) {
        this.productRepository = productRepository;
        this.limitRepository = limitRepository;
        this.chargeConfigRepository = chargeConfigRepository;
        this.glMasterRepository = glMasterRepository;
        this.glResolver = glResolver;
        this.auditService = auditService;
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
        var product = productRepository.findById(id)
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND",
                "Product not found: " + id));
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
    public String createProduct(@RequestParam String productCode,
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
            if (productRepository.findByTenantIdAndProductCode(tenantId, productCode).isPresent()) {
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

            auditService.logEvent("ProductMaster", saved.getId(), "PRODUCT_CREATED",
                null, productCode, "PRODUCT_MASTER",
                "Product created: " + productCode + " — " + productName
                    + " | Category: " + productCategory + " | By: " + SecurityUtil.getCurrentUsername());

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
        redirectAttributes.addFlashAttribute("success",
            "Product GL cache evicted. New GL codes will be loaded on next transaction.");
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
        mav.addObject("limits",
            limitRepository.findByTenantIdOrderByRoleAscTransactionTypeAsc(tenantId));
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
        mav.addObject("charges",
            chargeConfigRepository.findByTenantIdAndIsActiveTrueOrderByEventTriggerAsc(tenantId));
        return mav;
    }
}

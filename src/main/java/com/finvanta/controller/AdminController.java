package com.finvanta.controller;

import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.domain.entity.TransactionLimit;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.repository.TransactionLimitRepository;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Admin Controller — Product Master and Transaction Limit management.
 *
 * ADMIN-only access (enforced in SecurityConfig).
 *
 * Per Finacle/Temenos:
 * - Product Master (PDDEF): configures GL codes, interest methods, limits per product
 * - Transaction Limits: per-role, per-type amount controls for operational risk
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final ProductMasterRepository productRepository;
    private final TransactionLimitRepository limitRepository;

    public AdminController(ProductMasterRepository productRepository,
                            TransactionLimitRepository limitRepository) {
        this.productRepository = productRepository;
        this.limitRepository = limitRepository;
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

    /** View product details */
    @GetMapping("/products/{id}")
    public ModelAndView viewProduct(@PathVariable Long id) {
        ProductMaster product = productRepository.findById(id).orElse(null);
        ModelAndView mav = new ModelAndView("admin/product-detail");
        mav.addObject("product", product);
        return mav;
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
}

package com.finvanta.api;

import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.service.ProductMasterService;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Product Configuration REST API per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * <p>Thin orchestration layer over {@link ProductMasterService}.
 * All validation (code format, duplicate check, rate/amount/tenure ranges,
 * GL code existence, lifecycle transitions, maker-checker for GL changes)
 * resides in the service layer.
 *
 * <p>Per Finacle PDDEF / RBI Fair Practices Code 2023:
 * <ul>
 *   <li>Product code and category are IMMUTABLE after creation</li>
 *   <li>GL code changes on products with active accounts require maker-checker</li>
 *   <li>Product lifecycle: DRAFT → ACTIVE → SUSPENDED → RETIRED</li>
 *   <li>Only ACTIVE products can be used for new account origination</li>
 * </ul>
 *
 * <p>CBS Role Matrix: ALL endpoints are ADMIN-only.
 * Product configuration is a system-level operation.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductApiController {

    private final ProductMasterService productService;
    private final ProductMasterRepository productRepository;

    public ProductApiController(
            ProductMasterService productService,
            ProductMasterRepository productRepository) {
        this.productService = productService;
        this.productRepository = productRepository;
    }

    // === Inquiry ===

    /** List all products for the tenant. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ProductResponse>>>
            listProducts() {
        var products = productService.listProducts();
        var items = products.stream()
                .map(ProductResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /** Get product detail with active account count. */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductDetailResponse>>
            getProduct(@PathVariable Long id) {
        ProductMaster product = productService.getProduct(id);
        long activeAccounts = productService
                .countActiveAccounts(id);
        return ResponseEntity.ok(ApiResponse.success(
                ProductDetailResponse.from(
                        product, activeAccounts)));
    }

    /** Search products by code, name, category, or status. */
    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<ProductResponse>>>
            searchProducts(@RequestParam String q) {
        String tenantId = TenantContext.getCurrentTenant();
        List<ProductMaster> products;
        if (q != null && q.trim().length() >= 2) {
            products = productRepository.searchProducts(
                    tenantId, q.trim());
        } else {
            products = productService.listProducts();
        }
        var items = products.stream()
                .map(ProductResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Mutations ===

    /**
     * Update product mutable fields. ADMIN only.
     * Per Finacle PDDEF: productCode and productCategory are IMMUTABLE.
     * GL code changes on products with active accounts trigger
     * maker-checker workflow automatically in the service layer.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>>
            updateProduct(@PathVariable Long id,
                    @Valid @RequestBody UpdateProductRequest req) {
        ProductMaster updated = new ProductMaster();
        updated.setProductName(req.productName());
        updated.setDescription(req.description());
        updated.setInterestMethod(req.interestMethod());
        updated.setInterestType(req.interestType());
        updated.setMinInterestRate(req.minInterestRate());
        updated.setMaxInterestRate(req.maxInterestRate());
        updated.setDefaultPenalRate(req.defaultPenalRate());
        updated.setMinLoanAmount(req.minLoanAmount());
        updated.setMaxLoanAmount(req.maxLoanAmount());
        updated.setMinTenureMonths(req.minTenureMonths());
        updated.setMaxTenureMonths(req.maxTenureMonths());
        updated.setRepaymentAllocation(
                req.repaymentAllocation());
        updated.setPrepaymentPenaltyApplicable(
                req.prepaymentPenaltyApplicable());
        updated.setProcessingFeePct(req.processingFeePct());
        // GL codes
        updated.setGlLoanAsset(req.glLoanAsset());
        updated.setGlInterestReceivable(
                req.glInterestReceivable());
        updated.setGlBankOperations(req.glBankOperations());
        updated.setGlInterestIncome(req.glInterestIncome());
        updated.setGlFeeIncome(req.glFeeIncome());
        updated.setGlPenalIncome(req.glPenalIncome());
        updated.setGlProvisionExpense(
                req.glProvisionExpense());
        updated.setGlProvisionNpa(req.glProvisionNpa());
        updated.setGlWriteOffExpense(
                req.glWriteOffExpense());
        updated.setGlInterestSuspense(
                req.glInterestSuspense());

        ProductMaster saved = productService
                .updateProduct(id, updated);
        return ResponseEntity.ok(ApiResponse.success(
                ProductResponse.from(saved),
                "Product updated: "
                        + saved.getProductCode()));
    }

    /**
     * Change product lifecycle status. ADMIN only.
     * Per Finacle PDDEF: DRAFT→ACTIVE, ACTIVE→SUSPENDED,
     * SUSPENDED→ACTIVE, ACTIVE→RETIRED, SUSPENDED→RETIRED.
     */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>>
            changeStatus(@PathVariable Long id,
                    @Valid @RequestBody StatusChangeRequest req) {
        ProductMaster saved = productService
                .changeStatus(id, req.newStatus());
        return ResponseEntity.ok(ApiResponse.success(
                ProductResponse.from(saved),
                "Status changed to "
                        + saved.getProductStatus()));
    }

    /**
     * Clone a product to create a variant. ADMIN only.
     * Per Finacle PDDEF: copies all parameters from source,
     * requires new unique code, starts in ACTIVE status.
     */
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ProductResponse>>
            cloneProduct(@PathVariable Long id,
                    @Valid @RequestBody CloneRequest req) {
        ProductMaster cloned = productService.cloneProduct(
                id, req.newProductCode(),
                req.newProductName());
        return ResponseEntity.ok(ApiResponse.success(
                ProductResponse.from(cloned),
                "Product cloned: "
                        + cloned.getProductCode()));
    }

    // === Request DTOs ===

    public record UpdateProductRequest(
            @NotBlank String productName,
            String description,
            String interestMethod,
            String interestType,
            BigDecimal minInterestRate,
            BigDecimal maxInterestRate,
            BigDecimal defaultPenalRate,
            BigDecimal minLoanAmount,
            BigDecimal maxLoanAmount,
            Integer minTenureMonths,
            Integer maxTenureMonths,
            String repaymentAllocation,
            boolean prepaymentPenaltyApplicable,
            BigDecimal processingFeePct,
            String glLoanAsset,
            String glInterestReceivable,
            String glBankOperations,
            String glInterestIncome,
            String glFeeIncome,
            String glPenalIncome,
            String glProvisionExpense,
            String glProvisionNpa,
            String glWriteOffExpense,
            String glInterestSuspense) {}

    public record StatusChangeRequest(
            @NotBlank String newStatus) {}

    public record CloneRequest(
            @NotBlank String newProductCode,
            @NotBlank String newProductName) {}

    // === Response DTOs ===

    public record ProductResponse(
            Long id,
            String productCode,
            String productName,
            String productCategory,
            String productStatus,
            String currencyCode,
            String interestType,
            BigDecimal minInterestRate,
            BigDecimal maxInterestRate,
            BigDecimal minLoanAmount,
            BigDecimal maxLoanAmount,
            Integer minTenureMonths,
            Integer maxTenureMonths,
            int configVersion,
            String createdAt) {
        static ProductResponse from(ProductMaster p) {
            return new ProductResponse(
                    p.getId(),
                    p.getProductCode(),
                    p.getProductName(),
                    p.getProductCategory() != null
                            ? p.getProductCategory().name()
                            : null,
                    p.getProductStatus() != null
                            ? p.getProductStatus().name()
                            : null,
                    p.getCurrencyCode(),
                    p.getInterestType(),
                    p.getMinInterestRate(),
                    p.getMaxInterestRate(),
                    p.getMinLoanAmount(),
                    p.getMaxLoanAmount(),
                    p.getMinTenureMonths(),
                    p.getMaxTenureMonths(),
                    p.getConfigVersion(),
                    p.getCreatedAt() != null
                            ? p.getCreatedAt().toString()
                            : null);
        }
    }

    /** Extended product detail with active account count and GL codes. */
    public record ProductDetailResponse(
            Long id,
            String productCode,
            String productName,
            String productCategory,
            String productStatus,
            String description,
            String currencyCode,
            String interestMethod,
            String interestType,
            BigDecimal minInterestRate,
            BigDecimal maxInterestRate,
            BigDecimal defaultPenalRate,
            BigDecimal minLoanAmount,
            BigDecimal maxLoanAmount,
            Integer minTenureMonths,
            Integer maxTenureMonths,
            String repaymentAllocation,
            boolean prepaymentPenaltyApplicable,
            BigDecimal processingFeePct,
            int configVersion,
            long activeAccountCount,
            // GL codes
            String glLoanAsset,
            String glInterestReceivable,
            String glBankOperations,
            String glInterestIncome,
            String glFeeIncome,
            String glPenalIncome,
            String glProvisionExpense,
            String glProvisionNpa,
            String glWriteOffExpense,
            String glInterestSuspense) {
        static ProductDetailResponse from(
                ProductMaster p, long activeAccounts) {
            return new ProductDetailResponse(
                    p.getId(),
                    p.getProductCode(),
                    p.getProductName(),
                    p.getProductCategory() != null
                            ? p.getProductCategory().name()
                            : null,
                    p.getProductStatus() != null
                            ? p.getProductStatus().name()
                            : null,
                    p.getDescription(),
                    p.getCurrencyCode(),
                    p.getInterestMethod(),
                    p.getInterestType(),
                    p.getMinInterestRate(),
                    p.getMaxInterestRate(),
                    p.getDefaultPenalRate(),
                    p.getMinLoanAmount(),
                    p.getMaxLoanAmount(),
                    p.getMinTenureMonths(),
                    p.getMaxTenureMonths(),
                    p.getRepaymentAllocation(),
                    p.isPrepaymentPenaltyApplicable(),
                    p.getProcessingFeePct(),
                    p.getConfigVersion(),
                    activeAccounts,
                    p.getGlLoanAsset(),
                    p.getGlInterestReceivable(),
                    p.getGlBankOperations(),
                    p.getGlInterestIncome(),
                    p.getGlFeeIncome(),
                    p.getGlPenalIncome(),
                    p.getGlProvisionExpense(),
                    p.getGlProvisionNpa(),
                    p.getGlWriteOffExpense(),
                    p.getGlInterestSuspense());
        }
    }
}

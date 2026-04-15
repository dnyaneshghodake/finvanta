package com.finvanta.service;

import com.finvanta.domain.entity.ProductMaster;

import java.util.List;

/**
 * CBS Product Master Service per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * Central service layer for all product lifecycle operations.
 * Per Finacle/Temenos/BNP Tier-1 layering:
 *   Controller → Service → Repository
 *   All product business logic, validation, lifecycle, and audit reside HERE.
 *   Controller only handles HTTP request/response mapping.
 *
 * Per RBI Fair Practices Code 2023:
 * - Product configuration must be transparent and auditable
 * - Product changes must be logged with before/after state
 * - GL code changes on products with active accounts require explicit override
 *
 * Product Lifecycle: DRAFT → ACTIVE → SUSPENDED → RETIRED
 * Only ACTIVE products can be used for new loan/account origination.
 */
public interface ProductMasterService {

    /**
     * Create a new product with full validation per Finacle PDDEF.
     * Validates: product code format, duplicate check, rate/amount/tenure ranges,
     * GL code existence and account type correctness.
     *
     * @param product Product entity with all fields populated
     * @return Created product with auto-set system fields
     */
    ProductMaster createProduct(ProductMaster product);

    /**
     * Update mutable product fields per Finacle PDDEF.
     * Immutable after creation: productCode, productCategory.
     * Validates: rate/amount/tenure ranges, GL code correctness.
     * Warns if GL codes changed and active accounts exist.
     * Auto-evicts ProductGLResolver cache on save.
     *
     * @param productId Product ID
     * @param updated   Product entity with updated fields
     * @return Updated product
     */
    ProductMaster updateProduct(Long productId, ProductMaster updated);

    /**
     * Transition product lifecycle status per Finacle PDDEF.
     * Allowed transitions: DRAFT→ACTIVE, ACTIVE→SUSPENDED, SUSPENDED→ACTIVE,
     * ACTIVE→RETIRED, SUSPENDED→RETIRED.
     *
     * @param productId Product ID
     * @param newStatus Target status
     * @return Updated product
     */
    ProductMaster changeStatus(Long productId, String newStatus);

    /** Get product by ID with tenant isolation */
    ProductMaster getProduct(Long productId);

    /** List all products for the current tenant */
    List<ProductMaster> listProducts();

    /** Count non-terminal loan accounts using this product */
    long countActiveAccounts(Long productId);

    /**
     * CBS Tier-1 Gap #1: Apply CHECKER-approved GL code changes.
     *
     * Per Finacle PDDEF / RBI Internal Controls: when GL codes are modified on a
     * product with active accounts, the change requires maker-checker approval.
     * The MAKER submits the change (which creates a PENDING_APPROVAL workflow),
     * and the CHECKER calls this method to apply the approved GL codes.
     *
     * This method is called by the approval workflow callback after the CHECKER
     * approves the PRODUCT_GL_CHANGE workflow. It re-reads the proposed GL codes
     * from the workflow's payloadSnapshot and applies them to the product.
     *
     * @param workflowId The approved workflow ID containing the GL diff
     * @return Updated product with new GL codes applied
     */
    ProductMaster applyApprovedGlChange(Long workflowId);
}

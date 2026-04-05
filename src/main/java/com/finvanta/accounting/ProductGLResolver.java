package com.finvanta.accounting;

import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS Product-Aware GL Code Resolver per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG.
 *
 * In Tier-1 CBS platforms, GL codes are resolved through the product definition,
 * not hardcoded constants. This service provides the bridge:
 *
 * 1. If a product is configured in product_master → use product-specific GL codes
 * 2. If no product is found → fall back to GLConstants (backward compatibility)
 *
 * This enables gradual migration: existing loans with productType="TERM_LOAN"
 * continue to work via fallback, while new products get their own GL mapping.
 *
 * Usage:
 *   String glCode = glResolver.getLoanAssetGL("TERM_LOAN");
 *   // Returns product_master.gl_loan_asset if configured, else GLConstants.LOAN_ASSET
 */
@Service
public class ProductGLResolver {

    private static final Logger log = LoggerFactory.getLogger(ProductGLResolver.class);

    private final ProductMasterRepository productRepository;

    public ProductGLResolver(ProductMasterRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Resolves GL code for loan asset (principal outstanding) */
    public String getLoanAssetGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlLoanAsset, GLConstants.LOAN_ASSET);
    }

    /** Resolves GL code for interest receivable */
    public String getInterestReceivableGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlInterestReceivable, GLConstants.INTEREST_RECEIVABLE);
    }

    /** Resolves GL code for bank operations (disbursement/collection) */
    public String getBankOperationsGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlBankOperations, GLConstants.BANK_OPERATIONS);
    }

    /** Resolves GL code for interest income */
    public String getInterestIncomeGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlInterestIncome, GLConstants.INTEREST_INCOME);
    }

    /** Resolves GL code for fee income */
    public String getFeeIncomeGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlFeeIncome, GLConstants.FEE_INCOME);
    }

    /** Resolves GL code for penal interest income */
    public String getPenalIncomeGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlPenalIncome, GLConstants.PENAL_INTEREST_INCOME);
    }

    /** Resolves GL code for provision expense */
    public String getProvisionExpenseGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlProvisionExpense, GLConstants.PROVISION_EXPENSE);
    }

    /** Resolves GL code for provision for NPA */
    public String getProvisionNpaGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlProvisionNpa, GLConstants.PROVISION_NPA);
    }

    /** Resolves GL code for write-off expense */
    public String getWriteOffExpenseGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlWriteOffExpense, GLConstants.WRITE_OFF_EXPENSE);
    }

    /** Resolves GL code for interest suspense (NPA) */
    public String getInterestSuspenseGL(String productType) {
        return resolveGL(productType, ProductMaster::getGlInterestSuspense, GLConstants.INTEREST_SUSPENSE);
    }

    /**
     * Returns the full ProductMaster for a product type, or null if not configured.
     * Used for product-level validation (amount limits, tenure limits, etc.)
     */
    public ProductMaster getProduct(String productType) {
        if (productType == null) return null;
        String tenantId = TenantContext.getCurrentTenant();
        return productRepository.findByTenantIdAndProductCode(tenantId, productType).orElse(null);
    }

    /**
     * Core GL resolution: product-specific → fallback to constant.
     */
    private String resolveGL(String productType,
                              java.util.function.Function<ProductMaster, String> glExtractor,
                              String fallback) {
        if (productType == null) {
            return fallback;
        }
        String tenantId = TenantContext.getCurrentTenant();
        return productRepository.findByTenantIdAndProductCode(tenantId, productType)
            .map(glExtractor)
            .orElseGet(() -> {
                log.debug("Product '{}' not in product_master — using default GL: {}", productType, fallback);
                return fallback;
            });
    }
}

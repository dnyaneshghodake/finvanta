package com.finvanta.accounting;

import com.finvanta.domain.entity.ProductMaster;
import com.finvanta.repository.ProductMasterRepository;
import com.finvanta.util.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

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
 * Performance: Uses Caffeine TTL-based cache to avoid repeated DB lookups.
 * A single repayment calls get*GL() 3 times; EOD batch calls it 100s of times.
 * Without cache: 3 DB queries per repayment, 1000s during EOD.
 * With cache: 1 DB query per product per TTL window (15 minutes default).
 *
 * Cache strategy:
 * - TTL: 15 minutes — stale GL codes auto-refresh without JVM restart
 * - Max size: 100 entries — bounded memory for multi-tenant deployments
 * - Manual eviction: {@link #evictCache()} for immediate refresh after admin changes
 * - Negative caching: unconfigured products cached as Optional.empty() to avoid DB misses
 *
 * Usage:
 *   String glCode = glResolver.getLoanAssetGL("TERM_LOAN");
 *   // Returns product_master.gl_loan_asset if configured, else GLConstants.LOAN_ASSET
 */
@Service
public class ProductGLResolver {

    private static final Logger log = LoggerFactory.getLogger(ProductGLResolver.class);

    private final ProductMasterRepository productRepository;

    /**
     * Caffeine TTL-based product cache: key = "tenantId:productCode", value = Optional<ProductMaster>.
     * - expireAfterWrite(15 min): stale GL codes auto-refresh without manual intervention
     * - maximumSize(100): bounded memory — CBS typically has <20 products per tenant
     * - Optional.empty() cached for unconfigured products to avoid repeated DB misses
     */
    private final Cache<String, Optional<ProductMaster>> productCache = Caffeine.newBuilder()
        .expireAfterWrite(15, TimeUnit.MINUTES)
        .maximumSize(100)
        .recordStats()
        .build();

    public ProductGLResolver(ProductMasterRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Evicts all cached products. Call when product_master is modified via admin UI. */
    public void evictCache() {
        productCache.invalidateAll();
        log.info("ProductGLResolver cache evicted (all entries invalidated)");
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
     * Result is cached to avoid repeated DB lookups.
     */
    public ProductMaster getProduct(String productType) {
        if (productType == null) return null;
        return findCached(productType).orElse(null);
    }

    /**
     * Core GL resolution: product-specific → fallback to constant.
     * Uses cached product lookup to avoid DB round-trip on every GL resolution.
     */
    private String resolveGL(String productType,
                              java.util.function.Function<ProductMaster, String> glExtractor,
                              String fallback) {
        if (productType == null) {
            return fallback;
        }
        return findCached(productType)
            .map(glExtractor)
            .orElseGet(() -> {
                log.debug("Product '{}' not in product_master — using default GL: {}", productType, fallback);
                return fallback;
            });
    }

    /**
     * Cached product lookup using Caffeine's get() with loader.
     * Caches both hits and misses (Optional.empty()) to avoid repeated DB queries.
     * Entries auto-expire after 15 minutes per TTL configuration.
     */
    private Optional<ProductMaster> findCached(String productType) {
        String tenantId = TenantContext.getCurrentTenant();
        String cacheKey = tenantId + ":" + productType;
        return productCache.get(cacheKey, k ->
            productRepository.findByTenantIdAndProductCode(tenantId, productType));
    }
}

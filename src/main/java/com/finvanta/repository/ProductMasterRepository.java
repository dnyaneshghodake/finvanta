package com.finvanta.repository;

import com.finvanta.domain.entity.ProductMaster;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Product Master Repository per Finacle PDDEF standards.
 */
@Repository
public interface ProductMasterRepository extends JpaRepository<ProductMaster, Long> {

    /** Find product by code (primary lookup for GL resolution) */
    Optional<ProductMaster> findByTenantIdAndProductCode(String tenantId, String productCode);

    /** All active products for a tenant (for product selection UI) */
    @Query("SELECT p FROM ProductMaster p WHERE p.tenantId = :tenantId AND p.active = true " + "ORDER BY p.productName")
    List<ProductMaster> findActiveProducts(@Param("tenantId") String tenantId);

    /** All products for a tenant (for admin management UI) */
    List<ProductMaster> findByTenantIdOrderByProductCode(String tenantId);

    /** Check if product code exists (for duplicate prevention) */
    boolean existsByTenantIdAndProductCode(String tenantId, String productCode);

    // === CBS PDDEF Search per Finacle PDDEF / Temenos AA.PRODUCT.CATALOG ===

    /**
     * Search products by code, name, category, or status.
     * Per Finacle PDDEF: ADMIN must locate products for GL config, rate changes,
     * and lifecycle management. Tenant-scoped (ADMIN-only module).
     */
    @Query("SELECT p FROM ProductMaster p WHERE p.tenantId = :tenantId AND ("
            + "LOWER(p.productCode) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(p.productName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(CAST(p.productCategory AS string)) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(CAST(p.productStatus AS string)) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY p.productCode")
    List<ProductMaster> searchProducts(
            @Param("tenantId") String tenantId, @Param("query") String query);
}

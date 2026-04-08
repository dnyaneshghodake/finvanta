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
}

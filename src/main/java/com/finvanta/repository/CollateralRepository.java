package com.finvanta.repository;

import com.finvanta.domain.entity.Collateral;
import com.finvanta.domain.enums.CollateralType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CBS Collateral Repository per Finacle COLMAS standards.
 */
@Repository
public interface CollateralRepository extends JpaRepository<Collateral, Long> {

    Optional<Collateral> findByTenantIdAndCollateralRef(String tenantId, String collateralRef);

    List<Collateral> findByTenantIdAndLoanApplicationId(String tenantId, Long loanApplicationId);

    List<Collateral> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    List<Collateral> findByTenantIdAndCollateralType(String tenantId, CollateralType collateralType);

    /** Total market value of all active collaterals for a loan application */
    @Query("SELECT COALESCE(SUM(c.marketValue), 0) FROM Collateral c " +
           "WHERE c.tenantId = :tenantId AND c.loanApplication.id = :appId " +
           "AND c.status = 'ACTIVE'")
    java.math.BigDecimal sumMarketValueByApplication(
        @Param("tenantId") String tenantId,
        @Param("appId") Long applicationId);

    /** Collaterals with expired insurance (for EOD monitoring) */
    @Query("SELECT c FROM Collateral c WHERE c.tenantId = :tenantId " +
           "AND c.insuranceExpiryDate IS NOT NULL AND c.insuranceExpiryDate < :today " +
           "AND c.status = 'ACTIVE'")
    List<Collateral> findExpiredInsurance(
        @Param("tenantId") String tenantId,
        @Param("today") java.time.LocalDate today);

    /** Collaterals due for revaluation */
    @Query("SELECT c FROM Collateral c WHERE c.tenantId = :tenantId " +
           "AND c.status = 'ACTIVE' AND c.valuationDate IS NOT NULL")
    List<Collateral> findAllActiveWithValuation(@Param("tenantId") String tenantId);
}

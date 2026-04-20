package com.finvanta.repository;

import com.finvanta.domain.entity.AmlStrReport;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CBS AML STR Report Repository per Finacle AML_STR / Temenos FC.STR.REPORT.
 *
 * Per PMLA 2002 / RBI KYC Master Direction Section 29:
 * STR records must be queryable by status, customer, detection date,
 * and filing deadline for compliance monitoring and FIU-IND batch filing.
 */
public interface AmlStrReportRepository extends JpaRepository<AmlStrReport, Long> {

    Optional<AmlStrReport> findByTenantIdAndStrReference(String tenantId, String strReference);

    List<AmlStrReport> findByTenantIdAndStatus(String tenantId, String status);

    List<AmlStrReport> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    /** STRs pending filing that have breached the 7-day deadline */
    @Query("SELECT s FROM AmlStrReport s WHERE s.tenantId = :tenantId "
            + "AND s.status NOT IN ('FILED', 'ACKNOWLEDGED', 'CLOSED') "
            + "AND s.detectionDate < :deadlineDate "
            + "ORDER BY s.detectionDate ASC")
    List<AmlStrReport> findBreachedDeadline(
            @Param("tenantId") String tenantId,
            @Param("deadlineDate") LocalDate deadlineDate);

    /** Count of open STRs for dashboard metrics */
    long countByTenantIdAndStatusIn(String tenantId, List<String> statuses);
}

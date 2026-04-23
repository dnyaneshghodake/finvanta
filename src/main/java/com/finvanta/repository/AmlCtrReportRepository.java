package com.finvanta.repository;

import com.finvanta.domain.entity.AmlCtrReport;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CBS AML CTR Report Repository per Finacle AML_CTR / Temenos FC.CTR.REPORT.
 *
 * Per PMLA 2002 / RBI KYC Master Direction Section 28(2):
 * CTR records must be queryable by reporting month, status, and customer
 * for monthly batch filing to FIU-IND by the 15th of the following month.
 */
public interface AmlCtrReportRepository extends JpaRepository<AmlCtrReport, Long> {

    /** All PENDING CTRs for a reporting month — used by monthly batch filing job */
    List<AmlCtrReport> findByTenantIdAndReportingMonthAndStatus(
            String tenantId, LocalDate reportingMonth, String status);

    /** CTRs for a specific customer — for AML risk review */
    List<AmlCtrReport> findByTenantIdAndCustomerIdOrderByTransactionDateDesc(
            String tenantId, Long customerId);

    /** Count PENDING CTRs that are past filing deadline (15th of following month) */
    @Query("SELECT COUNT(c) FROM AmlCtrReport c WHERE c.tenantId = :tenantId "
            + "AND c.status = 'PENDING' "
            + "AND c.reportingMonth < :deadlineMonth")
    long countOverduePendingCtrs(
            @Param("tenantId") String tenantId,
            @Param("deadlineMonth") LocalDate deadlineMonth);
}

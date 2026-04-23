package com.finvanta.repository;

import com.finvanta.domain.entity.CreditBureauInquiry;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * CBS Credit Bureau Inquiry Repository per CICRA 2005.
 *
 * Per RBI: credit bureau check is mandatory before sanctioning any credit facility.
 * This repository provides queries for inquiry history, latest score lookup,
 * and application-level bureau check status.
 */
public interface CreditBureauInquiryRepository extends JpaRepository<CreditBureauInquiry, Long> {

    Optional<CreditBureauInquiry> findByTenantIdAndInquiryReference(
            String tenantId, String inquiryReference);

    List<CreditBureauInquiry> findByTenantIdAndCustomerIdOrderByInquiryDateDesc(
            String tenantId, Long customerId);

    List<CreditBureauInquiry> findByTenantIdAndApplicationId(
            String tenantId, Long applicationId);

    /** Latest successful inquiry for a customer from a specific bureau */
    @Query("SELECT c FROM CreditBureauInquiry c "
            + "WHERE c.tenantId = :tenantId AND c.customerId = :customerId "
            + "AND c.bureauName = :bureauName AND c.status = 'SUCCESS' "
            + "ORDER BY c.inquiryDate DESC")
    List<CreditBureauInquiry> findLatestSuccessful(
            @Param("tenantId") String tenantId,
            @Param("customerId") Long customerId,
            @Param("bureauName") String bureauName,
            org.springframework.data.domain.Pageable pageable);

    /** Check if a recent inquiry exists (within last N days) to avoid duplicate pulls */
    @Query("SELECT COUNT(c) > 0 FROM CreditBureauInquiry c "
            + "WHERE c.tenantId = :tenantId AND c.customerId = :customerId "
            + "AND c.bureauName = :bureauName AND c.status = 'SUCCESS' "
            + "AND c.inquiryDate >= :sinceDate")
    boolean existsRecentInquiry(
            @Param("tenantId") String tenantId,
            @Param("customerId") Long customerId,
            @Param("bureauName") String bureauName,
            @Param("sinceDate") java.time.LocalDateTime sinceDate);
}

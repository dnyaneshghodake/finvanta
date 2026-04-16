package com.finvanta.repository;

import com.finvanta.domain.entity.LoanApplication;
import com.finvanta.domain.enums.ApplicationStatus;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Loan Application Repository per Finacle APPINQ / Temenos AA.ARRANGEMENT.
 */
@Repository
public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {

    Optional<LoanApplication> findByTenantIdAndApplicationNumber(String tenantId, String applicationNumber);

    List<LoanApplication> findByTenantIdAndStatus(String tenantId, ApplicationStatus status);

    List<LoanApplication> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    boolean existsByTenantIdAndApplicationNumber(String tenantId, String applicationNumber);

    long countByTenantIdAndStatus(String tenantId, ApplicationStatus status);

    // === CBS APPINQ: Loan Application Search per Finacle APPINQ / Temenos AA.ARRANGEMENT.ENQUIRY ===

    /**
     * Search loan applications by application number, customer name, or customer CIF.
     * Per Finacle APPINQ: operations staff must locate applications instantly for
     * verification, approval, and RBI inspection queries.
     * All branches visible (ADMIN/AUDITOR). Branch-scoped variant below.
     */
    @Query("SELECT la FROM LoanApplication la WHERE la.tenantId = :tenantId "
            + "AND la.status IN ('SUBMITTED', 'VERIFIED', 'APPROVED') AND ("
            + "la.applicationNumber LIKE CONCAT('%', :query, '%') OR "
            + "la.customer.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "LOWER(la.customer.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(la.customer.lastName) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY la.createdAt DESC")
    List<LoanApplication> searchApplications(
            @Param("tenantId") String tenantId, @Param("query") String query,
            org.springframework.data.domain.Pageable pageable);

    /** Convenience overload — default max 500 results to prevent OOM at scale */
    default List<LoanApplication> searchApplications(String tenantId, String query) {
        return searchApplications(tenantId, query, org.springframework.data.domain.PageRequest.of(0, 500));
    }

    /** Branch-scoped application search for MAKER/CHECKER per Finacle BRANCH_CONTEXT */
    @Query("SELECT la FROM LoanApplication la WHERE la.tenantId = :tenantId "
            + "AND la.branch.id = :branchId "
            + "AND la.status IN ('SUBMITTED', 'VERIFIED', 'APPROVED') AND ("
            + "la.applicationNumber LIKE CONCAT('%', :query, '%') OR "
            + "la.customer.customerNumber LIKE CONCAT('%', :query, '%') OR "
            + "LOWER(la.customer.firstName) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(la.customer.lastName) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY la.createdAt DESC")
    List<LoanApplication> searchApplicationsByBranch(
            @Param("tenantId") String tenantId, @Param("branchId") Long branchId,
            @Param("query") String query, org.springframework.data.domain.Pageable pageable);

    /** Convenience overload — default max 500 results */
    default List<LoanApplication> searchApplicationsByBranch(String tenantId, Long branchId, String query) {
        return searchApplicationsByBranch(tenantId, branchId, query, org.springframework.data.domain.PageRequest.of(0, 500));
    }
}

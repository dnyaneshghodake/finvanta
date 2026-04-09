package com.finvanta.repository;

import com.finvanta.domain.entity.LoanDocument;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Loan Document Repository per Finacle DOCMAS standards.
 */
@Repository
public interface LoanDocumentRepository extends JpaRepository<LoanDocument, Long> {

    List<LoanDocument> findByTenantIdAndLoanApplicationId(String tenantId, Long loanApplicationId);

    List<LoanDocument> findByTenantIdAndLoanApplicationIdAndDocumentType(
            String tenantId, Long loanApplicationId, String documentType);

    /** Count of mandatory documents not yet verified for an application */
    @Query("SELECT COUNT(d) FROM LoanDocument d WHERE d.tenantId = :tenantId "
            + "AND d.loanApplication.id = :appId AND d.mandatory = true "
            + "AND d.verificationStatus != 'VERIFIED'")
    long countUnverifiedMandatoryDocuments(@Param("tenantId") String tenantId, @Param("appId") Long applicationId);

    /** All pending verification documents across applications (for checker queue) */
    @Query("SELECT d FROM LoanDocument d WHERE d.tenantId = :tenantId "
            + "AND d.verificationStatus = 'PENDING' ORDER BY d.createdAt ASC")
    List<LoanDocument> findPendingVerification(@Param("tenantId") String tenantId);
}

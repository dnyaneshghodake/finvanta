package com.finvanta.repository;

import com.finvanta.domain.entity.CustomerDocument;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Customer Document Repository per Finacle DOC_MASTER.
 */
@Repository
public interface CustomerDocumentRepository extends JpaRepository<CustomerDocument, Long> {

    /** All documents for a customer (ordered by upload date desc) */
    @Query("SELECT d FROM CustomerDocument d WHERE d.tenantId = :tenantId "
            + "AND d.customer.id = :customerId ORDER BY d.createdAt DESC")
    List<CustomerDocument> findByCustomer(
            @Param("tenantId") String tenantId, @Param("customerId") Long customerId);

    /** Documents of a specific type for a customer */
    @Query("SELECT d FROM CustomerDocument d WHERE d.tenantId = :tenantId "
            + "AND d.customer.id = :customerId AND d.documentType = :docType "
            + "ORDER BY d.createdAt DESC")
    List<CustomerDocument> findByCustomerAndType(
            @Param("tenantId") String tenantId,
            @Param("customerId") Long customerId,
            @Param("docType") String docType);

    /** Count of unverified documents for a customer (for dashboard badge) */
    @Query("SELECT COUNT(d) FROM CustomerDocument d WHERE d.tenantId = :tenantId "
            + "AND d.customer.id = :customerId AND d.verificationStatus = 'UPLOADED'")
    long countUnverified(
            @Param("tenantId") String tenantId, @Param("customerId") Long customerId);
}

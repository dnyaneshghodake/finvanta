package com.finvanta.repository;

import com.finvanta.domain.entity.InterBranchTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Repository for InterBranchTransaction — Settlement ledger per Finacle IB_SETTLEMENT.
 */
@Repository
public interface InterBranchSettlementRepository extends JpaRepository<InterBranchTransaction, Long> {

    /**
     * Find all pending transactions for settlement.
     */
    List<InterBranchTransaction> findByTenantIdAndSettlementStatusOrderByBusinessDateAsc(
        String tenantId,
        String settlementStatus
    );

    /**
     * Find transactions for a business date.
     */
    List<InterBranchTransaction> findByTenantIdAndBusinessDateOrderBySourceBranchIdAsc(
        String tenantId,
        LocalDate businessDate
    );

    /**
     * Sum outgoing receivables (what target branches owe us).
     */
    @Query("SELECT SUM(i.amount) FROM InterBranchTransaction i " +
           "WHERE i.tenantId = :tenantId AND i.targetBranchId = :branchId " +
           "AND i.settlementStatus = 'SETTLED'")
    BigDecimal sumReceivablesByBranch(
        @Param("tenantId") String tenantId,
        @Param("branchId") Long branchId
    );

    /**
     * Sum outgoing payables (what we owe source branches).
     */
    @Query("SELECT SUM(i.amount) FROM InterBranchTransaction i " +
           "WHERE i.tenantId = :tenantId AND i.sourceBranchId = :branchId " +
           "AND i.settlementStatus = 'SETTLED'")
    BigDecimal sumPayablesByBranch(
        @Param("tenantId") String tenantId,
        @Param("branchId") Long branchId
    );

    /**
     * Find transactions for a settlement batch.
     */
    List<InterBranchTransaction> findByTenantIdAndSettlementBatchRefOrderByBusinessDateAsc(
        String tenantId,
        String settlementBatchRef
    );
}


package com.finvanta.repository;

import com.finvanta.domain.entity.SettlementBatch;
import com.finvanta.domain.enums.PaymentRail;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Settlement Batch Repository per Finacle SETTLEMENT_MASTER.
 */
@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    /** Find settlement batch by RBI reference (for confirmation processing) */
    Optional<SettlementBatch> findByTenantIdAndRbiSettlementRef(String tenantId, String rbiSettlementRef);

    /** Find all batches for a date and rail */
    List<SettlementBatch> findByTenantIdAndSettlementDateAndRailTypeOrderByCreatedAtAsc(
            String tenantId, LocalDate settlementDate, PaymentRail railType);

    /** Find unreconciled batches for EOD check */
    @Query("SELECT sb FROM SettlementBatch sb WHERE sb.tenantId = :tenantId "
            + "AND sb.settlementDate = :date AND sb.status NOT IN ('RECONCILED', 'FAILED') "
            + "ORDER BY sb.railType")
    List<SettlementBatch> findUnreconciledByDate(
            @Param("tenantId") String tenantId, @Param("date") LocalDate date);

    /** Find pending batches (awaiting RBI confirmation) */
    List<SettlementBatch> findByTenantIdAndStatusOrderByCreatedAtAsc(String tenantId, String status);
}

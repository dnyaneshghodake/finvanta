package com.finvanta.repository;

import com.finvanta.domain.entity.TransactionBatch;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Transaction Batch Repository per Finacle/Temenos batch control standards.
 */
@Repository
public interface TransactionBatchRepository extends JpaRepository<TransactionBatch, Long> {

    /** All batches for a business date (for EOD validation and batch management UI) */
    List<TransactionBatch> findByTenantIdAndBusinessDateOrderByOpenedAtAsc(String tenantId, LocalDate businessDate);

    /** Find OPEN batches for a business date (for transaction posting validation) */
    @Query("SELECT tb FROM TransactionBatch tb WHERE tb.tenantId = :tenantId "
            + "AND tb.businessDate = :businessDate AND tb.status = 'OPEN'")
    List<TransactionBatch> findOpenBatches(
            @Param("tenantId") String tenantId, @Param("businessDate") LocalDate businessDate);

    /** Find a specific OPEN batch by name (for transaction tagging) */
    @Query("SELECT tb FROM TransactionBatch tb WHERE tb.tenantId = :tenantId "
            + "AND tb.businessDate = :businessDate AND tb.batchName = :batchName "
            + "AND tb.status = 'OPEN'")
    Optional<TransactionBatch> findOpenBatchByName(
            @Param("tenantId") String tenantId,
            @Param("businessDate") LocalDate businessDate,
            @Param("batchName") String batchName);

    /** Lock batch for running-totals update and close operation (pessimistic write).
     *  30s lock timeout per Finacle GL_LOCK standard — prevents indefinite blocking
     *  if a concurrent posting holds the batch lock during a long GL update. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
    @Query("SELECT tb FROM TransactionBatch tb WHERE tb.id = :id")
    Optional<TransactionBatch> findAndLockById(@Param("id") Long id);

    /** Check if any OPEN batches exist for a business date (EOD precondition) */
    @Query("SELECT COUNT(tb) FROM TransactionBatch tb WHERE tb.tenantId = :tenantId "
            + "AND tb.businessDate = :businessDate AND tb.status = 'OPEN'")
    long countOpenBatches(@Param("tenantId") String tenantId, @Param("businessDate") LocalDate businessDate);

    /** Check if any batch exists for a business date (for day open validation) */
    boolean existsByTenantIdAndBusinessDate(String tenantId, LocalDate businessDate);

    /** Check duplicate batch name for same business date */
    boolean existsByTenantIdAndBusinessDateAndBatchName(String tenantId, LocalDate businessDate, String batchName);

    /** All batches for a branch on a business date */
    List<TransactionBatch> findByTenantIdAndBranchIdAndBusinessDateOrderByOpenedAtAsc(
            String tenantId, Long branchId, LocalDate businessDate);
}

package com.finvanta.repository;

import com.finvanta.domain.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * CBS Ledger Entry Repository — append-only financial ledger per Finacle/Temenos standards.
 */
@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** Get the latest ledger entry for hash chain linking */
    @Query("SELECT le FROM LedgerEntry le WHERE le.tenantId = :tenantId " +
           "ORDER BY le.ledgerSequence DESC")
    List<LedgerEntry> findTopByTenantIdOrderByLedgerSequenceDesc(
        @Param("tenantId") String tenantId,
        org.springframework.data.domain.Pageable pageable);

    /**
     * Get the latest ledger entry with pessimistic lock to serialize concurrent postings.
     * Prevents race conditions where two concurrent postings get the same sequence/hash.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT le FROM LedgerEntry le WHERE le.tenantId = :tenantId " +
           "ORDER BY le.ledgerSequence DESC")
    List<LedgerEntry> findTopByTenantIdForUpdate(
        @Param("tenantId") String tenantId,
        org.springframework.data.domain.Pageable pageable);

    default Optional<LedgerEntry> findLatestByTenantId(String tenantId) {
        List<LedgerEntry> entries = findTopByTenantIdOrderByLedgerSequenceDesc(
            tenantId, org.springframework.data.domain.PageRequest.of(0, 1));
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /**
     * Get the latest ledger entry with pessimistic lock and return sequence + hash.
     * Used by LedgerService.postToLedger() to serialize concurrent ledger postings.
     */
    default Optional<LedgerEntry> findAndLockLatestByTenantId(String tenantId) {
        List<LedgerEntry> entries = findTopByTenantIdForUpdate(
            tenantId, org.springframework.data.domain.PageRequest.of(0, 1));
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /** Get the max sequence number for a tenant */
    @Query("SELECT COALESCE(MAX(le.ledgerSequence), 0) FROM LedgerEntry le WHERE le.tenantId = :tenantId")
    long getMaxSequence(@Param("tenantId") String tenantId);

    /** Ledger entries for a GL code on a business date (for reconciliation) */
    List<LedgerEntry> findByTenantIdAndGlCodeAndBusinessDateOrderByLedgerSequenceAsc(
        String tenantId, String glCode, LocalDate businessDate);

    /** All ledger entries for a business date (for day-end report) */
    List<LedgerEntry> findByTenantIdAndBusinessDateOrderByLedgerSequenceAsc(
        String tenantId, LocalDate businessDate);

    /** Ledger entries for a specific journal entry (for traceability) */
    List<LedgerEntry> findByTenantIdAndJournalEntryIdOrderByLedgerSequenceAsc(
        String tenantId, Long journalEntryId);

    /** Sum debit/credit for a GL code (for ledger-vs-GL reconciliation) */
    @Query("SELECT COALESCE(SUM(le.debitAmount), 0) FROM LedgerEntry le " +
           "WHERE le.tenantId = :tenantId AND le.glCode = :glCode")
    java.math.BigDecimal sumDebitByGlCode(@Param("tenantId") String tenantId,
                                           @Param("glCode") String glCode);

    @Query("SELECT COALESCE(SUM(le.creditAmount), 0) FROM LedgerEntry le " +
           "WHERE le.tenantId = :tenantId AND le.glCode = :glCode")
    java.math.BigDecimal sumCreditByGlCode(@Param("tenantId") String tenantId,
                                            @Param("glCode") String glCode);
}
package com.finvanta.repository;

import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.enums.DebitCredit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByTenantIdAndJournalRef(String tenantId, String journalRef);

    List<JournalEntry> findByTenantIdAndValueDate(String tenantId, LocalDate valueDate);

    @Query("SELECT je FROM JournalEntry je WHERE je.tenantId = :tenantId "
            + "AND je.valueDate BETWEEN :fromDate AND :toDate ORDER BY je.postingDate")
    List<JournalEntry> findByTenantIdAndValueDateBetween(
            @Param("tenantId") String tenantId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    List<JournalEntry> findByTenantIdAndSourceModuleAndSourceRef(
            String tenantId, String sourceModule, String sourceRef);

    /**
     * CBS Compound Journal Query: finds all journal entries for a specific account+module+date.
     * Used by Transaction360 to retrieve compound journal groups (e.g., write-off with 3 legs)
     * without over-matching journals from other business dates.
     *
     * Per Finacle TRAN_POSTING, compound journals share the same sourceModule, sourceRef,
     * and valueDate. Filtering by date prevents returning unrelated journals for the same
     * account from different business dates (e.g., accrual journals from prior days).
     */
    List<JournalEntry> findByTenantIdAndSourceModuleAndSourceRefAndValueDate(
            String tenantId, String sourceModule, String sourceRef, LocalDate valueDate);

    /**
     * CBS Reconciliation: Sum journal line amounts by GL code and debit/credit direction.
     * Used to compare journal totals against GL master balances.
     */
    @Query("SELECT COALESCE(SUM(jel.amount), 0) FROM JournalEntryLine jel " + "JOIN jel.journalEntry je "
            + "WHERE je.tenantId = :tenantId AND jel.glCode = :glCode "
            + "AND jel.debitCredit = :debitCredit AND je.posted = true")
    java.math.BigDecimal sumJournalLinesByGlCode(
            @Param("tenantId") String tenantId,
            @Param("glCode") String glCode,
            @Param("debitCredit") DebitCredit debitCredit);
}

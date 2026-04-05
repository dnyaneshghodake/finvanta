package com.finvanta.repository;

import com.finvanta.domain.entity.JournalEntry;
import com.finvanta.domain.enums.DebitCredit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByTenantIdAndJournalRef(String tenantId, String journalRef);

    List<JournalEntry> findByTenantIdAndValueDate(String tenantId, LocalDate valueDate);

    @Query("SELECT je FROM JournalEntry je WHERE je.tenantId = :tenantId " +
           "AND je.valueDate BETWEEN :fromDate AND :toDate ORDER BY je.postingDate")
    List<JournalEntry> findByTenantIdAndValueDateBetween(
        @Param("tenantId") String tenantId,
        @Param("fromDate") LocalDate fromDate,
        @Param("toDate") LocalDate toDate
    );

    List<JournalEntry> findByTenantIdAndSourceModuleAndSourceRef(String tenantId, String sourceModule, String sourceRef);

    /**
     * CBS Reconciliation: Sum journal line amounts by GL code and debit/credit direction.
     * Used to compare journal totals against GL master balances.
     */
    @Query("SELECT COALESCE(SUM(jel.amount), 0) FROM JournalEntryLine jel " +
           "JOIN jel.journalEntry je " +
           "WHERE je.tenantId = :tenantId AND jel.glCode = :glCode " +
           "AND jel.debitCredit = :debitCredit AND je.posted = true")
    java.math.BigDecimal sumJournalLinesByGlCode(
        @Param("tenantId") String tenantId,
        @Param("glCode") String glCode,
        @Param("debitCredit") DebitCredit debitCredit);
}

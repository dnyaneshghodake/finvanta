package com.finvanta.cbs.modules.teller.repository;

import com.finvanta.cbs.modules.teller.domain.CashDenomination;
import com.finvanta.cbs.modules.teller.domain.IndianCurrencyDenomination;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Cash Denomination Repository.
 *
 * <p>Provides per-transaction lookup (statement re-print, audit drill-down) and
 * aggregation queries used by:
 * <ul>
 *   <li>Till EOD reconciliation -- "what physical cash should this till hold?"</li>
 *   <li>Currency chest remittance -- "how many INR 2000 notes inflowed today?"</li>
 *   <li>Branch-level cash position reports per RBI Currency Management Dept.</li>
 * </ul>
 */
@Repository
public interface CashDenominationRepository extends JpaRepository<CashDenomination, Long> {

    /** Per-transaction denomination breakdown for statement / audit drill-down. */
    List<CashDenomination> findByTenantIdAndTransactionRefOrderByDenominationAsc(
            String tenantId, String transactionRef);

    /**
     * Returns one row per denomination with the net unit count for the given till
     * on the given business date. Net = SUM(IN units) - SUM(OUT units).
     *
     * <p>Used at till close to compute the expected physical denomination
     * breakdown the teller should be holding. Counterfeit-flagged rows are
     * excluded from the inflow side because they were never credited.
     */
    @Query("SELECT cd.denomination, "
            + "SUM(CASE WHEN cd.direction = 'IN' AND cd.counterfeitFlag = false THEN cd.unitCount "
            + "         WHEN cd.direction = 'OUT' THEN -cd.unitCount "
            + "         ELSE 0 END) "
            + "FROM CashDenomination cd "
            + "WHERE cd.tenantId = :tenantId "
            + "AND cd.tillId = :tillId "
            + "AND cd.valueDate = :valueDate "
            + "GROUP BY cd.denomination")
    List<Object[]> sumNetUnitsByTillAndDate(
            @Param("tenantId") String tenantId,
            @Param("tillId") Long tillId,
            @Param("valueDate") LocalDate valueDate);

    /**
     * Returns net rupee-value inflow per denomination for the entire branch on
     * the given business date. Used for currency chest remittance reports.
     */
    @Query("SELECT cd.denomination, "
            + "SUM(CASE WHEN cd.direction = 'IN' AND cd.counterfeitFlag = false THEN cd.totalValue "
            + "         WHEN cd.direction = 'OUT' THEN -cd.totalValue "
            + "         ELSE 0 END) "
            + "FROM CashDenomination cd "
            + "JOIN com.finvanta.cbs.modules.teller.domain.TellerTill t "
            + "  ON t.id = cd.tillId "
            + "WHERE cd.tenantId = :tenantId "
            + "AND t.branch.id = :branchId "
            + "AND cd.valueDate = :valueDate "
            + "GROUP BY cd.denomination")
    List<Object[]> sumNetValueByBranchAndDate(
            @Param("tenantId") String tenantId,
            @Param("branchId") Long branchId,
            @Param("valueDate") LocalDate valueDate);

    /**
     * Filters denominations marked counterfeit on a given business date for the
     * FICN (Forged Indian Currency Notes) report per RBI master direction.
     */
    @Query("SELECT cd FROM CashDenomination cd "
            + "WHERE cd.tenantId = :tenantId "
            + "AND cd.counterfeitFlag = true "
            + "AND cd.valueDate = :valueDate "
            + "ORDER BY cd.denomination DESC")
    List<CashDenomination> findCounterfeitsByDate(
            @Param("tenantId") String tenantId,
            @Param("valueDate") LocalDate valueDate);

    /**
     * Returns the gross inflow value of a specific denomination on a business
     * date. Used to alert when INR 2000 inflows cross the chest-remittance
     * threshold (per RBI 2023 directive).
     */
    @Query("SELECT COALESCE(SUM(cd.totalValue), 0) FROM CashDenomination cd "
            + "WHERE cd.tenantId = :tenantId "
            + "AND cd.denomination = :denomination "
            + "AND cd.direction = 'IN' "
            + "AND cd.counterfeitFlag = false "
            + "AND cd.valueDate = :valueDate")
    BigDecimal sumGrossInflowForDenomination(
            @Param("tenantId") String tenantId,
            @Param("denomination") IndianCurrencyDenomination denomination,
            @Param("valueDate") LocalDate valueDate);
}

package com.finvanta.repository;

import com.finvanta.domain.entity.StandingInstruction;
import com.finvanta.domain.enums.SIStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * CBS Standing Instruction Repository per Finacle SI_MASTER / Temenos STANDING.ORDER.
 *
 * Key query: findDueSIs — the EOD batch entry point that fetches all SIs
 * due for execution on the business date, ordered by priority (LOAN_EMI first).
 */
@Repository
public interface StandingInstructionRepository extends JpaRepository<StandingInstruction, Long> {

    Optional<StandingInstruction> findByTenantIdAndSiReference(String tenantId, String siReference);

    /**
     * CBS EOD: Find all Standing Instructions due for execution on the business date.
     *
     * Per Finacle SI_MASTER EOD execution rules:
     *   - Status must be ACTIVE (PAUSED/CANCELLED/EXPIRED are skipped)
     *   - nextExecutionDate must be <= businessDate (catches retries from prior days)
     *   - Ordered by priority ASC (LOAN_EMI=1 executes before UTILITY=5)
     *   - Then by sourceAccountNumber (deterministic order for deadlock prevention)
     *
     * The <= comparison (not ==) is critical for retry handling:
     *   If an SI failed on April 5 (nextExecutionDate=April 5) and today is April 6,
     *   the SI is still picked up because nextExecutionDate (5) <= businessDate (6).
     *   After max retries are exhausted, nextExecutionDate advances to the next cycle.
     */
    @Query("SELECT si FROM StandingInstruction si WHERE si.tenantId = :tenantId " +
           "AND si.status = 'ACTIVE' " +
           "AND si.nextExecutionDate <= :businessDate " +
           "AND (si.endDate IS NULL OR si.endDate >= :businessDate) " +
           "ORDER BY si.priority ASC, si.sourceAccountNumber ASC")
    List<StandingInstruction> findDueSIs(
        @Param("tenantId") String tenantId,
        @Param("businessDate") LocalDate businessDate);

    /** All SIs for a specific CASA account (for account view page) */
    List<StandingInstruction> findByTenantIdAndSourceAccountNumberOrderByPriorityAsc(
        String tenantId, String sourceAccountNumber);

    /** All SIs linked to a specific loan account */
    List<StandingInstruction> findByTenantIdAndLoanAccountNumber(
        String tenantId, String loanAccountNumber);

    /** All SIs for a customer (for CIF-level SI management) */
    List<StandingInstruction> findByTenantIdAndCustomerIdOrderByStatusAscPriorityAsc(
        String tenantId, Long customerId);

    /** Check if an active LOAN_EMI SI already exists for a loan (prevent duplicates) */
    @Query("SELECT COUNT(si) > 0 FROM StandingInstruction si WHERE si.tenantId = :tenantId " +
           "AND si.loanAccountNumber = :loanAccountNumber " +
           "AND si.destinationType = 'LOAN_EMI' " +
           "AND si.status NOT IN ('CANCELLED', 'EXPIRED', 'REJECTED')")
    boolean existsActiveLoanEmiSI(
        @Param("tenantId") String tenantId,
        @Param("loanAccountNumber") String loanAccountNumber);

    /** Count of failed SIs for operations dashboard */
    @Query("SELECT COUNT(si) FROM StandingInstruction si WHERE si.tenantId = :tenantId " +
           "AND si.status = 'ACTIVE' AND si.lastExecutionStatus LIKE 'FAILED%'")
    long countFailedSIs(@Param("tenantId") String tenantId);

    // === Operations Dashboard Queries (Finacle SI_MONITOR) ===

    /** Count of active SIs by destination type for dashboard summary */
    @Query("SELECT si.destinationType, COUNT(si) FROM StandingInstruction si " +
           "WHERE si.tenantId = :tenantId AND si.status = 'ACTIVE' GROUP BY si.destinationType")
    List<Object[]> countActiveSIsByType(@Param("tenantId") String tenantId);

    /** Total active SIs count */
    long countByTenantIdAndStatus(String tenantId, SIStatus status);

    /** SIs due for execution on a specific date (for upcoming execution forecast) */
    @Query("SELECT COUNT(si) FROM StandingInstruction si WHERE si.tenantId = :tenantId " +
           "AND si.status = 'ACTIVE' AND si.nextExecutionDate = :date")
    long countDueOnDate(@Param("tenantId") String tenantId, @Param("date") LocalDate date);

    /** All active SIs with recent failures (for operations attention) */
    @Query("SELECT si FROM StandingInstruction si WHERE si.tenantId = :tenantId " +
           "AND si.status = 'ACTIVE' AND si.lastExecutionStatus LIKE 'FAILED%' " +
           "ORDER BY si.lastExecutionDate DESC")
    List<StandingInstruction> findFailedActiveSIs(@Param("tenantId") String tenantId);

    /** All SIs regardless of status for full dashboard view */
    @Query("SELECT si FROM StandingInstruction si WHERE si.tenantId = :tenantId " +
           "ORDER BY si.status ASC, si.priority ASC, si.nextExecutionDate ASC")
    List<StandingInstruction> findAllForDashboard(@Param("tenantId") String tenantId);
}

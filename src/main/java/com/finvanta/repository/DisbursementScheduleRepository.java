package com.finvanta.repository;

import com.finvanta.domain.entity.DisbursementSchedule;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for DisbursementSchedule — Multi-tranche tracking per Finacle DISB_MASTER.
 */
@Repository
public interface DisbursementScheduleRepository extends JpaRepository<DisbursementSchedule, Long> {

    /** All tranches for a loan account, ordered by tranche number */
    List<DisbursementSchedule> findByTenantIdAndLoanAccountIdOrderByTrancheNumberAsc(
            String tenantId, Long loanAccountId);

    /** Count disbursed tranches for a loan account */
    @Query("SELECT COUNT(ds) FROM DisbursementSchedule ds WHERE ds.tenantId = :tenantId "
            + "AND ds.loanAccount.id = :accountId AND ds.status = 'DISBURSED'")
    long countDisbursedTranches(@Param("tenantId") String tenantId, @Param("accountId") Long accountId);
}

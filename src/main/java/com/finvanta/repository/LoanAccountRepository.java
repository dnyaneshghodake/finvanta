package com.finvanta.repository;

import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanAccountRepository extends JpaRepository<LoanAccount, Long> {

    Optional<LoanAccount> findByTenantIdAndAccountNumber(String tenantId, String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.accountNumber = :accountNumber")
    Optional<LoanAccount> findAndLockByTenantIdAndAccountNumber(
        @Param("tenantId") String tenantId,
        @Param("accountNumber") String accountNumber
    );

    List<LoanAccount> findByTenantIdAndStatus(String tenantId, LoanStatus status);

    List<LoanAccount> findByTenantIdAndCustomerId(String tenantId, Long customerId);

    @Query("SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status = 'ACTIVE' AND la.daysPastDue >= :threshold")
    List<LoanAccount> findNpaCandidates(
        @Param("tenantId") String tenantId,
        @Param("threshold") int threshold
    );

    @Query("SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status = 'ACTIVE'")
    List<LoanAccount> findAllActiveAccounts(@Param("tenantId") String tenantId);

    @Query("SELECT COALESCE(SUM(la.outstandingPrincipal), 0) FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status = 'ACTIVE'")
    BigDecimal calculateTotalOutstandingPrincipal(@Param("tenantId") String tenantId);
}

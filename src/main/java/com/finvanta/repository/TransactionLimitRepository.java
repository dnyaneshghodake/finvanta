package com.finvanta.repository;

import com.finvanta.domain.entity.TransactionLimit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Transaction Limit Repository per Finacle/Temenos Internal Controls.
 */
@Repository
public interface TransactionLimitRepository extends JpaRepository<TransactionLimit, Long> {

    /**
     * Find applicable limit for a role and transaction type.
     * Returns the most specific limit: type-specific first, then 'ALL' fallback.
     */
    @Query("SELECT tl FROM TransactionLimit tl WHERE tl.tenantId = :tenantId "
            + "AND tl.role = :role AND tl.transactionType = :txnType AND tl.active = true")
    Optional<TransactionLimit> findByRoleAndType(
            @Param("tenantId") String tenantId, @Param("role") String role, @Param("txnType") String txnType);

    /** Fallback: find limit for role with transaction type 'ALL' */
    @Query("SELECT tl FROM TransactionLimit tl WHERE tl.tenantId = :tenantId "
            + "AND tl.role = :role AND tl.transactionType = 'ALL' AND tl.active = true")
    Optional<TransactionLimit> findByRoleForAllTypes(@Param("tenantId") String tenantId, @Param("role") String role);

    /**
     * All active limits for a role (for login COC hydration).
     * Per Finacle TRAN_AUTH: returns all configured limits so the UI can
     * pre-validate amounts before submission. The server re-validates
     * via TransactionLimitService on every transaction.
     * Indexed on (tenant_id, role, transaction_type).
     */
    @Query("SELECT tl FROM TransactionLimit tl WHERE tl.tenantId = :tenantId "
            + "AND tl.role = :role AND tl.active = true "
            + "ORDER BY tl.transactionType ASC")
    List<TransactionLimit> findActiveByTenantIdAndRole(
            @Param("tenantId") String tenantId, @Param("role") String role);

    /** All limits for a tenant (for admin management UI) */
    List<TransactionLimit> findByTenantIdOrderByRoleAscTransactionTypeAsc(String tenantId);
}

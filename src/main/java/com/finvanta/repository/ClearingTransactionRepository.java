package com.finvanta.repository;

import com.finvanta.domain.entity.ClearingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ClearingTransaction — Payment clearing/settlement ledger.
 */
@Repository
public interface ClearingTransactionRepository extends JpaRepository<ClearingTransaction, Long> {

    /**
     * Find clearing transaction by reference.
     */
    Optional<ClearingTransaction> findByTenantIdAndClearingRef(String tenantId, String clearingRef);

    /**
     * Find all pending clearing transactions (for confirmation/settlement).
     */
    List<ClearingTransaction> findByTenantIdAndStatusInOrderByInitiatedDateAsc(
        String tenantId,
        List<String> statuses
    );

    /**
     * Find clearing transactions for a business date.
     */
    List<ClearingTransaction> findByTenantIdAndBusinessDateAndStatusOrderByInitiatedDateAsc(
        String tenantId,
        LocalDate businessDate,
        String status
    );

    /**
     * Find unconfirmed transactions (for reconciliation retry).
     */
    List<ClearingTransaction> findByTenantIdAndStatusNotInOrderByInitiatedDateAsc(
        String tenantId,
        List<String> excludedStatuses
    );

    /**
     * Check for pending clearing on a business date (EOD check).
     */
    boolean existsByTenantIdAndBusinessDateAndStatusNotIn(
        String tenantId,
        LocalDate businessDate,
        List<String> excludedStatuses
    );

    /**
     * Find by source type (for batch clearing).
     */
    List<ClearingTransaction> findByTenantIdAndSourceTypeAndStatusOrderByInitiatedDateAsc(
        String tenantId,
        String sourceType,
        String status
    );
}


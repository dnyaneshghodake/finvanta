package com.finvanta.repository;

import com.finvanta.domain.entity.AuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTenantIdAndEntityTypeAndEntityIdOrderByEventTimestampDesc(
            String tenantId, String entityType, Long entityId);

    // CBS: ORDER BY id DESC (not eventTimestamp DESC) so the verification walk
    // sees records in the same order the hash chain was built. The chain is
    // constructed by logEvent() which calls findLatestByTenantId() — that uses
    // ORDER BY id DESC. If we order by eventTimestamp instead, records with
    // identical timestamps (common when multiple events fire in the same
    // millisecond — login + password change + session) can be returned in
    // arbitrary order, causing verifyRecentChainIntegrity() to report a false
    // VIOLATED even though the chain is intact.
    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.id DESC")
    List<AuditLog> findRecentAuditLogsPaged(
            @Param("tenantId") String tenantId, Pageable pageable);

    default List<AuditLog> findRecentAuditLogs(String tenantId) {
        return findRecentAuditLogsPaged(tenantId, PageRequest.of(0, 500));
    }

    /**
     * Ascending-id walk over the ENTIRE audit chain for integrity verification.
     * Per Finacle/Temenos Tier-1 and RBI IT Governance Direction 2023 §8.3: audit
     * chain verification must cover every record, not just the recent window.
     * Partial verification is not acceptable to an RBI on-site inspector.
     */
    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.id ASC")
    List<AuditLog> findAllByTenantIdOrderByIdAsc(
            @Param("tenantId") String tenantId, Pageable pageable);

    @Query("SELECT COUNT(al) FROM AuditLog al WHERE al.tenantId = :tenantId")
    long countByTenantId(@Param("tenantId") String tenantId);

    default Optional<AuditLog> findLatestByTenantId(String tenantId) {
        List<AuditLog> logs = findTopByTenantIdOrderByIdDesc(tenantId);
        return logs.isEmpty() ? Optional.empty() : Optional.of(logs.get(0));
    }

    /**
     * Fetch the latest audit record with a pessimistic write lock (SELECT ... FOR UPDATE).
     * Used by {@code AuditService.logEvent()} to serialize concurrent audit inserts
     * and prevent hash-chain breaks.
     *
     * <p><b>Why this is needed:</b> {@code logEvent()} uses {@code REQUIRES_NEW}
     * propagation, so two concurrent audit events (e.g., dual LOGOUT events fired
     * simultaneously during a single logout action) each run in their own transaction.
     * Without a lock, both threads call {@code findLatestByTenantId()} and see the
     * same "latest" record, both set {@code previousHash} to that record's hash,
     * and both save — resulting in two records pointing to the same predecessor
     * instead of forming a chain. The second record's {@code previousHash} should
     * point to the first record, not to their shared predecessor.
     *
     * <p>The pessimistic lock on the latest record ensures the second thread blocks
     * until the first thread's REQUIRES_NEW transaction commits, at which point the
     * second thread sees the first thread's newly-inserted record as the latest.
     */
    default Optional<AuditLog> findAndLockLatestByTenantId(String tenantId) {
        List<AuditLog> logs = findTopByTenantIdForUpdate(tenantId, PageRequest.of(0, 1));
        return logs.isEmpty() ? Optional.empty() : Optional.of(logs.get(0));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.id DESC")
    List<AuditLog> findTopByTenantIdForUpdate(
            @Param("tenantId") String tenantId, Pageable pageable);

    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.id DESC")
    List<AuditLog> findTopByTenantIdOrderByIdDesc(
            @Param("tenantId") String tenantId, Pageable pageable);

    default List<AuditLog> findTopByTenantIdOrderByIdDesc(String tenantId) {
        return findTopByTenantIdOrderByIdDesc(tenantId, PageRequest.of(0, 1));
    }

    // === CBS AUDITINQ: Audit Trail Search per Finacle AUDIT_INQUIRY / RBI IT Governance §8.3 ===

    /**
     * Search audit logs by entity type, action, user, or description.
     * Per RBI IT Governance Direction 2023 §8.3: audit trails must be searchable
     * by entity, user, and date range for regulatory examination.
     * Per Finacle AUDIT_INQUIRY: operations/compliance staff must locate audit
     * records instantly for internal investigation and RBI on-site inspection.
     */
    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId AND ("
            + "LOWER(al.entityType) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.action) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.performedBy) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.module) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.description) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY al.id DESC")
    List<AuditLog> searchAuditLogsPaged(
            @Param("tenantId") String tenantId, @Param("query") String query,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Search audit logs with date range filter.
     * Per RBI Inspection Manual: inspectors specify date ranges for examination periods.
     * Combined with text search for targeted investigation (e.g., "all KYC_VERIFY by user X in March").
     */
    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId "
            + "AND al.eventTimestamp >= :fromDate AND al.eventTimestamp < :toDate AND ("
            + "LOWER(al.entityType) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.action) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.performedBy) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.module) LIKE LOWER(CONCAT('%', :query, '%')) OR "
            + "LOWER(al.description) LIKE LOWER(CONCAT('%', :query, '%')))"
            + " ORDER BY al.id DESC")
    List<AuditLog> searchAuditLogsWithDateRange(
            @Param("tenantId") String tenantId, @Param("query") String query,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            org.springframework.data.domain.Pageable pageable);
}

package com.finvanta.repository;

import com.finvanta.domain.entity.AuditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTenantIdAndEntityTypeAndEntityIdOrderByEventTimestampDesc(
            String tenantId, String entityType, Long entityId);

    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.eventTimestamp DESC")
    List<AuditLog> findRecentAuditLogsPaged(
            @Param("tenantId") String tenantId, Pageable pageable);

    default List<AuditLog> findRecentAuditLogs(String tenantId) {
        return findRecentAuditLogsPaged(tenantId, PageRequest.of(0, 500));
    }

    default Optional<AuditLog> findLatestByTenantId(String tenantId) {
        List<AuditLog> logs = findTopByTenantIdOrderByIdDesc(tenantId);
        return logs.isEmpty() ? Optional.empty() : Optional.of(logs.get(0));
    }

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
            + " ORDER BY al.eventTimestamp DESC")
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
            + " ORDER BY al.eventTimestamp DESC")
    List<AuditLog> searchAuditLogsWithDateRange(
            @Param("tenantId") String tenantId, @Param("query") String query,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            org.springframework.data.domain.Pageable pageable);
}

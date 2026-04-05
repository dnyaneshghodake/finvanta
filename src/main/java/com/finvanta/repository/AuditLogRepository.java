package com.finvanta.repository;

import com.finvanta.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTenantIdAndEntityTypeAndEntityIdOrderByEventTimestampDesc(
        String tenantId, String entityType, Long entityId
    );

    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.eventTimestamp DESC")
    List<AuditLog> findRecentAuditLogsPaged(@Param("tenantId") String tenantId,
                                             org.springframework.data.domain.Pageable pageable);

    default List<AuditLog> findRecentAuditLogs(String tenantId) {
        return findRecentAuditLogsPaged(tenantId, org.springframework.data.domain.PageRequest.of(0, 500));
    }

    default Optional<AuditLog> findLatestByTenantId(String tenantId) {
        List<AuditLog> logs = findTopByTenantIdOrderByIdDesc(tenantId);
        return logs.isEmpty() ? Optional.empty() : Optional.of(logs.get(0));
    }

    @Query("SELECT al FROM AuditLog al WHERE al.tenantId = :tenantId ORDER BY al.id DESC")
    List<AuditLog> findTopByTenantIdOrderByIdDesc(@Param("tenantId") String tenantId,
                                                    org.springframework.data.domain.Pageable pageable);

    default List<AuditLog> findTopByTenantIdOrderByIdDesc(String tenantId) {
        return findTopByTenantIdOrderByIdDesc(tenantId, org.springframework.data.domain.PageRequest.of(0, 1));
    }
}

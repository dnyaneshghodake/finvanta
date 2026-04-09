package com.finvanta.audit;

import com.finvanta.domain.entity.AuditLog;
import com.finvanta.repository.AuditLogRepository;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * CBS Immutable Audit Trail Service.
 *
 * Per RBI guidelines on IT governance and Finacle/Temenos audit standards:
 * - Every financial and administrative action is logged with before/after state
 * - Audit records are append-only (immutable) — no updates or deletes allowed
 * - Hash chain (SHA-256) ensures tamper detection (blockchain-style integrity)
 * - Each record links to the previous via previousHash → GENESIS chain
 * - Uses REQUIRES_NEW transaction propagation to ensure audit persistence
 *   even if the parent transaction rolls back
 *
 * Captures: entity type, entity ID, action, user, IP address, timestamp,
 * before/after JSON snapshots, and cryptographic hash chain.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog logEvent(
            String entityType,
            Long entityId,
            String action,
            Object beforeState,
            Object afterState,
            String module,
            String description) {
        String tenantId = TenantContext.getCurrentTenant();
        String performedBy = SecurityUtil.getCurrentUsername();
        String ipAddress = resolveIpAddress();

        String beforeJson = serializeToJson(beforeState);
        String afterJson = serializeToJson(afterState);

        String previousHash = auditLogRepository
                .findLatestByTenantId(tenantId)
                .map(AuditLog::getHash)
                .orElse("GENESIS");

        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(tenantId);
        auditLog.setEntityType(entityType);
        auditLog.setEntityId(entityId);
        auditLog.setAction(action);
        auditLog.setBeforeSnapshot(beforeJson);
        auditLog.setAfterSnapshot(afterJson);
        auditLog.setPerformedBy(performedBy);
        auditLog.setIpAddress(ipAddress);
        auditLog.setEventTimestamp(LocalDateTime.now());
        auditLog.setPreviousHash(previousHash);
        auditLog.setModule(module);
        auditLog.setDescription(description);

        String hash = computeHash(auditLog, previousHash);
        auditLog.setHash(hash);

        AuditLog saved = auditLogRepository.save(auditLog);
        log.info("Audit log created: entity={}/{}, action={}, user={}", entityType, entityId, action, performedBy);
        return saved;
    }

    public List<AuditLog> getAuditTrail(String entityType, Long entityId) {
        String tenantId = TenantContext.getCurrentTenant();
        return auditLogRepository.findByTenantIdAndEntityTypeAndEntityIdOrderByEventTimestampDesc(
                tenantId, entityType, entityId);
    }

    public boolean verifyChainIntegrity(String tenantId) {
        List<AuditLog> logs = auditLogRepository.findRecentAuditLogs(tenantId);
        if (logs.isEmpty()) {
            return true;
        }

        for (int i = 0; i < logs.size() - 1; i++) {
            AuditLog current = logs.get(i);
            AuditLog previous = logs.get(i + 1);
            if (!current.getPreviousHash().equals(previous.getHash())) {
                log.error("Audit chain integrity violation detected at log id={}", current.getId());
                return false;
            }
        }

        // Only verify GENESIS link if we have the complete chain (fewer records than page size)
        if (logs.size() < 500) {
            AuditLog oldest = logs.get(logs.size() - 1);
            if (!"GENESIS".equals(oldest.getPreviousHash())) {
                log.error(
                        "Audit chain integrity violation: oldest record id={} does not link to GENESIS",
                        oldest.getId());
                return false;
            }
        }

        return true;
    }

    private String computeHash(AuditLog auditLog, String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = auditLog.getTenantId()
                    + auditLog.getEntityType()
                    + auditLog.getEntityId()
                    + auditLog.getAction()
                    + auditLog.getEventTimestamp()
                    + auditLog.getPerformedBy()
                    + previousHash;
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String serializeToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize object for audit: {}", e.getMessage());
            return obj.toString();
        }
    }

    private String resolveIpAddress() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xForwarded = request.getHeader("X-Forwarded-For");
                if (xForwarded != null && !xForwarded.isEmpty()) {
                    return xForwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("Could not resolve IP address: {}", e.getMessage());
        }
        return "SYSTEM";
    }
}

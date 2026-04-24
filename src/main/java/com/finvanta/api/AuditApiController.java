package com.finvanta.api;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AuditLog;
import com.finvanta.repository.AuditLogRepository;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Audit Trail REST API per Finacle AUDIT_INQUIRY / RBI IT Governance §8.3.
 *
 * <p>Read-only inquiry endpoints for the immutable audit trail.
 * Per RBI IT Governance Direction 2023 §8.3: audit trails must be
 * searchable by entity, user, and date range for regulatory examination.
 *
 * <p>CBS SECURITY: Audit logs are physically immutable — database triggers
 * prevent UPDATE and DELETE operations on the audit_logs table. The hash
 * chain (SHA-256) provides cryptographic tamper detection.
 *
 * <p>CBS Role Matrix:
 * <ul>
 *   <li>AUDITOR/ADMIN → all audit inquiry endpoints</li>
 *   <li>No mutation endpoints — audit logs are write-once via AuditService</li>
 * </ul>
 *
 * <p>Used by the Next.js BFF for:
 * <ul>
 *   <li>Audit trail screen (AUDITOR's primary work screen)</li>
 *   <li>RBI inspection support (date-range scoped search)</li>
 *   <li>Chain integrity verification dashboard widget</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditApiController {

    private static final int MAX_RESULTS = 500;

    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;

    public AuditApiController(
            AuditLogRepository auditLogRepository,
            AuditService auditService) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
    }

    /**
     * Get recent audit logs (default view — last 500 entries).
     * Per Finacle AUDIT_INQUIRY: the default landing page for AUDITOR role.
     */
    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>>
            getRecentLogs(
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "100") int size) {
        String tenantId = TenantContext.getCurrentTenant();
        int safeSize = Math.min(Math.max(size, 1), MAX_RESULTS);
        var logs = auditLogRepository.findRecentAuditLogsPaged(
                tenantId, PageRequest.of(
                        Math.max(0, page), safeSize));
        var items = logs.stream()
                .map(AuditLogResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Search audit logs by entity type, action, user, module, or description.
     * Optional date range filter for RBI inspection period queries.
     *
     * <p>Per RBI Inspection Manual: inspectors specify examination periods
     * and require all audit records within that window for the requested
     * entity/user. The date range filter enables this.
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>>
            searchLogs(
                    @RequestParam(required = false) String q,
                    @RequestParam(required = false) String fromDate,
                    @RequestParam(required = false) String toDate,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "100") int size) {
        String tenantId = TenantContext.getCurrentTenant();
        int safeSize = Math.min(Math.max(size, 1), MAX_RESULTS);
        var pageable = PageRequest.of(Math.max(0, page), safeSize);

        List<AuditLog> logs;
        if (q == null || q.trim().length() < 2) {
            logs = auditLogRepository.findRecentAuditLogsPaged(
                    tenantId, pageable);
        } else if (fromDate != null && toDate != null
                && !fromDate.isBlank() && !toDate.isBlank()) {
            LocalDateTime from = LocalDate.parse(fromDate)
                    .atStartOfDay();
            LocalDateTime to = LocalDate.parse(toDate)
                    .plusDays(1).atStartOfDay();
            logs = auditLogRepository
                    .searchAuditLogsWithDateRange(
                            tenantId, q.trim(),
                            from, to, pageable);
        } else {
            logs = auditLogRepository.searchAuditLogsPaged(
                    tenantId, q.trim(), pageable);
        }

        var items = logs.stream()
                .map(AuditLogResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    /**
     * Verify audit chain cryptographic integrity.
     * Per Finacle AUDIT_TRAIL / RBI IT Governance §8.3: the SHA-256
     * hash chain must be intact — any broken link indicates tampering.
     *
     * <p>Returns a simple boolean result. If false, the SOC/compliance
     * team must investigate immediately.
     */
    @GetMapping("/integrity")
    @PreAuthorize("hasAnyRole('AUDITOR', 'ADMIN')")
    public ResponseEntity<ApiResponse<IntegrityResponse>>
            verifyIntegrity() {
        String tenantId = TenantContext.getCurrentTenant();
        boolean intact = auditService
                .verifyChainIntegrity(tenantId);
        return ResponseEntity.ok(ApiResponse.success(
                new IntegrityResponse(intact,
                        intact ? "Audit chain intact"
                                : "INTEGRITY VIOLATION DETECTED")));
    }

    /**
     * Record a UI screen-access event for the audit trail.
     *
     * <p>Called by the Next.js BFF on every authenticated screen navigation
     * (dashboard, accounts, transfers, loans, customers, KYC, etc.) so the
     * audit trail captures what the user viewed — not just what they mutated.
     * Per RBI IT Governance Direction 2023 §8.3, read/view access to customer
     * data must be auditable for inspection.
     *
     * <p><b>Authenticated, any role.</b> Unlike the inquiry endpoints above
     * (AUDITOR/ADMIN only), this is written by every signed-in user as a
     * side effect of navigation.
     *
     * <p><b>Returns 202 Accepted with an empty body.</b> The endpoint is
     * fire-and-forget from the BFF's perspective; a failure to record must
     * not block navigation. Any server error becomes a standard error envelope
     * via the global exception handler.
     *
     * <p><b>Performance note — follow-up candidate.</b> {@link AuditService#logEvent}
     * appends to the SHA-256 hash-chained {@code audit_logs} table, meaning
     * every page navigation adds a row that participates in
     * {@code verifyRecentChainIntegrity}. On a high-traffic tenant this will
     * dominate the audit table. A future refactor should consider a lightweight
     * {@code screen_access_log} table (no hash chain) for view events, keeping
     * the cryptographic chain reserved for state-changing actions. For now we
     * use the standard audit path to keep all access records in one place.
     */
    @PostMapping("/screen-access")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> recordScreenAccess(
            @Valid @RequestBody ScreenAccessRequest req) {
        auditService.logEvent(
                "UI_SCREEN",
                null,
                "SCREEN_ACCESS",
                null,
                null,
                "UI_NAVIGATION",
                buildDescription(req));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static String buildDescription(ScreenAccessRequest req) {
        StringBuilder sb = new StringBuilder("screenCode=").append(req.screenCode());
        if (req.returnTo() != null && !req.returnTo().isBlank()) {
            sb.append(" | returnTo=").append(req.returnTo());
        }
        if (req.referrer() != null && !req.referrer().isBlank()) {
            sb.append(" | referrer=").append(req.referrer());
        }
        return sb.toString();
    }

    // === Request DTOs ===

    /**
     * Screen-access audit payload from the BFF.
     *
     * <p>Field name {@code screenCode} matches the Next.js BFF contract —
     * it sends a screen identifier (e.g. "DASHBOARD", "CUSTOMER_VIEW"), not
     * a raw URL path. Field sizes match the audit trail's {@code description}
     * column capacity and are intentionally restrictive — this endpoint is
     * hit on every page load so oversized payloads are rejected at the
     * boundary rather than truncated in the DB.
     */
    public record ScreenAccessRequest(
            @NotBlank @Size(max = 200) String screenCode,
            @Size(max = 500) String returnTo,
            @Size(max = 500) String referrer) {}

    // === Response DTOs ===

    /**
     * Audit log entry response per Finacle AUDIT_INQUIRY.
     * CBS SECURITY: beforeSnapshot and afterSnapshot may contain
     * entity state — the UI should render them as read-only JSON.
     * No PII masking needed here because AUDITOR role has explicit
     * access to full audit data per RBI IT Governance §8.3.
     */
    public record AuditLogResponse(
            Long id,
            String entityType,
            Long entityId,
            String action,
            String performedBy,
            String module,
            String description,
            String branchCode,
            String eventTimestamp,
            String ipAddress,
            boolean chainValid) {
        static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(
                    a.getId(),
                    a.getEntityType(),
                    a.getEntityId(),
                    a.getAction(),
                    a.getPerformedBy(),
                    a.getModule(),
                    a.getDescription(),
                    a.getBranchCode(),
                    a.getEventTimestamp() != null
                            ? a.getEventTimestamp().toString()
                            : null,
                    a.getIpAddress(),
                    a.getHash() != null
                            && !a.getHash().isBlank());
        }
    }

    public record IntegrityResponse(
            boolean intact,
            String message) {}
}

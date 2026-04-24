package com.finvanta.api;

import com.finvanta.audit.AuditService;
import com.finvanta.config.CorrelationIdMdcFilter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CBS Audit REST API per Finacle AUDIT_TRAIL / Temenos AUDIT.LOG.
 *
 * <p>Thin orchestration layer over {@link AuditService} — no business logic.
 * The underlying {@code audit_logs} table is immutable and hash-chained per
 * RBI IT Governance Direction 2023 §8.3 / §8.5.
 *
 * <p><b>Screen-access logging ({@code POST /screen-access}):</b> invoked by the
 * Next.js BFF on every navigation to a CBS screen. Captures which operator
 * opened which module/action for the regulator-mandated "who saw what" trail.
 * The event is reduced onto the existing {@code audit_logs} hash chain rather
 * than a dedicated table — this keeps a single append-only integrity chain
 * for all audit events (login, GL posting, screen access, etc.) which is the
 * Finacle / Temenos reference pattern.
 *
 * <p><b>Why no dedicated table / migration:</b> {@code audit_logs} already
 * provides tenant isolation, branch attribution, immutability, hash chain, and
 * IP / user capture via {@link AuditService}. A separate {@code audit_screen_access}
 * table would duplicate that infrastructure and create a second chain the
 * regulator would have to verify independently.
 *
 * <p><b>Why no {@code IdempotencyRegistry}:</b> that table is shaped for
 * financial postings (stores {@code transactionRef} / {@code voucherNumber} /
 * {@code journalEntryId}). Screen-access events are non-financial and
 * high-volume; a duplicate on retry is a diagnostic annoyance, not a
 * correctness bug.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * CBS Screen-Access Audit per RBI IT Governance Direction 2023 §8.3.
     *
     * <p>Records that the authenticated operator opened a specific CBS screen.
     * Tenant, branch, username, IP, and server timestamp are captured by
     * {@link AuditService#logEvent} from the existing security/tenant context
     * — this endpoint only supplies the screen-specific fields on the request.
     *
     * <p>The {@code clientTimestamp} on the request is treated as diagnostic
     * only (clock-skew window); the authoritative timestamp stored on the
     * audit row is the server-side {@code eventTimestamp} set by
     * {@code AuditService} at insert time.
     *
     * <p>Correlation id is read from MDC (populated and regex-validated by
     * {@link CorrelationIdMdcFilter}) rather than directly from the
     * {@code X-Correlation-Id} header so we always use the validated value.
     */
    @PostMapping("/screen-access")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<Void>> logScreenAccess(
            @Valid @RequestBody ScreenAccessRequest req) {
        String correlationId = MDC.get(CorrelationIdMdcFilter.MDC_KEY);
        String description = "screenCode=" + req.screenCode()
                + " | pathname=" + req.pathname()
                + " | clientTs=" + req.clientTimestamp()
                + (correlationId != null ? " | correlationId=" + correlationId : "");
        // entityId is null — screen access is a user-level event not tied to
        // a single domain entity. AuditService stores 0L for null entityId
        // (see AuditService#logEventInternal).
        auditService.logEvent(
                "SCREEN",
                null,
                "ACCESS",
                null,
                req,
                "UI",
                description);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Screen-access request body.
     *
     * <p>Field shape mirrors the BFF allow-list in {@code endpointPolicy.ts}:
     * the browser supplies {@code screenCode}, {@code pathname}, and a
     * client-side {@code clientTimestamp}. All other audit fields (tenant,
     * branch, operator, server timestamp, IP) are derived server-side.
     *
     * @param screenCode      dot-delimited MODULE.ACTION identifier
     *                        (e.g. {@code TRANSFER.NEW}, {@code LOAN.DISBURSE});
     *                        pattern restricts to uppercase letters + underscore
     *                        to prevent injection and PII bleed through this field.
     * @param pathname        browser pathname; must not contain raw customer
     *                        identifiers — the BFF is responsible for hashing
     *                        or omitting PII-bearing path segments before this
     *                        call. Capped at 512 chars.
     * @param clientTimestamp browser-reported ISO-8601 instant; stored for
     *                        clock-skew diagnostics only, NOT trusted for the
     *                        authoritative audit row timestamp.
     */
    public record ScreenAccessRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]+\\.[A-Z_]+$") String screenCode,
            @NotBlank @Size(max = 512) String pathname,
            @NotNull Instant clientTimestamp) {}
}

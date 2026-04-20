package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.repository.AuditLogRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Audit Trail Controller per Finacle AUDIT_INQUIRY / RBI IT Governance Direction 2023 §8.3.
 *
 * Per RBI IT Governance Direction 2023 Section 8.3:
 * - Audit trails must be searchable by entity, user, and date range
 * - Must be available for regulatory examination within 24 hours of request
 * - Chain integrity (hash chain) must be verifiable on demand
 *
 * Per Finacle AUDIT_INQUIRY / Temenos AUDIT.TRAIL.ENQUIRY:
 * - Search by entity type (Customer, LoanAccount, DepositAccount, etc.)
 * - Search by action (CREATE, UPDATE, KYC_VERIFY, DEACTIVATE, etc.)
 * - Search by user (performedBy) for maker-checker investigation
 * - Date range filter for examination period queries
 */
@Controller
@RequestMapping("/audit")
public class AuditController {

    /** CBS: Max audit results per search to prevent OOM on large audit tables */
    private static final int MAX_AUDIT_RESULTS = 500;

    /** CBS: Known entity types for audit trail — whitelist per defense-in-depth. */
    private static final Set<String> KNOWN_ENTITY_TYPES = Set.of(
            "Customer", "DepositAccount", "LoanAccount", "LoanApplication",
            "Transaction", "JournalEntry", "Branch", "ProductMaster",
            "StandingInstruction", "ApprovalWorkflow", "TransactionLimit",
            "ChargeConfig", "BusinessCalendar", "User");

    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;

    public AuditController(AuditLogRepository auditLogRepository, AuditService auditService) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
    }

    @GetMapping("/logs")
    public ModelAndView auditLogs() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("audit/logs");
        mav.addObject("auditLogs", auditLogRepository.findRecentAuditLogs(tenantId));
        // CBS: page-load indicator uses the bounded recent-window check
        // (sub-100ms). Full O(N) chain walk is gated behind /audit/verify
        // per Finacle/Temenos Tier-1 audit UX -- a synchronous multi-minute
        // walk on every audit page view would make the UI unusable on any
        // production-sized audit table.
        mav.addObject("chainIntegrity",
                auditService.verifyRecentChainIntegrity(tenantId));
        return mav;
    }

    /**
     * CBS full audit chain verification endpoint per RBI IT Governance Direction 2023 §8.3.
     *
     * <p>Triggers the O(N) walk of every audit record. Intentionally kept OFF the
     * default page-load path (see {@link #auditLogs()}) because it can take minutes
     * on a production-sized audit table. This endpoint exists so operations /
     * compliance staff can trigger a full verification on demand during RBI
     * inspection prep.
     */
    @GetMapping("/verify")
    public ModelAndView verifyFullChain() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("audit/logs");
        mav.addObject("auditLogs", auditLogRepository.findRecentAuditLogs(tenantId));
        mav.addObject("chainIntegrity", auditService.verifyChainIntegrity(tenantId));
        mav.addObject("fullChainVerified", true);
        return mav;
    }

    /**
     * CBS Per-Entity Audit Trail per Finacle AUDIT_INQUIRY / RBI IT Governance §8.3.
     * Linked from detail screens (customer/view, deposit/view, loan/account-details)
     * via "View Audit Trail" button. Shows all audit records for a specific entity.
     *
     * Per RBI IT Governance Direction 2023 §8.3: inspectors must be able to view
     * the complete change history of any individual entity (customer, account, loan).
     */
    @GetMapping("/entity")
    public ModelAndView entityAuditTrail(
            @RequestParam String entityType,
            @RequestParam Long entityId) {
        // CBS: Validate entityType against known types to prevent arbitrary strings
        // in audit queries and log output. entityId must be non-negative.
        if (entityType == null || !KNOWN_ENTITY_TYPES.contains(entityType)) {
            throw new BusinessException("INVALID_ENTITY_TYPE",
                    "Unknown entity type for audit trail: " + entityType);
        }
        if (entityId == null || entityId < 0) {
            throw new BusinessException("INVALID_ENTITY_ID",
                    "Entity ID must be a non-negative number");
        }
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("audit/logs");
        // CBS: Paginate entity audit trail to prevent OOM on high-activity entities.
        // Same MAX_AUDIT_RESULTS cap as search endpoint for consistency.
        mav.addObject("auditLogs",
                auditLogRepository.findByTenantIdAndEntityTypeAndEntityId(
                        tenantId, entityType, entityId,
                        PageRequest.of(0, MAX_AUDIT_RESULTS)));
        mav.addObject("chainIntegrity",
                auditService.verifyRecentChainIntegrity(tenantId));
        mav.addObject("entityFilter", entityType + " #" + entityId);
        return mav;
    }

    /**
     * CBS Audit Trail Search per Finacle AUDIT_INQUIRY / RBI IT Governance §8.3.
     * Searches by entity type, action, user, module, or description.
     * Optional date range filter for RBI inspection period queries.
     *
     * Per RBI IT Governance Direction 2023 §8.3: audit trails must be searchable
     * by entity, user, and date range for regulatory examination.
     * Per RBI Inspection Manual: inspectors specify examination periods and require
     * all audit records within that window for the requested entity/user.
     */
    @GetMapping("/search")
    public ModelAndView searchAuditLogs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("audit/logs");

        if (q != null && !q.isBlank() && q.trim().length() >= 2) {
            String trimmed = q.trim();

            // CBS: Date range filter — if both dates provided, scope the search.
            // Per RBI Inspection Manual: inspectors always specify examination periods.
            if (fromDate != null && toDate != null && !fromDate.isBlank() && !toDate.isBlank()) {
                try {
                    LocalDateTime from = LocalDate.parse(fromDate).atStartOfDay();
                    LocalDateTime to = LocalDate.parse(toDate).plusDays(1).atStartOfDay();
                    mav.addObject("auditLogs",
                            auditLogRepository.searchAuditLogsWithDateRange(
                                    tenantId, trimmed, from, to,
                                    PageRequest.of(0, MAX_AUDIT_RESULTS)));
                    mav.addObject("fromDate", fromDate);
                    mav.addObject("toDate", toDate);
                } catch (Exception e) {
                    // Invalid date format — fall back to no date filter
                    mav.addObject("auditLogs",
                            auditLogRepository.searchAuditLogsPaged(
                                    tenantId, trimmed,
                                    PageRequest.of(0, MAX_AUDIT_RESULTS)));
                    mav.addObject("error", "Invalid date format. Showing results without date filter.");
                }
            } else {
                mav.addObject("auditLogs",
                        auditLogRepository.searchAuditLogsPaged(
                                tenantId, trimmed,
                                PageRequest.of(0, MAX_AUDIT_RESULTS)));
            }
            mav.addObject("searchQuery", q);
        } else {
            // No query — show recent logs (default view)
            mav.addObject("auditLogs", auditLogRepository.findRecentAuditLogs(tenantId));
        }

        mav.addObject("chainIntegrity",
                auditService.verifyRecentChainIntegrity(tenantId));
        return mav;
    }
}

package com.finvanta.accounting;

import com.finvanta.batch.ReconciliationService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS Tier-1 Financial Safety Kill Switch per Finacle RISK_CONTROL / Temenos EB.SYSTEM.STATUS.
 *
 * <p><b>Purpose:</b> Blocks financial postings for a specific tenant when a critical
 * integrity failure is detected. This is the last line of defense against financial
 * data corruption.
 *
 * <p><b>MULTI-TENANT ISOLATION (CBS Tier-1 Mandatory):</b> Restrictions are PER-TENANT.
 * One tenant's GL imbalance must NOT block other tenants' operations. Per RBI IT
 * Governance Direction 2023 §8.3 and Finacle RISK_CONTROL: multi-tenant systems
 * must provide tenant-isolated controls.
 *
 * <p><b>Thread safety:</b> Uses {@link ConcurrentHashMap} with immutable
 * {@link RestrictionState} records. This eliminates the race condition where a
 * concurrent reader could see the restriction flag as {@code true} but metadata
 * fields as {@code null} (the old AtomicBoolean + volatile fields pattern had a
 * happens-before gap between the flag set and the metadata writes).
 *
 * <p><b>Trigger conditions:</b>
 * <ul>
 *   <li>GL reconciliation imbalance (SUM(ledger) ≠ GL balance)</li>
 *   <li>Ledger hash chain break (tamper detected)</li>
 *   <li>Batch total mismatch</li>
 *   <li>Manual activation by risk team</li>
 * </ul>
 *
 * <p><b>When RESTRICTED:</b>
 * <ul>
 *   <li>TransactionEngine.executeInternal() throws POSTING_RESTRICTED before any GL touch</li>
 *   <li>Read operations (balance inquiry, statements) continue to work</li>
 *   <li>EOD batch is blocked from starting</li>
 *   <li>Only ADMIN can clear the restriction after investigation</li>
 * </ul>
 *
 * <p><b>Per RBI IT Governance Direction 2023 §8.3:</b> A CBS must have automated
 * controls that halt financial operations when data integrity is compromised.
 * Continuing to post on a corrupted ledger compounds the damage exponentially.
 *
 * <p><b>Cluster note:</b> This is JVM-scoped. In a clustered deployment, each node
 * maintains its own map. A DB-backed flag (tenant_config table) should be added for
 * cluster-wide coordination in production.
 *
 * @see ReconciliationService
 * @see LedgerService#verifyChainIntegrity()
 */
@Service
public class PostingIntegrityGuard {

    private static final Logger log = LoggerFactory.getLogger(PostingIntegrityGuard.class);

    /**
     * Immutable restriction state per tenant. Using a record ensures all fields
     * are published atomically — no concurrent reader can see partial state.
     */
    private record RestrictionState(String reason, String restrictedAt, String restrictedBy) {}

    /**
     * Per-tenant restriction map. Key = tenantId, Value = immutable restriction state.
     * A tenant is restricted if and only if it has an entry in this map.
     * ConcurrentHashMap provides thread-safe lock-free access.
     */
    private final ConcurrentHashMap<String, RestrictionState> restrictedTenants = new ConcurrentHashMap<>();

    /**
     * Checks if posting is allowed for the CURRENT tenant. Called by TransactionEngine
     * at the very start of executeInternal() — BEFORE any validation, sequence
     * allocation, or DB write.
     *
     * @throws BusinessException with code POSTING_RESTRICTED if the tenant is in restricted mode
     */
    public void assertPostingAllowed() {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : null;
        if (tenantId == null) {
            return; // No tenant context — pre-auth or system bootstrap
        }
        RestrictionState state = restrictedTenants.get(tenantId);
        if (state != null) {
            log.error("POSTING BLOCKED — tenant {} in RESTRICTED MODE: {}", tenantId, state.reason());
            throw new BusinessException(
                    "POSTING_RESTRICTED",
                    "Financial postings are currently BLOCKED for tenant " + tenantId
                            + " due to integrity failure: " + state.reason()
                            + ". Restricted at: " + state.restrictedAt()
                            + " by: " + state.restrictedBy()
                            + ". Contact IT Risk team to investigate and clear the restriction.");
        }
    }

    /**
     * Activates RESTRICTED MODE — blocks all financial postings.
     *
     * <p>Called by:
     * <ul>
     *   <li>ReconciliationService when GL↔Ledger mismatch detected</li>
     *   <li>LedgerService when hash chain break detected</li>
     *   <li>BatchService when batch total mismatch detected</li>
     *   <li>Admin controller for manual risk activation</li>
     * </ul>
     *
     * @param reason    Human-readable reason (e.g., "GL 1001 debit mismatch: GL=50000, Ledger=49500")
     * @param activatedBy Who/what triggered the restriction (e.g., "EOD_RECONCILIATION", "ADMIN:john")
     */
    public void activateRestriction(String reason, String activatedBy) {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : "UNKNOWN";
        // CBS CRITICAL: Build immutable state BEFORE publishing to the map.
        // ConcurrentHashMap.put() is atomic — concurrent readers see either the
        // complete RestrictionState or no entry, never partial metadata.
        RestrictionState state = new RestrictionState(
                reason,
                java.time.LocalDateTime.now().toString(),
                activatedBy);
        restrictedTenants.put(tenantId, state);
        log.error("FINANCIAL SAFETY KILL SWITCH ACTIVATED — POSTINGS BLOCKED FOR TENANT {}. "
                + "Reason: {} | By: {}",
                tenantId, reason, activatedBy);
    }

    /**
     * Clears RESTRICTED MODE — resumes financial postings.
     * Should only be called after the integrity issue has been investigated and resolved.
     *
     * @param clearedBy Who cleared the restriction (must be ADMIN role)
     * @param resolution Description of how the issue was resolved
     */
    public void clearRestriction(String clearedBy, String resolution) {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : "UNKNOWN";
        RestrictionState prev = restrictedTenants.remove(tenantId);
        String prevReason = prev != null ? prev.reason() : "N/A";
        log.warn("Financial safety restriction CLEARED for tenant {}. Previous reason: {} | Cleared by: {} | Resolution: {}",
                tenantId, prevReason, clearedBy, resolution);
    }

    /** Returns true if the CURRENT tenant is in RESTRICTED MODE. */
    public boolean isRestricted() {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : null;
        return tenantId != null && restrictedTenants.containsKey(tenantId);
    }

    /** Returns the reason for the current tenant's restriction, or null if not restricted. */
    public String getRestrictionReason() {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : null;
        if (tenantId == null) return null;
        RestrictionState state = restrictedTenants.get(tenantId);
        return state != null ? state.reason() : null;
    }

    /** Returns when the current tenant's restriction was activated, or null. */
    public String getRestrictedAt() {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : null;
        if (tenantId == null) return null;
        RestrictionState state = restrictedTenants.get(tenantId);
        return state != null ? state.restrictedAt() : null;
    }

    /** Returns who activated the current tenant's restriction, or null. */
    public String getRestrictedBy() {
        String tenantId = TenantContext.isSet() ? TenantContext.getCurrentTenant() : null;
        if (tenantId == null) return null;
        RestrictionState state = restrictedTenants.get(tenantId);
        return state != null ? state.restrictedBy() : null;
    }
}

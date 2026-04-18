package com.finvanta.accounting;

import com.finvanta.batch.ReconciliationService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS Tier-1 Financial Safety Kill Switch per Finacle RISK_CONTROL / Temenos EB.SYSTEM.STATUS.
 *
 * <p><b>Purpose:</b> Blocks ALL financial postings when a critical integrity failure
 * is detected. This is the last line of defense against financial data corruption.
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
 * <p><b>Thread safety:</b> Uses AtomicBoolean for lock-free concurrent access.
 * The restriction flag is JVM-scoped — in a clustered deployment, each node
 * maintains its own flag. A DB-backed flag (tenant_config table) should be
 * added for cluster-wide coordination in production.
 *
 * @see ReconciliationService
 * @see LedgerService#verifyChainIntegrity()
 */
@Service
public class PostingIntegrityGuard {

    private static final Logger log = LoggerFactory.getLogger(PostingIntegrityGuard.class);

    /**
     * JVM-level restriction flag. When true, ALL financial postings are blocked.
     * AtomicBoolean for thread-safe lock-free access from concurrent posting threads.
     */
    private final AtomicBoolean restricted = new AtomicBoolean(false);

    /** Human-readable reason for the restriction (for error messages and audit). */
    private volatile String restrictionReason = null;

    /** Timestamp when restriction was activated. */
    private volatile String restrictedAt = null;

    /** Who/what activated the restriction. */
    private volatile String restrictedBy = null;

    /**
     * Checks if posting is allowed. Called by TransactionEngine at the very start
     * of executeInternal() — BEFORE any validation, sequence allocation, or DB write.
     *
     * @throws BusinessException with code POSTING_RESTRICTED if the system is in restricted mode
     */
    public void assertPostingAllowed() {
        if (restricted.get()) {
            log.error("POSTING BLOCKED — system in RESTRICTED MODE: {}", restrictionReason);
            throw new BusinessException(
                    "POSTING_RESTRICTED",
                    "Financial postings are currently BLOCKED due to integrity failure: "
                            + restrictionReason
                            + ". Restricted at: " + restrictedAt
                            + " by: " + restrictedBy
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
        restricted.set(true);
        this.restrictionReason = reason;
        this.restrictedAt = java.time.LocalDateTime.now().toString();
        this.restrictedBy = activatedBy;
        log.error("🚨 FINANCIAL SAFETY KILL SWITCH ACTIVATED — ALL POSTINGS BLOCKED. "
                + "Reason: {} | By: {} | Tenant: {}",
                reason, activatedBy, TenantContext.isSet() ? TenantContext.getCurrentTenant() : "N/A");
    }

    /**
     * Clears RESTRICTED MODE — resumes financial postings.
     * Should only be called after the integrity issue has been investigated and resolved.
     *
     * @param clearedBy Who cleared the restriction (must be ADMIN role)
     * @param resolution Description of how the issue was resolved
     */
    public void clearRestriction(String clearedBy, String resolution) {
        String prevReason = this.restrictionReason;
        restricted.set(false);
        this.restrictionReason = null;
        this.restrictedAt = null;
        this.restrictedBy = null;
        log.warn("Financial safety restriction CLEARED. Previous reason: {} | Cleared by: {} | Resolution: {}",
                prevReason, clearedBy, resolution);
    }

    /** Returns true if the system is currently in RESTRICTED MODE. */
    public boolean isRestricted() {
        return restricted.get();
    }

    /** Returns the reason for the current restriction, or null if not restricted. */
    public String getRestrictionReason() {
        return restrictionReason;
    }

    /** Returns when the restriction was activated, or null. */
    public String getRestrictedAt() {
        return restrictedAt;
    }

    /** Returns who activated the restriction, or null. */
    public String getRestrictedBy() {
        return restrictedBy;
    }
}

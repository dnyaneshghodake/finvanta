package com.finvanta.accounting;

import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PostingIntegrityGuard — CBS Tier-1 Financial Safety Kill Switch.
 *
 * Verifies:
 * 1. Tenant isolation: Tenant A's restriction does NOT block Tenant B
 * 2. Atomic metadata: restriction reason/timestamp visible immediately
 * 3. Clear/restore: restriction can be cleared per-tenant
 * 4. No-tenant-context: pre-auth requests are not blocked
 */
class PostingIntegrityGuardTest {

    private PostingIntegrityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PostingIntegrityGuard();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Unrestricted tenant: assertPostingAllowed does not throw")
    void unrestrictedTenant_postingAllowed() {
        TenantContext.setCurrentTenant("TENANT_A");
        assertDoesNotThrow(() -> guard.assertPostingAllowed());
        assertFalse(guard.isRestricted());
        assertNull(guard.getRestrictionReason());
    }

    @Test
    @DisplayName("Restricted tenant: assertPostingAllowed throws POSTING_RESTRICTED")
    void restrictedTenant_postingBlocked() {
        TenantContext.setCurrentTenant("TENANT_A");
        guard.activateRestriction("GL mismatch on 1001", "EOD_RECONCILIATION");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.assertPostingAllowed());
        assertEquals("POSTING_RESTRICTED", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("TENANT_A"));
        assertTrue(ex.getMessage().contains("GL mismatch on 1001"));
        assertTrue(guard.isRestricted());
        assertEquals("GL mismatch on 1001", guard.getRestrictionReason());
        assertNotNull(guard.getRestrictedAt());
        assertEquals("EOD_RECONCILIATION", guard.getRestrictedBy());
    }

    @Test
    @DisplayName("CRITICAL: Tenant A restriction does NOT block Tenant B")
    void tenantIsolation_restrictionDoesNotCrossLeak() {
        // Restrict Tenant A
        TenantContext.setCurrentTenant("TENANT_A");
        guard.activateRestriction("Hash chain break", "LEDGER_VERIFY");

        // Switch to Tenant B — must NOT be blocked
        TenantContext.setCurrentTenant("TENANT_B");
        assertDoesNotThrow(() -> guard.assertPostingAllowed(),
                "Tenant B must NOT be blocked by Tenant A's restriction");
        assertFalse(guard.isRestricted());
        assertNull(guard.getRestrictionReason());

        // Verify Tenant A is still blocked
        TenantContext.setCurrentTenant("TENANT_A");
        assertThrows(BusinessException.class, () -> guard.assertPostingAllowed());
        assertTrue(guard.isRestricted());
    }

    @Test
    @DisplayName("Clear restriction: tenant resumes posting after clear")
    void clearRestriction_resumesPosting() {
        TenantContext.setCurrentTenant("TENANT_A");
        guard.activateRestriction("Batch mismatch", "BATCH_CLOSE");

        // Verify blocked
        assertThrows(BusinessException.class, () -> guard.assertPostingAllowed());

        // Clear
        guard.clearRestriction("ADMIN:john", "Investigated — false positive");

        // Verify unblocked
        assertDoesNotThrow(() -> guard.assertPostingAllowed());
        assertFalse(guard.isRestricted());
        assertNull(guard.getRestrictionReason());
    }

    @Test
    @DisplayName("No tenant context: assertPostingAllowed does not throw")
    void noTenantContext_postingAllowed() {
        TenantContext.clear();
        assertDoesNotThrow(() -> guard.assertPostingAllowed());
    }

    @Test
    @DisplayName("Metadata atomicity: reason and timestamp visible immediately after activation")
    void metadataAtomicity() {
        TenantContext.setCurrentTenant("TENANT_X");
        guard.activateRestriction("Ledger tamper seq 42", "CHAIN_VERIFY");

        // All metadata must be visible in the same read
        assertEquals("Ledger tamper seq 42", guard.getRestrictionReason());
        assertNotNull(guard.getRestrictedAt());
        assertEquals("CHAIN_VERIFY", guard.getRestrictedBy());
    }

    @Test
    @DisplayName("Multiple tenants: independent restriction lifecycle")
    void multipleTenants_independentLifecycle() {
        // Restrict both tenants
        TenantContext.setCurrentTenant("BANK_A");
        guard.activateRestriction("GL drift", "EOD");

        TenantContext.setCurrentTenant("BANK_B");
        guard.activateRestriction("Hash break", "VERIFY");

        // Both blocked
        TenantContext.setCurrentTenant("BANK_A");
        assertTrue(guard.isRestricted());
        TenantContext.setCurrentTenant("BANK_B");
        assertTrue(guard.isRestricted());

        // Clear only BANK_A
        TenantContext.setCurrentTenant("BANK_A");
        guard.clearRestriction("ADMIN", "Fixed");
        assertFalse(guard.isRestricted());

        // BANK_B still blocked
        TenantContext.setCurrentTenant("BANK_B");
        assertTrue(guard.isRestricted());
        assertEquals("Hash break", guard.getRestrictionReason());
    }
}
package com.finvanta.legacy;

import com.finvanta.batch.ClearingEngine;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.util.TenantContext;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LEGACY: CBS Clearing/Settlement Service per Finacle CLG_MASTER.
 *
 * <p>Moved to {@code com.finvanta.legacy} per Finacle/Temenos Tier-1 hygiene: all
 * deprecated @Service beans live in a single visible package so accidental
 * injections are obvious during code review.
 *
 * @deprecated This service is fully replaced by {@link ClearingEngine} which provides:
 * - Rail-specific suspense GLs (NEFT/RTGS/IMPS/UPI inward + outward)
 * - ClearingDirection (INWARD/OUTWARD) with separate GL flows
 * - ClearingStatus state machine with lifecycle tracking
 * - ClearingCycle for NEFT batch netting
 * - SettlementBatch for RBI settlement tracking
 * - Idempotent external reference (UTR) validation
 * - Branch-scoped clearing transactions
 * - Maker-checker for high-value outward payments
 *
 * The initiateClearingTransaction(), confirmClearing(), and failClearing() methods
 * have been removed because the ClearingTransaction entity was restructured
 * (clearingRef→externalRefNo, sourceType→paymentRail enum, status→ClearingStatus enum,
 * initiatedDate→initiatedAt, businessDate→valueDate, counterpartyDetails→3 separate fields,
 * settlementDate→settledAt) and the old methods cannot compile against the new entity.
 *
 * Only validateSuspenseBalance() is retained for backward compatibility with any
 * callers that still check the legacy GL 2400 suspense balance.
 * New code must use {@link ClearingEngine#validateAllSuspenseBalances(LocalDate)}
 * or {@link ClearingEngine#reconcileSuspensePerRail(LocalDate)}.
 */
@Deprecated(forRemoval = true, since = "2026-04")
@Service
public class ClearingService {

    private static final Logger log = LoggerFactory.getLogger(ClearingService.class);

    private final GLMasterRepository glRepository;

    public ClearingService(GLMasterRepository glRepository) {
        this.glRepository = glRepository;
    }

    /**
     * Emits a visible startup warning so operators notice this legacy bean is still
     * wired. Per Tier-1 migration hygiene: deprecated financial subsystems MUST
     * announce themselves at startup to force explicit retirement planning.
     */
    @PostConstruct
    void announceLegacy() {
        log.warn("LEGACY bean active: com.finvanta.legacy.ClearingService. "
                + "Migrate callers to ClearingEngine -- this class will be removed.");
    }

    /**
     * EOD suspense reconciliation check for LEGACY GL 2400.
     * Validates that the deprecated Clearing Suspense GL (2400) balance = 0.
     *
     * @deprecated Use {@link ClearingEngine#validateAllSuspenseBalances(LocalDate)} for
     *             per-rail suspense validation, or {@link ClearingEngine#reconcileSuspensePerRail(LocalDate)}
     *             for detailed GL vs clearing transaction reconciliation.
     *
     * @param businessDate CBS business date
     * @return true if suspense balance is zero, false if non-zero (flagged for investigation)
     */
    @Deprecated(forRemoval = true)
    @Transactional(readOnly = true)
    public boolean validateSuspenseBalance(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        var glAccount = glRepository.findByTenantIdAndGlCode(tenantId, "2400").orElse(null);

        if (glAccount == null) {
            log.warn("Clearing Suspense GL (2400) not found");
            return true; // Pass if GL doesn't exist
        }

        BigDecimal netBalance = glAccount.getCreditBalance().subtract(glAccount.getDebitBalance());

        if (netBalance.compareTo(BigDecimal.ZERO) != 0) {
            log.warn("Clearing Suspense GL (2400) non-zero at EOD: {}. Investigate stuck transactions.", netBalance);
            return false;
        }

        log.info("Clearing Suspense GL (2400) verified zero at EOD");
        return true;
    }
}

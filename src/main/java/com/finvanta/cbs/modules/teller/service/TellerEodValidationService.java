package com.finvanta.cbs.modules.teller.service;

import com.finvanta.cbs.common.constants.CbsErrorCodes;
import com.finvanta.cbs.modules.teller.domain.VaultPosition;
import com.finvanta.cbs.modules.teller.repository.TellerTillRepository;
import com.finvanta.cbs.modules.teller.repository.VaultPositionRepository;
import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Teller EOD Pre-Flight Validation Service per RBI Master Circular on
 * Cash Management at Branches §4.3 and CBS Tier-1 EOD trial-run standard.
 *
 * <p>Tier-1 invariant: EOD must not run while the cash custody chain is
 * unreconciled. The teller module produces two cash subledgers:
 * <ul>
 *   <li>{@code TellerTill.currentBalance} -- per-teller cash on hand (subledger
 *       for GL BANK_OPERATIONS at branch level).</li>
 *   <li>{@code VaultPosition.currentBalance} -- per-branch safe (companion
 *       subledger to GL BANK_OPERATIONS).</li>
 * </ul>
 * Both must be in CLOSED state with sign-off / counted-balance recorded BEFORE
 * EOD generates balance snapshots and runs subledger reconciliation. Otherwise:
 * <ol>
 *   <li>The day's CASA/loan snapshots (Steps 8.6 / 8.7 of {@code EodOrchestrator})
 *       are asserted against an unreconciled BANK_OPERATIONS subledger.</li>
 *   <li>{@code SubledgerReconciliationService.reconcile()} (Step 7.1) sees a
 *       live mid-shift till figure, not a counted figure — silent variance.</li>
 *   <li>The reconciliation invariant
 *       {@code SUM(till.currentBalance) + vault.currentBalance == GL BANK_OPERATIONS @ branch}
 *       only holds when both sides are CLOSED with verified counted balance.</li>
 * </ol>
 *
 * <p>This service is a CROSS-MODULE pre-flight check: it lives in the teller
 * module so the "what counts as closed" predicate stays here, and it is
 * called by {@code BatchController.runEodApply} BEFORE
 * {@code EodOrchestrator.executeEod}. Same architectural shape as
 * {@code TransactionBatchService.validateAllBatchesClosed}.
 *
 * <p>Thread safety: read-only ({@code @Transactional(readOnly = true)}). No
 * locks acquired, no state mutated. Safe to call from the EOD apply thread
 * and from {@code EodTrialService} (the trial-run UI checklist).
 */
@Service
public class TellerEodValidationService {

    private static final Logger log = LoggerFactory.getLogger(TellerEodValidationService.class);

    private final TellerTillRepository tillRepository;
    private final VaultPositionRepository vaultRepository;
    private final BranchRepository branchRepository;

    public TellerEodValidationService(
            TellerTillRepository tillRepository,
            VaultPositionRepository vaultRepository,
            BranchRepository branchRepository) {
        this.tillRepository = tillRepository;
        this.vaultRepository = vaultRepository;
        this.branchRepository = branchRepository;
    }

    /**
     * Asserts that every teller till at every operational branch is in a
     * terminal state (CLOSED) for the given business date. Throws
     * {@code TELLER_TILLS_STILL_OPEN} listing the offending branches and
     * the count of active tills per branch so the EOD operator can route
     * the issue to the right branch supervisor.
     *
     * <p>"Active" means OPEN, PENDING_OPEN, or PENDING_CLOSE — anything that
     * is not the terminal CLOSED or SUSPENDED state. SUSPENDED is excluded
     * intentionally: a fraud-investigation freeze does not block EOD per RBI
     * Master Direction on Frauds (the suspended till is parked, not active).
     *
     * @param businessDate the EOD business date
     * @throws BusinessException CBS-TELLER-100 if any branch has active tills
     */
    @Transactional(readOnly = true)
    public void validateAllTillsClosed(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        var branches = branchRepository.findAllOperationalBranches(tenantId);

        List<String> offending = new ArrayList<>();
        long totalActive = 0;
        for (Branch branch : branches) {
            long active = tillRepository.countActiveAtBranch(
                    tenantId, branch.getId(), businessDate);
            if (active > 0) {
                offending.add(branch.getBranchCode() + " (" + active + " active)");
                totalActive += active;
            }
        }

        if (!offending.isEmpty()) {
            log.error(
                    "EOD blocked: {} active till(s) across {} branch(es) on {} -- {}",
                    totalActive, offending.size(), businessDate, String.join(", ", offending));
            throw new BusinessException(
                    CbsErrorCodes.TELLER_TILLS_STILL_OPEN,
                    totalActive + " teller till(s) still OPEN/PENDING across "
                            + offending.size() + " branch(es) on " + businessDate
                            + ": " + String.join(", ", offending)
                            + ". Every teller must close their till "
                            + "(and a supervisor must sign off) before EOD can run.");
        }

        log.info("EOD pre-flight: all tills CLOSED across {} branch(es) for {}",
                branches.size(), businessDate);
    }

    /**
     * Asserts that every operational branch's vault is in CLOSED state for
     * the given business date. Throws {@code TELLER_VAULTS_NOT_CLOSED}
     * listing branches whose vault is still OPEN or has no vault row at all.
     *
     * <p>A missing vault row is treated as "not closed" rather than "no vault
     * needed" because every operational branch is expected to have a vault
     * position once the branch has had any cash activity. If a branch is
     * genuinely cash-free (e.g., a digital-only branch), the vault must still
     * be opened with zero balance and closed with zero counted balance for the
     * audit trail to be complete -- this is an explicit RBI requirement, not
     * a code workaround.
     *
     * @param businessDate the EOD business date
     * @throws BusinessException CBS-TELLER-101 if any branch's vault is not CLOSED
     */
    @Transactional(readOnly = true)
    public void validateAllVaultsClosed(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        var branches = branchRepository.findAllOperationalBranches(tenantId);

        List<String> offending = new ArrayList<>();
        for (Branch branch : branches) {
            VaultPosition vault = vaultRepository
                    .findByBranchAndDate(tenantId, branch.getId(), businessDate)
                    .orElse(null);
            if (vault == null) {
                offending.add(branch.getBranchCode() + " (no vault opened)");
            } else if (!vault.isClosed()) {
                offending.add(branch.getBranchCode() + " (status=" + vault.getStatus() + ")");
            }
        }

        if (!offending.isEmpty()) {
            log.error(
                    "EOD blocked: {} branch vault(s) not CLOSED on {} -- {}",
                    offending.size(), businessDate, String.join(", ", offending));
            throw new BusinessException(
                    CbsErrorCodes.TELLER_VAULTS_NOT_CLOSED,
                    offending.size() + " branch vault(s) not CLOSED on " + businessDate
                            + ": " + String.join(", ", offending)
                            + ". Every vault custodian must close their vault "
                            + "(after all tills are CLOSED) before EOD can run.");
        }

        log.info("EOD pre-flight: all vaults CLOSED across {} branch(es) for {}",
                branches.size(), businessDate);
    }

    /**
     * Convenience composite: runs till and vault validation in the canonical
     * order. Per RBI Internal Controls: tills must close BEFORE vaults
     * (vault custodian reconciles tills' returned cash before counting the
     * safe). This method preserves that ordering -- the till check throws
     * first, so the operator sees the upstream issue rather than a confusing
     * "vault not closed" when the real cause is an open till.
     *
     * <p>Cheap to call: both internal methods are read-only with simple
     * COUNT queries; no locks, no GL touch. Safe inside the EOD trial-run
     * read-only transaction.
     */
    @Transactional(readOnly = true)
    public void validateTellerEodReadiness(LocalDate businessDate) {
        validateAllTillsClosed(businessDate);
        validateAllVaultsClosed(businessDate);
    }
}

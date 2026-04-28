package com.finvanta.cbs.modules.teller.service;

import com.finvanta.cbs.modules.teller.domain.TellerCashMovement;
import com.finvanta.cbs.modules.teller.domain.VaultPosition;

import java.math.BigDecimal;
import java.util.List;

/**
 * CBS Vault Service Interface per CBS VAULT standard.
 *
 * <p>Manages the branch-level cash safe and cash movements between the
 * vault and teller tills. Per RBI Internal Controls:
 * <ul>
 *   <li>Vault access requires dual control (maker = teller, checker =
 *       vault custodian).</li>
 *   <li>Every movement is recorded with a permanent reference and an
 *       immutable denomination breakdown.</li>
 *   <li>The vault cannot go negative (the branch cannot dispense more
 *       cash than it physically holds in the safe).</li>
 *   <li>Vault↔till movements do NOT touch the GL — they redistribute
 *       cash within the branch's GL BANK_OPERATIONS balance.</li>
 * </ul>
 *
 * <p>The EOD reconciliation invariant:
 * <pre>
 *   SUM(till.currentBalance @ branch X, date D)
 *     + vault.currentBalance(branch X, date D)
 *     == GL BANK_OPERATIONS branch balance(branch X, date D)
 * </pre>
 */
public interface VaultService {

    /**
     * Opens (or retrieves) the vault position for the authenticated user's
     * branch on the current business date. If no vault row exists for today,
     * creates one with the given opening balance. If one already exists,
     * returns it (idempotent for BOD retry).
     *
     * @param openingBalance cash in the safe at start of day
     * @return the vault position in OPEN status
     */
    VaultPosition openVault(BigDecimal openingBalance);

    /**
     * Returns the current vault position for the authenticated user's
     * branch on the current business date, or throws if none exists.
     */
    VaultPosition getMyBranchVault();

    /**
     * Teller requests cash FROM the vault (vault→till). Creates a
     * PENDING movement that the vault custodian must approve before
     * balances move.
     *
     * @param amount the INR amount the teller needs
     * @param remarks optional narration
     * @return the PENDING movement record
     */
    TellerCashMovement requestBuyCash(BigDecimal amount, String remarks);

    /**
     * Teller returns excess cash TO the vault (till→vault). Creates a
     * PENDING movement that the vault custodian must approve before
     * balances move.
     *
     * @param amount the INR amount the teller is returning
     * @param remarks optional narration
     * @return the PENDING movement record
     */
    TellerCashMovement requestSellCash(BigDecimal amount, String remarks);

    /**
     * Vault custodian approves a PENDING movement. On approval:
     * <ul>
     *   <li>BUY: vault.currentBalance decrements, till.currentBalance increments.</li>
     *   <li>SELL: till.currentBalance decrements, vault.currentBalance increments.</li>
     * </ul>
     *
     * <p>Per RBI Internal Controls: the custodian (checker) must NOT be the
     * same user who requested the movement (maker ≠ checker).
     *
     * @param movementId the ID of the PENDING movement to approve
     * @return the APPROVED movement with balances updated
     */
    TellerCashMovement approveMovement(Long movementId);

    /**
     * Vault custodian rejects a PENDING movement. No balance change occurs.
     *
     * @param movementId the ID of the PENDING movement to reject
     * @param reason mandatory rejection reason for audit trail
     * @return the REJECTED movement
     */
    TellerCashMovement rejectMovement(Long movementId, String reason);

    /**
     * Returns PENDING movements at the authenticated user's branch for
     * the current business date. Used by the vault custodian dashboard.
     */
    List<TellerCashMovement> getPendingMovements();

    /**
     * Closes the vault for the current business date after all tills at the
     * branch are CLOSED. The custodian enters a physical count; the system
     * computes the variance and transitions the vault to CLOSED.
     *
     * <p>Per RBI Internal Controls: the vault cannot close while any till
     * at the branch is still OPEN or PENDING_CLOSE. This enforces the
     * ordering: all tellers close first, then the vault custodian reconciles.
     *
     * @param countedBalance the custodian's physical cash count (INR)
     * @param remarks optional narration
     * @return the vault in CLOSED status with variance computed
     */
    VaultPosition closeVault(BigDecimal countedBalance, String remarks);
}

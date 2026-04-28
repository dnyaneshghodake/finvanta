package com.finvanta.cbs.modules.teller.service;

import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;

/**
 * CBS Teller Module Service Interface per CBS TELLER standard.
 *
 * <p>Tier-1 service contract for the over-the-counter cash channel. Per the
 * refactored DDD architecture (see {@code CBS_TIER1_AUDIT_REPORT.md}):
 * <ul>
 *   <li>All methods carry {@code @Transactional} in the implementation.</li>
 *   <li>Cash deposit posting routes through {@code TransactionEngine.execute}
 *       (single enforcement point for GL, idempotency, maker-checker).</li>
 *   <li>Till mutation happens ONLY after the engine returns a non-pending
 *       result -- the till must stay untouched while a workflow is open.</li>
 *   <li>Pessimistic write lock acquired on the till row before any mutation,
 *       mirroring the DepositAccount lock-first pattern this PR established.</li>
 *   <li>Returns domain entities/responses to the controller; the v2 controller
 *       layer is responsible for HTTP / JSON / exception mapping.</li>
 * </ul>
 *
 * <p>Methods only declared here are exposed to controllers. Helpers for vault
 * movements, EOD reconciliation, and till close are intentionally on a
 * separate interface so the cash-deposit flow can be reviewed and tested in
 * isolation.
 */
public interface TellerService {

    /**
     * Opens a till for the authenticated teller on the current branch business
     * date. Creates a {@link TellerTill} in PENDING_OPEN; auto-promotes to OPEN
     * if the opening balance falls within the branch threshold, otherwise
     * routes to a supervisor via maker-checker.
     */
    TellerTill openTill(OpenTillRequest request);

    /**
     * Posts a customer cash deposit at the counter. End-to-end this method:
     * <ol>
     *   <li>Resolves and locks the teller's OPEN till.</li>
     *   <li>Validates denomination sum equals amount; rejects FICN-flagged
     *       deposits with a dedicated error path.</li>
     *   <li>Posts the GL via {@code TransactionEngine.execute} with a
     *       balanced double-entry (DR Cash-in-Hand / CR Customer Deposit).
     *       This step also enforces idempotency, business-date validity, and
     *       the per-user transaction limit / maker-checker gate.</li>
     *   <li>If the engine routes to maker-checker, returns a PENDING_APPROVAL
     *       response with the till and customer balances UNCHANGED.</li>
     *   <li>Otherwise mutates the customer account ledger (reusing the v1
     *       deposit pipeline for consistency), increments the till
     *       {@code currentBalance}, and writes immutable {@link
     *       com.finvanta.cbs.modules.teller.domain.CashDenomination} rows.</li>
     *   <li>Returns the receipt response with full denomination echo.</li>
     * </ol>
     *
     * <p>All steps run inside a single {@code @Transactional} boundary so
     * either the GL post + till mutation + denomination rows commit
     * atomically, or none do.
     */
    CashDepositResponse cashDeposit(CashDepositRequest request);

    /**
     * Returns the till owned by the authenticated teller for the current
     * business date, or throws {@code TELLER_TILL_NOT_OPEN} if none exists.
     * Used by the JSP topbar indicator and by the BFF before rendering the
     * cash-deposit screen.
     */
    TellerTill getMyCurrentTill();
}

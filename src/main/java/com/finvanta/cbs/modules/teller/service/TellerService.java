package com.finvanta.cbs.modules.teller.service;

import com.finvanta.cbs.modules.teller.domain.TellerTill;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.CashWithdrawalRequest;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.dto.response.CashWithdrawalResponse;
import com.finvanta.transaction.TransactionResult;

import java.math.BigDecimal;
import java.time.LocalDate;

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
     * Pays out cash to a customer over the counter. Mirrors
     * {@link #cashDeposit} but on the debit side:
     * <ol>
     *   <li>Locks the customer account (PESSIMISTIC_WRITE) FIRST, then the
     *       till -- same canonical order as deposit so concurrent
     *       deposit + withdrawal on the same account never deadlock.</li>
     *   <li>Validates customer-side: not closed, debits allowed (honoring
     *       partial freezes), sufficient available balance, minimum-balance
     *       not breached, per-account daily withdrawal limit not exceeded.</li>
     *   <li>Validates till-side: till has enough physical cash to pay out
     *       ({@code currentBalance >= amount}) -- {@code CBS-TELLER-006}.</li>
     *   <li>Routes through {@link com.finvanta.transaction.TransactionEngine}
     *       for GL posting (DR customer GL / CR BANK_OPERATIONS), idempotency,
     *       and per-user limit + maker-checker gating.</li>
     *   <li>If engine returns PENDING_APPROVAL: balances UNCHANGED, no
     *       denomination rows. The customer leaves with neither the cash
     *       nor an updated balance; the supervisor approval flow completes
     *       the transaction.</li>
     *   <li>Otherwise: debits customer ledger, DECREMENTS till
     *       {@code currentBalance}, persists immutable {@code direction='OUT'}
     *       {@link com.finvanta.cbs.modules.teller.domain.CashDenomination}
     *       rows representing the physical cash paid out.</li>
     * </ol>
     *
     * <p>Note: there is no FICN gate on withdrawals -- the bank only pays
     * out genuine notes from its till, so {@code counterfeitCount} on a
     * withdrawal request is rejected by Bean Validation upstream.
     */
    CashWithdrawalResponse cashWithdrawal(CashWithdrawalRequest request);

    /**
     * Returns the till owned by the authenticated teller for the current
     * business date, or throws {@code TELLER_TILL_NOT_OPEN} if none exists.
     * Used by the JSP topbar indicator and by the BFF before rendering the
     * cash-deposit screen.
     */
    TellerTill getMyCurrentTill();

    /**
     * Supervisor approves a PENDING_OPEN till, promoting it to OPEN.
     *
     * <p>Per RBI Internal Controls / dual-control requirement: when a till's
     * opening balance exceeds the branch threshold (configured in
     * {@code TellerServiceImpl.TILL_OPEN_AUTO_APPROVE_THRESHOLD}), the till
     * sits in PENDING_OPEN until a supervisor (CHECKER or ADMIN role) signs
     * off. This method performs that sign-off.
     *
     * <p>Validations:
     * <ul>
     *   <li>Till must be in PENDING_OPEN status.</li>
     *   <li>The authenticated principal must NOT be the same user who opened
     *       the till (maker ≠ checker per RBI Internal Controls).</li>
     *   <li>The authenticated principal must have CHECKER or ADMIN role.</li>
     * </ul>
     *
     * @param tillId the ID of the PENDING_OPEN till to approve
     * @return the till in OPEN status with {@code openedBySupervisor} set
     */
    TellerTill approveTillOpen(Long tillId);

    /**
     * Applies the subledger effect of a checker-approved teller cash
     * transaction (deposit or withdrawal) that was routed to maker-checker.
     *
     * <p>Called by {@code WorkflowController.approve()} after
     * {@code TransactionReExecutionService.reExecuteApprovedTransaction()}
     * has posted the GL. This method:
     * <ol>
     *   <li>Locks the customer account and the teller's till.</li>
     *   <li>Applies the balance effect to the customer ledger (credit for
     *       deposit, debit for withdrawal).</li>
     *   <li>Mutates the till {@code currentBalance} (increment for deposit,
     *       decrement for withdrawal).</li>
     *   <li>Persists the immutable {@code CashDenomination} rows that were
     *       deferred at the time the deposit/withdrawal was initially submitted
     *       (denominations are NOT written on the pending path — they are
     *       written here, on approval).</li>
     * </ol>
     *
     * <p>This is the teller-specific equivalent of
     * {@code DepositAccountService.applyApprovedTransaction(...)}.
     *
     * @param accountNumber   the customer account to credit/debit
     * @param amount          the transaction amount
     * @param transactionType "CASH_DEPOSIT" or "CASH_WITHDRAWAL"
     * @param makerUserId     the original teller's username (from
     *                        {@code workflow.getMakerUserId()}); used to resolve the
     *                        teller's till via the unique index on
     *                        {@code (tenantId, tellerUserId, businessDate)}
     * @param result          the GL posting result from the re-execution service
     * @param businessDate    the CBS business date of the original transaction
     */
    void applyApprovedTellerTransaction(
            String accountNumber,
            BigDecimal amount,
            String transactionType,
            String makerUserId,
            TransactionResult result,
            LocalDate businessDate);

    /**
     * Teller submits a till-close request with a physical cash count.
     * The system computes the variance (counted - system balance) and
     * transitions the till to PENDING_CLOSE. A supervisor must sign off
     * (via {@link #approveTillClose}) before the till reaches CLOSED.
     *
     * <p>Per RBI Internal Controls: the teller must sell any excess cash
     * back to the vault BEFORE closing. The till's current balance after
     * all vault movements is the system-side reference; the counted balance
     * is the physical-side reference. Any non-zero variance is flagged.
     *
     * @param countedBalance the teller's physical cash count (INR)
     * @param remarks optional narration
     * @return the till in PENDING_CLOSE status with variance computed
     */
    TellerTill requestCloseTill(BigDecimal countedBalance, String remarks);

    /**
     * Supervisor approves a PENDING_CLOSE till, transitioning it to CLOSED.
     * The supervisor has reviewed the variance (if any) and signed off.
     *
     * <p>Per RBI Internal Controls: maker (teller who counted) ≠ checker
     * (supervisor who signs off). A zero-variance close is routine; a
     * non-zero variance is flagged in the audit trail and may trigger a
     * cash-variance adjustment workflow (out of scope for this commit).
     *
     * @param tillId the ID of the PENDING_CLOSE till to approve
     * @return the till in CLOSED status
     */
    TellerTill approveTillClose(Long tillId);
}

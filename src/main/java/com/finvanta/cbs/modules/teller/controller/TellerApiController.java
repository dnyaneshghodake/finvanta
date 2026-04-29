package com.finvanta.cbs.modules.teller.controller;

import com.finvanta.api.ApiResponse;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.CashWithdrawalRequest;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.dto.response.CashWithdrawalResponse;
import com.finvanta.cbs.modules.teller.dto.response.TellerTillResponse;
import com.finvanta.cbs.modules.teller.mapper.TellerTillMapper;
import com.finvanta.cbs.modules.teller.service.TellerService;
import com.finvanta.cbs.modules.teller.service.VaultService;

import jakarta.validation.Valid;

import java.math.BigDecimal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CBS Teller REST API Controller per CBS TELLER_API standard.
 *
 * <p>Tier-1 v2 controller. Per the refactored architecture:
 * <ul>
 *   <li>ZERO entity imports -- only DTOs cross the API boundary.</li>
 *   <li>ZERO repository imports -- all data access via the service layer.</li>
 *   <li>ZERO {@code @Transactional} -- transaction boundary is in the service.</li>
 *   <li>{@code @PreAuthorize} on every endpoint per RBI RBAC requirements.</li>
 *   <li>Mappers translate entities to response DTOs at the boundary.</li>
 * </ul>
 *
 * <p>CBS Role Matrix for the teller channel:
 * <ul>
 *   <li>TELLER -- open till, cash deposit, view own till.</li>
 *   <li>MAKER  -- alias for TELLER on the deposit endpoint (legacy compatibility).</li>
 *   <li>CHECKER -- approves PENDING_OPEN tills and PENDING_APPROVAL deposits via
 *       the workflow API (NOT exposed on this controller -- they live on
 *       {@code WorkflowApiController}).</li>
 *   <li>ADMIN -- all teller operations + supervisor sign-offs.</li>
 *   <li>AUDITOR -- read-only inquiry (out of scope for this commit).</li>
 * </ul>
 *
 * <p>Layering:
 * <pre>
 *   TellerApiController (this)
 *     -> TellerService (business logic + @Transactional + locking)
 *       -> DenominationValidator
 *       -> TransactionEngine (GL posting + maker-checker)
 *       -> TellerTillRepository
 *       -> CashDenominationRepository
 *       -> DepositAccountRepository
 *     -> TellerTillMapper (entity -> DTO)
 * </pre>
 */
@RestController("cbsTellerApiController")
@RequestMapping("/api/v2/teller")
public class TellerApiController {

    private final TellerService tellerService;
    private final VaultService vaultService;
    private final TellerTillMapper tillMapper;

    public TellerApiController(TellerService tellerService, VaultService vaultService, TellerTillMapper tillMapper) {
        this.tellerService = tellerService;
        this.vaultService = vaultService;
        this.tillMapper = tillMapper;
    }

    // === Till Lifecycle ===

    /**
     * Opens a till for the authenticated teller on the current business
     * date. Auto-promotes to OPEN if the opening balance is within the
     * branch threshold; otherwise creates a PENDING_OPEN entry that must
     * be approved by a supervisor (workflow path, not this controller).
     *
     * <p>Per RBI Internal Controls / segregation of duties: branch and
     * teller-user are derived server-side from the authenticated principal
     * -- the request body cannot specify them.
     */
    @PostMapping("/till/open")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TellerTillResponse>> openTill(
            @Valid @RequestBody OpenTillRequest request) {
        var till = tellerService.openTill(request);
        return ResponseEntity.ok(ApiResponse.success(
                tillMapper.toResponse(till),
                "Till " + till.getStatus()
                        + (till.isOpen() ? " (auto-approved)" : " (awaiting supervisor)")));
    }

    /**
     * Returns the till for the authenticated teller on the current business
     * date. Used by the BFF / JSP topbar indicator and as a pre-render
     * gate for the cash-deposit screen. Returns CBS-TELLER-001 (HTTP 409)
     * if no till is open.
     */
    @GetMapping("/till/me")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<TellerTillResponse>> myCurrentTill() {
        var till = tellerService.getMyCurrentTill();
        return ResponseEntity.ok(ApiResponse.success(tillMapper.toResponse(till)));
    }

    // === Cash Deposit ===

    /**
     * Posts an over-the-counter customer cash deposit. The teller's till is
     * resolved from the authenticated principal -- there is NO {@code tillId}
     * field on the request because the only valid till is the caller's own
     * (per RBI Internal Controls).
     *
     * <p>{@code idempotencyKey} is mandatory on this endpoint (stricter than
     * the v1 {@code /api/v1/accounts/{n}/deposit} where it is optional).
     * Cash deposits cannot tolerate the optional path because a network
     * retry without idempotency would double-post the till AND the GL.
     *
     * <p>HTTP semantics:
     * <ul>
     *   <li>200 OK with {@code pendingApproval=false} -- deposit posted, till
     *       and customer balances mutated.</li>
     *   <li>200 OK with {@code pendingApproval=true} -- deposit routed to
     *       maker-checker; balances unchanged.</li>
     *   <li>422 -- balance/freeze/dormant/insufficient-till errors per
     *       {@code CbsApiExceptionHandler}.</li>
     *   <li>409 -- till not open / dup transaction.</li>
     *   <li>400 -- denomination sum mismatch / counterfeit detected.</li>
     * </ul>
     */
    @PostMapping("/cash-deposit")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CashDepositResponse>> cashDeposit(
            @Valid @RequestBody CashDepositRequest request) {
        var receipt = tellerService.cashDeposit(request);
        String message = receipt.pendingApproval()
                ? "Cash deposit submitted for checker approval"
                : "Cash deposit posted";
        return ResponseEntity.ok(ApiResponse.success(receipt, message));
    }

    // === Cash Withdrawal ===

    /**
     * Pays out cash to a customer at the counter. Mirrors {@code /cash-deposit}
     * but on the debit side; see {@link com.finvanta.cbs.modules.teller.service.TellerService#cashWithdrawal}
     * for the full contract.
     *
     * <p>HTTP semantics:
     * <ul>
     *   <li>200 OK with {@code pendingApproval=false} -- withdrawal posted;
     *       customer ledger debited, till decremented, denominations recorded.</li>
     *   <li>200 OK with {@code pendingApproval=true} -- withdrawal routed to
     *       maker-checker; balances unchanged, customer leaves WITHOUT cash.</li>
     *   <li>422 -- balance / minimum-balance / freeze / dormancy / daily-limit
     *       breach OR till has insufficient cash (CBS-TELLER-006).</li>
     *   <li>409 -- till not open / dup transaction.</li>
     *   <li>400 -- denomination sum mismatch / counterfeit on withdrawal request.</li>
     * </ul>
     */
    @PostMapping("/cash-withdrawal")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<CashWithdrawalResponse>> cashWithdrawal(
            @Valid @RequestBody CashWithdrawalRequest request) {
        var receipt = tellerService.cashWithdrawal(request);
        String message = receipt.pendingApproval()
                ? "Cash withdrawal submitted for checker approval"
                : "Cash withdrawal posted";
        return ResponseEntity.ok(ApiResponse.success(receipt, message));
    }

    // === Till Close ===

    /**
     * Teller submits a till-close request with a physical cash count. The
     * system computes the variance ({@code counted - current}) and transitions
     * the till to PENDING_CLOSE; a supervisor must sign off via
     * {@link #approveTillClose} before the till reaches CLOSED.
     *
     * <p>Per RBI Internal Controls: the teller must sell any excess cash back
     * to the vault BEFORE closing. Any non-zero variance is flagged in the
     * audit trail and may trigger a cash-variance adjustment workflow.
     */
    @PostMapping("/till/close")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TellerTillResponse>> requestCloseTill(
            @RequestParam BigDecimal countedBalance,
            @RequestParam(required = false) String remarks) {
        var till = tellerService.requestCloseTill(countedBalance, remarks);
        String varianceMsg = till.getVarianceAmount() != null && till.getVarianceAmount().signum() != 0
                ? " (variance: INR " + till.getVarianceAmount() + ")"
                : " (zero variance)";
        return ResponseEntity.ok(ApiResponse.success(
                tillMapper.toResponse(till),
                "Till close requested" + varianceMsg + ". Awaiting supervisor sign-off."));
    }

    /**
     * Supervisor approves a PENDING_CLOSE till, transitioning it to CLOSED.
     * Per RBI Internal Controls / dual-control: maker (teller who counted)
     * must not equal checker (supervisor signing off).
     */
    @PostMapping("/till/{tillId}/approve-close")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TellerTillResponse>> approveTillClose(
            @PathVariable Long tillId) {
        var till = tellerService.approveTillClose(tillId);
        return ResponseEntity.ok(ApiResponse.success(
                tillMapper.toResponse(till),
                "Till CLOSED for teller " + till.getTellerUserId()));
    }

    // === Supervisor Approval ===

    /**
     * Supervisor approves a PENDING_OPEN till, promoting it to OPEN.
     *
     * <p>Per RBI Internal Controls / dual-control: the authenticated
     * principal must be a CHECKER or ADMIN, and must NOT be the same user
     * who opened the till (maker ≠ checker).
     *
     * <p>HTTP semantics: 200 OK with the updated till in OPEN status.
     * 409 if the till is not in PENDING_OPEN. 403 if the supervisor
     * is the same user who opened the till (CBS-WF-001).
     */
    @PostMapping("/till/{tillId}/approve")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TellerTillResponse>> approveTillOpen(
            @PathVariable Long tillId) {
        var till = tellerService.approveTillOpen(tillId);
        return ResponseEntity.ok(ApiResponse.success(
                tillMapper.toResponse(till),
                "Till approved and OPEN for teller " + till.getTellerUserId()));
    }

    // === Vault Operations ===

    @PostMapping("/vault/open")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> openVault(
            @RequestParam BigDecimal openingBalance) {
        var vault = vaultService.openVault(openingBalance);
        return ResponseEntity.ok(ApiResponse.success(vault, "Vault opened"));
    }

    @GetMapping("/vault/me")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> myBranchVault() {
        var vault = vaultService.getMyBranchVault();
        return ResponseEntity.ok(ApiResponse.success(vault));
    }

    @PostMapping("/vault/buy")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> requestBuyCash(
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String remarks) {
        var mov = vaultService.requestBuyCash(amount, remarks);
        return ResponseEntity.ok(ApiResponse.success(mov, "Buy cash request submitted (PENDING custodian approval)"));
    }

    @PostMapping("/vault/sell")
    @PreAuthorize("hasAnyRole('TELLER', 'MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> requestSellCash(
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String remarks) {
        var mov = vaultService.requestSellCash(amount, remarks);
        return ResponseEntity.ok(ApiResponse.success(mov, "Sell cash request submitted (PENDING custodian approval)"));
    }

    @PostMapping("/vault/movement/{movementId}/approve")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> approveMovement(@PathVariable Long movementId) {
        var mov = vaultService.approveMovement(movementId);
        return ResponseEntity.ok(ApiResponse.success(mov, "Movement approved; balances updated"));
    }

    @PostMapping("/vault/movement/{movementId}/reject")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> rejectMovement(
            @PathVariable Long movementId,
            @RequestParam String reason) {
        var mov = vaultService.rejectMovement(movementId, reason);
        return ResponseEntity.ok(ApiResponse.success(mov, "Movement rejected"));
    }

    @GetMapping("/vault/movements/pending")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> pendingMovements() {
        var list = vaultService.getPendingMovements();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    @PostMapping("/vault/close")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Object>> closeVault(
            @RequestParam BigDecimal countedBalance,
            @RequestParam(required = false) String remarks) {
        var vault = vaultService.closeVault(countedBalance, remarks);
        return ResponseEntity.ok(ApiResponse.success(vault, "Vault CLOSED"));
    }
}

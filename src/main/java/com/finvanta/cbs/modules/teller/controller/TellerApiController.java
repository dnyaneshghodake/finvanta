package com.finvanta.cbs.modules.teller.controller;

import com.finvanta.api.ApiResponse;
import com.finvanta.cbs.modules.teller.dto.request.CashDepositRequest;
import com.finvanta.cbs.modules.teller.dto.request.OpenTillRequest;
import com.finvanta.cbs.modules.teller.dto.response.CashDepositResponse;
import com.finvanta.cbs.modules.teller.dto.response.TellerTillResponse;
import com.finvanta.cbs.modules.teller.mapper.TellerTillMapper;
import com.finvanta.cbs.modules.teller.service.TellerService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final TellerTillMapper tillMapper;

    public TellerApiController(TellerService tellerService, TellerTillMapper tillMapper) {
        this.tellerService = tellerService;
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
}

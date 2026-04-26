package com.finvanta.cbs.modules.account.controller;

import com.finvanta.api.ApiResponse;
import com.finvanta.cbs.modules.account.dto.request.FinancialRequest;
import com.finvanta.cbs.modules.account.dto.request.OpenAccountRequest;
import com.finvanta.cbs.modules.account.dto.request.TransferRequest;
import com.finvanta.cbs.modules.account.dto.response.AccountResponse;
import com.finvanta.cbs.modules.account.dto.response.BalanceResponse;
import com.finvanta.cbs.modules.account.dto.response.TxnResponse;
import com.finvanta.cbs.modules.account.mapper.AccountMapper;
import com.finvanta.cbs.modules.account.service.DepositAccountModuleService;

import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS CASA REST API Controller per CBS CUSTACCT_API standard.
 *
 * <p>This is a REFACTORED Tier-1 CBS controller demonstrating the target architecture:
 * <ul>
 *   <li>ZERO entity imports -- only DTOs cross the API boundary</li>
 *   <li>ZERO repository imports -- all data access via service layer</li>
 *   <li>ZERO business logic -- pure request/response orchestration</li>
 *   <li>ZERO {@code @Transactional} -- transaction boundaries belong in service</li>
 *   <li>Dedicated mapper for entity-to-DTO conversion with PII masking</li>
 *   <li>{@code @PreAuthorize} on every endpoint per RBI RBAC requirements</li>
 * </ul>
 *
 * <p>CBS Role Matrix:
 * <ul>
 *   <li>MAKER -- deposit, withdraw, transfer, open account</li>
 *   <li>CHECKER -- activate, freeze, unfreeze, close, reverse</li>
 *   <li>ADMIN -- all MAKER + CHECKER operations</li>
 *   <li>AUDITOR -- read-only inquiry (account, balance, statement)</li>
 * </ul>
 *
 * <p>Layering enforcement:
 * <pre>
 *   DepositAccountApiController (this)
 *     -> DepositAccountModuleService (business logic + @Transactional)
 *       -> AccountValidator (business validation)
 *       -> TransactionEngine (GL posting)
 *       -> DepositAccountRepository (data access)
 *     -> AccountMapper (entity -> DTO + PII masking)
 * </pre>
 */
@RestController("cbsDepositAccountApiController")
@RequestMapping("/api/v2/accounts")
public class DepositAccountApiController {

    private final DepositAccountModuleService accountService;
    private final AccountMapper mapper;

    public DepositAccountApiController(
            DepositAccountModuleService accountService,
            AccountMapper mapper) {
        this.accountService = accountService;
        this.mapper = mapper;
    }

    // === Account Lifecycle ===

    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>> openAccount(
            @Valid @RequestBody OpenAccountRequest request) {
        var account = accountService.openAccount(request);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toAccountResponse(account),
                "Account opened in PENDING_ACTIVATION"));
    }

    @PostMapping("/{accountNumber}/activate")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>> activateAccount(
            @PathVariable String accountNumber) {
        var account = accountService.activateAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toAccountResponse(account),
                "Account activated"));
    }

    @PostMapping("/{accountNumber}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>> freezeAccount(
            @PathVariable String accountNumber,
            @RequestParam String freezeType,
            @RequestParam String reason) {
        var account = accountService.freezeAccount(accountNumber, freezeType, reason);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toAccountResponse(account),
                "Account frozen: " + freezeType));
    }

    @PostMapping("/{accountNumber}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>> unfreezeAccount(
            @PathVariable String accountNumber) {
        var account = accountService.unfreezeAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toAccountResponse(account),
                "Account unfrozen"));
    }

    @PostMapping("/{accountNumber}/close")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>> closeAccount(
            @PathVariable String accountNumber,
            @RequestParam String reason) {
        var account = accountService.closeAccount(accountNumber, reason);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toAccountResponse(account),
                "Account closed"));
    }

    @GetMapping("/pipeline")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getPendingAccounts() {
        var pending = accountService.getPendingAccounts();
        var items = pending.stream().map(mapper::toAccountResponse).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Financial Operations ===

    @PostMapping("/{accountNumber}/deposit")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>> deposit(
            @PathVariable String accountNumber,
            @Valid @RequestBody FinancialRequest request) {
        var txn = accountService.deposit(accountNumber, request);
        return ResponseEntity.ok(ApiResponse.success(mapper.toTxnResponse(txn)));
    }

    @PostMapping("/{accountNumber}/withdraw")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>> withdraw(
            @PathVariable String accountNumber,
            @Valid @RequestBody FinancialRequest request) {
        var txn = accountService.withdraw(accountNumber, request);
        return ResponseEntity.ok(ApiResponse.success(mapper.toTxnResponse(txn)));
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>> transfer(
            @Valid @RequestBody TransferRequest request) {
        var txn = accountService.transfer(request);
        return ResponseEntity.ok(ApiResponse.success(mapper.toTxnResponse(txn)));
    }

    @PostMapping("/reversal/{transactionRef}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>> reverseTransaction(
            @PathVariable String transactionRef,
            @RequestParam String reason) {
        var txn = accountService.reverseTransaction(transactionRef, reason);
        return ResponseEntity.ok(ApiResponse.success(
                mapper.toTxnResponse(txn),
                "Transaction reversed"));
    }

    // === Inquiry ===

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<AccountResponse>> getAccount(
            @PathVariable String accountNumber) {
        var account = accountService.getAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(mapper.toAccountResponse(account)));
    }

    @GetMapping("/{accountNumber}/balance")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<BalanceResponse>> getBalance(
            @PathVariable String accountNumber) {
        var account = accountService.getAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(mapper.toBalanceResponse(account)));
    }

    @GetMapping("/{accountNumber}/mini-statement")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<TxnResponse>>> getMiniStatement(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "10") int count) {
        var txns = accountService.getMiniStatement(accountNumber, Math.min(count, 50));
        var items = txns.stream().map(mapper::toTxnResponse).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }
}

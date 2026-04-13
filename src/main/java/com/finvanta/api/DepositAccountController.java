package com.finvanta.api;

import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.DepositTransaction;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS CASA REST API per Finacle CUSTACCT_API / Temenos IRIS Account.
 *
 * Thin orchestration layer over DepositAccountService — no business logic here.
 * All GL posting, maker-checker, branch access reside in service/engine layers.
 *
 * CBS Role Matrix:
 *   MAKER   → deposit, withdraw, transfer, open account
 *   CHECKER → activate, freeze, unfreeze, close, reverse
 *   ADMIN   → all MAKER + CHECKER operations
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class DepositAccountController {

    private final DepositAccountService depositService;
    private final BusinessDateService businessDateService;

    public DepositAccountController(
            DepositAccountService depositService,
            BusinessDateService businessDateService) {
        this.depositService = depositService;
        this.businessDateService = businessDateService;
    }

    // === Account Lifecycle ===

    @PostMapping("/open")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>>
            openAccount(@Valid @RequestBody OpenAccountRequest req) {
        DepositAccount account = depositService.openAccount(
                req.customerId(), req.branchId(), req.accountType(),
                req.productCode() != null ? req.productCode() : req.accountType(),
                req.initialDeposit(), req.nomineeName(), req.nomineeRelationship());
        return ResponseEntity.ok(ApiResponse.success(
                AccountResponse.from(account), "Account opened in PENDING_ACTIVATION"));
    }

    @PostMapping("/{accountNumber}/activate")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>>
            activateAccount(@PathVariable String accountNumber) {
        DepositAccount account = depositService.activateAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(
                AccountResponse.from(account), "Account activated"));
    }

    @PostMapping("/{accountNumber}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>>
            freezeAccount(@PathVariable String accountNumber,
                    @RequestBody FreezeRequest req) {
        DepositAccount account = depositService.freezeAccount(
                accountNumber, req.freezeType(), req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                AccountResponse.from(account), "Account frozen: " + req.freezeType()));
    }

    @PostMapping("/{accountNumber}/unfreeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>>
            unfreezeAccount(@PathVariable String accountNumber) {
        DepositAccount account = depositService.unfreezeAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(
                AccountResponse.from(account), "Account unfrozen"));
    }

    @PostMapping("/{accountNumber}/close")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>>
            closeAccount(@PathVariable String accountNumber,
                    @RequestBody CloseRequest req) {
        DepositAccount account = depositService.closeAccount(accountNumber, req.reason());
        return ResponseEntity.ok(ApiResponse.success(
                AccountResponse.from(account), "Account closed"));
    }

    // === Financial Operations ===

    @PostMapping("/{accountNumber}/deposit")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>>
            deposit(@PathVariable String accountNumber,
                    @Valid @RequestBody FinancialRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        DepositTransaction txn = depositService.deposit(accountNumber, req.amount(), bd,
                req.narration(), req.idempotencyKey(),
                req.channel() != null ? req.channel() : "API");
        return ResponseEntity.ok(ApiResponse.success(TxnResponse.from(txn)));
    }

    @PostMapping("/{accountNumber}/withdraw")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>>
            withdraw(@PathVariable String accountNumber,
                    @Valid @RequestBody FinancialRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        DepositTransaction txn = depositService.withdraw(accountNumber, req.amount(), bd,
                req.narration(), req.idempotencyKey(),
                req.channel() != null ? req.channel() : "API");
        return ResponseEntity.ok(ApiResponse.success(TxnResponse.from(txn)));
    }

    @PostMapping("/transfer")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>>
            transfer(@Valid @RequestBody TransferRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        DepositTransaction txn = depositService.transfer(req.fromAccount(), req.toAccount(),
                req.amount(), bd, req.narration(), req.idempotencyKey());
        return ResponseEntity.ok(ApiResponse.success(TxnResponse.from(txn)));
    }

    @PostMapping("/reversal/{transactionRef}")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TxnResponse>>
            reverseTransaction(@PathVariable String transactionRef,
                    @RequestBody ReversalRequest req) {
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        DepositTransaction txn = depositService.reverseTransaction(
                transactionRef, req.reason(), bd);
        return ResponseEntity.ok(ApiResponse.success(TxnResponse.from(txn), "Transaction reversed"));
    }

    // === Inquiry ===

    @GetMapping("/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>>
            getAccount(@PathVariable String accountNumber) {
        DepositAccount account = depositService.getAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(AccountResponse.from(account)));
    }

    /** Real-time balance inquiry per Finacle BAL_INQ — called by UPI/IMPS. */
    @GetMapping("/{accountNumber}/balance")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<BalanceResponse>>
            getBalance(@PathVariable String accountNumber) {
        DepositAccount a = depositService.getAccount(accountNumber);
        return ResponseEntity.ok(ApiResponse.success(new BalanceResponse(
                a.getAccountNumber(), a.getAccountStatus().name(),
                a.getLedgerBalance(), a.getAvailableBalance(),
                a.getHoldAmount(), a.getUnclearedAmount(),
                a.getOdLimit(), a.getEffectiveAvailable())));
    }

    @GetMapping("/{accountNumber}/mini-statement")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<TxnResponse>>>
            getMiniStatement(@PathVariable String accountNumber,
                    @RequestParam(defaultValue = "10") int count) {
        var txns = depositService.getMiniStatement(accountNumber, Math.min(count, 50));
        var items = txns.stream().map(TxnResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/{accountNumber}/statement")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<StatementResponse>>
            getStatement(@PathVariable String accountNumber,
                    @RequestParam String fromDate, @RequestParam String toDate) {
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        DepositAccount account = depositService.getAccount(accountNumber);
        var txns = depositService.getStatement(accountNumber, from, to);
        var items = txns.stream().map(TxnResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(new StatementResponse(
                accountNumber, account.getAccountType().name(),
                from.toString(), to.toString(),
                account.getLedgerBalance(), account.getAvailableBalance(),
                items.size(), items)));
    }

    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<AccountResponse>>>
            getAccountsByCustomer(@PathVariable Long customerId) {
        var accounts = depositService.getAccountsByCustomer(customerId);
        var items = accounts.stream().map(AccountResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Request DTOs ===

    public record OpenAccountRequest(
            @NotNull Long customerId,
            @NotNull Long branchId,
            @NotBlank String accountType,
            String productCode,
            BigDecimal initialDeposit,
            String nomineeName,
            String nomineeRelationship) {}

    public record FinancialRequest(
            @NotNull @Positive BigDecimal amount,
            String narration,
            String idempotencyKey,
            String channel) {}

    public record TransferRequest(
            @NotBlank String fromAccount,
            @NotBlank String toAccount,
            @NotNull @Positive BigDecimal amount,
            String narration,
            String idempotencyKey) {}

    public record FreezeRequest(String freezeType, String reason) {}

    public record CloseRequest(String reason) {}

    public record ReversalRequest(String reason) {}

    // === Response DTOs (no JPA entity exposure) ===

    public record AccountResponse(
            Long id, String accountNumber, String accountType,
            String productCode, String status, String branchCode,
            String currencyCode, BigDecimal ledgerBalance,
            BigDecimal availableBalance, BigDecimal holdAmount,
            BigDecimal odLimit, BigDecimal interestRate,
            BigDecimal accruedInterest, String openedDate,
            String lastTransactionDate, String nomineeName,
            boolean chequeBookEnabled, boolean debitCardEnabled) {
        static AccountResponse from(DepositAccount a) {
            return new AccountResponse(
                    a.getId(), a.getAccountNumber(),
                    a.getAccountType().name(), a.getProductCode(),
                    a.getAccountStatus().name(),
                    a.getBranch() != null ? a.getBranch().getBranchCode() : null,
                    a.getCurrencyCode(), a.getLedgerBalance(),
                    a.getAvailableBalance(), a.getHoldAmount(),
                    a.getOdLimit(), a.getInterestRate(),
                    a.getAccruedInterest(),
                    a.getOpenedDate() != null ? a.getOpenedDate().toString() : null,
                    a.getLastTransactionDate() != null
                            ? a.getLastTransactionDate().toString() : null,
                    a.getNomineeName(),
                    a.isChequeBookEnabled(), a.isDebitCardEnabled());
        }
    }

    public record BalanceResponse(
            String accountNumber, String status,
            BigDecimal ledgerBalance, BigDecimal availableBalance,
            BigDecimal holdAmount, BigDecimal unclearedAmount,
            BigDecimal odLimit, BigDecimal effectiveAvailable) {}

    public record TxnResponse(
            Long id, String transactionRef, String transactionType,
            String debitCredit, BigDecimal amount, BigDecimal balanceAfter,
            String valueDate, String postingDate, String narration,
            String counterpartyAccount, String channel,
            String voucherNumber, String branchCode, boolean reversed) {
        static TxnResponse from(DepositTransaction t) {
            return new TxnResponse(
                    t.getId(), t.getTransactionRef(),
                    t.getTransactionType(), t.getDebitCredit(),
                    t.getAmount(), t.getBalanceAfter(),
                    t.getValueDate() != null ? t.getValueDate().toString() : null,
                    t.getPostingDate() != null ? t.getPostingDate().toString() : null,
                    t.getNarration(), t.getCounterpartyAccount(),
                    t.getChannel(), t.getVoucherNumber(),
                    t.getBranchCode(), t.isReversed());
        }
    }

    public record StatementResponse(
            String accountNumber, String accountType,
            String fromDate, String toDate,
            BigDecimal ledgerBalance, BigDecimal availableBalance,
            int transactionCount, List<TxnResponse> transactions) {}
}

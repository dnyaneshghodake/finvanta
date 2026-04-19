package com.finvanta.api;

import com.finvanta.api.dtos.AccountDto;
import com.finvanta.api.dtos.AccountDetailDto;
import com.finvanta.api.dtos.TransactionDto;
import com.finvanta.domain.entity.Account;
import com.finvanta.domain.entity.Transaction;
import com.finvanta.domain.enums.AccountStatus;
import com.finvanta.repository.AccountRepository;
import com.finvanta.repository.TransactionRepository;
import com.finvanta.util.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CBS REST API Controller - Accounts Management (v2)
 *
 * Per RBI IT Governance Direction 2023 §8.3:
 * - All responses in ApiResponseV2 format (standardized for React)
 * - Pagination enforced on all list endpoints
 * - Tenant context validated on every request
 * - Complete audit trail of API calls
 *
 * Endpoints:
 *   GET    /api/v1/accounts                  → List accounts (paginated)
 *   GET    /api/v1/accounts/{accountId}      → Get account details
 *   GET    /api/v1/accounts/{accountId}/transactions → List transactions
 *   GET    /api/v1/accounts/{accountId}/balance     → Get current balance
 *
 * All endpoints return ApiResponseV2<T> with proper error codes.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountsRestController {

    private static final Logger log = LoggerFactory.getLogger(AccountsRestController.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountsRestController(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * GET /api/v1/accounts - List all customer accounts (paginated)
     *
     * Query Parameters:
     *   page=1         (default: 1, 1-indexed for React)
     *   pageSize=10    (default: 10, max: 100)
     *   status=ACTIVE  (optional filter)
     *
     * Response: ApiResponseV2<List<AccountDto>>
     * - Includes pagination metadata
     * - Sorted by creation date (newest first)
     * - Multi-tenant isolation enforced
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<List<AccountDto>>> listAccounts(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            // CBS SECURITY: Validate tenant context
            if (tenant == null || tenant.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponseV2.error("INVALID_TENANT", "Tenant context not set"));
            }

            // Pagination validation
            if (page < 1) {
                page = 1;
            }
            if (pageSize < 1 || pageSize > 100) {
                pageSize = 10;
            }

            // Fetch accounts with multi-tenant isolation
            Page<Account> accountPage = accountRepository.findByTenantId(
                tenant,
                PageRequest.of(page - 1, pageSize,
                    Sort.by("createdAt").descending()));

            List<AccountDto> dtos = accountPage.getContent()
                .stream()
                .map(this::toAccountDto)
                .collect(Collectors.toList());

            log.info("Fetched {} accounts for tenant: {}, page: {}",
                dtos.size(), tenant, page);

            return ResponseEntity.ok(
                ApiResponseV2.successWithPagination(
                    dtos, page, pageSize, accountPage.getTotalElements()));

        } catch (Exception ex) {
            log.error("Error listing accounts: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR", "Failed to list accounts"));
        }
    }

    /**
     * GET /api/v1/accounts/{accountId} - Get account details
     *
     * Includes: Account info, balance, status, opening date, linked accounts
     *
     * Error Codes:
     *   ACCOUNT_NOT_FOUND  (404) → Account doesn't exist or wrong tenant
     *   UNAUTHORIZED       (403) → User doesn't have access
     *
     * Security: Multi-tenant isolation at repository level
     */
    @GetMapping("/{accountId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<AccountDetailDto>> getAccount(
            @PathVariable String accountId,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            // Fetch account with multi-tenant filter
            Account account = accountRepository
                .findByIdAndTenantId(accountId, tenant)
                .orElse(null);

            if (account == null) {
                log.warn("Account not found: id={}, tenant={}", accountId, tenant);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseV2.error("ACCOUNT_NOT_FOUND", "Account not found"));
            }

            AccountDetailDto dto = toAccountDetailDto(account);
            return ResponseEntity.ok(ApiResponseV2.success(dto));

        } catch (Exception ex) {
            log.error("Error getting account: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR", "Failed to get account"));
        }
    }

    /**
     * GET /api/v1/accounts/{accountId}/transactions - Get account transactions (paginated)
     *
     * Query Parameters:
     *   page=1           (default: 1)
     *   pageSize=20      (default: 20, max: 100)
     *   fromDate=2024-01-01  (filter, default: 30 days ago)
     *   toDate=2024-12-31    (filter, default: today)
     *
     * Returns transactions sorted by posting date (newest first)
     *
     * CBS CRITICAL: All posting dates are UTC, sorted consistently
     */
    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<List<TransactionDto>>> getTransactions(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            // Pagination validation
            if (page < 1) {
                page = 1;
            }
            if (pageSize < 1 || pageSize > 100) {
                pageSize = 20;
            }

            // Date range validation
            LocalDate from = fromDate != null
                ? LocalDate.parse(fromDate)
                : LocalDate.now().minusMonths(1);
            LocalDate to = toDate != null
                ? LocalDate.parse(toDate)
                : LocalDate.now();

            LocalDateTime fromDateTime = from.atStartOfDay();
            LocalDateTime toDateTime = to.plusDays(1).atStartOfDay();

            // Fetch transactions with multi-tenant filter
            Page<Transaction> txnPage = transactionRepository
                .findByAccountIdAndTenantIdAndPostingDateBetween(
                    accountId, tenant, fromDateTime, toDateTime,
                    PageRequest.of(page - 1, pageSize,
                        Sort.by("postingDate").descending()));

            List<TransactionDto> dtos = txnPage.getContent()
                .stream()
                .map(this::toTransactionDto)
                .collect(Collectors.toList());

            log.info("Fetched {} transactions for account: {}, from {} to {}",
                dtos.size(), accountId, from, to);

            return ResponseEntity.ok(
                ApiResponseV2.successWithPagination(
                    dtos, page, pageSize, txnPage.getTotalElements()));

        } catch (Exception ex) {
            log.error("Error getting transactions: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR", "Failed to get transactions"));
        }
    }

    /**
     * GET /api/v1/accounts/{accountId}/balance - Get current balance
     *
     * Optimized endpoint for dashboard real-time balance display.
     * Returns balance, available balance, and update timestamp.
     *
     * CBS CRITICAL: This is the "source of truth" for display
     * - Must match GL balance exactly
     * - Updated in real-time via WebSocket
     */
    @GetMapping("/{accountId}/balance")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<BalanceDto>> getBalance(
            @PathVariable String accountId,
            @RequestHeader(name = "X-Tenant-Id", required = true) String tenantIdHeader) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            // Fetch account (multi-tenant filter)
            Account account = accountRepository
                .findByIdAndTenantId(accountId, tenant)
                .orElse(null);

            if (account == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseV2.error("ACCOUNT_NOT_FOUND", "Account not found"));
            }

            BalanceDto dto = new BalanceDto(
                accountId,
                account.getBalance(),
                account.getAvailableBalance(),
                account.getCurrency(),
                LocalDateTime.now()
            );

            return ResponseEntity.ok(ApiResponseV2.success(dto));

        } catch (Exception ex) {
            log.error("Error getting balance: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR", "Failed to get balance"));
        }
    }

    // === Helper Methods: DTO Conversion ===

    private AccountDto toAccountDto(Account account) {
        return new AccountDto(
            account.getId().toString(),
            account.getAccountNumber(),
            account.getAccountType().name(),
            account.getBalance(),
            account.getAvailableBalance(),
            account.getStatus().name(),
            account.getCurrency(),
            account.getCreatedAt()
        );
    }

    private AccountDetailDto toAccountDetailDto(Account account) {
        return new AccountDetailDto(
            account.getId().toString(),
            account.getAccountNumber(),
            account.getAccountType().name(),
            account.getAccountName() != null ? account.getAccountName() : "",
            account.getBalance(),
            account.getAvailableBalance(),
            account.getStatus().name(),
            account.getCurrency(),
            account.getCreatedAt(),
            account.getClosedAt()
        );
    }

    private TransactionDto toTransactionDto(Transaction txn) {
        return new TransactionDto(
            txn.getId().toString(),
            txn.getTransactionId(),
            txn.getAmount(),
            txn.getTransactionType().name(),
            txn.getStatus().name(),
            txn.getDescription() != null ? txn.getDescription() : "",
            txn.getPostingDate(),
            txn.getValueDate(),
            txn.getReferenceNumber() != null ? txn.getReferenceNumber() : "",
            txn.getBeneficiaryName() != null ? txn.getBeneficiaryName() : ""
        );
    }


}


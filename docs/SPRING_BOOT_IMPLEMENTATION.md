# SPRING BOOT REACT INTEGRATION - PRACTICAL IMPLEMENTATION
## Ready-to-Apply Code Changes for Finvanta

**Document Version:** 1.0  
**Date:** April 19, 2026  
**Focus:** Adding React UI/UX Request Handling to Existing Spring Boot MVC

---

## STEP-BY-STEP IMPLEMENTATION

### STEP 1: Add WebSocket & CORS Dependencies to pom.xml

```xml
<!-- In the <dependencies> section of your pom.xml, add: -->

<!-- WebSocket for real-time updates -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>

<!-- Messaging template for WebSocket -->
<dependency>
    <groupId>org.springframework.messaging</groupId>
    <artifactId>spring-messaging</artifactId>
</dependency>

<!-- Socket.io support (optional, for better JavaScript compatibility) -->
<dependency>
    <groupId>io.socket</groupId>
    <artifactId>socket.io-server</artifactId>
    <version>4.5.4</version>
</dependency>
```

Then run:
```bash
mvn clean install
```

---

### STEP 2: Create Enhanced API Response with Pagination

**File: `src/main/java/com/finvanta/api/ApiResponseV2.java`**

```java
package com.finvanta.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Enhanced API Response for React Frontend
 * Supports pagination, field validation errors, and tracing
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponseV2<T> {

    private final String status;              // SUCCESS | ERROR | VALIDATION_ERROR
    private final T data;                     // Response payload
    private final String errorCode;           // Error code for frontend
    private final String message;             // User-friendly message
    private final String requestId;           // UUID for tracing
    private final LocalDateTime timestamp;    // Server time
    
    // Pagination (optional)
    private final Integer page;
    private final Integer pageSize;
    private final Long total;
    private final Integer totalPages;
    private final Boolean hasNextPage;
    private final Boolean hasPreviousPage;
    
    // Validation errors
    private final Map<String, String> fieldErrors;

    private ApiResponseV2(
            String status, T data, String errorCode, String message,
            Integer page, Integer pageSize, Long total, Integer totalPages,
            Map<String, String> fieldErrors) {
        this.status = status;
        this.data = data;
        this.errorCode = errorCode;
        this.message = message;
        this.requestId = UUID.randomUUID().toString();
        this.timestamp = LocalDateTime.now();
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.totalPages = totalPages;
        this.hasNextPage = page != null && pageSize != null && total != null
            ? (page * pageSize) < total : null;
        this.hasPreviousPage = page != null ? page > 1 : null;
        this.fieldErrors = fieldErrors;
    }

    // === Builders ===

    public static <T> ApiResponseV2<T> success(T data) {
        return new ApiResponseV2<>("SUCCESS", data, null, null, 
            null, null, null, null, null);
    }

    public static <T> ApiResponseV2<T> success(T data, String message) {
        return new ApiResponseV2<>("SUCCESS", data, null, message,
            null, null, null, null, null);
    }

    public static <T> ApiResponseV2<T> successWithPagination(
            T data, Integer page, Integer pageSize, Long total) {
        int totalPages = (int) Math.ceil((double) total / pageSize);
        return new ApiResponseV2<>("SUCCESS", data, null, null,
            page, pageSize, total, totalPages, null);
    }

    public static <T> ApiResponseV2<T> error(String errorCode, String message) {
        return new ApiResponseV2<>("ERROR", null, errorCode, message,
            null, null, null, null, null);
    }

    public static <T> ApiResponseV2<T> validationError(Map<String, String> fieldErrors) {
        return new ApiResponseV2<>("VALIDATION_ERROR", null, "VALIDATION_FAILED",
            "Validation failed", null, null, null, null, fieldErrors);
    }

    // === Getters ===
    public String getStatus() { return status; }
    public T getData() { return data; }
    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public String getRequestId() { return requestId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Integer getPage() { return page; }
    public Integer getPageSize() { return pageSize; }
    public Long getTotal() { return total; }
    public Integer getTotalPages() { return totalPages; }
    public Boolean getHasNextPage() { return hasNextPage; }
    public Boolean getHasPreviousPage() { return hasPreviousPage; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
```

---

### STEP 3: Create WebSocket Configuration

**File: `src/main/java/com/finvanta/config/WebSocketConfigurer.java`**

```java
package com.finvanta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket configuration for real-time updates to React frontend
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfigurer implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple broker for /topic destination
        config.enableSimpleBroker("/topic", "/queue");
        
        // Application destination prefix
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/cbs")
            .setAllowedOrigins("http://localhost:3000", "https://cbs.example.com")
            .withSockJS();
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = 
            new ServletServerContainerFactoryBean();
        container.setMaxTextMessageSize(8192);
        container.setMaxBinaryMessageSize(8192);
        return container;
    }
}
```

---

### STEP 4: Create Realtime Update Service

**File: `src/main/java/com/finvanta/service/RealtimeUpdateService.java`**

```java
package com.finvanta.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.finvanta.domain.entity.Account;
import com.finvanta.domain.entity.Transaction;

/**
 * Publish real-time updates to React clients via WebSocket
 */
@Service
public class RealtimeUpdateService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeUpdateService.class);
    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Publish balance update when transfer completes or interest posts
     */
    public void publishBalanceUpdate(
            String accountId,
            BigDecimal newBalance,
            BigDecimal availableBalance,
            String reason) {
        
        try {
            BalanceUpdate update = new BalanceUpdate(
                accountId,
                newBalance,
                availableBalance,
                reason,
                LocalDateTime.now()
            );

            messagingTemplate.convertAndSend(
                "/topic/accounts/" + accountId + "/balance",
                update);

            log.debug("Published balance update for account: {}", accountId);
        } catch (Exception ex) {
            log.error("Failed to publish balance update: {}", ex.getMessage());
            // Don't fail the transaction if WebSocket fails
        }
    }

    /**
     * Publish transaction posted event
     */
    public void publishTransactionPosted(
            String accountId,
            Transaction transaction) {
        
        try {
            TransactionUpdate update = new TransactionUpdate(
                transaction.getId(),
                transaction.getTransactionId(),
                transaction.getAmount(),
                transaction.getTransactionType().name(),
                transaction.getStatus().name(),
                transaction.getDescription(),
                transaction.getPostingDate(),
                transaction.getValueDate()
            );

            messagingTemplate.convertAndSend(
                "/topic/accounts/" + accountId + "/transactions",
                update);

            log.debug("Published transaction event for account: {}", accountId);
        } catch (Exception ex) {
            log.error("Failed to publish transaction: {}", ex.getMessage());
        }
    }

    /**
     * Publish loan status change
     */
    public void publishLoanStatusChange(
            String loanId,
            String oldStatus,
            String newStatus) {
        
        try {
            LoanStatusUpdate update = new LoanStatusUpdate(
                loanId,
                oldStatus,
                newStatus,
                LocalDateTime.now()
            );

            messagingTemplate.convertAndSend(
                "/topic/loans/" + loanId + "/status",
                update);

            log.debug("Published loan status update: {} -> {}", oldStatus, newStatus);
        } catch (Exception ex) {
            log.error("Failed to publish loan status: {}", ex.getMessage());
        }
    }

    // === DTOs ===

    public record BalanceUpdate(
            String accountId,
            BigDecimal balance,
            BigDecimal availableBalance,
            String reason,
            LocalDateTime timestamp) {}

    public record TransactionUpdate(
            String id,
            String transactionId,
            BigDecimal amount,
            String type,
            String status,
            String description,
            LocalDateTime postingDate,
            LocalDateTime valueDate) {}

    public record LoanStatusUpdate(
            String loanId,
            String oldStatus,
            String newStatus,
            LocalDateTime timestamp) {}
}
```

---

### STEP 5: Update AuthController to Return User Info

**File: `src/main/java/com/finvanta/api/AuthController.java` (MODIFICATIONS)**

Add this method to your existing AuthController class:

```java
// Add these DTOs to the end of AuthController.java

/**
 * Enhanced token response for React frontend
 */
public record TokenResponseWithUser(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        long refreshExpiresIn,
        UserInfoDto user) {}

public record UserInfoDto(
        String id,
        String email,
        String firstName,
        String lastName,
        String role,
        java.util.List<String> permissions,
        String branchCode,
        LocalDateTime lastLogin) {}

/**
 * Modified authenticate method to return user info
 */
@PostMapping("/token")
public ResponseEntity<ApiResponseV2<TokenResponseWithUser>>
        authenticateV2(
                @Valid @RequestBody TokenRequest req) {
    String tenantId = TenantContext.getCurrentTenant();

    AppUser user = userRepository
            .findByTenantIdAndUsername(tenantId,
                    req.username())
            .orElse(null);

    if (user == null) {
        log.warn("API auth failed: user not found: {}",
                req.username());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponseV2.error(
                        "AUTH_FAILED",
                        "Invalid credentials"));
    }

    // CBS: Check account status before password
    if (!user.isActive()) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponseV2.error(
                        "ACCOUNT_DISABLED",
                        "Account is disabled"));
    }
    if (user.isLocked()
            && !user.isAutoUnlockEligible()) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponseV2.error(
                        "ACCOUNT_LOCKED",
                        "Account locked. Try after "
                                + AppUser
                                .LOCKOUT_DURATION_MINUTES
                                + " minutes"));
    }

    // CBS: Auto-unlock if eligible
    if (user.isLocked()
            && user.isAutoUnlockEligible()) {
        user.resetLoginAttempts();
        userRepository.save(user);
    }

    // CBS: Validate password
    if (!passwordEncoder.matches(
            req.password(), user.getPasswordHash())) {
        boolean locked = user.recordFailedLogin();
        userRepository.save(user);
        log.warn("API auth failed: bad password: "
                + "user={}, attempts={}, locked={}",
                req.username(),
                user.getFailedLoginAttempts(),
                locked);
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponseV2.error(
                        "AUTH_FAILED",
                        "Invalid credentials"));
    }

    // CBS: Check password expiry
    if (user.isPasswordExpired()) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponseV2.error(
                        "PASSWORD_EXPIRED",
                        "Password expired. Change via "
                                + "UI before API access"));
    }

    // CBS: Generate tokens
    String role = user.getRole().name();
    String branchCode = user.getBranch() != null
            ? user.getBranch().getBranchCode() : null;

    String accessToken =
            jwtTokenService.generateAccessToken(
                    user.getUsername(), tenantId,
                    role, branchCode);
    JwtTokenService.RefreshTokenIssue refresh =
            jwtTokenService.generateRefreshToken(
                    user.getUsername(), tenantId);

    // Record successful auth
    user.recordSuccessfulLogin("API");
    userRepository.save(user);

    log.info("API token issued: user={}, role={}, "
            + "branch={}, refreshJti={}",
            user.getUsername(), role, branchCode,
            refresh.jti());

    // Build user info for React
    UserInfoDto userInfo = new UserInfoDto(
        user.getId().toString(),
        user.getUsername(),
        user.getFirstName() != null ? user.getFirstName() : "",
        user.getLastName() != null ? user.getLastName() : "",
        role,
        user.getPermissions() != null 
            ? user.getPermissions() 
            : new java.util.ArrayList<>(),
        branchCode,
        user.getLastLogin()
    );

    return ResponseEntity.ok(ApiResponseV2.success(
            new TokenResponseWithUser(
                    accessToken, refresh.token(),
                    "Bearer",
                    jwtTokenService
                            .parseToken(accessToken)
                            .getExpiration()
                            .getTime()
                            / 1000,
                    refresh.expiresIn(),
                    userInfo)));
}
```

---

### STEP 6: Create Customer Account API for React

**File: `src/main/java/com/finvanta/api/AccountsApiControllerV2.java`** (NEW)

```java
package com.finvanta.api;

import com.finvanta.domain.entity.Account;
import com.finvanta.domain.entity.Transaction;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API for account operations - optimized for React frontend
 * All endpoints return ApiResponseV2 with proper pagination support
 */
@RestController
@RequestMapping("/api/v1/accounts")
@CrossOrigin(origins = {"http://localhost:3000", "https://cbs.example.com"})
public class AccountsApiControllerV2 {

    private static final Logger log = LoggerFactory.getLogger(AccountsApiControllerV2.class);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AccountsApiControllerV2(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * GET /api/v1/accounts - List all customer accounts (paginated)
     * 
     * Query params:
     *   page=1 (default 1)
     *   pageSize=10 (default 10)
     *   sortBy=balance (optional)
     *   status=ACTIVE (optional filter)
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<List<AccountDto>>> listAccounts(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            // Validate pagination
            if (page < 1) page = 1;
            if (pageSize < 1 || pageSize > 100) pageSize = 10;

            // Fetch accounts with pagination
            Page<Account> accountPage = accountRepository.findByTenantId(
                tenant,
                PageRequest.of(page - 1, pageSize,
                    Sort.by("createdAt").descending()));

            List<AccountDto> dtos = accountPage.getContent()
                .stream()
                .map(this::toAccountDto)
                .collect(Collectors.toList());

            log.info("Listed {} accounts for tenant: {}", dtos.size(), tenant);

            return ResponseEntity.ok(
                ApiResponseV2.successWithPagination(
                    dtos, page, pageSize, accountPage.getTotalElements()));
        } catch (Exception ex) {
            log.error("Error listing accounts: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR",
                    "Failed to list accounts"));
        }
    }

    /**
     * GET /api/v1/accounts/{accountId} - Get account details
     */
    @GetMapping("/{accountId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<AccountDetailDto>> getAccount(
            @PathVariable String accountId,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            Account account = accountRepository
                .findByIdAndTenantId(accountId, tenant)
                .orElse(null);

            if (account == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseV2.error("ACCOUNT_NOT_FOUND",
                        "Account not found"));
            }

            AccountDetailDto dto = toAccountDetailDto(account);
            return ResponseEntity.ok(ApiResponseV2.success(dto));
        } catch (Exception ex) {
            log.error("Error getting account: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR",
                    "Failed to get account"));
        }
    }

    /**
     * GET /api/v1/accounts/{accountId}/transactions - Get account transactions
     * 
     * Query params:
     *   page=1
     *   pageSize=20
     *   fromDate=2024-01-01 (filter)
     *   toDate=2024-12-31 (filter)
     */
    @GetMapping("/{accountId}/transactions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<List<TransactionDto>>> getTransactions(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            // Validate pagination
            if (page < 1) page = 1;
            if (pageSize < 1 || pageSize > 100) pageSize = 20;

            // Parse date filters
            LocalDate from = fromDate != null
                ? LocalDate.parse(fromDate)
                : LocalDate.now().minusMonths(1);
            LocalDate to = toDate != null
                ? LocalDate.parse(toDate)
                : LocalDate.now();

            LocalDateTime fromDateTime = from.atStartOfDay();
            LocalDateTime toDateTime = to.plusDays(1).atStartOfDay();

            // Fetch transactions
            Page<Transaction> txnPage = transactionRepository
                .findByAccountIdAndTenantIdAndPostingDateBetween(
                    accountId, tenant, fromDateTime, toDateTime,
                    PageRequest.of(page - 1, pageSize,
                        Sort.by("postingDate").descending()));

            List<TransactionDto> dtos = txnPage.getContent()
                .stream()
                .map(this::toTransactionDto)
                .collect(Collectors.toList());

            log.info("Retrieved {} transactions for account: {}", 
                dtos.size(), accountId);

            return ResponseEntity.ok(
                ApiResponseV2.successWithPagination(
                    dtos, page, pageSize, txnPage.getTotalElements()));
        } catch (Exception ex) {
            log.error("Error getting transactions: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR",
                    "Failed to get transactions"));
        }
    }

    /**
     * GET /api/v1/accounts/{accountId}/balance - Get current balance
     * Optimized endpoint for real-time balance display
     */
    @GetMapping("/{accountId}/balance")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseV2<BalanceDto>> getBalance(
            @PathVariable String accountId,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        try {
            String tenant = TenantContext.getCurrentTenant();

            Account account = accountRepository
                .findByIdAndTenantId(accountId, tenant)
                .orElse(null);

            if (account == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponseV2.error("ACCOUNT_NOT_FOUND",
                        "Account not found"));
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
            log.error("Error getting balance: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseV2.error("INTERNAL_ERROR",
                    "Failed to get balance"));
        }
    }

    // === Helper Methods to Convert Entities to DTOs ===

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
            txn.getReferenceNumber() != null ? txn.getReferenceNumber() : ""
        );
    }

    // === Response DTOs ===

    public record AccountDto(
            String id,
            String accountNumber,
            String accountType,
            BigDecimal balance,
            BigDecimal availableBalance,
            String status,
            String currency,
            LocalDateTime createdAt) {}

    public record AccountDetailDto(
            String id,
            String accountNumber,
            String accountType,
            String accountName,
            BigDecimal balance,
            BigDecimal availableBalance,
            String status,
            String currency,
            LocalDateTime createdAt,
            LocalDateTime closedAt) {}

    public record TransactionDto(
            String id,
            String transactionId,
            BigDecimal amount,
            String type,
            String status,
            String description,
            LocalDateTime postingDate,
            LocalDateTime valueDate,
            String referenceNumber) {}

    public record BalanceDto(
            String accountId,
            BigDecimal balance,
            BigDecimal availableBalance,
            String currency,
            LocalDateTime timestamp) {}
}
```

---

### STEP 7: Application Properties for React

**Update: `src/main/resources/application.properties`**

```properties
# Add CORS configuration
server.servlet.context-path=/api
server.port=8080

# WebSocket
spring.websocket.servlet.path=/ws

# Jackson configuration (important for React)
spring.jackson.serialization.write-dates-as-timestamps=false
spring.jackson.default-property-inclusion=non_null
spring.jackson.time-zone=UTC

# CORS origins
app.cors.allowed-origins=http://localhost:3000,https://cbs.example.com
app.cors.allowed-methods=GET,POST,PUT,DELETE,OPTIONS,PATCH
app.cors.allowed-headers=*
app.cors.exposed-headers=Authorization,X-Request-ID,X-Total-Count,X-Total-Pages

# API versioning
app.api.version=v1
```

---

### STEP 8: Update TransactionEngine to Publish Real-time Events

**File: Update existing `src/main/java/com/finvanta/accounting/TransactionEngine.java`**

Add these imports and modifications:

```java
import com.finvanta.service.RealtimeUpdateService;

// In the TransactionEngine class, add:
private final RealtimeUpdateService realtimeUpdateService;

// In constructor, add realtimeUpdateService parameter:
public TransactionEngine(
        // ... existing parameters ...
        RealtimeUpdateService realtimeUpdateService) {
    // ... existing assignments ...
    this.realtimeUpdateService = realtimeUpdateService;
}

// In the execute() method, after GL posting, add:
/**
 * Publish real-time updates to React clients
 */
private void publishTransactionUpdate(Transaction transaction) {
    try {
        // Publish to from account (debit side)
        Account fromAccount = accountRepository.findById(
            transaction.getFromAccountId()).orElse(null);
        if (fromAccount != null) {
            realtimeUpdateService.publishTransactionPosted(
                fromAccount.getId().toString(),
                transaction);
            realtimeUpdateService.publishBalanceUpdate(
                fromAccount.getId().toString(),
                fromAccount.getBalance(),
                fromAccount.getAvailableBalance(),
                "TRANSACTION_POSTED");
        }

        // Publish to to account (credit side)
        Account toAccount = accountRepository.findById(
            transaction.getToAccountId()).orElse(null);
        if (toAccount != null) {
            realtimeUpdateService.publishTransactionPosted(
                toAccount.getId().toString(),
                transaction);
            realtimeUpdateService.publishBalanceUpdate(
                toAccount.getId().toString(),
                toAccount.getBalance(),
                toAccount.getAvailableBalance(),
                "TRANSACTION_POSTED");
        }
    } catch (Exception ex) {
        // Log but don't fail the transaction
        log.error("Failed to publish real-time updates: {}", ex.getMessage());
    }
}
```

---

## REACT FRONTEND - USAGE EXAMPLES

### Login Component (React)

```typescript
// pages/login.tsx
import { useForm } from 'react-hook-form';
import { AuthService, LoginCredentials } from '@/services/api/authService';
import { useAuthStore } from '@/store/authStore';
import { useRouter } from 'next/router';

export default function LoginPage() {
  const router = useRouter();
  const { register, handleSubmit, formState: { errors } } = useForm<LoginCredentials>();
  const { setUser, setToken } = useAuthStore();

  const onSubmit = async (data: LoginCredentials) => {
    try {
      const response = await AuthService.login({
        ...data,
        tenantId: 'bank-001' // or get from env
      });

      // Store tokens
      localStorage.setItem('accessToken', response.accessToken);
      localStorage.setItem('refreshToken', response.refreshToken);

      // Store user info
      setUser(response.user);

      // Redirect to dashboard
      router.push('/dashboard');
    } catch (error) {
      // Show error
    }
  };

  return (
    <form onSubmit={handleSubmit(onSubmit)}>
      <input {...register('email', { required: true })} placeholder="Email" />
      {errors.email && <span>Email is required</span>}
      
      <input
        {...register('password', { required: true })}
        type="password"
        placeholder="Password"
      />
      {errors.password && <span>Password is required</span>}

      <button type="submit">Login</button>
    </form>
  );
}
```

### Accounts List Component (React)

```typescript
// components/accounts/AccountsList.tsx
import { useEffect, useState } from 'react';
import { useApi } from '@/hooks/useApi';
import { Account } from '@/types/entities';

export const AccountsList = () => {
  const [page, setPage] = useState(1);
  const { data: response, loading } = useApi<any>(
    `/accounts?page=${page}&pageSize=10`,
    {}
  );

  if (loading) return <div>Loading accounts...</div>;

  const accounts: Account[] = response?.data || [];
  const { total, totalPages } = response || {};

  return (
    <div>
      <h2>My Accounts</h2>
      {accounts.map(account => (
        <div key={account.id} className="account-card">
          <h3>{account.accountNumber}</h3>
          <p>Balance: ₹{account.balance?.toLocaleString()}</p>
          <p>Type: {account.accountType}</p>
  </div>
      ))}
      
      {/* Pagination */}
      <div className="pagination">
        <button disabled={page === 1} onClick={() => setPage(page - 1)}>
          Previous
        </button>
        <span>Page {page} of {totalPages}</span>
        <button disabled={page === totalPages} onClick={() => setPage(page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
};
```

---

## VERIFICATION CHECKLIST

After implementing the above changes:

- [ ] Maven build successful: `mvn clean install`
- [ ] Spring Boot starts: `mvn spring-boot:run`
- [ ] CORS requests work: `curl -X OPTIONS http://localhost:8080/api/v1/accounts -H "Origin: http://localhost:3000"`
- [ ] Auth endpoint returns user info: `POST /api/v1/auth/token`
- [ ] Accounts endpoint has pagination: `GET /api/v1/accounts?page=1&pageSize=10`
- [ ] WebSocket connects: React connects to `ws://localhost:8080/ws/cbs`
- [ ] Balance updates publish: Check browser console for WebSocket messages
- [ ] React frontend can login
- [ ] React frontend can list accounts
- [ ] React frontend can view transactions

---

## NEXT STEPS

1. **Connect React Frontend** - Use the API integration guide
2. **Create Transfer Endpoints** - Similar pattern to accounts
3. **Add Loan Management Endpoints** - Similar pattern
4. **Deploy to Production** - Use Docker + Kubernetes
5. **Monitor Real-time Updates** - Test WebSocket with multiple clients

See: `REACT_NEXTJS_API_INTEGRATION.md` for React side of implementation.


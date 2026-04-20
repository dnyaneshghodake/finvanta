# Tier-1 Grade CBS: RESTful API Design Guidelines

## Table of Contents
1. [API Design Principles](#api-design-principles)
2. [URL Design & Naming](#url-design--naming)
3. [HTTP Methods & Status Codes](#http-methods--status-codes)
4. [Request/Response Formats](#requestresponse-formats)
5. [Authentication & Authorization](#authentication--authorization)
6. [Error Handling in APIs](#error-handling-in-apis)
7. [API Versioning](#api-versioning)
8. [Pagination & Filtering](#pagination--filtering)
9. [Rate Limiting](#rate-limiting)
10. [API Documentation with OpenAPI/Swagger](#api-documentation-with-openapii-swagger)

---

## API Design Principles

### Core Principles

1. **RESTful**: Follow REST principles - resources oriented, stateless
2. **Consistency**: Consistent naming, patterns, and response formats across all APIs
3. **Simplicity**: Easy to understand and use
4. **Security**: Authentication, authorization, input validation
5. **Performance**: Efficient, caching strategies, pagination
6. **Versioning**: Support multiple API versions for backward compatibility
7. **Documentation**: Self-documenting with clear specifications
8. **Error Handling**: Clear, consistent error messages and codes

### Resource-Oriented Design

```
APIs should model resources, not actions:

✅ GOOD:        ❌ BAD:
/customers      /getCustomer
/customers/123  /customer/123/getDetails
/accounts       /listAccounts
/loans/456      /deleteLoan?id=456
/transactions   /executeTransaction
```

---

## URL Design & Naming

### URL Naming Convention

```
Base URL: https://api.bank.com/api/v1

Format: /api/{version}/{resource}/{resourceId}/{subresource}/{subresourceId}

Examples:
GET    /api/v1/customers                           # List all customers
POST   /api/v1/customers                           # Create customer
GET    /api/v1/customers/CUST0001                  # Get specific customer
PUT    /api/v1/customers/CUST0001                  # Update customer
DELETE /api/v1/customers/CUST0001                  # Delete customer
GET    /api/v1/customers/CUST0001/accounts        # Get customer's accounts
POST   /api/v1/customers/CUST0001/accounts        # Create account for customer
GET    /api/v1/accounts/ACC001/transactions       # Get account transactions
POST   /api/v1/accounts/ACC001/transfer           # Execute transfer (action)
GET    /api/v1/reports/statement                  # Generate statement
```

### URL Design Rules

| Rule | Description | Example |
|------|-------------|---------|
| **Use nouns** | URLs represent resources, not actions | `/customers` NOT `/getCustomers` |
| **Use lowercase** | All lowercase for consistency | `/api/v1/customers` NOT `/api/v1/Customers` |
| **Use hyphens** | Separate words with hyphens | `/customer-accounts` NOT `/customerAccounts` |
| **Use plural** | Pluralize resource names | `/customers` NOT `/customer` |
| **Depth limit** | Maximum 3 levels deep | `/api/v1/resources/id/subresources` |
| **Use IDs** | Use resource IDs, not names | `/customers/123` NOT `/customers/john-doe` |
| **Use query params** | Filters, sorting, pagination use query params | `?status=active&sort=-date&page=1` |

---

## HTTP Methods & Status Codes

### HTTP Methods

| Method | Usage | Idempotent | Safe | Example |
|--------|-------|-----------|------|---------|
| **GET** | Retrieve resource(s) | Yes | Yes | `GET /customers/123` |
| **POST** | Create new resource | No | No | `POST /customers` |
| **PUT** | Replace entire resource | Yes | No | `PUT /customers/123` |
| **PATCH** | Partial update | No | No | `PATCH /customers/123` |
| **DELETE** | Delete resource | Yes | No | `DELETE /customers/123` |
| **HEAD** | Like GET but no body | Yes | Yes | `HEAD /customers/123` |
| **OPTIONS** | Get allowed methods | Yes | Yes | `OPTIONS /customers` |

### HTTP Status Codes

#### Success Codes

| Code | Message | Use Case |
|------|---------|----------|
| **200** | OK | GET, PUT, PATCH successful |
| **201** | Created | POST successful, resource created |
| **202** | Accepted | Async operation accepted |
| **204** | No Content | DELETE successful, DELETE, no response body |
| **206** | Partial Content | Range request for pagination |

#### Client Error Codes

| Code | Message | Use Case |
|------|---------|----------|
| **400** | Bad Request | Invalid request format, validation errors |
| **401** | Unauthorized | Authentication failed, missing credentials |
| **403** | Forbidden | Authenticated but no permission |
| **404** | Not Found | Resource doesn't exist |
| **409** | Conflict | Resource conflict (duplicate, state conflict) |
| **422** | Unprocessable Entity | Valid format but semantic errors |
| **429** | Too Many Requests | Rate limit exceeded |

#### Server Error Codes

| Code | Message | Use Case |
|------|---------|----------|
| **500** | Internal Server Error | Unexpected server error |
| **502** | Bad Gateway | External service unavailable |
| **503** | Service Unavailable | Server temporarily unavailable |
| **504** | Gateway Timeout | External service timeout |

---

## Request/Response Formats

### Request Format

#### JSON Request Example

```json
{
  "name": "John Doe",
  "email": "john.doe@example.com",
  "phoneNumber": "+1234567890",
  "dateOfBirth": "1990-05-15",
  "address": {
    "streetAddress": "123 Main Street",
    "city": "New York",
    "state": "NY",
    "postalCode": "10001",
    "country": "USA"
  },
  "preferences": {
    "emailNotifications": true,
    "smsNotifications": false
  }
}
```

#### Request Headers

```http
POST /api/v1/customers HTTP/1.1
Host: api.bank.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0...
Content-Type: application/json
Accept: application/json
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000
X-Client-Version: 1.0.0
User-Agent: CustomBankApp/1.0
```

### Response Format

#### Success Response (200/201)

```json
{
  "status": "SUCCESS",
  "message": "Customer created successfully",
  "data": {
    "customerId": "CUST001023",
    "name": "John Doe",
    "email": "john.doe@example.com",
    "status": "ACTIVE",
    "kycStatus": "VERIFIED",
    "createdDate": "2024-01-15T10:30:00Z"
  },
  "timestamp": "2024-01-15T10:30:00Z",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### Error Response (400/500)

```json
{
  "status": "ERROR",
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "errors": [
    {
      "field": "email",
      "message": "Email must be valid",
      "rejectedValue": "invalid-email"
    },
    {
      "field": "phoneNumber",
      "message": "Phone number must match pattern",
      "rejectedValue": "123"
    }
  ],
  "timestamp": "2024-01-15T10:30:00Z",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "path": "/api/v1/customers"
}
```

#### Paginated Response

```json
{
  "status": "SUCCESS",
  "data": {
    "content": [
      {
        "customerId": "CUST001",
        "name": "John Doe",
        "email": "john@example.com",
        "status": "ACTIVE"
      },
      {
        "customerId": "CUST002",
        "name": "Jane Smith",
        "email": "jane@example.com",
        "status": "ACTIVE"
      }
    ],
    "totalElements": 50,
    "totalPages": 5,
    "pageNumber": 0,
    "pageSize": 10,
    "hasNext": true,
    "hasPrevious": false
  },
  "timestamp": "2024-01-15T10:30:00Z",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### Response Headers

```http
HTTP/1.1 201 Created
Content-Type: application/json; charset=utf-8
X-Request-ID: 550e8400-e29b-41d4-a716-446655440000
X-Response-Time: 125ms
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 999
X-RateLimit-Reset: 1705317000
Cache-Control: max-age=300, public
ETag: "33a64df551425fcc55e4d42a148795d9f25f89d4"
```

---

## Authentication & Authorization

### JWT Authentication Flow

```
1. Client sends credentials to /auth/login endpoint
2. Server validates credentials
3. Server generates JWT token (access + refresh)
4. Client stores tokens securely
5. Client includes token in Authorization header for subsequent requests
6. Server validates token on each request
7. If token expired, client uses refresh token to get new access token
```

### JWT Token Structure

```
Header.Payload.Signature

{
  "alg": "HS256",
  "typ": "JWT"
}
{
  "sub": "123456",          // User ID
  "exp": 1705403400,        // Expiration time
  "iat": 1705317000,        // Issued at
  "userId": "CUST001",
  "roles": ["CUSTOMER"],
  "permissions": ["VIEW_ACCOUNT", "TRANSFER_FUNDS"]
}
```

### Authorization Annotations

```java
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    
    /**
     * Requires any authenticated user
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AccountResponse>>> getAccounts() {
        // Implementation
    }
    
    /**
     * Requires specific role
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<AccountResponse>> createAccount(
            @RequestBody AccountCreateRequest request) {
        // Implementation
    }
    
    /**
     * Requires specific permission
     */
    @DeleteMapping("/{accountId}")
    @PreAuthorize("hasPermission(#accountId, 'DELETE')")
    public void deleteAccount(@PathVariable String accountId) {
        // Implementation
    }
    
    /**
     * Custom authorization
     */
    @PutMapping("/{accountId}")
    @PreAuthorize("@accountService.isOwner(#accountId, principal)")
    public ResponseEntity<ApiResponse<AccountResponse>> updateAccount(
            @PathVariable String accountId,
            @RequestBody AccountUpdateRequest request) {
        // Implementation
    }
}
```

### API Key Authentication

```java
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        String apiKey = request.getHeader("X-API-Key");
        
        if (apiKey != null && validateApiKey(apiKey)) {
            ApiKeyAuthenticationToken auth = new ApiKeyAuthenticationToken(apiKey);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        filterChain.doFilter(request, response);
    }
    
    private boolean validateApiKey(String apiKey) {
        // Validate API key against database/cache
        return true;
    }
}
```

---

## Error Handling in APIs

### Error Response Structure

```java
@Data
@Builder
public class ApiErrorResponse {
    private String timestamp;
    private String errorCode;        // Machine-readable error code
    private String errorMessage;     // User-friendly message
    private String details;          // Technical details for debugging
    private String path;             // API path that caused error
    private int status;              // HTTP status code
    private List<FieldError> fieldErrors;  // Field-level validation errors
    private String requestId;        // For tracing
}

@Data
@Builder
public class FieldError {
    private String field;
    private String message;
    private Object rejectedValue;
    private String errorCode;
}
```

### Global Exception Handler

```java
@RestControllerAdvice
@Slf4j
public class GlobalApiExceptionHandler {
    
    /**
     * Handle validation errors (400)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        
        List<FieldError> fieldErrors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            fieldErrors.add(FieldError.builder()
                .field(error.getField())
                .message(error.getDefaultMessage())
                .rejectedValue(error.getRejectedValue())
                .errorCode("VALIDATION_ERROR")
                .build())
        );
        
        ApiErrorResponse response = ApiErrorResponse.builder()
            .timestamp(LocalDateTime.now().toString())
            .errorCode("VALIDATION_ERROR")
            .errorMessage("Request validation failed")
            .status(HttpStatus.BAD_REQUEST.value())
            .fieldErrors(fieldErrors)
            .path(request.getRequestURI())
            .requestId(request.getHeader("X-Request-ID"))
            .build();
        
        logger.warn("Validation error: {}", fieldErrors);
        return ResponseEntity.badRequest().body(response);
    }
    
    /**
     * Handle authentication errors (401)
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request) {
        
        ApiErrorResponse response = ApiErrorResponse.builder()
            .timestamp(LocalDateTime.now().toString())
            .errorCode("AUTHENTICATION_ERROR")
            .errorMessage("Authentication failed")
            .details(ex.getMessage())
            .status(HttpStatus.UNAUTHORIZED.value())
            .path(request.getRequestURI())
            .requestId(request.getHeader("X-Request-ID"))
            .build();
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }
    
    /**
     * Handle business exceptions (422)
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(
            BusinessException ex,
            HttpServletRequest request) {
        
        ApiErrorResponse response = ApiErrorResponse.builder()
            .timestamp(LocalDateTime.now().toString())
            .errorCode(ex.getErrorCode())
            .errorMessage(ex.getMessage())
            .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
            .path(request.getRequestURI())
            .requestId(request.getHeader("X-Request-ID"))
            .build();
        
        logger.warn("Business error: {}", ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }
    
    /**
     * Handle resource not found (404)
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request) {
        
        ApiErrorResponse response = ApiErrorResponse.builder()
            .timestamp(LocalDateTime.now().toString())
            .errorCode("RESOURCE_NOT_FOUND")
            .errorMessage(ex.getMessage())
            .status(HttpStatus.NOT_FOUND.value())
            .path(request.getRequestURI())
            .requestId(request.getHeader("X-Request-ID"))
            .build();
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }
    
    /**
     * Handle all other exceptions (500)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {
        
        String requestId = UUID.randomUUID().toString();
        
        ApiErrorResponse response = ApiErrorResponse.builder()
            .timestamp(LocalDateTime.now().toString())
            .errorCode("INTERNAL_SERVER_ERROR")
            .errorMessage("An unexpected error occurred")
            .details("Request ID: " + requestId)
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .path(request.getRequestURI())
            .requestId(requestId)
            .build();
        
        logger.error("Unexpected error (Request ID: {})", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
```

---

## API Versioning

### Versioning Strategy

```
URL Path Versioning (RECOMMENDED for banking systems):

/api/v1/customers       # Version 1
/api/v2/customers       # Version 2 (backward incompatible changes)
/api/v1.1/customers     # Patch version (backward compatible)

Header Versioning (Alternative):
GET /api/customers
Accept: application/vnd.bank.v1+json

Query Parameter Versioning (NOT RECOMMENDED):
GET /api/customers?version=1
```

### Version Management

```java
/**
 * Supports multiple API versions
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerControllerV1 {
    // Version 1 implementation
}

@RestController
@RequestMapping("/api/v2/customers")
public class CustomerControllerV2 {
    // Version 2 implementation with breaking changes
}

/**
 * Deprecated endpoints migration
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountControllerV1 {
    
    @Deprecated(forRemoval = true, since = "2.0")
    @GetMapping
    public ResponseEntity<?> listAccounts() {
        // Still available but mark as deprecated
    }
}
```

---

## Pagination & Filtering

### Pagination

```java
/**
 * Example paginated endpoint
 */
@GetMapping("/customers")
public ResponseEntity<ApiResponse<Page<CustomerResponse>>> getCustomers(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(defaultValue = "id") String sortBy,
        @RequestParam(defaultValue = "ASC") Sort.Direction direction) {
    
    Pageable pageable = PageRequest.of(page, pageSize, Sort.by(direction, sortBy));
    Page<CustomerResponse> results = customerService.getCustomers(pageable);
    
    return ResponseEntity.ok(ApiResponse.<Page<CustomerResponse>>builder()
        .data(results)
        .build());
}

/**
 * Query Examples:
 * GET /api/v1/customers?page=0&pageSize=20&sortBy=name&direction=ASC
 * GET /api/v1/customers?page=1&pageSize=50
 * GET /api/v1/customers?page=0&pageSize=10&sortBy=createdDate&direction=DESC
 */
```

### Filtering

```java
/**
 * Filtered search endpoint
 */
@GetMapping("/customers/search")
public ResponseEntity<ApiResponse<Page<CustomerResponse>>> searchCustomers(
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) CustomerStatus status,
        @RequestParam(required = false) String city,
        @RequestParam(required = false) LocalDate fromDate,
        @RequestParam(required = false) LocalDate toDate,
        Pageable pageable) {
    
    CustomerSearchCriteria criteria = CustomerSearchCriteria.builder()
        .name(name)
        .email(email)
        .status(status)
        .city(city)
        .fromDate(fromDate)
        .toDate(toDate)
        .build();
    
    Page<CustomerResponse> results = customerService.search(criteria, pageable);
    
    return ResponseEntity.ok(ApiResponse.<Page<CustomerResponse>>builder()
        .data(results)
        .build());
}

/**
 * Query Examples:
 * GET /api/v1/customers/search?name=John&status=ACTIVE&page=0&pageSize=20
 * GET /api/v1/customers/search?email=john@example.com&fromDate=2024-01-01&toDate=2024-12-31
 * GET /api/v1/customers/search?city=NewYork&status=INACTIVE
 */
```

---

## Rate Limiting

### Rate Limiting Configuration

```java
@Configuration
public class RateLimitConfig {
    
    @Bean
    public RequestLimitService requestLimitService() {
        return new RequestLimitService();
    }
}

/**
 * Rate limiting interceptor
 */
@Component
@Slf4j
public class RateLimitingInterceptor implements HandlerInterceptor {
    
    @Autowired
    private RequestLimitService limitService;
    
    @Override
    public boolean preHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler) throws Exception {
        
        String clientId = getClientIdentifier(request);
        String endpoint = request.getRequestURI();
        
        if (!limitService.allowRequest(clientId, endpoint)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(buildRateLimitExceededResponse());
            return false;
        }
        
        // Add rate limit headers to response
        RateLimitInfo info = limitService.getRateLimitInfo(clientId, endpoint);
        response.setHeader("X-RateLimit-Limit", String.valueOf(info.getLimit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(info.getRemaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(info.getResetTime()));
        
        return true;
    }
    
    private String getClientIdentifier(HttpServletRequest request) {
        // Get from API key, JWT, or IP address
        return request.getHeader("X-API-Key") != null ?
            request.getHeader("X-API-Key") :
            request.getRemoteAddr();
    }
}

/**
 * Rate limiting rules by API tier
 */
@Data
public class RateLimitPolicy {
    public static final RateLimitPolicy BASIC = new RateLimitPolicy(1000, 3600);      // 1000 req/hour
    public static final RateLimitPolicy STANDARD = new RateLimitPolicy(5000, 3600);   // 5000 req/hour
    public static final RateLimitPolicy PREMIUM = new RateLimitPolicy(50000, 3600);   // 50000 req/hour
    
    private final int requestLimit;
    private final int windowSeconds;
}
```

---

## API Documentation with OpenAPI/Swagger

### Configuration

```java
@Configuration
public class OpenApiConfig {
    
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Core Banking System (CBS) API")
                .version("1.0.0")
                .description("Comprehensive REST API for banking operations")
                .contact(new Contact()
                    .name("Banking System Dev Team")
                    .email("api@bank.com")))
            .servers(Arrays.asList(
                new Server()
                    .url("https://api.bank.com")
                    .description("Production"),
                new Server()
                    .url("https://api-dev.bank.com")
                    .description("Development")
            ))
            .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
            .components(new Components()
                .addSecuritySchemes("Bearer Authentication",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT authentication token")));
    }
}
```

### API Endpoint Documentation

```java
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customer Management", description = "APIs for managing customers")
public class CustomerController {
    
    @PostMapping
    @Operation(
        summary = "Create a new customer",
        description = "Creates a new customer with KYC details"
    )
    @ApiResponse(
        responseCode = "201",
        description = "Customer successfully created",
        content = @Content(schema = @Schema(implementation = CustomerResponse.class))
    )
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request - validation errors",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))
    )
    @ApiResponse(
        responseCode = "409",
        description = "Conflict - customer with email already exists"
    )
    public ResponseEntity<ApiResponse<CustomerResponse>> createCustomer(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Customer details",
                required = true,
                content = @Content(schema = @Schema(implementation = CustomerCreateRequest.class))
            )
            CustomerCreateRequest request) {
        // Implementation
    }
    
    @GetMapping("/{customerId}")
    @Operation(summary = "Get customer details by ID")
    @Parameters({
        @Parameter(
            name = "customerId",
            description = "Unique customer identifier",
            example = "CUST001023",
            in = ParameterIn.PATH,
            required = true
        )
    })
    @ApiResponse(responseCode = "200", description = "Customer found")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomer(
            @PathVariable String customerId) {
        // Implementation
    }
    
    @GetMapping
    @Operation(summary = "Search customers with filters")
    @Parameters({
        @Parameter(name = "name", description = "Customer name (partial match)", example = "John"),
        @Parameter(name = "email", description = "Customer email", example = "john@example.com"),
        @Parameter(name = "status", description = "Customer status", example = "ACTIVE"),
        @Parameter(name = "page", description = "Page number (0-indexed)", example = "0"),
        @Parameter(name = "pageSize", description = "Page size", example = "20")
    })
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> searchCustomers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) CustomerStatus status,
            Pageable pageable) {
        // Implementation
    }
}
```

### Swagger UI Access

```
Swagger UI:    https://api.bank.com/swagger-ui.html
OpenAPI JSON:  https://api.bank.com/v3/api-docs
OpenAPI YAML:  https://api.bank.com/v3/api-docs.yaml
```

---

## API Naming Conventions

### Resource Endpoints

| Operation | Method | Path | Response |
|-----------|--------|------|----------|
| Create | POST | `/resources` | 201 Created + Resource |
| Read | GET | `/resources/{id}` | 200 OK + Resource |
| Update | PUT | `/resources/{id}` | 200 OK + Resource |
| Partial Update | PATCH | `/resources/{id}` | 200 OK + Resource |
| Delete | DELETE | `/resources/{id}` | 204 No Content |
| List | GET | `/resources` | 200 OK + Array + Pagination |
| Search | GET | `/resources/search?q=...` | 200 OK + Array |

### Action Endpoints

For RPC-style actions, use POST with action in URL:

```
POST /api/v1/accounts/ACC001/transfer              # Transfer funds
POST /api/v1/accounts/ACC001/freeze                # Freeze account
POST /api/v1/loans/LOAN001/calculate-emi           # Calculate EMI
POST /api/v1/customers/CUST001/verify-kyc          # Verify KYC
POST /api/v1/reports/generate                      # Generate report
```

---

This REST API design guideline ensures consistency, security, and scalability across all banking system APIs.


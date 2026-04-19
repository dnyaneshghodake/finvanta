# Tier-1 Grade CBS: Comprehensive Coding Standards & Guidelines

## Table of Contents
1. [Code Quality Standards](#code-quality-standards)
2. [Java Coding Conventions](#java-coding-conventions)
3. [Exception Handling](#exception-handling)
4. [Logging Standards](#logging-standards)
5. [Code Comments & Documentation](#code-comments--documentation)
6. [Entity Design Standards](#entity-design-standards)
7. [DTO Design Standards](#dto-design-standards)
8. [Controller Design Standards](#controller-design-standards)
9. [Service Layer Standards](#service-layer-standards)
10. [Repository Pattern Standards](#repository-pattern-standards)
11. [Validator Implementation](#validator-implementation)
12. [Security Standards](#security-standards)

---

## Code Quality Standards

### SonarQube Compliance

All code must meet these quality metrics:

| Metric | Standard | Compliance |
|--------|----------|-----------|
| **Code Coverage** | ≥ 80% | Minimum for production code |
| **Cyclomatic Complexity** | ≤ 10 | Per method |
| **Cognitive Complexity** | ≤ 15 | Per method |
| **Duplicated Lines** | < 3% | Of codebase |
| **Technical Debt Ratio** | < 5% | Overall |
| **Security Vulnerabilities** | 0 | Blocking |
| **Critical Issues** | 0 | Blocking |
| **Major Issues** | < 5 | Warning |

### Code Format Standards

- **Line Length**: Max 120 characters (enforced by checkstyle)
- **Indentation**: 4 spaces (NOT tabs)
- **Line Endings**: Unix (LF) format
- **File Encoding**: UTF-8
- **Trailing Whitespace**: None

### Import Organization

```java
// 1. Java standard library
import java.util.*;
import java.time.*;
import java.math.BigDecimal;

// Blank line separator

// 2. Third-party libraries  
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springdoc.openapi.annotations.security.SecurityRequirement;
import com.fasterxml.jackson.annotation.JsonProperty;

// Blank line separator

// 3. Project imports
import com.cbs.banking.app.dto.request.CustomerCreateRequest;
import com.cbs.banking.app.dto.response.CustomerResponse;
import com.cbs.banking.app.exception.ValidationException;
import com.cbs.banking.business.service.CustomerService;
import com.cbs.banking.domain.entity.Customer;
import com.cbs.banking.domain.repository.CustomerRepository;

// NO wildcard imports (e.g., import java.util.*;)
// Exception: Can use wildcards for static imports if ≤3 items
```

---

## Java Coding Conventions

### Class and Interface Design

#### Class Declaration

```java
/**
 * Manages customer-related business operations.
 * 
 * Responsible for:
 * - Customer creation, reading, updating, deletion
 * - Customer validation
 * - Customer search and filtering
 * 
 * @author Banking System Dev Team
 * @version 2.0
 * @since 1.0
 */
@Service
@Slf4j
@Transactional
@CacheConfig(cacheNames = "customers")
public class CustomerService {
    
    // Class body
}
```

#### Constructor Injection (Preferred)

```java
// ✅ GOOD: Constructor injection (immutability, testability)
@Service
public class CustomerService {
    private final CustomerRepository repository;
    private final CustomerValidator validator;
    private final CacheManager cacheManager;
    
    public CustomerService(
            CustomerRepository repository,
            CustomerValidator validator,
            CacheManager cacheManager) {
        this.repository = repository;
        this.validator = validator;
        this.cacheManager = cacheManager;
    }
}

// ❌ BAD: Field injection (mutable, hard to test)
@Service
public class CustomerService {
    @Autowired
    private CustomerRepository repository;
}
```

#### Instance Field Rules

```java
@Service
public class CustomerService {
    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);
    private static final String CACHE_KEY_PREFIX = "CUSTOMER_";
    private static final int MAX_RETRIES = 3;
    
    // All instance fields should be private and final when possible
    private final CustomerRepository repository;
    private final CustomerValidator validator;
    
    // Constructor follows
}
```

### Variable Declaration

```java
// ✅ GOOD: Declare close to first use, use final when possible
public CustomerResponse getCustomer(Long customerId) {
    final Customer customer = repository.findById(customerId)
        .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    
    return customerMapper.toResponse(customer);
}

// ✅ GOOD: Use appropriate types
List<Account> accounts = customer.getAccounts();  // Not: List raw type
Map<String, Object> cache = new HashMap<>();
Optional<Customer> customer = repository.findById(id);

// ❌ BAD: Overly broad scope
public void processCustomer(Long customerId) {
    Customer customer;
    
    // ... 50 lines of code ...
    
    customer = repository.findById(customerId).orElseThrow();
}
```

### Method Design

#### Method Signature Best Practices

```java
// ✅ GOOD: Clear purpose, reasonable parameters
public CustomerResponse createCustomer(CustomerCreateRequest request) throws ValidationException {
    // Implementation
}

// ✅ GOOD: Return specific types instead of Object
public List<CustomerResponse> searchCustomers(CustomerSearchCriteria criteria, Pageable pageable) {
    // Implementation
}

// ❌ BAD: Too many parameters (>5)
public void createCustomer(String name, String email, String phone, 
                          String address, String city, String state, 
                          String country, String zipCode) {
    // Implementation
}
// Solution: Use request object

// ❌ BAD: Method does too much
public void process(Customer customer) {
    // Validates
    // Saves
    // Publishes event
    // Sends notification
    // Logs activity
}
// Solution: Break into smaller methods
```

#### Method Length

```
Maximum recommended method length: 50 lines
- If exceeding: Extract helper methods
- Each method should have single responsibility
- Private helper methods for common logic

EXAMPLE Structure:
public CustomerResponse createCustomer(CustomerCreateRequest request) {      // 5-10 lines
    validator.validate(request);                                              // Delegate
    Customer customer = transformer.toEntity(request);                        // Delegate
    customer = repository.save(customer);                                     // Delegate
    publishCustomerCreatedEvent(customer);                                    // Delegate
    return mapper.toResponse(customer);                                       // Delegate
}

private void publishCustomerCreatedEvent(Customer customer) {
    // 10-15 lines of event publication logic
}
```

---

## Exception Handling

### Exception Hierarchy

```java
// Base exception for banking system
public abstract class BankingSystemException extends RuntimeException {
    private final String errorCode;
    private final String errorMessage;
    
    public BankingSystemException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }
    
    public String getErrorCode() { return errorCode; }
}

// Business exceptions
public class ValidationException extends BankingSystemException {
    public ValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}

public class ResourceNotFoundException extends BankingSystemException {
    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", message);
    }
}

public class BusinessRuleViolationException extends BankingSystemException {
    public BusinessRuleViolationException(String message) {
        super("BUSINESS_RULE_VIOLATION", message);
    }
}

public class InsufficientFundsException extends BankingSystemException {
    public InsufficientFundsException(String message) {
        super("INSUFFICIENT_FUNDS", message);
    }
}
```

### Exception Handling Patterns

```java
// ✅ GOOD: Specific exception handling with recovery
public CustomerResponse getCustomer(Long customerId) {
    try {
        return repository.findById(customerId)
            .map(customerMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(
                String.format("Customer with ID %d not found", customerId)
            ));
    } catch (DatabaseException ex) {
        logger.error("Database error retrieving customer {}", customerId, ex);
        throw new SystemException("Failed to retrieve customer", ex);
    }
}

// ❌ BAD: Generic exception catching
public CustomerResponse getCustomer(Long customerId) {
    try {
        return repository.findById(customerId)
            .map(customerMapper::toResponse)
            .orElseThrow();
    } catch (Exception ex) {  // Too generic
        logger.error("Error", ex);
        throw new RuntimeException("Failed");
    }
}

// ✅ GOOD: Global exception handler
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            ValidationException ex, HttpServletRequest request) {
        
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .errorCode(ex.getErrorCode())
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .build();
        
        return ResponseEntity.badRequest().body(error);
    }
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFoundException(
            ResourceNotFoundException ex, HttpServletRequest request) {
        
        ErrorResponse error = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.NOT_FOUND.value())
            .errorCode(ex.getErrorCode())
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .build();
        
        logger.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }
}
```

---

## Logging Standards

### Logging Configuration

```xml
<!-- logback-spring.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Pattern for log output -->
    <property name="LOG_PATTERN" value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n" />
    
    <!-- File appender -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/cbs-banking.log</file>
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/cbs-banking-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
    </appender>
    
    <!-- Root logger -->
    <root level="INFO">
        <appender-ref ref="FILE" />
    </root>
    
    <!-- Module-specific loggers -->
    <logger name="com.cbs.banking" level="DEBUG" />
    <logger name="org.springframework.security" level="DEBUG" />
    <logger name="org.springframework.data" level="INFO" />
</configuration>
```

### Logging Standards

```java
@Service
@Slf4j  // Lombok annotation for logger injection
public class CustomerService {
    
    private final CustomerRepository repository;
    
    // ✅ GOOD: Appropriate log levels
    public CustomerResponse createCustomer(CustomerCreateRequest request) {
        logger.info("Creating customer with email: {}", request.getEmail());
        
        try {
            validator.validate(request);
            logger.debug("Customer validation passed");
            
            Customer customer = new Customer();
            customer.setEmail(request.getEmail());
            customer.setName(request.getName());
            
            Customer saved = repository.save(customer);
            logger.info("Customer created successfully with ID: {}", saved.getId());
            
            return mapper.toResponse(saved);
            
        } catch (ValidationException ex) {
            logger.warn("Customer creation failed - validation error: {}", ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            logger.error("Unexpected error creating customer", ex);
            throw new SystemException("Failed to create customer", ex);
        }
    }
    
    // ✅ GOOD: Log performance metrics
    @Timed("customer.search")
    public List<CustomerResponse> searchCustomers(CustomerSearchCriteria criteria) {
        long startTime = System.currentTimeMillis();
        
        try {
            List<Customer> results = repository.search(criteria);
            long duration = System.currentTimeMillis() - startTime;
            
            if (duration > 5000) {
                logger.warn("Customer search took {}ms - consider optimization", duration);
            } else {
                logger.debug("Customer search completed in {}ms", duration);
            }
            
            return mapper.toResponseList(results);
        } catch (Exception ex) {
            logger.error("Error searching customers with criteria: {}", criteria, ex);
            throw ex;
        }
    }
}

// ✅ GOOD: Don't log sensitive information
public void updateCustomer(CustomerUpdateRequest request) {
    logger.info("Updating customer ID: {}", request.getId());
    // DON'T: logger.info("Updating customer: {}", request);  // SSN, Account#, etc.
}
```

### Log Levels

| Level | Use Case | Example |
|-------|----------|---------|
| **TRACE** | Very detailed diagnostic info | Variable values in loops, method entry/exit |
| **DEBUG** | Detailed diagnostic info | Parameter values, state changes, SQL queries |
| **INFO** | General informational | Application startup, business operations, summary results |
| **WARN** | Warning conditions | Deprecated API usage, performance degradation |
| **ERROR** | Error conditions | Exceptions, failed operations, data inconsistencies |
| **FATAL** | Critical conditions | System shutdown, data corruption |

---

## Code Comments & Documentation

### JavaDoc Standards

```java
/**
 * Processes a financial transaction from one account to another.
 * 
 * This method:
 * <ol>
 *   <li>Validates transaction request and accounts</li>
 *   <li>Verifies account balances and limits</li>
 *   <li>Creates transaction record</li>
 *   <li>Updates account balances with double-entry accounting</li>
 *   <li>Publishes transaction event for audit</li>
 * </ol>
 * 
 * <p>
 * The transaction is atomic and will roll back entirely on any failure.
 * </p>
 * 
 * @param request the transaction request containing from/to accounts and amount
 * @return {@link TransactionResponse} containing transaction details
 * @throws ValidationException if transaction request is invalid
 * @throws InsufficientFundsException if source account has insufficient balance
 * @throws AccountLockedException if either account is locked
 * @throws LimitExceededException if transaction exceeds account limits
 * 
 * @see com.cbs.banking.business.validator.TransactionValidator
 * @see com.cbs.banking.business.service.GLPostingService
 * 
 * @since 1.0.0
 * @author Banking System Dev Team
 */
public TransactionResponse processTransaction(TransactionRequest request) 
        throws ValidationException, InsufficientFundsException {
    
    // Implementation
}
```

### Inline Comments

```java
public class InterestCalculator {
    
    /**
     * Calculates compound interest for a given principal, rate, and period.
     * Formula: A = P(1 + r/n)^(nt)
     * where P = principal, r = annual rate, n = compounding frequency, t = time in years
     */
    public BigDecimal calculateCompoundInterest(
            BigDecimal principal, 
            BigDecimal annualRate, 
            int compoundingFrequency, 
            int years) {
        
        // Avoid division by zero and invalid rates
        if (annualRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("Annual rate cannot be negative");
        }
        
        // Convert annual rate to decimal (e.g., 5% -> 0.05)
        BigDecimal rateDecimal = annualRate.divide(
            new BigDecimal(100), 
            10, 
            RoundingMode.HALF_UP
        );
        
        // Calculate: (1 + r/n)
        BigDecimal ratePerCompounding = rateDecimal.divide(
            new BigDecimal(compoundingFrequency), 
            10, 
            RoundingMode.HALF_UP
        ).add(BigDecimal.ONE);
        
        // Calculate exponent: n*t
        int exponent = compoundingFrequency * years;
        
        // Calculate final amount
        BigDecimal amount = principal.multiply(
            ratePerCompounding.pow(exponent, new MathContext(10, RoundingMode.HALF_UP))
        );
        
        return amount;
    }
}
```

### Comment Guidelines

| Type | Use | Example |
|------|-----|---------|
| **TODO** | Future improvements | `// TODO: Optimize this query` |
| **FIXME** | Known issues | `// FIXME: Handle edge case when amount is 0` |
| **NOTE** | Important information | `// NOTE: This must be called before transaction commit` |
| **HACK** | Temporary workaround | `// HACK: Workaround for legacy API compatibility` |
| **XXX** | Problem area | `// XXX: This could cause a race condition` |

---

## Entity Design Standards

### Entity Class Structure

```java
/**
 * Customer entity representing a banking customer.
 * 
 * Attributes:
 * - Personal information (name, email, phone)
 * - Address and contact details
 * - KYC status and documents
 * - Account relationships
 * 
 * @author Banking System Dev Team
 * @version 1.0
 * @since 1.0
 */
@Entity
@Table(name = "t_customers", indexes = {
    @Index(name = "idx_customer_email", columnList = "email", unique = true),
    @Index(name = "idx_customer_status", columnList = "status"),
    @Index(name = "idx_customer_created_date", columnList = "created_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
@ToString(exclude = {"accounts", "loans", "documents"})  // Avoid circular references in logs
@EqualsAndHashCode(of = {"id"})  // Use only ID for equality
public class Customer {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * Unique identifier for customer (Customer ID assigned by system)
     */
    @Column(name = "customer_id", unique = true, nullable = false, length = 20)
    private String customerId;
    
    /**
     * Customer's full name
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;
    
    /**
     * Customer's email address
     */
    @Column(name = "email", unique = true, nullable = false, length = 100)
    private String email;
    
    /**
     * Customer's phone number
     */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;
    
    /**
     * Customer account status
     */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CustomerStatus status;
    
    /**
     * Customer KYC status
     */
    @Column(name = "kyc_status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private KYCStatus kycStatus;
    
    /**
     * Date of birth
     */
    @Column(name = "date_of_birth")
    @Temporal(TemporalType.DATE)
    private LocalDate dateOfBirth;
    
    /**
     * Embedded address information
     */
    @Embedded
    private Address address;
    
    /**
     * Embedded contact information
     */
    @Embedded
    private ContactInfo contactInfo;
    
    /**
     * Risk score (0-100)
     */
    @Column(name = "risk_score")
    private Integer riskScore;
    
    /**
     * Status indicates if customer is blacklisted
     */
    @Column(name = "blacklisted", nullable = false)
    private Boolean blacklisted = false;
    
    // ============ Relationships ============
    
    /**
     * Customer's bank accounts (one customer, many accounts)
     */
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<Account> accounts = new HashSet<>();
    
    /**
     * Customer's loans (one customer, many loans)
     */
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<Loan> loans = new HashSet<>();
    
    /**
     * KYC documents
     */
    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<KYCDocument> documents = new HashSet<>();
    
    // ============ Audit Fields ============
    
    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private LocalDateTime createdDate;
    
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;
    
    @LastModifiedDate
    @Column(name = "last_modified_date")
    @Temporal(TemporalType.TIMESTAMP)
    private LocalDateTime lastModifiedDate;
    
    @LastModifiedBy
    @Column(name = "last_modified_by", length = 100)
    private String lastModifiedBy;
    
    /**
     * Optimistic locking version
     */
    @Version
    @Column(name = "version")
    private Long version;
    
    // ============ Helper Methods ============
    
    /**
     * Adds an account to this customer
     */
    public void addAccount(Account account) {
        if (accounts == null) {
            accounts = new HashSet<>();
        }
        accounts.add(account);
        account.setCustomer(this);
    }
    
    /**
     * Removes an account from this customer
     */
    public void removeAccount(Account account) {
        if (accounts != null) {
            accounts.remove(account);
            account.setCustomer(null);
        }
    }
}
```

### Embedded Value Objects

```java
/**
 * Address value object embedded in Customer entity
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {
    
    @Column(name = "street_address", length = 255)
    private String streetAddress;
    
    @Column(name = "city", length = 100)
    private String city;
    
    @Column(name = "state_province", length = 100)
    private String stateProvince;
    
    @Column(name = "postal_code", length = 20)
    private String postalCode;
    
    @Column(name = "country", length = 100)
    private String country;
    
    @Column(name = "address_type", length = 20)
    @Enumerated(EnumType.STRING)
    private AddressType type;
}

/**
 * Contact information value object
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactInfo {
    
    @Column(name = "email_personal", length = 100)
    private String personalEmail;
    
    @Column(name = "phone_mobile", length = 20)
    private String mobilePhone;
    
    @Column(name = "phone_home", length = 20)
    private String homePhone;
}
```

---

## DTO Design Standards

### Request DTO

```java
/**
 * Request DTO for creating a new customer.
 * Contains all necessary information to create a customer in the system.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Validated
@Schema(description = "Request to create a new customer")
public class CustomerCreateRequest {
    
    @NotBlank(message = "Customer name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    @Schema(description = "Customer's full name", example = "John Doe")
    private String name;
    
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Schema(description = "Customer's email address", example = "john.doe@example.com")
    private String email;
    
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Phone number must be valid")
    @Schema(description = "Customer's phone number", example = "+1234567890")
    private String phoneNumber;
    
    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @Schema(description = "Customer's date of birth", example = "1990-05-15")
    private LocalDate dateOfBirth;
    
    @NotNull(message = "Address is required")
    @Valid  // Validate nested object
    @Schema(description = "Customer's residential address")
    private AddressDTO address;
    
    @Valid  // Validate nested object
    @Schema(description = "Customer's contact information")
    private ContactInfoDTO contactInfo;
}

/**
 * Nested address DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddressDTO {
    
    @NotBlank(message = "Street address is required")
    private String streetAddress;
    
    @NotBlank(message = "City is required")
    private String city;
    
    @NotBlank(message = "Country is required")
    private String country;
    
    @Pattern(regexp = "^[0-9]{5,10}$", message = "Postal code format invalid")
    private String postalCode;
}
```

### Response DTO

```java
/**
 * Response DTO for customer information.
 * Used in API responses when returning customer data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer information response")
public class CustomerResponse {
    
    @Schema(description = "Unique customer ID", example = "CUST001023")
    private String customerId;
    
    @Schema(description = "Customer's full name", example = "John Doe")
    private String name;
    
    @Schema(description = "Customer's email", example = "john.doe@example.com")
    private String email;
    
    @Schema(description = "Customer account status", example = "ACTIVE")
    private CustomerStatus status;
    
    @Schema(description = "KYC status", example = "VERIFIED")
    private KYCStatus kycStatus;
    
    @Schema(description = "Risk score (0-100)", example = "35")
    private Integer riskScore;
    
    @Schema(description = "Number of accounts", example = "3")
    private Integer accountCount;
    
    @Schema(description = "Total account balance", example = "50000.00")
    private BigDecimal totalBalance;
    
    @Schema(description = "Account creation date", example = "2024-01-15T10:30:00")
    private LocalDateTime createdDate;
    
    @Schema(description = "List of customer accounts")
    private List<AccountSummaryResponse> accounts;
}

/**
 * Minimal account summary for customer response
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountSummaryResponse {
    
    @Schema(description = "Account ID", example = "ACC12345")
    private String accountNumber;
    
    @Schema(description = "Account type", example = "SAVINGS")
    private AccountType accountType;
    
    @Schema(description = "Current balance", example = "10000.50")
    private BigDecimal balance;
    
    @Schema(description = "Account status", example = "ACTIVE")
    private AccountStatus status;
}
```

### DTO Mapper

```java
/**
 * Mapper for converting between Customer entity and DTOs
 */
@Component
public class CustomerMapper {
    
    /**
     * Converts CustomerCreateRequest DTO to Customer entity
     */
    public Customer toEntity(CustomerCreateRequest request) {
        if (request == null) {
            return null;
        }
        
        Customer customer = new Customer();
        customer.setName(request.getName());
        customer.setEmail(request.getEmail());
        customer.setPhoneNumber(request.getPhoneNumber());
        customer.setDateOfBirth(request.getDateOfBirth());
        
        // Map nested DTOs
        customer.setAddress(mapAddressDTO(request.getAddress()));
        customer.setContactInfo(mapContactInfoDTO(request.getContactInfo()));
        
        return customer;
    }
    
    /**
     * Converts Customer entity to CustomerResponse DTO
     */
    public CustomerResponse toResponse(Customer customer) {
        if (customer == null) {
            return null;
        }
        
        CustomerResponse response = new CustomerResponse();
        response.setCustomerId(customer.getCustomerId());
        response.setName(customer.getName());
        response.setEmail(customer.getEmail());
        response.setStatus(customer.getStatus());
        response.setKycStatus(customer.getKycStatus());
        response.setRiskScore(customer.getRiskScore());
        response.setCreatedDate(customer.getCreatedDate());
        
        // Map relationships
        if (customer.getAccounts() != null) {
            response.setAccountCount(customer.getAccounts().size());
            response.setAccounts(
                customer.getAccounts().stream()
                    .map(this::toAccountSummary)
                    .collect(Collectors.toList())
            );
        }
        
        return response;
    }
    
    /**
     * Converts list of customers to list of responses
     */
    public List<CustomerResponse> toResponseList(List<Customer> customers) {
        if (customers == null) {
            return Collections.emptyList();
        }
        
        return customers.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }
    
    // Helper methods for nested mapping
    private Address mapAddressDTO(AddressDTO dto) {
        // Implementation
    }
    
    private AccountSummaryResponse toAccountSummary(Account account) {
        // Implementation
    }
}
```

---

## Controller Design Standards

### REST Controller Pattern

```java
/**
 * REST API controller for customer-related operations.
 * 
 * Base path: /api/v1/customers
 * Supported operations: Create, Read, Update, Delete, Search
 */
@RestController
@RequestMapping("/api/v1/customers")
@Slf4j
@Validated
@Tag(name = "Customer Management", description = "APIs for managing customers")
public class CustomerController {
    
    private final CustomerService customerService;
    private final CustomerMapper customerMapper;
    
    // Constructor injection
    public CustomerController(
            CustomerService customerService,
            CustomerMapper customerMapper) {
        this.customerService = customerService;
        this.customerMapper = customerMapper;
    }
    
    /**
     * Creates a new customer.
     * 
     * @param request the customer creation request
     * @return the created customer
     * @throws ValidationException if request is invalid
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new customer")
    @ApiResponse(responseCode = "201", description = "Customer created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    public ResponseEntity<ApiResponse<CustomerResponse>> createCustomer(
            @Valid @RequestBody CustomerCreateRequest request) {
        
        logger.info("Creating customer with email: {}", request.getEmail());
        
        CustomerResponse response = customerService.createCustomer(request);
        
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.<CustomerResponse>builder()
                .status("SUCCESS")
                .message("Customer created successfully")
                .data(response)
                .timestamp(LocalDateTime.now())
                .build()
            );
    }
    
    /**
     * Retrieves a customer by ID.
     * 
     * @param customerId the customer ID
     * @return the customer details
     * @throws ResourceNotFoundException if customer not found
     */
    @GetMapping("/{customerId}")
    @Operation(summary = "Get customer details")
    @ApiResponse(responseCode = "200", description = "Customer retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Customer not found")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomer(
            @PathVariable
            @NotBlank(message = "Customer ID is required")
            String customerId) {
        
        logger.info("Fetching customer: {}", customerId);
        
        CustomerResponse response = customerService.getCustomer(customerId);
        
        return ResponseEntity.ok(ApiResponse.<CustomerResponse>builder()
            .status("SUCCESS")
            .data(response)
            .timestamp(LocalDateTime.now())
            .build()
        );
    }
    
    /**
     * Updates an existing customer.
     * 
     * @param customerId the customer ID
     * @param request the update request
     * @return the updated customer
     */
    @PutMapping("/{customerId}")
    @Operation(summary = "Update customer details")
    public ResponseEntity<ApiResponse<CustomerResponse>> updateCustomer(
            @PathVariable String customerId,
            @Valid @RequestBody CustomerUpdateRequest request) {
        
        logger.info("Updating customer: {}", customerId);
        
        CustomerResponse response = customerService.updateCustomer(customerId, request);
        
        return ResponseEntity.ok(ApiResponse.<CustomerResponse>builder()
            .status("SUCCESS")
            .message("Customer updated successfully")
            .data(response)
            .timestamp(LocalDateTime.now())
            .build()
        );
    }
    
    /**
     * Searches for customers with optional filters and pagination.
     * 
     * @param name optional customer name filter
     * @param email optional email filter
     * @param status optional status filter
     * @param pageable pagination parameters
     * @return paginated list of customers
     */
    @GetMapping
    @Operation(summary = "Search customers")
    public ResponseEntity<ApiResponse<Page<CustomerResponse>>> searchCustomers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) CustomerStatus status,
            @PageableDefault(size = 20, page = 0, sort = "id", direction = Sort.Direction.DESC)
            Pageable pageable) {
        
        logger.info("Searching customers with criteria: name={}, email={}, status={}", name, email, status);
        
        CustomerSearchCriteria criteria = CustomerSearchCriteria.builder()
            .name(name)
            .email(email)
            .status(status)
            .build();
        
        Page<CustomerResponse> results = customerService.searchCustomers(criteria, pageable);
        
        return ResponseEntity.ok(ApiResponse.<Page<CustomerResponse>>builder()
            .status("SUCCESS")
            .data(results)
            .timestamp(LocalDateTime.now())
            .build()
        );
    }
    
    /**
     * Deletes a customer (soft delete).
     * 
     * @param customerId the customer ID
     */
    @DeleteMapping("/{customerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a customer")
    public void deleteCustomer(@PathVariable String customerId) {
        logger.info("Deleting customer: {}", customerId);
        customerService.deleteCustomer(customerId);
    }
}
```

### Global API Response Wrapper

```java
/**
 * Generic API response wrapper for all endpoints
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Standardized API response")
public class ApiResponse<T> {
    
    @Schema(description = "Response status", example = "SUCCESS")
    private String status;  // SUCCESS, ERROR, FAILED
    
    @Schema(description = "Response message", example = "Operation completed successfully")
    private String message;
    
    @Schema(description = "Response data")
    private T data;
    
    @Schema(description = "Response timestamp")
    private LocalDateTime timestamp;
    
    @Schema(description = "Request ID for tracing")
    private String requestId;
}

/**
 * Error response structure
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorResponse {
    
    private String errorCode;
    private String errorMessage;
    private String details;
    private LocalDateTime timestamp;
    private String path;
    private int status;
}
```

---

## Service Layer Standards

### Service Interface & Implementation

```java
/**
 * Service interface for customer business logic
 */
public interface CustomerService {
    
    /**
     * Creates a new customer
     */
    CustomerResponse createCustomer(CustomerCreateRequest request) throws ValidationException;
    
    /**
     * Retrieves a customer by ID
     */
    CustomerResponse getCustomer(String customerId) throws ResourceNotFoundException;
    
    /**
     * Updates customer information
     */
    CustomerResponse updateCustomer(String customerId, CustomerUpdateRequest request);
    
    /**
     * Searches customers with criteria and pagination
     */
    Page<CustomerResponse> searchCustomers(CustomerSearchCriteria criteria, Pageable pageable);
    
    /**
     * Deletes a customer (soft delete)
     */
    void deleteCustomer(String customerId);
}

/**
 * Service implementation
 */
@Service
@Slf4j
@Transactional
public class CustomerServiceImpl implements CustomerService {
    
    private final CustomerRepository repository;
    private final CustomerValidator validator;
    private final CustomerMapper mapper;
    private final CacheManager cacheManager;
    private final EventPublisher eventPublisher;
    
    public CustomerServiceImpl(
            CustomerRepository repository,
            CustomerValidator validator,
            CustomerMapper mapper,
            CacheManager cacheManager,
            EventPublisher eventPublisher) {
        this.repository = repository;
        this.validator = validator;
        this.mapper = mapper;
        this.cacheManager = cacheManager;
        this.eventPublisher = eventPublisher;
    }
    
    @Override
    @Transactional
    public CustomerResponse createCustomer(CustomerCreateRequest request) {
        logger.info("Creating customer with email: {}", request.getEmail());
        
        // 1. Validate input
        validator.validateCreateRequest(request);
        
        // 2. Check for duplicates
        if (repository.existsByEmail(request.getEmail())) {
            throw new DuplicateCustomerException("Email already registered");
        }
        
        // 3. Transform to entity
        Customer customer = mapper.toEntity(request);
        customer.setCustomerId(generateCustomerId());
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setKycStatus(KYCStatus.PENDING);
        customer.setBlacklisted(false);
        
        // 4. Save to database
        Customer saved = repository.save(customer);
        logger.debug("Customer saved with ID: {}", saved.getId());
        
        // 5. Publish event for audit/notification
        eventPublisher.publishEvent(new CustomerCreatedEvent(saved));
        
        // 6. Return response
        return mapper.toResponse(saved);
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "customers", key = "#customerId")
    public CustomerResponse getCustomer(String customerId) {
        logger.info("Fetching customer: {}", customerId);
        
        Customer customer = repository.findByCustomerId(customerId)
            .orElseThrow(() -> new ResourceNotFoundException(
                String.format("Customer not found: %s", customerId)
            ));
        
        return mapper.toResponse(customer);
    }
    
    @Override
    @Transactional
    @CacheEvict(value = "customers", key = "#customerId")
    public CustomerResponse updateCustomer(String customerId, CustomerUpdateRequest request) {
        logger.info("Updating customer: {}", customerId);
        
        // Validate request
        validator.validateUpdateRequest(request);
        
        // Fetch existing customer
        Customer customer = repository.findByCustomerId(customerId)
            .orElseThrow(() -> new ResourceNotFoundException(
                String.format("Customer not found: %s", customerId)
            ));
        
        // Update fields
        customer.setName(request.getName());
        customer.setPhoneNumber(request.getPhoneNumber());
        
        // Save and return
        Customer updated = repository.save(customer);
        eventPublisher.publishEvent(new CustomerUpdatedEvent(updated));
        
        return mapper.toResponse(updated);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Page<CustomerResponse> searchCustomers(CustomerSearchCriteria criteria, Pageable pageable) {
        logger.info("Searching customers with criteria: {}", criteria);
        
        Page<Customer> results = repository.search(criteria, pageable);
        return results.map(mapper::toResponse);
    }
    
    @Override
    @Transactional
    @CacheEvict(value = "customers", key = "#customerId")
    public void deleteCustomer(String customerId) {
        logger.info("Deleting customer: {}", customerId);
        
        Customer customer = repository.findByCustomerId(customerId)
            .orElseThrow(() -> new ResourceNotFoundException(
                String.format("Customer not found: %s", customerId)
            ));
        
        // Soft delete
        customer.setStatus(CustomerStatus.INACTIVE);
        repository.save(customer);
        
        eventPublisher.publishEvent(new CustomerDeletedEvent(customer));
    }
    
    /**
     * Helper method to generate unique customer ID
     */
    private String generateCustomerId() {
        // Implementation specific to business logic
        return "CUST" + System.currentTimeMillis();
    }
}
```

---

## Repository Pattern Standards

### Repository Interface

```java
/**
 * Repository for Customer entity access
 */
public interface CustomerRepository extends JpaRepository<Customer, Long>, 
                                            CustomCustomerRepository {
    
    /**
     * Finds a customer by their unique customer ID
     */
    Optional<Customer> findByCustomerId(String customerId);
    
    /**
     * Finds a customer by email
     */
    Optional<Customer> findByEmail(String email);
    
    /**
     * Checks if customer exists by email
     */
    boolean existsByEmail(String email);
    
    /**
     * Finds all customers by status
     */
    List<Customer> findByStatus(CustomerStatus status);
    
    /**
     * Paginates customers by status
     */
    Page<Customer> findByStatus(CustomerStatus status, Pageable pageable);
    
    /**
     * Finds customers by creation date range
     */
    List<Customer> findByCreatedDateBetween(LocalDateTime startDate, LocalDateTime endDate);
    
    /**
     * Counts customers by status
     */
    long countByStatus(CustomerStatus status);
}

/**
 * Custom repository interface for complex queries
 */
public interface CustomCustomerRepository {
    
    /**
     * Searches customers based on criteria
     */
    Page<Customer> search(CustomerSearchCriteria criteria, Pageable pageable);
    
    /**
     * Finds customers with pending KYC
     */
    List<Customer> findPendingKYCCustomers();
    
    /**
     * Finds high-risk customers
     */
    List<Customer> findHighRiskCustomers(int riskThreshold);
}
```

### Custom Repository Implementation

```java
/**
 * Custom repository implementation
 */
@Repository
@Slf4j
public class CustomCustomerRepositoryImpl implements CustomCustomerRepository {
    
    @PersistenceContext
    private EntityManager entityManager;
    
    @Override
    public Page<Customer> search(CustomerSearchCriteria criteria, Pageable pageable) {
        logger.debug("Searching customers with criteria: {}", criteria);
        
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Customer> query = cb.createQuery(Customer.class);
        Root<Customer> root = query.from(Customer.class);
        
        List<Predicate> predicates = new ArrayList<>();
        
        // Add search conditions
        if (StringUtils.hasText(criteria.getName())) {
            predicates.add(cb.like(
                cb.lower(root.get("name")), 
                "%" + criteria.getName().toLowerCase() + "%"
            ));
        }
        
        if (StringUtils.hasText(criteria.getEmail())) {
            predicates.add(cb.equal(root.get("email"), criteria.getEmail()));
        }
        
        if (criteria.getStatus() != null) {
            predicates.add(cb.equal(root.get("status"), criteria.getStatus()));
        }
        
        // Apply predicates
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(new Predicate[0])));
        }
        
        // Apply sorting and pagination
        // ... implementation for sorting and pagination
        
        return buildPagedResultSet(query, pageable);
    }
    
    private Page<Customer> buildPagedResultSet(CriteriaQuery<Customer> query, Pageable pageable) {
        // Implementation
        return null;
    }
}
```

---

## Validator Implementation

### Validator Pattern

```java
/**
 * Validator for customer creation and update requests
 */
@Component
@Slf4j
public class CustomerValidator {
    
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MIN_AGE = 18;
    
    private final CustomerRepository repository;
    
    public CustomerValidator(CustomerRepository repository) {
        this.repository = repository;
    }
    
    /**
     * Validates customer creation request
     */
    public void validateCreateRequest(CustomerCreateRequest request) {
        logger.debug("Validating customer creation request");
        
        List<String> errors = new ArrayList<>();
        
        // Validate name
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            errors.add("Customer name is required");
        } else if (request.getName().length() < MIN_NAME_LENGTH || 
                   request.getName().length() > MAX_NAME_LENGTH) {
            errors.add(String.format("Name must be between %d and %d characters", 
                MIN_NAME_LENGTH, MAX_NAME_LENGTH));
        }
        
        // Validate email
        if (request.getEmail() == null || !isValidEmail(request.getEmail())) {
            errors.add("Email must be valid");
        }
        
        // Validate phone
        if (request.getPhoneNumber() == null || !isValidPhoneNumber(request.getPhoneNumber())) {
            errors.add("Phone number must be valid");
        }
        
        // Validate age
        if (request.getDateOfBirth() != null) {
            int age = Period.between(request.getDateOfBirth(), LocalDate.now()).getYears();
            if (age < MIN_AGE) {
                errors.add(String.format("Customer must be at least %d years old", MIN_AGE));
            }
        }
        
        // Validate address
        if (request.getAddress() == null) {
            errors.add("Address is required");
        } else {
            validateAddress(request.getAddress(), errors);
        }
        
        if (!errors.isEmpty()) {
            logger.warn("Customer validation failed: {}", errors);
            throw new ValidationException(String.join("; ", errors));
        }
        
        logger.debug("Customer validation passed");
    }
    
    /**
     * Validates address object
     */
    private void validateAddress(AddressDTO address, List<String> errors) {
        if (address.getStreetAddress() == null || address.getStreetAddress().isEmpty()) {
            errors.add("Street address is required");
        }
        if (address.getCity() == null || address.getCity().isEmpty()) {
            errors.add("City is required");
        }
        if (address.getCountry() == null || address.getCountry().isEmpty()) {
            errors.add("Country is required");
        }
    }
    
    /**
     * Email validation helper
     */
    private boolean isValidEmail(String email) {
        String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";
        return email.matches(emailRegex);
    }
    
    /**
     * Phone number validation helper
     */
    private boolean isValidPhoneNumber(String phone) {
        String phoneRegex = "^\\+?[1-9]\\d{1,14}$";
        return phone.matches(phoneRegex);
    }
}
```

---

## Security Standards

### Security Configuration

```java
/**
 * Security configuration for the application
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class SecurityConfig {
    
    /**
     * Configures security filter chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()  // Disable for stateless APIs
            .cors()
                .and()
            .authorizeRequests()
                // Public endpoints
                .antMatchers("/api/v1/auth/login", "/api/v1/auth/register").permitAll()
                .antMatchers("/actuator/health").permitAll()
                
                // Protected endpoints
                .antMatchers("/api/v1/**").authenticated()
                .antMatchers("/admin/**").hasRole("ADMIN")
                
                // Default
                .anyRequest().authenticated()
                .and()
            .httpBasic()
                .and()
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
        
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
    
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter();
    }
}

/**
 * JWT Authentication Filter
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    
    @Autowired
    private JwtTokenProvider tokenProvider;
    
    @Autowired
    private UserDetailsService userDetailsService;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);
            
            if (jwt != null && tokenProvider.validateToken(jwt)) {
                Long userId = tokenProvider.getUserIdFromToken(jwt);
                UserDetails userDetails = userDetailsService.loadUserByUsername(userId.toString());
                
                UsernamePasswordAuthenticationToken authentication = 
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            logger.error("JWT authentication failed", ex);
        }
        
        filterChain.doFilter(request, response);
    }
    
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

---

## POM.XML Dependencies Reference

For a Tier-1 grade CBS application, include these key dependencies:

```xml
<!-- Spring Boot -->
<spring.boot.version>3.1.5</spring.boot.version>
<spring-cloud.version>2022.0.4</spring-cloud.version>

<!-- Database -->
<artifactId>spring-boot-starter-data-jpa</artifactId>
<artifactId>mssql-jdbc</artifactId>
<artifactId>flyway-core</artifactId>
<artifactId>flyway-sqlserver</artifactId>

<!-- Security -->
<artifactId>spring-boot-starter-security</artifactId>
<groupId>io.jsonwebtoken</groupId>

<!-- Validation & Serialization -->
<artifactId>spring-boot-starter-validation</artifactId>
<artifactId>jackson-databind</artifactId>

<!-- Caching -->
<artifactId>spring-boot-starter-data-redis</artifactId>
<groupId>redis.clients</groupId>

<!-- Message Queue -->
<groupId>org.springframework.kafka</groupId>
<artifactId>spring-kafka</artifactId>

<!-- API Documentation -->
<groupId>org.springdoc</groupId>
<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>

<!-- Monitoring & Logging -->
<artifactId>spring-boot-starter-actuator</artifactId>
<groupId>io.micrometer</groupId>

<!-- Testing -->
<artifactId>spring-boot-starter-test</artifactId>
<artifactId>testcontainers</artifactId>

<!-- Utilities -->
<groupId>org.projectlombok</groupId>
<artifactId>lombok</artifactId>
<groupId>org.mapstruct</groupId>
<artifactId>mapstruct</artifactId>
```

This comprehensive guide ensures enterprise-grade code quality and maintainability.


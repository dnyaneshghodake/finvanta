# Tier-1 Grade CBS: Project Structure & Organization Guidelines

## Table of Contents
1. [Project Structure Overview](#project-structure-overview)
2. [Package Organization](#package-organization)
3. [Source Code Directory Structure](#source-code-directory-structure)
4. [Resource Organization](#resource-organization)
5. [Test Organization](#test-organization)
6. [Naming Conventions](#naming-conventions)
7. [File Organization Best Practices](#file-organization-best-practices)

---

## Project Structure Overview

```
cbs-banking-system/
├── pom.xml                                 (Maven configuration)
├── README.md
├── docker-compose.yml                      (Local development containerization)
├── Dockerfile
├── .gitignore
├── .env                                    (Environment configuration)
│
├── docs/                                   (Documentation)
│   ├── ARCHITECTURE.md
│   ├── API_DOCUMENTATION.md
│   ├── DATABASE_DESIGN.md
│   ├── DEPLOYMENT_GUIDE.md
│   ├── DEVELOPMENT_GUIDE.md
│   └── TROUBLESHOOTING.md
│
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── cbs/
│   │   │           └── banking/
│   │   │               ├── app/                        (Application layer)
│   │   │               │   ├── controller/
│   │   │               │   ├── dto/
│   │   │               │   ├── exception/
│   │   │               │   └── util/
│   │   │               │
│   │   │               ├── domain/                     (Domain layer)
│   │   │               │   ├── entity/
│   │   │               │   ├── enums/
│   │   │               │   ├── model/
│   │   │               │   ├── repository/
│   │   │               │   └── valueobject/
│   │   │               │
│   │   │               ├── business/                   (Business/Service layer)
│   │   │               │   ├── service/
│   │   │               │   ├── validator/
│   │   │               │   ├── calculator/
│   │   │               │   ├── transformer/
│   │   │               │   └── rules/
│   │   │               │
│   │   │               ├── infrastructure/             (Infrastructure layer)
│   │   │               │   ├── cache/
│   │   │               │   ├── messaging/
│   │   │               │   ├── audit/
│   │   │               │   ├── security/
│   │   │               │   └── external/
│   │   │               │
│   │   │               ├── integration/                (Integration layer)
│   │   │               │   ├── payment/
│   │   │               │   └── gateway/
│   │   │               │
│   │   │               ├── config/                     (Configuration)
│   │   │               │   ├── SecurityConfig.java
│   │   │               │   ├── CacheConfig.java
│   │   │               │   ├── DataSourceConfig.java
│   │   │               │   └── ApplicationConfig.java
│   │   │               │
│   │   │               ├── aspect/                     (AOP aspects)
│   │   │               │   ├── LoggingAspect.java
│   │   │               │   ├── SecurityAspect.java
│   │   │               │   └── PerformanceAspect.java
│   │   │               │
│   │   │               ├── schedule/                   (Scheduled jobs)
│   │   │               │   ├── InterestCalculationScheduler.java
│   │   │               │   ├── ChargeCalculationScheduler.java
│   │   │               │   └── DayEndProcessingScheduler.java
│   │   │               │
│   │   │               └── CbsApplication.java         (Main application class)
│   │   │
│   │   └── resources/
│   │       ├── application.yml                        (Main config)
│   │       ├── application-dev.yml                    (Dev profile)
│   │       ├── application-prod.yml                   (Prod profile)
│   │       ├── application-test.yml                   (Test profile)
│   │       ├── logback-spring.xml                     (Logging config)
│   │       ├── messages/
│   │       │   ├── messages.properties
│   │       │   ├── messages_en.properties
│   │       │   └── messages_es.properties
│   │       ├── db/
│   │       │   ├── migration/                         (Flyway migrations)
│   │       │   │   ├── V1__Initial_Schema.sql
│   │       │   │   ├── V2__Add_Audit_Tables.sql
│   │       │   │   └── V3__Add_Indexes.sql
│   │       │   └── seed/
│   │       │       └── data.sql
│   │       ├── static/
│   │       │   ├── css/
│   │       │   ├── js/
│   │       │   └── images/
│   │       └── templates/                              (Email templates, etc.)
│   │           ├── email/
│   │           └── reports/
│   │
│   └── test/
│       ├── java/
│       │   └── com/
│       │       └── cbs/
│       │           └── banking/
│       │               ├── app/
│       │               │   ├── controller/             (Controller tests)
│       │               │   └── dto/                    (DTO tests)
│       │               │
│       │               ├── business/
│       │               │   ├── service/                (Service tests)
│       │               │   ├── validator/              (Validator tests)
│       │               │   └── calculator/             (Calculator tests)
│       │               │
│       │               ├── domain/
│       │               │   └── entity/                 (Entity tests)
│       │               │
│       │               ├── integration/                (Integration tests)
│       │               │   ├── CustomerIntegrationTest.java
│       │               │   └── AccountIntegrationTest.java
│       │               │
│       │               └── fixtures/                   (Test fixtures & data)
│       │                   ├── CustomerTestData.java
│       │                   └── AccountTestData.java
│       │
│       └── resources/
│           ├── application-test.yml
│           ├── logback-test.xml
│           ├── test-data/
│           │   ├── customers.json
│           │   ├── accounts.json
│           │   └── transactions.json
│           └── mockdata/
│               └── external-service-responses.json
│
├── scripts/
│   ├── build.sh
│   ├── deploy.sh
│   ├── database-init.sql
│   └── health-check.sh
│
├── config/
│   ├── nginx.conf                          (API Gateway config)
│   └── kubernetes/                         (K8s manifests)
│       ├── deployment.yaml
│       ├── service.yaml
│       └── configmap.yaml
│
└── .github/
    └── workflows/
        ├── build.yml
        └── deploy.yml

```

---

## Package Organization

### Core Package Structure

```
com.cbs.banking (Root package)
│
├── app (Application Layer)
│   ├── controller (REST Controllers)
│   ├── dto (Data Transfer Objects)
│   ├── exception (Application Exceptions)
│   └── util (Application Utilities)
│
├── domain (Domain Layer)
│   ├── entity (JPA Entities)
│   ├── enums (Enumerations)
│   ├── model (Domain Models/POJOs)
│   ├── repository (Data Access Interfaces)
│   └── valueobject (Value Objects)
│
├── business (Business Logic Layer)
│   ├── service (Business Services)
│   ├── validator (Business Validators)
│   ├── calculator (Calculation Engines)
│   ├── transformer (Business Transformers)
│   └── rules (Business Rules)
│
├── infrastructure (Infrastructure/Cross-cutting)
│   ├── cache (Caching layer)
│   ├── messaging (Message queue integration)
│   ├── audit (Audit logging)
│   ├── security (Security utilities)
│   └── external (External service calls)
│
├── integration (Integration Layer)
│   ├── payment (Payment gateway integration)
│   └── gateway (External API gateway)
│
├── config (Configuration Classes)
│
├── aspect (AOP Aspects)
│
└── schedule (Scheduled Jobs)
```

---

## Source Code Directory Structure

### Detailed Package Breakdown

#### Application Layer (`app/`)

```
app/
├── controller/
│   ├── CustomerController.java
│   ├── AccountController.java
│   ├── DepositController.java
│   ├── LoanController.java
│   ├── PaymentController.java
│   ├── ReportController.java
│   └── BaseController.java              (Common controller functionality)
│
├── dto/
│   ├── request/
│   │   ├── CustomerCreateRequest.java
│   │   ├── CustomerUpdateRequest.java
│   │   ├── AccountOpenRequest.java
│   │   ├── TransactionRequest.java
│   │   └── PaymentRequest.java
│   │
│   ├── response/
│   │   ├── CustomerResponse.java
│   │   ├── AccountResponse.java
│   │   ├── TransactionResponse.java
│   │   ├── ApiResponse.java                (Wrapper response)
│   │   └── PaginatedResponse.java
│   │
│   └── mapper/
│       ├── CustomerMapper.java
│       ├── AccountMapper.java
│       └── PaymentMapper.java
│
├── exception/
│   ├── GlobalExceptionHandler.java
│   ├── BusinessException.java
│   ├── ResourceNotFoundException.java
│   ├── ValidationException.java
│   ├── SecurityException.java
│   └── ExternalServiceException.java
│
└── util/
    ├── ApiResponseUtil.java
    ├── ValidationUtil.java
    └── DateUtil.java
```

#### Domain Layer (`domain/`)

```
domain/
├── entity/
│   ├── Customer.java
│   ├── Account.java
│   ├── Transaction.java
│   ├── Loan.java
│   ├── Deposit.java
│   ├── AccountType.java
│   ├── GeneralLedger.java
│   ├── GLMapping.java
│   ├── Interest.java
│   └── Charge.java
│
├── enums/
│   ├── CustomerStatus.java
│   ├── AccountStatus.java
│   ├── TransactionType.java
│   ├── TransactionStatus.java
│   ├── LoanStatus.java
│   ├── DepositType.java
│   └── UserRole.java
│
├── model/
│   ├── Address.java                     (Domain model, not entity)
│   ├── ContactInfo.java
│   └── IdentityDocument.java
│
├── repository/
│   ├── CustomerRepository.java          (Spring Data JPA interface)
│   ├── AccountRepository.java
│   ├── TransactionRepository.java
│   ├── GLRepository.java
│   ├── CustomCustomerRepository.java    (Custom queries)
│   └── CustomAccountRepository.java
│
└── valueobject/
    ├── Money.java
    ├── InterestRate.java
    ├── IBAN.java
    └── BankCode.java
```

#### Business Logic Layer (`business/`)

```
business/
├── service/
│   ├── CustomerService.java             (Interface)
│   ├── CustomerServiceImpl.java          (Implementation)
│   ├── AccountService.java
│   ├── AccountServiceImpl.java
│   ├── TransactionService.java
│   ├── TransactionServiceImpl.java
│   ├── LoanService.java
│   ├── LoanServiceImpl.java
│   ├── DepositService.java
│   ├── DepositServiceImpl.java
│   ├── GLPostingService.java
│   ├── GLPostingServiceImpl.java
│   ├── InterestCalculationService.java
│   ├── InterestCalculationServiceImpl.java
│   ├── ChargeCalculationService.java
│   ├── ChargeCalculationServiceImpl.java
│   ├── SettlementService.java
│   └── SettlementServiceImpl.java
│
├── validator/
│   ├── CustomerValidator.java
│   ├── AccountValidator.java
│   ├── TransactionValidator.java
│   ├── LoanValidator.java
│   ├── DepositValidator.java
│   └── BusinessRuleValidator.java
│
├── calculator/
│   ├── InterestCalculator.java
│   ├── EMICalculator.java
│   ├── ChargeCalculator.java
│   └── TaxCalculator.java
│
├── transformer/
│   ├── CustomerTransformer.java
│   ├── AccountTransformer.java
│   ├── TransactionTransformer.java
│   └── ReportTransformer.java
│
└── rules/
    ├── InterestRuleEngine.java
    ├── ChargeRuleEngine.java
    ├── ApprovalRuleEngine.java
    └── RiskRuleEngine.java
```

#### Infrastructure Layer (`infrastructure/`)

```
infrastructure/
├── cache/
│   ├── CacheManager.java
│   ├── CustomerCacheProvider.java
│   └── AccountCacheProvider.java
│
├── messaging/
│   ├── MessagePublisher.java
│   ├── TransactionEventPublisher.java
│   ├── CustomerEventPublisher.java
│   └── MessageListener.java
│
├── audit/
│   ├── AuditService.java
│   ├── AuditServiceImpl.java
│   ├── AuditLog.java                   (Audit entity)
│   └── AuditRepository.java
│
├── security/
│   ├── JwtTokenProvider.java
│   ├── JwtTokenValidator.java
│   ├── PasswordEncoder.java
│   ├── SecurityUtil.java
│   └── UserDetailsServiceImpl.java
│
└── external/
    ├── PaymentGatewayClient.java
    ├── NotificationService.java
    ├── EmailService.java
    └── SMSService.java
```

#### Integration Layer (`integration/`)

```
integration/
├── payment/
│   ├── PaymentGatewayAdapter.java
│   ├── StripePaymentAdapter.java
│   ├── PayPalPaymentAdapter.java
│   └── PaymentGatewayResponse.java
│
└── gateway/
    ├── ExternalBankGateway.java
    └── CentralBankGateway.java
```

---

## Resource Organization

### Configuration Files

```
resources/
├── application.yml                   (Master configuration)
├── application-dev.yml               (Development profile)
├── application-prod.yml              (Production profile)
├── application-test.yml              (Test profile)
├── logback-spring.xml                (Logging configuration)
│
├── db/
│   ├── migration/
│   │   ├── V001__Initial_Schema.sql
│   │   ├── V002__Add_Audit_Tables.sql
│   │   ├── V003__Add_Indexes.sql
│   │   └── V004__Add_Security_Tables.sql
│   │
│   └── seed/
│       ├── master_data.sql           (Reference data)
│       └── test_data.sql
│
├── properties/
│   ├── messages.properties
│   ├── messages_en.properties
│   └── error-codes.properties
│
├── db/schema/
│   ├── customer_schema.sql
│   ├── account_schema.sql
│   └── transaction_schema.sql
│
├── static/
│   ├── css/
│   ├── js/
│   └── images/
│
└── templates/
    ├── email/
    │   ├── password-reset.html
    │   └── transaction-confirmation.html
    │
    └── reports/
        └── monthly-statement.html
```

---

## Test Organization

### Test Directory Structure

```
test/java/com/cbs/banking/
│
├── app/
│   ├── controller/
│   │   ├── CustomerControllerTest.java
│   │   ├── AccountControllerTest.java
│   │   └── PaymentControllerTest.java
│   │
│   ├── dto/
│   │   ├── CustomerMapperTest.java
│   │   └── AccountMapperTest.java
│   │
│   └── exception/
│       └── GlobalExceptionHandlerTest.java
│
├── business/
│   ├── service/
│   │   ├── CustomerServiceTest.java
│   │   ├── AccountServiceTest.java
│   │   ├── TransactionServiceTest.java
│   │   └── GLPostingServiceTest.java
│   │
│   ├── validator/
│   │   ├── CustomerValidatorTest.java
│   │   └── TransactionValidatorTest.java
│   │
│   └── calculator/
│       ├── InterestCalculatorTest.java
│       ├── EMICalculatorTest.java
│       └── ChargeCalculatorTest.java
│
├── domain/
│   └── repository/
│       ├── CustomerRepositoryTest.java
│       └── AccountRepositoryTest.java
│
├── integration/
│   ├── CustomerIntegrationTest.java     (End-to-end tests)
│   ├── AccountIntegrationTest.java
│   └── TransactionIntegrationTest.java
│
└── fixtures/
    ├── TestDataBuilder.java
    ├── CustomerTestDataBuilder.java
    ├── AccountTestDataBuilder.java
    └── TransactionTestDataBuilder.java

test/resources/
├── application-test.yml
├── logback-test.xml
├── test-data/
│   ├── customers.json
│   ├── accounts.json
│   └── transactions.json
└── mockdata/
    └── external-service-responses.json
```

---

## Naming Conventions

### Java Classes

| Class Type | Convention | Example |
|-----------|-----------|---------|
| **Controller** | `[Feature]Controller` | `CustomerController.java` |
| **Service** | `[Domain]Service` / `[Domain]ServiceImpl` | `CustomerService.java`, `CustomerServiceImpl.java` |
| **Repository** | `[Entity]Repository` | `CustomerRepository.java` |
| **Entity** | CamelCase, Singular | `Customer.java`, `Account.java` |
| **Enum** | CamelCase | `CustomerStatus.java`, `AccountType.java` |
| **DTO (Request)** | `[Entity][Operation]Request` | `CustomerCreateRequest.java`, `AccountUpdateRequest.java` |
| **DTO (Response)** | `[Entity]Response` | `CustomerResponse.java`, `AccountResponse.java` |
| **Validator** | `[Domain]Validator` | `CustomerValidator.java` |
| **Calculator** | `[Feature]Calculator` | `InterestCalculator.java` |
| **Transformer** | `[Domain]Transformer` | `CustomerTransformer.java` |
| **Mapper** | `[Entity]Mapper` | `CustomerMapper.java` |
| **Aspect** | `[Feature]Aspect` | `LoggingAspect.java` |
| **Config** | `[Feature]Config` | `SecurityConfig.java` |
| **Exception** | `[Feature]Exception` | `CustomerNotFoundException.java` |
| **Utility** | `[Feature]Util` / `[Feature]Utils` | `DateUtil.java` |
| **Test** | `[Class]Test` | `CustomerServiceTest.java` |

### Method Names

| Method Type | Convention | Example |
|-----------|-----------|---------|
| **Getter** | `get[Property]()` | `getCustomerId()`, `getAccountBalance()` |
| **Setter** | `set[Property]()` | `setCustomerName()` |
| **Boolean getter** | `is[Property]()` / `has[Property]()` | `isActive()`, `hasLoan()` |
| **Query method** | `find[Entity]By[Criteria]()` | `findCustomerByEmail()` |
| **Count method** | `count[Entity]By[Criteria]()` | `countAccountsByStatus()` |
| **Create method** | `create[Entity]()` | `createAccount()` |
| **Update method** | `update[Entity]()` | `updateCustomer()` |
| **Delete method** | `delete[Entity]()` | `deleteAccount()` |
| **Validate method** | `validate[Entity]()` | `validateTransaction()` |
| **Calculate method** | `calculate[Feature]()` | `calculateInterest()` |

### Variable Names

| Type | Convention | Example |
|------|-----------|---------|
| **Constants** | `UPPER_SNAKE_CASE` | `MAX_ACCOUNTS", "DEFAULT_RATE` |
| **Private instance** | camelCase with prefix | `customerId`, `accountBalance` |
| **Local variables** | camelCase | `totalAmount`, `isActive` |
| **Static variables** | camelCase with `static` keyword | `static int counter` |
| **Enums** | UPPER_SNAKE_CASE | `ACTIVE, INACTIVE, PENDING` |

### Package Naming

- Use reverse domain name + project structure
- Example: `com.cbs.banking.app.controller`
- Keep names meaningful and hierarchical

---

## File Organization Best Practices

### 1. **File Size Guidelines**

- **Controller**: Max 500 lines
- **Service**: Max 800 lines
- **Mapper/Transformer**: Max 300 lines
- **Validator**: Max 400 lines
- **Entity**: Max 600 lines (including relations)

**Action**: Split into multiple files if exceeding limits

### 2. **Class Structure Order**

```java
public class CustomerService {
    // 1. Class-level constants
    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);
    private static final String DEFAULT_SORT = "id";
    
    // 2. Class-level annotations & variables
    @Autowired
    private CustomerRepository repository;
    
    @Autowired
    private CustomerValidator validator;
    
    // 3. Constructor
    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }
    
    // 4. Public methods (by business importance)
    public CustomerResponse createCustomer(CustomerCreateRequest request) { }
    public CustomerResponse getCustomer(Long id) { }
    
    // 5. Private/helper methods
    private void validateCustomerData(Customer customer) { }
}
```

### 3. **Import Statement Organization**

```java
// 1. Java standard library imports
import java.util.*;
import java.time.LocalDateTime;

// 2. Third-party imports
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 3. Project imports
import com.cbs.banking.domain.entity.Customer;
import com.cbs.banking.app.dto.response.CustomerResponse;

// NO wildcard imports in production
```

### 4. **Comments & Documentation**

```java
/**
 * Creates a new customer in the system.
 * 
 * This method:
 * 1. Validates the customer data
 * 2. Checks for duplicate email
 * 3. Assigns a unique customer ID
 * 4. Persists to database
 * 
 * @param request the customer creation request containing customer details
 * @return {@link CustomerResponse} containing the newly created customer
 * @throws ValidationException if customer data is invalid
 * @throws DuplicateCustomerException if email already exists
 * 
 * @since 1.0.0
 */
public CustomerResponse createCustomer(CustomerCreateRequest request) {
    // Validate input
    validator.validateCustomer(request);
    
    // Check for duplicate
    if (repository.existsByEmail(request.getEmail())) {
        throw new DuplicateCustomerException("Email already exists");
    }
    
    // ... rest of implementation
}
```

### 5. **Resource File Organization**

- Keep configuration files at root of `resources/`
- Organize properties files by domain/module
- Maintain consistent naming: `application-[profile].yml`
- Organize migrations by version number
- Keep seed data separate from migration scripts

---

## Related Documentation

Reference the following detailed specifications:
- `TIER1_LAYER_SPECIFICATIONS.md` - Detailed layer specifications
- `TIER1_CODING_STANDARDS.md` - Comprehensive coding standards
- `TIER1_API_DESIGN_GUIDELINES.md` - RESTful API design guidelines


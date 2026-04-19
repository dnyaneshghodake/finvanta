# Tier-1 Grade CBS: Quick Reference & Visual Guidelines

## Module Dependency Map

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CORE CUSTOMER JOURNEY                                │
└─────────────────────────────────────────────────────────────────────────┘

    1. ONBOARDING
       │
       ├─► Customer Registration
       ├─► KYC Verification
       ├─► Document Upload
       └─► Account Activation
            │
            ▼
    2. ACCOUNT MANAGEMENT
       │
       ├─► Account Opening
       ├─► Account Linking
       ├─► Limit Setting
       └─► Status Management
            │
            ▼
    3. PRODUCTS & SERVICES
       │
       ├─► Deposits (Savings/FD/RD)
       ├─► Loans (Personal/Home/Auto)
       ├─► Payments (Transfers/Remittance)
       └─► Investment Products
            │
            ▼
    4. TRANSACTIONS
       │
       ├─► Fund Transfer
       ├─► Payment Processing
       ├─► Interest Accrual
       └─► Fee Application
            │
            ▼
    5. OPERATIONS & COMPLIANCE
       │
       ├─► GL Posting
       ├─► Reconciliation
       ├─► Risk Monitoring
       ├─► AML Screening
       └─► Regulatory Reporting
            │
            ▼
    6. ANALYTICS & REPORTING
       │
       ├─► Customer Reports
       ├─► Account Statements
       ├─► Management Reports
       └─► Analytics Dashboard


┌─────────────────────────────────────────────────────────────────────────┐
│                    MODULE COUPLING DIAGRAM                              │
└─────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────┐
                    │ CUSTOMER MANAGEMENT │
                    └──────────┬──────────┘
                               │
                ┌──────────────┼──────────────┐
                │              │              │
                ▼              ▼              ▼
        ┌──────────────┐  ┌─────────────┐  ┌──────────┐
        │   ACCOUNT    │  │   DEPOSIT   │  │  LOANS   │
        │MANAGEMENT   │  │MANAGEMENT  │  │MANAGEMENT│
        └──────┬───────┘  └──────┬──────┘  └─────┬────┘
               │                 │               │
               └────────┬────────┴───────────────┘
                        │
            ┌───────────▼──────────────┐
            │ TRANSACTION PROCESSING   │
            └───────────┬──────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
    ┌────────┐  ┌────────────┐  ┌──────────┐
    │PAYMENTS│  │ INTEREST   │  │ CHARGES  │
    │& FEES  │  │ ACCRUAL   │  │& FEES   │
    └────┬───┘  └────┬───────┘  └────┬────┘
         │           │               │
         └───────────┼───────────────┘
                     │
            ┌────────▼─────────┐
            │ GL POSTING &     │
            │ RECONCILIATION   │
            └────────┬─────────┘
                     │
         ┌───────────┼───────────┐
         │           │           │
         ▼           ▼           ▼
    ┌─────────┐ ┌──────────┐ ┌──────────┐
    │ AUDIT & │ │   RISK   │ │COMPLIANCE│
    │ LOGGING │ │MANAGEMENT│ │   &AML   │
    └─────────┘ └──────────┘ └──────────┘
         │           │           │
         └───────────┼───────────┘
                     │
            ┌────────▼─────────┐
            │ REPORTING &      │
            │ ANALYTICS       │
            └──────────────────┘
```

---

## Layer Interaction Flow

```
REQUEST FLOW (Top to Bottom):
┌──────────────────────────────────────────────────────────┐
│ 1. CLIENT REQUEST arrives at API                         │
│    POST /api/v1/accounts/transfer                        │
│    Header: Authorization: Bearer JWT_TOKEN              │
│    Body: {amount, fromAccount, toAccount}               │
└────────────────────┬─────────────────────────────────────┘
                     │ HTTP Request
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 2. API GATEWAY LAYER                                     │
│    ✓ Rate limiting check                                │
│    ✓ Request size validation                            │
│    ✓ Route to appropriate handler                       │
└────────────────────┬─────────────────────────────────────┘
                     │ Validated Request
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 3. CONTROLLER LAYER (@RestController)                   │
│    @PostMapping("/transfer")                            │
│    ✓ Extract @RequestBody                              │
│    ✓ Validate @Valid annotations                       │
│    ✓ Map to service call                               │
│    ✗ No business logic here                            │
└────────────────────┬─────────────────────────────────────┘
                     │ Validated DTO
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 4. FAÇADE LAYER                                          │
│    ✓ Orchestrate multiple services                      │
│    ✓ Manage transaction boundaries                      │
│    ✓ Coordinate workflows                               │
└────────────────────┬─────────────────────────────────────┘
                     │ Service Call
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 5. SERVICE LAYER (Business Logic)                       │
│    ✓ Validate business rules                            │
│    ✓ Execute core logic                                 │
│    ✓ Call repositories                                  │
│    ✓ Publish domain events                              │
└────────────────────┬─────────────────────────────────────┘
                     │ Query/Update
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 6. INTEGRATION LAYER                                    │
│    ✓ Cache checks                                       │
│    ✓ Event publishing                                   │
│    ✓ Audit logging                                      │
└────────────────────┬─────────────────────────────────────┘
                     │ Data Access
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 7. REPOSITORY LAYER (Data Access)                       │
│    ✓ Spring Data JPA automatic query                    │
│    ✓ L1 cache check (Session)                          │
│    ✓ L2 cache check (Redis)                            │
│    ✓ Execute database query                            │
│    ✓ Map result to entity                              │
└────────────────────┬─────────────────────────────────────┘
                     │ Result Set
                     ▼
┌──────────────────────────────────────────────────────────┐
│ 8. DATABASE LAYER                                        │
│    SQL execution (SQL Server/PostgreSQL)                │
│    Connection pooling (HikariCP)                        │
│    Transaction management                              │
└──────────────────────────────────────────────────────────┘

RESPONSE FLOW (Bottom to Top):
Entity → Mapper → DTO → Service → Controller → ApiResponse → Client
```

---

## Typical REST Endpoint Implementation

```
REQUIREMENT: Customer wants to transfer $1000 from Account A to Account B

ENDPOINT:
POST /api/v1/accounts/ACC001/transfer
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json

REQUEST BODY:
{
  "toAccountNumber": "ACC002",
  "amount": 1000.00,
  "currency": "USD",
  "narration": "Bill Payment",
  "transactionRef": "CUST001-2024-001"
}

LAYER-BY-LAYER PROCESSING:

1️⃣ CONTROLLER
@PostMapping("/{accountId}/transfer")
@PreAuthorize("hasPermission(#accountId, 'TRANSFER')")
public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
    @PathVariable String accountId,
    @Valid @RequestBody TransferRequest request
) {
    TransactionResponse response = transferService.transfer(accountId, request);
    return ResponseEntity.ok(ApiResponse.builder()
        .status("SUCCESS")
        .data(response)
        .build());
}

2️⃣ FAÇADE/ORCHESTRATOR
@Service
public class TransferFacade {
    public TransactionResponse transfer(String fromAccountId, TransferRequest req) {
        // Orchestrate multiple services
        accountService.validateTransferEligibility(fromAccountId);
        transactionService.executeTransfer(fromAccountId, req);
        glPostingService.postTransferEntries(...);
        eventPublisher.publishTransferEvent(...);
    }
}

3️⃣ SERVICE
@Service
@Transactional
public class TransactionService {
    public void executeTransfer(String fromId, TransferRequest req) {
        // 1. Validate
        validator.validateTransfer(fromId, req);
        
        // 2. Lock accounts (distributed mutex)
        accountLock.lock(fromId, req.getToAccountNumber());
        
        // 3. Get accounts
        Account from = accountRepo.findByNumber(fromId).orElseThrow();
        Account to = accountRepo.findByNumber(req.getToAccountNumber()).orElseThrow();
        
        // 4. Check balance
        if (from.getBalance() < req.getAmount()) {
            throw new InsufficientFundsException("...");
        }
        
        // 5. Create transaction record
        Transaction txn = new Transaction();
        txn.setFromAccount(from);
        txn.setToAccount(to);
        txn.setAmount(req.getAmount());
        txn.setStatus(TransactionStatus.PENDING);
        Transaction saved = txnRepo.save(txn);
        
        // 6. Update balances
        from.setBalance(from.getBalance() - req.getAmount());
        to.setBalance(to.getBalance() + req.getAmount());
        accountRepo.saveAll(List.of(from, to));
        
        // 7. Update transaction status
        saved.setStatus(TransactionStatus.SUCCESS);
        txnRepo.save(saved);
        
        // 8. Unlock accounts
        accountLock.unlock(fromId, req.getToAccountNumber());
        
        logger.info("Transfer completed: {} -> {}", fromId, to.getNumber());
    }
}

4️⃣ REPOSITORY
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByTransactionId(String txnId);
    List<Transaction> findByFromAccountOrderByCreatedDateDesc(Account account, Pageable page);
}
// Spring Data JPA automatically creates:
// - Queries from method names
// - Prepared statement caching
// - Connection pooling

5️⃣ DATABASE
// Final SQL executed:
INSERT INTO t_transactions (from_account, to_account, amount, status, created_date)
VALUES ('ACC001', 'ACC002', 1000.00, 'PENDING', NOW());

UPDATE t_accounts SET current_balance = current_balance - 1000.00
WHERE account_number = 'ACC001';

UPDATE t_accounts SET current_balance = current_balance + 1000.00
WHERE account_number = 'ACC002';

UPDATE t_transactions SET status = 'SUCCESS'
WHERE transaction_id = [generated_id];
```

---

## Class Instantiation & Dependency Injection

```
PROBLEM: How to create objects with proper initialization?

SOLUTION: Constructor Injection with Spring

@Service  // Registered as Spring Bean
@Slf4j
@Transactional
public class CustomerService {
    
    // Final fields = immutability
    private final CustomerRepository repository;
    private final CustomerValidator validator;
    private final CustomerMapper mapper;
    private final EventPublisher eventPublisher;
    private final CacheManager cacheManager;
    
    // Constructor Injection (Preferred)
    // Spring automatically injects dependencies
    public CustomerService(
            CustomerRepository repository,
            CustomerValidator validator,
            CustomerMapper mapper,
            EventPublisher eventPublisher,
            CacheManager cacheManager) {
        this.repository = repository;
        this.validator = validator;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.cacheManager = cacheManager;
    }
    
    // Methods can now use injected dependencies
    public CustomerResponse createCustomer(CustomerCreateRequest request) {
        validator.validate(request);  // Use validator
        Customer entity = mapper.toEntity(request);  // Use mapper
        Customer saved = repository.save(entity);  // Use repository
        eventPublisher.publish(new CustomerCreatedEvent(saved));  // Use publisher
        return mapper.toResponse(saved);
    }
}

HOW SPRING CREATES COMPONENTS:

1. Scan classpath for @Component, @Service, @Repository, @Controller
2. Find @Service public class CustomerService
3. Examine constructor: public CustomerService(CustomerRepository, ...)
4. Resolve each parameter type:
   - CustomerRepository? Found @Repository
   - CustomerValidator? Found @Component
   - ...
5. Create instances of dependencies first (recursive)
6. Invoke constructor with resolved instances
7. Register CustomerService bean in application context
8. When another service needs CustomerService:
   - Lookup from context
   - Return same instance (Singleton)

AUTOWIRING EXAMPLE:

@Controller
@RequestMapping("/customers")
public class CustomerController {
    
    private final CustomerService service;
    
    // Spring injects CustomerService
    public CustomerController(CustomerService service) {
        this.service = service;
    }
    
    @GetMapping("/{customerId}")
    public ResponseEntity<ApiResponse<CustomerResponse>> getCustomer(
            @PathVariable String customerId) {
        return ResponseEntity.ok(ApiResponse.builder()
            .data(service.getCustomer(customerId))  // service available here
            .build());
    }
}
```

---

## Database Query Optimization Examples

```
SLOW QUERY (AVOID):
❌ N+1 Problem - Per customer, loads all accounts separately
List<Customer> customers = repository.findAll();  // 1 query
for (Customer c : customers) {
    System.out.println(c.getAccounts().size());  // N queries (1 per customer)
}
Total: 1 + N queries

FAST QUERY (USE):
✓ JOIN FETCH - Single query with join
@Query("SELECT c FROM Customer c " +
       "LEFT JOIN FETCH c.accounts " +
       "WHERE c.status = 'ACTIVE'")
List<Customer> findActiveCustomersWithAccounts();
Total: 1 query

PAGINATION (REQUIRED):
❌ BAD - Load all into memory
List<Account> accounts = repository.findAll();

✓ GOOD - Paginate
@GetMapping
public Page<AccountResponse> getAccounts(
        @PageableDefault(size = 20, page = 0, sort = "id", direction = DESC)
        Pageable pageable) {
    return repository.findAll(pageable)
        .map(mapper::toResponse);
}

PROJECTION (OPTIMIZE):
❌ BAD - Select unused columns
SELECT * FROM t_customers;  // All columns loaded

✓ GOOD - Select needed columns only
@Query("SELECT c.id, c.name, c.email FROM Customer c WHERE c.status = 'ACTIVE'")
List<CustomerDTO> findActiveCustomers();

BATCHING (PERFORMANCE):
❌ BAD - Individual inserts
for (Account a : accounts) {
    repository.save(a);  // Each = 1 INSERT
}

✓ GOOD - Batch insert
repository.saveAll(accounts);  // 1 BATCH INSERT

INDEX USAGE:
❌ BAD - Function disables index
SELECT * FROM t_accounts WHERE YEAR(created_date) = 2024;

✓ GOOD - Index can be used
SELECT * FROM t_accounts WHERE created_date >= '2024-01-01' 
                            AND created_date < '2025-01-01';

CACHING (READ-HEAVY):
❌ Each request hits database
@GetMapping("/{id}")
public Account getAccount(@PathVariable Long id) {
    return repository.findById(id).orElseThrow();
}

✓ Cached results
@GetMapping("/{id}")
@Cacheable("accounts")
public Account getAccount(@PathVariable Long id) {
    return repository.findById(id).orElseThrow();
}
```

---

## Testing Structure

```
UNIT TEST (Test single class in isolation):
@ExtendWith(MockitoExtension.class)
public class CustomerServiceTest {
    
    @Mock
    private CustomerRepository repository;
    
    @InjectMocks
    private CustomerService service;
    
    @Test
    void testCreateCustomer_Success() {
        // Arrange
        CustomerCreateRequest request = new CustomerCreateRequest(...);
        Customer entity = new Customer(...);
        Customer saved = new Customer(...); saved.setId(1L);
        when(repository.save(any())).thenReturn(saved);
        
        // Act
        CustomerResponse response = service.createCustomer(request);
        
        // Assert
        assertThat(response.getCustomerId()).isEqualTo("CUST001");
        verify(repository).save(any());
    }
}

INTEGRATION TEST (Test service + database together):
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
public class CustomerServiceIntegrationTest {
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Container
    static PostgreSQLContainer<?>  db = new PostgreSQLContainer<>()
        .withDatabaseName("testdb");
    
    @Test
    void testCreateCustomerEndToEnd() {
        // Arrange
        CustomerCreateRequest request = new CustomerCreateRequest(...);
        
        // Act
        ResponseEntity<ApiResponse<CustomerResponse>> response = restTemplate
            .postForEntity("/api/v1/customers", request, ApiResponse.class);
        
        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}

CONTROLLER TEST (Test REST endpoint):
@WebMvcTest(CustomerController.class)
public class CustomerControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @MockBean
    private CustomerService service;
    
    @Test
    void testGetCustomer_Success() throws Exception {
        // Arrange
        when(service.getCustomer("CUST001"))
            .thenReturn(new CustomerResponse(...));
        
        // Act & Assert
        mockMvc.perform(get("/api/v1/customers/CUST001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.customerId").value("CUST001"));
    }
}
```

---

## Common Patterns Quick Reference

| Problem | Pattern | Example |
|---------|---------|---------|
| Create complex object | Builder | Customer.builder().name("John").email("j@ex.com").build() |
| Select implementation | Strategy | InterestCalculator calculator = new SimpleInterestCalculator(); |
| Single instance | Singleton | Spring @Bean (automatic singleton) |
| Object creation | Factory | CustomerFactory.createCustomer(type) |
| Handle events | Observer | @EventListener(CustomerCreatedEvent.class) |
| Extend behavior | Decorator | LoggingTransactionService wraps TransactionService |
| Isolate external | Adapter | PaymentGatewayAdapter wraps StripePayment |
| Simplify complex | Façade | TransferFacade orchestrates multiple services |
| Lazy loading | Proxy | Spring LazyInitializationProxy for entities |

---

This quick reference provides everyday guidance for implementing Tier-1 CBS systems.


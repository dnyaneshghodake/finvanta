# Finvanta CBS — Module-Wise Summary

**Architecture Pattern:** Multi-module monolith with microservices-ready boundaries  
**Enforcement Model:** Single TransactionEngine, per-module repositories, shared audit trail  
**Data Isolation:** Multi-tenant (TenantFilter), branch-scoped (SecurityUtil), account-level (JPA constraints)

---

## Module Architecture Overview

```
TransactionEngine (Orchestrator)
                ↓
┌─────────────────────────────────────────────────────────────┐
│  com.finvanta (13 Core Modules)                             │
│                                                              │
│  ├─ transaction/    → TransactionEngine (10-step pipeline)  │
│  ├─ accounting/     → GL posting, double-entry validation   │
│  ├─ service/        → Business logic (loan, deposit, etc.)  │
│  ├─ batch/          → EOD orchestration, parallel exec      │
│  ├─ charge/         → Charge kernel, GST calculation        │
│  ├─ domain/         → Entities, enums, rules (immutable)    │
│  ├─ repository/     → JPA repos, custom @Query methods      │
│  ├─ controller/     → Web controllers (model → view)        │
│  ├─ api/            → REST controllers (JSON responses)     │
│  ├─ config/         → Security, encryption, bootstrap       │
│  ├─ audit/          → Audit trail, chain-hash verification  │
│  ├─ util/           → Shared utilities (TenantContext, etc) │
│  └─ workflow/       → Approval workflows (maker-checker)    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
         ↓
  ┌──────────────────────────┐
  │  Spring Data JPA         │
  │  + Hibernate ORM         │
  │  + H2 (dev) / SQL Srv    │
  └──────────────────────────┘
         ↓
  ┌──────────────────────────┐
  │  Database Layer          │
  │  (Tenant/Branch/Branch)  │
  └──────────────────────────┘
```

---

## 1. **TRANSACTION MODULE** (`com.finvanta.transaction`)

### Purpose
Single enforcement point for all financial transactions. Implements the Tier-1 CBS 10-step pipeline per Finacle TRAN_POSTING / Temenos TRANSACTION framework.

### Key Classes

#### **TransactionEngine** (Primary Orchestrator)
```java
@Service
public class TransactionEngine {
    public TransactionResult execute(TransactionRequest request) {
        // 1. Idempotency check (cross-module duplicate detection)
        // 2. Business date validation (EOD-locked days blocked)
        // 3. Account validation (balance, status, GL codes)
        // 4. Double-entry posting (GL validation)
        // 5. GL balance update (pessimistic lock)
        // 6. Ledger entry (immutable, hash-chain)
        // 7. Batch running totals (atomic increment)
        // 8. Voucher generation (sequential per branch)
        // 9. Module callback (post-posting state update)
        // 10. Audit trail capture (before/after snapshot)
        return result;
    }
}
```

### Key Operations

| Operation | Request Type | Flow |
|-----------|--------------|------|
| **Deposit** | POST /api/v1/accounts/{accNo}/deposit | GL: 1100 (Bank) DR / 2010 (Deposits) CR |
| **Withdraw** | POST /api/v1/accounts/{accNo}/withdraw | GL: 2010 (Deposits) DR / 1100 (Bank) CR |
| **Transfer** | POST /api/v1/accounts/transfer | GL: 2010 DR (from) / 2010 CR (to) |
| **Loan Disburse** | POST /api/v1/loans/{accNo}/disburse | GL: 1001 (Loan Portfolio) DR / 1100 (Bank) CR |
| **EMI Repay** | POST /api/v1/loans/{accNo}/repay | GL: 1100 (Bank) DR / 1001 (Loan Portfolio) CR |
| **Interest Accrual** | Internal (EOD Step 3) | GL: 1002 (Interest Receivable) DR / 4001 (Interest Income) CR |
| **Charge Apply** | POST /charge/apply | GL: 1100 (Bank) DR / 4002 (Fee Income) CR; +GST posting |
| **Provision** | Internal (EOD Step 6) | GL: 5100 (Provision Expense) DR / 1003 (Provision Reserve) CR |

### Defensive Design Features

**Security Defense-in-Depth:**
- ThreadLocal `ENGINE_TOKEN` set by TransactionEngine.execute()
- AccountingService.postJournalEntry() checks token; rejects if null
- Direct GL calls from outside engine → SECURITY_VIOLATION exception

**Idempotency:**
- `idempotency_registry` table tracks completed requests by key
- Duplicate request returns cached result without re-posting
- Prevents double GL entries on network retry

**Audit Trail:**
- Every step captures before/after state
- Chain-hashed audit logs for tamper detection
- User, branch, module, timestamp captured

---

## 2. **ACCOUNTING MODULE** (`com.finvanta.accounting`)

### Purpose
GL posting, double-entry validation, chart of accounts management, balance tracking.

### Key Classes

#### **AccountingService**
```java
@Service
public class AccountingService {
    // Core operations
    public JournalEntry postJournalEntry(JournalLineRequest[] lines) {
        validateDoubleEntry(totalDebit, totalCredit); // DR must == CR
        LedgerEntry[] entries = updateGLBalance(lines, businessDate);
        return journalEntry; // Persisted, immutable
    }

    // Validation
    private void validateDoubleEntry(BigDecimal dr, BigDecimal cr) {
        if (dr.compareTo(cr) != 0) {
            throw new BusinessException("ACCOUNTING_IMBALANCE", "GL imbalance detected");
        }
    }
}
```

#### **GLMaster** (Chart of Accounts)
28 GL codes initialized at bootstrap:
- **1000-1099**: Assets (Loan Portfolio, Interest Receivable, etc.)
- **2000-2099**: Liabilities (Customer Deposits, GST Payable, etc.)
- **3000-3099**: Equity (Share Capital)
- **4000-4099**: Income (Interest, Fee Income)
- **5000-5099**: Expenses (Provision Expense, Write-off)

### Key Data Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| **GLMaster** | gl_master | Chart of accounts; active/inactive GL codes |
| **GLBranchBalance** | gl_branch_balances | Debit/credit balance per branch per GL code |
| **JournalEntry** | journal_entries | Posted GL entries (immutable) |
| **JournalEntryLine** | journal_entry_lines | Individual DR/CR lines within a posting |
| **LedgerEntry** | ledger_entries | Immutable hash-chained ledger |

### Design Patterns

**Compound Posting:**
- Multi-leg transactions (e.g., charge + GST) posted as single balanced entry
- All legs share same journal_entry_id; atomic all-or-nothing

**Pessimistic Locking:**
```java
@Query("SELECT g FROM GLMaster g WHERE g.tenantId = :tenantId "
    + "AND g.glCode = :glCode")
@Lock(LockModeType.PESSIMISTIC_WRITE)
GLMaster findAndLockGLMaster(...);
```

**Immutable Ledger:**
- Every GL posting creates LedgerEntry with hash of previous entry
- Chain breakage detected by AuditService.verifyChainIntegrity()

---

## 3. **LOAN ORIGINATION & MANAGEMENT MODULE** (`com.finvanta.service.impl.LoanAccountServiceImpl`)

### Purpose
Loan application → approval → disbursement → repayment tracking → closure.

### Key Classes

#### **LoanAccountService** (Interface)
```java
public interface LoanAccountService {
    // Lifecycle
    LoanAccount createAccountFromApplication(LoanApplication app, LocalDate businessDate);
    void disburseLoan(String accountNumber, LocalDate businessDate); // GL posting via engine
    LoanAccount processRepayment(String accountNumber, BigDecimal amount, LocalDate businessDate);
    
    // Interest & Charges
    LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate);
    LoanTransaction chargeFee(String accountNumber, BigDecimal fee, String feeType, LocalDate date);
    
    // Reporting
    LoanAccount getAccount(String accountNumber);
    List<LoanSchedule> getSchedule(Long accountId);
    int updateDaysPastDue(Long accountId, LocalDate businessDate);
}
```

### Loan Account Lifecycle

```
PENDING_APPROVAL
  ↓ (Checker approves LoanApplication)
APPROVED
  ↓ (Maker/Checker moves from Application to LoanAccount)
ACTIVE (after disbursement)
  ├─ Regular EMI payments → outstanding_balance decreases
  ├─ Overdue EMI (DPD > 0) scheduled, not paid
  ├─ NPA Classification (DPD > 30) → provision GL posting
  │  ├─ Standard (0-30 DPD)
  │  ├─ SubStandard (31-60 DPD) — 10% provision
  │  ├─ Doubtful (61-90 DPD) — 50% provision
  │  └─ Loss (90+ DPD) — 100% provision
  └─ Restructuring (extension/rate change) → new schedule
      
CLOSED (zero outstanding, final interest accrued)
```

### Key Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| **LoanApplication** | loan_applications | Application (PENDING → APPROVED) |
| **LoanAccount** | loan_accounts | Active loan account |
| **LoanSchedule** | loan_schedules | Amortization schedule (EMI details per month) |
| **LoanTransaction** | loan_transactions | Transaction history (interest, repay, charge) |
| **DisbursementSchedule** | disbursement_schedules | Tranche-wise disbursal tracking |

### Interest Accrual (Daily, EOD Step 3)

```java
@Transactional
public LoanTransaction applyInterestAccrual(String accountNumber, LocalDate accrualDate) {
    LoanAccount account = getAccount(accountNumber);
    
    // Resolve product-specific interest method (ACTUAL_365 for INR)
    BigDecimal dailyRate = annualRate / 365;
    BigDecimal daysCounted = daysBetween(lastAccrualDate, accrualDate);
    BigDecimal accruedInterest = outstanding * dailyRate * daysCounted;
    
    // Post to GL via TransactionEngine
    // GL: 1002 (Interest Receivable) DR / 4001 (Interest Income) CR
    TransactionResult result = transactionEngine.execute(
        new TransactionRequest()
            .amount(accruedInterest)
            .journalLines([
                new JournalLineRequest("1002", DEBIT, accruedInterest),
                new JournalLineRequest("4001", CREDIT, accruedInterest)
            ])
    );
    
    return new LoanTransaction(...)
        .setInterestComponent(accruedInterest)
        .setJournalEntryId(result.getJournalEntryId());
}
```

### Repayment Processing

```java
@Transactional
public LoanAccount processRepayment(
    String accountNumber, BigDecimal repayAmount, LocalDate paymentDate) {
    
    LoanAccount account = getAccount(accountNumber);
    List<LoanSchedule> unpaid = getUnpaidInstallments(account.getId());
    
    BigDecimal remaining = repayAmount;
    for (LoanSchedule installment : unpaid) {
        BigDecimal due = installment.getEmiAmount();
        
        if (remaining >= due) {
            // Full EMI paid
            remaining -= due;
            installment.setStatus(PAID).setPaidAmount(due).setPaidDate(paymentDate);
        } else if (remaining > 0) {
            // Partial payment
            installment.setStatus(PARTIALLY_PAID).setPaidAmount(remaining);
            remaining = 0;
        }
    }
    
    // GL: 1100 (Bank) DR / 1001 (Loan Portfolio) CR (principal portion only)
    transactionEngine.execute(new TransactionRequest()
        .amount(repayAmount) // Total including interest
        .journalLines([...])); // Split principal/interest
    
    account.setOutstandingPrincipal(...);
    account.setDaysPastDue(0); // Reset DPD after payment
    return accountRepository.save(account);
}
```

### EMI Auto-Debit (Standing Instruction)

At disbursement:
1. **LoanSchedule** generated (all EMIs for tenure)
2. **StandingInstruction** auto-created (LOAN_EMI type)
3. **EOD SI Execution** (Step 5) processes due SIs:
   - SI due date matches business date? → Auto-withdraw from CASA
   - Failed withdrawal? → Log error, retry next EOD
   - Successful? → GL posting (1100 DR / 2010 CR), schedule marked PAID

---

## 4. **CASA MODULE** (`com.finvanta.service.impl.DepositAccountServiceImpl`)

### Purpose
Savings/Current account opening, deposits, withdrawals, interest accrual, dormancy.

### Key Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| **DepositAccount** | deposit_accounts | CASA account (PENDING_ACTIVATION → ACTIVE → DORMANT → INOPERATIVE) |
| **DepositTransaction** | deposit_transactions | Transaction history (deposit, withdraw, transfer, interest) |

### Account Types

| Type | Interest | Min Balance | Use Case |
|------|----------|-------------|----------|
| **SAVINGS** | 3% p.a. (RBI floor) | ₹0 (PMJDY) or ₹10,000 | Individual savings |
| **CURRENT** | 0% | ₹10,000 | Business operations |
| **SAVINGS_NRI** | 3.5% | ₹10,000 | NRE/NRO per FEMA |
| **SAVINGS_PMJDY** | 4% (statutory) | ₹0 | Pradhan Mantri Jan Dhan |

### Account Lifecycle

```
PENDING_ACTIVATION (initial KYC, pending checker approval)
  ↓ (Checker activates + initial deposit if applicable)
ACTIVE (operational)
  ├─ Deposits/Withdrawals from transactional accounts
  ├─ Interest accrual (quarterly credit)
  ├─ Dormancy trigger: 2 years no transaction → DORMANT status
  │   (no further transactions unless reactivated)
  └─ Inoperative: 10 years + no transaction → INOPERATIVE
      
FROZEN (court order or regulatory) — no debits, credits allowed if !CREDIT_FREEZE
CLOSED (zero balance + customer request) — immutable record
```

### Interest Accrual (Quarterly, EOD Step 3)

```java
@Transactional
public DepositTransaction creditInterest(String accountNumber, LocalDate businessDate) {
    DepositAccount account = getAccount(accountNumber);
    
    // Interest calculated daily but credited quarterly (Mar 31, Jun 30, Sep 30, Dec 31)
    BigDecimal quarterlyInterest = account.getAccruedInterest(); // Sum of daily accruals
    
    // GL: 1100 (Bank Ops) DR / 2010 (Savings Deposits) CR
    TransactionResult result = transactionEngine.execute(
        new TransactionRequest()
            .amount(quarterlyInterest)
            .journalLines([
                new JournalLineRequest("1100", DEBIT, quarterlyInterest),
                new JournalLineRequest("2010", CREDIT, quarterlyInterest)
            ])
    );
    
    // TDS: If YTD interest > ₹40,000, deduct 10% per IT Act §194A
    BigDecimal tds = quarterlyInterest.compareTo(new BigDecimal("40000")) > 0 
        ? quarterlyInterest.multiply(new BigDecimal("0.10"))
        : BigDecimal.ZERO;
    
    if (tds.compareTo(BigDecimal.ZERO) > 0) {
        // Post TDS as separate transaction
        transactionEngine.execute(new TransactionRequest()
            .amount(tds)
            .journalLines([
                new JournalLineRequest("2010", DEBIT, tds), // Liability (TDS payable) DR
                new JournalLineRequest("5200", CREDIT, tds) // Tax Expense CR
            ])
        );
    }
    
    account.setAccruedInterest(BigDecimal.ZERO);
    return new DepositTransaction(...);
}
```

---

## 5. **BATCH (EOD) MODULE** (`com.finvanta.batch`)

### Purpose
End-of-Day processing: DPD update, interest accrual, NPA classification, SI execution, reconciliation.

### Key Classes

#### **EodOrchestrator** (Main Orchestrator)
```java
@Service
public class EodOrchestrator {
    public BatchJob executeEod(LocalDate businessDate) {
        // Step 1: Mark Overdue Installments (parallel)
        // Step 2: Update DPD (parallel)
        // Step 3: Interest Accrual (parallel)
        // Step 4: NPA Classification (parallel)
        // Step 5: Standing Instructions Execution (sequential, priority-based)
        // Step 6: Loan Provisioning (IRAC GL posting)
        // Step 7.1: SubLedger Reconciliation (subledger vs GL)
        // Step 7.2: Branch Balance Reconciliation
        // Step 8: Generate EOD Report (processed, failed, errors)
        // Finalize: Update calendar (EOD_COMPLETE), release day for new business
    }
}
```

### EOD Pipeline (10 Steps)

#### Step 1: Mark Overdue Installments
```
For each LoanSchedule where due_date < businessDate and status != PAID:
  Set status = OVERDUE
  Update loan_accounts.days_past_due = (businessDate - due_date).days
```

#### Step 2: Update DPD (Days Past Due)
```
For each LoanAccount where status = ACTIVE:
  oldest_unpaid = getOldestUnpaidInstallment()
  if oldest_unpaid.due_date < businessDate:
    daysOverdue = (businessDate - oldest_unpaid.due_date).days
    Set loan_accounts.days_past_due = daysOverdue
```

#### Step 3: Interest Accrual (Parallel)
```
For each LoanAccount (in parallel, each in REQUIRES_NEW transaction):
  if status IN (ACTIVE, OVERDUE, RESTRUCTURED):
    dailyInterest = outstanding * (annual_rate / 365) * (1 + dayCount)
    Post GL: 1002 (Interest Receivable) DR / 4001 (Interest Income) CR
    Set account.interest_component += dailyInterest
```

#### Step 4: NPA Classification (Parallel)
```
For each LoanAccount where days_past_due > 0:
  if daysOverdue <= 30:
    npa_category = STANDARD (0% provision)
  elif daysOverdue <= 60:
    npa_category = SUBSTANDARD (10% provision)
  elif daysOverdue <= 90:
    npa_category = DOUBTFUL (50% provision)
  elif daysOverdue > 90:
    npa_category = LOSS (100% provision)
  
  Set account.npa_category = npa_category
  Calculate provision = outstanding_principal * provision_percentage
  Post GL: 5100 (Provision Expense) DR / 1003 (Provision Reserve) CR
```

#### Step 5: Standing Instructions Execution (Sequential)
```
For each StandingInstruction where status = ACTIVE and nextExecutionDate == businessDate:
  
  // Priority order (Finacle SI_MASTER)
  1. LOAN_EMI (highest priority — bank's own recovery)
  2. RD_CONTRIBUTION
  3. SIP
  4. INTERNAL_TRANSFER
  5. UTILITY (lowest priority)

  For each SI (in priority order):
    Withdraw from source_account (GL: 2010 DR / 1100 CR if CASA, or 1001 DR if loan)
    
    if withdrawal succeeds:
      SI.status = EXECUTED
      SI.nextExecutionDate = nextMonthExecutionDate()
    else:
      SI.status = FAILED
      log error, send notification
      nextRetry = nextBusinessDate
```

#### Step 6: Loan Provisioning (GL Posting)
```
For each LoanAccount where npa_category != STANDARD:
  existing_provision = getCurrentProvision()
  required_provision = outstanding * provision_percentage
  delta = required_provision - existing_provision
  
  if delta > 0:
    Post GL: 5100 (Provision Expense) DR / 1003 (Provision Reserve) CR
  elif delta < 0:
    Post GL: 1003 (Provision Reserve) DR / 5100 (Provision Write-back) CR
```

#### Step 7.1: Subledger Reconciliation
```
For each subledger module (Loan, Deposit, InterBranchSettlement):
  subledger_balance = sum(all_account_balances)
  gl_balance = getGLNetBalance(gl_code)
  
  if subledger_balance != gl_balance:
    discrepancy detected, logged, reported
    EOD status might be PARTIALLY_COMPLETED
```

#### Step 8: Generate EOD Report
```
Report = {
  business_date: LocalDate,
  processed_count: N,
  failed_count: M,
  errors: [
    "Account ACC001: insufficient balance for SI",
    "Account ACC002: interest calculation failed (invalid rate)"
  ],
  status: COMPLETED | PARTIALLY_COMPLETED | FAILED,
  start_time: Timestamp,
  end_time: Timestamp,
  duration_ms: Long
}
```

### Parallelization for Performance

```java
ExecutorService executor = Executors.newFixedThreadPool(4 * Runtime.getRuntime().availableProcessors());

int[] results = processAccountsParallel(
    activeAccounts,
    executor,
    currentTenant,
    (account, businessDate) -> updateAccountDpd(account, businessDate) // Per-account task
);

// Results: [processed, failed]
```

**For 100K accounts on 16-core machine:**
- 64 threads available
- Each account task: ~10-20ms
- Total: 100,000 / 64 threads × 0.015s = ~23 seconds (est.)
- With I/O & GC: ~20 min end-to-end realistic

### Key Entities

| Entity | Table | Purpose |
|--------|-------|---------|
| **BatchJob** | batch_jobs | EOD job metadata (name, status, start, end, error) |
| **LoanBalanceSnapshot** | loan_balance_snapshots | Daily balance snapshot per account for audit trail |
| **DepositBalanceSnapshot** | daily_balance_snapshots | Daily balance snapshot for deposit accounts |

---

## 6. **CHARGE MODULE** (`com.finvanta.charge`, `com.finvanta.batch`)

### Purpose
Calculate fees (FLAT/PERCENTAGE/SLAB), apply GST, post GL, maintain charge configuration.

### Key Classes

#### **ChargeKernel** (Unified Charge Engine)
```java
@Service
public class ChargeKernel {
    @Transactional
    public ChargeResult applyCharge(
        String chargeCode, 
        BigDecimal baseAmount, 
        String productCode, 
        String accountNumber,
        LocalDate businessDate) {
        
        // 1. Fetch charge config
        ChargeConfig config = chargeRepository.findByTenantIdAndChargeCode(...);
        
        // 2. Calculate charge based on type
        BigDecimal charge = calculateChargeAmount(config.calculationType, baseAmount);
        
        // 3. Apply min/max bounds
        charge = charge.max(config.getMinCharge()).min(config.getMaxCharge());
        
        // 4. Calculate GST (18% standard)
        BigDecimal gst = charge.multiply(new BigDecimal("0.18")).setScale(2, HALF_UP);
        
        // 5. Post GL: 1100 (Bank) DR / 4002 (Fee Income) CR + GST posting
        TransactionResult result = transactionEngine.execute(
            new TransactionRequest()
                .amount(charge.add(gst))
                .journalLines([
                    new JournalLineRequest("1100", DEBIT, charge.add(gst)),
                    new JournalLineRequest("4002", CREDIT, charge),
                    new JournalLineRequest("2200", CREDIT, gst) // GST Payable
                ])
        );
        
        return new ChargeResult(chargeCode, charge, gst, charge.add(gst), result);
    }
}
```

### Charge Types

| Type | Formula | Example |
|------|---------|---------|
| **FLAT** | Fixed amount from config | ₹1,000 documentation charge |
| **PERCENTAGE** | base × percentage / 100 | 1% of ₹1,00,000 loan = ₹1,000 |
| **SLAB** | Lookup JSON slab config | 0-50K: 0.5%, 50K-1L: 1%, 1L+: 1.5% |

### Charge Configuration

| Field | Purpose | Example |
|-------|---------|---------|
| charge_code | Unique identifier | DOCUMENTATION_CHARGE |
| calculation_type | FLAT/PERCENTAGE/SLAB | PERCENTAGE |
| base_amount | Amount for FLAT | 1000.00 |
| percentage | Percentage for PERCENTAGE | 1.00 |
| min_charge | Floor | 500.00 |
| max_charge | Ceiling | 50000.00 |
| gst_applicable | Include GST? | TRUE |
| gst_rate | 18% standard, can vary | 18.00 |
| gl_charge_income | Income GL code | 4002 |
| gl_gst_payable | GST payable GL code | 2200 |

---

## 7. **DOMAIN MODULE** (`com.finvanta.domain`)

### Purpose
JPA entities, enums, business rules (immutable, shared across services).

### Sub-modules

#### **entity/** — JPA Entities
- LoanAccount, DepositAccount, Customer, Branch, Tenant
- Transaction records (LoanTransaction, DepositTransaction)
- Configuration (ProductMaster, ChargeConfig, etc.)
- System tables (BusinessCalendar, StandingInstruction, etc.)

#### **enums/** — Finacle-Standard Enums
```java
// Account Status
LoanStatus: PENDING_APPROVAL, APPROVED, ACTIVE, CLOSED, WRITTEN_OFF, RESTRUCTURED
DepositAccountStatus: PENDING_ACTIVATION, ACTIVE, DORMANT, INOPERATIVE, FROZEN, CLOSED

// Transaction Type
TransactionType: DEPOSIT, WITHDRAW, TRANSFER, INTEREST_ACCRUAL, CHARGE, REPAYMENT, WRITE_OFF

// NPA Classification (IRAC)
NPACategory: STANDARD, SUBSTANDARD, DOUBTFUL, LOSS

// Standing Instruction
SIStatus: PENDING_APPROVAL, ACTIVE, PAUSED, CANCELLED, FAILED
SIFrequency: MONTHLY, QUARTERLY, HALF_YEARLY, ANNUALLY

// Day Control
DayStatus: DAY_OPEN, EOD_RUNNING, EOD_COMPLETE

// User Role
UserRole: MAKER, CHECKER, ADMIN, AUDITOR
```

#### **rules/** — Business Rules
- **InterestCalculationRule** — Daily accrual (ACTUAL_365, ACTUAL_360)
- **IRACProvisioningRule** — NPA percentage → provision amount
- **PostingIntegrityGuard** — Validation before GL posting
- **ProductGLResolver** — Product type → GL code lookup

---

## 8. **REPOSITORY MODULE** (`com.finvanta.repository`)

### Purpose
JPA repositories, custom @Query methods, multi-tenant filtering.

### Patterns

#### Multi-Tenant Filtering
```java
@Repository
public interface LoanAccountRepository extends JpaRepository<LoanAccount, Long> {
    @Query("SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.accountNumber = :accNo")
    Optional<LoanAccount> findByTenantIdAndAccountNumber(@Param("tenantId") String tenantId, @Param("accNo") String accNo);

    @Query("SELECT la FROM LoanAccount la WHERE la.tenantId = :tenantId AND la.status IN ('ACTIVE', 'OVERDUE')")
    List<LoanAccount> findAllActiveAccounts(@Param("tenantId") String tenantId);
}
```

#### Pessimistic Locking (GL Balances)
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT g FROM GLMaster g WHERE g.tenantId = :tenantId AND g.glCode = :glCode")
Optional<GLMaster> findAndLockGlMaster(...);
```

#### Complex Searches
```java
@Query("SELECT ls FROM LoanSchedule ls WHERE ls.tenantId = :tenantId "
    + "AND ls.loanAccount.id = :accountId "
    + "AND ls.status NOT IN ('PAID', 'WAIVED') "
    + "ORDER BY ls.installmentNumber ASC")
List<LoanSchedule> findUnpaidInstallments(...);
```

---

## 9. **CONTROLLER MODULE** (`com.finvanta.controller`) + **API MODULE** (`com.finvanta.api`)

### Purpose
Web request routing, request validation, response formatting.

### Controllers

| Controller | Endpoints | Pattern |
|------------|-----------|---------|
| **DepositController** | /deposit/* | Web forms (JSP) |
| **LoanController** | /loan/* | Web forms (JSP) |
| **LoginController** | /login, /logout | Auth |
| **MfaLoginController** | /mfa/verify | 2FA |
| **AuditController** | /audit/logs, /audit/verify | Audit UI |
| **DepositAccountController** (REST) | /api/v1/accounts/* | JSON REST |
| **LoanAccountController** (REST) | /api/v1/loans/* | JSON REST |
| **LoanApplicationController** (REST) | /api/v1/loan-applications/* | JSON REST |

### Request/Response Pattern

```java
@RestController
@RequestMapping("/api/v1/accounts")
public class DepositAccountController {
    @PostMapping("/deposit/{accountNumber}")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
        @PathVariable String accountNumber,
        @Valid @RequestBody DepositRequest request) {
        
        // Service layer handles all logic
        DepositTransaction txn = depositService.deposit(
            accountNumber,
            request.amount(),
            businessDateService.getCurrentBusinessDate(),
            request.narration()
        );
        
        // Format response
        return ResponseEntity.ok(ApiResponse.success(
            new TransactionResponse(...),
            "Deposit successful"
        ));
    }
}

public record ApiResponse<T>(
    boolean success,
    T data,
    String message,
    long timestamp) {}
```

---

## 10. **CONFIG MODULE** (`com.finvanta.config`)

### Purpose
Spring Security, encryption, MFA setup, application bootstrap.

### Key Classes

#### **SecurityConfig**
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http.authorizeHttpRequests(authz -> authz
                .requestMatchers("/login", "/h2-console/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/deposit/**", "/loan/**").hasAnyRole("MAKER", "CHECKER", "ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(new MfaAuthenticationSuccessHandler())
                .failureUrl("/login?error")
            )
            .logout(logout -> logout.logoutUrl("/logout"))
            .csrf().disable() // H2 console access
            .headers().frameOptions().disable();
        
        return http.build();
    }
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of(
            "noop", new NoOpPasswordEncoder(), // DEV ONLY
            "bcrypt", new BCryptPasswordEncoder()
        ));
    }
}
```

#### **CbsBootstrapInitializer** (Day Zero)
```java
@Component
@Order(100)
public class CbsBootstrapInitializer implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 1. Create tenant if missing
        // 2. Create head office branch
        // 3. Bootstrap GL chart (28 accounts)
        // 4. Create admin user
        // 5. Create business calendar & open today
        // 6. Load default charge config
    }
}
```

#### **PiiEncryptionConverter**
```java
@Converter(autoApply = true)
public class PiiEncryptionConverter implements AttributeConverter<String, String> {
    
    @Override
    public String convertToDatabaseColumn(String plaintext) {
        // AES-256 encrypt Aadhaar, PAN, account numbers
        return encryptionService.encrypt(plaintext);
    }
    
    @Override
    public String convertToEntityAttribute(String encrypted) {
        // Decrypt when loading from DB
        return encryptionService.decrypt(encrypted);
    }
}
```

#### **MfaSecretEncryptor**
```java
// Encrypts TOTP secret before storing in app_users.mfa_secret
// Decrypts at login for TOTP verification
```

---

## 11. **AUDIT MODULE** (`com.finvanta.audit`)

### Purpose
Immutable audit trail, chain-hash verification, tamper detection.

### Audit Lifecycle

```
1. Business event occurs (transaction, account change, approval)
   ↓
2. AuditService.logEvent() called (REQUIRES_NEW transaction)
   ├─ Capture before/after state (JSON snapshot)
   ├─ Calculate SHA-256 hash(before + after + previous_hash)
   ├─ Persist to audit_logs table (append-only)
   └─ Return AuditLog entity with hash chain
   
3. Audit chain verification (on demand)
   ├─ Walk all audit logs in order
   ├─ Recompute hash for each, verify against stored hash
   └─ Detect any modification (hash mismatch = tampering)
```

### Audit Log Fields

```java
@Entity
@Table(name = "audit_logs")
public class AuditLog {
    Long id;              // Sequence number (immutable)
    String tenantId;      // Multi-tenant isolation
    Long branchId;        // Branch attribution
    String entityType;    // Entity class name (LoanAccount, DepositAccount, etc.)
    Long entityId;        // Entity primary key
    String action;        // EVENT_NAME (e.g., LOAN_DISBURSED, ACCOUNT_OPENED)
    String beforeSnapshot; // JSON of state before change
    String afterSnapshot;  // JSON of state after change
    String performedBy;   // Username of user who initiated action
    String module;        // Module context (LOAN, DEPOSIT, BATCH, BILLING)
    String description;   // Human-readable description
    LocalDateTime eventTimestamp;
    String payloadHash;   // SHA-256(before + after + module)
    String chainHash;     // SHA-256(payloadHash + previous_chainHash)
    String previousChainHash; // Reference to prior record (for chain walk)
}
```

---

## 12. **UTILITY MODULE** (`com.finvanta.util`)

### Key Utilities

#### **TenantContext** (ThreadLocal)
```java
public class TenantContext {
    private static final ThreadLocal<String> context = new ThreadLocal<>();
    
    public static void setCurrentTenant(String tenantId) {
        context.set(tenantId);
    }
    
    public static String getCurrentTenant() {
        String tenant = context.get();
        if (tenant == null) throw new SecurityException("Tenant context missing");
        return tenant;
    }
}
```

#### **SecurityUtil** (CurrentUser Info)
```java
public class SecurityUtil {
    public static String getCurrentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
    
    public static Long getCurrentUserBranchId() {
        // From BranchAwareUserDetails principal
    }
    
    public static String getCurrentUserBranchCode() {
        // From BranchAwareUserDetails principal
    }
}
```

#### **BusinessException** (Standardized Errors)
```java
public class BusinessException extends RuntimeException {
    String errorCode; // Machine-readable (ACCOUNT_NOT_FOUND, GL_IMBALANCE)
    String message;   // Human-readable
    
    // Caught by @ExceptionHandler, returned as ApiResponse.error()
}
```

---

## 13. **WORKFLOW MODULE** (`com.finvanta.workflow`)

### Purpose
Maker-checker approval workflow, state transitions.

### Workflow Pattern

```
PENDING_APPROVAL
  ├─ MAKER submits (application, account freeze request, etc.)
  └─ Stored in approval_workflows table with status = PENDING
       ↓
  CHECKER views pending items
  ├─ Approves → status = APPROVED, effective_date set
  └─ Rejects → status = REJECTED, reason captured
       ↓
  Post-approval:
  ├─ Account creation, disbursement, freeze, etc. triggered
  └─ Audit trail logged with approver name & timestamp
```

### Example: Loan Disbursement Workflow

```
1. MAKER creates LoanApplication (status = PENDING_VERIFICATION)
2. CHECKER verifies (document check, credit scoring) → VERIFIED
3. CHECKER approves → APPROVED (stored in approval_workflows)
4. MAKER creates LoanAccount from Application → ACTIVE
5. MAKER requests disbursement → DisbursementSchedule with status = PENDING_APPROVAL
6. CHECKER approves disbursement → executes GL posting (1001 DR / 1100 CR)
7. LoanAccount.status = ACTIVE, LoanSchedule auto-generated
8. StandingInstruction auto-created for EMI auto-debit
```

---

## Module Dependencies Graph

```
TransactionEngine
    ├─ AccountingService (GL posting)
    ├─ LoanAccountService (loan lifecycle)
    ├─ DepositAccountService (CASA lifecycle)
    ├─ ChargeKernel (fee calculation)
    ├─ StandingInstructionService (SI execution)
    └─ AuditService (immutable trail)

EodOrchestrator
    ├─ LoanAccountService (DPD, interest, NPA)
    ├─ DepositAccountService (interest accrual)
    ├─ StandingInstructionService (SI execute)
    ├─ SubledgerReconciliationService (verification)
    └─ TransactionEngine (GL postings per step)

Spring Security
    ├─ MfaService (TOTP verification)
    ├─ BranchAwareUserDetails (branch context)
    └─ TenantFilter (multi-tenant isolation)

Audit Service
    └─ AuditLogRepository (immutable persistence)

Config Layer
    ├─ CbsBootstrapInitializer (day-zero setup)
    ├─ PiiEncryptionConverter (at-rest encryption)
    └─ CbsPropertyDecryptor (key management)
```

---

## Summary: Module Interaction Pattern

```
User Request (HTTP POST /api/v1/accounts/deposit/{accNo})
        ↓
Controller (thin orchestration layer)
        ↓
Service Layer (business logic)
        ↓
TransactionEngine.execute() ← SINGLE ENFORCEMENT POINT
        ├─ 10-step validation pipeline
        ├─ GL posting via AccountingService
        ├─ Module callback
        └─ Audit trail capture
             ↓
Repository Layer (JPA persistence)
        ├─ Multi-tenant filtering (@Filter)
        ├─ Pessimistic locks (GL balance)
        └─ Immutable audit logs
             ↓
Database (H2 dev / SQL Server prod)
```

---

**Last Updated:** April 2026  
**Maintained By:** Senior Core Banking Architect


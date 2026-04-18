# Finvanta CBS — Architecture & System Design

**Architecture Style:** Layered monolith with microservices-ready boundaries  
**Design Model:** Domain-Driven Design (DDD) with Finacle/Temenos reference implementation  
**Concurrency Model:** ACID transactions with optimistic reads, pessimistic GL locks  
**Scalability Model:** Horizontal scaling via stateless Spring Boot + database sharding (future)  
**Deployment Model:** Docker/K8s-ready WAR on Tomcat 10 + SQL Server

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                             │
├─────────────────────────────────────────────────────────────────┤
│  ├─ Web UI (JSP + Bootstrap)                                    │
│  ├─ REST API (JSON, JWT auth)                                  │
│  └─ Mobile/Fintech Integrations (NPCI Account Aggregator ready)│
└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                     SECURITY LAYER                              │
├─────────────────────────────────────────────────────────────────┤
│  ├─ Spring Security (username/password + TOTP 2FA)             │
│  ├─ TenantFilter (multi-tenant isolation via ThreadLocal)       │
│  ├─ BranchAwareUserDetails (branch-scoped authorization)        │
│  └─ PII Encryption (AES-256 at rest, TLS in-transit)            │
└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                  PRESENTATION LAYER (Controllers)               │
├─────────────────────────────────────────────────────────────────┤
│  ├─ Web Controllers (@Controller + JSP views)                  │
│  ├─ REST Controllers (@RestController + JSON)                  │
│  └─ Thin orchestration (delegates to services)                 │
└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                   SERVICE LAYER (Business Logic)                │
├─────────────────────────────────────────────────────────────────┤
│  ├─ LoanAccountService (loan lifecycle)                        │
│  ├─ DepositAccountService (CASA lifecycle)                     │
│  ├─ TransactionEngine (SINGLE ENFORCEMENT POINT)               │
│  ├─ EodOrchestrator (batch processing)                         │
│  ├─ MfaService (TOTP generation/verification)                  │
│  ├─ StandingInstructionService (recurring payments)            │
│  ├─ ChargeKernel (fee calculation + GST)                       │
│  ├─ AuditService (immutable audit trail)                       │
│  └─ BusinessCalendarService (day control per Finacle DAYCTRL)  │
└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                    PERSISTENCE LAYER (JPA)                      │
├─────────────────────────────────────────────────────────────────┤
│  ├─ Repositories (JPA + custom @Query)                         │
│  ├─ Multi-tenant filtering (@Filter at query level)            │
│  ├─ Pessimistic locking (GL balance updates)                   │
│  ├─ Immutable entities (audit logs, ledger entries)            │
│  └─ Transaction boundaries (@Transactional with isolation)     │
└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│                    DATABASE LAYER                               │
├─────────────────────────────────────────────────────────────────┤
│  ├─ H2 (dev profile, in-memory, auto-schema)                   │
│  ├─ SQL Server (prod, TLS encryption, connection pooling)      │
│  ├─ Transaction logs (for point-in-time recovery)              │
│  └─ Indexes optimized for queries + pessimistic locks          │
└─────────────────────────────────────────────────────────────────┘
```

---

## Core Design Principles

### 1. Single Enforcement Point (TransactionEngine)

**Problem:** Multiple entry points mean inconsistent GL posting, voucher generation, audit trails.

**Solution:** All financial transactions route through `TransactionEngine.execute()`.

```
User Request
    ↓
Controller.deposit()
    ↓
DepositAccountService.deposit()
    ↓
TransactionEngine.execute() ← ENFORCES 10-STEP PIPELINE
    ├─ 1. Idempotency registry check
    ├─ 2. Business date validation
    ├─ 3. Account validation
    ├─ 4. Double-entry posting
    ├─ 5. GL balance update (pessimistic lock)
    ├─ 6. Ledger entry (hash chain)
    ├─ 7. Batch totals update
    ├─ 8. Voucher generation
    ├─ 9. Module callback
    └─ 10. Audit trail
         ↓
    Returns: TransactionResult (voucher, journal_id, success/failure)
         ↓
    Module receives result, updates account state
         ↓
    Response sent to user
```

**Defense-in-Depth:** Direct calls to `AccountingService.postJournalEntry()` are rejected unless `ThreadLocal ENGINE_TOKEN` is set by the engine.

```java
private JournalEntry postJournalEntryInternal(...) {
    if (ENGINE_TOKEN.get() == null) {
        throw new BusinessException(
            "ENGINE_CONTEXT_REQUIRED",
            "GL posting must route through TransactionEngine.execute(). "
            + "Direct calls are prohibited."
        );
    }
}
```

### 2. Idempotency for Network Resilience

**Problem:** Network timeout during GL posting → request retried → duplicate entry.

**Solution:** `idempotency_registry` table caches completed transaction results.

```
Deposit Request with idempotencyKey = "DEP-2026-04-19-001"
    ↓
TransactionEngine.execute()
    ├─ Check idempotency_registry for key
    │   ├─ Found? → return cached result (no re-posting)
    │   └─ Not found? → proceed with 10-step pipeline
    ├─ Post GL
    ├─ Cache result in registry: (key, result, timestamp)
    ├─ Return result
    └─ Response sent (even on network timeout, client can retry safely)
         ↓
    Client retries with same key
    ├─ Check registry → found → return cached result immediately
    └─ No duplicate GL posting
```

### 3. Immutable Ledger with Chain-Hash

**Problem:** Auditors must detect any tampering (accidental or malicious GL entries).

**Solution:** Each `LedgerEntry` includes SHA-256 hash of all prior entries.

```
LedgerEntry #1:
  gl_code: 1100 (Bank), debit: ₹100, credit: 0
  previousHash: "GENESIS"
  hash: SHA-256(1100|100|0|GENESIS)

LedgerEntry #2:
  gl_code: 2010 (Deposits), debit: 0, credit: ₹100
  previousHash: "hash of #1"
  hash: SHA-256(2010|0|100|hash of #1)

LedgerEntry #3:
  identical to #2 (edit attempt)
  previousHash: "hash of #1" (MISMATCH — should be hash of #2)
  Verification fails → Tampering detected
```

Chain verification (linear scan):
```java
boolean verifyChainIntegrity(String tenantId) {
    String expectedPreviousHash = "GENESIS";
    
    for (LedgerEntry entry : ledgerRepository.findAllByTenantIdOrderByIdAsc(tenantId)) {
        String computedHash = SHA256(entry);
        
        if (!computedHash.equals(entry.getHash())) {
            throw new TamperingDetectedException("Ledger entry #" + entry.getId() + " tampered");
        }
        
        if (!entry.getPreviousHash().equals(expectedPreviousHash)) {
            throw new TamperingDetectedException("Chain broken at entry #" + entry.getId());
        }
        
        expectedPreviousHash = entry.getHash();
    }
    
    return true; // Chain verified
}
```

### 4. Pessimistic Locking for GL Balance Consistency

**Problem:** Concurrent transactions on same GL code (e.g., 1100 Bank) cause phantom updates.

**Scenario:**
```
Thread 1: Read GL 1100 balance = ₹1,000,000
Thread 2: Read GL 1100 balance = ₹1,000,000
Thread 1: Debit ₹100,000 → write ₹900,000
Thread 2: Debit ₹50,000 → write ₹950,000 (overwrites Thread 1's update)
Result: Only ₹50K debited, ₹100K lost
```

**Solution:** Pessimistic lock via `SELECT ... FOR UPDATE`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT g FROM GLMaster g WHERE g.tenantId = :tenantId AND g.glCode = :glCode")
Optional<GLMaster> findAndLockGlMaster(@Param("tenantId") String tenantId, @Param("glCode") String glCode);
```

**Execution:**
```
Thread 1: SELECT * FROM gl_master WHERE tenant_id=1 AND gl_code='1100' FOR UPDATE
         → Acquires exclusive lock, Row is locked for other writers
         → Balance = ₹1,000,000
         → Debit ₹100,000 → Update to ₹900,000
         → Commit → Release lock

Thread 2: SELECT * FROM gl_master WHERE tenant_id=1 AND gl_code='1100' FOR UPDATE
         → Waits for Thread 1's lock to release
         → Acquires lock
         → Balance = ₹900,000 (Thread 1's update is visible)
         → Debit ₹50,000 → Update to ₹850,000
         → Commit → Release lock

Result: Balance correctly = ₹850,000 (both debits applied)
```

### 5. Per-Account Transaction Boundaries

**Problem:** EOD batch on 100K accounts — one account's interest calc failure rolls back all.

**Solution:** Each account processed in separate `REQUIRES_NEW` transaction via self-proxy.

```java
@Service
public class EodOrchestrator {
    
    @Lazy // Self-proxy to bypass Spring AOP on same-class calls
    private EodOrchestrator self;
    
    // NOT @Transactional — each account gets its own boundary
    public BatchJob executeEod(LocalDate businessDate) {
        List<LoanAccount> accounts = accountRepository.findAllActiveAccounts(tenantId);
        
        for (LoanAccount account : accounts) {
            try {
                self.updateAccountDpd(account, businessDate); // REQUIRES_NEW TX
                processedCount++;
            } catch (Exception e) {
                log.error("Account {} failed: {}", account.getAccountNumber(), e.getMessage());
                failedCount++;
                // Continue with next account — don't roll back all
            }
        }
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAccountDpd(LoanAccount account, LocalDate businessDate) {
        // This TX is independent
        // If it fails, only this account's EOD processing is rolled back
    }
}
```

**For 100K accounts with 5% failure rate:**
- 95K accounts × 1 success TX each = 95K successful updates persisted
- 5K accounts × 1 failed TX each = no update, logged, retry next EOD
- **Result:** EOD completes with PARTIALLY_COMPLETED status; operations investigate failed accounts

### 6. Business Date vs System Date

**Problem:** When is "today"? System clock is 23:59:59. Is it still business date D, or already D+1?

**Solution:** `BusinessCalendar.businessDate` is the authoritative "day".

```
RBI Bank (IST = Asia/Kolkata):
  23:55 on 2026-04-19 (Sat) — systemDate = 2026-04-19, businessDate = 2026-04-19
  23:56 EOD starts
  23:57 EOD processing for 2026-04-19
  23:59 EOD finishes
  00:01 (2026-04-20, Sun) — systemDate = 2026-04-20, businessDate still = 2026-04-19
       (because 2026-04-20 is Sunday — non-working day)
       
      TransactionEngine checks: businessCalendar.eodComplete = true
      → Rejects NEW deposits/withdrawals for 2026-04-19
      → Accepts only reversals & adjustments (CHECKER-only)
      
  09:00 (2026-04-20, Mon) — Operations runs "advance day" batch
       → businessDate = 2026-04-21 (skips weekends)
       → dayStatus = DAY_OPEN
       → eodComplete = false
       → New transactions accepted for 2026-04-21
```

### 7. Maker-Checker Workflow

**Problem:** One operator could embezzle by creating a fake account, approving their own loan, disbursing to themselves.

**Solution:** Dual authorization via separate sessions.

```
MAKER (Loan Officer):
  ├─ Creates LoanApplication (status = PENDING_VERIFICATION)
  ├─ Completes on own laptop
  └─ Logs out

CHECKER (Approval Officer):
  ├─ Logs in from own session (separate machine, separate credentials)
  ├─ Views pending applications
  ├─ Verifies documentation, credit score, collateral
  ├─ Approves → approval_workflows.status = APPROVED
  └─ Logs out

MAKER (again):
  ├─ Views approved applications
  ├─ Creates LoanAccount
  ├─ Requests disbursement
  └─ Submits for checker sign-off

CHECKER (again):
  ├─ Reviews disbursement request
  ├─ Approves → executes GL posting
  ├─ Loan is active with EMI auto-debit set
  └─ Audit trail captures both signatures
```

### 8. MFA (TOTP) per RFC 6238

**Problem:** Username/password alone insufficient for high-privilege users; passwords can be phished.

**Solution:** Second factor = time-based one-time password (TOTP).

```
Enrollment:
  1. Admin enables MFA for CHECKER account
  2. CHECKER scans QR code in Google Authenticator / Authy
     (QR = "otpauth://totp/finvanta:checker1@bank.com?secret=ABCD...&issuer=Finvanta")
  3. CHECKER enters 6-digit code to verify enrollment

Login:
  1. CHECKER enters username + password → authenticated
  2. Spring redirects to /mfa/verify page
  3. CHECKER enters 6-digit code (from authenticator app)
  4. MfaService.verifyLoginTotp() checks:
     ├─ Compute TOTP for current 30-sec time step
     ├─ Also check ±1 step (30-60 sec tolerance per RFC 6238)
     ├─ Verify code matches & time step > last verified (replay protection)
     └─ If valid → update last_totp_time_step, allow login
  5. Session marked MFA_VERIFIED
  
Replay Protection:
  ├─ Attacker intercepts TOTP code "123456" at 14:00:30
  ├─ Attacker tries to reuse "123456" at 14:00:40
  ├─ Time step changed (30-sec intervals)
  ├─ Code invalid for new time step
  └─ Rejection
  
Brute Force Protection:
  ├─ Max 5 failed TOTP attempts
  ├─ After 5th failure → session.invalidate() → force re-login with password
  ├─ User must re-enter password + new TOTP code from next 30-sec interval
```

### 9. Multi-Tenant Isolation at Query Level

**Problem:** Missing tenant filter causes data leakage across banks.

**Solution:** Hibernate `@Filter` on all JPA entities; queries fail if tenant context is null.

```java
@Entity
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class LoanAccount {
    @Column(name = "tenant_id", nullable = false)
    String tenantId;
    
    @Column(name = "account_number")
    String accountNumber;
    
    // All other fields
}

// Repository query:
@Query("SELECT la FROM LoanAccount la WHERE la.accountNumber = :accNo")
Optional<LoanAccount> findByAccountNumber(...); // Tenant filter auto-applied

// Will translate to:
// SELECT * FROM loan_accounts 
// WHERE account_number = :accNo AND tenant_id = :tenantId (auto-added)
```

**Enforcement:**
```java
@Configuration
public class TenantFilterConfig {
    @Bean
    public FilterRegistrationBean<TenantFilter> tenantFilter() {
        FilterRegistrationBean<TenantFilter> bean = new FilterRegistrationBean<>(new TenantFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}

public class TenantFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        String tenantId = extractTenantFromRequest(httpReq);
        
        if (tenantId == null) {
            throw new SecurityException("Tenant context missing");
        }
        
        TenantContext.setCurrentTenant(tenantId);
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
```

If query is executed without tenant context:
```
Exception in thread "main": 
  org.hibernate.HibernateException: 
    Expected named parameter for filter 'tenantFilter': tenantId
    
→ Application crashes; no accidental data leakage
```

### 10. Audit Chain-Hash Detection

**Problem:** Need to prove audit trail hasn't been tampered.

**Solution:** Each `AuditLog` includes SHA-256 hash of previous record + payload.

```java
public class AuditLog {
    Long id;
    String entityType;
    Long entityId;
    String beforeSnapshot;
    String afterSnapshot;
    String payloadHash;      // SHA-256(beforeSnapshot + afterSnapshot + module + action)
    String chainHash;        // SHA-256(payloadHash + previousChainHash)
    String previousChainHash;
}

// Verification:
@Transactional(readOnly = true, isolation = REPEATABLE_READ)
public boolean verifyChainIntegrity(String tenantId) {
    String expectedPreviousHash = "GENESIS";
    
    for (AuditLog log : auditLogRepository.findAllByTenantIdOrderByIdAsc(tenantId)) {
        String computedPayloadHash = SHA256(log.beforeSnapshot + log.afterSnapshot + log.module);
        
        // Verify payload integrity
        if (!computedPayloadHash.equals(log.payloadHash)) {
            log.error("Audit log #{} TAMPERED: payload hash mismatch", log.id);
            return false;
        }
        
        // Verify chain linkage
        String computedChainHash = SHA256(computedPayloadHash + expectedPreviousHash);
        if (!computedChainHash.equals(log.chainHash)) {
            log.error("Audit log #{} TAMPERED: chain hash mismatch", log.id);
            return false;
        }
        
        expectedPreviousHash = log.chainHash;
    }
    
    log.info("Audit chain verified: {} records, integrity intact", count);
    return true;
}
```

---

## Transaction Processing Pipeline (10 Steps)

### Step-by-Step Execution Flow for a Deposit

```
User deposits ₹10,000 to account ACC-2026-001

TransactionEngine.execute(TransactionRequest {
    accountReference: "ACC-2026-001",
    transactionType: DEPOSIT,
    amount: 10000.00,
    journalLines: [
        JournalLineRequest(glCode="1100", debit=10000, credit=0),
        JournalLineRequest(glCode="2010", debit=0, credit=10000)
    ]
})

STEP 1: IDEMPOTENCY CHECK
└─ Query idempotency_registry for idempotencyKey
   ├─ Found matching key with same tenant/account/amount/txnType?
   │  └─ YES → Return cached TransactionResult, exit (no re-posting)
   └─ NOT found → Continue to Step 2

STEP 2: BUSINESS DATE VALIDATION
└─ Query BusinessCalendar for current business_date
   ├─ dayStatus == DAY_OPEN? → Continue
   ├─ dayStatus == EOD_RUNNING? → Reject (day locked, only reversals allowed)
   └─ dayStatus == EOD_COMPLETE && eodComplete == true? → Reject

STEP 3: ACCOUNT VALIDATION
└─ DepositAccount account = depositRepository.findByTenantIdAndAccountNumber(tenantId, "ACC-2026-001")
   ├─ Account exists? NO → Throw ACCOUNT_NOT_FOUND
   ├─ Account.status == ACTIVE? NO (e.g., FROZEN) → Throw ACCOUNT_FROZEN
   ├─ Sufficient available balance for withdrawal? (if withdraw) YES → Continue
   └─ Account branch matches user's branch? (or user is ADMIN) YES → Continue

STEP 4: DOUBLE-ENTRY POSTING VALIDATION
└─ For each journal line:
   ├─ GL code active/postable? NO → Throw GL_NOT_FOUND
   ├─ Debit amount > 0? Mark as DR side
   └─ Credit amount > 0? Mark as CR side
   
   Aggregate totals:
   ├─ totalDebit = 10000.00
   ├─ totalCredit = 10000.00
   ├─ totalDebit == totalCredit? YES → Continue
   └─ Imbalanced? NO → Throw ACCOUNTING_IMBALANCE

STEP 5: GL BALANCE UPDATE (Pessimistic Lock)
└─ For each journal line's GL code:
   ├─ SELECT * FROM gl_master WHERE gl_code='1100' FOR UPDATE (locks row)
   │  ├─ Current debit_balance = ₹5,000,000
   │  ├─ Update to 5,000,000 + 10,000 = ₹5,010,000
   │  └─ Commit
   ├─ SELECT * FROM gl_master WHERE gl_code='2010' FOR UPDATE
   │  ├─ Current credit_balance = ₹100,000,000
   │  ├─ Update to 100,000,000 + 10,000 = ₹100,010,000
   │  └─ Commit
   └─ Release locks

STEP 6: LEDGER ENTRY (Immutable Hash-Chained)
└─ JournalEntry entry = new JournalEntry()
   ├─ valueDate = businessDate
   ├─ postingDate = systemDateTime
   ├─ totalDebit = 10000
   ├─ totalCredit = 10000
   ├─ posted = true
   └─ Save to journal_entries table
   
   For each journal line:
   ├─ LedgerEntry ledger1 = new LedgerEntry()
   │  ├─ glCode = "1100"
   │  ├─ debitAmount = 10000
   │  ├─ creditAmount = 0
   │  ├─ journalEntryId = entry.id
   │  ├─ previousHash = "sha256(last ledger chain hash)"
   │  ├─ hash = SHA256(ledger1 content + previousHash)
   │  └─ Save
   ├─ LedgerEntry ledger2 = new LedgerEntry() (similar for CR side)
   └─ Hash chain established (can verify later)

STEP 7: BATCH RUNNING TOTALS UPDATE
└─ BatchJob batch = batchJobRepository.findByTenantIdAndDayAndStatus(tenantId, businessDate, RUNNING)
   ├─ batch.totalTransactions += 1
   ├─ batch.totalDebits += 10000
   ├─ batch.totalCredits += 10000
   └─ Save

STEP 8: VOUCHER GENERATION (Sequential per Branch)
└─ VoucherSequencer sequencer = voucherSequencerRepository.findAndLockByBranchId(branchId)
   ├─ nextVousNb = 1001 → incrementing sequence
   ├─ voucherNumber = "HQ001-2026-04-19-001001"
   └─ Commit (release lock)

STEP 9: MODULE CALLBACK (DepositAccountService)
└─ DepositAccount result = depositAccountService.postDepositInternal(...)
   ├─ account.ledgerBalance += 10000
   ├─ account.availableBalance += 10000
   ├─ account.lastTransactionDate = businessDate
   └─ Save

STEP 10: AUDIT TRAIL CAPTURE
└─ AuditService.logEventInline(...) [same TX as engine]
   ├─ Create AuditLog with:
   │  ├─ entityType = "DepositAccount"
   │  ├─ entityId = account.id
   │  ├─ action = "DEPOSIT"
   │  ├─ beforeSnapshot = JSON(previousBalances)
   │  ├─ afterSnapshot = JSON(newBalances)
   │  ├─ performedBy = currentUsername
   │  ├─ module = "DEPOSIT"
   │  ├─ branchId = branchCode
   │  ├─ payloadHash = SHA256(before+after+module)
   │  ├─ chainHash = SHA256(payloadHash + previousChainHash)
   │  └─ Save
   
   Return: TransactionResult {
       success: true,
       voucherNumber: "HQ001-2026-04-19-001001",
       journalEntryId: entry.id,
       ledgerBalance: 5010000,
       message: "Deposit successful"
   }
```

**Total Time:** ~50-100ms (single thread, local DB)

---

## Database Schema Architecture

### Multi-Tenant Hierarchical Model

```
TENANTS (root)
  ├─ tenant_id (PK)
  ├─ tenant_code, tenant_name
  ├─ rbi_bank_code, ifsc_prefix
  └─ Many-to-Many with BRANCHES

BRANCHES (per tenant)
  ├─ id (PK)
  ├─ tenant_id (FK, scoped)
  ├─ branch_code, branch_name, ifsc_code (unique per tenant)
  ├─ is_active, region
  └─ Many-to-Many with CUSTOMERS

CUSTOMERS (per tenant/branch)
  ├─ id (PK)
  ├─ tenant_id (FK, scoped)
  ├─ customer_number, full_name, email, phone
  ├─ aadhaar_number (encrypted), pan (encrypted)
  ├─ kyc_status (VERIFIED/PENDING)
  └─ Many-to-One with BRANCH

DEPOSIT_ACCOUNTS (per customer)
  ├─ id (PK)
  ├─ tenant_id (FK, scoped)
  ├─ account_number (unique per tenant)
  ├─ account_type (SAVINGS, CURRENT)
  ├─ status (ACTIVE, DORMANT, FROZEN)
  ├─ ledger_balance, available_balance, accrued_interest
  └─ Many-to-One with CUSTOMER, BRANCH

LOAN_ACCOUNTS (per customer)
  ├─ id (PK)
  ├─ tenant_id (FK, scoped)
  ├─ account_number (unique per tenant)
  ├─ status (ACTIVE, CLOSED, NPA)
  ├─ sanctioned_amount, disbursed_amount, outstanding
  ├─ npa_category (STANDARD, SUBSTANDARD, DOUBTFUL, LOSS)
  ├─ days_past_due, last_emi_date, next_emi_date
  └─ Many-to-One with CUSTOMER, BRANCH

LOAN_SCHEDULES (per loan)
  ├─ id (PK)
  ├─ tenant_id (FK, scoped)
  ├─ loan_account_id (FK, scoped)
  ├─ installment_number (1, 2, 3...)
  ├─ emi_amount, principal_amount, interest_amount
  ├─ due_date, status (SCHEDULED, OVERDUE, PAID), paid_date, paid_amount
  └─ Index on (tenant_id, loan_account_id, status)

GL_MASTER (Chart of Accounts)
  ├─ id (PK)
  ├─ tenant_id (FK, scoped)
  ├─ gl_code (unique per tenant, e.g., "1100")
  ├─ gl_name, account_type (ASSET, LIABILITY, EQUITY, INCOME, EXPENSE)
  ├─ debit_balance, credit_balance (running totals)
  ├─ is_active, is_header_account
  └─ Index on (tenant_id, gl_code)

GL_BRANCH_BALANCES (per branch per GL)
  ├─ id (PK)
  ├─ tenant_id, branch_id, gl_code (composite unique)
  ├─ debit_balance, credit_balance (branch-scoped)
  └─ Index on (tenant_id, branch_id, gl_code)

JOURNAL_ENTRIES (GL Posting)
  ├─ id (PK)
  ├─ tenant_id, branch_id, branch_code
  ├─ value_date, posting_date
  ├─ voucher_number (unique per branch), transaction_ref
  ├─ total_debit, total_credit
  ├─ source_module, source_ref
  ├─ is_system_generated, posted
  └─ Index on (tenant_id, posting_date, voucher_number)

LEDGER_ENTRIES (Immutable Chain)
  ├─ id (PK)
  ├─ tenant_id, journal_entry_id
  ├─ gl_code, debit_amount, credit_amount
  ├─ posting_date, branch_code
  ├─ hash (SHA-256), previous_hash (chain linkage)
  └─ CONSTRAINT: IMMUTABLE (no UPDATE/DELETE allowed)

STANDING_INSTRUCTIONS (Recurring Payments)
  ├─ id (PK)
  ├─ tenant_id, si_reference
  ├─ source_account_number, destination_account_number
  ├─ amount (NULL for dynamic LOAN_EMI), frequency (MONTHLY, etc.)
  ├─ status (ACTIVE, PAUSED, CANCELLED), priority
  ├─ next_execution_date, start_date, end_date
  └─ Index on (tenant_id, status, next_execution_date)

AUDIT_LOGS (Immutable Trail)
  ├─ id (PK, sequence)
  ├─ tenant_id, branch_id, branch_code
  ├─ entity_type, entity_id
  ├─ action (e.g., "DEPOSIT", "LOAN_DISBURSED")
  ├─ before_snapshot (JSON), after_snapshot (JSON)
  ├─ performed_by, module, event_timestamp
  ├─ payload_hash, chain_hash, previous_chain_hash
  └─ CONSTRAINT: IMMUTABLE (append-only)

IDEMPOTENCY_REGISTRY (Cross-Module Dedup)
  ├─ id (PK)
  ├─ tenant_id, idempotency_key
  ├─ transaction_result (JSON cached result)
  ├─ created_timestamp, expires_at (24 hours)
  └─ CONSTRAINT: UNIQUE (tenant_id, idempotency_key)

BUSINESS_CALENDAR (Day Control)
  ├─ id (PK)
  ├─ tenant_id, branch_id, business_date
  ├─ day_status (DAY_OPEN, EOD_RUNNING, EOD_COMPLETE)
  ├─ eod_complete, is_working_day, locked
  └─ CONSTRAINT: UNIQUE (tenant_id, branch_id, business_date)

BATCH_JOBS (EOD Metadata)
  ├─ id (PK)
  ├─ tenant_id, job_name (e.g., "EOD"), business_date
  ├─ status (RUNNING, COMPLETED, PARTIALLY_COMPLETED, FAILED)
  ├─ start_time, end_time, duration_ms
  ├─ total_records, processed_records, failed_records
  └─ error_log (text)
```

### Indexing Strategy

```
High-Frequency Queries:
├─ (tenant_id, account_number) → UNIQUE INDEX
├─ (tenant_id, gl_code) → UNIQUE INDEX
├─ (tenant_id, loan_account_id, status) → COMPOSITE INDEX (schedule lookups)
├─ (tenant_id, branch_id, business_date) → UNIQUE INDEX (calendar lookups)
└─ (tenant_id, posting_date, voucher_number) → COMPOSITE INDEX (reconciliation)

GL Balance Updates (Pessimistic Lock):
└─ (tenant_id, gl_code) → UNIQUE (prevents lock conflicts)

EOD Batch Processing:
├─ (tenant_id, status, next_execution_date) → SI execution
├─ (tenant_id, loan_account_id, status) → unpaid installments
└─ (tenant_id, days_past_due DESC) → NPA classification queries

Audit Trail Searches:
├─ (tenant_id, entity_type, entity_id) → per-entity trail
├─ (tenant_id, performed_by, event_timestamp) → user activity
└─ (tenant_id, action, event_timestamp) → action-based searches
```

---

## Scalability & Performance Considerations

### Horizontal Scaling (Future)

**Stateless Deployment:**
```
Load Balancer (sticky sessions for MFA/TOTP)
    ├─ App Instance 1 (Spring Boot, stateless)
    ├─ App Instance 2 (Spring Boot, stateless)
    ├─ App Instance 3 (Spring Boot, stateless)
    └─ App Instance N
         ↓ (all read/write to single SQL Server)
    SQL Server (read replicas + sync backup)
         └─ Single source of truth (GL, customers, accounts)
```

**Database Sharding (v0.1+):**
```
Current (v0.0.1):
└─ Single SQL Server host (tenant-namespace approach)
   └─ All tenants' data in same DB → multi-tenant filter at query level

Future (v0.1+):
├─ Shard by tenant_id
│  ├─ Tenant 001 → SQL Server Shard 1
│  ├─ Tenant 002 → SQL Server Shard 2
│  ├─ Tenant 003 → SQL Server Shard 3
│  └─ Tenant N → SQL Server Shard N
├─ Router layer (lookup tenant → shard)
└─ Benefits: reduced lock contention, independent recovery, per-tenant capacity planning
```

### Performance Optimization

**Caching:**
```
ProductGLResolver.glCodeCache (Caffeine)
├─ product_type → GL codes mapping
├─ TTL: 8 hours (cache stale only during business hours)
└─ Avoids repeated DB queries during EOD (100K accounts × 3 GL lookups each)
```

**Batch Operations:**
```
Hibernate batch_size: 50
order_inserts: true
order_updates: true
└─ Reduces database round-trips for bulk EOD updates
```

**Read-Only Transactions:**
```
@Transactional(readOnly = true, isolation = REPEATABLE_READ)
public List<LoanSchedule> getUnpaidInstallments(...) { ... }
└─ Read-only flag allows DB to skip undo log generation (faster)
```

---

## Deployment Architecture

### Development (H2 In-Memory)
```
Developer Workstation (Windows)
    ├─ IntelliJ IDEA / VS Code
    ├─ Maven (mvn spring-boot:run)
    ├─ Spring Boot (embedded Tomcat)
    ├─ H2 In-Memory DB (auto-schema, auto-seed)
    └─ JSP + REST API available at localhost:8080
```

### Production (SQL Server)
```
Docker Host / Kubernetes Cluster
    ├─ Tomcat 10 Container (WAR deployment)
    │  ├─ JVM: -Xms2g -Xmx4g (CBS sizing)
    │  ├─ Heap dump on OOM
    │  └─ G1GC with 200ms pause target
    ├─ Spring Boot (embedded config)
    ├─ Spring Security (HTTPS/TLS 1.2+)
    └─ Connection Pool (HikariCP, 20 connections)
         ↓
    SQL Server (Prod Cluster)
    ├─ Primary Database (encrypted)
    ├─ Sync Replica (for failover)
    ├─ Transaction Logs (point-in-time recovery)
    ├─ Daily Backup (incremental)
    └─ RTO 4 hours, RPO 1 hour
```

---

## Security Architecture

### Authentication Layers

```
1. Password Authentication (First Factor)
   ├─ Username/password submitted via HTTPS/TLS
   ├─ BCrypt hashing (production)
   ├─ Spring Security AuthenticationProvider verifies
   └─ On success → Spring creates Authentication principal

2. Session Creation
   ├─ FINVANTA_SESSION cookie (HttpOnly, Secure flags)
   ├─ Session stored in Servlet container (Tomcat)
   ├─ Timeout: 15m (production), 30m (dev)
   └─ On inactivity → session invalidated

3. MFA (Second Factor, TOTP per RFC 6238)
   ├─ If mfa_enabled = true AND mfa_secret is set
   ├─ Redirect to /mfa/verify page (session marked MFA_PENDING)
   ├─ User enters 6-digit code from authenticator app
   ├─ MfaService verifies (±1 time step tolerance)
   ├─ Track last_totp_time_step (replay protection)
   ├─ On success → session marked MFA_VERIFIED
   └─ Max 5 failed attempts → session invalidate

4. Authorization (After Auth Success)
   ├─ Extract role from Authentication principal (MAKER, CHECKER, ADMIN, AUDITOR)
   ├─ Method-level @PreAuthorize("hasAnyRole(...)") enforced
   ├─ Extract branch_id from BranchAwareUserDetails
   ├─ All queries scoped to user's branch OR ADMIN can see all
   └─ Audit trail captures user + action
```

### Encryption

**At Rest:**
```
PII Fields (Aadhaar, PAN, encrypted_field columns):
  ├─ Encrypted with AES-256
  ├─ Key from environment variable (FINVANTA_DB_ENCRYPTION_KEY)
  ├─ Spring JPA converts automatically via @Convert(converter = PiiEncryptionConverter.class)
  └─ Query: SELECT * WHERE DECRYPT(aadhaar_number) = '12345...' (functional encrypted search)

MFA Secret:
  ├─ Encrypted before storing in app_users.mfa_secret
  ├─ Decrypted at login for TOTP verification
  └─ Key from environment variable (MFA_ENCRYPTION_KEY)

Database-Level:
  ├─ SQL Server Transparent Data Encryption (TDE) optional
  └─ Encrypts entire database file at disk level
```

**In Transit:**
```
HTTPS/TLS 1.2+:
  ├─ All HTTP → HTTPS redirect (production)
  ├─ Valid certificate (CA-signed, not self-signed in prod)
  ├─ TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384 (strong cipher)
  └─ Password + session cookie encrypted on wire

API Tokens (JWT):
  ├─ Signed with HMAC-SHA256
  ├─ Token contains: username, roles, tenant_id, expiry
  ├─ Secret from CBS_JWT_SECRET environment variable
  ├─ Stateless (server doesn't store token; validates signature)
  └─ Access token: 15 min expiry, Refresh token: 8 hours expiry
```

---

## Disaster Recovery & Business Continuity

### RTO / RPO Targets

| Scenario | RTO | RPO | Implementation |
|----------|-----|-----|-----------------|
| **Transaction Rollback** | < 5 sec | 0 sec | ACID transactions + undo logs |
| **Single GL Posting Failure** | < 30 sec | 0 sec | Idempotency registry + retry |
| **Database Server Failure** | 4 hours | 1 hour | Sync replica + transaction logs |
| **Data Center Failure** | 8 hours | 1 hour | Geographic backup + manual failover |
| **Operator Error** (deleted account) | 24 hours | 1 hour | Point-in-time restore to pre-delete state |

### Backup Strategy

```
Daily Schedule (IST):
  23:30 — Differential backup (since last full backup)
  00:00 — Full database backup (to off-site storage)
  
Retention:
  ├─ Daily backups: 30 days
  ├─ Weekly backups: 12 weeks
  ├─ Monthly backups: 7 years (RBI requirement)
  └─ Transaction logs: 7 days (for point-in-time recovery)
  
Verification:
  └─ Weekly restore test to standby server (ensure backups work)
```

### Failover Procedure

```
Primary SQL Server fails → Sync replica promoted to primary (< 1 min automatic failover)

App Instance Detection:
  ├─ Connection pool detects primary is unreachable
  ├─ Failover to replica (via DNS alias or connection string)
  ├─ Reconnect + retry transaction
  └─ User may see 5-10 sec delay, but transaction succeeds

Data Consistency:
  └─ Sync replica is byte-for-byte identical (no data loss)
```

---

## Operational Monitoring & Alerting

### Health Checks

```
/actuator/health (Spring Boot Actuator)
  ├─ /actuator/health → UP (if OK) or DOWN
  ├─ /actuator/health/db → UP (database connectivity)
  ├─ /actuator/health/diskSpace → UP (disk > 10%)
  └─ Kubernetes liveness/readiness probes call these
```

### Metrics Exported

```
JVM Metrics:
  ├─ jvm.memory.used (MB)
  ├─ jvm.gc.pause (ms — G1GC target: < 200ms)
  └─ jvm.threads.live (active threads)

Tomcat Metrics:
  ├─ tomcat.sessions.active
  └─ tomcat.threads.busy

Application Metrics:
  ├─ finvanta.transaction.processing_ms (latency)
  ├─ finvanta.eod.processed_accounts (batch throughput)
  └─ finvanta.errors.count (exceptions logged)
```

### Alerting Rules

```
Alert: "High Heap Memory Usage"
  Condition: jvm.memory.used > 80% of max
  Action: Page on-call engineer, trigger heap dump

Alert: "Database Connection Pool Exhausted"
  Condition: connection_pool.active == max_pool_size
  Action: Page DBA, restart app instance

Alert: "EOD Batch Failed"
  Condition: eod_job.status == FAILED
  Action: Notify operations team, block all transactions

Alert: "Audit Chain Integrity Failed"
  Condition: audit_chain.verified == false
  Action: Page security team, freeze all financial operations (incident response)
```

---

## Testing Architecture

### Test Pyramid

```
              ▲
             / \
            /   \  E2E Tests (10% coverage)
           /     \ - SpringBootTest with H2
          /       \ - Full transaction pipeline
         /_________\
        /           \
       /     ▲      \
      /     / \      \
     /     /   \      \
    /     / Int \      \  Integration Tests (30% coverage)
   /     / Tests \      \ - Service + Repository layers
  /     /         \      \ - Multi-tenant scenarios
 /     /___________ \      \
/_____________________\     \
|                     |        Repository + Unit Tests (60% coverage)
|   Unit Tests        |        - Double-entry validation
| (*Service*.java)    |        - Interest calculation
| (CvmputleLogic.java)|        - IRAC provisioning
|_____________________|        - Encryption/Decryption
```

### Test Categories

| Category | Tool | Coverage | Example |
|----------|------|----------|---------|
| **Unit** | JUnit 5, Mockito | 60% | AccountingServiceDoubleEntryTest |
| **Integration** | Spring Boot, TestContainers | 30% | EodOrchestratorTest (H2 full stack) |
| **Architecture** | ArchUnit | 100% | CbsArchitectureTest (enforcement) |
| **Performance** | JMH + H2 | 5% | EOD benchmark (100K accounts) |
| **Security** | Spring Security Test | 10% | MfaServiceTest, AuthenticationTest |

---

## Code Quality Standards

### Formatting (Spotless)
```
Java Files:
  ├─ Palantir Java Format (4-space indent)
  ├─ Import ordering (java → jakarta → com.finvanta → static)
  ├─ Trailing whitespace removal
  ├─ Unused import removal
  └─ Line ending: Unix (LF)

POM Files:
  ├─ sortPom (dependencies ordered)
  └─ 4-space indent

CI Gate:
  └─ mvn spotless:check MUST pass before merge
```

### Architecture Enforcement (ArchUnit)

```
Rule: No Direct GL Posting
  └─ Fail: DepositService calls AccountingService.postJournalEntry() directly
  └─ Pass: Only TransactionEngine.execute() calls AccountingService

Rule: Single Tenant Context
  └─ Fail: Query without tenant_id parameter
  └─ Pass: All @Query methods include tenant_id WHERE clause

Rule: Audit Every Financial Action
  └─ Fail: postJournalEntry() without AuditService call
  └─ Pass: Every financial operation logged
```

---

## Conclusion

Finvanta's architecture follows **Tier-1 CBS design patterns from Finacle/Temenos**, with emphasis on:

1. **Single Enforcement Point** — TransactionEngine as the canonical path for all GL posts
2. **Data Integrity** — Double-entry posting, immutable ledger, chain-hash verification
3. **Multi-Tenancy** — Tenant/branch isolation at query level, TenantFilter enforcement
4. **Security** — Password + TOTP 2FA, encryption at rest/transit, audit trail
5. **Scalability** — Horizontal scaling via stateless Spring Boot + database (sharding ready)
6. **Compliance** — RBI IT Governance, IRAC provisioning, immutable audit trail

This architecture is **production-grade** and suitable for deployment at Indian financial institutions.

---

**Last Updated:** April 2026  
**Maintained By:** Senior Core Banking Architect


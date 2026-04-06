# Finvanta CBS - Module-Wise Summary Document
**Tier-1 Core Banking System for Indian RBI-Regulated Banks**

**Date:** April 6, 2026 | **Version:** 0.0.1-SNAPSHOT | **Architecture:** Spring Boot 3.2.5 + Java 17 | **Database:** SQL Server / H2

---

## Table of Contents
1. [Accounting Module](#1-accounting-module)
2. [Transaction Engine](#2-transaction-engine)
3. [Batch Processing & EOD Orchestration](#3-batch-processing--eod-orchestration)
4. [Audit & Compliance](#4-audit--compliance)
5. [Security & Access Control](#5-security--access-control)
6. [Loan Management](#6-loan-management)
7. [Configuration & Infrastructure](#7-configuration--infrastructure)
8. [Workflow & Approval Management](#8-workflow--approval-management)

---

## 1. Accounting Module

### 1.1 **Core Service: AccountingService**
**Location:** `com.finvanta.accounting.AccountingService`

**Purpose:** 
CBS General Ledger (GL) and Journal Entry Service implementing double-entry bookkeeping per Finacle/Temenos standards.

**Key Features:**
- **Double-Entry Posting:** All financial transactions are posted as balanced journal entries (DR total = CR total)
- **Immutable Journal Entries:** Once posted, entries are never modified—reversals create new entries
- **GL Master Management:** Maintains Chart of Accounts with pessimistic locking for atomic balance updates
- **Engine Context Guard:** Token-based cryptographic verification ensuring GL posting ONLY through TransactionEngine
- **Trial Balance Validation:** Ensures GL integrity (Assets + Expenses = Liabilities + Income + Equity)
- **Batch Control:** Associates transactions to TransactionBatch for intra-day reconciliation

**Critical Design Pattern:**
```
DefenseInDepth: ENGINE_TOKEN (ThreadLocal UUID)
├─ TransactionEngine generates token
├─ AccountingService validates token
└─ Prevents direct GL postings (security violation)
```

**Key Methods:**
- `postJournalEntry()` - Posts balanced journal with GL validation and batch tagging
- `updateGLBalances()` - Updates GL debit/credit balances with PESSIMISTIC_WRITE lock
- `getTrialBalance()` - Computes complete trial balance across all GL accounts

**RBI Compliance:**
- Per RBI IT Governance Direction 2023 Section 8.3
- Per RBI Internal Controls Guidelines (segregation of duties)
- Journal entries are immutable per Basel III operational risk framework

**Data Integrity Guarantees:**
- Pessimistic locking on GL master rows prevents lost updates
- Transactional consistency via @Transactional (ACID properties)
- GL balance discrepancies trigger reconciliation alerts

---

### 1.2 **Service: LedgerService**
**Location:** `com.finvanta.accounting.LedgerService`

**Purpose:**
CBS Immutable Ledger Engine per Finacle/Temenos standards maintaining a tamper-proof, append-only transaction ledger.

**Distinction from Journal:**
| Aspect | Journal | Ledger |
|--------|---------|--------|
| **Structure** | Groups related DR/CR lines | Flat chronological record |
| **Entries** | One entry per posting | One entry per GL line |
| **Example** | 1 disbursement (DR Loan + CR Bank) | 2 ledger entries (one per GL) |

**Key Features:**
- **Hash Chain (SHA-256):** Each ledger entry is hashed with previous hash for tamper detection
- **Monotonic Sequencing:** Ledger sequence guarantees chronological ordering (UNIQUE constraint safeguard)
- **Pagination-Safe Verification:** verifyChainIntegrity() processes large ledgers (10M+ entries) with paginated queries
- **Source Traceability:** Links to original journal entry and source module

**Hash Chain Formula:**
```
hash_N = SHA256(tenantId | sequence | glCode | debit | credit | businessDate | previousHash)
previousHash_0 = "GENESIS"
```

**Critical Implementation Detail:**
- BigDecimal normalization: `setScale(2, HALF_UP).toPlainString()` ensures canonical form
- Prevents false tamper detection due to scale mismatches (e.g., 100.00 vs 100.0)

**Key Methods:**
- `postToLedger()` - Creates ledger entries with hash chain for posted journal
- `verifyChainIntegrity()` - Full paginated chain verification with tamper detection

**RBI Compliance:**
- Per RBI audit requirements: 8-year minimum retention
- Ledger entries NEVER updated or deleted (append-only)
- Hash chain integrity verifiable independently

---

### 1.3 **Service: ProductGLResolver**
**Location:** `com.finvanta.accounting.ProductGLResolver`

**Purpose:**
CBS Product-Aware GL Code Resolver enabling product-specific GL mapping (Finacle PDDEF / Temenos AA.PRODUCT.CATALOG).

**Design Pattern:**
```
GL Resolution: Product-Specific → Fallback to Constant
├─ Product configured in product_master? → Use product GL codes
└─ Not configured? → Use GLConstants (backward compatibility)
```

**Performance Optimization - Caffeine Cache:**
```yaml
Cache Configuration:
  - TTL: 15 minutes (auto-refresh stale GL codes)
  - Max Size: 100 entries (bounded memory for multi-tenant)
  - Negative Caching: Unconfigured products cached as Optional.empty()
  
Performance Impact:
  - Without cache: 3 DB queries per repayment, 1000s during EOD
  - With cache: 1 DB query per product per TTL window
```

**Key Methods:**
- `getLoanAssetGL()` - Resolves loan portfolio GL (principal outstanding)
- `getInterestReceivableGL()` - Resolves accrued interest GL
- `getBankOperationsGL()` - Resolves disbursement/collection GL
- `getProvisionExpenseGL()` / `getProvisionNpaGL()` - NPA provisioning GL codes
- `evictCache()` - Manual invalidation for admin product changes

**RBI Compliance:**
- Per CBS standards: GL codes configurable per product, not hardcoded
- Enables gradual product migration without code changes

---

### 1.4 **Constants: GLConstants**
**Location:** `com.finvanta.accounting.GLConstants`

**Purpose:**
Centralized Chart of Accounts per Indian Banking Standard.

**Structure:**
```
1xxx = Assets (Loan Portfolio, Interest Receivable, Bank Operations)
2xxx = Liabilities (Customer Deposits, Suspense Accounts)
3xxx = Equity (Capital, Retained Earnings)
4xxx = Income (Interest Income, Fees, Penal Interest)
5xxx = Expenses (Provisioning, Write-Offs)
```

**Key GL Codes:**
| Code | Account | Purpose |
|------|---------|---------|
| 1001 | Loan Portfolio | Outstanding principal for term loans |
| 1002 | Interest Receivable | Accrued interest not yet collected |
| 1003 | Provision for NPA | Contra-asset for loan loss provisioning |
| 1100 | Bank Operations | Cash/bank for disbursements and collections |
| 2100 | Interest Suspense | RBI IRAC: Interest on NPA accounts (not recognized as income until collected) |
| 4001 | Interest Income | Recognized interest revenue |
| 4003 | Penal Interest Income | Charged on overdue accounts |
| 5001 | Provision Expense | P&L charge for NPA provisioning |
| 5002 | Write-Off Expense | Loss write-off for NPA Loss accounts |

**RBI NPA Interest Management (2100 - Interest Suspense):**
```
When Loan Becomes NPA:
  DR Interest Income (4001)
  CR Interest Suspense (2100)
  
When NPA Interest Collected:
  DR Interest Suspense (2100)
  CR Interest Income (4001)
```

---

## 2. Transaction Engine

### 2.1 **Core Service: TransactionEngine**
**Location:** `com.finvanta.transaction.TransactionEngine`

**Purpose:**
**THE SINGLE ENFORCEMENT POINT** for entire CBS platform. Every financial transaction (regardless of source module) must pass through this engine.

**Architecture Principle:**
```
Modules (Loan, Deposit, Remittance, Batch)
  ↓
All build TransactionRequest
  ↓
TransactionEngine.execute(request)
  ├─ 10-Step Validation Chain (SEQUENTIAL)
  ├─ GL Posting via AccountingService
  ├─ Voucher Generation
  └─ Audit Trail
  ↓
All receive TransactionResult
```

**10-Step Validation Chain (IMMUTABLE ORDER):**

| Step | Name | Validation | Enforces |
|------|------|-----------|----------|
| 1 | Idempotency | Checked at module level (module-specific tables) | No duplicate processing on retries |
| 2 | Business Date | Value date must exist in business calendar | No postings to deleted/future dates |
| 3 | Day Status | DAY_OPEN only; EOD system-generated exempt | Day-level control (prevents trading outside hours) |
| 4 | Amount | Positive, ≤16 integer digits, ≤2 decimal places | Precision within DECIMAL(18,2) limits |
| 5 | Branch | Must exist and be active | No postings to inactive branches |
| 6 | Transaction Limits | Per-role per-type + daily aggregate | RBI Internal Controls (segregation of duties) |
| 7 | Maker-Checker | Above-limit txns require dual authorization | RBI governance (dual control) |
| 8 | GL Posting | Double-entry posting with lock/update/ledger/batch | Accounting integrity |
| 9 | Voucher Generation | Unique per branch per date | Finacle TRAN_POSTING convention |
| 10 | Audit Trail | Hash-chained audit log | RBI audit requirements |

**Validation Chain Code Pattern:**
```java
// CBS Security: Each step validates ONE concept and fails fast.
// Order is critical: earlier steps filter invalid inputs for later steps.
// No step can be bypassed or reordered.
```

**Key Methods:**
- `execute(TransactionRequest)` - Main entry point for all financial transactions

**TransactionResult:**
```java
record TransactionResult(
    String transactionRef,          // Unique identifier
    String voucherNumber,           // VCH/{branch}/{YYYYMMDD}/{seq}
    Long journalEntryId,            // GL posting reference
    String journalRef,              // Journal reference
    BigDecimal totalDebit,          // Posted debit amount
    BigDecimal totalCredit,         // Posted credit amount
    LocalDate valueDate,            // Transaction date
    LocalDateTime postingDate,      // Posted timestamp
    String postingStatus            // POSTED | PENDING_APPROVAL
)
```

**Compound Posting (Multi-Leg Transactions):**
```
Example: Disbursement (3 GL legs)
  Group 1: DR Loan Asset / CR Bank Operations
  Group 2: DR Bank Operations (fee) / CR Fee Income
  Group 3: DR Processing Fee Expense / CR Processing Fee Accrual

Result:
  - All groups share same voucher + transaction ref
  - All groups shared same audit trail
  - First journal entry returned as primary reference
```

**RBI Compliance:**
- Per RBI IT Governance Direction 2023
- Per RBI Internal Controls Guidelines
- Per Finacle TRAN_POSTING architecture

---

## 3. Batch Processing & EOD Orchestration

### 3.1 **Orchestrator: EodOrchestrator**
**Location:** `com.finvanta.batch.EodOrchestrator`

**Purpose:**
End-of-Day Batch Orchestrator executing nightly batch cycle with per-account error isolation and full audit trail.

**EOD Step Sequence (7 Steps):**

| Step | Name | Purpose | Per-Account | Idempotent |
|------|------|---------|------------|-----------|
| 1 | Mark Overdue Installments | Identifies all installments past due date | NO | YES |
| 2 | Update Account DPD | Calculates Days Past Due from oldest overdue | YES | YES |
| 3 | Interest Accrual | Accrues daily interest on outstanding principal | YES | YES |
| 4 | Penal Interest Accrual | Charges penal interest on overdue amounts | YES | YES |
| 5 | NPA Classification | Classifies accounts per RBI IRAC norms | YES | YES |
| 6 | Provisioning | Posts provisioning per RBI IRAC percentages | YES (by group) | YES |
| 7 | GL Reconciliation | Reconciles ledger vs GL master balances | NO | YES |

**Day Status Lifecycle:**
```
NOT_OPENED
  ↓ (DayOpenService.openDay)
DAY_OPEN
  ↓ (EodOrchestrator.executeEod)
EOD_RUNNING
  ↓ (EodOrchestrator.finalizeEod)
[eodComplete=true]
  ↓ (DayCloseService.closeDay - admin action)
DAY_CLOSED
```

**Error Isolation:**
```
Per-Account Try/Catch:
├─ Account A: Interest accrual succeeds
├─ Account B: Interest accrual fails (invalid schedule)
└─ Account C: Interest accrual succeeds
Result: Partially completed (processed=2, failed=1)
```

**EOD Job Tracking:**
```
BatchJob entity tracks:
├─ totalRecords: Count of active accounts
├─ processedRecords: Successfully completed accounts
├─ failedRecords: Failed accounts
├─ stepName: Current step (e.g., INTEREST_ACCRUAL)
├─ status: RUNNING | COMPLETED | PARTIALLY_COMPLETED | FAILED
└─ errorMessage: Concatenated error details
```

**RBI Compliance:**
- Per RBI IRAC Master Circular (provisioning percentages)
- Per RBI Fair Lending Code 2023 (penal interest on overdue)
- Per RBI audit requirements (EOD completion validation)

---

### 3.2 **Service: ProvisioningService**
**Location:** `com.finvanta.batch.ProvisioningService`

**Purpose:**
CBS Provisioning Engine calculating and posting loan loss provisions per RBI IRAC Master Circular.

**RBI IRAC Provisioning Percentages:**
```
Asset Classification    Secured?      Provision %
─────────────────────────────────────────────────
Standard                -             0.40%
Sub-Standard            Secured       15.00%
Sub-Standard            Unsecured     25.00%
Doubtful (0-1 year)     -             25.00%
Doubtful (1-3 years)    -             40.00%
Doubtful (>3 years)     -             100.00%
Loss                    -             100.00%
```

**Provisioning Calculation:**
```
Required Provision = Outstanding Principal × IRAC Rate / 100
Delta = Required - Existing
If |Delta| > 0.01:
  If Delta > 0: DR Provision Expense / CR Provision for NPA
  If Delta < 0: DR Provision for NPA / CR Provision Expense (reversal)
```

**Key Methods:**
- `calculateAndPostProvisioning()` - Batch provisioning for all active accounts
- `calculateRequiredProvision()` - Computes required amount per IRAC norms
- `getProvisioningRate()` - Returns rate per account status and security

**GL Entries:**
```
Increase Provision:
  GL 5001 (Provision Expense) - DEBIT
  GL 1003 (Provision for NPA) - CREDIT

Decrease Provision (Reversal):
  GL 1003 (Provision for NPA) - DEBIT
  GL 5001 (Provision Expense) - CREDIT
```

**RBI Compliance:**
- Per RBI IRAC Master Circular
- Per RBI Provisioning guidelines
- Account-level calculation (not portfolio-level aggregation)

---

### 3.3 **Service: ReconciliationService**
**Location:** `com.finvanta.batch.ReconciliationService`

**Purpose:**
GL Reconciliation Service comparing immutable ledger totals against GL master running balances.

**Reconciliation Formula:**
```
For each GL code:
  Ledger sum(debit) must equal GL master debitBalance
  Ledger sum(credit) must equal GL master creditBalance

If discrepancy detected:
  ├─ Possible cause: Lost update bug in GL balance logic
  ├─ Possible cause: Direct DB manipulation bypassing app layer
  └─ Possible cause: Data corruption
  
Action: Log error and trigger investigation
```

**Discrepancy Record:**
```java
record Discrepancy(
    String glCode,
    String glName,
    BigDecimal glDebit,          // GL master debit balance
    BigDecimal ledgerDebit,      // Sum of ledger debits
    BigDecimal glCredit,         // GL master credit balance
    BigDecimal ledgerCredit      // Sum of ledger credits
)
```

**RBI Compliance:**
- Per RBI audit requirements (reconciliation frequency)
- Per Basel III operational risk framework
- Mandatory EOD validation step

---

## 4. Audit & Compliance

### 4.1 **Service: AuditService**
**Location:** `com.finvanta.audit.AuditService`

**Purpose:**
CBS Immutable Audit Trail Service with SHA-256 hash chain ensuring tamper detection per RBI guidelines.

**Audit Log Captures:**
```
├─ Entity Type & ID (e.g., LoanAccount/1001)
├─ Action (CREATE, UPDATE, DELETE, POSTED, REVERSED)
├─ Before & After JSON snapshots
├─ Performed By (user who took action)
├─ IP Address (request source)
├─ Event Timestamp
├─ Module (LOAN, ACCOUNTING, BATCH)
├─ Description (human-readable)
├─ Hash Chain (SHA-256 with previous hash linkage)
└─ Previous Hash (linkage to prior audit entry)
```

**Transaction Propagation (REQUIRES_NEW):**
```
If parent transaction rolls back:
├─ Audit record still persists (separate transaction)
└─ Ensures audit trail integrity even on failure
```

**Hash Chain Verification:**
```
Entry N hash = SHA256(entityType | entityId | action | user | timestamp | beforeJson | afterJson | previousHash)
previousHash_0 = "GENESIS"
```

**Key Methods:**
- `logEvent()` - Creates immutable audit log with hash chain
- `getAuditTrail()` - Retrieves audit history for entity
- `verifyChainIntegrity()` - Validates hash chain for tamper detection

**RBI Compliance:**
- Per RBI IT Governance Direction 2023
- Per RBI audit requirements (minimum 8-year retention)
- Per RBI guidelines on system access and change management

---

## 5. Security & Access Control

### 5.1 **Security Configuration: SecurityConfig**
**Location:** `com.finvanta.config.SecurityConfig`

**Purpose:**
Role-Based Access Control (RBAC) per Finacle/Temenos security standards.

**CBS Role Matrix:**

| Role | Responsibilities | Constraints |
|------|------------------|-------------|
| **MAKER** | Loan applications, customer creation, repayment processing | Cannot verify/approve own transactions (enforced in service layer) |
| **CHECKER** | Verification, approval, rejection, KYC, disbursement, account creation | Verifier ≠ Approver (segregation of duties) |
| **ADMIN** | All CHECKER permissions + EOD batch, branch/system config | Elevated access for administrative functions |
| **AUDITOR** | Read-only audit trail access | No transactional authority |

**Authentication & Authorization:**
```
Login Flow:
  /login → DelegatingPasswordEncoder (bcrypt/noop/scrypt)
  → Form-based authentication
  → Session management (max 1 session per user)
  → Session fixation protection
  → CSRF protection (except H2 console for dev)

Authorization Cascade:
  @PreAuthorize("hasRole('CHECKER')") filters endpoints
  @RequestMapping("/loan/approve/**") restricts access
  → SecurityFilterChain enforces role checks
```

**Password Encoding:**
```
Strategy: DelegatingPasswordEncoder
├─ Production: {bcrypt} (secure hashing)
├─ Development: {noop} (plaintext for seed data only)
├─ Supported: {scrypt}, {argon2}
└─ Migration: Mixed encoding during transition
```

**Session Management:**
```
├─ Session timeout: 30 minutes (configurable)
├─ Maximum sessions per user: 1 (prevents concurrent login abuse)
├─ Session fixation: migrateSession (creates new session post-login)
└─ JSESSIONID cookie invalidation on logout
```

**RBI Compliance:**
- Per RBI Internal Controls Guidelines (segregation of duties)
- Per RBI guidelines on user access management
- Per RBI audit requirements (transaction traceability)

---

### 5.2 **Tenant Filter: TenantFilter**
**Location:** `com.finvanta.config.TenantFilter`

**Purpose:**
Multi-tenant request isolation via ThreadLocal-based tenant context.

**Tenant Resolution Order:**
```
1. Request header: X-Tenant-Id
2. Session attribute: TENANT_ID
3. Fallback: DEFAULT

Resolution Pattern:
  HttpRequest → Header check
            → Session check (if no header)
            → Fallback to DEFAULT
            → TenantContext.setCurrentTenant()
            → Request processing
            → TenantContext.clear() (finally block)
```

**Security Guarantee:**
```
Each request isolated in ThreadLocal:
├─ No cross-tenant data leakage
├─ Multiple concurrent requests (thread pool) safely isolated
└─ finally block ensures cleanup (prevents stale context on thread reuse)
```

**RBI Compliance:**
- Per RBI guidelines on data segregation for multi-tenant deployments
- Per regulatory requirement for tenant-level isolation

---

### 5.3 **Service: TransactionLimitService**
**Location:** `com.finvanta.service.TransactionLimitService`

**Purpose:**
Transaction Limit Validation per RBI Internal Controls Guidelines.

**Two-Level Validation:**
```
Per-Transaction Limit:
├─ Single transaction amount cap
├─ Role + Type specific (e.g., MAKER + REPAYMENT)
├─ Type fallback: MAKER + ALL
└─ No limit: backward compatible (proceeds)

Daily Aggregate Limit:
├─ Cumulative amount cap per user per CBS business date
├─ Prevents single-user daily exposure
└─ Uses CBS business date (not system date)
```

**Limit Resolution Order:**
```
1. FindByRoleAndType (e.g., MAKER + REPAYMENT)
2. FindByRoleAndType with Type='ALL' (catch-all)
3. Not configured? Proceed (backward compatible)
```

**Rejection Cases:**
```
- User has null role → Rejected (no transactional authority)
- Amount exceeds per-transaction limit → TRANSACTION_LIMIT_EXCEEDED
- Daily aggregate exceeded → DAILY_LIMIT_EXCEEDED
- System-generated (EOD) → Bypass (caller responsibility)
```

**RBI Compliance:**
- Per RBI Internal Controls Guidelines
- Per RBI segregation of duties (role-based limits)
- Per RBI operational risk management framework

---

### 5.4 **Service: MakerCheckerService**
**Location:** `com.finvanta.service.MakerCheckerService`

**Purpose:**
Dual Authorization Service per RBI governance framework (Finacle TRAN_AUTH).

**Maker-Checker Flow:**
```
High-Value Transaction (above threshold):
  
  MAKER: Initiates transaction
    ↓
  TransactionEngine.execute() → Step 7: requiresApproval()
    ├─ Creates ApprovalWorkflow (PENDING_APPROVAL)
    ├─ No GL posting yet
    └─ Returns TransactionResult (status=PENDING_APPROVAL)
    ↓
  CHECKER: Reviews transaction
    ├─ Calls approveTransaction()
    ├─ OR rejectTransaction()
    ↓
  If APPROVED:
    ├─ TransactionEngine re-executes (pre-approval flag set)
    ├─ Step 7 skipped (already approved)
    └─ GL posting proceeds (Steps 8-10)
    
  If REJECTED:
    ├─ ApprovalWorkflow status → REJECTED
    └─ Transaction never posted to GL
```

**Threshold Determination:**
```
Threshold = per_transaction_limit from transaction_limits table
Transactions WITHIN limit: Auto-approved (single authorization)
Transactions ABOVE limit: Require checker approval (dual authorization)
```

**Verification Constraints:**
```
Maker ≠ Checker:
├─ Enforced at service layer
├─ Prevents single user approving own high-value txns
└─ Per RBI segregation of duties
```

**RBI Compliance:**
- Per RBI Internal Controls Guidelines
- Per RBI governance framework (dual authorization for high-value txns)
- Per RBI audit requirements (approval workflow tracking)

---

## 6. Loan Management

### 6.1 **Service: LoanAccountService (Interface)**
**Location:** `com.finvanta.service.LoanAccountService`

**Purpose:**
Core loan account operations implementing RBI-compliant lending workflows.

**Key Operations:**

| Operation | Purpose | GL Posting | Compliance |
|-----------|---------|-----------|-----------|
| `createLoanAccount()` | Creates account from approved application | No | Per Finacle AA |
| `disburseLoan()` | Full disbursement | DR Loan / CR Bank | Per RBI Fair Lending |
| `disburseTranche()` | Tranche disbursement (multi-disb products) | DR Loan / CR Bank | Home Loan, Construction Finance support |
| `applyInterestAccrual()` | Daily interest on outstanding | DR Int Rec / CR Int Income | Actual/365 per RBI NPA rules |
| `applyPenalInterest()` | Penal on overdue accounts | DR Int Rec / CR Penal Income | RBI Fair Lending Code 2023 |
| `classifyNPA()` | NPA classification per IRAC | Account status update | RBI IRAC Master Circular |
| `writeOffAccount()` | Loss write-off | DR Write-Off / CR Loan Asset | Per IRAC Loss classification |
| `processRepayment()` | Loan repayment | DR Bank / CR Loan + Int Rec | Allocation per product rules |
| `processPrepayment()` | Early loan closure | DR Bank / CR Loan + Int Rec | Per RBI Fair Lending (no penalty on floating) |
| `reverseTransaction()` | Reverse posted transaction | Contra journal entry | Per Finacle reversals |
| `chargeProcessingFee()` | Charge disbursement fees | DR Bank / CR Fee Income | At disbursement or ad-hoc |

**Transaction Reversal Pattern:**
```
Original Transaction:
  DR Loan Asset (1001)   / CR Bank Operations (1100)
  
Reversal:
  DR Bank Operations (1100) / CR Loan Asset (1001)
  (Exact mirror of original)

Audit Trail:
  ├─ Original marked as REVERSED (never deleted)
  ├─ Reversal creates new transaction
  ├─ Linked via reversal reference
  └─ Full audit trail preserved
```

**NPA Classification Per RBI IRAC:**
```
Standard: 0 DPD
SMA (Special Mention Account):
  SMA-0: 0-30 DPD
  SMA-1: 31-60 DPD
  SMA-2: 61-90 DPD
Sub-Standard: 90+ DPD (within 12 months)
Doubtful:
  0-1 year NPA
  1-3 years NPA
  >3 years NPA
Loss: >3 years NPA (100% written off)
```

**RBI Compliance:**
- Per RBI IRAC Master Circular
- Per RBI Fair Lending Code 2023
- Per Finacle/Temenos AA standards

---

## 7. Configuration & Infrastructure

### 7.1 **Common Model Advice: CommonModelAdvice**
**Location:** `com.finvanta.config.CommonModelAdvice`

**Purpose:**
Populates common model attributes for all view responses (topbar, layout).

**Key Attributes:**
```java
@ModelAttribute
model.addAttribute("businessDate", openDay.getBusinessDate().format("dd-MMM-yyyy"));
model.addAttribute("userRole", SecurityContextHolder.getContext().getUserRole());
```

**Critical Principle:**
```
businessDate sourced from CBS Business Calendar (NOT LocalDate.now())
Per Finacle/Temenos: The UI always shows the current CBS business date
(Which may differ from system date during EOD)
```

---

### 7.2 **Tenant Context Utility**
**Location:** `com.finvanta.util.TenantContext`

**Purpose:**
ThreadLocal-based tenant isolation for multi-tenant deployments.

**Usage Pattern:**
```java
// In request processing:
String tenantId = TenantContext.getCurrentTenant();  // DEFAULT if not set
TenantContext.setCurrentTenant("TENANT_001");        // Set by TenantFilter
// ... request processing ...
TenantContext.clear();  // Cleanup (finally block)
```

**Security Guarantee:**
```
Each request thread isolated:
├─ No cross-tenant data leakage
├─ Multiple concurrent requests safely isolated
└─ ThreadLocal prevents cross-request contamination
```

---

### 7.3 **Application Configuration: application.yml**
**Location:** `src/main/resources/application.yml`

**Database Profiles:**
```yaml
# Development Profile (Default)
spring.datasource.url: jdbc:h2:mem:finvantadb
spring.h2.console.enabled: true
spring.jpa.hibernate.ddl-auto: update

# Production Profile (-Dspring.profiles.active=prod)
spring.datasource.url: jdbc:sqlserver://localhost:1433;databaseName=finvanta
spring.jpa.hibernate.ddl-auto: validate  # Never auto-create schema in prod
```

**Hibernate Configuration:**
```yaml
jdbc.batch_size: 50              # Batch SQL inserts for performance
order_inserts: true              # Order statements for replication
format_sql: true                 # Readable SQL logs (disable in prod)
```

**Logging:**
```yaml
Development:
  com.finvanta: DEBUG            # Application-level detail
  org.springframework.security: WARN
  org.hibernate.SQL: WARN

Production:
  com.finvanta: INFO             # Only operational events
  root: WARN
```

**Session Management:**
```yaml
server.servlet.session.timeout: 30m  # Configurable timeout
```

---

## 8. Workflow & Approval Management

### 8.1 **Service: ApprovalWorkflowService**
**Location:** `com.finvanta.service.ApprovalWorkflowService`

**Purpose:**
Manages loan application and transaction approval workflows with multi-step validation.

**Workflow Entities:**
```
ApprovalWorkflow:
├─ entityType: LOAN_APPLICATION | TRANSACTION
├─ entityId: Reference to loan/transaction
├─ status: PENDING_APPROVAL | APPROVED | REJECTED | CANCELLED
├─ createdBy: MAKER (initiator)
├─ approvedBy: CHECKER (approver)
├─ rejectionReason: Optional (if rejected)
└─ comments: Optional audit notes
```

**Loan Application Approval Flow:**
```
MAKER: Create Loan Application
  ├─ Status: SUBMITTED
  ├─ Routed to CHECKER queue
  ↓
CHECKER: Verify Documents & KYC
  ├─ Review application
  ├─ Verify customer KYC
  └─ Status: KYC_VERIFIED
  ↓
CHECKER: Approve/Reject Application
  ├─ If APPROVE:
  │  └─ Status: APPROVED
  ├─ If REJECT:
  │  ├─ Status: REJECTED
  │  └─ Reason: Mandatory
  ↓
CHECKER: Create Loan Account (on approval)
  ├─ Generates account number
  ├─ Initializes repayment schedule
  └─ Status: ACCOUNT_CREATED
```

**RBI Compliance:**
- Per RBI Internal Controls Guidelines (segregation of duties)
- Per RBI audit requirements (approval workflow tracking)

---

## Summary: Module Interdependencies

```
┌─────────────────────────────────────────────────────────┐
│                   TRANSACTION ENGINE                     │
│          (10-Step Validation Enforcement Point)          │
└────────────────────┬────────────────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ↓            ↓            ↓
   ┌─────────┐  ┌──────────┐  ┌──────────┐
   │Accounting│  │  Audit   │  │Security  │
   │ Module   │  │ Service  │  │ Config   │
   ├─────────┤  ├──────────┤  ├──────────┤
   │ • Journal│  │• Immutable│  │• RBAC   │
   │ • GL     │  │  Logs    │  │• Limits  │
   │ • Ledger │  │• Hash    │  │• Maker-  │
   │ • Recon  │  │  Chain   │  │  Checker │
   └─────────┘  └──────────┘  └──────────┘
        │            │            │
        └────────────┼────────────┘
                     ↓
        ┌───────────────────────┐
        │  Batch Processing     │
        │  (EOD Orchestrator)   │
        ├───────────────────────┤
        │• Mark Overdue        │
        │• Update DPD          │
        │• Interest Accrual    │
        │• Penal Accrual       │
        │• NPA Classification  │
        │• Provisioning        │
        │• GL Reconciliation   │
        └───────────────────────┘
                     │
        ┌────────────┼────────────┐
        ↓            ↓            ↓
   ┌─────────┐  ┌──────────┐  ┌──────────┐
   │   Loan  │  │Workflow &│  │   Data   │
   │Mgmt Svc │  │Approval  │  │ Layer    │
   └─────────┘  └──────────┘  └──────────┘
```

---

## Key Design Principles

1. **Single Enforcement Point:** All financial operations route through TransactionEngine (no direct GL posting)
2. **Defense-in-Depth:** Multiple validation layers with clear failure boundaries
3. **Immutable Audit Trail:** Hash-chained audit logs prevent tampering
4. **Pessimistic Locking:** GL and batch updates use database-level locks (no lost updates)
5. **Per-Tenant Isolation:** ThreadLocal tenant context prevents cross-tenant data leakage
6. **RBI Compliance:** Every module implements relevant RBI guidelines (IRAC, Fair Lending, Internal Controls)
7. **Error Isolation:** EOD batch errors per-account (one failure doesn't stop entire batch)
8. **Transactional Consistency:** @Transactional boundaries ensure ACID properties

---



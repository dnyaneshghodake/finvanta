# Finvanta CBS - Quick Reference Index

**Generated:** April 6, 2026 | **Documents:** 3

---

## Document Overview

### 1. **APPLICATION_SUMMARY.md** (Primary Document)
**Comprehensive system overview** for architects and decision-makers.

**Contents:**
- Executive summary & architecture overview
- Complete module descriptions with RBI compliance mappings
- Data model documentation
- Key workflows (loan journey, maker-checker flow)
- RBI/Finacle compliance coverage matrix
- Performance considerations
- Deployment & operations guide
- Production readiness assessment

**Best For:** Strategic evaluation, regulatory review, design decisions

**Key Sections:**
- Sec II: Core Modules Deep Dive
- Sec IV: Key Workflows
- Sec V: Compliance Mappings
- Sec VIII: Deployment Guide

---

### 2. **MODULE_WISE_SUMMARY.md** (Technical Reference)
**Detailed module-by-module breakdown** for developers and architects.

**8 Core Modules:**
1. Accounting Module (GL, Ledger, Product GL Resolver)
2. Transaction Engine (10-step validation chain)
3. Batch Processing & EOD (7-step orchestration)
4. Audit & Compliance
5. Security & Access Control
6. Loan Management
7. Configuration & Infrastructure
8. Workflow & Approval Management

**Best For:** Code review, implementation, technical deep-dives

**Key Sections:**
- Module 1.1: AccountingService (GL posting, ENGINE_TOKEN guard)
- Module 1.2: LedgerService (SHA-256 hash chain, paginated verification)
- Module 1.3: ProductGLResolver (Caffeine cache strategy)
- Module 2.1: TransactionEngine (10-step chain with code examples)
- Module 3.1: EodOrchestrator (7-step EOD sequence)
- Module 3.2: ProvisioningService (RBI IRAC rates)
- Module 3.3: ReconciliationService (GL vs Ledger)

---

### 3. **QUICK_REFERENCE.md** (This Document)
**Module index and quick navigation guide.**

---

## Module Navigation Map

### **Accounting Module** (GL + Ledger + Reconciliation)
```
Location: com.finvanta.accounting.*

Key Services:
  ├─ AccountingService         → GL posting with double-entry validation
  ├─ LedgerService             → Immutable ledger with hash chain
  ├─ ProductGLResolver         → Product-aware GL code resolution (cached)
  └─ GLConstants               → Centralized Chart of Accounts (1xxx-5xxx)

Key Methods:
  AccountingService.postJournalEntry()    → Posts balanced journal entry
  AccountingService.updateGLBalances()    → Updates GL with pessimistic lock
  AccountingService.getTrialBalance()     → Computes GL trial balance
  
  LedgerService.postToLedger()            → Creates hash-chained ledger entry
  LedgerService.verifyChainIntegrity()    → Validates ledger tamper-proof
  
  ProductGLResolver.getLoanAssetGL()      → Resolves product-specific GL codes
  ProductGLResolver.evictCache()          → Manual cache invalidation

RBI Compliance: IRAC, GL integrity, audit trail
Performance: Caffeine cache (15min TTL, 100 entries, negative caching)
Locking: PESSIMISTIC_WRITE on GL master rows
```

---

### **Transaction Engine** (Central Validation)
```
Location: com.finvanta.transaction.*

Key Service:
  └─ TransactionEngine        → THE SINGLE ENFORCEMENT POINT for all financial operations

10-Step Validation Chain:
  1. Idempotency              → Prevent duplicate processing
  2. Business Date            → Value date must exist in calendar
  3. Day Status               → DAY_OPEN (or EOD_RUNNING for system)
  4. Amount                   → Positive, ≤16 digits, ≤2 decimals
  5. Branch                   → Must exist and be active
  6. Limits                   → Per-txn + daily aggregate (skip system)
  7. Maker-Checker            → Above-threshold transactions → PENDING_APPROVAL
  8. GL Posting               → Double-entry posting with lock/update/ledger/batch
  9. Voucher Generation       → Unique per branch per date (VCH/{branch}/{YYYYMMDD}/{seq})
  10. Audit Trail             → Hash-chained immutable log

Key Method:
  TransactionEngine.execute(TransactionRequest) → 10-step chain + GL posting

Design Pattern:
  - Engine generates cryptographic token (ENGINE_TOKEN)
  - AccountingService validates token (prevents direct GL posting bypasses)
  - Compound posting: multi-group transactions with shared voucher

RBI Compliance: Internal Controls (10-step enforcement), IT Governance, audit trail
Performance: ~100ms per transaction (pessimistic locks)
```

---

### **Batch Processing & EOD** (Nightly Batch Cycle)
```
Location: com.finvanta.batch.*

Key Services:
  ├─ EodOrchestrator          → Orchestrates 7-step EOD sequence
  ├─ ProvisioningService      → Calculates & posts RBI IRAC provisions
  └─ ReconciliationService    → Reconciles ledger vs GL master

7-Step EOD Sequence:
  1. Mark Overdue Installments        → Identify past-due schedules
  2. Update Account DPD               → Calculate Days Past Due from oldest overdue
  3. Interest Accrual                 → Accrue daily interest on outstanding
  4. Penal Interest Accrual           → Charge penal interest on overdue (RBI Fair Lending)
  5. NPA Classification               → Classify per RBI IRAC (Standard/SMA/NPA/Loss)
  6. Provisioning                     → Post RBI IRAC percentage provisions
  7. GL Reconciliation                → Verify ledger vs GL master match

RBI IRAC Provisioning Rates (on outstanding):
  Standard:         0.40%
  Sub-Std/Secured: 15.00%
  Sub-Std/Unsecured: 25.00%
  Doubtful (0-1yr): 25.00%
  Doubtful (1-3yr): 40.00%
  Doubtful (>3yr): 100.00%
  Loss:            100.00%

Error Isolation: Per-account try/catch (one failure ≠ EOD fail)
Day Status: DAY_OPEN → EOD_RUNNING → [eodComplete=true] → DAY_CLOSED

RBI Compliance: IRAC Master Circular, Fair Lending Code 2023, audit requirements
Performance: Batch size 50 (Hibernate), parallel processing for independent steps
```

---

### **Audit & Compliance** (Immutable Audit Trails)
```
Location: com.finvanta.audit.*

Key Service:
  └─ AuditService             → Immutable hash-chained audit logs

Audit Log Captures:
  ├─ Entity (type, ID)
  ├─ Action (CREATE, UPDATE, DELETE, POSTED, REVERSED, APPROVED)
  ├─ Before & After JSON snapshots
  ├─ Performed By (user), IP Address, Timestamp
  ├─ Module (LOAN, ACCOUNTING, BATCH, ADMIN)
  └─ Hash Chain (SHA256 with previousHash)

Hash Verification:
  hash_N = SHA256(entity|action|user|time|before|after|prevHash)
  previousHash_0 = "GENESIS"

Transaction Propagation: REQUIRES_NEW
  → Audit persists even if parent transaction rolls back

Key Method:
  AuditService.logEvent(...) → Creates immutable audit log

RBI Compliance: 8-year retention, IT Governance, system access logging
Tamper Detection: Hash chain verification catches modifications
Performance: ~50ms per log (separate transaction)
```

---

### **Security & Access Control** (RBAC + Limits + Maker-Checker)
```
Location: com.finvanta.config.* + com.finvanta.service.*

Key Services:
  ├─ SecurityConfig            → Role-Based Access Control (RBAC)
  ├─ TenantFilter              → ThreadLocal multi-tenant isolation
  ├─ TransactionLimitService   → Per-txn + daily aggregate limits
  ├─ MakerCheckerService       → Dual authorization for high-value txns
  └─ CustomUserDetailsService  → Spring Security integration

Role Matrix:
  MAKER     → Loan applications, repayments, customer creation
  CHECKER   → Verification, approval, disbursement, account creation
  ADMIN     → All CHECKER + EOD, branch/system config
  AUDITOR   → Read-only audit trail

Authentication:
  Strategy: DelegatingPasswordEncoder (bcrypt/noop/scrypt)
  Session: 30-min timeout, max 1 session per user, CSRF protection
  
Authorization:
  Cascade: request → @PreAuthorize → endpoint → logic layer
  
Limits Enforcement:
  Level 1: Per-role per-transaction limit (e.g., MAKER REPAYMENT max 500K)
  Level 2: Daily aggregate per user (e.g., MAKER daily max 1M)
  Level 3: Maker-Checker threshold (transactions above limit → PENDING_APPROVAL)

Maker-Checker Flow:
  MAKER initiates high-value txn
  → TransactionEngine Step 7: requiresApproval() returns true
  → Create ApprovalWorkflow (PENDING_APPROVAL, no GL posting)
  → CHECKER approves/rejects
  → If approved: Re-execute with preApproved flag (Step 7 skipped)
  → If rejected: Transaction never posted to GL

RBI Compliance: Internal Controls (segregation of duties), governance
Enforcement: Database-level (pessimistic locks), application-level (role checks)
```

---

### **Loan Management** (Account Lifecycle)
```
Location: com.finvanta.service.*

Key Service:
  └─ LoanAccountService (interface)

Core Operations:
  ├─ createLoanAccount()       → Create account from approved application
  ├─ disburseLoan()            → Full disbursement (GL: DR Loan / CR Bank)
  ├─ disburseTranche()         → Tranche disbursement (multi-disb products)
  ├─ applyInterestAccrual()    → Daily interest accrual (Actual/365)
  ├─ applyPenalInterest()      → Penal on overdue (RBI Fair Lending)
  ├─ classifyNPA()             → NPA classification per RBI IRAC
  ├─ writeOffAccount()         → Loss write-off (GL: DR Write-Off / CR Loan)
  ├─ processRepayment()        → Loan repayment (principal + interest allocation)
  ├─ processPrepayment()       → Early loan closure (no penalty on floating)
  ├─ reverseTransaction()      → Reverse posted transaction (contra journal)
  └─ chargeProcessingFee()     → Charge disbursement fees (GL: DR Bank / CR Fee Income)

NPA Classification (RBI IRAC):
  Standard:    DPD = 0
  SMA-0:       0 < DPD ≤ 30
  SMA-1:       31 ≤ DPD ≤ 60
  SMA-2:       61 ≤ DPD ≤ 90
  Sub-Std:     90 < DPD ≤ 180
  Doubtful:    DPD > 180 (subdivided: 0-1yr, 1-3yr, >3yr)
  Loss:        DPD > 3 years

Transaction Reversal:
  Original: DR Loan Asset / CR Bank Operations
  Reversal: DR Bank Operations / CR Loan Asset (exact mirror)
  Audit: Original marked REVERSED, reversal creates new record

RBI Compliance: IRAC, Fair Lending Code 2023, transaction reversals
Performance: Each operation routes through TransactionEngine (10-step validation)
```

---

### **Configuration & Infrastructure** (App Config, Utilities)
```
Location: com.finvanta.config.* + com.finvanta.util.*

Key Components:
  ├─ CommonModelAdvice        → Populates model attributes (businessDate, userRole)
  ├─ TenantContext             → ThreadLocal tenant isolation (zero cross-tenant leakage)
  ├─ TenantFilter              → Request interception, tenant extraction
  ├─ SecurityConfig            → RBAC configuration
  ├─ JacksonConfig             → JSON serialization (dates as ISO-8601)
  ├─ PiiEncryptionConverter    → PII field encryption (optional)
  └─ CbsStartupLogger          → Application startup logging

Key Utilities:
  ├─ TenantContext.getCurrentTenant()    → Get current tenant from ThreadLocal
  ├─ TenantContext.setCurrentTenant()    → Set by TenantFilter
  ├─ SecurityUtil.getCurrentUsername()   → Get authenticated user
  ├─ SecurityUtil.getCurrentUserRole()   → Get user's primary role
  ├─ ReferenceGenerator.generateJournalRef()     → Generate JNL20260407001
  ├─ ReferenceGenerator.generateTransactionRef() → Generate TXN20260407001
  ├─ BusinessException                  → Custom exception for business errors
  └─ ValidationException                 → Custom exception for validation errors

Tenant Isolation:
  Request Header (X-Tenant-Id) or Session (TENANT_ID)
  → TenantFilter.doFilter()
  → TenantContext.setCurrentTenant(tenantId)
  → All queries filtered by tenantId
  → finally: TenantContext.clear()
  
  Result: Thread-isolated, no cross-tenant leakage

Business Calendar Integration:
  ├─ CommonModelAdvice: UI always shows CBS businessDate (not LocalDate.now())
  ├─ TransactionEngine Step 2: Validate value date exists in calendar
  ├─ EodOrchestrator: Uses businessDate (not system date)
  └─ Critical: Never use LocalDate.now() for financial operations

Application Profiles:
  ├─ application.yml (shared defaults)
  ├─ application-dev.properties (H2, debug logging)
  ├─ application-prod.properties (SQL Server, restricted logging)
  └─ application-test.properties (testing)

Database Configuration:
  Dev:  H2 in-memory (jdbc:h2:mem:finvantadb, ddl-auto=update)
  Prod: SQL Server (jdbc:sqlserver://..., ddl-auto=validate)
```

---

### **Workflow & Approval** (Loan Application & Transaction Approval)
```
Location: com.finvanta.service.* + com.finvanta.workflow.*

Key Service:
  └─ ApprovalWorkflowService  → Manages loan application and transaction approvals

Workflow Entities:
  ├─ entityType: LOAN_APPLICATION | TRANSACTION
  ├─ status: PENDING_APPROVAL | APPROVED | REJECTED | CANCELLED
  ├─ createdBy: MAKER (initiator)
  ├─ approvedBy: CHECKER (approver)
  └─ rejectionReason: Optional

Loan Application Workflow:
  MAKER: Submit application (SUBMITTED)
    ↓
  CHECKER: Verify documents & KYC (KYC_VERIFIED)
    ↓
  CHECKER: Approve/Reject (APPROVED or REJECTED)
    ↓
  CHECKER: Create loan account (ACCOUNT_CREATED)
    ↓
  CHECKER: Disburse loan (GL posting, account status ACTIVE)

Transaction Approval:
  For transactions above maker-checker threshold:
  ├─ MAKER initiates high-value txn
  ├─ TransactionEngine Step 7: Requires approval? YES
  ├─ Create ApprovalWorkflow (PENDING_APPROVAL)
  ├─ CHECKER reviews & approves
  ├─ Re-execute TransactionEngine with preApproved flag
  └─ GL posting proceeds

RBI Compliance: Internal Controls (segregation of duties), approval workflows
Performance: Queued approvals, no impact on transactional flow
```

---

## Quick Lookup Table

| Question | Answer | Reference |
|----------|--------|-----------|
| How does the GL posting engine prevent direct calls? | ENGINE_TOKEN (ThreadLocal UUID) in AccountingService | MODULE_WISE 1.1 |
| What are the 10 validation steps? | Business date, day status, amount, branch, limits, maker-checker, GL posting, voucher, audit | MODULE_WISE 2.1 |
| How is the ledger tamper-proof? | SHA-256 hash chain (previousHash linkage) | MODULE_WISE 1.2 |
| What are RBI IRAC provisioning percentages? | Standard 0.40%, Sub-Std 15-25%, Doubtful 25-100%, Loss 100% | MODULE_WISE 3.2 |
| How does maker-checker work? | Threshold-based: below = auto-approved, above = PENDING_APPROVAL | MODULE_WISE 5.4 |
| What is DPD (Days Past Due)? | Calculated from oldest overdue installment during EOD Step 2 | MODULE_WISE 3.1 |
| How are GL balances protected from lost updates? | PESSIMISTIC_WRITE lock on GL master rows during update | APPLICATION_SUMMARY VI.2 |
| What's the multi-tenant isolation mechanism? | ThreadLocal TenantContext + TenantFilter on every request | MODULE_WISE 5.2 |
| How is the audit trail immutable? | Hash chain + REQUIRES_NEW transaction propagation (persists on parent rollback) | MODULE_WISE 4.1 |
| What happens during EOD if one account fails? | Per-account error isolation: failed account skipped, EOD continues for others | MODULE_WISE 3.1 |

---

## Key Diagrams

### Transaction Flow
```
Module (Loan/Deposit)
  ├─ Build TransactionRequest
  └─ TransactionEngine.execute(request)
      ├─ Steps 1-7: Validation & auth
      ├─ Step 8: GL Posting via AccountingService
      │  ├─ Verify ENGINE_TOKEN (security)
      │  ├─ Post journal entry (double-entry)
      │  ├─ Update GL balances (pessimistic lock)
      │  ├─ Post to ledger (hash chain)
      │  └─ Update batch totals
      ├─ Step 9: Generate voucher
      └─ Step 10: Audit log (hash chain)
  ├─ Module updates own subledger
  └─ Return to client
```

### EOD Sequence
```
EodOrchestrator.executeEod(businessDate)
  ├─ Validate & lock business day
  ├─ Step 1: Mark overdue installments
  ├─ Step 2: Update account DPD (per-account)
  ├─ Step 3: Interest accrual (per-account)
  ├─ Step 4: Penal interest (per-account)
  ├─ Step 5: NPA classification (per-account)
  ├─ Step 6: Provisioning (per-account group)
  ├─ Step 7: GL reconciliation
  └─ Finalize job (COMPLETED | PARTIALLY_COMPLETED | FAILED)
```

### Maker-Checker Gate
```
User initiates high-value txn (above per_txn_limit)
  ↓
TransactionEngine.execute()
  ├─ Steps 1-6: Validation OK
  └─ Step 7: MakerCheckerService.requiresApproval() → true
      ├─ Create ApprovalWorkflow (PENDING_APPROVAL)
      ├─ Return TransactionResult (status=PENDING_APPROVAL)
      └─ NO GL posting yet
        ↓
[CHECKER reviews and approves]
        ↓
MakerCheckerService.approveTransaction()
  ├─ Re-execute TransactionEngine (preApproved=true)
  ├─ Step 7: Skipped (already approved)
  ├─ Steps 8-10: GL posting + voucher + audit
  └─ Return TransactionResult (status=POSTED)
```

---

## Document Statistics

| Metric | Value |
|--------|-------|
| Total Modules | 8 |
| Key Services | 20+ |
| Database Tables | 25+ |
| GL Chart of Accounts | 14 (1xxx-5xxx) |
| RBI IRAC Provisions | 7 categories |
| EOD Steps | 7 |
| Transaction Engine Steps | 10 |
| Pages (APPLICATION_SUMMARY) | ~80 |
| Pages (MODULE_WISE_SUMMARY) | ~100 |

---

## Document Access Guide

**For Regulatory Review:**
1. Start: APPLICATION_SUMMARY Section V (Compliance Mappings)
2. Dive-deep: MODULE_WISE_SUMMARY Sections 1-4 (Accounting, Txn Engine, Batch, Audit)

**For Architecture Design:**
1. Start: APPLICATION_SUMMARY Section I (Architecture Overview)
2. Dive-deep: MODULE_WISE_SUMMARY Section 2 (TransactionEngine - 10 steps)
3. Reference: APPLICATION_SUMMARY Section VI (Performance)

**For Development/Implementation:**
1. Start: MODULE_WISE_SUMMARY (comprehensive service documentation)
2. Patterns: Key design patterns for each module
3. RBI Compliance: Applicable guidelines per operation

**For Operations/Deployment:**
1. Start: APPLICATION_SUMMARY Section VIII (Deployment & Operations)
2. Test: Section IX (Testing & Validation)
3. Readiness: Section X (Production Assessment)

---

**Generated:** April 6, 2026 | **Version:** 0.0.1-SNAPSHOT | **Status:** Complete



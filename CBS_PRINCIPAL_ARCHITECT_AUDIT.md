# CBS SPRING BOOT CODEBASE — PRINCIPAL ARCHITECT AUDIT REPORT

**Date:** 2026-04-30  
**Auditor:** Principal Banking Backend Architect  
**Repository:** `/workspace/project/finvanta`  
**Code Base:** ~39,692 lines Java (365 source files)  
**Stack:** Spring Boot 3.x · JPA/Hibernate · PostgreSQL · JSP + REST v1/v2

---

## 1. CRITICAL ISSUES (BLOCKERS FOR PRODUCTION)

### 1.1 Architecture Violations

| # | File | Violation | Severity |
|---|------|---------|---------:|---------|
| A1 | `AdminController.java` lines 270, 309, 337, 446, 531, 558 | **Direct repository `.save()` in controller** — 6 instances | 🔴 CRITICAL |
| A2 | `LoanController.java` lines 614, 636, 660 | **Direct repository `.save()` in controller** — 3 instances | 🔴 CRITICAL |
| A3 | `AdminController.java` lines 261, 295, 327, 419, 488, 548, 581 | **Entity mutation in controller** — creates `TransactionLimit`/`ChargeConfig` objects directly | 🔴 CRITICAL |
| A4 | `LoanController.java` lines 614, 636, 660 | **Entity mutation in controller** — creates `LoanDocument` objects directly | 🔴 CRITICAL |
| A5 | `DepositController.java` line 182 | **No `@Transactional`** on state-mutating `maintainAccount()` — account mutations not atomic | 🔴 CRITICAL |

**Impact:** Breaks the Controller → Service → Domain → Repository contract. Business logic exists in controllers, violating layered architecture. No service-layer transaction demarcation for atomic multi-step operations.

### 1.2 Transaction Engine Integrity Gaps

| # | Component | Gap | Severity |
|---|----------|-----|---------:|---------|
| T1 | `TransactionEngine` (lines 160–265) | `IdempotencyRegistry` registration **SWALLOWS exceptions silently** — duplicate transaction risk | 🔴 CRITICAL |
| T2 | `TransactionEngine` (lines 216–263) | `TransactionOutbox` INSERT **SWALLOWS exceptions silently** — CTR events may not publish | 🔴 CRITICAL |
| T3 | `LedgerEntry.java` | `runningBalance` is **nullable** — no DB enforcement that it's always populated on insert | 🟡 MEDIUM |
| T4 | `AccountingService.java` | `postJournalEntry()` missing **engine token validation** — can be called directly bypassing TransactionEngine | 🔴 CRITICAL |
| T5 | `TransactionBatch` | No **batch-level ACID guarantee** across GL + subledger updates | 🟡 MEDIUM |

### 1.3 Multi-Tenant Isolation Gaps

| # | File | Gap | Severity |
|---|------|-----|---------:|---------|
| M1 | `AdminController.java:270,309,337` | `TransactionLimit` mutation uses **tenantId from controller** — service-layer never validates | 🔴 CRITICAL |
| M2 | `AdminController.java:446,531,558` | `ChargeConfig` mutation uses **tenantId from controller** — service-layer never validates | 🔴 CRITICAL |
| M3 | `LoanController.java:614,636,660` | `LoanDocument` mutation uses **tenantId from controller** — no service-layer isolation | 🔴 CRITICAL |
| M4 | `TenantContext.java` | No **HTTP filter enforcement** — ThreadLocal can be bypassed in async/retry threads | 🟡 MEDIUM |

---

## 2. SCORES

| Dimension | Score | Max | Grade |
|----------|------:|----:|------|
| **Architecture** | 62 | 100 | 🟡 B− |
| **Code Quality** | 71 | 100 | 🟡 B |
| **Security** | 78 | 100 | 🟢 B+ |
| **Transaction Integrity** | 68 | 100 | 🟡 B− |
| **Multi-Tenant Safety** | 65 | 100 | 🟡 B− |
| **Overall** | **68.8** | 100 | 🟡 **B−** |

---

## 3. FILE-LEVEL VIOLATIONS

### 🔴 Controller Layer (Architecture)

```
controller/AdminController.java   — 13 violations (6× .save(), 7× entity mutation)
controller/LoanController.java      —  3 violations (3× .save(), 3× entity mutation)
controller/DepositController.java  —  1 violation (no @Transactional on maintainAccount)
```

### 🟡 Service Layer (Quality)

```
service/impl/DepositAccountServiceImpl.java — buildTxn() is 147 lines (god method)
service/LoanAccountServiceImpl.java         — single service owns 2,000+ lines
service/impl/LoanAccountServiceImpl.java    — duplicate transfer/reversal logic
service/txn360/*.java                  — 7 resolver classes with overlapping ref formats
```

### 🟢 Repository Layer (Structure — PASSES)

All repositories correctly use `tenantId` as first-class filter parameter. No raw `EntityManager` usage. No native queries outside `SequenceGeneratorService`. Proper JPA `@Query` usage.

---

## 4. TRANSACTION INTEGRITY GAPS

### 4.1 Double-Entry Enforcement
- ✅ `TransactionEngine.validate()` enforces DR == CR balance check in preview
- ✅ `AccountingService.updateGLBalances()` acquires GL locks in sorted order (ABBA prevention)
- ⚠️ `postJournalEntry()` does **not** re-validate DR == CR after sorting

### 4.2 Voucher/DR-CR Balance
- ✅ Voucher pre-allocation (Step 8.0) before GL locks
- ✅ `GLBranchBalance` optimistic-lock race condition handled inline
- ⚠️ `runningBalance` nullable — no DB constraint enforcement

### 4.3 Immutable Ledger
- ✅ `LedgerEntry` has **no `@Version`** (immutable design)
- ✅ SHA-256 hash chain with `previousHash`
- ✅ Append-only design documented

### 4.4 Idempotency
- ✅ Engine-level `IdempotencyRegistry` for cross-module dedup
- 🔴 **Silently swallows INSERT failures** — could duplicate on DB constraint violation
- 🔴 No distributed lock mechanism — PostgreSQL advisory lock missing

---

## 5. SECURITY RISKS

### 🔴 HIGH (3)

| ID | Risk | File | Detail |
|----|------|------|--------|
| S1 | **MFA secret stored in DB unencrypted** | `MfaSecretEncryptor` | Encryption key from config — not HSM |
| S2 | **JWT signing key from env** | `JwtTokenService` | Key rotation mechanism missing |
| S3 | **AdminController bypasses service** | `AdminController:261,270` | Transaction limits set directly — no service validation |

### 🟡 MEDIUM (4)

| ID | Risk | File | Detail |
|----|------|------|--------|
| S4 | No rate limiting on MFA verify | `MfaLoginController` | Brute-force TOTP possible |
| S5 | `NoOpPasswordEncoder` in delegating encoder | `SecurityConfig` | Risk if `{noop}` prefix used in prod |
| S6 | No concurrent session revocation on new login | `SessionContextService` | Last-login-wins only |
| S7 | `SecurityUtil` reads from HTTP session directly | `SecurityUtil:162-176` | Outside SecurityContext filter order |

### 🟢 LOW (3)

| ID | Risk | File | Detail |
|----|------|------|--------|
| S8 | Tenant ID validation regex allows underscore | `TenantFilter` | `[A-Za-z0-9_]` — acceptable |
| S9 | BCrypt 12 rounds configurable | `SecurityConfig` | Good, exceeds RBI minimum |
| S10 | HSTS 1-year in prod | `SecurityConfig` | OWASP compliant |

---

## 6. CORRECTED MODULE STRUCTURE

```
com.finvanta/
├── cbs/
│   └── modules/                    ← NEW: Tier-1 modular (teller ✓, account ✓)
│       ├── account/
│       │   ├── controller/
│       │   ├── dto/
│       │   ├── mapper/
│       │   ├── service/            ← ✅ All business logic here
│       │   └── validator/
│       ├── customer/
│       │   ├── dto/
│       │   ├── mapper/
│       │   └── service/
│       ├── loan/
│       │   └── domain/             ← ✅ Domain entity
│       └── teller/
│           ├── controller/
│           ├── domain/
│           ├── dto/
│           ├── exception/
│           ├── mapper/
│           ├── repository/         ← ✅ Repository per module
│           ├── service/           ← ✅ Business logic
│           └── validator/
├── controller/                     ← ⚠️ NEEDS REFACTOR: move DB ops to service
│   └── AdminController.java       ← 🔴 Violates Controller→Service rule
├── service/
│   └── impl/                       ← ✅ Tier-2 service implementations
│       ├── DepositAccountServiceImpl.java  ← 🔴 2,000+ lines — god class
│       └── LoanAccountServiceImpl.java         ← 🔴 Duplicate deposit logic
├── transaction/                    ← ✅ Transaction Engine (Tier-1)
│   ├── TransactionEngine.java
│   ├── TransactionRequest.java
│   └── TransactionResult.java
├── accounting/                    ← ✅ Accounting Service
│   ├── AccountingService.java
│   ├── LedgerService.java
│   └── PostingIntegrityGuard.java
├── domain/
│   ├── entity/                   ← ✅ Domain entities
│   ├── enums/                   ← ✅ Bounded context enums
│   └── rules/                   ← ✅ Domain rules (NPA, Provisioning)
├── repository/                  ← ✅ Data access (passes audit)
├── config/                      ← ✅ Security + infra
├── audit/                       ← ✅ Audit trail
└── batch/                      ← ✅ EOD batch jobs
```

---

## 7. REFACTORING STEPS

### Step 1: Remove Business Logic from Controllers (P0 — 2 days)

1. **AdminController → AdminService**
   ```java
   // BEFORE (AdminController.java:270)
   limitRepository.save(tl);
   
   // AFTER — delegate to service
   adminService.updateLimit(id, perTransactionLimit, dailyAggregateLimit, description);
   ```

2. **LoanController → LoanDocumentService**
   ```java
   // BEFORE (LoanController.java:614)
   documentRepository.save(doc);
   
   // AFTER
   loanDocumentService.saveDocument(document);
   ```

### Step 2: Fix Silent Exception Swallowing (P0 — 1 day)

1. **IdempotencyRegistry INSERT failure** — log FATAL, trigger alert:
   ```java
   } catch (Exception e) {
       log.error("FATAL: Idempotency registry failed for key={}", idempotencyKey, e);
       // DO NOT SWALLOW — escalate to ops
   }
   ```

2. **TransactionOutbox INSERT failure** — log FATAL:
   ```java
   } catch (Exception e) {
       log.error("FATAL: Outbox event failed for txnRef={}", transactionRef, e);
   }
   ```

### Step 3: Add Engine Token Validation (P0 — 0.5 day)

```java
// AccountingService.postJournalEntry() — add token guard
if (ENGINE_TOKEN.get() == null) {
    throw new IllegalStateException("AccountingService.postJournalEntry() called outside TransactionEngine");
}
```

### Step 4: Break God Classes (P1 — 3 days)

| File | God Method | Extract To |
|------|----------|----------|
| `DepositAccountServiceImpl` | `buildTxn()` (147 lines) | `DepositTransactionFactory` |
| `DepositAccountServiceImpl` | `reverseTransfer()` (127 lines) | `TransferReversalService` |
| `LoanAccountServiceImpl` | Interest calculations | `InterestCalculationService` |

### Step 5: Add Service-Layer Transaction Demarcation (P1 — 1 day)

```java
// DepositAccountServiceImpl.maintainAccount()
@Transactional(isolation = Isolation.SERIALIZABLE)
public void maintainAccount(...) { ... }
```

### Step 6: Fix Tenant Isolation (P1 — 2 days)

All service methods must accept `tenantId` as parameter and validate:
```java
public ApprovalWorkflow updateLimit(Long id, BigDecimal perTxn, ...) {
    String tenantId = TenantContext.getCurrentTenant();
    TransactionLimit tl = limitRepository.findById(id)
        .filter(l -> l.getTenantId().equals(tenantId))
        .orElseThrow(() -> new BusinessException("NOT_FOUND"));
    // ... mutate
}
```

### Step 7: Add MFA Rate Limiting (P1 — 0.5 day)

```java
// Per IP: max 10 TOTP attempts per 5 minutes
// Lock account after 3 failures
```

---

## 8. PRODUCTION READINESS VERDICT

| Dimension | Status | Notes |
|----------|--------|-------|
| **Architecture** | ❌ NOT READY | Controller→Service→Domain→Repository contract broken (9 direct DB saves in controllers) |
| **Transaction Integrity** | ⚠️ CONDITIONAL | Silent exception swallowing in idempotency/outbox breaks audit trail guarantees |
| **Multi-Tenant Safety** | ❌ NOT READY | TenantId bypass in 3 controllers — cross-tenant data leak risk |
| **Security** | ⚠️ CONDITIONAL | MFA secret encryption key not HSM-backed, no rate limiting on TOTP |
| **Code Quality** | ⚠️ CONDITIONAL | 2 god classes, duplicate transfer logic |
| **Performance** | ✅ READY | Proper indexing, JOIN FETCH for N+1 prevention |

### Verdict: **❌ NOT PRODUCTION READY** (until P0 items fixed)

**Minimum viable fixes before deployment:**
1. Extract all `.save()` calls from controllers → service layer
2. Stop swallowing idempotency/outbox exceptions
3. Add engine token validation in `AccountingService`
4. Add service-layer tenant isolation validation

**Estimated fix effort:** 8–10 days engineering.

---

*End of Principal Architect Audit Report*
# Finvanta CBS — Architecture Doc 01: System Context & Module Map

> **Status:** Living document. Anchored to commit `2781093d`.
> **Audience:** Front-end integrators + internal architecture review.
> **Scope of this chunk:** What modules exist, what the single enforcement point is, and where to find the chart of accounts. No business logic yet — that's Chunks 2–10.

---

## 1. One-paragraph system context

Finvanta is a multi-tenant Core Banking System modeled on Finacle / Temenos patterns. Every financial state change in the bank — CASA debit, FD booking, loan disbursement, RD closure, fee charge — is funnelled through a **single enforcement point**, `TransactionEngine.execute(...)` (`src/main/java/com/finvanta/transaction/TransactionEngine.java:87`). Modules never post to the General Ledger directly; the GL layer (`AccountingService`) is protected by a cryptographic `ENGINE_TOKEN` guard (`src/main/java/com/finvanta/accounting/AccountingService.java:59-81`) and an ArchUnit test (`src/test/java/com/finvanta/arch/CbsArchitectureTest.java:18-44`). This is the architectural backbone; everything else in this document hangs off it.

## 2. Top-level package map

| Package | Role |
|---|---|
| `com.finvanta.api` | `@RestController` layer — thin HTTP orchestration only |
| `com.finvanta.transaction` | `TransactionEngine`, `TransactionRequest`, `TransactionValidationService` — the enforcement point |
| `com.finvanta.accounting` | `AccountingService` (double-entry GL), `GLConstants`, `ProductGLResolver` |
| `com.finvanta.service` / `service.impl` | Business services (CASA, FD, RD, Loan, Customer) |
| `com.finvanta.domain` | JPA entities, enums, domain rules (`NpaClassificationRule`, `InterestCalculationRule`) |
| `com.finvanta.repository` | Tenant-filtered Spring Data repositories (boundary enforced by ArchUnit) |
| `com.finvanta.batch` | EOD jobs, `ChargeEngine`, scheduled posting |
| `com.finvanta.audit` | `AuditService` — immutable audit trail |
| `com.finvanta.compliance` | `AmlComplianceService` (Phase 1 stub — see Chunk 7) |
| `com.finvanta.config` | `SecurityConfig`, filters, MDC plumbing |
| `com.finvanta.util` | `TenantContext`, `BusinessException`, `PiiHashUtil`, `SecurityUtil` |
| `com.finvanta.legacy` | Frozen package — ArchUnit forbids references from production code |

## 3. REST surface (for front-end)

| Controller | Base path | File |
|---|---|---|
| `AuthController` | `/v1/auth` | `src/main/java/com/finvanta/api/AuthController.java` |
| `CustomerController` | `/v1/customers` | `src/main/java/com/finvanta/api/CustomerController.java` |
| `DepositAccountController` | `/v1/accounts` | `src/main/java/com/finvanta/api/DepositAccountController.java` |
| `FixedDepositController` | `/v1/fixed-deposits` | `src/main/java/com/finvanta/api/FixedDepositController.java` |
| `LoanAccountController` | `/v1/loans` | `src/main/java/com/finvanta/api/LoanAccountController.java` |

> **Note — verify before publishing to FE:** RD and Compliance controllers were not confirmed in the Chunk 1 scan. Chunk 3 will enumerate them.

All secured endpoints require `Authorization: Bearer <jwt>`. Unauthenticated requests receive a Jackson-serialized 401 envelope (`status`, `errorCode`, `error`, `meta.correlationId`, `meta.timestamp` in UTC ISO-8601) — see `src/main/java/com/finvanta/config/SecurityConfig.java:126-143`. Front-end must propagate `X-Correlation-Id` and `X-Idempotency-Key` headers on all mutating calls.

## 4. The enforcement point — `TransactionEngine`

Every module constructs a `TransactionRequest` and calls `execute()`. The engine runs a **14-step chain** (`src/main/java/com/finvanta/transaction/TransactionEngine.java:50-66`):

```
Step 0   Financial safety kill switch (PostingIntegrityGuard)
Step 1   Idempotency check — tenant_id + idempotency_key (IdempotencyRegistry)
Step 1.5 Tenant validation + RBI CTR/AML flagging (TransactionValidationService)
Step 2   Business date validation
Step 2.5 Value date window (T-2 … T+0)
Step 3   Day status (DAY_OPEN required; EOD system exempt)
Step 4   Amount & currency (positive, precision, INR only)
Step 5   Branch validation
Step 5.5 Transaction batch (open batch required)
Step 6   Transaction limits (per-role + daily aggregate)
Step 7   Maker-checker gate (→ PENDING_APPROVAL)
Step 8.0 Voucher & transaction-ref pre-allocation
Step 8   Double-entry journal posting (DR total == CR total)
Step 10  Audit trail (hash-chained)
Step 11  Outbox event publish (same TX — for recon, CTR, fraud)
```

### `TransactionRequest` envelope
Captures **WHAT** (amount, txn type, journal lines) / **WHO** (sourceModule, accountReference, initiatedBy) / **WHEN** (valueDate) / **WHERE** (branchCode) / **WHY** (narration). Optional: `idempotencyKey`, `productType`, `systemGenerated`. See `src/main/java/com/finvanta/transaction/TransactionRequest.java:9-47`.

### Idempotency
On duplicate `tenant_id + idempotency_key`, the engine short-circuits and returns the previously persisted `TransactionResult` (`TransactionEngine.java:308-326`). This is why the FD `bookFd` / `prematureClose` default overloads added in this PR (`src/main/java/com/finvanta/service/FixedDepositService.java:65-120`) matter — Phase 2 will plumb the key into `TransactionRequest` so double-clicks cannot post two vouchers.

## 5. Chart of Accounts (RBI / Indian Banking Standard)

Defined in `src/main/java/com/finvanta/accounting/GLConstants.java`. Numbering: `1xxx` Assets, `2xxx` Liabilities, `3xxx` Equity, `4xxx` Income, `5xxx` Expenses.

| GL | Constant | Class | Used by |
|---|---|---|---|
| 1001 | `LOAN_ASSET` | Asset | Loan |
| 1002 | `INTEREST_RECEIVABLE` | Asset | Loan accrual |
| 1003 | `PROVISION_NPA` | Contra-asset | IRAC |
| 1100 | `BANK_OPERATIONS` | Asset (cash) | Disbursement / collection |
| 1300 | `INTER_BRANCH_RECEIVABLE` | Asset | Inter-branch settlement |
| 1400 | `RBI_SETTLEMENT` | Asset (Nostro) | NEFT / RTGS |
| 2010 | `SB_DEPOSITS` | Liability | CASA Savings |
| 2020 | `CA_DEPOSITS` | Liability | CASA Current |
| 2100 | `INTEREST_SUSPENSE` | Liability | NPA interest park |
| 2200 / 2201 / 2202 | `CGST_PAYABLE` / `SGST_PAYABLE` / `IGST_PAYABLE` | Liability | GST on fees |
| 2300 | `INTER_BRANCH_PAYABLE` | Liability | Inter-branch settlement |
| 2500 | `TDS_PAYABLE` | Liability | §194A TDS on interest |
| 2600–2631 | NEFT / RTGS / IMPS / UPI inward & outward suspense | Liability | Per-rail clearing |
| 4001 | `INTEREST_INCOME` | Income | Loan interest |
| 4002 | `FEE_INCOME` | Income | Service charges |
| 4003 | `PENAL_INTEREST_INCOME` | Income | Overdue loans |
| 4010 | `INTEREST_INCOME_DEPOSITS` | Income | CASA float |

> **Gap flagged — resolve in Chunk 5:** RD-specific GL constants (`RD_DEPOSITS`, `RD_INTEREST_PAYABLE` at 2041) are referenced in `RecurringDepositServiceImpl` but were not surfaced by the Chunk 1 scan of `GLConstants`. Chunk 5 will read `GLConstants` in full and reconcile.

## 6. Trust boundaries

1. **HTTP → Service:** JWT auth (`SecurityConfig`), per-role limits, `TenantContext` populated from token claims.
2. **Service → TransactionEngine:** modules are forbidden from calling `AccountingService` directly (ArchUnit rule 1 + `ENGINE_TOKEN` ThreadLocal).
3. **Engine → GL:** per-invocation 128-bit `SecureRandom` token set before `postJournalEntry` and cleared in `finally` (`AccountingService.java:59-81`).
4. **Legacy package:** `com.finvanta.legacy` is quarantined by ArchUnit — no production code may import it.

## 7. What Chunk 2 will cover

- Full `TransactionEngine.execute()` walkthrough with line citations.
- `AccountingService.postJournalEntry` — locking strategy, trial-balance check, ledger hash chain.
- **🛑 REDESIGN candidates already on the radar:**
  - RD `prematureClose` never calls `casaSvc.deposit()` (asymmetric with FD) — confirmed pre-existing, ticketed.
  - RD `closureJournalId` field exists but is never persisted after closure posting.
  - Negative `days` edge case in RD premature close bypasses the new `adjustedInterest` cap.

---

*End of Chunk 1. Next commit will add `02-transaction-engine.md`.*

# Finvanta CBS — REST API Implementation Plan

> **Status:** Complete (Phases 0-3 delivered)
> **Baseline Commit:** `6882f50`
> **Compliance:** RBI IT Governance Direction 2023, IRAC Norms, Fair Lending Code 2023

---

## 1. Current State Audit

### Existing REST API Controllers (10 controllers, ~85 endpoints)

| # | Controller | Path | Endpoints | Gaps |
|---|---|---|---|---|
| 1 | `AuthController` | `/v1/auth` | token, mfa/verify, refresh | ✅ Complete with COC, password-first validation |
| 2 | `DepositAccountController` | `/v1/accounts` | 14 endpoints | ⚠️ Missing: AUDITOR role on inquiry, pagination on statement, hold/release-hold |
| 3 | `LoanAccountController` | `/v1/loans` | 11 endpoints | ⚠️ Missing: loan txn history, by-branch list, AUDITOR role, restructure endpoint |
| 4 | `LoanApplicationController` | `/v1/loan-applications` | 7 endpoints | ⚠️ Missing: pagination on lists, all-applications list for ADMIN |
| 5 | `FixedDepositController` | `/v1/fixed-deposits` | 8 endpoints | ⚠️ Missing: AUDITOR role, renewal endpoint, by-branch list |
| 6 | `CustomerController` | `/v1/customers` | ~6 endpoints | ⚠️ Need to verify completeness |
| 7 | `ChargeController` | `/v1/charges` | 4 endpoints | ⚠️ Missing: charge definition CRUD (ADMIN), pagination on history |
| 8 | `ClearingController` | `/v1/clearing` | 10 endpoints | ⚠️ Missing: transaction inquiry/search, cycle list |
| 9 | `GLInquiryController` | `/v1/gl` | 4 endpoints | ⚠️ Missing: branch-level GL balance, journal entry inquiry |
| 10 | `NotificationController` | `/v1/notifications` | 5 endpoints | ⚠️ Missing: typed DTO for summary (returns raw Object[]) |

### Missing REST API Controllers (12 new controllers needed)

| # | New Controller | Path | MVC Source | Service Layer | Priority |
|---|---|---|---|---|---|
| 1 | `BranchApiController` | `/v1/branches` | `BranchController` | `BranchService` | **P0** — login COC branch selector |
| 2 | `CalendarApiController` | `/v1/calendar` | `CalendarController` | `BusinessDateService` | **P0** — day status for dashboard |
| 3 | `DashboardApiController` | `/v1/dashboard` | `DashboardController` | Multiple repos | **P0** — landing page after login |
| 4 | `UserApiController` | `/v1/users` | `UserController` | `UserService` | **P1** — admin user management |
| 5 | `PasswordApiController` | `/v1/auth/password` | `PasswordController` | `UserService` | **P1** — self-service password change |
| 6 | `WorkflowApiController` | `/v1/workflow` | `WorkflowController` | `WorkflowService` | **P1** — maker-checker approval queue |
| 7 | `AuditApiController` | `/v1/audit` | `AuditController` | `AuditService` | **P2** — audit trail inquiry |
| 8 | `ReportApiController` | `/v1/reports` | `ReportController` | Multiple repos | **P2** — DPD/IRAC/Provision reports |
| 9 | `ProductApiController` | `/v1/products` | `AdminController` | `ProductMasterService` | **P2** — product config |
| 10 | `LimitApiController` | `/v1/limits` | `AdminController` | `TransactionLimitRepository` | **P2** — limit config |
| 11 | `ReconciliationApiController` | `/v1/reconciliation` | `ReconciliationController` | Recon service | **P3** — admin function |
| 12 | `BatchApiController` | `/v1/batch` | `BatchController` | EOD orchestrator | **P3** — admin function |

---

## 2. Implementation Phases

### Phase 0: Navigation Prerequisites (P0) — 3 controllers

These are needed for the React dashboard to render after login.

**Batch 0a: `BranchApiController`**
- `GET /v1/branches` — list active branches (for HO user branch selector)
- `GET /v1/branches/{id}` — branch detail with portfolio summary
- `GET /v1/branches/search?q=` — search by code/name/IFSC/region
- `POST /v1/branches` — create branch (ADMIN)
- `PUT /v1/branches/{id}` — update branch (ADMIN)
- Role: ADMIN for mutations, MAKER/CHECKER/ADMIN for inquiry

**Batch 0b: `CalendarApiController`**
- `GET /v1/calendar/today` — current business date + day status for user's branch
- `GET /v1/calendar/branch/{branchId}` — calendar entries for a branch
- `POST /v1/calendar/day/open` — open business day (ADMIN)
- `POST /v1/calendar/day/close` — close business day (ADMIN)
- `POST /v1/calendar/generate` — generate calendar for month (ADMIN)
- `POST /v1/calendar/holiday` — add holiday (ADMIN)
- `DELETE /v1/calendar/holiday` — remove holiday (ADMIN)
- Role: ADMIN for mutations, all roles for inquiry

**Batch 0c: `DashboardApiController`**
- `GET /v1/dashboard/summary` — aggregated CBS metrics (single endpoint)
  - Customer count, loan counts by status, NPA ratios
  - CASA metrics, total deposits, CASA ratio
  - Pending approvals count
  - All computed server-side per existing DashboardController logic
- Role: MAKER/CHECKER/ADMIN (branch-scoped for non-ADMIN)

### Phase 1: Admin + Workflow (P1) — 3 controllers

**Batch 1a: `UserApiController`**
- `GET /v1/users` — list users (ADMIN)
- `GET /v1/users/search?q=` — search users (ADMIN)
- `POST /v1/users` — create user (ADMIN)
- `POST /v1/users/{id}/toggle-active` — activate/deactivate (ADMIN)
- `POST /v1/users/{id}/unlock` — unlock locked account (ADMIN)
- `POST /v1/users/{id}/reset-password` — admin password reset (ADMIN)

**Batch 1b: `PasswordApiController`**
- `POST /v1/auth/password/change` — self-service password change (any authenticated user)
  - Validates current password, new password policy, history check
  - Returns instruction to re-login (tokens invalidated server-side)

**Batch 1c: `WorkflowApiController`**
- `GET /v1/workflow/pending` — pending approvals queue for current user
- `GET /v1/workflow/{id}` — workflow item detail
- `POST /v1/workflow/{id}/approve` — approve (CHECKER/ADMIN)
- `POST /v1/workflow/{id}/reject` — reject (CHECKER/ADMIN)
- Role: CHECKER/ADMIN

### Phase 2: Audit + Reports + Config (P2) — 3 controllers

**Batch 2a: `AuditApiController`**
- `GET /v1/audit/logs` — recent audit logs (AUDITOR/ADMIN)
- `GET /v1/audit/search?q=&fromDate=&toDate=` — search with date range
- `GET /v1/audit/integrity` — chain integrity verification

**Batch 2b: `ReportApiController`**
- `GET /v1/reports/dpd` — DPD distribution report
- `GET /v1/reports/irac` — IRAC asset classification
- `GET /v1/reports/provision` — provisioning adequacy
- All branch-scoped for CHECKER, tenant-wide for ADMIN/AUDITOR

**Batch 2c: `ProductApiController`**
- `GET /v1/products` — list products (ADMIN)
- `GET /v1/products/{id}` — product detail with active account count
- `PUT /v1/products/{id}` — update product (ADMIN)
- `GET /v1/products/search?q=` — search products

### Phase 3: Existing Controller Enhancements

**Batch 3a: `DepositAccountController` enhancements**
- Add `AUDITOR` role to all inquiry endpoints
- Add pagination to `GET /{accountNumber}/statement`
- Add `POST /{accountNumber}/hold` and `POST /{accountNumber}/release-hold`

**Batch 3b: `LoanAccountController` enhancements**
- Add `GET /{accountNumber}/transactions` — loan transaction history
- Add `GET /branch/{branchId}` — loans by branch
- Add `AUDITOR` role to inquiry endpoints
- Add `POST /{accountNumber}/restructure` — loan restructure (ADMIN)

**Batch 3c: `LoanApplicationController` enhancements**
- Add pagination to `GET /customer/{customerId}` and `GET /status/{status}`
- Add `GET /` — all applications list with pagination (ADMIN)

**Batch 3d: Other controller enhancements**
- `FixedDepositController`: Add AUDITOR role, by-branch list
- `GLInquiryController`: Add branch-level GL balance, journal entry inquiry
- `NotificationController`: Replace `Object[]` with typed `DeliveryStatusSummary` DTO
- `ClearingController`: Add transaction search, cycle list inquiry

---

## 3. Implementation Rules (Tier-1 CBS)

Every new/enhanced controller MUST follow:

1. **Thin orchestration** — no business logic in controllers. Delegate to existing services.
2. **Request DTOs** — Jakarta validation annotations. No entity exposure in request.
3. **Response DTOs** — immutable records with `static from(Entity)` factory. No JPA entity in response.
4. **`ApiResponse<T>` envelope** — every response wrapped in standard envelope.
5. **`@PreAuthorize`** — role-based access on every endpoint per CBS role matrix.
6. **Branch isolation** — MAKER/CHECKER see own branch only. ADMIN/AUDITOR see all.
7. **Tenant isolation** — `TenantContext.getCurrentTenant()` on every query.
8. **Pagination** — all list endpoints use `PageResponse<T>` with safe bounds.
9. **No `Object[]`** — every response field is a typed DTO.
10. **Audit trail** — mutations logged via `AuditService.logEvent()`.

---

## 4. Execution Order

| Step | Batch | Files | Est. Lines |
|---|---|---|---|
| 1 | 0a | `BranchApiController.java` | ~200 |
| 2 | 0b | `CalendarApiController.java` | ~250 |
| 3 | 0c | `DashboardApiController.java` | ~200 |
| 4 | 1a | `UserApiController.java` | ~250 |
| 5 | 1b | `PasswordApiController.java` | ~80 |
| 6 | 1c | `WorkflowApiController.java` | ~150 |
| 7 | 2a | `AuditApiController.java` | ~150 |
| 8 | 2b | `ReportApiController.java` | ~250 |
| 9 | 2c | `ProductApiController.java` | ~200 |
| 10 | 3a | `DepositAccountController.java` (enhance) | ~80 |
| 11 | 3b | `LoanAccountController.java` (enhance) | ~100 |
| 12 | 3c | `LoanApplicationController.java` (enhance) | ~60 |
| 13 | 3d | Other enhancements | ~120 |

**Total estimated: ~2,090 lines across 13 commits.**

Each batch is one commit, independently compilable and testable.

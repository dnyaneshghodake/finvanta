# Finvanta CBS — Teller Module REST API Contract

**Base Path:** `/api/v2/teller`
**Auth:** JWT Bearer token (same as v1 endpoints)
**Content-Type:** `application/json` (request + response)
**Response Envelope:** `ApiResponse<T>` — `{ status, data, errorCode, message, timestamp }`

## User Roles

The teller module recognizes the standard CBS role hierarchy
(least → most privilege for transactional operations):

```
TELLER < MAKER < CHECKER < ADMIN
```

`TELLER` is a first-class transactional role — a specialization of MAKER
restricted to the over-the-counter cash channel. Per RBI Internal Controls
the teller's per-transaction (INR 2L) and daily aggregate (INR 10L) limits
are tighter than MAKER (INR 10L / INR 50L) because cash tellerage carries
higher operational risk (physical cash handling, counterfeit exposure,
FICN workflow).

`AUDITOR` is read-only and is excluded from any mutation endpoint below.

## Transaction Limits (per RBI Internal Controls)

| Role | Per-txn | Daily aggregate | Notes |
|------|---------|-----------------|-------|
| TELLER  | INR 2L  | INR 10L  | Cash counter only; WRITE_OFF / REVERSAL / DISBURSEMENT explicitly disabled |
| MAKER   | INR 10L | INR 50L  | Standard maker |
| CHECKER | INR 50L | INR 2Cr  | Approver |
| ADMIN   | INR 5Cr | INR 20Cr | Branch manager |

Above-limit `CASH_DEPOSIT` / `CASH_WITHDRAWAL` requests are HARD-REJECTED
with `TRANSACTION_LIMIT_EXCEEDED` (HTTP 422). They are NOT routed to
maker-checker — the engine's amount-based PENDING_APPROVAL gate applies
only to `REVERSAL`, `WRITE_OFF`, and `WRITE_OFF_RECOVERY` transaction types.

---

## Till Lifecycle

### POST /till/open
Opens a till for the authenticated teller on the current business date.

**Role:** TELLER, MAKER, ADMIN
**Request Body:**
```json
{
  "openingBalance": 50000.00,
  "tillCashLimit": null,
  "remarks": "Morning shift"
}
```
**Response (200):** `ApiResponse<TellerTillResponse>`

### GET /till/me
Returns the authenticated teller's till for today.

**Role:** TELLER, MAKER, CHECKER, ADMIN, AUDITOR
**Response (200):** `ApiResponse<TellerTillResponse>`
**Error (409):** `CBS-TELLER-001` — no till open

### POST /till/{tillId}/approve
Supervisor approves a PENDING_OPEN till.

**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<TellerTillResponse>` (status=OPEN)
**Error (403):** `CBS-WF-001` — maker = checker

### POST /till/close
Teller requests till close with physical cash count.

**Role:** TELLER, MAKER, ADMIN
**Params:** `countedBalance` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<TellerTillResponse>` (status=PENDING_CLOSE, variance computed)

### POST /till/{tillId}/approve-close
Supervisor approves a PENDING_CLOSE till.

**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<TellerTillResponse>` (status=CLOSED)

---

## Cash Deposit

### POST /cash-deposit
Posts a customer cash deposit with denomination breakdown.

**Role:** TELLER, MAKER, ADMIN
**Request Body:**
```json
{
  "accountNumber": "SB-BR001-000001",
  "amount": 2800.00,
  "denominations": [
    { "denomination": "NOTE_500", "unitCount": 5, "counterfeitCount": 0 },
    { "denomination": "NOTE_100", "unitCount": 3, "counterfeitCount": 0 }
  ],
  "idempotencyKey": "uuid-v4-here",
  "depositorName": "Ramesh Kumar",
  "depositorMobile": "9876543210",
  "panNumber": null,
  "narration": "Salary deposit",
  "form60Reference": null
}
```
**Response (200):** `ApiResponse<CashDepositResponse>`
- `pendingApproval: false` → deposit posted
- `pendingApproval: true` → routed to maker-checker

**Error (422):** `CBS-TELLER-008` — counterfeit detected (body includes `FicnAcknowledgementResponse`)
**Error (400):** `CBS-TELLER-004` — denomination sum mismatch
**Error (409):** `CBS-TELLER-001` — till not open
**Error (422):** `CBS-COMP-002` — CTR threshold, PAN/Form60 required

---

## Cash Withdrawal

### POST /cash-withdrawal
Pays out cash to a customer with denomination breakdown.

**Role:** TELLER, MAKER, ADMIN
**Request Body:**
```json
{
  "accountNumber": "SB-BR001-000001",
  "amount": 5000.00,
  "denominations": [
    { "denomination": "NOTE_500", "unitCount": 10, "counterfeitCount": 0 }
  ],
  "idempotencyKey": "uuid-v4-here",
  "beneficiaryName": "Account Holder",
  "beneficiaryMobile": null,
  "chequeNumber": null,
  "narration": "Cash withdrawal"
}
```
**Response (200):** `ApiResponse<CashWithdrawalResponse>`
**Error (422):** `CBS-TELLER-006` — till has insufficient cash
**Error (422):** `CBS-ACCT-006` — insufficient account balance

---

## Vault Operations

### POST /vault/open
Opens the branch vault for the day.

**Role:** CHECKER, ADMIN
**Params:** `openingBalance` (BigDecimal)
**Response (200):** `ApiResponse<VaultPosition>`

### GET /vault/me
Returns the branch vault for today.

**Role:** TELLER, MAKER, CHECKER, ADMIN
**Response (200):** `ApiResponse<VaultPosition>`

### POST /vault/buy
Teller requests cash from vault (vault→till). Creates PENDING movement.

**Role:** TELLER, MAKER, ADMIN
**Params:** `amount` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<TellerCashMovement>` (status=PENDING)

### POST /vault/sell
Teller returns cash to vault (till→vault). Creates PENDING movement.

**Role:** TELLER, MAKER, ADMIN
**Params:** `amount` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<TellerCashMovement>` (status=PENDING)

### POST /vault/movement/{movementId}/approve
Vault custodian approves a PENDING movement. Balances move atomically.

**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<TellerCashMovement>` (status=APPROVED)
**Error (403):** `CBS-WF-001` — maker = checker
**Error (422):** `CBS-TELLER-006` — vault/till has insufficient cash

### POST /vault/movement/{movementId}/reject
Vault custodian rejects a PENDING movement. No balance change.

**Role:** CHECKER, ADMIN
**Params:** `reason` (mandatory)
**Response (200):** `ApiResponse<TellerCashMovement>` (status=REJECTED)

### GET /vault/movements/pending
Returns PENDING movements at the branch for today.

**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<List<TellerCashMovement>>`

### POST /vault/close
Closes the vault after all tills are CLOSED. Custodian enters physical count.

**Role:** CHECKER, ADMIN
**Params:** `countedBalance` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<VaultPosition>` (status=CLOSED, variance computed)

---

## Denomination Enum Values

```typescript
type IndianCurrencyDenomination =
  | 'NOTE_2000' | 'NOTE_500' | 'NOTE_200' | 'NOTE_100'
  | 'NOTE_50'   | 'NOTE_20'  | 'NOTE_10'  | 'NOTE_5'
  | 'COIN_BUCKET';
```

## Error Code Reference

| Code | HTTP | Meaning |
|------|------|---------|
| CBS-TELLER-001 | 409 | No till open for today |
| CBS-TELLER-002 | 409 | Till not in expected state (e.g. CLOSED before checker approval) |
| CBS-TELLER-003 | 403 | Till ownership violation |
| CBS-TELLER-004 | 400 | Denomination sum ≠ amount |
| CBS-TELLER-005 | 400 | Invalid denomination |
| CBS-TELLER-006 | 422 | Till/vault insufficient cash |
| CBS-TELLER-007 | 400 | Till cash limit exceeded (soft cap, routes to maker-checker) |
| CBS-TELLER-008 | 422 | Counterfeit detected (FICN); response includes `FicnAcknowledgementResponse` |
| CBS-TELLER-009 | 400 | Invalid business date |
| CBS-TELLER-010 | 409 | Till already exists for teller on business date |
| CBS-TELLER-099 | 500 | Internal teller error (defensive; e.g. counterfeit on a withdrawal request) |
| CBS-COMP-002  | 422 | CTR threshold (PAN or Form 60/61 required per PMLA Rule 9) |
| CBS-WF-001    | 403 | Maker = checker (self-approval blocked) |
| TRANSACTION_LIMIT_EXCEEDED | 422 | Amount above role's per-transaction limit |
| ACCESS_DENIED | 403 | `@PreAuthorize` denied (insufficient role) |

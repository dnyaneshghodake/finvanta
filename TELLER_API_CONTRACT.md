# Finvanta CBS — Teller Module REST API Contract

**Base Path:** `/api/v2/teller`
**Auth:** JWT Bearer token (same as v1 endpoints)
**Content-Type:** `application/json` (request + response)
**Response Envelope:** `ApiResponse<T>` — `{ status, data, errorCode, message, timestamp }`

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

**Role:** ALL
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

**Role:** ALL
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
| CBS-TELLER-002 | 409 | Till not in expected state |
| CBS-TELLER-003 | 403 | Till ownership violation |
| CBS-TELLER-004 | 400 | Denomination sum ≠ amount |
| CBS-TELLER-005 | 400 | Invalid denomination |
| CBS-TELLER-006 | 422 | Till/vault insufficient cash |
| CBS-TELLER-007 | 400 | Till cash limit exceeded |
| CBS-TELLER-008 | 422 | Counterfeit detected (FICN) |
| CBS-TELLER-009 | 400 | Invalid business date |
| CBS-TELLER-010 | 409 | Till already exists |
| CBS-COMP-002 | 422 | CTR threshold (PAN required) |
| CBS-WF-001 | 403 | Maker = checker |

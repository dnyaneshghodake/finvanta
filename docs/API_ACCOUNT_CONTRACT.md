# Finvanta CBS — CASA Account API Contract v1

> **Base Path:** `/api/v1/accounts`
> **Authentication:** Bearer JWT (Access Token)
> **Tenant:** Resolved from JWT `tenant` claim
> **Branch:** Resolved from JWT `branch` claim
> **Content-Type:** `application/json`

---

## 1. Account Lifecycle

### 1.1 Open Account (Maker)

Creates account in `PENDING_ACTIVATION` status. Requires checker approval.

```
POST /api/v1/accounts/open
Role: MAKER, ADMIN
```

**Request:**
```json
{
  "customerId": 1,
  "branchId": 1,
  "accountType": "SAVINGS",
  "productCode": "SB_GEN",
  "initialDeposit": null,
  "nomineeName": "Jane Doe",
  "nomineeRelationship": "SPOUSE"
}
```

| Field | Type | Required | Values |
|-------|------|----------|--------|
| customerId | Long | Yes | Existing CIF ID (KYC must be verified) |
| branchId | Long | Yes | Active branch ID |
| accountType | String | Yes | SAVINGS, CURRENT, SAVINGS_PMJDY, SAVINGS_NRI, SAVINGS_MINOR, SAVINGS_JOINT, CURRENT_OD |
| productCode | String | No | Product master code (defaults to accountType) |
| initialDeposit | BigDecimal | No | Deferred until activation |
| nomineeName | String | No | Per RBI nomination guidelines |
| nomineeRelationship | String | No | SPOUSE, CHILD, PARENT, SIBLING, OTHER |

**Response (201):**
```json
{
  "status": "SUCCESS",
  "data": {
    "id": 42,
    "accountNumber": "SB-BR001-000001",
    "accountType": "SAVINGS",
    "productCode": "SB_GEN",
    "status": "PENDING_ACTIVATION",
    "branchCode": "BR001",
    "currencyCode": "INR",
    "ledgerBalance": 0.00,
    "availableBalance": 0.00,
    "customerId": 1,
    "customerNumber": "00200000001",
    "customerName": "John Doe",
    "nomineeName": "Jane Doe",
    "nomineeRelationship": "SPOUSE"
  },
  "message": "Account opened in PENDING_ACTIVATION"
}
```

**Validations:**
- Customer must exist and be active
- Customer KYC must be verified (`KYC_NOT_VERIFIED`)
- Branch must be active
- Business day must be open (`NO_BUSINESS_DAY_OPEN`)
- One account per CIF per type per branch (`DUPLICATE_ACCOUNT`)

---

### 1.2 Activate Account (Checker)

Approves a pending account. Transitions to `ACTIVE`.

```
POST /api/v1/accounts/{accountNumber}/activate
Role: CHECKER, ADMIN
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "accountNumber": "SB-BR001-000001",
    "status": "ACTIVE"
  },
  "message": "Account activated"
}
```

**Error:** `ACCOUNT_NOT_FOUND` if account doesn't exist.

---

### 1.3 Reject Account (Checker)

Denies a pending account. Transitions to `CLOSED`.

```
POST /api/v1/accounts/{accountNumber}/reject
Role: CHECKER, ADMIN
```

**Request:**
```json
{
  "reason": "KYC documents incomplete"
}
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "accountNumber": "SB-BR001-000001",
    "status": "CLOSED",
    "closureReason": "KYC documents incomplete"
  },
  "message": "Account rejected"
}
```

---

### 1.4 Pipeline (Pending Accounts)

Returns accounts awaiting checker action. Branch-scoped.

```
GET /api/v1/accounts/pipeline
Role: CHECKER, ADMIN
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": [
    {
      "accountNumber": "SB-BR001-000001",
      "accountType": "SAVINGS",
      "status": "PENDING_ACTIVATION",
      "customerName": "John Doe",
      "branchCode": "BR001",
      "openedDate": "2026-04-12"
    }
  ]
}
```

---

### 1.5 Freeze Account (Admin)

```
POST /api/v1/accounts/{accountNumber}/freeze
Role: ADMIN
```

**Request:**
```json
{
  "freezeType": "DEBIT_FREEZE",
  "reason": "Court order #12345"
}
```

| freezeType | Debits | Credits |
|------------|--------|---------|
| DEBIT_FREEZE | Blocked | Allowed |
| CREDIT_FREEZE | Allowed | Blocked |
| TOTAL_FREEZE | Blocked | Blocked |

---

### 1.6 Unfreeze Account (Admin)

```
POST /api/v1/accounts/{accountNumber}/unfreeze
Role: ADMIN
```

---

### 1.7 Close Account (Checker)

Requires zero ledger balance. Terminal state.

```
POST /api/v1/accounts/{accountNumber}/close
Role: CHECKER, ADMIN
```

**Request:**
```json
{
  "reason": "Customer request"
}
```

---

## 2. Financial Operations

### 2.1 Cash Deposit

```
POST /api/v1/accounts/{accountNumber}/deposit
Role: MAKER, ADMIN
```

**Request:**
```json
{
  "amount": 10000.00,
  "narration": "Cash deposit",
  "idempotencyKey": "DEP-20260412-001",
  "channel": "BRANCH"
}
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "transactionRef": "TXN-20260412-000001",
    "transactionType": "CASH_DEPOSIT",
    "debitCredit": "CREDIT",
    "amount": 10000.00,
    "balanceBefore": 0.00,
    "balanceAfter": 10000.00,
    "valueDate": "2026-04-12",
    "voucherNumber": "VCH/BR001/20260412/000001",
    "branchCode": "BR001"
  }
}
```

**GL:** DR Bank Ops (1100) / CR Customer Deposits (2010/2020)

---

### 2.2 Cash Withdrawal

```
POST /api/v1/accounts/{accountNumber}/withdraw
Role: MAKER, ADMIN
```

Same request/response shape as deposit. Validates sufficient funds.

**Errors:** `INSUFFICIENT_BALANCE`, `ACCOUNT_FROZEN`, `DEBIT_NOT_ALLOWED`

---

### 2.3 Fund Transfer (Internal)

```
POST /api/v1/accounts/transfer
Role: MAKER, ADMIN
```

**Request:**
```json
{
  "fromAccount": "SB-BR001-000001",
  "toAccount": "SB-BR001-000002",
  "amount": 5000.00,
  "narration": "Fund transfer",
  "idempotencyKey": "TRF-20260412-001"
}
```

---

### 2.4 Transaction Reversal

```
POST /api/v1/accounts/reversal/{transactionRef}
Role: CHECKER, ADMIN
```

**Request:**
```json
{
  "reason": "Customer dispute"
}
```

---

## 3. Inquiry

### 3.1 Account Details

```
GET /api/v1/accounts/{accountNumber}
Role: MAKER, CHECKER, ADMIN, AUDITOR
```

Returns full `AccountResponse` with all balance, customer, lifecycle,
freeze, nominee, and facility fields.

---

### 3.2 Balance Inquiry

```
GET /api/v1/accounts/{accountNumber}/balance
Role: MAKER, CHECKER, ADMIN, AUDITOR
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "accountNumber": "SB-BR001-000001",
    "status": "ACTIVE",
    "ledgerBalance": 50000.00,
    "availableBalance": 48000.00,
    "holdAmount": 2000.00,
    "unclearedAmount": 0.00,
    "odLimit": 0.00,
    "effectiveAvailable": 48000.00
  }
}
```

---

### 3.3 Mini Statement

```
GET /api/v1/accounts/{accountNumber}/mini-statement?count=10
Role: MAKER, CHECKER, ADMIN, AUDITOR
```

Returns last N transactions (max 50).

---

### 3.4 Full Statement

```
GET /api/v1/accounts/{accountNumber}/statement?fromDate=2026-01-01&toDate=2026-04-12
Role: MAKER, CHECKER, ADMIN, AUDITOR
```

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "accountNumber": "SB-BR001-000001",
    "accountType": "SAVINGS",
    "fromDate": "2026-01-01",
    "toDate": "2026-04-12",
    "ledgerBalance": 50000.00,
    "availableBalance": 48000.00,
    "transactionCount": 15,
    "transactions": [ ... ]
  }
}
```

---

### 3.5 Customer Accounts

```
GET /api/v1/accounts/customer/{customerId}
Role: MAKER, CHECKER, ADMIN, AUDITOR
```

Returns all accounts for a CIF.

---

### 3.6 Account List (Paginated)

```
GET /api/v1/accounts?branchId=1&page=0&size=20
Role: MAKER, CHECKER, ADMIN, AUDITOR
```

Branch-scoped. ADMIN sees all branches. Excludes CLOSED.

**Response:**
```json
{
  "status": "SUCCESS",
  "data": {
    "content": [ ... ],
    "page": 0,
    "size": 20,
    "totalElements": 142
  }
}
```

---

## 4. Error Codes

| Code | HTTP | Severity | Action |
|------|------|----------|--------|
| ACCOUNT_NOT_FOUND | 404 | LOW | Verify account number |
| INSUFFICIENT_BALANCE | 422 | HIGH | Verify balance or arrange funds |
| ACCOUNT_FROZEN | 422 | HIGH | Contact branch to unfreeze |
| ACCOUNT_CLOSED | 422 | HIGH | Account is closed |
| ACCOUNT_DORMANT | 422 | MEDIUM | Visit branch to reactivate |
| DEBIT_NOT_ALLOWED | 422 | HIGH | Account restrictions apply |
| CREDIT_NOT_ALLOWED | 422 | HIGH | Account restrictions apply |
| DUPLICATE_ACCOUNT | 409 | MEDIUM | One per CIF per type per branch |
| KYC_NOT_VERIFIED | 422 | HIGH | Complete KYC first |
| DUPLICATE_TRANSACTION | 409 | MEDIUM | Already processed |
| NO_BUSINESS_DAY_OPEN | 400 | HIGH | Day must be opened first |
| VALIDATION_FAILED | 400 | LOW | Fix highlighted fields |

---

## 5. Account Status State Machine

```
PENDING_ACTIVATION ──activate──→ ACTIVE
PENDING_ACTIVATION ──reject────→ CLOSED
ACTIVE ──────────────freeze────→ FROZEN
FROZEN ──────────────unfreeze──→ ACTIVE
ACTIVE ──────────────(2yr)─────→ DORMANT
DORMANT ─────────────deposit───→ ACTIVE (reactivation)
ACTIVE ──────────────close─────→ CLOSED
DORMANT ─────────────(10yr)────→ INOPERATIVE
```

---

## 6. Role Matrix

| Operation | MAKER | CHECKER | ADMIN | AUDITOR |
|-----------|:-----:|:-------:|:-----:|:-------:|
| Open Account | ✅ | | ✅ | |
| Activate | | ✅ | ✅ | |
| Reject | | ✅ | ✅ | |
| Pipeline | | ✅ | ✅ | |
| Deposit | ✅ | | ✅ | |
| Withdraw | ✅ | | ✅ | |
| Transfer | ✅ | | ✅ | |
| Reversal | | ✅ | ✅ | |
| Freeze | | | ✅ | |
| Unfreeze | | | ✅ | |
| Close | | ✅ | ✅ | |
| View/Balance/Statement | ✅ | ✅ | ✅ | ✅ |
| Account List | ✅ | ✅ | ✅ | ✅ |

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

### Maker-checker model — two independent mechanisms

The teller module uses TWO separate maker-checker mechanisms; both are
active, and neither overlaps the other:

1. **Supervisor inline checks (fully active):** `maker ≠ checker` is
   enforced in-service on every supervisor action — till open approve /
   reject, till close approve / reject, vault movement approve / reject,
   and vault close (opener ≠ closer per RBI §4.3 joint-custody). Fires
   `CBS-WF-001` (HTTP 403).
2. **Engine PENDING_APPROVAL workflow (not exercised for teller cash):**
   `TransactionEngine.execute` Step 7 can route transactions to
   PENDING_APPROVAL, which later re-executes through
   `ApprovalWorkflowService.approve()` (which also enforces maker ≠
   checker via `WORKFLOW_SELF_APPROVAL`). But for `CASH_DEPOSIT` and
   `CASH_WITHDRAWAL`, Step 6 hard-rejects above-limit amounts before
   Step 7 can route, and neither cash type is in `ALWAYS_REQUIRE_APPROVAL`
   (which contains only `REVERSAL`, `WRITE_OFF`, `WRITE_OFF_RECOVERY`).
   So `pendingApproval: true` is structurally unreachable on teller cash
   responses today. The response field + the `if (r.isPendingApproval())`
   branches in the service are kept as defensive scaffolding for future
   config changes.

---

## Till Lifecycle

All till endpoints return **`TellerTillResponse`**:

```json
{
  "id": 100,
  "tellerUserId": "teller1",
  "branchCode": "BR001",
  "branchName": "New Delhi Main",
  "businessDate": "2026-04-01",
  "status": "OPEN",
  "openingBalance": 100000.00,
  "currentBalance": 102800.00,
  "countedBalance": null,
  "varianceAmount": null,
  "tillCashLimit": 500000.00,
  "openedAt": "2026-04-01T09:00:00",
  "closedAt": null,
  "openedBySupervisor": null,
  "closedBySupervisor": null,
  "remarks": "Morning shift"
}
```

- `status` ∈ `{PENDING_OPEN, OPEN, PENDING_CLOSE, CLOSED, SUSPENDED}`.
- `countedBalance` / `varianceAmount` populated only on close request.
- `openedBySupervisor` / `closedBySupervisor` populated only when dual-control
  sign-off was required (above the auto-approve threshold).

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
```json
{
  "transactionRef": "TXN-20260401-000042",
  "voucherNumber": "VCH/BR001/20260401/000007",
  "accountNumber": "SB-BR001-000001",
  "amount": 2800.00,
  "balanceBefore": 50000.00,
  "balanceAfter": 52800.00,
  "valueDate": "2026-04-01",
  "postingDate": "2026-04-01T10:15:30",
  "narration": "Salary deposit",
  "channel": "TELLER",
  "pendingApproval": false,
  "tillBalanceAfter": 102800.00,
  "tillId": 100,
  "tellerUserId": "teller1",
  "denominations": [
    { "denomination": "NOTE_500", "unitCount": 5, "totalValue": 2500.00, "counterfeitCount": 0 },
    { "denomination": "NOTE_100", "unitCount": 3, "totalValue": 300.00, "counterfeitCount": 0 }
  ],
  "ctrTriggered": false,
  "ficnTriggered": false
}
```

**`pendingApproval` semantics:**
- `false` → deposit POSTED, ledger and till mutated. This is the ONLY reachable
  outcome for `/cash-deposit` on the current engine configuration.
- `true` → reserved for a future configuration where `CASH_DEPOSIT` is added to
  `MakerCheckerService.ALWAYS_REQUIRE_APPROVAL`. Not reachable today (see
  "Maker-checker model" section above). The field stays on the response for
  forward compatibility; BFF clients should treat a `true` value as "pending
  checker approval; ledger and till UNCHANGED" if/when it appears.

**Errors:**
- `CBS-TELLER-001` (409) — till not open for today
- `CBS-TELLER-004` (400) — denomination sum ≠ amount
- `CBS-TELLER-005` (400) — invalid denomination enum
- `CBS-TELLER-008` (422) — counterfeit detected (FICN); response body is `ApiResponse<FicnAcknowledgementResponse>`, not `CashDepositResponse`
- `CBS-COMP-002` (422) — amount ≥ INR 50,000 without PAN or Form 60/61 (PMLA Rule 9)
- `CBS-ACCT-001` (404) — account not found
- `CBS-ACCT-002` (422) — account closed
- `CBS-ACCT-004` (422) — credit frozen (CREDIT_FREEZE / TOTAL_FREEZE)
- `TRANSACTION_LIMIT_EXCEEDED` (422) — amount above role's per-transaction limit

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
```json
{
  "transactionRef": "TXN-20260401-000043",
  "voucherNumber": "VCH/BR001/20260401/000008",
  "accountNumber": "SB-BR001-000001",
  "amount": 5000.00,
  "balanceBefore": 52800.00,
  "balanceAfter": 47800.00,
  "valueDate": "2026-04-01",
  "postingDate": "2026-04-01T11:02:14",
  "narration": "Cash withdrawal",
  "channel": "TELLER",
  "pendingApproval": false,
  "tillBalanceAfter": 97800.00,
  "tillId": 100,
  "tellerUserId": "teller1",
  "denominations": [
    { "denomination": "NOTE_500", "unitCount": 10, "totalValue": 5000.00, "counterfeitCount": 0 }
  ],
  "ctrTriggered": false,
  "chequeNumber": null
}
```

**`pendingApproval` semantics:** identical to `/cash-deposit` — structurally
unreachable on current engine configuration (see "Maker-checker model" section).
The field stays on the response for forward compatibility.

**Errors:**
- `CBS-TELLER-001` (409) — till not open for today
- `CBS-TELLER-004` (400) — denomination sum ≠ amount
- `CBS-TELLER-006` (422) — till has insufficient physical cash (request a vault buy)
- `CBS-TELLER-099` (500) — counterfeit count submitted on a withdrawal
  (the bank never pays out counterfeits; defense-in-depth service guard)
- `CBS-ACCT-001` (404) — account not found
- `CBS-ACCT-002` (422) — account closed
- `CBS-ACCT-004` (422) — debit frozen (DEBIT_FREEZE / TOTAL_FREEZE / DORMANT)
- `CBS-ACCT-005` (422) — insufficient effective available balance
- `CBS-ACCT-007` (422) — minimum-balance breach
- `CBS-ACCT-008` (422) — daily withdrawal limit exceeded
- `TRANSACTION_LIMIT_EXCEEDED` (422) — amount above role's per-transaction limit

---

## FICN Acknowledgement (Counterfeit Detection)

When a cash deposit contains counterfeit-flagged notes, the service impounds
them per RBI Master Direction on Counterfeit Notes and returns HTTP 422 with
error code `CBS-TELLER-008`. The response body carries the printable
**FicnAcknowledgementResponse** customer slip:

```json
{
  "status": "ERROR",
  "errorCode": "CBS-TELLER-008",
  "message": "Counterfeit notes detected and impounded. FICN register: FICN/BR001/20260401/000003 | FIR mandatory per RBI (count >= 5)",
  "data": {
    "registerRef": "FICN/BR001/20260401/000003",
    "originatingTxnRef": "idem-key-abc-123",
    "branchCode": "BR001",
    "branchName": "New Delhi Main",
    "detectionDate": "2026-04-01",
    "detectionTimestamp": "2026-04-01T14:22:08",
    "detectedByTeller": "teller1",
    "depositorName": "Ramesh Kumar",
    "depositorIdType": "PAN",
    "depositorIdNumber": "ABCDE1234F",
    "depositorMobile": "9876543210",
    "impoundedDenominations": [
      { "denomination": "NOTE_500", "counterfeitCount": 6, "totalFaceValue": 3000.00 }
    ],
    "totalFaceValue": 3000.00,
    "firRequired": true,
    "chestDispatchStatus": "PENDING",
    "remarks": null
  }
}
```

- `registerRef` is a **permanent** reference (format `FICN/{branch}/{YYYYMMDD}/{seq}`);
  printed on the customer slip, the FIR copy, and the currency-chest dispatch envelope.
- `firRequired: true` when total counterfeit count across all denominations in the
  transaction ≥ 5 (RBI threshold). BFF must print "FIR mandatory" prominently.
- `chestDispatchStatus` is always `PENDING` on the customer's slip; `DISPATCHED` /
  `REMITTED` values appear only on the supervisor view.
- The `CounterfeitNoteRegister` row is **committed in a REQUIRES_NEW sub-transaction**
  so the register entry survives the FICN-rejection rollback — the slip is always
  backed by a real DB row.

---

## Supervisor Queue (REST parity)

### GET /till/pending
Returns tills at the supervisor's branch awaiting sign-off (PENDING_OPEN or PENDING_CLOSE).
**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<List<TellerTillResponse>>`

### POST /till/{tillId}/reject-open?reason={reason}
Rejects a PENDING_OPEN till (terminal: transitions to CLOSED).
**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<TellerTillResponse>` (status=CLOSED)
**Error (403):** `CBS-WF-001` — maker = checker
**Error (409):** `CBS-TELLER-002` — till not PENDING_OPEN

### POST /till/{tillId}/reject-close?reason={reason}
Rejects a PENDING_CLOSE till (recoverable: returns to OPEN, clears variance).
**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<TellerTillResponse>` (status=OPEN)
**Error (403):** `CBS-WF-001` — maker = checker
**Error (409):** `CBS-TELLER-002` — till not PENDING_CLOSE

---

## Vault Operations

All vault endpoints return DTOs (`VaultPositionResponse` / `TellerCashMovementResponse`)
via `VaultMapper`. Raw JPA entity exposure has been eliminated.

### VaultPositionResponse shape

```json
{
  "id": 11,
  "branchCode": "BR001",
  "branchName": "New Delhi Main",
  "businessDate": "2026-04-01",
  "status": "OPEN",
  "openingBalance": 1000000.00,
  "currentBalance": 950000.00,
  "countedBalance": null,
  "varianceAmount": null,
  "openedBy": "checker1",
  "closedBy": null,
  "remarks": null
}
```

### TellerCashMovementResponse shape

```json
{
  "id": 42,
  "movementRef": "VMOV/BR001/20260401/000007",
  "movementType": "BUY",
  "branchCode": "BR001",
  "tillId": 100,
  "vaultId": 11,
  "businessDate": "2026-04-01",
  "amount": 50000.00,
  "status": "PENDING",
  "requestedBy": "teller1",
  "requestedAt": "2026-04-01T10:30:00",
  "approvedBy": null,
  "approvedAt": null,
  "rejectionReason": null,
  "remarks": null
}
```

### POST /vault/open
Opens the branch vault for the day.

**Role:** CHECKER, ADMIN
**Params:** `openingBalance` (BigDecimal)
**Response (200):** `ApiResponse<VaultPositionResponse>`

### GET /vault/me
Returns the branch vault for today.

**Role:** TELLER, MAKER, CHECKER, ADMIN
**Response (200):** `ApiResponse<VaultPositionResponse>`
**Error (409):** `CBS-TELLER-002` — vault not open for branch today / no branch assignment

### POST /vault/buy
Teller requests cash from vault (vault→till). Creates PENDING movement.

**Role:** TELLER, MAKER, ADMIN
**Params:** `amount` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<TellerCashMovementResponse>` (status=PENDING)

### POST /vault/sell
Teller returns cash to vault (till→vault). Creates PENDING movement.

**Role:** TELLER, MAKER, ADMIN
**Params:** `amount` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<TellerCashMovementResponse>` (status=PENDING)

### POST /vault/movement/{movementId}/approve
Vault custodian approves a PENDING movement. Balances move atomically under
PESSIMISTIC_WRITE locks on vault and till (in that order). Both must be in
OPEN status — a CLOSED-till or CLOSED-vault movement is rejected.

**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<TellerCashMovementResponse>` (status=APPROVED)
**Error (403):** `CBS-WF-001` — maker = checker (requester ≠ custodian enforced inline)
**Error (409):** `CBS-TELLER-002` — movement not PENDING / till not OPEN / vault not OPEN
**Error (422):** `CBS-TELLER-006` — vault/till has insufficient cash

### POST /vault/movement/{movementId}/reject
Vault custodian rejects a PENDING movement. No balance change.

**Role:** CHECKER, ADMIN
**Params:** `reason` (mandatory)
**Response (200):** `ApiResponse<TellerCashMovementResponse>` (status=REJECTED)
**Error (403):** `CBS-WF-001` — maker = checker (requester cannot reject own request per RBI dual-control)
**Error (409):** `CBS-TELLER-002` — movement not PENDING

### GET /vault/movements/pending
Returns PENDING movements at the branch for today. Empty list when the
authenticated principal has no branch assignment.

**Role:** CHECKER, ADMIN
**Response (200):** `ApiResponse<List<TellerCashMovementResponse>>`

### POST /vault/close
Closes the vault after all tills are CLOSED. Custodian enters physical count.

**Role:** CHECKER, ADMIN
**Params:** `countedBalance` (BigDecimal), `remarks` (optional)
**Response (200):** `ApiResponse<VaultPositionResponse>` (status=CLOSED, variance computed)
**Error (403):** `CBS-WF-001` — opener ≠ closer (RBI Master Circular on Cash Management §4.3 joint-custody: the custodian who opened the vault at BOD cannot close it at EOD)
**Error (409):** `CBS-TELLER-002` — vault already CLOSED / one or more tills still OPEN or PENDING at this branch / one or more vault movements still PENDING at this branch

---

## Idempotency Contract

Both `/cash-deposit` and `/cash-withdrawal` require a non-blank
`idempotencyKey` (UUID recommended). The engine + service layer guarantee:

1. **Lock-then-check ordering.** The service acquires PESSIMISTIC_WRITE on
   the account and till rows BEFORE checking the idempotency registry. This
   serializes concurrent retries on the same key at the DB level — the
   second caller blocks, then observes the first caller's committed
   transaction and returns the prior receipt without re-posting.
2. **Byte-for-byte prior-receipt on retry.** A retry with a key that was
   already processed returns the STORED transaction's fields (txnRef,
   voucherNumber, amount, balanceBefore/After, narration, channel,
   ctrTriggered derived from stored amount, chequeNumber, denomination
   breakdown). Live-request fields are NOT leaked through — this defends
   against tampered retry payloads.
3. **State-dependent checks skipped on retry.** Balance / minimum-balance /
   daily-limit / till-cash checks apply only to first-time requests. A
   retry of a successful withdrawal never fails with
   `ACCT_INSUFFICIENT_BALANCE` just because the original debit emptied
   the account.
4. **Rejected-at-open till retry.** If a supervisor rejects a
   `PENDING_OPEN` till (status = CLOSED, `openedAt == null`, no GL post,
   no cash movement), a subsequent `/till/open` by the same teller on the
   same business date transparently PURGES the rejected row (audit-logged
   as `REJECTED_TILL_PURGED`) and creates a fresh PENDING_OPEN. The
   `CBS-TELLER-010` duplicate guard still blocks retry on any CLOSED
   till with `openedAt != null` (a till that actually worked a shift).

---

## EOD Pre-Flight Gates

EOD apply (`POST /batch/eod/apply`) is blocked by the teller module
until the cash custody chain is fully closed. Per RBI Master Circular on
Cash Management at Branches §4.3:

1. Every teller till at every operational branch must be in CLOSED
   status — or the apply fails with `CBS-TELLER-100` (HTTP 409). The
   error message enumerates the offending branches.
2. Every branch vault must be in CLOSED status (per business date) —
   or the apply fails with `CBS-TELLER-101` (HTTP 409). A missing vault
   row also counts as "not closed" (the branch is expected to open a
   vault row even if its opening balance is zero, per RBI audit-trail
   completeness).
3. Sequential ordering is enforced on the single-branch close path:
   `vault.close()` rejects with `CBS-TELLER-002` if any till at that
   branch is still active, AND if any vault movement is still PENDING.

The same predicate is surfaced on the EOD trial-run UI
(`POST /batch/eod/trial`) as BLOCKER-severity checks
(`TELLER_TILLS`, `BRANCH_VAULTS`) so admins see the gate before
clicking Apply.

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
| CBS-TELLER-010 | 409 | Till already exists for teller on business date (not a rejected-at-open till — those are auto-purged, see Idempotency section) |
| CBS-TELLER-099 | 500 | Internal teller error (defensive; e.g. counterfeit on a withdrawal request) |
| CBS-TELLER-100 | 409 | EOD pre-flight: one or more teller tills are still OPEN / PENDING on the EOD business date |
| CBS-TELLER-101 | 409 | EOD pre-flight: one or more branch vaults are still OPEN on the EOD business date |
| CBS-COMP-002  | 422 | CTR threshold (PAN or Form 60/61 required per PMLA Rule 9) |
| CBS-WF-001    | 403 | Maker = checker (self-approval blocked). Fired on till approve/reject, vault movement approve/reject, and vault close (opener ≠ closer per RBI joint-custody). |
| TRANSACTION_LIMIT_EXCEEDED | 422 | Amount above role's per-transaction limit (hard-rejected for CASH_DEPOSIT/CASH_WITHDRAWAL — never routed to maker-checker) |
| ACCESS_DENIED | 403 | `@PreAuthorize` denied (insufficient role) |

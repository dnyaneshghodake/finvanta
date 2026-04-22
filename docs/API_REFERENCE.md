# Finvanta CBS — REST API Reference

> **Version:** 3.0 · **Base URL:** `/api/v1` · **Auth:** JWT Bearer · **Envelope:** `ApiResponse<T>` with `meta` + `error` (Tier-1 CBS Grade)
>
> Per RBI IT Governance Direction 2023, RBI Fair Practices Code 2023, Finacle Connect / Temenos IRIS standards.
> **109+ endpoints** across 22 controllers · **42 error codes** · **4 CBS roles** · **4 severity levels**
>
> **What's new in v3.0:** Tier-1 response envelope (meta.apiVersion, meta.correlationId, error.severity, error.action),
> Prometheus metrics (/actuator/prometheus), JSON structured logging (prod), all controllers unified under `/api/v1/`,
> CIF Lookup API (30-field CifLookupResponse), 67-field CreateCustomerRequest per CERSAI CKYC v2.0,
> DSB/CRR-SLR/CRAR regulatory reports, Compliance API (PSL, AML, Credit Bureau, Certificates).

---

## Table of Contents

| # | Section | Endpoints |
|---|---------|-----------|
| 1 | [Global Conventions](#1-global-conventions) | — |
| 2 | [Authentication and Context](#2-authentication) | 4 |
| 3 | [Customer Onboarding](#3-customer-onboarding) | 6 |
| 4 | [CASA Account Lifecycle](#4-casa-account-lifecycle) | 6 |
| 5 | [CASA Financial Operations](#5-casa-financial-operations) | 4 |
| 6 | [CASA Inquiry](#6-casa-inquiry) | 5 |
| 7 | [Loan Application Pipeline](#7-loan-application-pipeline) | 7 |
| 8 | [Loan Account Lifecycle](#8-loan-account-lifecycle) | 4 |
| 9 | [Loan Financial Operations](#9-loan-financial-operations) | 4 |
| 10 | [Loan Rate and Inquiry](#10-loan-rate-and-inquiry) | 3 |
| 11 | [Fixed Deposits](#11-fixed-deposits) | 8 |
| 12 | [Clearing and Payments](#12-clearing-and-payments) | 10 |
| 13 | [Charges and Fees](#13-charges-and-fees) | 3 |
| 14 | [GL Inquiry and Reporting](#14-gl-inquiry-and-reporting) | 4 |
| 15 | [Maker-Checker Workflow](#15-maker-checker-workflow) | 6 |
| 16 | [Notifications](#16-notifications) | 5 |
| 17 | [Dashboard Widgets](#17-dashboard-widgets-4-widget-endpoints--1-legacy) | 4+1 |
| 18 | [Teller Dashboard Widgets](#18-teller-dashboard-widgets) | 2 |
| 19 | [Manager Dashboard Widgets](#19-manager-dashboard-widgets) | 2 |
| 20 | [Users](#20-users) | 6 |
| 21 | [Products](#21-products) | 6 |
| 22 | [Audit Trail](#22-audit-trail) | 3 |
| 23 | [Reports](#23-reports) | 6 |
| 24 | [Password Management](#24-password-management) | 1 |
| 25 | [Charge Reversal](#25-charge-reversal) | 1 |
| 26 | [Error Code Reference](#26-error-code-reference) | 42 codes |
| 27 | [Infrastructure](#27-infrastructure) | — |

---

## 1. Global Conventions

### Required Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-Tenant-Id` | **Yes** | Multi-tenant discriminator. Pattern: `[A-Za-z0-9_]{1,20}` |
| `Authorization` | Yes (except `/auth/**`) | `Bearer {accessToken}` |
| `X-Correlation-Id` | Recommended | End-to-end trace ID (16-64 alphanumeric+dash). Auto-generated UUID if absent. Echoed on response. |
| `X-Idempotency-Key` | Recommended (POSTs) | Client-supplied dedup key for financial operations |
| `X-Branch-Code` | Optional | Branch context override (HO users only) |
| `Content-Type` | Yes (POST/PUT) | `application/json` |

### Response Envelope (Tier-1 CBS Grade)

Every response uses `ApiResponse<T>` with structured `meta` and `error` objects per Finacle API / Temenos IRIS / ISO 20022 alignment.

**Success:**
```json
{
  "status": "SUCCESS",
  "data": { "accountNumber": "SB-BR001-000001", "status": "ACTIVE" },
  "message": "Account activated",
  "timestamp": "2026-04-20T10:30:00",
  "meta": {
    "apiVersion": "v1",
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-04-20T10:30:00"
  }
}
```

**Error (with severity and remediation action):**
```json
{
  "status": "ERROR",
  "errorCode": "INSUFFICIENT_BALANCE",
  "message": "Insufficient account balance",
  "timestamp": "2026-04-20T10:30:00",
  "error": {
    "code": "INSUFFICIENT_BALANCE",
    "message": "Insufficient account balance",
    "severity": "HIGH",
    "action": "Verify available balance or arrange funds before retrying"
  },
  "meta": {
    "apiVersion": "v1",
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-04-20T10:30:00"
  }
}
```

**Error with data (428 MFA step-up):**
```json
{
  "status": "ERROR",
  "errorCode": "MFA_REQUIRED",
  "message": "MFA step-up required to complete sign-in",
  "data": { "challengeId": "eyJ...", "channel": "TOTP" },
  "error": {
    "code": "MFA_REQUIRED",
    "message": "MFA step-up required to complete sign-in"
  },
  "meta": {
    "apiVersion": "v1",
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-04-20T10:30:00"
  }
}
```

> **Backward compatibility:** Legacy flat fields (`errorCode`, `message`, `timestamp`) are retained alongside the new structured `error` and `meta` objects. Existing BFF clients that read `response.errorCode` continue to work; new clients should prefer `response.error.code`, `response.error.severity`, and `response.meta.correlationId`.

**Error Severity Levels (per RBI Fair Practices Code 2023):**

| Severity | BFF UI Treatment | Examples |
|----------|-----------------|----------|
| `LOW` | Toast notification, auto-dismiss | ACCOUNT_NOT_FOUND, VALIDATION_FAILED, INVALID_REQUEST |
| `MEDIUM` | Warning modal, user acknowledges | DUPLICATE_TRANSACTION, VERSION_CONFLICT, ACCOUNT_DORMANT |
| `HIGH` | Blocking error, corrective action required | INSUFFICIENT_BALANCE, ACCOUNT_FROZEN, LIEN_BLOCKED, ACCESS_DENIED |
| `CRITICAL` | Contact support with correlation ID | INTERNAL_ERROR |

**Error `action` Field:** Per RBI Fair Practices Code 2023 §7.1 — every error to the customer includes actionable remediation guidance. Examples:

| Error Code | Action |
|------------|--------|
| `INSUFFICIENT_BALANCE` | "Verify available balance or arrange funds before retrying" |
| `ACCOUNT_FROZEN` | "Contact branch to request account unfreeze" |
| `ACCOUNT_DORMANT` | "Visit the branch with ID proof to reactivate the account" |
| `KYC_NOT_VERIFIED` | "Complete KYC verification before proceeding" |
| `WORKFLOW_SELF_APPROVAL` | "A different user must approve this operation" |
| `DUPLICATE_TRANSACTION` | "This transaction was already processed. Check your statement" |

### Role Matrix

| Role | Capability |
|------|------------|
| **MAKER** | Create customers, open accounts, submit applications, initiate transactions |
| **CHECKER** | Verify, approve, reject, activate, reverse (maker != checker enforced) |
| **ADMIN** | All MAKER + CHECKER + system ops (freeze, write-off, rate reset, EOD) |
| **AUDITOR** | Read-only inquiry (accounts, balances, statements, GL) |

---

## 2. Authentication and Context

**Auth Base:** `/api/v1/auth` · **Auth:** `permitAll` · **Rate limit:** 20 req/IP burst, 1 token/6s refill
**Context Base:** `/api/v1/context` · **Auth:** JWT required

### 2.1 `POST /auth/token` — Login

Authenticates user and issues JWT tokens. Returns **ONLY identity + tokens** (no operational context). If MFA is enabled, returns 428 with a challenge token.

**Tier-1 CBS Principle:** Login must be ultra-fast (<300ms). Operational context (branch status, business day, permissions, limits) is fetched via `GET /api/v1/context/bootstrap` AFTER login.

**Request:**

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `username` | string | **Yes** | `@NotBlank` |
| `password` | string | **Yes** | `@NotBlank` |

**Response (200) — MFA disabled — `AuthResponse`:**

```json
{
  "accessToken": "eyJhbG...",
  "refreshToken": "eyJhbG...",
  "tokenType": "Bearer",
  "expiresAt": 1713600000,
  "user": {
    "userId": 1,
    "username": "maker01",
    "displayName": "Rajesh Kumar",
    "role": "MAKER",
    "branchCode": "HQ001",
    "authenticationLevel": "PASSWORD",
    "mfaEnabled": false
  }
}
```

**Response (428) — MFA enabled:**

```json
{
  "status": "ERROR",
  "errorCode": "MFA_REQUIRED",
  "message": "MFA step-up required to complete sign-in",
  "data": { "challengeId": "eyJhbG...5min-expiry-jwt", "channel": "TOTP" }
}
```

**Error Codes:**

| Code | HTTP | Description |
|------|------|-------------|
| `AUTH_FAILED` | 401 | Invalid credentials (constant-time, no user enumeration) |
| `ACCOUNT_DISABLED` | 401 | Account disabled (revealed only after valid password) |
| `ACCOUNT_LOCKED` | 401 | Locked after 5 failed attempts (30-min auto-unlock) |
| `PASSWORD_EXPIRED` | 401 | Password expired, change via UI first |
| `MFA_REQUIRED` | 428 | MFA step-up needed, challenge token in response data |

### 2.2 `POST /auth/mfa/verify` — MFA Step-Up

Exchanges the 428 challenge token plus a valid TOTP code for JWT tokens. Challenge is single-use (5-min expiry).

**Request:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `challengeId` | string | **Yes** | Challenge JWT from the 428 response |
| `otp` | string | **Yes** | 6-digit TOTP code from authenticator app |

**Response (200):** Same `AuthResponse` structure as `/auth/token` with `authenticationLevel: "MFA"`.

**Error Codes:**

| Code | HTTP | Description |
|------|------|-------------|
| `INVALID_MFA_CHALLENGE` | 401 | Challenge expired, tampered, or tenant mismatch |
| `MFA_CHALLENGE_REUSED` | 401 | Single-use challenge already consumed |
| `MFA_VERIFICATION_FAILED` | 401 | Wrong TOTP code (increments lockout counter) |
| `ACCOUNT_INVALID` | 401 | Account disabled/locked between password and MFA |

### 2.3 `POST /auth/refresh` — Token Rotation

Rotates refresh token per RFC 6749 section 10.4. Old token is denylisted; replay is detected.

**Request:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `refreshToken` | string | **Yes** | Current refresh JWT (denylisted on success) |

**Response (200):**

```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJhbG...new-access",
    "refreshToken": "eyJhbG...new-refresh",
    "tokenType": "Bearer",
    "expiresAt": 1713600900
  }
}
```

**Error Codes:**

| Code | HTTP | Description |
|------|------|-------------|
| `INVALID_REFRESH_TOKEN` | 401 | Token invalid or expired |
| `NOT_REFRESH_TOKEN` | 401 | Access token provided instead of refresh |
| `REFRESH_TOKEN_REUSED` | 401 | Stolen token replay detected (SOC alert) |
| `LEGACY_REFRESH_TOKEN` | 401 | Pre-rotation token, must re-authenticate |
| `ACCOUNT_INVALID` | 401 | Account disabled/locked since token issuance |

### 2.4 `GET /context/bootstrap` — Operational Context (Post-Login) `#83`

**Base:** `/api/v1/context` · **Auth:** JWT required · **When:** Immediately after login, after branch switch, after token refresh

Loads the full Controlled Operational Context (COC) for the authenticated user. This is the "session activation" step — the BFF calls it once after login and caches the result in its server-side session.

**Response (200) — `LoginSessionContext`:**

| Section | Fields |
|---------|--------|
| `token` | `null` (BFF already has tokens from login) |
| `user` | `userId`, `username`, `displayName`, `authenticationLevel`, `loginTimestamp`, `lastLoginTimestamp`, `passwordExpiryDate`, `mfaEnabled` |
| `branch` | `branchId`, `branchCode`, `branchName`, `ifscCode`, `branchType`, `zoneCode`, `regionCode`, `headOffice` |
| `businessDay` | `businessDate`, `dayStatus` (DAY_OPEN / EOD_RUNNING / DAY_CLOSED / NOT_OPENED), `isHoliday`, `previousBusinessDate`, `nextBusinessDate` |
| `role` | `role`, `makerCheckerRole` (MAKER / CHECKER / BOTH / VIEWER), `permissionsByModule` {module→[perms]}, `allowedModules` |
| `limits` | `transactionLimits[]` → `transactionType`, `channel`, `perTransactionLimit`, `dailyAggregateLimit` |
| `operationalConfig` | `baseCurrency`, `decimalPrecision`, `roundingMode`, `fiscalYearStartMonth`, `businessDayPolicy` |

**BFF refresh triggers:** initial login, branch switch, token refresh, day status change event

**Tier-1 BFF Flow:**
```
POST /auth/token → store JWT in memory
  → GET /context/bootstrap → hydrate server-side session
  → GET /dashboard/widgets/* → render dashboard
```

---

## 3. Customer Onboarding

**Base:** `/api/v1/customers` · **Full Contract:** [`docs/API_CUSTOMER_CONTRACT.md`](API_CUSTOMER_CONTRACT.md)

| # | Method | Path | Roles | Response DTO | Description |
|---|--------|------|-------|-------------|-------------|
| 4 | POST | `/customers` | MAKER, ADMIN | `CustomerResponse` | Create CIF (67-field request) |
| 5 | GET | `/customers/{id}` | MAKER, CHECKER, ADMIN | `CifLookupResponse` | CIF Lookup (30 fields, audit-logged) |
| 6 | PUT | `/customers/{id}` | MAKER, ADMIN | `CustomerResponse` | Update mutable fields (**PAN/Aadhaar immutable** — 400 IMMUTABLE_FIELD) |
| 7 | POST | `/customers/{id}/verify-kyc` | CHECKER, ADMIN | `CustomerResponse` | Verify KYC (maker-checker: verifier != creator) |
| 8 | POST | `/customers/{id}/deactivate` | ADMIN | `CustomerResponse` | Deactivate CIF (blocked if active loans/deposits) |
| 9 | GET | `/customers/search?q={query}` | MAKER, CHECKER, ADMIN | `List<CustomerResponse>` | Search by name, CIF, mobile, PAN (hash lookup) |

**Create/Update Request — `CreateCustomerRequest` (67 fields):**

| Category | Fields | Regulatory |
|----------|--------|-----------|
| Identity (8) | `firstName`**, `lastName`**, `middleName`, `dateOfBirth`, `panNumber`, `aadhaarNumber`, `customerType`, `branchId`** | RBI KYC §3 |
| Contact (4) | `mobileNumber`, `email`, `alternateMobile`, `communicationPref` | RBI KYC |
| Legacy Address (4) | `address`, `city`, `state`, `pinCode` | Backward compat |
| Demographics (10) | `gender`*, `fatherName`*, `motherName`*, `spouseName`, `nationality`, `maritalStatus`, `residentStatus`, `occupationCode`, `annualIncomeBand`, `sourceOfFunds` | CERSAI CKYC v2.0 |
| KYC & Risk (3) | `kycRiskCategory`, `pep` (Boolean wrapper), `kycMode` | RBI KYC MD §16, FATF |
| KYC Documents (4) | `photoIdType`, `photoIdNumber`, `addressProofType`, `addressProofNumber` | CKYC mandatory |
| OVD (4) | `passportNumber`, `passportExpiry`, `voterId`, `drivingLicense` | RBI KYC §3 |
| FATCA (1) | `fatcaCountry` | FATCA IGA |
| Permanent Address (7) | `permanentAddress`, `permanentCity`, `permanentDistrict`, `permanentState`, `permanentPinCode`, `permanentCountry`, `addressSameAsPermanent` | CKYC/CERSAI |
| Correspondence (6) | `correspondenceAddress/City/District/State/PinCode/Country` | CKYC/CERSAI |
| Income (5) | `monthlyIncome`, `maxBorrowingLimit`, `employmentType`, `employerName`, `cibilScore` | RBI Exposure Norms |
| Segmentation (2) | `customerSegment`, `sourceOfIntroduction` | Finacle CIF_MASTER |
| Corporate (6) | `companyName`, `cin`, `gstin`, `dateOfIncorporation`, `constitutionType`, `natureOfBusiness` | RBI KYC §9 |
| Nominee (3) | `nomineeDob`, `nomineeAddress`, `nomineeGuardianName` | RBI Nomination |

> ** = `@NotBlank`/`@NotNull` required. * = CKYC mandatory for INDIVIDUAL/JOINT/MINOR/NRI types.
> PAN/Aadhaar: encrypted at rest (AES-256-GCM), duplicate-checked via SHA-256 hash, **immutable on update**.
> `pep` and `addressSameAsPermanent`: Boolean wrappers — `null` = not provided (preserves existing value).

**Response — `CustomerResponse` (76 fields):** All request fields plus system-managed read-only fields (`id`, `customerNumber`, `fullName`, `kycVerified`, `kycExpiryDate`, `rekycDue`, `ckycStatus`, `ckycNumber`, `videoKycDone`, `customerGroupId`, `customerGroupName`, `relationshipManagerId`, `active`, `branchCode`, `createdAt`). PII masked: `maskedPan`, `maskedAadhaar`, `maskedMobile`. OVD document numbers NOT returned.

**Response — `CifLookupResponse` (30 fields, GET /{id} only):** Optimized for frontend CifLookup widget. Gender mapped (`M`→`MALE`), `kycStatus` computed (`VERIFIED`/`PENDING`/`EXPIRED`), `status` computed (`ACTIVE`/`INACTIVE`), nested address objects. Audit-logged.

---

## 4. CASA Account Lifecycle

**Base:** `/api/v1/accounts`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 10 | POST | `/accounts/open` | MAKER, ADMIN | Open account (status: PENDING_ACTIVATION) |
| 11 | POST | `/accounts/{accountNumber}/activate` | CHECKER, ADMIN | Activate account (maker-checker) |
| 12 | POST | `/accounts/{accountNumber}/freeze` | ADMIN | Freeze (DEBIT_FREEZE / CREDIT_FREEZE / TOTAL_FREEZE) |
| 13 | POST | `/accounts/{accountNumber}/unfreeze` | ADMIN | Unfreeze account |
| 14 | POST | `/accounts/{accountNumber}/close` | CHECKER, ADMIN | Close account |
| 15 | GET | `/accounts?branchId={id}&page={0}&size={20}` | ALL | List accounts (paginated, branch-scoped) |

**Open Account Request:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `customerId` | long | **Yes** | Customer CIF ID |
| `branchId` | long | **Yes** | Branch ID |
| `accountType` | string | **Yes** | SAVINGS, CURRENT, CURRENT_OD, SAVINGS_NRI, SAVINGS_MINOR, SAVINGS_JOINT, SAVINGS_PMJDY |
| `productCode` | string | No | Product master code (defaults to accountType) |
| `initialDeposit` | decimal | No | Initial deposit amount |
| `nomineeName` | string | No | Nominee name |
| `nomineeRelationship` | string | No | SPOUSE, CHILD, PARENT, SIBLING, OTHER |

**Freeze Request:** `freezeType` (DEBIT_FREEZE / CREDIT_FREEZE / TOTAL_FREEZE), `reason`

**Close Request:** `reason`

**Response — `AccountResponse` (32 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `accountNumber`, `accountType`, `productCode`, `status`, `branchCode`, `currencyCode` |
| Balances | `ledgerBalance`, `availableBalance`, `holdAmount`, `unclearedAmount`, `odLimit`, `effectiveAvailable`, `minimumBalance` |
| Interest | `interestRate`, `accruedInterest`, `lastInterestCreditDate` |
| Customer | `customerId`, `customerNumber`, `customerName` |
| Lifecycle | `openedDate`, `closedDate`, `closureReason`, `lastTransactionDate` |
| Freeze | `freezeType`, `freezeReason` |
| Nomination | `nomineeName`, `nomineeRelationship`, `jointHolderMode` |
| Facilities | `chequeBookEnabled`, `debitCardEnabled`, `dailyWithdrawalLimit`, `dailyTransferLimit` |

---

## 5. CASA Financial Operations

| # | Method | Path | Roles | GL Entry |
|---|--------|------|-------|----------|
| 16 | POST | `/accounts/{accountNumber}/deposit` | MAKER, ADMIN | DR Cash/Bank Ops (1100) / CR Customer Deposits (2010/2020) |
| 17 | POST | `/accounts/{accountNumber}/withdraw` | MAKER, ADMIN | DR Customer Deposits / CR Cash/Bank Ops |
| 18 | POST | `/accounts/transfer` | MAKER, ADMIN | DR From Account / CR To Account (atomic double-entry) |
| 19 | POST | `/accounts/reversal/{transactionRef}` | CHECKER, ADMIN | Reverse original GL entries |

**Financial Request (deposit/withdraw):**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `amount` | decimal | **Yes** | Must be positive |
| `narration` | string | No | Transaction description |
| `idempotencyKey` | string | Recommended | Client dedup key |
| `channel` | string | No | BRANCH, ATM, INTERNET, MOBILE, UPI, API (default: API) |

**Transfer Request:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `fromAccount` | string | **Yes** | Source account number |
| `toAccount` | string | **Yes** | Destination account number |
| `amount` | decimal | **Yes** | Must be positive |
| `narration` | string | No | Transfer description |
| `idempotencyKey` | string | Recommended | Client dedup key |

**Reversal Request:** `reason` (mandatory for audit trail)

**Response — `TxnResponse` (19 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `transactionRef`, `transactionType`, `debitCredit` |
| Amount | `amount`, `balanceBefore`, `balanceAfter` |
| Dates | `valueDate`, `postingDate` |
| Details | `narration`, `counterpartyAccount`, `counterpartyName`, `channel`, `chequeNumber` |
| Audit | `voucherNumber`, `branchCode`, `reversed`, `reversedByRef`, `idempotencyKey` |

---

## 6. CASA Inquiry

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 20 | GET | `/accounts/{accountNumber}` | ALL | Account details (full AccountResponse) |
| 21 | GET | `/accounts/{accountNumber}/balance` | ALL | Real-time balance inquiry (per Finacle BAL_INQ, used by UPI/IMPS) |
| 22 | GET | `/accounts/{accountNumber}/mini-statement?count=10` | ALL | Last N transactions (max 50) |
| 23 | GET | `/accounts/{accountNumber}/statement?fromDate=YYYY-MM-DD&toDate=YYYY-MM-DD` | ALL | Full statement for date range |
| 24 | GET | `/accounts/customer/{customerId}` | ALL | All accounts by customer CIF |

**`BalanceResponse`:** `accountNumber`, `status`, `ledgerBalance`, `availableBalance`, `holdAmount`, `unclearedAmount`, `odLimit`, `effectiveAvailable`

**`StatementResponse`:** `accountNumber`, `accountType`, `fromDate`, `toDate`, `ledgerBalance`, `availableBalance`, `transactionCount`, `transactions[]` (array of TxnResponse)

---

## 7. Loan Application Pipeline

**Base:** `/api/v1/loan-applications`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 25 | POST | `/loan-applications` | MAKER, ADMIN | Submit loan application |
| 26 | GET | `/loan-applications/{id}` | MAKER, CHECKER, ADMIN | Get application details |
| 27 | POST | `/loan-applications/{id}/verify` | CHECKER, ADMIN | Verify application (maker != checker) |
| 28 | POST | `/loan-applications/{id}/approve` | CHECKER, ADMIN | Approve application (maker != checker) |
| 29 | POST | `/loan-applications/{id}/reject` | CHECKER, ADMIN | Reject application (reason mandatory) |
| 30 | GET | `/loan-applications/customer/{customerId}` | MAKER, CHECKER, ADMIN | Applications by customer CIF |
| 31 | GET | `/loan-applications/status/{status}` | CHECKER, ADMIN | Applications by status (pipeline view) |

**Submit Request:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `customerId` | long | **Yes** | Customer CIF ID |
| `branchId` | long | **Yes** | Branch ID |
| `productType` | string | **Yes** | HOME_LOAN, PERSONAL_LOAN, VEHICLE_LOAN, GOLD_LOAN, etc. |
| `requestedAmount` | decimal | **Yes** | Must be positive |
| `interestRate` | decimal | **Yes** | Annual rate (% p.a.) |
| `tenureMonths` | int | **Yes** | Loan tenure in months |
| `purpose` | string | No | Loan purpose |
| `collateralReference` | string | No | Collateral reference for secured loans |
| `disbursementAccountNumber` | string | No | Borrower's CASA account for disbursement |
| `penalRate` | decimal | No | Penal interest rate (% p.a.) on overdue EMIs |

**Verify/Approve Request:** `remarks*` (mandatory)

**Reject Request:** `reason*` (mandatory for audit)

**Response — `ApplicationResponse` (28 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `applicationNumber`, `status`, `productType`, `branchCode` |
| Customer | `customerId`, `customerNumber`, `customerName` |
| Financial | `requestedAmount`, `approvedAmount`, `interestRate`, `penalRate`, `tenureMonths` |
| Details | `purpose`, `collateralReference`, `riskCategory`, `disbursementAccountNumber` |
| Lifecycle | `applicationDate`, `verifiedBy`, `verifiedDate`, `approvedBy`, `approvedDate`, `rejectedBy`, `rejectedDate`, `rejectionReason`, `remarks` |

---

## 8. Loan Account Lifecycle

**Base:** `/api/v1/loans`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 32 | POST | `/loans/create-account/{applicationId}` | CHECKER, ADMIN | Create loan account from approved application |
| 33 | POST | `/loans/{accountNumber}/disburse` | CHECKER, ADMIN | Full disbursement (GL: DR Loan Asset / CR CASA) |
| 34 | POST | `/loans/{accountNumber}/disburse-tranche` | CHECKER, ADMIN | Tranche disbursement (multi-disbursement products) |
| 35 | POST | `/loans/{accountNumber}/write-off` | ADMIN | Write off NPA Loss account per RBI IRAC |

**Tranche Request:** `amount*` (positive), `narration`

**Response — `LoanResponse` (46 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `accountNumber`, `status`, `productType`, `currencyCode`, `branchCode` |
| Customer | `customerId`, `customerNumber`, `customerName` |
| Amounts | `sanctionedAmount`, `disbursedAmount`, `undisbursedAmount`, `outstandingPrincipal`, `outstandingInterest`, `accruedInterest`, `totalOutstanding`, `overduePrincipal`, `overdueInterest` |
| Rate | `interestRate`, `penalRate`, `penalInterestAccrued`, `benchmarkRateName`, `benchmarkRate`, `spread`, `rateResetFrequency`, `nextRateResetDate` |
| EMI/Tenure | `emiAmount`, `repaymentFrequency`, `tenureMonths`, `remainingTenure`, `nextEmiDate`, `lastPaymentDate` |
| NPA/IRAC | `daysPastDue`, `npaDate`, `provisioningAmount`, `riskCategory`, `sectoralClassification` |
| Disbursement | `disbursementDate`, `maturityDate`, `disbursementMode`, `fullyDisbursed`, `totalTranchesPlanned`, `tranchesDisbursed`, `disbursementAccountNumber` |

---

## 9. Loan Financial Operations

| # | Method | Path | Roles | GL Entry |
|---|--------|------|-------|----------|
| 36 | POST | `/loans/{accountNumber}/repayment` | MAKER, ADMIN | DR CASA / CR Loan Asset (principal + interest + penalty split) |
| 37 | POST | `/loans/{accountNumber}/prepayment` | MAKER, ADMIN | Foreclosure — no penalty on floating rate per RBI |
| 38 | POST | `/loans/{accountNumber}/fee` | MAKER, ADMIN | DR Loan Asset / CR Fee Income |
| 39 | POST | `/loans/reversal/{transactionRef}` | CHECKER, ADMIN | Reverse original GL entries |

**Repayment Request:** `amount*` (positive), `idempotencyKey`

**Fee Request:** `amount*` (positive), `feeType*` (PROCESSING, DOCUMENTATION, LATE_PAYMENT, PREPAYMENT_PENALTY)

**Reversal Request:** `reason*` (mandatory for audit)

**Response — `LoanTxnResponse` (15 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `transactionRef`, `transactionType`, `amount` |
| Breakdown | `principalComponent`, `interestComponent`, `penaltyComponent` |
| Balance | `balanceAfter` |
| Dates | `valueDate`, `postingDate` |
| Audit | `narration`, `voucherNumber`, `branchCode`, `reversed`, `reversedByRef` |

---

## 10. Loan Rate and Inquiry

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 40 | POST | `/loans/{accountNumber}/rate-reset` | ADMIN | Floating rate reset per RBI EBLR/MCLR framework |
| 41 | GET | `/loans/{accountNumber}` | ALL | Loan account details (full LoanResponse) |
| 42 | GET | `/loans/active` | ALL | List all active loan accounts |

**Rate Reset Request:** `newBenchmarkRate*` (positive decimal, new benchmark rate % p.a.)

---

## 11. Fixed Deposits

**Base:** `/api/v1/fixed-deposits`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 43 | POST | `/fixed-deposits/book` | MAKER, ADMIN | Book FD (GL: DR CASA / CR FD Deposits) |
| 44 | POST | `/fixed-deposits/{fdNumber}/premature-close` | CHECKER, ADMIN | Premature closure with penalty rate reduction |
| 45 | POST | `/fixed-deposits/{fdNumber}/maturity-close` | CHECKER, ADMIN | Maturity closure with full interest credit |
| 46 | POST | `/fixed-deposits/{fdNumber}/lien/mark` | CHECKER, ADMIN | Mark lien for loan collateral |
| 47 | POST | `/fixed-deposits/{fdNumber}/lien/release` | CHECKER, ADMIN | Release lien |
| 48 | GET | `/fixed-deposits/{fdNumber}` | MAKER, CHECKER, ADMIN | FD details |
| 49 | GET | `/fixed-deposits/customer/{customerId}` | MAKER, CHECKER, ADMIN | FDs by customer CIF |
| 50 | GET | `/fixed-deposits/active` | CHECKER, ADMIN | Active FDs (dashboard) |

**Book FD Request:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `customerId` | long | **Yes** | Customer CIF ID |
| `branchId` | long | **Yes** | Branch ID |
| `linkedAccountNumber` | string | **Yes** | CASA account to debit |
| `principalAmount` | decimal | **Yes** | FD principal (positive) |
| `interestRate` | decimal | **Yes** | Annual rate (% p.a.) |
| `tenureDays` | int | **Yes** | FD tenure in days |
| `interestPayoutMode` | string | No | CUMULATIVE, MONTHLY, QUARTERLY |
| `autoRenewalMode` | string | No | NONE, PRINCIPAL_ONLY, PRINCIPAL_AND_INTEREST |
| `nomineeName` | string | No | Nominee name |
| `nomineeRelationship` | string | No | Nominee relationship |

**Premature Close Request:** `reason`

**Lien Request:** `lienAmount*` (positive), `loanAccountNumber*`

**Response — `FdResponse` (31 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `fdAccountNumber`, `status`, `currencyCode`, `branchCode` |
| Customer | `customerId`, `customerNumber`, `customerName` |
| Principal | `principalAmount`, `currentPrincipal`, `maturityAmount` |
| Interest | `interestRate`, `effectiveRate`, `prematurePenaltyRate`, `interestPayoutMode`, `accruedInterest`, `totalInterestPaid`, `ytdInterestPaid`, `ytdTdsDeducted` |
| Tenure | `tenureDays`, `bookingDate`, `maturityDate`, `closureDate` |
| Linked | `linkedAccountNumber` |
| Renewal | `autoRenewalMode`, `renewalCount` |
| Lien | `lienMarked`, `lienAmount`, `lienLoanAccount` |
| Nomination | `nomineeName`, `nomineeRelationship` |

---

## 12. Clearing and Payments

**Base:** `/api/v1/clearing`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 51 | POST | `/clearing/outward` | MAKER, ADMIN | Initiate outward clearing (NEFT/RTGS/IMPS/UPI) |
| 52 | POST | `/clearing/outward/approve` | CHECKER, ADMIN | Approve outward (maker-checker) |
| 53 | POST | `/clearing/inward` | MAKER, ADMIN | Process inward clearing |
| 54 | POST | `/clearing/settlement` | CHECKER, ADMIN | Confirm settlement with RBI reference |
| 55 | POST | `/clearing/network/send` | CHECKER, ADMIN | Send to payment network |
| 56 | POST | `/clearing/reverse` | CHECKER, ADMIN | Reverse clearing transaction |
| 57 | POST | `/clearing/inward/return` | CHECKER, ADMIN | Return inward transaction |
| 58 | POST | `/clearing/cycle/close` | ADMIN | Close NEFT clearing cycle |
| 59 | POST | `/clearing/cycle/submit` | ADMIN | Submit cycle to RBI |
| 60 | POST | `/clearing/cycle/settle` | ADMIN | Settle cycle with RBI confirmation |

**Outward Request:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `extRef` | string | **Yes** | External reference number |
| `rail` | string | **Yes** | NEFT, RTGS, IMPS, UPI |
| `amount` | decimal | **Yes** | Must be positive |
| `customerAccount` | string | **Yes** | Customer CASA account |
| `counterpartyIfsc` | string | **Yes** | Beneficiary bank IFSC |
| `counterpartyAccount` | string | **Yes** | Beneficiary account number |
| `counterpartyName` | string | **Yes** | Beneficiary name |
| `narration` | string | No | Payment description |
| `branchId` | long | **Yes** | Initiating branch |

**Inward Request:** `extRef*`, `utr`, `rail*`, `amount*`, `beneficiaryAccount*`, `remitterIfsc`, `remitterAccount`, `remitterName`, `narration`, `branchId*`

**Approve Request:** `extRef*`, `workflowId*`, `remarks`

**Settlement Request:** `extRef*`, `rbiRef*`

**Reversal Request:** `extRef*`, `reason*`

**Response — `ClearingTxnResponse` (21 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `extRef`, `utr`, `rail`, `direction`, `amount`, `status` |
| Accounts | `customerAccount`, `counterpartyIfsc`, `counterpartyAccount`, `counterpartyName` |
| Audit | `branchCode`, `makerId`, `narration`, `valueDate` |
| Timestamps | `initiatedAt`, `completedAt`, `settledAt` |
| Failure | `failureReason`, `reversalReason` |

**`CycleResponse`:** `id`, `rail`, `cycleDate`, `cycleNumber`, `status`, `netObligation`, `transactionCount`

---

## 13. Charges and Fees

**Base:** `/api/v1/charges`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 61 | POST | `/charges/levy` | MAKER, ADMIN | Levy charge (GST-inclusive: base + CGST + SGST) |
| 62 | POST | `/charges/waive` | CHECKER, ADMIN | Waive charge (maker-checker) |
| 63 | GET | `/charges/history/{accountNumber}?fromDate=&toDate=` | MAKER, CHECKER, ADMIN | Charge history by account |

**Levy Request:** `eventType*`, `accountNumber*`, `customerGlCode*`, `transactionAmount*`, `productCode*`, `sourceModule*`, `sourceRef*`, `branchCode*`

**Waiver Request:** `chargeTransactionId*`, `reason*`

**`ChargeResponse`:** Charge result with base fee, GST breakdown, total debit

**`WaiverResponse`:** `id`, `eventType`, `totalDebit`, `waivedBy`, `waiverReason`

**`ChargeHistoryItem`:** `id`, `eventType`, `baseFee`, `gstAmount`, `totalDebit`, `waived`, `valueDate`, `sourceModule`, `sourceRef`

---

## 14. GL Inquiry and Reporting

**Base:** `/api/v1/gl`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 64 | GET | `/gl/{glCode}` | CHECKER, ADMIN, AUDITOR | GL balance by code |
| 65 | GET | `/gl/chart-of-accounts` | CHECKER, ADMIN, AUDITOR | Chart of Accounts (all active GLs ordered by code) |
| 66 | GET | `/gl/trial-balance` | CHECKER, ADMIN, AUDITOR | Trial Balance (total debit = total credit check) |
| 67 | GET | `/gl/type/{accountType}` | CHECKER, ADMIN, AUDITOR | GLs by type (ASSET / LIABILITY / EQUITY / INCOME / EXPENSE) |

**Response — `GlResponse` (15 fields):**

| Category | Fields |
|----------|--------|
| Identity | `glCode`, `glName`, `accountType`, `description` |
| Status | `active`, `headerAccount`, `parentGlCode`, `glLevel` |
| Balances | `debitBalance`, `creditBalance`, `netBalance` |
| Period Close | `openingDebitBalance`, `openingCreditBalance`, `lastPeriodCloseDate` |

**`TrialBalanceResponse`:** `totalDebit`, `totalCredit`, `variance`, `balanced` (boolean), `accountCount`, `accounts[]` (array of GlResponse)

---

## 15. Maker-Checker Workflow

**Base:** `/api/v1/workflow`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 68 | GET | `/workflow/pending` | CHECKER, ADMIN | Pending approval queue |
| 69 | GET | `/workflow/history/{entityType}/{entityId}` | CHECKER, ADMIN | Workflow history for a specific entity |
| 70 | GET | `/workflow/sla-breached` | ADMIN | SLA-breached workflows (overdue approvals) |
| 71 | POST | `/workflow/{id}/approve` | CHECKER, ADMIN | Approve workflow item (maker != checker enforced) |
| 72 | POST | `/workflow/{id}/reject` | CHECKER, ADMIN | Reject workflow item (remarks mandatory) |
| 73 | POST | `/workflow/escalate` | ADMIN | Escalate all SLA-breached workflows to ADMIN |

**Action Request:** `remarks*` (mandatory for audit trail)

**Response — `WorkflowResponse` (15 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `entityType`, `entityId`, `actionType`, `status` |
| Users | `makerUserId`, `checkerUserId` |
| Remarks | `makerRemarks`, `checkerRemarks` |
| Timestamps | `submittedAt`, `actionedAt` |
| SLA | `slaBreached`, `slaDeadline`, `escalationCount`, `escalatedTo` |

**`EscalationResponse`:** `escalatedCount`

---

## 16. Notifications

**Base:** `/api/v1/notifications`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 74 | POST | `/notifications/send` | MAKER, ADMIN | Send transaction alert (SMS/Email) |
| 75 | POST | `/notifications/retry` | ADMIN | Retry all failed notifications |
| 76 | GET | `/notifications/customer/{customerId}` | MAKER, CHECKER, ADMIN | Notification history by customer |
| 77 | GET | `/notifications/account/{accountNumber}` | MAKER, CHECKER, ADMIN | Notification history by account |
| 78 | GET | `/notifications/summary?hoursBack=24` | CHECKER, ADMIN | Delivery status summary (dashboard) |

**Send Alert Request:** `eventType*`, `customerId*`, `accountNumber*`, `transactionRef`, `amount`, `balanceAfter`, `productCode`, `sourceModule*`, `narration`

**Response — `NotifLogResponse` (18 fields):**

| Category | Fields |
|----------|--------|
| Identity | `id`, `eventType`, `channel` (SMS/EMAIL), `customerId`, `customerName` |
| Transaction | `accountReference`, `transactionReference`, `amount`, `balanceAfter` |
| Delivery | `recipient` (masked per RBI), `messageContent`, `deliveryStatus`, `dispatchedAt`, `deliveredAt` |
| Failure | `failureReason`, `gatewayReference` |
| Audit | `sourceModule`, `retryCount`, `createdAt` |

**`RetryResponse`:** `retriedCount`

---

## 17. Dashboard Widgets (4 widget endpoints + 1 legacy)

**Base:** `/api/v1/dashboard` · **Pattern:** Tier-1 Progressive Secure Hydration

Each widget is an independent endpoint fetched in parallel by the Next.js BFF. Layout renders immediately with role-based skeleton placeholders; data replaces skeletons as it arrives. A failed widget does NOT break the entire dashboard.

**Widget Registry (BFF uses `LoginSessionContext.role` to select):**

| Role | Widgets |
|------|---------|
| MAKER | portfolio, pending-approvals |
| CHECKER | portfolio, npa, pending-approvals |
| ADMIN | ALL widgets |
| AUDITOR | portfolio, npa, casa |

| # | Method | Path | Roles | Refresh | Skeleton |
|---|--------|------|-------|---------|----------|
| 79 | GET | `/dashboard/widgets/portfolio` | ALL | 60s | 6 metric cards |
| 80 | GET | `/dashboard/widgets/npa` | CHECKER, ADMIN, AUDITOR | 60s | 3 amounts + 2 ratios |
| 81 | GET | `/dashboard/widgets/casa` | CHECKER, ADMIN, AUDITOR | 60s | 3 metric values |
| 82 | GET | `/dashboard/widgets/pending-approvals` | MAKER, CHECKER, ADMIN | 15s | Badge counter |
| — | GET | `/dashboard/summary` | MAKER, CHECKER, ADMIN | — | Legacy monolithic (deprecated) |

**`PortfolioWidget`:** `totalCustomers`, `casaAccounts`, `activeLoans`, `smaAccounts`, `npaAccounts`, `pendingApplications`

**`NpaWidget`:** `totalOutstanding`, `npaOutstanding`, `totalProvisioning`, `grossNpaRatio`, `provisionCoverage`

**`CasaWidget`:** `totalDeposits`, `casaAccountCount`, `casaRatio`

**`ApprovalsWidget`:** `pendingCount`

---

## 18. Teller Dashboard Widgets

**Base:** `/api/v1/dashboard/widgets/teller` · **Pattern:** Independent widget endpoints

| # | Method | Path | Roles | Refresh | Description |
|---|--------|------|-------|---------|-------------|
| 83 | GET | `/dashboard/widgets/teller/txn-summary` | MAKER, ADMIN | 30s | Today's transaction metrics (count, credits, debits, net) |
| 84 | GET | `/dashboard/widgets/teller/approval-queue` | CHECKER, ADMIN | 15s | Pending approval queue with aging and SLA breach flags |

**`TellerTxnSummary`:** `businessDate`, `totalTransactions`, `totalCredits`, `totalDebits`, `netAmount`

**`ApprovalQueueWidget`:** `items[]` (id, reference, actionType, makerUserId, age, ageMinutes, slaBreached, status), `totalPending`, `overdueCount`

---

## 19. Manager Dashboard Widgets

**Base:** `/api/v1/dashboard/widgets/manager` · **Pattern:** Independent widget endpoints

| # | Method | Path | Roles | Refresh | Description |
|---|--------|------|-------|---------|-------------|
| 85 | GET | `/dashboard/widgets/manager/clearing-status` | CHECKER, ADMIN | 60s | Clearing & settlement status (initiated, sent, settled, failed) |
| 86 | GET | `/dashboard/widgets/manager/risk-metrics` | CHECKER, ADMIN | 60s | Risk metrics with threshold breach flags |

**`ClearingStatusWidget`:** `businessDate`, `initiated`, `sentToNetwork`, `settled`, `failed`

**`RiskMetricsWidget`:** `overdueApprovals`, `suspensePending`, `highValueTxnsToday`, `overdueBreached`, `suspenseBreached`, `highValueBreached`

---

## 20. Users

**Base:** `/api/v1/users` · **Controller:** `UserApiController` · **All endpoints: ADMIN only**

| # | Method | Path | Description |
|---|--------|------|-------------|
| 87 | GET | `/users` | List all users (ordered by role, username) |
| 88 | GET | `/users/search?q={query}` | Search by username, name, email, role, branch |
| 89 | POST | `/users` | Create user (password complexity enforced) |
| 90 | POST | `/users/{id}/toggle-active` | Activate/deactivate user |
| 91 | POST | `/users/{id}/unlock` | Unlock locked account |
| 92 | POST | `/users/{id}/reset-password` | Admin password reset |

**Create User Request:** `username*`, `password*`, `fullName*`, `email`, `role*` (MAKER/CHECKER/ADMIN/AUDITOR), `branchId*`

**Reset Password Request:** `newPassword*` (complexity: upper+lower+digit+special, min 8, not in last 3 history)

**Response — `UserResponse` (16 fields):** `id`, `username`, `fullName`, `email`, `role`, `branchCode`, `branchName`, `active`, `locked`, `mfaEnabled`, `passwordExpired`, `failedLoginAttempts`, `lastLoginAt`, `lastPasswordChange`, `passwordExpiryDate`, `createdAt`

> **CBS SECURITY:** User responses NEVER expose passwordHash, mfaSecret, or passwordHistory.

---

## 21. Products

**Base:** `/api/v1/products` · **Controller:** `ProductApiController` · **All endpoints: ADMIN only**

| # | Method | Path | Description |
|---|--------|------|-------------|
| 93 | GET | `/products` | List all products |
| 94 | GET | `/products/{id}` | Product detail with active account count and GL codes |
| 95 | GET | `/products/search?q={query}` | Search by code, name, category, status |
| 96 | PUT | `/products/{id}` | Update product (code/category immutable; GL change triggers maker-checker) |
| 97 | POST | `/products/{id}/status` | Change lifecycle status (DRAFT→ACTIVE→SUSPENDED→RETIRED) |
| 98 | POST | `/products/{id}/clone` | Clone product with new code |

**Status Change Request:** `newStatus*` (ACTIVE, SUSPENDED, RETIRED)

**Clone Request:** `newProductCode*`, `newProductName*`

**Response — `ProductResponse`:** `id`, `productCode`, `productName`, `productCategory`, `productStatus`, `currencyCode`, `interestType`, `minInterestRate`, `maxInterestRate`, `minLoanAmount`, `maxLoanAmount`, `minTenureMonths`, `maxTenureMonths`, `configVersion`, `createdAt`

---

## 22. Audit Trail

**Base:** `/api/v1/audit` · **Controller:** `AuditApiController` · **Roles: AUDITOR, ADMIN**

| # | Method | Path | Description |
|---|--------|------|-------------|
| 99 | GET | `/audit/logs?page=0&size=100` | Recent audit logs (paginated, max 500) |
| 100 | GET | `/audit/search?q={query}&fromDate=&toDate=` | Search by entity, user, action, module, date range |
| 101 | GET | `/audit/integrity` | Verify SHA-256 hash chain integrity |

**Response — `AuditLogResponse`:** `id`, `entityType`, `entityId`, `action`, `performedBy`, `module`, `description`, `branchCode`, `eventTimestamp`, `ipAddress`, `chainValid`

**`IntegrityResponse`:** `intact` (boolean), `message` ("Audit chain intact" or "INTEGRITY VIOLATION DETECTED")

> **CBS SECURITY:** Audit logs are physically immutable — database triggers prevent UPDATE and DELETE. Hash chain (SHA-256) provides cryptographic tamper detection.

---

## 23. Reports

**Base:** `/api/v1/reports` · **Controller:** `ReportApiController` · **Roles: CHECKER, ADMIN, AUDITOR**

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 102 | GET | `/reports/dpd` | CHECKER, ADMIN, AUDITOR | DPD Distribution Report (RBI Early Warning + IRAC) |
| 103 | GET | `/reports/irac` | CHECKER, ADMIN, AUDITOR | IRAC Asset Classification Report (Standard/SMA/NPA) |
| 104 | GET | `/reports/provision` | CHECKER, ADMIN, AUDITOR | Provisioning Adequacy Report (actual vs required) |
| 105 | GET | `/reports/dsb?date=YYYY-MM-DD` | ADMIN, AUDITOR | Daily Statement of Balances (RBI Act 1934 §27) |
| 106 | GET | `/reports/crr-slr` | ADMIN, AUDITOR | CRR/SLR Compliance Position (RBI Act 1934 §42/§24) |
| 107 | GET | `/reports/crar` | ADMIN, AUDITOR | Basel III Capital Adequacy — CRAR (RBI Master Circular 2023) |

DPD/IRAC/Provision reports are branch-scoped for CHECKER, tenant-wide for ADMIN/AUDITOR.
DSB/CRR-SLR/CRAR reports are tenant-wide (ADMIN/AUDITOR only).

**`DpdReport`:** `buckets[]` (label, count, outstanding, provisioning), `totalAccounts`, `businessDate`

**`IracReport`:** `categories[]` (Standard, SMA-0/1/2, NPA Sub-Standard/Doubtful/Loss, Restructured), `totalAccounts`, `totalOutstanding`, `businessDate`

**`ProvisionReport`:** `totalAccounts`, `totalOutstanding`, `totalProvisioning`, `npaCount`, `npaOutstanding`, `npaProvisioning`, `businessDate`

**`DSB`:** `reportDate`, `demandLiabilities`, `timeLiabilities`, `ndtl`, `crrPercentage`, `crrRequired`, `crrMaintained`, `crrShortfall`, `slrPercentage`, `slrRequired`, `slrMaintained`, `slrShortfall`, `totalAdvances`, `totalDeposits`

**`CRR/SLR Position`:** `ndtl`, `crrPercentage`, `crrRequired`, `crrMaintained`, `crrCompliant`, `slrPercentage`, `slrRequired`, `slrMaintained`, `slrCompliant`

**`CRAR`:** `tier1Capital`, `tier2Capital`, `totalCapital`, `totalRwa`, `housingRwa`, `retailRwa`, `commercialRwa`, `npaRwa`, `crar`, `tier1Ratio`, `minimumCrar` (9%), `minimumWithCcb` (11.5%), `crarCompliant`, `crarWithCcbCompliant`, `pcaStatus` (NORMAL/PCA_THRESHOLD_1/2/3), `pcaTriggered`

---

## 24. Password Management

**Base:** `/api/v1/auth/password` · **Controller:** `PasswordApiController` · **Auth:** `isAuthenticated()` (JWT required)

| # | Method | Path | Description |
|---|--------|------|-------------|
| 108 | POST | `/auth/password/change` | Self-service password change |

**Request:** `currentPassword*`, `newPassword*`, `confirmPassword*`

Per RBI IT Governance Direction 2023 §8.2: complexity (upper+lower+digit+special, min 8), last 3 history check, current password verification. On success, BFF must clear session and redirect to login.

---

## 25. Charge Reversal

**Base:** `/api/v1/charges` · **Controller:** `ChargeController`

| # | Method | Path | Roles | Description |
|---|--------|------|-------|-------------|
| 109 | POST | `/charges/reverse` | CHECKER, ADMIN | Reverse a previously levied charge (symmetric contra journal) |

**Reversal Request:** `chargeTransactionId*`, `reason*`

**`ReversalResponse`:** `chargeTransactionId`, `eventType`, `totalReversed`, `reversedBy`, `reason`, `reversalVoucherNumber`

---

## 26. Error Code Reference

### Authentication Errors (401)

| Code | Description |
|------|-------------|
| `AUTH_FAILED` | Invalid credentials (constant-time, no user enumeration) |
| `ACCOUNT_DISABLED` | Account disabled (revealed only after valid password) |
| `ACCOUNT_LOCKED` | Locked after 5 failed attempts (30-min auto-unlock) |
| `PASSWORD_EXPIRED` | Password expired, must change via UI |
| `INVALID_MFA_CHALLENGE` | Challenge expired, tampered, or tenant mismatch |
| `MFA_CHALLENGE_REUSED` | Single-use challenge already consumed |
| `MFA_VERIFICATION_FAILED` | Wrong TOTP code (increments lockout counter) |
| `ACCOUNT_INVALID` | Account disabled/locked since token issuance |
| `INVALID_REFRESH_TOKEN` | Refresh token invalid or expired |
| `NOT_REFRESH_TOKEN` | Access token provided instead of refresh |
| `REFRESH_TOKEN_REUSED` | Stolen token replay detected (SOC alert triggered) |
| `LEGACY_REFRESH_TOKEN` | Pre-rotation token, must re-authenticate |
| `UNAUTHORIZED` | Missing or invalid JWT Bearer token |

### Authorization Errors (403)

| Code | Description |
|------|-------------|
| `ACCESS_DENIED` | Insufficient role for this operation |
| `WORKFLOW_SELF_APPROVAL` | Maker and checker cannot be the same person |
| `BRANCH_ACCESS_DENIED` | Cross-branch access without HO privilege |

### Not Found Errors (404)

| Code | Description |
|------|-------------|
| `ACCOUNT_NOT_FOUND` | Invalid deposit account number |
| `LOAN_NOT_FOUND` | Invalid loan account number |
| `CUSTOMER_NOT_FOUND` | Invalid customer CIF ID |
| `FD_NOT_FOUND` | Invalid FD account number |
| `GL_NOT_FOUND` | Invalid GL code |
| `APPLICATION_NOT_FOUND` | Invalid loan application ID |
| `CLEARING_NOT_FOUND` | Invalid clearing reference |
| `CHARGE_NOT_FOUND` | Invalid charge configuration |
| `CYCLE_NOT_FOUND` | Invalid clearing cycle ID |
| `TRANSACTION_NOT_FOUND` | Invalid transaction reference |
| `BENEFICIARY_NOT_FOUND` | Invalid beneficiary |
| `BRANCH_NOT_FOUND` | Invalid branch ID |
| `NOTIFICATION_NOT_FOUND` | Invalid notification ID |
| `TEMPLATE_NOT_FOUND` | Invalid notification template |

### Validation Errors (400)

| Code | Description |
|------|-------------|
| `VALIDATION_FAILED` | `@Valid` constraint violation (field-level errors) |
| `INVALID_REQUEST` | Bad argument (IllegalArgumentException) |
| `IMMUTABLE_FIELD` | Attempt to change PAN or Aadhaar after CIF creation |

### Conflict Errors (409)

| Code | Description |
|------|-------------|
| `VERSION_CONFLICT` | Stale `@Version` on maker-checker record (optimistic lock) |
| `DUPLICATE_TRANSACTION` | Idempotency key collision |
| `ALREADY_TERMINAL` | Action on already-completed entity |
| `ALREADY_CLOSED` | Action on closed account |
| `ALREADY_DISBURSED` | Re-disbursement attempt |
| `ALREADY_REVERSED` | Re-reversal attempt |
| `ALREADY_WAIVED` | Re-waive attempt |
| `CLEARING_IN_PROGRESS` | Concurrent clearing operation |
| `DUPLICATE_CLEARING_REF` | Clearing reference collision |

### Business Rule Violations (422)

| Code | Description |
|------|-------------|
| `INSUFFICIENT_BALANCE` | Withdrawal exceeds available balance + OD limit |
| `ACCOUNT_FROZEN` | Operation on frozen account |
| `ACCOUNT_CLOSED` | Operation on closed account |
| `ACCOUNT_DORMANT` | Operation on dormant account (2yr+ no activity) |
| `DEBIT_NOT_ALLOWED` | Debit freeze active on account |
| `CREDIT_NOT_ALLOWED` | Credit freeze active on account |
| `FD_NOT_ACTIVE` | Operation on non-active FD |
| `PREMATURE_NOT_ALLOWED` | FD premature closure blocked by policy |
| `LIEN_BLOCKED` | Lien prevents the requested operation |
| `KYC_NOT_VERIFIED` | KYC verification required before this operation |

### Special Status Codes

| Code | HTTP | Description |
|------|------|-------------|
| `MFA_REQUIRED` | 428 | MFA step-up needed (challenge token in response data) |
| `AUTH_RATE_LIMIT_EXCEEDED` | 429 | Rate limit exceeded on auth endpoints (Retry-After header) |
| `INTERNAL_ERROR` | 500 | Unhandled error (no stack trace or internal details leaked) |

---

## 27. Infrastructure

### Servlet Filter Chain

| Order | Filter | Purpose |
|-------|--------|---------|
| 0 | `CorrelationIdMdcFilter` | Reads/generates `X-Correlation-Id`, places in SLF4J MDC, echoes on response |
| 1 | `TenantFilter` | Resolves `X-Tenant-Id` to `TenantContext`, sets MDC keys (tenant, branch, user, requestId) |
| — | `AuthRateLimitFilter` | Token-bucket rate limit on `/api/v1/auth/**` (20 req/IP burst, 1 token/6s refill, Caffeine-backed) |
| — | `JwtAuthenticationFilter` | Validates `Authorization: Bearer {jwt}`, sets Spring `SecurityContext` |

### CORS Configuration

| Direction | Headers |
|-----------|---------|
| **Allowed Request** | `Content-Type`, `Authorization`, `X-Tenant-Id`, `X-Request-ID`, `X-Correlation-Id`, `X-Idempotency-Key`, `X-Branch-Code`, `X-Client-Version`, `Accept`, `Accept-Language`, `X-CSRF-Token` |
| **Exposed Response** | `Authorization`, `X-Request-ID`, `X-Correlation-Id`, `X-Total-Count`, `X-Total-Pages`, `X-Current-Page`, `X-Page-Size` |

### Security Chains

| Chain | Matcher | Auth Method | Session Policy |
|-------|---------|-------------|----------------|
| API (`@Order 1`) | `/api/v1/**` | JWT Bearer (stateless) | `STATELESS` |
| UI (`@Order 2`) | Everything else | Form login + session + CSRF | `migrateSession`, max 1 concurrent |

### JWT Token Types

| Type | Expiry | Claims | Purpose |
|------|--------|--------|---------|
| `ACCESS` | 15 min | username, tenant, role, branch | API authorization via `@PreAuthorize` |
| `REFRESH` | 8 hours | username, tenant, jti | Token rotation (no role — cannot authorize operations) |
| `MFA_CHALLENGE` | 5 min | username, tenant, jti | Single-use MFA step-up challenge |

### Observability & Documentation Endpoints

| Endpoint | Auth | Profile | Description |
|----------|------|---------|-------------|
| `GET /actuator/health` | None | All | K8s liveness/readiness probe |
| `GET /actuator/info` | None | All | Build version and metadata |
| `GET /actuator/prometheus` | None | All | Micrometer metrics for Prometheus scraping |
| ~~`GET /v3/api-docs`~~ | — | — | **REMOVED** — springdoc-openapi removed from classpath (CRITICAL CVEs in swagger-core: CVSS 9.6, 9.1) |

> **CBS SECURITY:** springdoc-openapi runtime dependency was **removed entirely** from the classpath due to CRITICAL CVEs in swagger-core (CVE-2025-55754 CVSS 9.6, CVE-2026-29145 CVSS 9.1). Per RBI IT Governance §8.2: no runtime dependency with CVSS >= 7.0 is acceptable. API documentation is maintained in `docs/API_REFERENCE.md` and `docs/API_CUSTOMER_CONTRACT.md`. Prometheus metrics are permitted without authentication for infrastructure scraping.

### JSON Structured Logging (Production)

Production logs use `LogstashEncoder` (JSON format) for SIEM ingestion. All MDC keys are emitted as discrete JSON fields:

```json
{
  "@timestamp": "2026-04-20T10:30:00.123Z",
  "level": "INFO",
  "logger_name": "com.finvanta.transaction.TransactionEngine",
  "message": "Transaction engine completed: ref=TXN-20260420-010601",
  "tenantId": "BANK_A",
  "branchCode": "HQ001",
  "username": "maker01",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "txnRef": "TXN-20260420-010601",
  "application": "finvanta-cbs"
}
```

Dev/test profiles use plain text format for developer readability.

### Account Status Lifecycle

```
PENDING_ACTIVATION → ACTIVE → DORMANT (2yr no txn) → INOPERATIVE (10yr)
ACTIVE → FROZEN (regulatory/court) → ACTIVE (unfreeze)
ACTIVE → CLOSED (customer request + zero balance)
ACTIVE → DECEASED (death claim)
```

### Loan Application Pipeline

```
DRAFT → SUBMITTED → VERIFIED → APPROVED → ACCOUNT_CREATED → DISBURSED
                  → REJECTED (at any stage with mandatory reason)
```

### NPA Classification (RBI IRAC)

| DPD Range | Classification | Provisioning |
|-----------|---------------|--------------|
| 0 | Standard | 0.40% |
| 1-30 | SMA-0 | 0.40% |
| 31-60 | SMA-1 | 0.40% |
| 61-90 | SMA-2 | 0.40% |
| 91-365 | Sub-standard | 15% |
| 366-730 | Doubtful-1 | 25% |
| 731-1095 | Doubtful-2 | 40% |
| 1096+ | Doubtful-3 / Loss | 100% |

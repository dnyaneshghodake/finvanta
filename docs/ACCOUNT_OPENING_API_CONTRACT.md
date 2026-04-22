# Finvanta CBS — Account Opening API Contract

> **Version:** 1.0 · **Endpoint:** `POST /api/v1/accounts/open` · **Roles:** MAKER, ADMIN
> **Status:** Account created in `PENDING_ACTIVATION` — checker activates via `POST /accounts/{accountNumber}/activate`
> **Frontend Source:** `app/(dashboard)/accounts/new/page.tsx` · **Service:** `src/services/api/accountService.ts`

---

## 1. Overview

The frontend Account Opening screen collects **32 fields** across 11 sections per the Tier-1 CBS Account Opening Blueprint. The backend `POST /accounts/open` endpoint must accept all 29 API fields (3 fields are UI-only declarations). Fields not yet supported by the backend are sent as `null`/absent — the backend must ignore unknown fields gracefully (`@JsonIgnoreProperties(ignoreUnknown = true)`).

**Current backend accepts:** `customerId`, `branchId`, `accountType`, `productCode`, `initialDeposit`, `nomineeName`, `nomineeRelationship` (7 fields).

**Target backend accepts:** All 29 fields documented below.

---

## 2. Required Headers (injected by BFF, not frontend)

| Header | Source | Notes |
|--------|--------|-------|
| `Authorization` | BFF server session | `Bearer {JWT}` |
| `X-Tenant-Id` | BFF server session | Multi-tenant discriminator |
| `X-Correlation-Id` | BFF auto-generated | UUID, echoed on response |
| `Content-Type` | BFF | `application/json` |

The frontend does NOT send auth headers — the BFF proxy at `app/api/cbs/[...path]/route.ts` injects them from the server-side session.

---

## 3. Request Body — `AccountOpenRequest`

All fields are **flat** (not nested). Optional fields accept `null` or can be absent.

### §1 Product Selection

| # | Field | Java Type | Required | Validation | Enum Values |
|---|-------|-----------|----------|------------|-------------|
| 1 | `customerId` | `Long` | **Yes** | `@NotNull`, FK to `customers.id`, customer status must be `ACTIVE` | — |
| 2 | `accountType` | `String` | **Yes** | `@NotBlank` | `SAVINGS`, `CURRENT`, `CURRENT_OD`, `SAVINGS_NRI`, `SAVINGS_MINOR`, `SAVINGS_JOINT`, `SAVINGS_PMJDY`, `SALARY` |
| 3 | `productCode` | `String` | No | Defaults to `accountType` if absent | Product master code |
| 4 | `currencyCode` | `String` | No | Defaults to `INR` | ISO 4217 |
| 5 | `initialDeposit` | `BigDecimal` | No | `@DecimalMin("0")`, `@Digits(integer=15, fraction=2)` | — |

### §3 KYC & Regulatory

| # | Field | Java Type | Required | Validation | Enum Values |
|---|-------|-----------|----------|------------|-------------|
| 6 | `panNumber` | `String` | No | `@Pattern(regexp="[A-Z]{5}[0-9]{4}[A-Z]")`, encrypt at rest | — |
| 7 | `aadhaarNumber` | `String` | No | `@Pattern(regexp="\\d{12}")`, encrypt at rest | — |
| 8 | `kycStatus` | `String` | No | | `FULL_KYC`, `MIN_KYC`, `RE_KYC` |
| 9 | `pepFlag` | `Boolean` | No | Frontend sends `"YES"`/`"NO"` string; deserialize to boolean | — |

### §4 Personal Details

| # | Field | Java Type | Required | Validation | Notes |
|---|-------|-----------|----------|------------|-------|
| 10 | `fullName` | `String` | **Yes** | `@NotBlank`, `@Size(max=200)` | As per PAN/Aadhaar |
| 11 | `dateOfBirth` | `LocalDate` | No | Must be past date; age ≥ 18 (≥ 10 for SAVINGS_MINOR) | Frontend sends `YYYY-MM-DD` |
| 12 | `gender` | `String` | No | | `MALE`, `FEMALE`, `OTHER` |
| 13 | `fatherSpouseName` | `String` | No | `@Size(max=200)` | Required for minor accounts |
| 14 | `nationality` | `String` | No | | `INDIAN`, `NRI` |

### §5 Contact Details

| # | Field | Java Type | Required | Validation | Notes |
|---|-------|-----------|----------|------------|-------|
| 15 | `mobileNumber` | `String` | No | `@Pattern(regexp="[6-9]\\d{9}")` | 10-digit Indian mobile |
| 16 | `email` | `String` | No | `@Email` | — |

### §6 Address

| # | Field | Java Type | Required | Validation | Notes |
|---|-------|-----------|----------|------------|-------|
| 17 | `addressLine1` | `String` | No | `@Size(max=500)` | Permanent address |
| 18 | `addressLine2` | `String` | No | `@Size(max=500)` | — |
| 19 | `city` | `String` | No | `@Size(max=100)` | — |
| 20 | `state` | `String` | No | `@Size(max=100)` | — |
| 21 | `pinCode` | `String` | No | `@Pattern(regexp="\\d{6}")` | Indian PIN code |

### §7 Occupation & Financial Profile

| # | Field | Java Type | Required | Validation | Enum Values |
|---|-------|-----------|----------|------------|-------------|
| 22 | `occupation` | `String` | No | | `SALARIED`, `SELF_EMPLOYED`, `BUSINESS`, `PROFESSIONAL`, `RETIRED`, `STUDENT`, `HOMEMAKER` |
| 23 | `annualIncome` | `String` | No | | `BELOW_1L`, `1L_5L`, `5L_10L`, `10L_25L`, `25L_50L`, `ABOVE_50L` |
| 24 | `sourceOfFunds` | `String` | No | | `SALARY`, `BUSINESS`, `INVESTMENT`, `AGRICULTURE`, `PENSION`, `OTHER` |

### §8 Nominee

| # | Field | Java Type | Required | Validation | Enum Values |
|---|-------|-----------|----------|------------|-------------|
| 25 | `nomineeName` | `String` | No | `@Size(max=200)` | — |
| 26 | `nomineeRelationship` | `String` | No | | `SPOUSE`, `FATHER`, `MOTHER`, `SON`, `DAUGHTER`, `SIBLING`, `OTHER` |

### §9 FATCA / CRS

| # | Field | Java Type | Required | Validation | Notes |
|---|-------|-----------|----------|------------|-------|
| 27 | `usTaxResident` | `Boolean` | No | Default `false` | If `true`, flag for FATCA reporting |

### §10 Account Configuration

| # | Field | Java Type | Required | Validation | Notes |
|---|-------|-----------|----------|------------|-------|
| 28 | `chequeBookRequired` | `Boolean` | No | Default `false` | — |
| 29 | `debitCardRequired` | `Boolean` | No | Default `false` | — |
| 30 | `smsAlerts` | `Boolean` | No | Default `true` | — |

### §14 Declarations (UI-only — NOT in request body)

| Field | Notes |
|-------|-------|
| `dueDiligenceConfirmed` | Maker confirms CDD completed. Audit log records maker userId + timestamp. |
| `documentsVerified` | Maker confirms physical document verification. |
| `customerConsentObtained` | Maker confirms customer consent obtained. |

### Injected by BFF (NOT from frontend)

| Field | Java Type | Source |
|-------|-----------|--------|
| `branchId` | `Long` | Authenticated session `user.branchId`. Frontend sends it but BFF should override from server session for security. |

---

## 4. Response — `AccountResponse`

No changes to the existing 32-field `AccountResponse` per API_REFERENCE.md §4. The new fields are persisted in the account entity but returned in the same response structure.

**New fields to add to `AccountResponse`:**

| Field | Java Type | Notes |
|-------|-----------|-------|
| `panNumber` | `String` | **Masked**: return `ABCD***34F` (first 4 + *** + last 3). Never return raw. |
| `aadhaarNumber` | `String` | **Masked**: return `**** **** 1234` (last 4 only). Never return raw. |
| `kycStatus` | `String` | `FULL_KYC` / `MIN_KYC` / `RE_KYC` |
| `pepFlag` | `Boolean` | — |
| `fullName` | `String` | Already exists as `customerName` — keep both for backward compat |
| `dateOfBirth` | `String` | `YYYY-MM-DD` |
| `gender` | `String` | — |
| `mobileNumber` | `String` | — |
| `email` | `String` | — |
| `occupation` | `String` | — |
| `annualIncome` | `String` | — |
| `usTaxResident` | `Boolean` | — |

---

## 5. Error Codes

| Code | HTTP | When |
|------|------|------|
| `VALIDATION_FAILED` | 400 | `@Valid` constraint violation |
| `CUSTOMER_NOT_FOUND` | 404 | `customerId` does not exist |
| `KYC_NOT_VERIFIED` | 422 | Customer KYC not verified (if product requires it) |
| `ACCOUNT_ALREADY_EXISTS` | 409 | Duplicate account for same customer + product |
| `ACCESS_DENIED` | 403 | Operator lacks MAKER/ADMIN role |
| `BRANCH_ACCESS_DENIED` | 403 | Cross-branch without HO privilege |

---

## 6. Example Request (minimal — current backend)

```json
{
  "customerId": 1001,
  "branchId": 1,
  "accountType": "SAVINGS",
  "productCode": "SAVINGS",
  "initialDeposit": 5000.00,
  "nomineeName": "Priya Kumar",
  "nomineeRelationship": "SPOUSE"
}
```

## 7. Example Request (full 29-field — after backend upgrade)

```json
{
  "customerId": 1001,
  "branchId": 1,
  "accountType": "SAVINGS",
  "productCode": "SAVINGS",
  "currencyCode": "INR",
  "initialDeposit": 5000.00,
  "panNumber": "ABCDE1234F",
  "aadhaarNumber": "123456789012",
  "kycStatus": "FULL_KYC",
  "pepFlag": false,
  "fullName": "Rajesh Kumar",
  "dateOfBirth": "1990-04-12",
  "gender": "MALE",
  "fatherSpouseName": "Suresh Kumar",
  "nationality": "INDIAN",
  "mobileNumber": "9876543210",
  "email": "rajesh@example.com",
  "addressLine1": "42 MG Road",
  "addressLine2": "Andheri East",
  "city": "Mumbai",
  "state": "Maharashtra",
  "pinCode": "400069",
  "occupation": "SALARIED",
  "annualIncome": "5L_10L",
  "sourceOfFunds": "SALARY",
  "nomineeName": "Priya Kumar",
  "nomineeRelationship": "SPOUSE",
  "usTaxResident": false,
  "chequeBookRequired": true,
  "debitCardRequired": true,
  "smsAlerts": true
}
```

---

## 8. Backend Implementation Checklist

- [ ] Add 22 new fields to `AccountOpenRequest` DTO (all `@Nullable` except `fullName`)
- [ ] Add `@JsonIgnoreProperties(ignoreUnknown = true)` to DTO class
- [ ] Extend `DepositAccount` entity with new columns (nullable)
- [ ] PAN/Aadhaar: reuse `@Convert(converter = EncryptedStringConverter.class)`
- [ ] PAN/Aadhaar in response: mask via `@JsonSerialize(using = MaskedPanSerializer.class)`
- [ ] Add new fields to `AccountResponse` (masked PAN/Aadhaar, KYC, personal, contact)
- [ ] Validate age from `dateOfBirth` (≥18 regular, ≥10 minor)
- [ ] Validate `pepFlag` triggers risk scoring flag
- [ ] Validate `nationality=NRI` only allowed for `SAVINGS_NRI` account type
- [ ] No endpoint URL change — still `POST /accounts/open`
- [ ] No BFF allowlist change — `/accounts/open` already allowed
- [ ] Add Flyway migration for new columns
- [ ] Update unit tests for extended DTO
- [ ] Update integration tests for full-field account opening

---

## 9. Frontend Integration (already done)

The frontend at `app/(dashboard)/accounts/new/page.tsx` already:
- Collects all 32 fields across 11 accordion sections
- Validates with Zod (PAN regex, Aadhaar 12-digit, mobile 10-digit, PIN 6-digit, email format)
- Sends via `accountService.createAccount()` which calls `POST /accounts/open`
- Currently sends only the 7 fields the backend accepts
- Will automatically send all 29 fields once `accountService.createAccount()` DTO is extended

**Frontend change needed after backend upgrade:**
1. Extend `accountService.createAccount()` parameter type to include all 29 fields
2. Pass all form fields in `onSubmit` handler (currently only passes 7)
3. Update `SpringAccount` interface to include new response fields
4. Wire risk panel to display `kycStatus`, `pepFlag` from response

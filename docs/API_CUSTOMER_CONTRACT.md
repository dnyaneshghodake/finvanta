# Finvanta CBS — Customer CIF API Contract

> **Version:** 2.0 · **Base Path:** `/api/v1/customers` · **Source:** `CustomerApiController.java`
> **Regulatory:** RBI KYC MD 2016, PMLA 2002, CERSAI CKYC v2.0, FATCA IGA, FEMA 1999
> **Entity:** `Customer.java` (70+ fields) · **DB Table:** `customers`

---

## 1. Endpoints

| # | Method | Path | Roles | Response DTO | Description |
|---|--------|------|-------|-------------|-------------|
| 1 | `POST` | `/api/v1/customers` | MAKER, ADMIN | `CustomerResponse` | Create CIF |
| 2 | `GET` | `/api/v1/customers/{id}` | MAKER, CHECKER, ADMIN | `CifLookupResponse` | CIF Lookup (audit-logged) |
| 3 | `PUT` | `/api/v1/customers/{id}` | MAKER, ADMIN | `CustomerResponse` | Update mutable fields |
| 4 | `POST` | `/api/v1/customers/{id}/verify-kyc` | CHECKER, ADMIN | `CustomerResponse` | KYC verification |
| 5 | `POST` | `/api/v1/customers/{id}/deactivate` | ADMIN | `CustomerResponse` | Deactivate CIF |
| 6 | `GET` | `/api/v1/customers/search?q=` | MAKER, CHECKER, ADMIN | `List<CustomerResponse>` | Search (branch-scoped) |

---

## 2. Response Envelope (v3.0)

```json
{
  "status": "SUCCESS",
  "data": { ... },
  "errorCode": null,
  "message": "Customer created: CIF-HQ001-000042",
  "error": null,
  "meta": {
    "apiVersion": "v1",
    "correlationId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": "2026-04-20T10:30:00"
  }
}
```

---

## 3. CreateCustomerRequest — 67 Fields

Used by `POST` (create) and `PUT` (update). All optional unless **Required**.

### 3.1 Identity (8 fields)

| # | Field | Type | Required | Validation | Notes |
|---|-------|------|----------|-----------|-------|
| 1 | `firstName` | `String` | **Yes** | `@NotBlank` | |
| 2 | `lastName` | `String` | **Yes** | `@NotBlank` | |
| 3 | `middleName` | `String` | No | | CKYC identity matching |
| 4 | `dateOfBirth` | `LocalDate` | CKYC* | `YYYY-MM-DD` | Mandatory for INDIVIDUAL per CERSAI |
| 5 | `panNumber` | `String` | No | `^[A-Z]{5}[0-9]{4}[A-Z]$` | Encrypted. Duplicate-checked. **Immutable on update** |
| 6 | `aadhaarNumber` | `String` | No | 12 digits + Verhoeff | Encrypted. Duplicate-checked. **Immutable on update** |
| 7 | `customerType` | `String` | No | Default: `INDIVIDUAL` | `INDIVIDUAL`, `JOINT`, `HUF`, `PARTNERSHIP`, `COMPANY`, `TRUST`, `NRI`, `MINOR`, `GOVERNMENT` |
| 8 | `branchId` | `Long` | **Yes** | `@NotNull` | Must be active branch in tenant |

### 3.2 Contact (4 fields)

| # | Field | Type | Validation | Notes |
|---|-------|------|-----------|-------|
| 9 | `mobileNumber` | `String` | `^[6-9]\d{9}$` | 10-digit Indian mobile |
| 10 | `email` | `String` | RFC 5322 | |
| 11 | `alternateMobile` | `String` | | SMS OTP fallback |
| 12 | `communicationPref` | `String` | | `EMAIL`, `SMS`, `BOTH`, `NONE` |

### 3.3 Legacy Address (4 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 13-16 | `address`, `city`, `state`, `pinCode` | `String` | Backward compat. Use permanent address for new CIFs |

### 3.4 Demographics — CERSAI v2.0 (10 fields)

| # | Field | Type | CKYC | Values |
|---|-------|------|------|--------|
| 17 | `gender` | `String` | **Mandatory*** | `M`, `F`, `T` per NALSA 2014 |
| 18 | `fatherName` | `String` | **Mandatory*** | |
| 19 | `motherName` | `String` | **Mandatory*** | |
| 20 | `spouseName` | `String` | If married | |
| 21 | `nationality` | `String` | No | `INDIAN`, `NRI`, `PIO`, `OCI`, `FOREIGN` |
| 22 | `maritalStatus` | `String` | No | `SINGLE`, `MARRIED`, `DIVORCED`, `WIDOWED`, `SEPARATED` |
| 23 | `residentStatus` | `String` | No | `RESIDENT`, `NRI`, `PIO`, `OCI`, `FOREIGN_NATIONAL` |
| 24 | `occupationCode` | `String` | No | `SALARIED_PRIVATE`, `SALARIED_GOVT`, `BUSINESS`, `PROFESSIONAL`, `SELF_EMPLOYED`, `RETIRED`, `HOUSEWIFE`, `STUDENT`, `AGRICULTURIST`, `OTHER` |
| 25 | `annualIncomeBand` | `String` | No | `BELOW_1L`, `1L_TO_5L`, `5L_TO_10L`, `10L_TO_25L`, `25L_TO_1CR`, `ABOVE_1CR` |
| 26 | `sourceOfFunds` | `String` | PMLA | `SALARY`, `BUSINESS`, `INVESTMENT`, `AGRICULTURE`, `PENSION`, `OTHER` |

> *CKYC mandatory fields enforced for INDIVIDUAL/JOINT/MINOR/NRI types only.

### 3.5 KYC and Risk (3 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 27 | `kycRiskCategory` | `String` | `LOW`, `MEDIUM`, `HIGH`. Null preserves default `MEDIUM` |
| 28 | `pep` | `Boolean` | Wrapper type. `null` = not provided. `true` auto-sets HIGH risk |
| 29 | `kycMode` | `String` | `IN_PERSON`, `VIDEO_KYC`, `DIGITAL_KYC`, `CKYC_DOWNLOAD` |

### 3.6 KYC Documents (4 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 30 | `photoIdType` | `String` | `PASSPORT`, `VOTER_ID`, `DRIVING_LICENSE`, `NREGA_CARD`, `PAN_CARD`, `AADHAAR` |
| 31 | `photoIdNumber` | `String` | Encrypted at rest |
| 32 | `addressProofType` | `String` | `PASSPORT`, `VOTER_ID`, `DRIVING_LICENSE`, `UTILITY_BILL`, `BANK_STATEMENT`, `AADHAAR` |
| 33 | `addressProofNumber` | `String` | Encrypted at rest |

### 3.7 OVD — RBI KYC Section 3 (4 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 34 | `passportNumber` | `String` | Encrypted |
| 35 | `passportExpiry` | `LocalDate` | |
| 36 | `voterId` | `String` | Encrypted |
| 37 | `drivingLicense` | `String` | Encrypted |

### 3.8 FATCA/CRS (1 field)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 38 | `fatcaCountry` | `String` | ISO 3166 alpha-2. `null` = Indian tax resident |

### 3.9 Permanent Address — CKYC (7 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 39 | `permanentAddress` | `String` | |
| 40 | `permanentCity` | `String` | |
| 41 | `permanentDistrict` | `String` | CKYC mandatory (separate from city) |
| 42 | `permanentState` | `String` | |
| 43 | `permanentPinCode` | `String` | 6 digits |
| 44 | `permanentCountry` | `String` | Default: `INDIA` |
| 45 | `addressSameAsPermanent` | `Boolean` | Wrapper type. `null` = preserve existing |

### 3.10 Correspondence Address — CKYC (6 fields)

| # | Field | Type |
|---|-------|------|
| 46-51 | `correspondenceAddress`, `correspondenceCity`, `correspondenceDistrict`, `correspondenceState`, `correspondencePinCode`, `correspondenceCountry` | `String` |

### 3.11 Income and Exposure (5 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 52 | `monthlyIncome` | `BigDecimal` | DTI ratio |
| 53 | `maxBorrowingLimit` | `BigDecimal` | Per-customer cap |
| 54 | `employmentType` | `String` | `SALARIED`, `SELF_EMPLOYED`, `BUSINESS`, `RETIRED`, `OTHER` |
| 55 | `employerName` | `String` | |
| 56 | `cibilScore` | `Integer` | 300-900 |

### 3.12 Segmentation (2 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 57 | `customerSegment` | `String` | `RETAIL`, `PREMIUM`, `HNI`, `CORPORATE`, `MSME`, `AGRICULTURE` |
| 58 | `sourceOfIntroduction` | `String` | |

### 3.13 Corporate — RBI KYC Section 9 (6 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 59 | `companyName` | `String` | Mandatory for COMPANY/PARTNERSHIP/TRUST/HUF |
| 60 | `cin` | `String` | Corporate Identification Number |
| 61 | `gstin` | `String` | GST registration |
| 62 | `dateOfIncorporation` | `LocalDate` | |
| 63 | `constitutionType` | `String` | `PROPRIETORSHIP`, `PARTNERSHIP`, `LLP`, `PRIVATE_LIMITED`, `PUBLIC_LIMITED`, `TRUST`, `SOCIETY`, `HUF` |
| 64 | `natureOfBusiness` | `String` | |

### 3.14 Nominee (3 fields)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 65 | `nomineeDob` | `LocalDate` | Required for minor nominees |
| 66 | `nomineeAddress` | `String` | |
| 67 | `nomineeGuardianName` | `String` | Required if nominee is minor |

---

## 4. CustomerResponse — 76 Fields

Returned by endpoints 1, 3, 4, 5, 6. Contains all request fields plus system-managed read-only fields.

### PII Masking (RBI IT Governance Section 8.5)

| Response Field | Source | Masking |
|---------------|---------|---------|
| `maskedPan` | `panNumber` | `XXXXXX234F` (last 4) |
| `maskedAadhaar` | `aadhaarNumber` | `XXXXXXXX9012` (last 4) |
| `maskedMobile` | `mobileNumber` | `XXXXXX3210` (last 4) |

OVD document **numbers** are NOT returned. Only `photoIdType` and `addressProofType` are exposed.

### System-Managed Fields (read-only)

| Field | Type | Set By |
|-------|------|--------|
| `id` | `Long` | Auto PK |
| `customerNumber` | `String` | `CbsReferenceService` |
| `fullName` | `String` | Computed: firstName + lastName |
| `kycVerified` | `boolean` | `verifyKyc()` endpoint |
| `kycExpiryDate` | `String` | Computed from verified date + risk period |
| `rekycDue` | `boolean` | EOD batch |
| `ckycStatus` | `String` | CKYC upload batch |
| `ckycNumber` | `String` | CERSAI response |
| `videoKycDone` | `boolean` | V-KYC workflow engine |
| `customerGroupId` | `Long` | Group management |
| `customerGroupName` | `String` | Group management |
| `relationshipManagerId` | `String` | RM assignment |
| `active` | `boolean` | `deactivate()` endpoint |
| `branchCode` | `String` | From Branch entity |
| `createdAt` | `String` | `@CreationTimestamp` |

---

## 5. CifLookupResponse — 30 Fields

Returned by `GET /api/v1/customers/{id}`. Optimized for the frontend `CifLookup.tsx` widget.

Key differences from `CustomerResponse`:

- Field names match frontend `CifCustomer` TypeScript type
- Gender mapped: `M` to `MALE`, `F` to `FEMALE`, `T` to `OTHER`
- `kycStatus` computed: `VERIFIED` / `PENDING` / `EXPIRED`
- `status` computed: `ACTIVE` / `INACTIVE`
- Addresses as nested objects (`permanentAddress.line1`, etc.)
- Audit-logged via `getCustomerWithAudit()`

---

## 6. Immutability Rules

| Field | Immutable After | Enforcement |
|-------|----------------|-------------|
| `panNumber` | CIF creation | API rejects `IMMUTABLE_FIELD`. Service skips. `@PreUpdate` JPA guard |
| `aadhaarNumber` | CIF creation | Same triple enforcement |
| `customerNumber` | CIF creation | Never in request DTO |

---

## 7. Maker-Checker Rules

| Operation | Maker | Checker | Enforcement |
|-----------|-------|---------|-------------|
| Create CIF | MAKER/ADMIN | -- | `@PreAuthorize` |
| Verify KYC | -- | CHECKER/ADMIN | `@PreAuthorize` + self-verify blocked (`SELF_VERIFY_PROHIBITED`) |
| Update CIF | MAKER/ADMIN | -- | `@PreAuthorize` |
| Deactivate | -- | ADMIN only | `@PreAuthorize` + active loans/deposits check |

---

## 8. Error Codes

| Code | HTTP | Severity | Scenario |
|------|------|----------|----------|
| `CUSTOMER_NOT_FOUND` | 404 | HIGH | CIF ID does not exist |
| `FIRST_NAME_REQUIRED` | 400 | LOW | First name blank |
| `LAST_NAME_REQUIRED` | 400 | LOW | Last name blank |
| `INVALID_PAN_FORMAT` | 400 | LOW | PAN not AAAAA0000A |
| `DUPLICATE_PAN` | 409 | MEDIUM | PAN already exists (one PAN = one CIF) |
| `INVALID_AADHAAR_FORMAT` | 400 | LOW | Aadhaar not 12 digits |
| `INVALID_AADHAAR_CHECKSUM` | 400 | LOW | Verhoeff checksum failed |
| `DUPLICATE_AADHAAR` | 409 | MEDIUM | Aadhaar already exists |
| `INVALID_MOBILE_FORMAT` | 400 | LOW | Mobile not 10 digits starting 6-9 |
| `INVALID_PINCODE_FORMAT` | 400 | LOW | PIN not 6 digits |
| `INVALID_EMAIL_FORMAT` | 400 | LOW | Email invalid |
| `GENDER_REQUIRED` | 400 | LOW | Gender missing for INDIVIDUAL |
| `INVALID_GENDER` | 400 | LOW | Gender not M/F/T |
| `DOB_REQUIRED` | 400 | LOW | DOB missing for INDIVIDUAL |
| `FATHER_NAME_REQUIRED` | 400 | LOW | Father name missing for INDIVIDUAL |
| `MOTHER_NAME_REQUIRED` | 400 | LOW | Mother name missing for INDIVIDUAL |
| `BRANCH_NOT_FOUND` | 404 | HIGH | Branch ID invalid or inactive |
| `IMMUTABLE_FIELD` | 400 | MEDIUM | PAN/Aadhaar change attempted on update |
| `SELF_VERIFY_PROHIBITED` | 403 | HIGH | Same user created and verifying KYC |
| `INVALID_KYC_RISK_CATEGORY` | 400 | LOW | Risk category not LOW/MEDIUM/HIGH |
| `CONCURRENT_MODIFICATION` | 409 | MEDIUM | Optimistic lock version mismatch |
| `CUSTOMER_HAS_ACTIVE_LOANS` | 409 | HIGH | Deactivation blocked by active loans |
| `CUSTOMER_HAS_ACTIVE_DEPOSITS` | 409 | HIGH | Deactivation blocked by non-closed CASA |
| `UNAUTHORIZED` | 401 | HIGH | Missing or invalid JWT |

---

## 9. Security

| Requirement | Implementation |
|-------------|---------------|
| PAN encrypted at rest | `@Convert(converter = PiiEncryptionConverter.class)` AES-256-GCM |
| Aadhaar encrypted at rest | Same as PAN |
| OVD documents encrypted | Passport, Voter ID, Driving License, Photo ID, Address Proof |
| PII never raw in response | `PiiMaskingUtil.maskPan()` / `maskAadhaar()` / `maskMobile()` |
| PII hash for dedup | SHA-256 hash stored in `pan_hash` / `aadhaar_hash` columns |
| PII immutability | `@PreUpdate` JPA guard + API rejection + service-layer skip |
| Branch-scoped access | `BranchAccessValidator.validateAccess()` on every operation |
| Audit trail | `AuditService.logEvent()` on create, update, view, KYC verify, deactivate |
| Tenant isolation | Hibernate `@Filter` on `tenant_id` + `TenantContext` |
| CSRF | Stateless JWT chain (no CSRF needed) |

---

## 10. Search Behavior

`GET /api/v1/customers/search?q={query}`

| Query Pattern | Behavior |
|--------------|----------|
| Empty / < 2 chars | Returns all active customers (branch-scoped) |
| PAN format (`AAAAA0000A`) | SHA-256 hash lookup (exact match) |
| Customer number / name / mobile | LIKE search on `customer_number`, `first_name`, `last_name`, `mobile_number` |
| ADMIN/AUDITOR | Sees all branches |
| MAKER/CHECKER | Sees own branch only |

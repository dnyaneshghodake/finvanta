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

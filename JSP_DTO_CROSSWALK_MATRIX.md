# FINVANTA -- JSP FORM <-> DTO CROSSWALK MATRIX

**Audit Date:** 2026-04-27
**Branch:** `devin/1777206832-cbs-tier1-architecture-refactor`
**Companion to:** `JSP_NAVIGATION_AUDIT.md` (Section 21), `DTO_PARITY_AUDIT_REPORT.md`
**Scope:** every JSP form field, mapped against (a) the controller binding target (entity property or `@RequestParam`) and (b) the parallel REST DTO that the React BFF uses for the same operation.

---

## LEGEND

| Symbol | Meaning |
|---|---|
| `[OK]` | JSP field name matches DTO field name -- direct binding |
| `[ALIAS]` | JSP field has different name from DTO/entity but maps via `@RequestParam` rename |
| `[GAP]` | JSP field exists, DTO field exists, but not connected (silently dropped or hard-coded null) |
| `[JSP-ONLY]` | JSP captures it; backend DTO/entity has no such field (silently ignored) |
| `[DTO-ONLY]` | DTO has the field; JSP form does not capture it |
| `[N/A]` | DTO does not exist (JSP-only flow) |
| `[ENTITY]` | JSP binds directly to JPA entity via `@ModelAttribute` (no DTO layer) |

> Severity classifications follow `DTO_PARITY_AUDIT_REPORT.md`: CRITICAL / HIGH / MEDIUM / LOW.

---

## 0. ARCHITECTURE NOTE

| JSP form | Server target | DTO involved? |
|---|---|---|
| `customer/add|edit.jsp` | `Customer` JPA entity (`@ModelAttribute`) | No -- entity is bound directly. REST API uses `CreateCustomerRequest` separately. |
| `loan/apply.jsp` | `LoanApplication` JPA entity (`@ModelAttribute`) | No on JSP path. REST API uses `SubmitApplicationRequest`. |
| `branch/add|edit.jsp` | `Branch` JPA entity (`@ModelAttribute`) | No |
| `admin/product-create|edit.jsp` | `ProductMaster` JPA entity (`@ModelAttribute`) | No |
| `deposit/open.jsp` | `OpenAccountRequest` DTO (constructed inside `DepositController.openAccount`, `DepositController.java:241-254`) | **Yes** -- but only 6 of 29 DTO fields are populated; the rest are hard-coded `null` |
| `deposit/deposit|withdraw|transfer.jsp` | `@RequestParam` on controller; service builds `TransactionRequest` internally | No DTO at HTTP boundary |
| All admin/calendar/batch/audit/reports forms | `@RequestParam` | No DTO |

**Architectural observation:** Only one screen (`deposit/open.jsp`) uses a DTO at the HTTP boundary on the JSP path. Every other JSP either binds an entity directly or passes loose `@RequestParam`s. The REST API path for the same business operations does have proper DTOs (consumed by the React BFF) -- so the crosswalk shows JSP field → REST DTO field for the operations where both UIs exist.

---

## 1. CASA ACCOUNT OPENING -- `deposit/open.jsp` <-> `OpenAccountRequest`

**REST DTO** (`src/main/java/com/finvanta/api/dto/OpenAccountRequest.java:30-77`) -- 29 fields.

| JSP field | DTO field | Status | Severity | Notes |
|---|---|---|---|---|
| `customerId` | `customerId` | `[OK]` | -- | required, both |
| `branchId` | `branchId` | `[OK]` | -- | required, both (BFF overrides from session per DTO Javadoc) |
| `accountType` | `accountType` | `[OK]` | -- | required, both |
| `productCode` | `productCode` | `[OK]` | -- | optional, both |
| `nomineeName` | `nomineeName` | `[OK]` | -- | optional, both |
| `nomineeRelationship` | `nomineeRelationship` | `[OK]` | -- | optional, both |
| -- | `currencyCode` | `[GAP]` | MEDIUM | DTO field; controller passes `null` for JSP path -- service defaults to INR |
| -- | `initialDeposit` | `[GAP]` | MEDIUM | DTO field; per Finacle ACCTOPN, JSP intentionally separates open from initial funding |
| -- | `panNumber` | `[GAP]` | HIGH | KYC field; JSP relies on CIF data |
| -- | `aadhaarNumber` | `[GAP]` | HIGH | KYC field; JSP relies on CIF data |
| -- | `kycStatus` | `[GAP]` | MEDIUM | inherited from CIF on JSP path |
| -- | `pepFlag` | `[GAP]` | MEDIUM | inherited from CIF |
| -- | `fullName` | `[GAP]` | MEDIUM | service defaults to `Customer.getFullName()` if null |
| -- | `dateOfBirth` | `[GAP]` | MEDIUM | inherited from CIF |
| -- | `gender` | `[GAP]` | MEDIUM | inherited |
| -- | `fatherSpouseName` | `[GAP]` | LOW | inherited |
| -- | `nationality` | `[GAP]` | LOW | inherited |
| -- | `mobileNumber` | `[GAP]` | MEDIUM | inherited from CIF |
| -- | `email` | `[GAP]` | LOW | inherited |
| -- | `addressLine1` | `[GAP]` | LOW | inherited |
| -- | `addressLine2` | `[GAP]` | LOW | inherited |
| -- | `city` | `[GAP]` | LOW | inherited |
| -- | `state` | `[GAP]` | LOW | inherited |
| -- | `pinCode` | `[GAP]` | LOW | inherited |
| -- | `occupation` | `[GAP]` | LOW | inherited |
| -- | `annualIncome` | `[GAP]` | LOW | inherited |
| -- | `sourceOfFunds` | `[GAP]` | LOW | inherited |
| -- | `usTaxResident` | `[GAP]` | MEDIUM | FATCA flag -- not captured on JSP |
| -- | `chequeBookRequired` | `[GAP]` | LOW | post-activation maintenance |
| -- | `debitCardRequired` | `[GAP]` | LOW | post-activation maintenance |
| -- | `smsAlerts` | `[GAP]` | LOW | post-activation maintenance |

**Coverage:** 6 / 29 DTO fields (21%). All 23 missing fields are intentionally inherited from the CIF record per Finacle ACCTOPN; React UI sends all 29 directly. See `DepositController.java:241-254` for the explicit `null` fill-ins.

---

## 2. CASA FINANCIAL OPS -- `deposit/deposit|withdraw|transfer.jsp` <-> v2 DTOs

The JSP path uses `@RequestParam` (no DTO at HTTP boundary). The v2 REST API uses `FinancialRequest` and `TransferRequest` records (`src/main/java/com/finvanta/cbs/modules/account/dto/request/`).

### 2.1 Deposit / Withdraw <-> `FinancialRequest`

| JSP field | DTO field | Status | Severity | Notes |
|---|---|---|---|---|
| `amount` | `amount` | `[OK]` | -- | required, both |
| `narration` | `narration` | `[OK]` | -- | optional, both |
| -- | `idempotencyKey` | `[GAP]` | **CRITICAL** | JSP sends none; controller passes `null`. Network retries can double-post. See `DepositController.java:487, 518` (`...null, "BRANCH"` calls). |
| -- | `channel` | `[GAP]` | MEDIUM | JSP path hard-codes `"BRANCH"` in the service call (`DepositController.java:487, 518`) |

### 2.2 Transfer <-> `TransferRequest`

| JSP field | DTO field | Status | Severity | Notes |
|---|---|---|---|---|
| `fromAccount` | `fromAccount` | `[OK]` | -- | required, both |
| `toAccount` | `toAccount` | `[OK]` | -- | required, both |
| `amount` | `amount` | `[OK]` | -- | required, both |
| `narration` | `narration` | `[OK]` | -- | optional |
| -- | `idempotencyKey` | `[GAP]` | **CRITICAL** | Same gap as deposit/withdraw |

---

## 3. CIF (CUSTOMER) -- `customer/add|edit.jsp` <-> `Customer` entity (`@ModelAttribute`)

JSP binds directly to `Customer` entity. The REST API has a parallel `CreateCustomerRequest` DTO (used by the React BFF). This crosswalk shows JSP field -> entity property and notes any divergence vs the REST DTO.

> Per `CustomerWebController.addCustomer` (`controller/CustomerWebController.java:130-148`): the entire `Customer` entity is constructed from form fields by Spring's data-binder. Field names in the JSP must exactly match `Customer` setter names.

| JSP field | `Customer.<prop>` | Status | Severity | Notes |
|---|---|---|---|---|
| `customerType` | `customerType` | `[ENTITY]` | -- | -- |
| `branchId` | -- | `[ALIAS]` | -- | NOT bound on entity; controller looks up `Branch` and sets it explicitly |
| `kycRiskCategory` | `kycRiskCategory` | `[ENTITY]` | -- | -- |
| `pep` (+ hidden `_pep`) | `pep` | `[ENTITY]` | -- | Spring checkbox idiom |
| `firstName`, `lastName` | same | `[ENTITY]` | -- | required |
| `gender`, `dateOfBirth`, `maritalStatus` | same | `[ENTITY]` | -- | -- |
| `fatherName`, `motherName`, `spouseName` | same | `[ENTITY]` | -- | CERSAI CKYC mandatory |
| `nationality`, `occupationCode`, `annualIncomeBand`, `cibilScore` | same | `[ENTITY]` | -- | -- |
| `panNumber`, `aadhaarNumber` | same | `[ENTITY]` | -- | immutable post-create (server-enforced) |
| `photoIdType`, `photoIdNumber`, `addressProofType`, `addressProofNumber` | same | `[ENTITY]` | -- | KYC OVD identifiers |
| `kycMode` | `kycMode` | `[ENTITY]` | -- | IN_PERSON / VIDEO_KYC / DIGITAL_KYC / CKYC_DOWNLOAD |
| `mobileNumber`, `email` | same | `[ENTITY]` | -- | required mobile |
| `address`, `city`, `state`, `pinCode` | same | `[ENTITY]` | -- | correspondence address |
| `addressSameAsPermanent` (+ `_addressSameAsPermanent`) | `addressSameAsPermanent` | `[ENTITY]` | -- | Spring checkbox idiom |
| `permanentAddress`, `permanentCity`, `permanentState`, `permanentPinCode`, `permanentCountry` | same | `[ENTITY]` | -- | -- |
| `monthlyIncome`, `maxBorrowingLimit`, `employmentType`, `employerName` | same | `[ENTITY]` | -- | -- |
| `nomineeDob`, `nomineeGuardianName`, `nomineeAddress` | same | `[ENTITY]` | -- | -- |

**JSP-vs-REST DTO divergence (`CreateCustomerRequest`, 67 fields per `DTO_PARITY_AUDIT_REPORT.md` Section 2.5):**

| Missing on JSP form | Present in REST DTO | Severity |
|---|---|---|
| `middleName`, `alternateMobile`, `communicationPref` | yes | LOW |
| `residentStatus`, `sourceOfFunds` | yes | MEDIUM (FEMA / AML) |
| `passportNumber`, `passportExpiry`, `voterId`, `drivingLicense` | yes | LOW-MEDIUM (OVD per RBI KYC §3) |
| `fatcaCountry` | yes | MEDIUM (FATCA / CRS) |
| `permanentDistrict`, `correspondenceAddress|City|District|State|PinCode|Country` | yes | LOW (CKYC completeness) |
| `customerSegment`, `sourceOfIntroduction` | yes | LOW |
| `companyName`, `cin`, `gstin`, `dateOfIncorporation`, `constitutionType`, `natureOfBusiness` | yes | MEDIUM (corporate CIF cannot be created via JSP) |

> Total: 23 fields are present in the REST DTO but absent from the JSP form. Critical impact: corporate, NRI, and FATCA customers cannot have all required attributes captured via JSP -- only via React.

---

## 4. LOAN APPLICATION -- `loan/apply.jsp` <-> `LoanApplication` entity (JSP) AND `SubmitApplicationRequest` DTO (REST)

JSP binds directly to `LoanApplication` JPA entity via `@ModelAttribute` (`controller/LoanController.java:134-140`). The REST API uses `SubmitApplicationRequest` (`api/LoanApplicationController.java:162` -- inline `record` of 10 fields).

| JSP field | `LoanApplication.<prop>` | REST DTO field (`SubmitApplicationRequest`) | Status | Severity | Notes |
|---|---|---|---|---|---|
| `customerId` | -- | `customerId` | `[ALIAS]` | -- | JSP: `@RequestParam`; controller looks up `Customer` and sets relation |
| `branchId` | -- | `branchId` | `[ALIAS]` | -- | JSP: `@RequestParam`; controller looks up `Branch` |
| `productType` | `productType` | `productType` | `[ENTITY]` + `[OK]` | -- | bound on both paths |
| `requestedAmount` | `requestedAmount` | `requestedAmount` | `[ENTITY]` + `[OK]` | -- | -- |
| `interestRate` | `interestRate` | `interestRate` | `[ENTITY]` + `[OK]` | -- | -- |
| `tenureMonths` | `tenureMonths` | `tenureMonths` | `[ENTITY]` + `[OK]` | -- | -- |
| `purpose` | `purpose` | `purpose` | `[ENTITY]` + `[OK]` | -- | optional, both |
| `collateralReference` | `collateralReference` | `collateralReference` | `[ENTITY]` + `[OK]` | -- | optional, both |
| `disbursementAccountNumber` | `disbursementAccountNumber` | `disbursementAccountNumber` | `[ENTITY]` + `[OK]` | -- | -- |
| `penalRate` | `penalRate` | `penalRate` | `[ENTITY]` + `[OK]` | -- | optional |
| `riskCategory` | -- | -- | `[JSP-ONLY]` | **HIGH** | **Silently dropped.** No corresponding entity setter and not in `SubmitApplicationRequest`. DTO uses `@JsonIgnoreProperties(ignoreUnknown = true)` so REST API would also ignore it. See `DTO_PARITY_AUDIT_REPORT.md` Section 2.6. |

> JSP field count: 11. REST DTO field count: 10. Entity bind covers 8 fields directly; `customerId`/`branchId` are sidecar params that the controller resolves. The single bona fide JSP-only field (`riskCategory`) is the silent-drop bug.

---

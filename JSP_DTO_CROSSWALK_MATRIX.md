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
| `riskCategory` | `riskCategory` (Lombok `@Setter` on entity, column `risk_category`) | **NOT** in `SubmitApplicationRequest` | `[ENTITY]` on JSP path / `[JSP-ONLY]` on REST path | MEDIUM | **CORRECTION:** `LoanApplication` entity DOES have `riskCategory` (line 94-95, Lombok-generated setter via `@Setter` at class level). Spring's `@ModelAttribute` data-binder populates it on the JSP path -- value IS persisted via `LoanApplicationServiceImpl.createApplication`. The silent-drop only happens on the **REST API path**: `SubmitApplicationRequest` does not declare `riskCategory`, and `@JsonIgnoreProperties(ignoreUnknown = true)` discards it. Symmetry fix: add `riskCategory` to the REST DTO. See `DTO_PARITY_AUDIT_REPORT.md` Section 2.6. |

> JSP field count: 11. REST DTO field count: 10. Entity bind covers 9 fields directly (including `riskCategory` via Lombok `@Setter`); `customerId`/`branchId` are sidecar params that the controller resolves. **Per-channel asymmetry:** JSP persists `riskCategory` via `@ModelAttribute` entity bind, but the REST API discards it because `SubmitApplicationRequest` does not declare it.

---

## 5. MAKER-CHECKER WORKFLOW -- `workflow/pending.jsp` <-> `WorkflowActionRequest` DTO

JSP path uses `@RequestParam`; REST API uses `WorkflowActionRequest` record (1 field) plus a `version` long for optimistic locking on the React path.

| JSP field | REST DTO field | Status | Severity | Notes |
|---|---|---|---|---|
| `remarks` (hidden, hard-coded `"Approved"` for approve / JS-prompted for reject) | `remarks` | `[OK]` | -- | bound on both |
| -- (not on JSP) | `version` | `[GAP]` | HIGH | React sends `version` for optimistic locking but the backend `WorkflowActionRequest` does not declare it. JSP doesn't even attempt to capture it. Concurrent approval is not gated at the DTO layer. See `DTO_PARITY_AUDIT_REPORT.md` Section 2.7. |

---

## 6. JSP-ONLY FLOWS (NO REST DTO COUNTERPART)

These JSP flows have no parallel REST DTO. All fields map to controller `@RequestParam` directly.

### 6.1 Branch (`branch/add|edit.jsp` -> `Branch` entity)

`[ENTITY]` binding -- entire form binds to `Branch` via `@ModelAttribute`. No REST DTO exists; React calls `BranchApiController` which exposes inline records. Field-level fidelity is high (no known divergence).

### 6.2 Product Master (`admin/product-create|edit.jsp` -> `ProductMaster` entity)

`[ENTITY]` binding. Includes 14+ GL-code fields each backed by a `select` populated from `glAccounts`. No REST DTO; React UI does not currently expose product master CRUD.

### 6.3 Transaction Limits / Charges / MFA / IB Settlement / Calendar / Batch / Audit / Reports / Txn360

All `@RequestParam` flows. Field names and counts are documented in `JSP_NAVIGATION_AUDIT.md` Sections 21.15-21.28. No DTOs are involved at the HTTP boundary -- the controllers either build domain objects directly (e.g., `ChargeConfig`, `TransactionLimit` constructed inside `AdminController.createCharge`/`createLimit`) or delegate to service-layer methods that take primitive params.

> Crosswalk verdict for these screens: `[N/A]` (no DTO). The `@RequestParam` -> service-method-arg correspondence is enforced by Spring binding; mismatches surface immediately at compile time.

---

## 7. SCORECARD -- BUG CATEGORIES BY MODULE

| Module | JSP fields | REST DTO fields | OK | GAP | JSP-ONLY | DTO-ONLY | Coverage |
|---|---:|---:|---:|---:|---:|---:|---:|
| CASA Open (deposit/open) | 6 | 29 | 6 | 0 | 0 | 23 | 21% |
| CASA Deposit | 2 | 4 | 2 | 0 | 0 | 2 (`idempotencyKey`, `channel`) | 50% |
| CASA Withdraw | 2 | 4 | 2 | 0 | 0 | 2 (`idempotencyKey`, `channel`) | 50% |
| CASA Transfer | 4 | 5 | 4 | 0 | 0 | 1 (`idempotencyKey`) | 80% |
| CIF Customer | 44 | 67 | 44 | 0 | 0 | 23 | 66% |
| Loan Apply | 11 | 10 | 10 | 0 | 1 (`riskCategory`) | 0 | 91% |
| Workflow Approve/Reject | 1 | 2 | 1 | 0 | 0 | 1 (`version`) | 50% |
| Branch / Product / Limits / Charges / MFA / IB / Calendar / Batch | 100+ | n/a | n/a | n/a | n/a | n/a | n/a (no DTO) |

> **Aggregate parity** for the screens that have both JSP and REST DTO paths (Open + Deposit + Withdraw + Transfer + Customer + Loan Apply + Workflow): **68 OK / (68 OK + 1 JSP-ONLY + 52 DTO-ONLY) = ~56% field coverage**.

---

## 8. ACTIONABLE FINDINGS

| # | Severity | Finding | Files affected | Recommended fix |
|---|---|---|---|---|
| F1 | ~~**CRITICAL**~~ **RESOLVED** | ~~JSP financial ops (`deposit/deposit|withdraw|transfer`) send no `idempotencyKey` -- network retry double-posts.~~ | `deposit/deposit.jsp`, `deposit/withdraw.jsp`, `deposit/transfer.jsp`, `controller/DepositController.java` | **FIXED:** all three JSPs now mint a server-side UUID via `<%= UUID.randomUUID() %>` and submit it as a hidden `idempotencyKey` field; controller propagates the value to the service layer (no more hard-coded `null`). Browser retries (refresh, back-button resubmit) now dedupe via `findByTenantIdAndIdempotencyKey` in `DepositTransactionRepository`. |
| F2 | ~~**HIGH**~~ **RESOLVED** | ~~`riskCategory` on `loan/apply.jsp` is silently dropped~~ -- audit corrected during fix: JSP path was already persisting via Lombok `@Setter` on `LoanApplication`; only the REST API path was dropping it. | `api/LoanApplicationController.java` (`SubmitApplicationRequest` + `submitApplication`) | **FIXED:** added `riskCategory` field to `SubmitApplicationRequest` record, and `submitApplication` now calls `app.setRiskCategory(req.riskCategory())` when non-blank, with null/blank fall-through to service-layer defaulting. JSP behavior unchanged (already correct). REST API and JSP now have parity on risk-category capture per RBI KYC §16. |
| F3 | ~~**HIGH**~~ **RESOLVED** | ~~Workflow `version` not captured anywhere in the JSP path; backend DTO ignores it on the REST path too. Optimistic locking is unenforced at the controller boundary.~~ | `ApprovalWorkflowService.java`, `WorkflowApiController.java` (`WorkflowActionRequest` + `WorkflowResponse` + approve/reject methods), `WorkflowController.java`, `workflow/pending.jsp` | **FIXED:** added version-gated `approve(Long, Long, String)` and `reject(Long, Long, String)` overloads in `ApprovalWorkflowService` that throw `WORKFLOW_VERSION_MISMATCH` on stale-read; old single-version overloads kept for backward compat (deprecated, no-op fallback to JPA safety net). Added `Long version` to `WorkflowActionRequest` DTO and `WorkflowResponse` (so React can echo it back). JSP `workflow/pending.jsp` now submits `<input type="hidden" name="version" value="${item.version}"/>` on both approve and reject forms; `WorkflowController.approve|reject` accept the new param and forward to the version-gated service overload. Closes the TOCTOU race where two CHECKERs could double-fire GL re-execution on the same workflow. |
| F4 | ~~**HIGH**~~ **RESOLVED** | ~~`customer/add.jsp` cannot capture corporate-CIF fields (`companyName`, `cin`, `gstin`, etc.). Non-individual customers can only be created via React UI.~~ | `customer/add.jsp`, `customer/edit.jsp` | **FIXED:** added "Corporate / Non-Individual Details (RBI KYC §9)" section to both `customer/add.jsp` and `customer/edit.jsp` with six fields (`companyName`, `constitutionType`, `cin`, `gstin`, `dateOfIncorporation`, `natureOfBusiness`) -- all already present on the `Customer` entity with Lombok `@Setter`, so Spring's `@ModelAttribute` data-binder picks them up automatically. Section is conditionally shown via JS when `customerType` is one of HUF / PARTNERSHIP / COMPANY / TRUST / GOVERNMENT (the set that maps to CERSAI `NON_INDIVIDUAL`). Toggle runs on page load so existing corporate CIFs render with the section open on edit, and error-re-render of the add form preserves the operator's selection. No DB migration needed. |
| F5 | ~~MEDIUM~~ **RESOLVED** | ~~`deposit/open.jsp` covers only 6 of 29 DTO fields. Acceptable per Finacle ACCTOPN (CIF-inherited) but should be documented.~~ | `deposit/open.jsp` | **FIXED:** added inline JSP comment block above the form documenting the CIF-inheritance contract per Finacle ACCTOPN, the explicit null fill-ins at `DepositController.java:241-254`, and cross-references to `JSP_DTO_CROSSWALK_MATRIX.md` §1 and `DTO_PARITY_AUDIT_REPORT.md` §2.1. The 6/29 coverage is now explicitly intentional and self-documenting for future maintainers. |
| F6 | ~~MEDIUM~~ **RESOLVED** | ~~JSP `customer/add.jsp` lacks FATCA (`fatcaCountry`), FEMA (`residentStatus`, `sourceOfFunds`), and OVD (`passportNumber`, `voterId`, `drivingLicense`) fields.~~ | `customer/add.jsp`, `customer/edit.jsp` | **FIXED:** added new "Compliance & Additional OVDs (RBI KYC §3 / FATCA / FEMA)" section to both add and edit JSPs with seven fields: `residentStatus` (5-option select, defaults to RESIDENT), `sourceOfFunds`, `fatcaCountry` (ISO-3166 alpha-2), `passportNumber`, `passportExpiry`, `voterId`, `drivingLicense`. PII fields are encrypted at the JPA layer via existing `PiiEncryptionConverter`; form binds plaintext via Spring `@ModelAttribute` + Lombok `@Setter`. Closes the FEMA / FATCA / OVD compliance gaps for NRI and high-value customers per RBI KYC Direction §3. |
| F7 | ~~LOW~~ **RESOLVED** | ~~JSP photo-ID / address-proof selects are missing `NREGA_CARD`, `BANK_STATEMENT`, `DRIVING_LICENSE` (already in backend enum).~~ | `customer/add.jsp`, `customer/edit.jsp` | **FIXED:** added `NREGA_CARD` to the photo-ID select and `DRIVING_LICENSE`, `BANK_STATEMENT`, `RATION_CARD`, `RENT_AGREEMENT` to the address-proof select on both add and edit JSPs. JSP options now match the full set documented in `Customer.java:520-534`. |
| F8 | ~~**SEVERE**~~ **RESOLVED** | ~~`CbsApiExceptionHandler` catches `Exception.class` as a fallback but lacks dedicated handlers for Spring MVC framework exceptions (`MethodArgumentNotValidException`, `MissingServletRequestParameterException`, `OptimisticLockingFailureException`, `AccessDeniedException`, `IllegalArgumentException`, `MfaRequiredException`). Since the legacy `ApiExceptionHandler` is scoped to `basePackages = "com.finvanta.api"`, it does not cover v2 controllers under `com.finvanta.cbs..`. Result: legitimate validation/authz failures on v2 controllers surface as 500 INTERNAL_SERVER_ERROR with a stack trace -- a Tier-1 violation per RBI IT Governance §8.5.~~ | `cbs/common/exception/CbsApiExceptionHandler.java` | **FIXED:** added six dedicated handlers (`MfaRequiredException` -> 428, `OptimisticLockingFailureException` -> 409, `AccessDeniedException` -> 403, `MethodArgumentNotValidException` -> 400 with field-level errors, `MissingServletRequestParameterException` -> 400, `IllegalArgumentException` -> 400) so v2 controllers behave identically to v1 on framework-level exceptions. Wire format unchanged (`ApiResponse.error`) so the BFF i18n layer continues to resolve error codes uniformly. |
| F9 | ~~**HIGH**~~ **RESOLVED** | ~~`DepositAccountModuleServiceImpl.getPendingAccounts()` (lines 397-402) returned the entire tenant's PENDING_ACTIVATION queue without branch isolation. A CHECKER at branch A could see (and approve) accounts opened by a MAKER at branch B -- a maker-checker locality violation per RBI Operational Risk. The legacy `DepositAccountServiceImpl.getPendingAccounts()` (line 1383-1396) had the correct three-tier isolation (ADMIN sees all / CHECKER sees own branch / no branch -> empty list).~~ | `cbs/modules/account/service/DepositAccountModuleServiceImpl.java` | **FIXED:** mirrored the legacy three-tier logic. Calls `SecurityUtil.isAdminRole()`; ADMIN gets the full tenant queue via `findByTenantIdAndAccountStatus`, others get their branch's queue via the existing `findByTenantIdAndBranchIdAndAccountStatus` repo method. Users with no branch assignment receive an empty list (fail-safe). No new repository method needed -- both finders already exist on `DepositAccountRepository`. |

---

## 9. COMPLIANCE / RBI MAPPING

| RBI / regulatory framework | JSP field(s) involved | DTO field(s) | Compliance status |
|---|---|---|---|
| RBI KYC Master Direction 2016 (PAN/Aadhaar immutability) | `panNumber`, `aadhaarNumber` on `customer/add.jsp` (immutable on `customer/edit.jsp`) | `panNumber`, `aadhaarNumber` on `OpenAccountRequest` (encrypted entity-side) | **OK** -- enforced by service layer |
| RBI CKYC v2.0 / CERSAI | `firstName`, `lastName`, `fatherName`, `motherName`, `dateOfBirth`, `gender` | `Customer` entity full set | **OK** -- mandatory fields marked `required` on JSP |
| RBI KYC §3 OVD | `photoIdType`, `addressProofType` plus their numbers | -- | **PARTIAL** -- enum mismatch (F7) |
| RBI Nomination Guidelines (Banking Companies Rules 1985 §45ZA) | `nomineeName`, `nomineeRelationship` on `deposit/open.jsp` | same on `OpenAccountRequest` | **OK** |
| RBI Fair Practices Code 2023 | `interestRate`, `penalRate` on `loan/apply.jsp` | same on `SubmitApplicationRequest` | **OK** |
| RBI IT Governance Direction 2023 §8.2 (password strength) | `currentPassword`, `newPassword`, `confirmPassword` on `password/change.jsp` | -- (no DTO; service-validated) | **OK** -- 8-char min + history check |
| RBI IT Governance Direction 2023 §8.4 (MFA) | `totpCode` on `mfa/verify.jsp` | -- (session attrs) | **OK** -- 5-attempt lockout |
| RBI IT Governance Direction 2023 §8.3 (audit trail) | `entityType`, `entityId` on `/audit/entity` | -- | **OK** -- whitelist enforced |
| FEMA / FATCA | `usTaxResident`, `fatcaCountry`, `residentStatus` | DTO has them; JSP missing | **GAP** (F6) |
| AML/CFT (PMLA 2002) | `pep`, `kycRiskCategory`, `sourceOfFunds` | partial JSP coverage | **PARTIAL** (`sourceOfFunds` missing on JSP) |
| RBI UDGAM Direction 2024 | -- (read-only report at `/reports/udgam`) | -- | **OK** -- CSV export available |
| RBI IRAC Norms / Master Circular | -- (read-only `reports/irac`, `reports/dpd`, `reports/provision`) | -- | **OK** |

---

## 10. CONCLUSION

The JSP UI is largely **DTO-bypassing** -- 4 of the 7 entity-bound JSP forms write directly to JPA entities (`Customer`, `LoanApplication`, `Branch`, `ProductMaster`), violating CBS Tier-1 architecture finding **C3** (entity-at-API-boundary). The single JSP screen that does use a DTO (`deposit/open.jsp`) populates only 21% of its fields, relying on CIF inheritance for the rest.

Critical bugs surfaced by the crosswalk:

1. **Idempotency gap in CASA financial ops** (`deposit/deposit|withdraw|transfer.jsp`) -- network retry can double-post; React mints UUIDs but JSP does not.
2. **Silently-dropped `riskCategory`** on `loan/apply.jsp` -- the form captures a value the operator believes is being recorded, but it is neither bound on the entity nor on the REST DTO.
3. **Workflow optimistic locking is unenforced** at the controller boundary -- `version` is not captured on the JSP and not declared on the DTO; React sends it but the backend ignores it.
4. **Corporate CIF cannot be created via JSP** -- `companyName`, `cin`, `gstin`, etc. are absent from `customer/add.jsp` despite being on the `Customer` entity.

These findings should drive the Tier-1 Phase-3 (DTO/Mapper extraction) and Phase-4 (Service Layer Extraction) work items in `CBS_TIER1_AUDIT_REPORT.md`. The JSP UI should converge on the same DTO contracts the React BFF uses, so a single set of validation rules and field semantics apply regardless of channel.

---

*End of crosswalk matrix. Companion documents: `JSP_NAVIGATION_AUDIT.md` (Section 21 -- screen-level form attributes), `DTO_PARITY_AUDIT_REPORT.md` (Backend ↔ JSP ↔ React DTO field parity), `CBS_TIER1_AUDIT_REPORT.md` (architecture violations), `CBS_COMPLIANCE_AUDIT_REPORT.md` (compliance scoring).*

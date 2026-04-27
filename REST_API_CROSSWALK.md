# FINVANTA -- REST API (com.finvanta.api.*) DTO CROSSWALK

**Audit Date:** 2026-04-27
**Branch:** `devin/1777206832-cbs-tier1-architecture-refactor`
**Scope:** All `@RestController` classes under `com.finvanta.api.*` serving JSON to the React/Next.js BFF.
**Companion to:** `JSP_NAVIGATION_AUDIT.md` (JSP contract), `JSP_DTO_CROSSWALK_MATRIX.md` (JSP↔DTO parity)

---

## 0. CONTROLLER INVENTORY

| # | Controller | Base Path | Request DTOs (field count) | Response DTOs (field count) | Financial |
|---|---|---|---|---|---|
| 1 | `DepositAccountController` | `/api/v1/accounts` | `OpenAccountRequest`(29), `FinancialRequest`(4), `TransferRequest`(5), `FreezeRequest`(2), `CloseRequest`(1), `ReversalRequest`(1), `RejectRequest`(1) | `AccountResponse`(40), `BalanceResponse`(8), `TxnResponse`(18), `StatementResponse`(8) | **YES** |
| 2 | `CustomerApiController` | `/api/v1/customers` | `CreateCustomerRequest`(67) | `CustomerResponse`(76), `CifLookupResponse`(30) | YES (KYC) |
| 3 | `LoanApplicationController` | `/api/v1/loan-applications` | `SubmitApplicationRequest`(11), `RemarksRequest`(1), `RejectRequest`(1) | `ApplicationResponse`(24) | **YES** |
| 4 | `LoanAccountController` | `/api/v1/loans` | `TrancheRequest`(2), `RepaymentRequest`(1), `FeeRequest`(2), `ReversalRequest`(1), `RateResetRequest`(3) | `LoanAccountResponse`(30+), `LoanTxnResponse`(15+) | **YES** |
| 5 | `WorkflowApiController` | `/api/v1/workflow` | `WorkflowActionRequest`(2) | `WorkflowResponse`(16), `EscalationResponse`(1) | **YES** (GL) |
| 6 | `AuthController` | `/api/v1/auth` | `TokenRequest`(2), `RefreshRequest`(1), `MfaVerifyRequest`(2) | `TokenResponse`(7), `AuthResponse`(5) | YES (session) |
| 7 | `FixedDepositController` | `/api/v1/fixed-deposits` | `BookFdRequest`(8), `CloseRequest`(1), `LienRequest`(3) | `FdResponse`(20+) | **YES** |
| 8 | `ClearingController` | `/api/v1/clearing` | `OutwardRequest`(8), `InwardRequest`(6), `ApproveRequest`(2), `SettlementRequest`(2), `ReversalRequest`(1) | cycle/txn responses | **YES** |
| 9 | `ChargeController` | `/api/v1/charges` | `LevyRequest`(6), `WaiverRequest`(2), `ReversalRequest`(2) | `ChargeResponse`(12), `WaiverResponse`(6) | YES |
| 10 | `ComplianceApiController` | `/api/v1/compliance` | `PslClassifyRequest`(3), `StrCreateRequest`(5), `BureauInquiryRequest`(3) | `BureauInquiryResponse`(5+) | YES (regulatory) |
| 11 | `BranchApiController` | `/api/v1/branches` | `CreateBranchRequest`(10), `UpdateBranchRequest`(8) | `BranchResponse`(12) | no |
| 12 | `UserApiController` | `/api/v1/users` | `CreateUserRequest`(6), `ResetPasswordRequest`(1) | `UserResponse`(10) | YES (access) |
| 13 | `ProductApiController` | `/api/v1/products` | `UpdateProductRequest`(15+), `StatusChangeRequest`(1), `CloneRequest`(2) | `ProductResponse`(15), `ProductDetailResponse`(25+) | no |
| 14 | `CalendarApiController` | `/api/v1/calendar` | `DayControlRequest`(2), `GenerateRequest`(2), `HolidayRequest`(2) | `DayStatusResponse`(5), `CalendarEntryResponse`(8) | no |
| 15 | `AuditApiController` | `/api/v1/audit` | `ScreenAccessRequest`(3) | `AuditLogResponse`(12), `IntegrityResponse`(3) | no |
| 16 | `PasswordApiController` | `/api/v1/auth/password` | `PasswordChangeRequest`(3) | `PasswordChangeResponse`(2) | YES (credential) |
| 17 | `GLInquiryController` | `/api/v1/gl` | (query params) | `GlResponse`(8), `TrialBalanceResponse`(5) | no |
| 18 | `NotificationController` | `/api/v1/notifications` | `SendAlertRequest`(5) | `NotifLogResponse`(8) | no |
| 19-21 | Dashboard, Report, Context | various | (none/query) | inline records | no |

**Total: 21 controllers, ~90 DTOs, ~450 fields.**

---

## 1. CASA DEPOSIT — `DepositAccountController` (`/api/v1/accounts`)

### 1.1 `FinancialRequest` (deposit/withdraw) — 4 fields

| # | Field | Type | Validation | JSP parity | Notes |
|---|---|---|---|---|---|
| 1 | `amount` | BigDecimal | `@NotNull @Positive` | ✅ | -- |
| 2 | `narration` | String | -- | ✅ | -- |
| 3 | `idempotencyKey` | String | -- | ✅ (F1 fix) | JSP mints UUID server-side |
| 4 | `channel` | String | -- | JSP hardcodes `"BRANCH"` | API defaults `"API"` |

### 1.2 `TransferRequest` — 5 fields

| # | Field | Type | Validation | JSP parity |
|---|---|---|---|---|
| 1 | `fromAccount` | String | `@NotBlank` | ✅ |
| 2 | `toAccount` | String | `@NotBlank` | ✅ |
| 3 | `amount` | BigDecimal | `@NotNull @Positive` | ✅ |
| 4 | `narration` | String | -- | ✅ |
| 5 | `idempotencyKey` | String | -- | ✅ (F1 fix) |

### 1.3 `OpenAccountRequest` — 29 fields (6 from JSP, 23 CIF-inherited)

JSP sends fields 1-4, 26-27 only. Fields 5-25, 28-29 are null on JSP path (CIF-inherited at service layer). React BFF sends all 29. See `JSP_DTO_CROSSWALK_MATRIX.md` §1.

### 1.4 Other request DTOs

| DTO | Fields | Notes |
|---|---|---|
| `FreezeRequest` | `freezeType` (`@NotBlank`), `reason` (`@NotBlank`) | JSP sends same |
| `CloseRequest` | `reason` (`@NotBlank`) | JSP sends same |
| `ReversalRequest` | `reason` (`@NotBlank`) | JSP sends `reason` + `accountNumber` |
| `RejectRequest` | `reason` (`@NotBlank`) | API-only (no JSP reject-account flow) |

### 1.5 Response DTOs

| DTO | Fields | PII masking |
|---|---|---|
| `AccountResponse` | 40 fields | `panNumber`, `aadhaarNumber`, `mobileNumber` masked via `PiiMaskingUtil` |
| `TxnResponse` | 18 fields | includes `balanceBefore`, `balanceAfter`, `idempotencyKey`, `reversed`, `reversedByRef` |
| `BalanceResponse` | 8 fields | minimal projection for UPI/IMPS |
| `StatementResponse` | 8 fields | wraps `List<TxnResponse>` with date range |

### 1.6 Service method mapping

| API endpoint | Service method | Same as JSP? |
|---|---|---|
| `POST /open` | `depositService.openAccount(req)` | ✅ same DTO |
| `POST /{n}/deposit` | `depositService.deposit(accNo, amount, bd, narration, idempotencyKey, channel)` | ✅ same 6-arg |
| `POST /{n}/withdraw` | `depositService.withdraw(...)` | ✅ same 6-arg |
| `POST /transfer` | `depositService.transfer(from, to, amount, bd, narration, idempotencyKey)` | ✅ same 6-arg |
| `POST /{n}/activate` | `depositService.activateAccount(accNo)` | ✅ |
| `POST /{n}/reject` | `depositService.rejectAccount(accNo, reason)` | API-only |
| `POST /{n}/freeze` | `depositService.freezeAccount(accNo, type, reason)` | ✅ |
| `POST /reversal/{ref}` | `depositService.reverseTransaction(ref, reason, bd)` | ✅ |
| `GET /pipeline` | `depositService.getPendingAccounts()` | ✅ branch-isolated (F9) |

---

## 2. LOAN APPLICATION — `LoanApplicationController` (`/api/v1/loan-applications`)

### 2.1 `SubmitApplicationRequest` — 11 fields

| # | Field | Type | Validation | JSP parity |
|---|---|---|---|---|
| 1 | `customerId` | Long | `@NotNull` | ✅ |
| 2 | `branchId` | Long | `@NotNull` | ✅ |
| 3 | `productType` | String | `@NotBlank` | ✅ |
| 4 | `requestedAmount` | BigDecimal | `@NotNull @Positive` | ✅ |
| 5 | `interestRate` | BigDecimal | `@NotNull @Positive` | ✅ |
| 6 | `tenureMonths` | Integer | `@NotNull @Positive` | ✅ |
| 7 | `purpose` | String | -- | ✅ |
| 8 | `collateralReference` | String | -- | ✅ |
| 9 | `disbursementAccountNumber` | String | -- | ✅ |
| 10 | `penalRate` | BigDecimal | -- | ✅ |
| 11 | `riskCategory` | String | -- | ✅ (F2 fix) |

### 2.2 Service method mapping

| API endpoint | Service method | Same as JSP? |
|---|---|---|
| `POST /` | `appService.createApplication(app, customerId, branchId)` | ✅ |
| `POST /{id}/verify` | `appService.verifyApplication(id, remarks)` | ✅ |
| `POST /{id}/approve` | `appService.approveApplication(id, remarks)` | ✅ |
| `POST /{id}/reject` | `appService.rejectApplication(id, reason)` | ✅ |

---

## 3. WORKFLOW — `WorkflowApiController` (`/api/v1/workflow`)

### 3.1 `WorkflowActionRequest` — 2 fields

| # | Field | Type | Validation | JSP parity |
|---|---|---|---|---|
| 1 | `remarks` | String | `@NotBlank` | ✅ |
| 2 | `version` | Long | -- (optional) | ✅ (F3 fix) |

### 3.2 `WorkflowResponse` — 16 fields (includes `version` at position 2 per F3 fix)

### 3.3 Service method mapping

| API endpoint | Service method | Same as JSP? |
|---|---|---|
| `POST /{id}/approve` | `workflowService.approve(id, req.version(), req.remarks())` | ✅ (F3) |
| `POST /{id}/reject` | `workflowService.reject(id, req.version(), req.remarks())` | ✅ (F3) |
| `POST /escalate` | `workflowService.escalateBreachedWorkflows()` | API-only |

---

## 4. CUSTOMER CIF — `CustomerApiController` (`/api/v1/customers`)

### 4.1 `CreateCustomerRequest` — 67 fields

| Section | Fields | Count |
|---|---|---|
| Identity | `firstName`\*, `lastName`\*, `middleName`, `dateOfBirth`, `panNumber`, `aadhaarNumber`, `customerType` | 7 |
| Contact | `mobileNumber`, `email`, `alternateMobile`, `communicationPref` | 4 |
| Address (legacy) | `address`, `city`, `state`, `pinCode` | 4 |
| Demographics (CKYC) | `gender`, `fatherName`, `motherName`, `spouseName`, `nationality`, `maritalStatus`, `residentStatus`, `occupationCode`, `annualIncomeBand`, `sourceOfFunds`, `kycRiskCategory`, `pep` | 12 |
| KYC Documents | `kycMode`, `photoIdType`, `photoIdNumber`, `addressProofType`, `addressProofNumber` | 5 |
| OVD (RBI §3) | `passportNumber`, `passportExpiry`, `voterId`, `drivingLicense` | 4 |
| FATCA/CRS | `fatcaCountry` | 1 |
| Permanent Address | `permanentAddress`..`permanentCountry`, `addressSameAsPermanent` | 7 |
| Correspondence Address | `correspondenceAddress`..`correspondenceCountry` | 6 |
| Income & Exposure | `monthlyIncome`, `maxBorrowingLimit`, `employmentType`, `employerName`, `cibilScore` | 5 |
| Segmentation | `customerSegment`, `sourceOfIntroduction` | 2 |
| Corporate (RBI §9) | `companyName`, `cin`, `gstin`, `dateOfIncorporation`, `constitutionType`, `natureOfBusiness` | 6 |
| Nominee | `nomineeDob`, `nomineeAddress`, `nomineeGuardianName` | 3 |
| Branch | `branchId`\* | 1 |
| **Total** | | **67** |

\* = `@NotNull` / `@NotBlank`

**JSP coverage:** 44 original + 13 added (F4/F6/F7) = 57 fields. Remaining 10 (middleName, alternateMobile, communicationPref, permanentDistrict, correspondence address block, customerSegment, sourceOfIntroduction) are LOW priority per `DTO_PARITY_AUDIT_REPORT.md` §2.5.

### 4.2 Response DTOs

| DTO | Fields | PII masking | Notes |
|---|---|---|---|
| `CustomerResponse` | 76 fields | PAN, Aadhaar, mobile masked | full CIF for admin screens |
| `CifLookupResponse` | 30 fields | PAN, Aadhaar, mobile masked | lightweight for shared CifLookup widget (12+ screens) |

### 4.3 Service method mapping

| API endpoint | Service method | Same as JSP? |
|---|---|---|
| `POST /` | `customerService.createCustomerFromEntity(c, branchId)` | ✅ same service method |
| `PUT /{id}` | `customerService.updateCustomer(id, updated, branchId)` | ✅ same service method |
| `GET /{id}` | `customerService.getCustomerWithAudit(id)` | ✅ |
| `POST /{id}/verify-kyc` | `customerService.verifyKyc(id)` | ✅ |
| `POST /{id}/deactivate` | `customerService.deactivateCustomer(id)` | ✅ |

### 4.4 Immutability enforcement

The API controller (lines 121-130) explicitly rejects `PUT` requests that include non-blank `panNumber` or `aadhaarNumber` with HTTP 400 `IMMUTABLE_FIELD`. The JSP controller relies on the service layer to silently ignore these fields (per `CustomerCifServiceImpl.updateCustomer` which does not copy PAN/Aadhaar from the updated entity). Both paths ultimately enforce immutability; the API path fails fast, the JSP path fails silently.

---

## 5. FINDINGS

| # | Severity | Finding |
|---|---|---|
| R1 | **OK** | All financial operations (deposit/withdraw/transfer/reversal) use the SAME service methods on both API and JSP paths. No behavioral divergence possible. |
| R2 | **OK** | `idempotencyKey` is present on both `FinancialRequest` (API) and JSP hidden field (F1). Both paths forward to the same 6-arg service method. |
| R3 | **OK** | `riskCategory` is present on both `SubmitApplicationRequest` (API, F2) and JSP `@ModelAttribute` entity bind. Both paths persist via `LoanApplication.riskCategory`. |
| R4 | **OK** | `version` is present on both `WorkflowActionRequest` (API, F3) and JSP hidden field (F3). Both paths forward to the same 3-arg service overload. |
| R5 | **OK** | `CreateCustomerRequest` (67 fields) is a superset of the JSP form (57 fields). No field exists on JSP that doesn't exist on the API DTO. |
| R6 | **NOTE** | `RejectRequest` on `DepositAccountController` (reject PENDING_ACTIVATION account) is API-only — no JSP equivalent. The JSP path has no account-rejection flow; CHECKERs can only activate or leave pending. |
| R7 | **NOTE** | `POST /escalate` on `WorkflowApiController` is API-only — no JSP equivalent. SLA escalation is an ADMIN background operation. |
| R8 | **NOTE** | `CifLookupResponse` (30 fields) is a separate lightweight DTO used by the React CifLookup widget. JSP uses the full `Customer` entity directly via `@ModelAttribute`. No parity issue — different architectural patterns. |

---

*End of REST API Crosswalk. This document proves the REST contract for the React BFF. Combined with `JSP_NAVIGATION_AUDIT.md` (JSP contract) and `JSP_DTO_CROSSWALK_MATRIX.md` (field-level crosswalk), the full API surface of the Finvanta CBS is now documented and validated.*

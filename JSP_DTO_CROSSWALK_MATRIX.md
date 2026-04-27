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

# FINVANTA -- JSP / SPRING MVC NAVIGATION & ATTRIBUTE FLOW AUDIT

**Audit Date:** 2026-04-27
**Branch:** `devin/1777206832-cbs-tier1-architecture-refactor`
**Scope:** Spring MVC controllers under `src/main/java/com/finvanta/controller/*.java` returning `ModelAndView` / view names, and their JSP views under `src/main/webapp/WEB-INF/views/**`.
**Out of scope:** REST controllers under `com.finvanta.api.*` (JSON for the React BFF) and the refactored `com.finvanta.cbs.modules.*.controller.*` (v2 API).
**Auditor roles:** Tier-1 CBS Architect | Principal Spring Boot Engineer | Enterprise UI Architect (JSP) | RBI Compliance & Audit Specialist | CBS QA Architect | Banking Domain SME

---

## 0. SITE MAP

| Module | Base URI | Controller |
|---|---|---|
| Dashboard / Login | `/`, `/dashboard`, `/login` | `controller/DashboardController.java` |
| CIF (Customer) | `/customer/**` | `controller/CustomerWebController.java` |
| CASA (Deposit) | `/deposit/**` | `controller/DepositController.java` |
| Loan | `/loan/**` | `controller/LoanController.java` |
| Branch | `/branch/**` | `controller/BranchController.java` |
| Branch switch (ADMIN) | `/admin/switch-branch`, `/admin/reset-branch` | `controller/BranchSwitchController.java` |
| Admin (Products / Limits / Charges / MFA / IB) | `/admin/**` | `controller/AdminController.java` |
| Users | `/admin/users/**` | `controller/UserController.java` |
| Calendar / Day Control | `/calendar/**` | `controller/CalendarController.java` |
| EOD Batch | `/batch/eod/**` | `controller/BatchController.java` |
| Txn Batch (intra-day) | `/batch/txn/**` | `controller/TransactionBatchController.java` |
| Accounting / GL | `/accounting/**` | `controller/AccountingController.java` |
| Reconciliation | `/reconciliation/report` | `controller/ReconciliationController.java` |
| Reports | `/reports/**` | `controller/ReportController.java` |
| Audit | `/audit/**` | `controller/AuditController.java` |
| Workflow (Maker-Checker) | `/workflow/**` | `controller/WorkflowController.java` |
| Txn 360 | `/txn360/**` | `controller/Txn360Controller.java` |
| Password | `/password/**` | `controller/PasswordController.java` |
| MFA Login | `/mfa/**` | `controller/MfaLoginController.java` |
| Error pages | `/error/**` | `controller/ErrorController.java` |

---

## 1. DASHBOARD & AUTH

### `DashboardController` (`src/main/java/com/finvanta/controller/DashboardController.java`)

| HTTP | URI | View / Redirect | Inputs (JSP -> Ctrl) | Model attributes (Ctrl -> JSP) |
|---|---|---|---|---|
| GET | `/` | redirect -> `/dashboard` | -- | -- |
| GET | `/login` | `login` | -- | -- |
| GET | `/dashboard` | `dashboard/index` | -- | `totalCustomers`, `pendingApplications`, `activeLoans`, `smaAccounts`, `npaAccounts`, `pendingApprovals`, `totalOutstanding`, `npaOutstanding`, `totalProvisioning`, `grossNpaRatio`, `provisionCoverage`, `totalDeposits`, `casaAccountCount`, `casaRatio` |

---

## 2. CIF (CUSTOMER)

### `CustomerWebController` (`src/main/java/com/finvanta/controller/CustomerWebController.java`)

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/customer/list` | `customer/list` | `page`, `size` | `customers`, `customerPage` |
| GET | `/customer/search` | `customer/list` | `q`, `page`, `size` | `customers`, `customerPage`, `searchQuery` |
| GET | `/customer/add` | `customer/add` | -- | `customer` (empty), `branches`, `defaultBranchId` |
| POST | `/customer/add` | redirect `/customer/list` (success) / re-render `customer/add` (error) | `@ModelAttribute Customer`, `branchId` | On error: `customer`, `error`, `branches`, `defaultBranchId` |
| GET | `/customer/view/{id}` | `customer/view` | path `id` | `customer`, `maskedPan`, `maskedAadhaar`, `maskedMobile`, `loanApplications`, `loanAccounts`, `depositAccounts`, `documents` |
| GET | `/customer/edit/{id}` | `customer/edit` | path `id` | `customer`, `maskedPan`, `maskedAadhaar`, `branches` |
| POST | `/customer/edit/{id}` | redirect `/customer/view/{id}` / re-render `customer/edit` | path `id`, `@ModelAttribute Customer`, `branchId` | On error: `customer`, `maskedPan`, `maskedAadhaar`, `error`, `branches` |
| POST | `/customer/verify-kyc/{id}` | redirect `/customer/view/{id}` | path `id` | flash |
| POST | `/customer/deactivate/{id}` | redirect `/customer/view/{id}` | path `id` | flash |
| POST | `/customer/document/upload/{customerId}` | redirect `/customer/view/{customerId}` | path `customerId`, multipart `file`, `documentType`, `documentNumber`, `remarks` | flash |
| GET | `/customer/document/download/{docId}` | binary stream | path `docId` | n/a |
| POST | `/customer/document/verify/{docId}` | redirect `/customer/view/{customerId}` | path `docId`, `action`, `rejectionReason` | flash |

> The `Customer` `@ModelAttribute` binding maps every `customer/add.jsp` / `customer/edit.jsp` form field directly onto the JPA entity by name (firstName, middleName, lastName, panNumber, aadhaarNumber, mobileNumber, dateOfBirth, gender, maritalStatus, address, city, state, pinCode, occupationCode, monthlyIncome, kycMode, photoIdType, addressProofType, pep, etc.). See `DTO_PARITY_AUDIT_REPORT.md` Section 2.5 for the field-by-field parity map.

---

## 3. CASA (DEPOSIT)

### `DepositController` (`src/main/java/com/finvanta/controller/DepositController.java`)

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/deposit/accounts` | `deposit/accounts` | -- | `accounts`, `pageTitle` |
| GET | `/deposit/search` | `deposit/accounts` | `q` | `accounts`, `searchQuery`, `pageTitle` |
| GET | `/deposit/pipeline` | `deposit/pipeline` | -- | `pendingAccounts`, `activeAccounts`, `attentionAccounts`, `pageTitle` |
| GET | `/deposit/open` | `deposit/open` | -- | `customers`, `branches`, `products`, `pageTitle` |
| POST | `/deposit/open` | redirect `/deposit/view/{accNo}` | `customerId`, `branchId`, `accountType`, `productCode`, `nomineeName`, `nomineeRelationship` | flash |
| GET | `/deposit/view/{accountNumber}` | `deposit/view` | path `accountNumber` | `account`, `transactions`, `standingInstructions`, `activeAccounts`, `accountBranchCode`, `pageTitle` |
| GET | `/deposit/preview/{accountNumber}` | `@ResponseBody` JSON (AJAX) | path `accountNumber`, `amount`, `txnType`, `narration` | JSON `TransactionPreview` |
| GET | `/deposit/deposit/{accountNumber}` | `deposit/deposit` | path `accountNumber` | `account`, `pageTitle` |
| POST | `/deposit/deposit/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber`, `amount`, `narration` | flash |
| GET | `/deposit/withdraw/{accountNumber}` | `deposit/withdraw` | path `accountNumber` | `account`, `pageTitle` |
| POST | `/deposit/withdraw/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber`, `amount`, `narration` | flash |
| GET | `/deposit/transfer` | `deposit/transfer` | -- | `accounts` (source), `allAccounts` (target), `pageTitle` |
| POST | `/deposit/transfer` | redirect `/deposit/view/{fromAccount}` (or `/deposit/transfer` on error) | `fromAccount`, `toAccount`, `amount`, `narration` | flash |
| POST | `/deposit/freeze/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber`, `freezeType`, `reason` | flash |
| POST | `/deposit/unfreeze/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber` | flash |
| POST | `/deposit/close/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber`, `reason` | flash |
| POST | `/deposit/maintain/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber`, `nomineeName`, `nomineeRelationship`, `jointHolderMode`, `chequeBookEnabled`, `debitCardEnabled`, `dailyWithdrawalLimit`, `dailyTransferLimit`, `odLimit`, `interestRate`, `minimumBalance` | flash |
| POST | `/deposit/activate/{accountNumber}` | redirect `/deposit/view/{accNo}` | path `accountNumber` | flash |
| POST | `/deposit/reversal/{transactionRef}` | redirect `/deposit/view/{accNo}` | path `transactionRef`, `reason`, `accountNumber` | flash |
| GET | `/deposit/statement/{accountNumber}/export` | CSV | path `accountNumber`, `fromDate`, `toDate` | n/a |
| GET | `/deposit/statement/{accountNumber}` | `deposit/statement` | path `accountNumber`, `fromDate`, `toDate` | `account`, `transactions`, `fromDate`, `toDate`, `pageTitle`, optional `error` |

JSP backers: `deposit/open.jsp`, `deposit/view.jsp`, `deposit/deposit.jsp`, `deposit/withdraw.jsp`, `deposit/transfer.jsp`, `deposit/statement.jsp`, `deposit/pipeline.jsp`, `deposit/accounts.jsp`.

---

## 4. LOAN

### `LoanController` (`src/main/java/com/finvanta/controller/LoanController.java`)

**Application lifecycle**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/loan/apply` | `loan/apply` | -- | `application` (empty), `customers`, `branches`, `casaAccounts`, `products` |
| POST | `/loan/apply` | redirect `/loan/applications` | `@ModelAttribute LoanApplication` (productType, requestedAmount, interestRate, tenureMonths, purpose, collateralReference, disbursementAccountNumber, penalRate, riskCategory*) + `customerId`, `branchId` | flash |
| GET | `/loan/applications` | `loan/applications` | -- | `applications` (SUBMITTED), `verifiedApplications`, `approvedApplications` |
| GET | `/loan/applications/search` | `loan/applications` | `q` | same buckets + `searchQuery` |
| GET | `/loan/verify/{id}` | `loan/verify` | path `id` | `application`, `collaterals`, `documents`, `collateralTypes` |
| POST | `/loan/verify/{id}` | redirect `/loan/applications` | path `id`, `remarks` | flash |
| GET | `/loan/approve/{id}` | `loan/approve` | path `id` | `application`, `collaterals`, `documents` |
| POST | `/loan/approve/{id}` | redirect `/loan/applications` | path `id`, `remarks` | flash |
| POST | `/loan/reject/{id}` | redirect `/loan/applications` | path `id`, `reason` | flash |

**Account lifecycle**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/loan/accounts` | `loan/accounts` | -- | `accounts` |
| GET | `/loan/accounts/search` | `loan/accounts` | `q` | `accounts`, `searchQuery` |
| GET | `/loan/account/{accountNumber}` | `loan/account-details` | path `accountNumber` | `account`, `transactions`, `schedule`, `collaterals`, `documents`, `accrualHistory`, `standingInstructions`, optional `productId`, `schedulePreview`, `previewEmi`, `previewTotalInterest`, `previewTotalPayable` |
| POST | `/loan/disburse/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber` | flash |
| POST | `/loan/repayment/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber`, `amount` | flash |
| POST | `/loan/prepayment/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber`, `amount` | flash |
| POST | `/loan/write-off/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber` | flash |
| POST | `/loan/create-account/{applicationId}` | redirect `/loan/account/{accNo}` | path `applicationId` | flash |
| POST | `/loan/reversal/{transactionRef}` | redirect `/loan/account/{accNo}` | path `transactionRef`, `reason`, `accountNumber` | flash |
| POST | `/loan/fee/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber`, `feeAmount`, `feeType` | flash |
| POST | `/loan/disburse-tranche/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber`, `trancheAmount`, `narration` | flash |
| POST | `/loan/restructure/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber`, `newRate`, `additionalMonths`, `reason` | flash |
| POST | `/loan/moratorium/{accountNumber}` | redirect `/loan/account/{accNo}` | path `accountNumber`, `moratoriumMonths`, `reason` | flash |

**Collateral & documents (Finacle COLMAS / DOCMAS)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| POST | `/loan/collateral/{applicationId}` | redirect `/loan/verify/{id}` | path `applicationId` + 18 collateral params (`collateralType`, `ownerName`, `ownerRelationship`, `goldPurity`, `goldWeightGrams`, `goldNetWeightGrams`, `goldRatePerGram`, `propertyAddress`, `propertyType`, `propertyAreaSqft`, `registrationNumber`, `vehicleRegistration`, `vehicleMake`, `vehicleModel`, `fdNumber`, `fdBankName`, `fdAmount`, `marketValue`, `description`) | flash |
| POST | `/loan/document/{applicationId}` | redirect `/loan/verify/{id}` | path `applicationId`, `documentType`, `documentName`, `remarks`, `mandatory` | flash |
| POST | `/loan/document/verify/{documentId}` | redirect `/loan/verify/{appId}` | path `documentId`, `applicationId` | flash |
| POST | `/loan/document/reject/{documentId}` | redirect `/loan/verify/{appId}` | path `documentId`, `applicationId`, `rejectionReason` | flash |

**Standing Instructions (Finacle SI_MASTER)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/loan/si/dashboard` | `loan/si-dashboard` | -- | `pageTitle`, `allSIs`, `failedSIs`, `pendingSIs`, `totalPending`, `totalActive`, `totalPaused`, `totalFailed`, `sisByType`, `executionForecast` |
| POST | `/loan/si/register` | redirect `/deposit/view/{sourceAccNo}` | `customerId`, `sourceAccountNumber`, `destinationType`, `destinationAccountNumber`, `amount`, `frequency`, `executionDay`, `startDate`, `endDate`, `narration` | flash |
| POST | `/loan/si/approve/{siReference}` | redirect `/loan/si/dashboard` | path `siReference` | flash |
| POST | `/loan/si/reject/{siReference}` | redirect `/loan/si/dashboard` | path `siReference`, `reason` | flash |
| POST | `/loan/si/pause/{siReference}` | redirect `/loan/account/{accNo}` | path `siReference`, `accountNumber` | flash |
| POST | `/loan/si/resume/{siReference}` | redirect `/loan/account/{accNo}` | path `siReference`, `accountNumber` | flash |
| POST | `/loan/si/cancel/{siReference}` | redirect `/loan/account/{accNo}` | path `siReference`, `accountNumber` | flash |
| POST | `/loan/si/amend/{siReference}` | redirect `/loan/account/{accNo}` | path `siReference`, `accountNumber`, `newAmount`, `newFrequency`, `newExecutionDay` | flash |

> *`riskCategory` on `loan/apply.jsp` is captured by the JSP form but is NOT a field on `LoanApplication` -- silently dropped. See `DTO_PARITY_AUDIT_REPORT.md` Section 2.6.

JSP backers: `loan/apply.jsp`, `loan/applications.jsp`, `loan/verify.jsp`, `loan/approve.jsp`, `loan/accounts.jsp`, `loan/account-details.jsp`, `loan/si-dashboard.jsp`.

---

## 5. BRANCH

### `BranchController` (`src/main/java/com/finvanta/controller/BranchController.java`)

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/branch/list` | `branch/list` | -- | `branches` |
| GET | `/branch/search` | `branch/list` | `q` | `branches`, `searchQuery` |
| GET | `/branch/add` | `branch/add` | -- | `branch` (empty), `branchTypes`, `parentBranches` |
| POST | `/branch/add` | redirect `/branch/list` | `@ModelAttribute Branch` | flash |
| GET | `/branch/view/{id}` | `branch/view` | path `id` | `branch`, `customers`, `totalOutstanding`, `loanAccounts`, `npaCount`, `smaCount`, `activeCount` |
| GET | `/branch/edit/{id}` | `branch/edit` | path `id` | `branch` |
| POST | `/branch/edit/{id}` | redirect `/branch/view/{id}` | path `id`, `@ModelAttribute Branch` | flash |

### `BranchSwitchController` (`src/main/java/com/finvanta/controller/BranchSwitchController.java`)

| HTTP | URI | Redirect | Inputs | Side effects |
|---|---|---|---|---|
| POST | `/admin/switch-branch` | redirect `/dashboard` | `branchId` | sets session attrs `SecurityUtil.SWITCHED_BRANCH_ID/CODE`; emits `BRANCH_SWITCH` audit |
| POST | `/admin/reset-branch` | redirect `/dashboard` | -- | clears session attrs |

JSP backers: `branch/list.jsp`, `branch/add.jsp`, `branch/view.jsp`, `branch/edit.jsp`. Branch switch uses topbar dropdown rendered by `layout/header.jsp`.

---

## 6. ADMIN (PRODUCTS / LIMITS / CHARGES / MFA / IB SETTLEMENT)

### `AdminController` (`src/main/java/com/finvanta/controller/AdminController.java`)

**Products (Finacle PDDEF)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/admin/products` | `admin/products` | -- | `products` |
| GET | `/admin/products/search` | `admin/products` | `q` | `products`, `searchQuery` |
| GET | `/admin/products/{id}` | `admin/product-detail` | path `id` | `product`, `activeAccountCount` |
| GET | `/admin/products/{id}/edit` | `admin/product-edit` | path `id` | `product`, `activeAccountCount`, `glAccounts` |
| POST | `/admin/products/{id}/edit` | redirect `/admin/products/{id}` (success) / re-render `admin/product-edit` (error) | path `id`, `@ModelAttribute ProductMaster` | On error: `product`, `activeAccountCount`, `glAccounts`, `error` |
| POST | `/admin/products/{id}/status` | redirect `/admin/products/{id}` | path `id`, `status` | flash |
| GET | `/admin/products/create` | `admin/product-create` | -- | `glAccounts`, `pageTitle` |
| POST | `/admin/products/create` | redirect `/admin/products` (success) / re-render `admin/product-create` (error) | `@ModelAttribute ProductMaster` | On error: `product`, `error`, `glAccounts` |
| POST | `/admin/products/evict-cache` | redirect `/admin/products` | -- | flash |

**Transaction Limits (Finacle LIMDEF)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/admin/limits` | `admin/limits` | -- | `limits` |
| POST | `/admin/limits/create` | redirect `/admin/limits` | `role`, `transactionType`, `perTransactionLimit`, `dailyAggregateLimit`, `description` | flash |
| POST | `/admin/limits/{id}/edit` | redirect `/admin/limits` | path `id`, `perTransactionLimit`, `dailyAggregateLimit`, `description` | flash |
| POST | `/admin/limits/{id}/toggle-active` | redirect `/admin/limits` | path `id` | flash |

**Charges (Finacle CHRG_MASTER)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/admin/charges` | `admin/charges` | -- | `charges`, `glAccounts`, `products` |
| POST | `/admin/charges/create` | redirect `/admin/charges` | `chargeCode`, `chargeName`, `chargeCategory`, `eventTrigger`, `calculationType`, `frequency`, `baseAmount`, `percentage`, `slabJson`, `minAmount`, `maxAmount`, `currencyCode`, `gstApplicable`, `gstRate`, `glChargeIncome`, `glGstPayable`, `waiverAllowed`, `maxWaiverPercent`, `productCode`, `channel`, `validFrom`, `validTo`, `customerDescription` | flash |
| POST | `/admin/charges/{id}/edit` | redirect `/admin/charges` | path `id` + same params as create (minus `chargeCode`) | flash |
| POST | `/admin/charges/{id}/toggle-active` | redirect `/admin/charges` | path `id` | flash |

**MFA (RBI IT Governance Direction 2023 Section 8.4)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/admin/mfa` | `admin/mfa` | -- | `users` |
| POST | `/admin/mfa/enable` | redirect `/admin/mfa` | `username` | flash |
| POST | `/admin/mfa/enroll` | `admin/mfa-enroll` (success) / redirect `/admin/mfa` (error) | `username` | `username`, `secret`, `otpAuthUri`, `qrCodeDataUri` |
| POST | `/admin/mfa/verify` | redirect `/admin/mfa` | `username`, `totpCode` | flash |
| POST | `/admin/mfa/disable` | redirect `/admin/mfa` | `username`, `reason` | flash |

**Inter-Branch Settlement (Finacle IB_SETTLEMENT)**

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/admin/ib-settlement` | `admin/ib-settlement` | -- | `stalePendingCount` |
| POST | `/admin/ib-settlement/manual-settle` | redirect `/admin/ib-settlement` | `reason`, `hoAuthorizationRef` | flash |

JSP backers: `admin/products.jsp`, `admin/product-detail.jsp`, `admin/product-edit.jsp`, `admin/product-create.jsp`, `admin/limits.jsp`, `admin/charges.jsp`, `admin/mfa.jsp`, `admin/mfa-enroll.jsp`, `admin/ib-settlement.jsp`.

---

## 7. USERS

### `UserController` (`src/main/java/com/finvanta/controller/UserController.java`)

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/admin/users` | `admin/users` | -- | `users`, `roles`, `branches`, `pageTitle` |
| GET | `/admin/users/search` | `admin/users` | `q` | `users`, `roles`, `branches`, `pageTitle`, `searchQuery` |
| POST | `/admin/users/create` | redirect `/admin/users` | `username`, `password`, `fullName`, `email`, `role`, `branchId` | flash |
| POST | `/admin/users/toggle-active/{id}` | redirect `/admin/users` | path `id` | flash |
| POST | `/admin/users/unlock/{id}` | redirect `/admin/users` | path `id` | flash |
| POST | `/admin/users/reset-password/{id}` | redirect `/admin/users` | path `id`, `newPassword` | flash |

JSP backer: `admin/users.jsp`.

---

## 8. CALENDAR / DAY CONTROL

### `CalendarController` (`src/main/java/com/finvanta/controller/CalendarController.java`) -- Finacle DAYCTRL

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/calendar/list` | `calendar/list` | -- | `calendarDates`, `openDay`, `currentBranchId`, `currentBranchCode` |
| POST | `/calendar/day-open` | redirect `/calendar/list` | `businessDate`, `branchId` | flash |
| POST | `/calendar/day-close` | redirect `/calendar/list` | `businessDate`, `branchId` | flash |
| POST | `/calendar/generate` | redirect `/calendar/list` | `year`, `month` | flash `success`/`info`/`error` |
| POST | `/calendar/add-holiday` | redirect `/calendar/list` | `date`, `description` | flash |
| POST | `/calendar/remove-holiday` | redirect `/calendar/list` | `date` | flash |

JSP backer: `calendar/list.jsp`.

---

## 9. BATCH (EOD + INTRA-DAY)

### `BatchController` (`src/main/java/com/finvanta/controller/BatchController.java`) -- Finacle EOD_TRIAL + EOD_APPLY

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/batch/eod` | `batch/eod` | -- | `batchHistory`, `currentBusinessDate` |
| POST | `/batch/eod/trial` | `batch/eod` (or redirect on error) | `businessDate` | `batchHistory`, `currentBusinessDate`, `trialResults`, `trialClean`, `trialDate` |
| POST | `/batch/eod/apply` | redirect `/batch/eod` | `businessDate` | flash |
| POST | `/batch/eod/run` *(deprecated)* | redirect `/batch/eod` | `businessDate` | delegates to `/batch/eod/apply` |

### `TransactionBatchController` (`src/main/java/com/finvanta/controller/TransactionBatchController.java`)

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/batch/txn/list` | `batch/txn-batches` | `businessDate` | `batches`, `businessDate` |
| POST | `/batch/txn/open` | redirect `/batch/txn/list?businessDate=...` | `businessDate`, `batchName`, `batchType`, `branchId` | flash |
| POST | `/batch/txn/close/{id}` | redirect `/batch/txn/list?businessDate=...` | path `id`, `businessDate` | flash |

JSP backers: `batch/eod.jsp`, `batch/txn-batches.jsp`.

---

## 10. ACCOUNTING / GL

### `AccountingController` (`src/main/java/com/finvanta/controller/AccountingController.java`)

| HTTP | URI | View | Inputs | Model |
|---|---|---|---|---|
| GET | `/accounting/trial-balance` | `accounting/trial-balance` | -- | `trialBalance` |
| GET | `/accounting/gl/search` | `accounting/trial-balance` | `q` | `trialBalance` (filtered map: `accounts`, `totalDebit`, `totalCredit`, `isBalanced`), `searchQuery` |
| GET | `/accounting/journal-entries` | `accounting/journal-entries` | `fromDate`, `toDate` | `entries`, `totalDebit`, `totalCredit`, `fromDate`, `toDate`, optional `warning`/`error` |
| GET | `/accounting/journal-entries/search` | `accounting/journal-entries` | `q`, `fromDate`, `toDate` | same + `searchQuery` |
| GET | `/accounting/financial-statements` | `accounting/financial-statements` | -- | `assets`, `liabilities`, `equity`, `income`, `expenses`, `totalAssets`, `totalLiabilities`, `totalEquity`, `totalIncome`, `totalExpenses`, `netProfit`, `balanceCheck`, `pageTitle` |
| GET | `/accounting/voucher-register` | `accounting/voucher-register` | `businessDate` | `pageTitle`, `ledgerEntries`, `loanTransactions`, `depositTransactions`, `reportDate`, optional `error` |

JSP backers: `accounting/trial-balance.jsp`, `accounting/journal-entries.jsp`, `accounting/financial-statements.jsp`, `accounting/voucher-register.jsp`.

---

## 11. RECONCILIATION

### `ReconciliationController` (`src/main/java/com/finvanta/controller/ReconciliationController.java`)

| HTTP | URI | View | Inputs | Model |
|---|---|---|---|---|
| GET | `/reconciliation/report` | `reconciliation/report` | `businessDate` | `reconResult`, `subledgerResult`, `branchBalanceResult`, `businessDate` |

JSP backer: `reconciliation/report.jsp`.

---

## 12. REPORTS

### `ReportController` (`src/main/java/com/finvanta/controller/ReportController.java`)

| HTTP | URI | View | Inputs | Model |
|---|---|---|---|---|
| GET | `/reports/dpd` | `reports/dpd` | -- | `dpdData`, `totalAccounts`, `businessDate` |
| GET | `/reports/irac` | `reports/irac` | -- | `iracData`, `totalAccounts`, `totalOutstanding`, `totalNpaOutstanding`, `npaRatio`, `businessDate` |
| GET | `/reports/provision` | `reports/provision` | -- | `provisionData`, `totalOutstanding`, `totalProvisioning`, `provisionCoverageRatio`, `businessDate` |
| GET | `/reports/udgam` | `reports/udgam` | -- | `unclaimedAccounts`, `totalUnclaimed`, `totalCount`, `businessDate`, `pageTitle` |
| GET | `/reports/udgam/export` | CSV | -- | n/a |

> Branch isolation enforced by `getBranchScopedAccounts()` (`ReportController.java:60-72`) -- ADMIN/AUDITOR see all branches, MAKER/CHECKER see only their home branch.

JSP backers: `reports/dpd.jsp`, `reports/irac.jsp`, `reports/provision.jsp`, `reports/udgam.jsp`.

---

## 13. AUDIT

### `AuditController` (`src/main/java/com/finvanta/controller/AuditController.java`) -- RBI IT Governance Direction 2023 Section 8.3

| HTTP | URI | View | Inputs | Model |
|---|---|---|---|---|
| GET | `/audit/logs` | `audit/logs` | -- | `auditLogs`, `chainIntegrity` (bounded recent-window check) |
| GET | `/audit/verify` | `audit/logs` | -- | `auditLogs`, `chainIntegrity` (full O(N) walk), `fullChainVerified` |
| GET | `/audit/entity` | `audit/logs` | `entityType`, `entityId` | `auditLogs` (paginated), `chainIntegrity`, `entityFilter` |
| GET | `/audit/search` | `audit/logs` | `q`, `fromDate`, `toDate` | `auditLogs`, `chainIntegrity`, `searchQuery`, optional `fromDate`/`toDate`/`error` |

> `entityType` whitelisted against `KNOWN_ENTITY_TYPES` (`AuditController.java:41-45`): Customer, DepositAccount, LoanAccount, LoanApplication, Transaction, JournalEntry, Branch, ProductMaster, StandingInstruction, ApprovalWorkflow, TransactionLimit, ChargeConfig, BusinessCalendar, User. New entity types must be added to this set or audit lookups fail with `INVALID_ENTITY_TYPE`.

JSP backer: `audit/logs.jsp`.

---

## 14. WORKFLOW (MAKER-CHECKER)

### `WorkflowController` (`src/main/java/com/finvanta/controller/WorkflowController.java`)

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/workflow/pending` | `workflow/pending` | -- | `pendingItems` |
| POST | `/workflow/approve/{id}` | redirect `/workflow/pending` | path `id`, `remarks` | flash `success`/`error` (also triggers GL re-execution + DEPOSIT-module subledger apply for `Transaction` workflows; see `WorkflowController.java:74-119`) |
| POST | `/workflow/reject/{id}` | redirect `/workflow/pending` | path `id`, `remarks` | flash |

> The JSP `workflow/pending.jsp` posts `remarks` only. React sends an additional `version` for optimistic locking that the backend `WorkflowActionRequest` does not accept. See `DTO_PARITY_AUDIT_REPORT.md` Section 2.7.

JSP backer: `workflow/pending.jsp`.

---

## 15. TRANSACTION 360

### `Txn360Controller` (`src/main/java/com/finvanta/controller/Txn360Controller.java`)

| HTTP | URI | View | Inputs | Model |
|---|---|---|---|---|
| GET | `/txn360/search` | `txn360/search` | `q` | `query`, plus `depositTxn` / `loanTxn` / `journalEntry` / `ledgerEntries` / `sourceModule` / `error` (depending on resolver outcome) |
| GET | `/txn360/voucher/**` | `txn360/view` | path tail (voucher number `VCH/{branch}/{YYYYMMDD}/{seq}`) | `lookupType="Voucher"`, `lookupValue`, plus all attrs from `Transaction360Service.getByVoucher(...)` |
| GET | `/txn360/journal/{journalRef}` | `txn360/view` | path `journalRef` | `lookupType="Journal Ref"`, `lookupValue`, plus attrs from `getByJournalRef(...)` |
| GET | `/txn360/{transactionRef}` *(catch-all, declared LAST)* | `txn360/view` | path `transactionRef` | `lookupType="Transaction Ref"`, `lookupValue`, plus attrs from `getTransaction360(...)` |

> The catch-all `/{transactionRef}` mapping MUST remain declared LAST in the controller so Spring MVC's resolver picks the more specific `/search`, `/voucher/**`, and `/journal/{ref}` paths first (`Txn360Controller.java:201-212`). Worth enforcing in CI via an ArchUnit rule.

JSP backers: `txn360/search.jsp`, `txn360/view.jsp`.

---

## 16. PASSWORD

### `PasswordController` (`src/main/java/com/finvanta/controller/PasswordController.java`) -- RBI IT Governance Direction 2023 Section 8.2

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/password/change` | `password/change` | `expired` (query) | `username`, `expired` |
| POST | `/password/change` | redirect `/login?password_changed` (success) / redirect `/password/change` (error) | `currentPassword`, `newPassword`, `confirmPassword` | session invalidated on success; flash `error` on failure |

JSP backer: `password/change.jsp`.

---

## 17. MFA LOGIN

### `MfaLoginController` (`src/main/java/com/finvanta/controller/MfaLoginController.java`) -- RBI IT Governance Direction 2023 Section 8.4

| HTTP | URI | View / Redirect | Inputs | Model |
|---|---|---|---|---|
| GET | `/mfa/verify` | `mfa/verify` | -- | `username`, `remainingAttempts` |
| POST | `/mfa/verify` | redirect `/dashboard` (success) / `/password/change?expired=true` / `/login?mfa_locked` / `/mfa/verify` | `totpCode` | session attrs `MFA_VERIFIED_ATTR`, `MFA_FAILED_ATTEMPTS`; flash `error` |

> `MAX_MFA_ATTEMPTS = 5`; on the 5th failure the session is invalidated and the user is forced back to `/login?mfa_locked`.

JSP backer: `mfa/verify.jsp`.

---

## 18. ERROR PAGES

### `ErrorController` (`src/main/java/com/finvanta/controller/ErrorController.java`)

| HTTP | URI | View | Inputs | Model |
|---|---|---|---|---|
| GET | `/error/403` | `error/error` | -- | `errorCode="403"`, `errorTitle`, `errorMessage` |
| ANY | `/error` | `error/error` | servlet error attributes | `errorCode`, `errorTitle`, `errorMessage` |

JSP backer: `error/error.jsp`.

---

## 19. CROSS-CUTTING JSP LAYOUT ATTRIBUTES

Every JSP includes `WEB-INF/views/layout/header.jsp` + `sidebar.jsp`. The shared model attributes for the layout (business date, user role, branch code, branch switch dropdown, etc.) are populated by `CbsLayoutAdvice` (a `@ControllerAdvice`) -- not by individual page controllers. Note that `CommonModelAdvice` was deleted in this PR (`src/main/java/com/finvanta/config/CommonModelAdvice.java` removed) to eliminate the duplicate `@ModelAttribute` source. `CbsLayoutAdvice` is now the single authoritative source for topbar context attributes.

---

## 20. AUDIT FINDINGS & OBSERVATIONS

1. **Entity binding via `@ModelAttribute`** in `customer/add`, `customer/edit`, `loan/apply`, `branch/add`, `branch/edit`, `admin/products/create`, `admin/products/{id}/edit` directly binds JSP form fields onto JPA entities. Convenient but bypasses DTO isolation -- see Tier-1 audit `CBS_TIER1_AUDIT_REPORT.md` violation **C3** (entity exposure).
2. **Idempotency keys missing from JSP financial flows** (`/deposit/deposit`, `/deposit/withdraw`, `/deposit/transfer`) -- the controller passes `null` to the service. React mints UUIDs; JSP retries can double-post. See `DTO_PARITY_AUDIT_REPORT.md` C2.
3. **Loan apply JSP submits `riskCategory`** but `LoanController.submitApplication` does not consume it -- silently dropped (the `LoanApplication` entity has no such bound field). See `DTO_PARITY_AUDIT_REPORT.md` Section 2.6.
4. **Workflow JSP has no optimistic-lock `version`** -- only `remarks` is captured; the backend `WorkflowActionRequest` doesn't expose `version` either, so concurrent approval is not gated at the controller layer.
5. **`@RequestMapping` ordering hazard in `Txn360Controller`** is mitigated only by declaration order. Recommend an ArchUnit rule that asserts `/{transactionRef}` is declared after the more specific mappings.
6. **`AuditController.entityAuditTrail` whitelist** (`AuditController.java:41-45`) is hard-coded -- when new auditable entity types are added, the whitelist must be updated or audit lookups will throw `INVALID_ENTITY_TYPE`. Consider moving to an enum or a registry.
7. **`AccountingController.searchJournalEntries`** date-range fallback resets `from`/`to` but does not clear `searchQuery` -- UX shows the "Invalid date format" error while still highlighting the search input.
8. **`DepositController.openAccount` (POST)** constructs an `OpenAccountRequest` with most fields hard-coded to `null` (`DepositController.java:241-254`). For non-individual customers (corporate, trust) the JSP cannot capture mandatory fields -- see `DTO_PARITY_AUDIT_REPORT.md` Section 2.5.
9. **`/admin/users/reset-password/{id}`** accepts the new password in a `@RequestParam`. Verify CSRF is enforced (Spring Security default) and that the access-log filter strips the parameter from logs (RBI IT Governance Section 8.3).
10. **`@Controller` returning `@ResponseBody` JSON** in `DepositController.previewTransaction` (`DepositController.java:303-422`) -- functional but inconsistent with the v1 REST surface that lives under `com.finvanta.api.*`. Consider moving the preview into the API package.

---

*Report compiled for the Finvanta CBS Tier-1 audit programme. Companion documents: `CBS_TIER1_AUDIT_REPORT.md` (architecture), `CBS_COMPLIANCE_AUDIT_REPORT.md` (prohibited terms + compliance), `DTO_PARITY_AUDIT_REPORT.md` (DTO field parity across Backend/JSP/React).*

---

## 21. SCREEN-LEVEL FORM ATTRIBUTES (JSP -> Controller binding)

This section enumerates every form input on each JSP screen and shows where the value lands on the server: either a JPA-entity property (via `@ModelAttribute`) or a controller `@RequestParam`. Attribute names are extracted directly from `name="..."` in the JSP source. CSRF tokens (`${_csrf.parameterName}`) are present on all POSTs and are intentionally not listed.

> Convention: entity-binding screens (Spring `@ModelAttribute`) flow into the entity by property name. `customer/add.jsp` binds to `Customer`; `loan/apply.jsp` binds to `LoanApplication`; `branch/add.jsp` / `branch/edit.jsp` bind to `Branch`; `admin/product-create.jsp` / `admin/product-edit.jsp` bind to `ProductMaster`. Everything else is `@RequestParam` on the controller method.

---

### 21.1 `customer/add.jsp` -> `POST /customer/add` (binds to `Customer` + `branchId` param)

| Field name | Element | Maps to (`Customer.<prop>` / param) | Notes |
|---|---|---|---|
| `customerType` | select | `customerType` | INDIVIDUAL / JOINT / HUF / PARTNERSHIP / COMPANY / TRUST / NRI / MINOR / GOVERNMENT |
| `branchId` | select / hidden | `@RequestParam Long branchId` | ADMIN sees select; others get hidden field with home branch |
| `kycRiskCategory` | select | `kycRiskCategory` | LOW / MEDIUM / HIGH |
| `pep` + `_pep` | checkbox + hidden | `pep` | Spring checkbox idiom |
| `firstName` | text | `firstName` | required, maxlength 100 |
| `lastName` | text | `lastName` | required, maxlength 100 |
| `gender` | select | `gender` | M / F / T |
| `dateOfBirth` | date | `dateOfBirth` | LocalDate |
| `maritalStatus` | select | `maritalStatus` | SINGLE / MARRIED / DIVORCED / WIDOWED / SEPARATED |
| `fatherName` | text | `fatherName` | required, CERSAI CKYC |
| `motherName` | text | `motherName` | required, CERSAI CKYC |
| `spouseName` | text | `spouseName` | optional |
| `nationality` | select | `nationality` | INDIAN / NRI / PIO / OCI / FOREIGN |
| `occupationCode` | select | `occupationCode` | 10 occupation codes |
| `annualIncomeBand` | select | `annualIncomeBand` | 6 bands (BELOW_1L .. ABOVE_1CR) |
| `cibilScore` | number | `cibilScore` | 300 - 900 |
| `panNumber` | text | `panNumber` | pattern `[A-Z]{5}[0-9]{4}[A-Z]`, immutable post-create |
| `aadhaarNumber` | text | `aadhaarNumber` | 12 digits, immutable post-create |
| `photoIdType` | select | `photoIdType` | PASSPORT / VOTER_ID / DRIVING_LICENSE / PAN_CARD / AADHAAR |
| `photoIdNumber` | text | `photoIdNumber` | maxlength 30 |
| `addressProofType` | select | `addressProofType` | PASSPORT / VOTER_ID / UTILITY_BILL / AADHAAR |
| `addressProofNumber` | text | `addressProofNumber` | maxlength 30 |
| `kycMode` | select | `kycMode` | IN_PERSON / VIDEO_KYC / DIGITAL_KYC / CKYC_DOWNLOAD |
| `mobileNumber` | text | `mobileNumber` | required, pattern `[6-9]\d{9}` |
| `email` | email | `email` | maxlength 200 |
| `address` | textarea | `address` | maxlength 500 |
| `city` | text | `city` | maxlength 100 |
| `state` | text | `state` | maxlength 100 |
| `pinCode` | text | `pinCode` | 6 digits |
| `addressSameAsPermanent` + `_addressSameAsPermanent` | checkbox + hidden | `addressSameAsPermanent` | toggles permanent block visibility |
| `permanentAddress` | textarea | `permanentAddress` | optional |
| `permanentCity` | text | `permanentCity` | optional |
| `permanentState` | text | `permanentState` | optional |
| `permanentPinCode` | text | `permanentPinCode` | 6 digits |
| `permanentCountry` | select | `permanentCountry` | INDIA / OTHER |
| `monthlyIncome` | number | `monthlyIncome` | BigDecimal |
| `maxBorrowingLimit` | number | `maxBorrowingLimit` | BigDecimal |
| `employmentType` | select | `employmentType` | SALARIED / SELF_EMPLOYED / BUSINESS / RETIRED / OTHER |
| `employerName` | text | `employerName` | -- |
| `nomineeDob` | date | `nomineeDob` | LocalDate |
| `nomineeGuardianName` | text | `nomineeGuardianName` | maxlength 200 |
| `nomineeAddress` | text | `nomineeAddress` | maxlength 500 |

> `customerNumber` is auto-generated server-side and is not a form field. `customer/edit.jsp` mirrors this list but disables the immutable triplet (`customerNumber`, `panNumber`, `aadhaarNumber`).

---

### 21.2 `deposit/open.jsp` -> `POST /deposit/open` (all params; no entity bind)

| Field name | Element | Maps to controller param | Notes |
|---|---|---|---|
| `customerId` | select | `@RequestParam Long customerId` | KYC-verified customers only |
| `branchId` | select | `@RequestParam Long branchId` | SOL |
| `accountType` | select | `@RequestParam String accountType` | SAVINGS / CURRENT / SAVINGS_PMJDY / SAVINGS_NRI / SAVINGS_MINOR / SAVINGS_JOINT / CURRENT_OD |
| `productCode` | select | `@RequestParam String productCode` | optional, defaults to accountType |
| `nomineeName` | text | `@RequestParam String nomineeName` | optional, maxlength 200 |
| `nomineeRelationship` | select | `@RequestParam String nomineeRelationship` | SPOUSE / CHILD / PARENT / SIBLING / OTHER |

> `interestRateDisplay`, `minBalDisplay` are read-only display-only inputs (no `name`, not submitted). Currency is hard-coded INR. The `OpenAccountRequest` DTO supports 25+ fields; the JSP captures only these 6 (`DepositController.java:241-254` fills the rest with `null`). See `DTO_PARITY_AUDIT_REPORT.md` Section 2.1.

---

### 21.3 `deposit/deposit.jsp` -> `POST /deposit/deposit/{accountNumber}`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `amount` | number | `@RequestParam BigDecimal amount` | min 0.01, step 0.01 |
| `narration` | text | `@RequestParam String narration` | maxlength 500 |

> No `idempotencyKey` field. Network retries can double-post. See finding #2.

---

### 21.4 `deposit/withdraw.jsp` -> `POST /deposit/withdraw/{accountNumber}`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `amount` | number | `@RequestParam BigDecimal amount` | min 0.01, step 0.01 |
| `narration` | text | `@RequestParam String narration` | maxlength 500 |

> Same idempotency-key gap as deposit.

---

### 21.5 `deposit/transfer.jsp` -> `POST /deposit/transfer`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `fromAccount` | select | `@RequestParam String fromAccount` | branch-scoped source list |
| `toAccount` | select | `@RequestParam String toAccount` | all-branch target list |
| `amount` | number | `@RequestParam BigDecimal amount` | min 0.01 |
| `narration` | text | `@RequestParam String narration` | optional |

---

### 21.6 `deposit/view.jsp` (action forms embedded in detail screen)

| Action -> URI | Field name | Element | Maps to |
|---|---|---|---|
| Maintain (`POST /deposit/maintain/{accNo}`) | `nomineeName` | text | `@RequestParam` |
| | `nomineeRelationship` | select | `@RequestParam` |
| | `jointHolderMode` | select | `@RequestParam` |
| | `chequeBookEnabled` | checkbox | `@RequestParam Boolean` |
| | `debitCardEnabled` | checkbox | `@RequestParam Boolean` |
| | `dailyWithdrawalLimit` | number | `@RequestParam BigDecimal` |
| | `dailyTransferLimit` | number | `@RequestParam BigDecimal` |
| | `odLimit` | number | `@RequestParam BigDecimal` |
| | `interestRate` | number | `@RequestParam BigDecimal` |
| | `minimumBalance` | number | `@RequestParam BigDecimal` |
| Freeze (`POST /deposit/freeze/{accNo}`) | `freezeType` | select | `@RequestParam`, default `TOTAL_FREEZE` |
| | `reason` | text | `@RequestParam`, mandatory |
| Close (`POST /deposit/close/{accNo}`) | `reason` | text | `@RequestParam`, default "Customer request" |
| Reversal (`POST /deposit/reversal/{txnRef}`) | `reason` | text | `@RequestParam`, mandatory |
| | `accountNumber` | hidden | `@RequestParam`, used for redirect |

---

### 21.7 `deposit/statement.jsp` -> `GET /deposit/statement/{accNo}`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `fromDate` | date | `@RequestParam String fromDate` | YYYY-MM-DD |
| `toDate` | date | `@RequestParam String toDate` | YYYY-MM-DD |

---

### 21.8 `loan/apply.jsp` -> `POST /loan/apply` (binds to `LoanApplication` + `customerId`/`branchId` params)

| Field name | Element | Maps to (`LoanApplication.<prop>` / param) | Notes |
|---|---|---|---|
| `customerId` | select | `@RequestParam Long customerId` | KYC-verified customers only |
| `branchId` | select | `@RequestParam Long branchId` | SOL |
| `productType` | select | `productType` | from `ProductMaster` catalogue |
| `requestedAmount` | number | `requestedAmount` | min 10000, max 50000000 |
| `interestRate` | number | `interestRate` | step 0.25, range from product |
| `tenureMonths` | number | `tenureMonths` | range from product |
| `penalRate` | number | `penalRate` | step 0.25, default from product |
| `riskCategory` | select | **NOT PERSISTED** | LOW / MEDIUM / HIGH / VERY_HIGH -- silently dropped (see `DTO_PARITY_AUDIT_REPORT.md` Section 2.6) |
| `collateralReference` | text | `collateralReference` | optional |
| `disbursementAccountNumber` | select | `disbursementAccountNumber` | borrower's CASA account |
| `purpose` | textarea | `purpose` | free-text |

---

### 21.9 `loan/verify.jsp` (collateral + document forms; CHECKER)

**Collateral registration -> `POST /loan/collateral/{applicationId}`**

| Field | Element | Maps to | Applies to |
|---|---|---|---|
| `collateralType` | select | `@RequestParam` | All types |
| `ownerName` | text | `@RequestParam` | All |
| `ownerRelationship` | select | `@RequestParam`, default `SELF` | All |
| `description` | textarea | `@RequestParam` | All |
| `marketValue` | number | `@RequestParam BigDecimal` | All |
| `goldPurity` | text | `@RequestParam` | GOLD only |
| `goldWeightGrams` | number | `@RequestParam BigDecimal` | GOLD |
| `goldNetWeightGrams` | number | `@RequestParam BigDecimal` | GOLD |
| `goldRatePerGram` | number | `@RequestParam BigDecimal` | GOLD |
| `propertyAddress` | textarea | `@RequestParam` | PROPERTY |
| `propertyType` | select | `@RequestParam` | PROPERTY |
| `propertyAreaSqft` | number | `@RequestParam BigDecimal` | PROPERTY |
| `registrationNumber` | text | `@RequestParam` | PROPERTY |
| `vehicleRegistration` | text | `@RequestParam` | VEHICLE |
| `vehicleMake` | text | `@RequestParam` | VEHICLE |
| `vehicleModel` | text | `@RequestParam` | VEHICLE |
| `fdNumber` | text | `@RequestParam` | FD lien |
| `fdBankName` | text | `@RequestParam` | FD lien |
| `fdAmount` | number | `@RequestParam BigDecimal` | FD lien |

**Document upload -> `POST /loan/document/{applicationId}`**

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `documentType` | select | `@RequestParam` | -- |
| `documentName` | text | `@RequestParam` | -- |
| `remarks` | text | `@RequestParam` | optional |
| `mandatory` | checkbox | `@RequestParam boolean`, default `false` | -- |

**Verify -> `POST /loan/document/verify/{documentId}`** | hidden `applicationId` param only.

**Reject -> `POST /loan/document/reject/{documentId}`** | hidden `applicationId`, `rejectionReason` text.

**Verify application -> `POST /loan/verify/{id}`** | `remarks` text, mandatory.

---

### 21.10 `loan/approve.jsp` -> `POST /loan/approve/{id}` / `POST /loan/reject/{id}`

| Action | Field | Element | Maps to |
|---|---|---|---|
| Approve | `remarks` | text | `@RequestParam` mandatory |
| Reject | `reason` | text | `@RequestParam` mandatory |

---

### 21.11 `loan/account-details.jsp` (action forms)

| Action -> URI | Field | Element | Maps to |
|---|---|---|---|
| Disburse (`POST /loan/disburse/{accNo}`) | -- | -- | path only |
| Disburse tranche (`POST /loan/disburse-tranche/{accNo}`) | `trancheAmount` | number | `@RequestParam BigDecimal` |
| | `narration` | text | `@RequestParam` |
| Repayment (`POST /loan/repayment/{accNo}`) | `amount` | number | `@RequestParam BigDecimal` |
| Prepayment (`POST /loan/prepayment/{accNo}`) | `amount` | number | `@RequestParam BigDecimal` |
| Write-off (`POST /loan/write-off/{accNo}`) | -- | -- | path only |
| Fee (`POST /loan/fee/{accNo}`) | `feeAmount` | number | `@RequestParam BigDecimal` |
| | `feeType` | select | `@RequestParam String` |
| Reversal (`POST /loan/reversal/{txnRef}`) | `reason` | text | `@RequestParam` |
| | `accountNumber` | hidden | `@RequestParam` (redirect) |
| Restructure (`POST /loan/restructure/{accNo}`) | `newRate` | number | `@RequestParam BigDecimal` (optional) |
| | `additionalMonths` | number | `@RequestParam int`, default 0 |
| | `reason` | text | `@RequestParam` mandatory |
| Moratorium (`POST /loan/moratorium/{accNo}`) | `moratoriumMonths` | number | `@RequestParam int` |
| | `reason` | text | `@RequestParam` |

---

### 21.12 `loan/si-dashboard.jsp` and embedded SI forms (`/loan/si/*`)

**Register SI -> `POST /loan/si/register`** (form on `deposit/view.jsp` SI panel)

| Field | Element | Maps to |
|---|---|---|
| `customerId` | hidden | `@RequestParam Long` |
| `sourceAccountNumber` | hidden | `@RequestParam String` |
| `destinationType` | select | `@RequestParam String` |
| `destinationAccountNumber` | text | `@RequestParam String` |
| `amount` | number | `@RequestParam BigDecimal` |
| `frequency` | select | `@RequestParam String` (SIFrequency enum) |
| `executionDay` | number | `@RequestParam int` |
| `startDate` | date | `@RequestParam String` |
| `endDate` | date | `@RequestParam String` (optional) |
| `narration` | text | `@RequestParam String` |

**Approve / Reject / Pause / Resume / Cancel / Amend** -- path `siReference` plus:
- Reject: `reason` text
- Amend: `accountNumber` hidden, `newAmount` number, `newFrequency` select, `newExecutionDay` number
- Pause/Resume/Cancel: `accountNumber` hidden (redirect)

---

### 21.13 `branch/add.jsp` / `branch/edit.jsp` -> `POST /branch/add` | `POST /branch/edit/{id}` (binds to `Branch`)

| Field name | Element | Maps to (`Branch.<prop>`) | Notes |
|---|---|---|---|
| `branchCode` | text | `branchCode` | unique per tenant |
| `branchName` | text | `branchName` | -- |
| `branchType` | select | `branchType` | from `BranchType` enum |
| `parentBranchId` | select | `parent.id` | optional |
| `ifscCode` | text | `ifscCode` | RBI IFSC format |
| `address`, `city`, `state`, `pinCode`, `zone`, `region` | text | corresponding entity props | -- |
| `contactPhone`, `contactEmail` | text/email | `contactPhone`, `contactEmail` | -- |
| `active` | checkbox | `active` | edit form only |

> Exact field set is whatever Spring `@ModelAttribute Branch` finds bindable on the JSP form. The `branch/view.jsp` page does not POST a form; it only renders.

---

### 21.14 `admin/product-create.jsp` / `admin/product-edit.jsp` -> binds to `ProductMaster`

Form fields submitted (all map to `ProductMaster.<prop>` via `@ModelAttribute`):

| Field | Element | Notes |
|---|---|---|
| `productCode` | text | unique; immutable on edit |
| `productName` | text | -- |
| `productCategory` | select | enum (`SAVINGS`, `CURRENT`, `LOAN_*`, etc.) |
| `productStatus` / `lifecycleStatus` | select | ACTIVE / SUSPENDED / RETIRED |
| `currencyCode` | select | INR default |
| `interestType` | select | FIXED / FLOATING |
| `minInterestRate`, `maxInterestRate`, `defaultPenalRate` | number | BigDecimal |
| `minLoanAmount`, `maxLoanAmount` | number | BigDecimal |
| `minTenureMonths`, `maxTenureMonths` | number | int |
| `glLoanAsset`, `glInterestIncome`, `glInterestAccrued`, `glPenalIncome`, `glProcessingFee`, `glChargeIncome`, etc. | select (GL dropdown) | references `GLMaster` |
| `prepaymentPenaltyApplicable` | checkbox | boolean |
| Description / regulatory text fields | textarea | -- |

> The product form is the longest entity-bound form in admin. Per `AdminController.updateProduct`, immutable fields (`productCode`, `productCategory`) are restored from the existing record on validation failure.

---

### 21.15 `admin/limits.jsp` (create + edit)

**Create -> `POST /admin/limits/create`**

| Field | Element | Maps to |
|---|---|---|
| `role` | select | `@RequestParam String role` |
| `transactionType` | select | `@RequestParam String transactionType` |
| `perTransactionLimit` | number | `@RequestParam BigDecimal` |
| `dailyAggregateLimit` | number | `@RequestParam BigDecimal` |
| `description` | text | `@RequestParam String` |

**Edit -> `POST /admin/limits/{id}/edit`** | `perTransactionLimit`, `dailyAggregateLimit`, `description` only.

**Toggle active -> `POST /admin/limits/{id}/toggle-active`** | path only.

---

### 21.16 `admin/charges.jsp` (create + edit)

**Create -> `POST /admin/charges/create`**

| Field | Element | Maps to |
|---|---|---|
| `chargeCode` | text | `@RequestParam String` (uppercase, regex `[A-Z0-9_]{2,50}`) |
| `chargeName` | text | `@RequestParam String` |
| `chargeCategory` | select | `@RequestParam String` |
| `eventTrigger` | select | `@RequestParam String` (e.g., `ACCOUNT_OPEN`, `TXN_AMT`, `MONTHLY_AMC`) |
| `calculationType` | select | `@RequestParam String` (`FLAT` / `PERCENTAGE` / `SLAB`) |
| `frequency` | select | `@RequestParam String` |
| `baseAmount` | number | `@RequestParam BigDecimal` (FLAT) |
| `percentage` | number | `@RequestParam BigDecimal` (PERCENTAGE) |
| `slabJson` | textarea | `@RequestParam String` (SLAB) |
| `minAmount`, `maxAmount` | number | `@RequestParam BigDecimal` |
| `currencyCode` | select | `@RequestParam String`, default `INR` |
| `gstApplicable` | checkbox | `@RequestParam boolean` |
| `gstRate` | number | `@RequestParam BigDecimal` (when GST applicable) |
| `glChargeIncome` | select | `@RequestParam String` (GL master) |
| `glGstPayable` | select | `@RequestParam String` (when GST applicable) |
| `waiverAllowed` | checkbox | `@RequestParam boolean` |
| `maxWaiverPercent` | number | `@RequestParam BigDecimal` |
| `productCode` | select | `@RequestParam String` (optional product scope) |
| `channel` | select | `@RequestParam String` (e.g., `BRANCH`, `ATM`, `INTERNET`) |
| `validFrom`, `validTo` | date | `@RequestParam String` (parsed to `LocalDate`) |
| `customerDescription` | text | `@RequestParam String` |

**Edit -> `POST /admin/charges/{id}/edit`** | same fields minus `chargeCode`. **Toggle -> `POST /admin/charges/{id}/toggle-active`** | path only.

---

### 21.17 `admin/users.jsp` -> `UserController` action forms

| Action -> URI | Field | Element | Maps to |
|---|---|---|---|
| Create (`POST /admin/users/create`) | `username` | text | `@RequestParam` |
| | `password` | password | `@RequestParam` |
| | `fullName` | text | `@RequestParam` |
| | `email` | email | `@RequestParam` (optional) |
| | `role` | select | `@RequestParam` (`UserRole` enum) |
| | `branchId` | select | `@RequestParam Long` |
| Toggle active (`POST /admin/users/toggle-active/{id}`) | -- | -- | path only |
| Unlock (`POST /admin/users/unlock/{id}`) | -- | -- | path only |
| Reset password (`POST /admin/users/reset-password/{id}`) | `newPassword` | password | `@RequestParam` |

---

### 21.18 `admin/mfa.jsp` / `admin/mfa-enroll.jsp` -> `AdminController` MFA endpoints

| Action -> URI | Field | Element | Maps to |
|---|---|---|---|
| Enable (`POST /admin/mfa/enable`) | `username` | hidden | `@RequestParam` |
| Enroll (`POST /admin/mfa/enroll`) | `username` | hidden | `@RequestParam` |
| Verify (`POST /admin/mfa/verify`) | `username` | hidden | `@RequestParam` |
| | `totpCode` | text (6-digit) | `@RequestParam` |
| Disable (`POST /admin/mfa/disable`) | `username` | hidden | `@RequestParam` |
| | `reason` | text | `@RequestParam` mandatory |

---

### 21.19 `admin/ib-settlement.jsp` -> `POST /admin/ib-settlement/manual-settle`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `reason` | text | `@RequestParam String` | mandatory, audit trail |
| `hoAuthorizationRef` | text | `@RequestParam String` | mandatory, HO auth number |

---

### 21.20 `calendar/list.jsp` -> `CalendarController` action forms

| Action -> URI | Field | Element | Maps to |
|---|---|---|---|
| Day open (`POST /calendar/day-open`) | `businessDate` | date | `@RequestParam String` |
| | `branchId` | hidden / select | `@RequestParam Long` |
| Day close (`POST /calendar/day-close`) | `businessDate` | date | `@RequestParam String` |
| | `branchId` | hidden / select | `@RequestParam Long` |
| Generate (`POST /calendar/generate`) | `year` | number | `@RequestParam int` |
| | `month` | select | `@RequestParam int` |
| Add holiday (`POST /calendar/add-holiday`) | `date` | date | `@RequestParam String` |
| | `description` | text | `@RequestParam String` |
| Remove holiday (`POST /calendar/remove-holiday`) | `date` | date | `@RequestParam String` |

---

### 21.21 `batch/eod.jsp` -> `BatchController`

| Action -> URI | Field | Element | Maps to |
|---|---|---|---|
| Trial (`POST /batch/eod/trial`) | `businessDate` | date | `@RequestParam String` |
| Apply (`POST /batch/eod/apply`) | `businessDate` | date / hidden | `@RequestParam String` |

### `batch/txn-batches.jsp` -> `TransactionBatchController`

| Action -> URI | Field | Element | Maps to |
|---|---|---|---|
| Open (`POST /batch/txn/open`) | `businessDate` | hidden | `@RequestParam String` |
| | `batchName` | text | `@RequestParam String` |
| | `batchType` | select | `@RequestParam String` |
| | `branchId` | select | `@RequestParam Long` (optional) |
| Close (`POST /batch/txn/close/{id}`) | `businessDate` | hidden | `@RequestParam String` |

---

### 21.22 `workflow/pending.jsp` -> `WorkflowController`

| Action -> URI | Field | Element | Maps to | Notes |
|---|---|---|---|---|
| Approve (`POST /workflow/approve/{id}`) | `remarks` | hidden | `@RequestParam String` | hard-coded value `"Approved"` in JSP |
| Reject (`POST /workflow/reject/{id}`) | `remarks` | hidden | `@RequestParam String` | populated by a JS prompt before submit (JSP class `fv-reason-field`) |

> No `version` field is captured on the JSP -- optimistic locking is not enforced at the controller layer here. React sends `version` but the backend DTO does not bind it either. See finding #4 and `DTO_PARITY_AUDIT_REPORT.md` Section 2.7.

---

### 21.23 `password/change.jsp` -> `POST /password/change`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `currentPassword` | password | `@RequestParam String` | required, `autocomplete="current-password"` |
| `newPassword` | password | `@RequestParam String` | required, `minlength=8` |
| `confirmPassword` | password | `@RequestParam String` | required, client-side match check |

> On success, session is invalidated; the login page picks up `?password_changed` query param (`PasswordController.java:83-89`).

---

### 21.24 `mfa/verify.jsp` -> `POST /mfa/verify`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `totpCode` | text (6-digit, `pattern=\d{6}`) | `@RequestParam String` | required; controller also enforces `matches("\\d{6}")` |

> Username comes from `SecurityContext` -- not a form field. Session attributes `MFA_FAILED_ATTEMPTS`, `MFA_VERIFIED_ATTR` are tracked server-side. `MAX_MFA_ATTEMPTS = 5` triggers session invalidation and redirect to `/login?mfa_locked`.

---

### 21.25 `audit/logs.jsp` -> `AuditController` (read-only filters, GET only)

| Endpoint | Query param | Element | Maps to |
|---|---|---|---|
| `GET /audit/search` | `q` | text | `@RequestParam String` |
| | `fromDate` | date | `@RequestParam String` |
| | `toDate` | date | `@RequestParam String` |
| `GET /audit/entity` | `entityType` | select | `@RequestParam String` (whitelisted) |
| | `entityId` | number | `@RequestParam Long` |

---

### 21.26 `accounting/*.jsp` -> `AccountingController` (GET filters only)

| Screen | Endpoint | Query param | Element | Maps to |
|---|---|---|---|---|
| `trial-balance.jsp` | `GET /accounting/gl/search` | `q` | text | `@RequestParam String` |
| `journal-entries.jsp` | `GET /accounting/journal-entries` | `fromDate`, `toDate` | date | `@RequestParam String` |
| `journal-entries.jsp` (search) | `GET /accounting/journal-entries/search` | `q`, `fromDate`, `toDate` | text + date | `@RequestParam String` |
| `voucher-register.jsp` | `GET /accounting/voucher-register` | `businessDate` | date | `@RequestParam String` |

---

### 21.27 `reports/*.jsp` -> `ReportController` (GET only; no POST forms)

| Screen | Endpoint | Query params |
|---|---|---|
| `reports/dpd.jsp` | `GET /reports/dpd` | none |
| `reports/irac.jsp` | `GET /reports/irac` | none |
| `reports/provision.jsp` | `GET /reports/provision` | none |
| `reports/udgam.jsp` | `GET /reports/udgam` | none |
| UDGAM CSV | `GET /reports/udgam/export` | none |

---

### 21.28 `txn360/search.jsp` -> `GET /txn360/search`

| Field | Element | Maps to | Notes |
|---|---|---|---|
| `q` | text | `@RequestParam String q` | Resolver dispatches by prefix (VCH/TXN/JRN) |

---

### 21.29 Summary of screen-level @ModelAttribute entity bindings

| JSP form | POST URL | `@ModelAttribute` target entity |
|---|---|---|
| `customer/add.jsp` | `/customer/add` | `Customer` |
| `customer/edit.jsp` | `/customer/edit/{id}` | `Customer` |
| `loan/apply.jsp` | `/loan/apply` | `LoanApplication` |
| `branch/add.jsp` | `/branch/add` | `Branch` |
| `branch/edit.jsp` | `/branch/edit/{id}` | `Branch` |
| `admin/product-create.jsp` | `/admin/products/create` | `ProductMaster` |
| `admin/product-edit.jsp` | `/admin/products/{id}/edit` | `ProductMaster` |

Every other form on every other screen uses explicit `@RequestParam` binding -- no JPA entities crossing the request boundary. This split is the root cause of architecture finding **C3** (entity exposure at API boundary) -- see `CBS_TIER1_AUDIT_REPORT.md`.

---

*End of Section 21. All screen-level attribute tables are compiled from `src/main/webapp/WEB-INF/views/**/*.jsp` at merge-base commit `d509531b70188180fbfe3625dccf10ddc811d607`.*

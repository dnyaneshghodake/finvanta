# Tier-1 Grade CBS: Flow Diagrams & Process Flows

## Table of Contents
1. [Customer Onboarding Flow](#customer-onboarding-flow)
2. [Account Opening Flow](#account-opening-flow)
3. [Funds Transfer Flow](#funds-transfer-flow)
4. [Deposit Application Flow](#deposit-application-flow)
5. [Loan Origination Flow](#loan-origination-flow)
6. [Loan Disbursement Flow](#loan-disbursement-flow)
7. [Payment Processing Flow](#payment-processing-flow)
8. [Interest Calculation Flow](#interest-calculation-flow)
9. [Day-End Processing Flow](#day-end-processing-flow)
10. [GL Posting & Reconciliation Flow](#gl-posting--reconciliation-flow)
11. [User Login & Auth Flow](#user-login--auth-flow)
12. [Data Access Layer Flow](#data-access-layer-flow)

---

## Customer Onboarding Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CUSTOMER ONBOARDING PROCESS                          │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────────┐
    │   START:     │
    │ Customer Web │
    │    Portal    │
    └──────┬───────┘
           │
           ▼
    ┌────────────────────┐
    │ Fill Registration  │
    │ Form:              │
    │ - Name             │
    │ - Email            │
    │ - Phone            │
    │ - Address          │
    │ - DOB              │
    └────────┬───────────┘
             │
             ▼
    ┌─────────────────────────────┐
    │ Validate Input Data         │
    │ ✓ Format Check              │
    │ ✓ Email Uniqueness          │
    │ ✓ Phone Uniqueness          │
    │ ✓ Age ≥ 18 years            │
    └────────┬─────────────────────┘
             │
             ├─── FAIL ──► Show Errors ← Retry
             │
             ▼ PASS
    ┌──────────────────────────┐
    │ Create Customer Record   │
    │ - Assign Customer ID     │
    │ - Set Status: PENDING    │
    │ - Set KYC: PENDING       │
    └────────┬─────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Generate KYC Documents:    │
    │ - Identity Proof Request   │
    │ - Address Proof Request    │
    │ - Income Proof Request     │
    │ - Verification URL Token   │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Send Verification Email  │
    │ with:                    │
    │ - Customer ID            │
    │ - OTP (6 digits)         │
    │ - Document Upload Link   │
    │ - Expiry: 48 hours       │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Customer Clicks Email    │
    │ Link & Verifies OTP      │
    └────────┬──────────────────┘
             │
             ├─── OTP Expired ──► Resend OTP ← Retry
             │
             ▼ OTP Valid
    ┌──────────────────────────┐
    │ Upload KYC Documents     │
    │ - Identity Proof         │
    │ - Address Proof          │
    │ - Additional Docs        │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Trigger KYC Verification │
    │ Service (Manual/AutoML)  │
    │ - Document Validation    │
    │ - Liveness Check         │
    │ - Risk Scoring           │
    └────────┬──────────────────┘
             │
             ├─── REJECTED ──► Show Error ← Retry
             │
             ▼ APPROVED
    ┌──────────────────────────┐
    │ Update Customer Status:  │
    │ - Status: ACTIVE         │
    │ - KYC: VERIFIED          │
    │ - Risk Score: Assigned   │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Publish Event:           │
    │ CustomerOnboardedEvent   │
    │ (for notifications)      │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Send Welcome Email       │
    │ - Account Credentials    │
    │ - Getting Started Guide  │
    │ - Available Products     │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │   END: Customer Active   │
    │   Ready for Banking      │
    └──────────────────────────┘
```

---

## Account Opening Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      ACCOUNT OPENING PROCESS                            │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────────────┐
    │     START:       │
    │ Customer applies │
    │ for Account      │
    └────────┬─────────┘
             │
             ▼
    ┌─────────────────────────┐
    │ Select Account Type:    │
    │ - Savings Account       │
    │ - Current Account       │
    │ - Salary Account        │
    │ - NRI Account           │
    └────────┬────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ Validate Account Eligibility:   │
    │ ✓ Customer Status: ACTIVE       │
    │ ✓ KYC: VERIFIED                 │
    │ ✓ Risk Score: Acceptable        │
    │ ✓ Max Accounts: Not Exceeded     │
    │ ✓ Country Restrictions          │
    └────────┬─────────────────────────┘
             │
             ├─── FAIL ──► Show Error ← Retry
             │
             ▼ PASS
    ┌──────────────────────────┐
    │ Generate Account Details:│
    │ - Assign Account Number  │
    │ - Assign IBAN            │
    │ - Assign Routing Number  │
    │ - Set Account Status:    │
    │   OPEN_PENDING           │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Create GL Accounts       │
    │ (Double Entry):          │
    │ - Asset (Customer Name)  │
    │ - Liability (GL Head)    │
    │ - Suspense (if needed)   │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Set Account Limits:      │
    │ - Minimum Balance        │
    │ - Daily Transfer Limit   │
    │ - Monthly Transaction*   │
    │ - Overdraft Limit (if OK)│
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Set Account Rules:       │
    │ - Dormancy Rule (1 year) │
    │ - Suspension Rule        │
    │ - Closure Rule           │
    │ - Interest Accrual Rule  │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Update Account Status:   │
    │ Status: ACTIVE           │
    │ Balance: 0.00            │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Create Initial GL Entry: │
    │ DR: Account GL           │
    │ CR: Suspense GL          │
    │ Amt: 0.00                │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Publish Event:           │
    │ AccountOpenedEvent       │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Send Account Details via:│
    │ - Email                  │
    │ - SMS                    │
    │ - Customer Portal        │
    │ - Cheque Book Request(opt)
    └────────┬──────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ END: Account Ready for Use  │
    └────────────────────────────┘
```

---

## Funds Transfer Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      FUNDS TRANSFER PROCESS                             │
│                   (Intra-Bank Transfer)                                 │
└─────────────────────────────────────────────────────────────────────────┘

    ┌────────────────────┐
    │    START:          │
    │ Customer initiates │
    │ transfer via app   │
    └────────┬───────────┘
             │
             ▼
    ┌─────────────────────────────┐
    │ Input Transfer Details:     │
    │ - From Account Number       │
    │ - To Account Number         │
    │ - Amount                    │
    │ - Narration/Purpose         │
    │ - Transaction Reference     │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────────────┐
    │ Validation Step 1:               │
    │ ✓ Source Account Exists          │
    │ ✓ Source Account Active          │
    │ ✓ Destination Account Exists     │
    │ ✓ Destination Account Active     │
    │ ✓ Amount > 0 & <= Max Limit      │
    │ ✓ Customer Ownership Check       │
    └────────┬──────────────────────────┘
             │
             ├─── FAIL ──► Log Error & Reject
             │
             ▼ PASS
    ┌────────────────────────────────┐
    │ Validation Step 2:             │
    │ ✓ Source Account Balance >= Amt│
    │ ✓ Daily Limit Not Exceeded     │
    │ ✓ Monthly Limit Not Exceeded   │
    │ ✓ Beneficiary Verified         │
    │ ✓ No Account Restrictions      │
    │ ✓ Risk Check Passed            │
    └────────┬─────────────────────────┘
             │
             ├─── FAIL ──► Reject & Notify
             │
             ▼ PASS
    ┌─────────────────────────┐
    │ Lock Both Accounts      │
    │ (mutex/distributed lock)│
    ├─────────────────────────┤
    │ Timeout: 30 seconds     │
    │ (prevent other requests)│
    └────────┬────────────────┘
             │
             ▼
    ┌──────────────────────────────────┐
    │ Create Transaction Record:       │
    │ - Transaction ID: Unique         │
    │ - Status: PENDING                │
    │ - From Account, To Account       │
    │ - Amount, Currency               │
    │ - Timestamp, Channel             │
    │ - Initiator Info                 │
    └────────┬──────────────────────────┘
             │
    ┌────────┴──────────────┬────────────────┐
    │                       │                │
    ▼                       ▼                ▼
    │                │                │
    └─ Debit ─┘ ├─ Update GL ─┤ ├─ Credit ─┘
    │                │                │
    │ Step 1         │   Step 2       │   Step 3
    │ ──────────────┼─────────────────┼──────────
    │                │                │
    │ Debit from     │ Post GL entries │ Credit to
    │ source account │ with audit trail│ dest account
    │                │                │
    └───────────────┬─────────────────┘
                    │
                    ▼
    ┌──────────────────────────────┐
    │ GL Double-Entry Posting:     │
    │ DR: To Account GL            │
    │ CR: From Account GL          │
    │ Amount: Transaction Amt      │
    │ Status: SUCCESS              │
    │ Timestamp: System Time       │
    │ Audit Trail: Entry           │
    └────────┬─────────────────────┘
             │
             ▼
    ┌─────────────────────────────┐
    │ Update Transaction Status:  │
    │ Status: SUCCESS             │
    │ CR: 0.00 (settled)          │
    │ Completion Time: Now        │
    └────────┬─────────────────────┘
             │
             ▼
    ┌─────────────────────────────┐
    │ Unlock Both Accounts        │
    │ (Release mutex/lock)        │
    └────────┬─────────────────────┘
             │
             ▼
    ┌─────────────────────────────┐
    │ Publish Events:             │
    │ - MoneyDebited Event        │
    │ - MoneyCredited Event       │
    │ - TransactionCompleted      │
    │ - AuditLog Entry            │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Send Notifications:      │
    │ - Debit Notification     │
    │ - Credit Notification    │
    │ (SMS, Email, In-App)     │
    └────────┬──────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Return Response to UI:   │
    │ - Transaction ID         │
    │ - New Balance (both)     │
    │ - Status: SUCCESS        │
    │ - Timestamp              │
    └────────┬──────────────────┘
             │
             ▼
    ┌─────────────────────┐
    │ END: Transfer Done  │
    └─────────────────────┘

ERROR HANDLING:
├── If lock acquisition fails: Queue for retry
├── If GL posting fails: Reverse transaction
├── If debit fails: Transaction FAILED, notify
├── If credit fails: Reverse transaction ROLLBACK
└── All errors logged with full audit trail
```

---

## Deposit Application Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    FIXED DEPOSIT APPLICATION FLOW                       │
└─────────────────────────────────────────────────────────────────────────┘

    ┌───────────────────┐
    │     START:        │
    │ Customer applies  │
    │ for Fixed Deposit │
    └────────┬──────────┘
             │
             ▼
    ┌───────────────────────────┐
    │ Input FD Details:         │
    │ - Amount                  │
    │ - Tenure (months/years)   │
    │ - Interest Rate           │
    │ - Payout Option           │
    │ - Maturity Amount (auto)  │
    └────────┬─────────────────┘
             │
             ▼
    ┌──────────────────────────────────┐
    │ Validate FD Request:             │
    │ ✓ Amount >= Min FD (e.g., $100)  │
    │ ✓ Amount <= Max FD (e.g., $1M)   │
    │ ✓ Tenure >= 1 Month              │
    │ ✓ Tenure <= 10 Years             │
    │ ✓ Rate Applicable                │
    │ ✓ Source Account Valid           │
    │ ✓ Account Balance >= Amount      │
    │ ✓ No Restrictions                │
    └────────┬──────────────────────────┘
             │
             ├─── FAIL ──► Show Error & Retry
             │
             ▼ PASS
    ┌────────────────────────────┐
    │ Calculate Interest Details:│
    │ - Principal Amount         │
    │ - Tax (if applicable)      │
    │ - Net Interest             │
    │ - Maturity Amount          │
    │ - Payout Amount            │
    │ - Compound Frequency       │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Review & Approve by Customer:  │
    │ - Show Full Breakdown          │
    │ - Customer Confirms Details    │
    │ - Enter Secure PIN/Biometric   │
    │ - Accept T&Cs                  │
    └────────┬─────────────────────────┘
             │
             ├─── REJECTED ──► Cancel Application
             │
             ▼ APPROVED
    ┌───────────────────────────────┐
    │ Create FD Record:             │
    │ - FD Number: Unique           │
    │ - Status: APPLICATION         │
    │ - Amount, Rate, Tenure        │
    │ - Start Date, Maturity Date   │
    │ - Interest Schedule           │
    │ - Created Timestamp           │
    └────────┬─────────────────────┘
             │
             ▼
    ┌───────────────────────────────┐
    │ Debit Amount from Account:    │
    │ - Debit: Principal Amount     │
    │ - Credit: FD GL Head          │
    │ - Narration: FD/[FD Number]   │
    └────────┬─────────────────────┘
             │
             ├─── FAIL ──► Rollback & Reject
             │
             ▼ PASS
    ┌───────────────────────────────┐
    │ Update FD Status:             │
    │ Status: ACTIVE                │
    │ FD GL Account Created         │
    │ Interest GL Mapping Done      │
    └────────┬─────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Create Interest Accrual        │
    │ Schedule:                      │
    │ - Monthly intervals            │
    │ - Quarterly intervals (if FD)  │
    │ - Daily calculation (backend)  │
    │ - Payment on Maturity          │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Generate FD Certificate:       │
    │ - Certificate Number           │
    │ - Principal Amount             │
    │ - Rate of Interest             │
    │ - Tenure, Maturity Date        │
    │ - Expected Payout              │
    │ - Terms & Conditions           │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Publish Events:                │
    │ - FDCreatedEvent               │
    │ - AuditLog                     │
    │ - DebitNotification            │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Send Confirmations:            │
    │ - Email: FD Certificate        │
    │ - SMS: Confirmation Alert      │
    │ - Portal: View FD Details      │
    │ - Debit Notification           │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌──────────────────────┐
    │ END: FD Active       │
    │ Auto-Renewal Config? │
    └──────────────────────┘

MATURITY PROCESSING FLOW:
    ┌──────────────────────────────┐
    │ On Maturity Date (Scheduler):│
    │ 1. Calculate Final Amount    │
    │ 2. Apply Tax if needed       │
    │ 3. Credit Account            │
    │ 4. Post GL Entries           │
    │ 5. Update FD: MATURED        │
    │ 6. Auto-Renew (if enabled)   │
    │ 7. Send Notification         │
    └──────────────────────────────┘
```

---

## Loan Origination Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    LOAN ORIGINATION PROCESS                             │
└─────────────────────────────────────────────────────────────────────────┘

    ┌────────────────────┐
    │     START:         │
    │ Customer applies   │
    │ for Loan           │
    └────────┬───────────┘
             │
             ▼
    ┌─────────────────────────┐
    │ Input Loan Details:     │
    │ - Loan Title            │
    │ - Loan Type             │
    │ - Loan Amount           │
    │ - Requested Tenure      │
    │ - Purpose               │
    │ - Collateral (if any)   │
    └────────┬────────────────┘
             │
             ▼
    ┌──────────────────────────────────┐
    │ Basic Validation:                │
    │ ✓ Amount > Min Loan              │
    │ ✓ Amount <= Max Loan             │
    │ ✓ Tenure Acceptable              │
    │ ✓ Customer KYC: VERIFIED         │
    │ ✓ Age >= 21, <= 60               │
    │ ✓ Employment Status: Valid       │
    └────────┬──────────────────────────┘
             │
             ├─── FAIL ──► Reject Application
             │
             ▼ PASS
    ┌────────────────────────────┐
    │ Credit Scoring:            │
    │ - Credit Score (CIBIL)     │
    │ - Income Verification      │
    │ - Debt-to-Income Ratio     │
    │ - Employment History       │
    │ - Existing Loan Details    │
    │ - Default History          │
    │ Generate Credit Rating     │
    └────────┬────────────────────┘
             │
             ├─── LOW SCORE ──► Reject or Mandate
             │               Security/Higher Rate
             │
             ▼ ACCEPTABLE SCORE
    ┌────────────────────────────┐
    │ Risk Assessment:           │
    │ - Collateral Valuation     │
    │ - Industry Risk            │
    │ - Geographic Risk          │
    │ - Currency Risk (if FX)    │
    │ - Mark Risk Category       │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Calculate Loan Details:    │
    │ - Eligible Amount          │
    │ - Interest Rate            │
    │ - EMI Amount               │
    │ - Processing Fee           │
    │ - Insurance Premium        │
    │ - Total Amount Due         │
    │ - Amortization Schedule    │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Generate Loan Offer:       │
    │ - Offer Letter             │
    │ - Terms & Conditions       │
    │ - Rate Card Conditions     │
    │ - Security Details         │
    │ - Repayment Schedule       │
    │ - Validity Period (14 days)│
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Customer Accepts Offer:    │
    │ - Digital Signature        │
    │ - E-Sign (if required)     │
    │ - Acceptance Processed     │
    │ - Status: APPROVAL         │
    └────────┬────────────────────┘
             │
             ├─── REJECTED ──► End Application
             │
             ▼ ACCEPTED
    ┌─────────────────────────────┐
    │ Create Loan Account:        │
    │ - Loan Account Number       │
    │ - Principal Amount          │
    │ - Disbursement Schedule     │
    │ - EMI Due Date              │
    │ - Repayment Start Date      │
    │ - Status: ACTIVE            │
    └────────┬──────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Create GL Accounts:        │
    │ - Asset A/C: Loan          │
    │ - Income A/C: Interest     │
    │ - Service Charge A/C       │
    │ - Suspense A/C             │
    └────────┬────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Update Loan Status:          │
    │ Status: SANCTIONED           │
    │ Create First EMI Record:     │
    │ - EMI Due Date               │
    │ - EMI Amount                 │
    │ - Status: PENDING            │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Publish Events:          │
    │ - LoanSanctioned         │
    │ - AuditLog               │
    └────────┬──────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Send Notification:             │
    │ - Loan Approval Notification   │
    │ - Offer Letter (Email/Portal)  │
    │ - Repayment Schedule           │
    │ - First EMI Due Date           │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ END: Loan Sanctioned       │
    │ Ready for Disbursement     │
    └────────────────────────────┘
```

---

## Interest Calculation Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    DAILY INTEREST CALCULATION PROCESS                   │
│                     (Savings Account Scenario)                          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐
│   Scheduled     │
│ Batch Job Daily │
│ (@ 23:55 PM)    │
└────────┬────────┘
         │
         ▼
┌──────────────────────────────┐
│ Fetch All Active Accounts    │
│ with Savings Product:        │
│ - Account Type: SAVINGS      │
│ - Status: ACTIVE             │
│ - Balance > Minimum Balance  │
│ - No Restrictions            │
└────────┬─────────────────────┘
         │
         ▼
┌────────────────────────────────────┐
│ For Each Account:                  │
│                                    │
│ 1. Get Opening Balance (@ 00:00)   │
│ 2. Get Closing Balance (@ 23:55)   │
│ 3. Calculate Daily Interest        │
│ 4. Create Accrual Entry            │
│ 5. Move to Next Account            │
└────────┬─────────────────────────────┘
         │
         ▼
┌──────────────────────────────────┐
│ Interest Calculation Logic:      │
│                                  │
│ Interest = Principal × Rate/365  │
│           × Number of Days       │
│                                  │
│ Example:                         │
│ Principal: $10,000               │
│ Rate: 3% p.a.                    │
│ Days: 1                          │
│ Interest: $10,000 × 0.03/365     │
│         = $0.82                  │
└────────┬─────────────────────────┘
         │
         ▼
┌────────────────────────────────────┐
│ Create Journal Entry:              │
│ Date: Today                        │
│ Debit: Interest Receivable A/C     │
│ Credit: Interest Income A/C        │
│ Amount: Calculated Interest        │
│ Narration: DLY-INT-[Acct]-[Date]   │
│ Status: POSTED                     │
│ Timestamp: Execution Time          │
└────────┬─────────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│ Store Accrual Record:           │
│ - Account ID                    │
│ - Calculation Date              │
│ - Principal Used                │
│ - Rate Applied                  │
│ - Days Counted                  │
│ - Interest Calculated           │
│ - GL Entry Reference            │
│ - Status: ACCRUED               │
└────────┬──────────────────────┘
         │
         ▼
┌────────────────────────────┐
│ Update Account Ledger:     │
│ - Accrued Interest Flag    │
│ - Last Interest Date       │
│ - Total Accrued Interest   │
└────────┬──────────────────┘
         │
         ▼
┌────────────────────────────┐
│ Move to Next Account       │
│ (Process until all done)   │
└────────┬──────────────────┘
         │
         ▼
    ┌─────────── MONTHLY ────────────┐
    │  (or Quarterly/Annually)       │
    │   INTEREST CREDIT PROCESS      │
    └────────┬──────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Fetch All Accounts with    │
    │ Due Interest Accrual:      │
    │ - Total Accrued > 0        │
    │ - Current Date = Payout    │
    │ - Month End                │
    └────────┬───────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Calculate Tax (TDS) if       │
    │ applicable:                  │
    │                              │
    │ Gross Interest: $100         │
    │ TDS Rate: 10% (example)      │
    │ TDS Amount: $10              │
    │ Net Interest: $90            │
    └────────┬─────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Create Credit Entry:           │
    │ Date: Payout Date              │
    │ Debit: Interest Income A/C     │
    │ Credit: Account Balance GL     │
    │ Amount: Net Interest           │
    │ Narration: INT-CREDIT          │
    │ Status: POSTED                 │
    │ Tax Deducted Entry (TDS A/C)   │
    │ Gov Tax Payable A/C            │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Update Account:                │
    │ - Balance: + Net Interest      │
    │ - Accrued Interest: 0          │
    │ - Last Interest Credit Date    │
    │ - Interest Credited Timestamp  │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Publish Events:                │
    │ - InterestAccruedEvent         │
    │ - InterestCreditedEvent        │
    │ - TaxDeductedEvent             │
    │ - AuditLog Entries             │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Send Notification:             │
    │ - Interest Credited Alert      │
    │ - New Account Balance          │
    │ - Tax Deducted (if any)        │
    │ - Email and SMS                │
    └────────┬────────────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ END: Interest Credited   │
    │ Accounts Updated         │
    └──────────────────────────┘
```

---

## Day-End Processing Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         DAY-END PROCESSING                              │
│              (Critical Overnight Batch Process)                         │
└─────────────────────────────────────────────────────────────────────────┘

    ┌──────────────────┐
    │ Scheduled:       │
    │ EOD @ 23:30 PM   │
    │ (Before Cutoff)  │
    └────────┬─────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Pre-Processing Steps:      │
    │                            │
    │ 1. System Lock             │
    │    (Nobody can make txn)    │
    │                            │
    │ 2. Queue All Pending Txns  │
    │    (Standby for closure)   │
    │                            │
    │ 3. Freeze Account Balances │
    │    (Read-Only Mode)        │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Phase 1: Transactions      │
    │ Settlement                 │
    │                            │
    │ 1. Process Pending TXNs    │
    │ 2. Settle Cheque Deposits  │
    │ 3. Settlement Clearing     │
    │ 4. Validate All TXNs       │
    │ 5. Create Settlement Report│
    └────────┬────────────────────┘
             │
             ├─── IF ERROR ──► Log & Review
             │                Manual Intervention
             │
             ▼ COMPLETE
    ┌────────────────────────────┐
    │ Phase 2: GL Reconciliation │
    │                            │
    │ 1. Reconcile GL Heads      │
    │ 2. Trial Balance           │
    │ 3. Check Debit = Credit    │
    │ 4. Reconcile Suspense      │
    │ 5. Reconcile Int Accruals  │
    └────────┬────────────────────┘
             │
             ├─── IF MISMATCH ──► Log Exception
             │                   Manual Review
             │
             ▼ BALANCED
    ┌────────────────────────────┐
    │ Phase 3: Interest Accrual  │
    │                            │
    │ 1. Run Daily Interest Batch│
    │ 2. Post GL Entries         │
    │ 3. Update Account Accruals │
    │ 4. Verify Calculations     │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────┐
    │ Phase 4: Charges & Fees    │
    │                            │
    │ 1. Calculate Monthly Fees  │
    │ 2. Calculate Service Charge│
    │ 3. Apply Penalties (if any)│
    │ 4. Post GL Entries         │
    │ 5. Debit Customer Accounts │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Phase 5: EMI/Loan Processing   │
    │                                │
    │ 1. Generate EMI Due List       │
    │ 2. Mark EMIs as Overdue        │
    │ 3. Calculate Penal Interest    │
    │ 4. Update Loan Status          │
    │ 5. Generate Demand Notices     │
    └────────┬────────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Phase 6: Account Closures    │
    │                              │
    │ 1. Mark Dormant Accounts     │
    │ 2. Process Scheduled Closures│
    │ 3. Final Balance Calculation │
    │ 4. Post Closure GL Entries   │
    │ 5. Archive Account Data      │
    └────────┬───────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Phase 7: Deposit Maturity    │
    │                              │
    │ 1. Fetch Matured Deposits    │
    │ 2. Calculate Final Payable   │
    │ 3. Apply Final Interest      │
    │ 4. Post GL Entries           │
    │ 5. Credit Customer Accounts  │
    │ 6. Update FD Status          │
    └────────┬───────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Phase 8: Reports Generation  │
    │                              │
    │ 1. Daily GL Report           │
    │ 2. Settlement Report         │
    │ 3. Transaction Summary       │
    │ 4. Customer Activity Report  │
    │ 5. Exception Report          │
    │ 6. Risk Report               │
    └────────┬───────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Phase 9: Data Backup         │
    │                              │
    │ 1. Backup Application DB     │
    │ 2. Backup Archive DB         │
    │ 3. Verify Backup Integrity   │
    │ 4. Replicate to DR Site      │
    │ 5. Validate Replication      │
    └────────┬───────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Phase 10: Final Validation   │
    │                              │
    │ 1. Reconcile Accounts        │
    │ 2. Verify Transaction Count  │
    │ 3. Check GL Balances         │
    │ 4. Validate No Anomalies     │
    │ 5. Sign-off by System        │
    └────────┬───────────────────────┘
             │
             ├─── IF ERROR ──► Alert Admin
             │                Rollback if needed
             │
             ▼ SUCCESS
    ┌─────────────────────────────┐
    │ Phase 11: System Unlock     │
    │                             │
    │ 1. Release Account Locks    │
    │ 2. Enable New Transactions  │
    │ 3. Mark Day as CLOSED       │
    │ 4. Start New Business Day   │
    └────────┬────────────────────┘
             │
             ▼
    ┌─────────────────────────────┐
    │ Phase 12: Notifications     │
    │                             │
    │ 1. Notify Stakeholders      │
    │ 2. Generate Day-End Report  │
    │ 3. Log Completion Time      │
    │ 4. Store Audit Trail        │
    │ 5. Archive Old Logs         │
    └────────┬────────────────────┘
             │
             ▼
    ┌────────────────────┐
    │ END: Day-End Close │
    │ Ready for Next Day │
    └────────────────────┘

FAILURE HANDLING:
├─ Partial Failure → Rollback & Alert
├─ Complete Failure → Don't Unlock System
├─ Manual Review Required
├─ Rollback to Previous Day-End
└─ Retry Next Cycle
```

---

## GL Posting & Reconciliation Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│            GENERAL LEDGER POSTING & RECONCILIATION FLOW                 │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─────────────────────────┐
    │ Transaction Initiated   │
    │ (Any Banking Operation) │
    └────────┬────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ Transaction Processing:         │
    │ - Capture All Txn Details       │
    │ - Generate Transaction ID       │
    │ - Create Audit Log Ent.         │
    │ - Determine GL A/Cs             │
    │ - Calculate Amounts             │
    │ - Assign Narration              │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ GL Mapping:                     │
    │ - Identify Debit Account        │
    │ - Identify Credit Account       │
    │ - Apply Cost Center (if needed) │
    │ - Apply Profit Center           │
    │ - Set GL Posting Status         │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ Validation Before Posting:      │
    │ ✓ Debit Account Valid           │
    │ ✓ Credit Account Valid          │
    │ ✓ Debit Amt = Credit Amt        │
    │ ✓ Amount > 0                    │
    │ ✓ Account Status = ACTIVE       │
    │ ✓ GL Head Status = ACTIVE       │
    │ ✓ No Restrictions               │
    └────────┬─────────────────────────┘
             │
             ├─── FAIL ──► Reject & Log Error
             │
             ▼ PASS
    ┌─────────────────────────────────┐
    │ Create GL Entry (Journal):      │
    │ Date: Transaction Date          │
    │ Debit A/C: With Amount          │
    │ Credit A/C: With Amount         │
    │ Description: Narration          │
    │ Reference: Transaction ID       │
    │ Status: PENDING                 │
    │ Created By: System/User         │
    │ Created Date-Time               │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ Apply Authorization Rules:      │
    │ - Check User Authority          │
    │ - Check Amount Limits           │
    │ - Multi-Level Approval (if req) │
    │ - Compliance Check              │
    └────────┬─────────────────────────┘
             │
             ├─── NOT AUTHORIZED ──► Queue for Approval
             │
             ▼ AUTHORIZED
    ┌─────────────────────────────────┐
    │ GL Posting:                     │
    │ 1. Post Debit Entry             │
    │    - Update GL Balance (-)      │
    │    - Create Ledger Entry        │
    │    - Update GL Running Balance  │
    │                                 │
    │ 2. Post Credit Entry            │
    │    - Update GL Balance (+)      │
    │    - Create Ledger Entry        │
    │    - Update GL Running Balance  │
    │                                 │
    │ 3. Update Reconciliation Status │
    │ 4. Update Audit Trail           │
    │ 5. Set GL Status: POSTED        │
    └────────┬─────────────────────────┘
             │
             ├─── POST FAIL ──► Rollback Entry
             │                 Log Exception
             │
             ▼ POSTED
    ┌─────────────────────────────────┐
    │ Update Account Balances:        │
    │ - Customer Account Balance      │
    │ - GL Account Balance            │
    │ - Cost Center Balance           │
    │ - Profit Center Balance         │
    │ - Branch Balance                │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ Create Reconciliation Entry:    │
    │ - Transaction ID                │
    │ - GL Entry IDs (Debit/Credit)   │
    │ - Post Date                     │
    │ - Clear Date                    │
    │ - Rec Status: PENDING_REC       │
    └────────┬─────────────────────────┘
             │
    ┌────────┴─────────────────────────────────┐
    │                                          │
    ▼                                          ▼
    │                                    │
    DAILY GL RECONCILIATION:          MONTHLY GL RECONCILIATION:
    (EOD Process)                     (Month-End Close)
    │                                    │
    1. Reconcile Credits              1. Prepare GL Trial Balance
    2. Reconcile Debits               2. Compare with Sub-Ledgers
    3. Check Balance Totals           3. Reconcile:
    4. Clear Matched Items              - Savings GL
    5. Report Mismatches                - Current GL
    6. Generate Report                  - Loan GL
                                        - FD GL
                                        - Suspense GL
                                     4. Investigate Differences
                                     5. Make Adjusting Entries
                                     6. Final Approval
                                     7. Generate Month-End Report
    │                                    │
    └────────┬──────────────────────────┘
             │
             ▼
    ┌─────────────────────────────────┐
    │ Final GL Status Update:         │
    │ - Rec Status: CLEARED           │
    │ - Final Balance Updated         │
    │ - Closed Date                   │
    │ - Approved By (signature)       │
    │ - Final Audit Trail Entry       │
    └────────┬─────────────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ END: GL Posting & Rec    │
    │ Complete & Cleared       │
    └──────────────────────────┘
```

---

## User Login & Authentication Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│              USER LOGIN & JWT AUTHENTICATION FLOW                       │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─────────────────────┐
    │  START:             │
    │ User Opens App      │
    │ Login Page          │
    └────────┬────────────┘
             │
             ▼
    ┌─────────────────────┐
    │ Enter Credentials:  │
    │ - Username/Email    │
    │ - Password          │
    │ - Optional: 2FA     │
    └────────┬────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Input Validation:            │
    │ ✓ Username Not Empty         │
    │ ✓ Password Not Empty         │
    │ ✓ Format Check               │
    │ ✓ Length Check               │
    └────────┬─────────────────────┘
             │
             ├─── FAIL ──► Show Error & Retry
             │
             ▼ PASS
    ┌──────────────────────────────┐
    │ Send POST Request:           │
    │ POST /api/v1/auth/login      │
    │ {                            │
    │   "username": "user@bank.com"│
    │   "password": "encrypted"    │
    │ }                            │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Server-Side Validation:      │
    │ ✓ Username Exists            │
    │ ✓ Password Correct (bcrypt)  │
    │ ✓ Account Active             │
    │ ✓ Account Not Locked         │
    │ ✓ Not Disabled               │
    │ ✓ Max Login Attempts OK      │
    └────────┬─────────────────────┘
             │
             ├─── INVALID ──► Failed Attempt++
             │                (Max 5 failed)
             │                Lock if exceeded
             │
             ▼ VALID
    ┌──────────────────────────────┐
    │ Check 2FA/MFA (if enabled):  │
    │ - Send OTP via SMS/Email     │
    │ - Wait for OTP Entry         │
    │ - Validate OTP (6 digits)    │
    │ - Verify within time limit   │
    └────────┬─────────────────────┘
             │
             ├─── OTP FAILED ──► Retry (Max 3)
             │
             ▼ OTP VALID (or 2FA disabled)
    ┌──────────────────────────────┐
    │ Load User Details:           │
    │ - User ID                    │
    │ - Username                   │
    │ - User Role(s)               │
    │ - Permissions                │
    │ - Branch                     │
    │ - Department                 │
    │ - Settings                   │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Generate JWT Token:          │
    │                              │
    │ Header: {                    │
    │   "alg": "HS256"             │
    │   "typ": "JWT"               │
    │ }                            │
    │                              │
    │ Payload: {                   │
    │   "sub": "user_id_123"       │
    │   "username": "user@bank"    │
    │   "roles": ["CUSTOMER"]      │
    │   "permissions": [...]       │
    │   "iat": 1705317000          │
    │   "exp": 1705403400 (+24hrs) │
    │   "jti": "unique_token_id"   │
    │ }                            │
    │                              │
    │ Signature: HMAC256(...)      │
    └────────┬─────────────────────┘
             │
             ▼
    ┌──────────────────────────────┐
    │ Generate Refresh Token:      │
    │ (Longer expiry: 7 days)      │
    │ Stored securely in backend   │
    │ Rotation enabled             │
    └────────┬─────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Create Login Audit Log:        │
    │ - User ID                      │
    │ - Login Time                   │
    │ - IP Address                   │
    │ - Device Type                  │
    │ - Browser/App Version          │
    │ - Location (if available)      │
    │ - Status: SUCCESS              │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Reset Failed Login Counter:    │
    │ - Failed Attempts: 0           │
    │ - Last Login: Now              │
    │ - Active Session: Yes          │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Return Login Response:         │
    │ {                              │
    │   "status": "SUCCESS"          │
    │   "data": {                    │
    │     "accessToken": "JWT...",   │
    │     "refreshToken": "...",     │
    │     "expiresIn": 86400,        │
    │     "user": {                  │
    │       "id": "user_123",        │
    │       "username": "user@..."   │
    │       "roles": ["CUSTOMER"]    │
    │     }                          │
    │   }                            │
    │ }                              │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Client Stores Token:           │
    │ - accessToken: LocalStorage    │
    │   (or Secure Cookie)           │
    │ - refreshToken: SecureStorage  │
    │ - Expiry Timer Set             │
    └────────┬────────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Redirect to Dashboard          │
    │ User Logged In Successfully    │
    │ Load Dashboard Data            │
    │ (Using Access Token)           │
    └────────┬────────────────────────┘
             │
             ▼
    ┌──────────────────┐
    │ END: Logged In   │
    │ Can Use App      │
    └──────────────────┘

SUBSEQUENT API REQUESTS:
    Every request includes:
    Header: Authorization: Bearer {accessToken}
    
    ┌─ Request Received
    │
    ├─ Extract Token from Header
    │
    ├─ Validate Token Signature
    │
    ├─ Check Expiry
    │
    ├─ If Expired → Use Refresh Token to get new Access Token
    │
    ├─ Load User Permissions from Token
    │
    ├─ Check Endpoint Authorization
    │
    └─ Process Request or Return 401/403
```

---

## Data Access Layer Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│            DATA ACCESS LAYER (REPOSITORY) FLOW                          │
└─────────────────────────────────────────────────────────────────────────┘

    ┌─────────────────────────┐
    │ Service Layer           │
    │ Calls Repository Method │
    │ findByCustomerId()      │
    └────────┬────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Spring Data JPA               │
    │ Creates Dynamic Query          │
    │ Based on Method Signature      │
    │ findByCustomerId → SELECT ... │
    │ WHERE customer_id = ?          │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Check Query Cache:             │
    │ ✓ Query in Prepared Cache?     │
    │ ✓ Get from Query Plan Cache    │
    │ ✓ Set Query Parameters         │
    │ ✓ Skip Parsing if Cached       │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Check Result Set Cache:        │
    │ (L1 Cache - Hibernate)         │
    │ ✓ Object already loaded?       │
    │ ✓ Return from Session Cache    │
    │ ✓ No DB Hit Needed             │
    └────────┬───────────────────────┘
             │
             ├─── IN CACHE ──► Return Object
             │
             ▼ NOT IN CACHE
    ┌────────────────────────────────┐
    │ Check L2 Cache:                │
    │ (Redis / EhCache)              │
    │ ✓ Key: customer#customerId     │
    │ ✓ Get from Redis               │
    │ ✓ Deserialize Object           │
    └────────┬───────────────────────┘
             │
             ├─── IN CACHE ──► Populate L1 & Return
             │
             ▼ NOT IN CACHE
    ┌────────────────────────────────┐
    │ Execute Database Query:        │
    │ SELECT * FROM t_customers      │
    │ WHERE customer_id = ?          │
    │ Parameter: customerId value    │
    │ Connection Pool: Get Connection│
    │ Query Execution                │
    │ Result Set Retrieved           │
    └────────┬───────────────────────┘
             │
             ├─── NOT FOUND ──► Return null/Optional.empty()
             │
             ▼ FOUND
    ┌────────────────────────────────┐
    │ Map Result Set to Object:      │
    │ (ORM Mapping)                  │
    │ - Map columns to fields        │
    │ - Set field values             │
    │ - Create Entity Object         │
    │ - Load Related Objects (if)    │
    │   (Lazy or Eager loading)      │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Store in L1 Cache:             │
    │ (Hibernate Session Cache)      │
    │ - Add to Session Identity Map  │
    │ - Object ID: customer_id       │
    │ - Reference: Java Object       │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Store in L2 Cache:             │
    │ (Redis)                        │
    │ - Serialize Object to JSON     │
    │ - Key: customer#customerId     │
    │ - TTL: 3600 seconds (1 hour)   │
    │ - Store in Redis               │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Connection Release:            │
    │ - Return connection to pool    │
    │ - Mark as available            │
    │ - Release resources            │
    └────────┬───────────────────────┘
             │
             ▼
    ┌────────────────────────────────┐
    │ Return Object to Service:      │
    │ Optional<Customer> with object │
    │ Service can use immediately    │
    │ No additional DB queries       │
    │ unless lazy fields accessed    │
    └────────┬───────────────────────┘
             │
             ▼
    ┌──────────────────────────┐
    │ Service Uses Object:     │
    │ - Read properties        │
    │ - Make business logic    │
    │ - Transform to DTO       │
    │ - Return to Controller   │
    └──────────────────────────┘

WRITE OPERATION FLOW:
    ┌─ Repository save() called
    │
    ├─ Check if new object or update
    │
    ├─ Generate SQL (INSERT or UPDATE)
    │
    ├─ Execute & Get Generated Keys
    │
    ├─ Map back to Entity
    │
    ├─ Update L1 Cache (Session)
    │
    ├─ Invalidate L2 Cache
    │
    ├─ Publish Entity Event (if configured)
    │
    ├─ Return Updated Entity
    │
    └─ Transaction Commit/Rollback
```

---

This comprehensive flow diagram documentation provides visual understanding of all major banking processes and system flows in a Tier-1 CBS application.


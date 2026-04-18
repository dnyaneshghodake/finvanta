# Finvanta CBS — End-to-End Transaction Flows

**Architecture Pattern:** Multi-module, single enforcement point (TransactionEngine)  
**Data Model:** Multi-tenant, branch-scoped, ledger-based accounting  
**Compliance:** RBI-compliant, Finacle/Temenos patterns

---

## Flow 1: Deposit to Savings Account (CASA)

### Scenario
**User:** Loan Officer (MAKER) at HQ001 branch  
**Action:** Customer deposits ₹10,000 cash to their savings account (ACC-2026-001)  
**Business Date:** 2026-04-19 (Saturday, working day)

---

### Step-by-Step Execution

```
┌─────────────────────────────────────────────────────────────────┐
│ PRESENTATION LAYER                                              │
├─────────────────────────────────────────────────────────────────┤

1. User submits HTTP request:
   POST /api/v1/accounts/ACC-2026-001/deposit
   {
     "amount": 10000.00,
     "narration": "Cash deposit by customer",
     "channel": "BRANCH_TELLER"
   }
   
   Headers:
   - Authorization: Bearer jwt_token (JWT signed with CBS_JWT_SECRET)
   - X-Tenant-Id: DEFAULT (or extracted from JWT)

2. Spring Security validates JWT:
   ├─ Signature valid? (HMAC-SHA256 with CBS_JWT_SECRET)
   ├─ Token not expired? (access_token expiry = 15 min)
   ├─ Extract claims: username, roles, tenant_id
   └─ Set SecurityContext

3. TenantFilter intercepts:
   ├─ Extract tenant_id from JWT
   ├─ TenantContext.setCurrentTenant("DEFAULT")
   └─ Continue to controller

4. DepositAccountController.deposit():
   ├─ @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')") → MAKER has permission ✓
   ├─ Validate request (amount > 0?) → ✓
   ├─ Delegate to service layer
   └─ Return ResponseEntity<ApiResponse>


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ SERVICE LAYER                                                   │
├─────────────────────────────────────────────────────────────────┤

5. DepositAccountService.deposit(accountNumber="ACC-2026-001", amount=10000):
   
   // Fetch account
   DepositAccount account = depositRepository.findByTenantIdAndAccountNumber(
       tenantId="DEFAULT", accountNumber="ACC-2026-001"
   )
   ├─ Implicit multi-tenant filter: WHERE tenant_id = :tenantId
   ├─ Account found with ID = 42, status = ACTIVE ✓
   └─ Balance before: ₹50,000

6. Validate account:
   ├─ Status == ACTIVE? YES ✓
   ├─ Account not FROZEN? YES ✓
   ├─ Account owner KYC verified? YES ✓
   ├─ Branch matches user's branch (HQ001)? YES ✓
   └─ All validations pass

7. TransactionEngine.execute() ← SINGLE ENFORCEMENT POINT
   
   Create TransactionRequest:
   {
       transactionType: DEPOSIT,
       accountReference: "ACC-2026-001",
       amount: 10000.00,
       narration: "Cash deposit",
       channel: "BRANCH_TELLER",
       journalLines: [
           JournalLineRequest(
               glCode: "1100",
               side: DEBIT,
               amount: 10000.00,
               description: "Bank Ops account"
           ),
           JournalLineRequest(
               glCode: "2010",
               side: CREDIT,
               amount: 10000.00,
               description: "Savings Deposits"
           )
       ],
       businessDate: 2026-04-19,
       isSystemGenerated: false,
       initiatedBy: "maker1"
   }


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ TRANSACTION ENGINE (10-STEP PIPELINE)                           │
├─────────────────────────────────────────────────────────────────┤

STEP 1: IDEMPOTENCY CHECK
└─ idempotencyKey = null (not provided) → skip this step

STEP 2: BUSINESS DATE VALIDATION
└─ Query: SELECT * FROM business_calendars 
          WHERE tenant_id='DEFAULT' AND branch_id=1 AND business_date='2026-04-19'
   ├─ dayStatus = DAY_OPEN ✓
   ├─ eodComplete = false (EOD not run yet for this day) ✓
   └─ Locked = false (day is open for transactions) ✓

STEP 3: ACCOUNT VALIDATION
├─ DepositAccount exists and status = ACTIVE ✓
├─ NOT frozen (unless CREDIT_FREEZE, credits allowed) ✓
├─ User branch (HQ001) == Account branch (HQ001) ✓
└─ All checks pass

STEP 4: DOUBLE-ENTRY POSTING VALIDATION
├─ Line 1: glCode "1100" exists, is_active=true ✓
├─ Line 2: glCode "2010" exists, is_active=true ✓
├─ Aggregate: totalDebit = 10000, totalCredit = 10000
├─ Balanced? YES ✓
└─ Continue to GL posting

STEP 5: GL BALANCE UPDATE (Pessimistic Lock)
├─ Query: SELECT * FROM gl_master 
          WHERE tenant_id='DEFAULT' AND gl_code='1100' FOR UPDATE
│  ├─ Current debit_balance: ₹5,000,000
│  ├─ Update: 5,000,000 + 10,000 = ₹5,010,000
│  └─ Commit (transaction row-locked for other writers)
│
└─ Query: SELECT * FROM gl_master 
          WHERE tenant_id='DEFAULT' AND gl_code='2010' FOR UPDATE
   ├─ Current credit_balance: ₹100,000,000
   ├─ Update: 100,000,000 + 10,000 = ₹100,010,000
   └─ Commit (locks released)

STEP 6: LEDGER ENTRY (Immutable Hash-Chain)
├─ Create JournalEntry:
│  ├─ id: 1001 (auto-increment)
│  ├─ tenant_id: DEFAULT
│  ├─ branch_id: 1, branch_code: HQ001
│  ├─ value_date: 2026-04-19
│  ├─ posting_date: 2026-04-19 14:30:45
│  ├─ voucher_number: HQ001-2026-04-19-001042
│  ├─ total_debit: 10000.00
│  ├─ total_credit: 10000.00
│  ├─ source_module: DEPOSIT
│  ├─ source_ref: ACC-2026-001
│  ├─ is_system_generated: false
│  └─ posted: true
│  
├─ Save to journal_entries table
│
├─ Create LedgerEntry #1:
│  ├─ id: 50001
│  ├─ journal_entry_id: 1001
│  ├─ gl_code: 1100
│  ├─ debit_amount: 10000.00
│  ├─ credit_amount: 0
│  ├─ posting_date: 2026-04-19
│  ├─ previousHash: "abc123...xyz" (from prior GL 1100 posting)
│  ├─ hash: SHA-256(1100|10000|0|abc123...xyz)
│  └─ Immutable (no update allowed)
│
└─ Create LedgerEntry #2:
   ├─ id: 50002
   ├─ journal_entry_id: 1001
   ├─ gl_code: 2010
   ├─ debit_amount: 0
   ├─ credit_amount: 10000.00
   ├─ posting_date: 2026-04-19
   ├─ previousHash: "def456...uvw" (from prior GL 2010 posting)
   ├─ hash: SHA-256(2010|0|10000|def456...uvw)
   └─ Immutable

STEP 7: BATCH RUNNING TOTALS UPDATE
└─ Query: SELECT * FROM batch_jobs 
          WHERE tenant_id='DEFAULT' AND date(business_date)='2026-04-19' 
          AND job_name='DAILY_BATCH'
   ├─ current totals: processed=150, totalDebits=₹5.5M, totalCredits=₹5.5M
   ├─ Update: processed=151, totalDebits=₹5.51M, totalCredits=₹5.51M
   └─ Commit

STEP 8: VOUCHER GENERATION (Sequential per Branch)
└─ Query: SELECT * FROM voucher_sequencer 
          WHERE tenant_id='DEFAULT' AND branch_id=1 FOR UPDATE
   ├─ Current nextVoucherNumber: 1042
   ├─ Increment to: 1043
   ├─ voucherNumber = "HQ001-2026-04-19-001042"
   └─ Commit (sequence protected for concurrent requests)

STEP 9: MODULE CALLBACK (Post-Deposit State Update)
└─ DepositAccountService.postDepositInternal(account=42, amount=10000):
   ├─ account.ledgerBalance = 50000 + 10000 = ₹60,000
   ├─ account.availableBalance = 60000 (same as ledger for savings)
   ├─ account.lastTransactionDate = 2026-04-19
   ├─ account.numTransactions = 127 + 1 = 128
   ├─ Save account (UPDATE deposit_accounts SET ...)
   └─ Audit: balance changed 50K→60K

STEP 10: AUDIT TRAIL CAPTURE (Same Transaction as Engine)
└─ AuditService.logEventInline(...):
   ├─ Create AuditLog:
   │  ├─ id: 5001 (sequence)
   │  ├─ tenant_id: DEFAULT
   │  ├─ branch_id: 1, branch_code: HQ001
   │  ├─ entity_type: DepositAccount
   │  ├─ entity_id: 42
   │  ├─ action: DEPOSIT
   │  ├─ beforeSnapshot: {"ledgerBalance":50000,"availableBalance":50000,"lastTxnDate":"2026-04-18"}
   │  ├─ afterSnapshot: {"ledgerBalance":60000,"availableBalance":60000,"lastTxnDate":"2026-04-19"}
   │  ├─ performed_by: maker1
   │  ├─ module: DEPOSIT
   │  ├─ description: "Deposit of ₹10,000 to ACC-2026-001"
   │  ├─ event_timestamp: 2026-04-19 14:30:45
   │  ├─ payloadHash: SHA-256(before+after+module) = "hash1"
   │  ├─ previousChainHash: "hash0" (from prior audit entry)
   │  └─ chainHash: SHA-256(hash1 + hash0) = "hash2"
   │
   ├─ Save to audit_logs (append-only)
   └─ Return: TransactionResult {
       success: true,
       voucherNumber: HQ001-2026-04-19-001042,
       journalEntryId: 1001,
       ledgerBalance: 60000,
       availableBalance: 60000,
       message: "Deposit successful"
   }


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ RESPONSE TO CLIENT                                              │
├─────────────────────────────────────────────────────────────────┤

8. DepositAccountController returns HTTP 200 OK:
   {
     "success": true,
     "data": {
       "transactionRef": "HQ001-2026-04-19-001042",
       "amount": 10000.00,
       "ledgerBalance": 60000.00,
       "availableBalance": 60000.00,
       "timestamp": "2026-04-19T14:30:45Z"
     },
     "message": "Deposit successful"
   }

9. User sees confirmation:
   "₹10,000 deposited successfully. Voucher: HQ001-2026-04-19-001042"

└─────────────────────────────────────────────────────────────────┘
```

---

## Flow 2: Loan Disbursement with EMI Setup

### Scenario
**User:** Checker (CHECKER) at HQ001 branch  
**Action:** Approve & disburse ₹1,00,000 loan to customer (LoanApplication approved, ready to create account)  
**Loan Details:**
- Amount: ₹1,00,000
- Tenure: 12 months
- Rate: 10% p.a.
- EMI: ₹8,791.33 (monthly, reducing balance)
- Disbursement Account: ACC-2026-001 (savings account)

---

### Execution

```
┌─────────────────────────────────────────────────────────────────┐
│ STEP 1: Create LoanAccount from Approved Application            │
├─────────────────────────────────────────────────────────────────┤

LoanController.createAccount(applicationId=100)
{
    application: {
        id: 100,
        status: APPROVED,
        customer_id: 10,
        requested_amount: 100000,
        approved_amount: 100000,
        interest_rate: 10.00,
        tenure_months: 12
    }
}

LoanAccountService.createAccountFromApplication(application, businessDate='2026-04-19')
├─ Validate: application.status == APPROVED? YES ✓
├─ Create LoanAccount:
│  ├─ account_number: "LOAN-2026-001" (uniqueness enforced)
│  ├─ customer_id: 10
│  ├─ status: ACTIVE (once disbursed)
│  ├─ sanctioned_amount: 1,00,000
│  ├─ principal_outstanding: 1,00,000
│  ├─ interest_rate: 10.00
│  ├─ tenure_months: 12
│  ├─ repayment_frequency: MONTHLY
│  ├─ next_emi_date: 2026-05-19 (30 days from disbursement)
│  ├─ maturity_date: 2027-04-19
│  ├─ disbursement_account_number: ACC-2026-001
│  └─ npa_category: STANDARD (initially)
│
└─ Save account (INSERT into loan_accounts)


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 2: Disburse Loan (GL Posting via TransactionEngine)        │
├─────────────────────────────────────────────────────────────────┤

LoanAccountService.disburseLoan(accountNumber="LOAN-2026-001", businessDate='2026-04-19')

TransactionEngine.execute({
    transactionType: LOAN_DISBURSE,
    amount: 100000.00,
    accountReference: "LOAN-2026-001",
    journalLines: [
        JournalLineRequest(glCode="1001", side=DEBIT, amount=100000.00, description="Loan Portfolio"),
        JournalLineRequest(glCode="1100", side=CREDIT, amount=100000.00, description="Bank Operations")
    ]
})

├─ STEP 1: Idempotency check (new txn, no prior key)
├─ STEP 2: Business date validation (DAY_OPEN? YES)
├─ STEP 3: Account validation (LOAN-2026-001 exists, ACTIVE)
├─ STEP 4: Double-entry validation (100K DR == 100K CR)
├─ STEP 5: GL balance update (pessimistic lock on 1001 & 1100)
│  └─ 1001 (Loan Portfolio) debit balance: ₹1,000,000 → ₹1,100,000
│  └─ 1100 (Bank) credit balance: ₹10,000,000 → ₹9,900,000
│
├─ STEP 6: Ledger entries (hash-chained)
│  └─ Entry #1: 1001 | DR 100K
│  └─ Entry #2: 1100 | CR 100K
│
├─ STEP 7: Batch totals (add to daily batch)
├─ STEP 8: Voucher generation (HQ001-2026-04-19-001043)
│
├─ STEP 9: Module callback (post-disbursal state):
│  ├─ LoanAccount.status = ACTIVE (vs APPROVED pre-disbursal)
│  ├─ LoanAccount.disbursed_amount = 100000
│  ├─ LoanAccount.outstanding_principal = 100000
│  ├─ LoanAccount.next_emi_date = 2026-05-19
│  ├─ DepositAccount(ACC-2026-001).ledger_balance += 100000
│  └─ Update: transaction_ref = HQ001-2026-04-19-001043
│
└─ STEP 10: Audit trail
   └─ AuditLog: LOAN_DISBURSED | before: {status:APPROVED}, after: {status:ACTIVE}


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 3: Generate Amortization Schedule                          │
├─────────────────────────────────────────────────────────────────┤

LoanScheduleService.generateSchedule(loanAccount, businessDate='2026-04-19')

Calculate EMI:
├─ Principal (P) = ₹1,00,000
├─ Monthly rate (r) = 10% / 12 = 0.8333% = 0.008333
├─ Tenure (n) = 12 months
├─ EMI = P × [r(1+r)^n] / [(1+r)^n - 1]
├─ EMI = 100000 × [0.008333 × 1.10471] / [0.10471]
├─ EMI = ₹8,791.33 (monthly payment)
└─ Total interest over tenure = (8791.33 × 12) - 100000 = ₹5,495.96

Generate 12 Schedule Rows:
├─ Installment 1:
│  ├─ emi_amount: ₹8,791.33
│  ├─ opening_balance: ₹1,00,000
│  ├─ interest_component: ₹833.33 (100000 × 0.008333)
│  ├─ principal_component: ₹7,958.00 (8791.33 - 833.33)
│  ├─ closing_balance: ₹92,042.00 (100000 - 7958)
│  ├─ due_date: 2026-05-19 (EMI 1 due 30 days from now)
│  ├─ status: SCHEDULED
│  └─ paid_amount: 0, paid_date: null
│
├─ Installment 2:
│  ├─ opening_balance: ₹92,042.00
│  ├─ interest_component: ₹766.95
│  ├─ principal_component: ₹8,024.38
│  ├─ closing_balance: ₹84,017.62
│  ├─ due_date: 2026-06-19
│  └─ status: SCHEDULED
│
├─ ... (Installments 3-11 similar)
│
└─ Installment 12:
   ├─ opening_balance: ₹7,947.81
   ├─ interest_component: ₹66.23
   ├─ principal_component: ₹8,725.10 (final installment balances)
   ├─ closing_balance: ₹0 (loan closed)
   ├─ due_date: 2027-04-19
   └─ status: SCHEDULED

INSERT 12 rows into loan_schedules table


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 4: Register Standing Instruction (EMI Auto-Debit)          │
├─────────────────────────────────────────────────────────────────┤

StandingInstructionService.createLoanEmiSI(loanAccount, businessDate='2026-04-19')

Create StandingInstruction:
├─ si_reference: "SI-2026-001" (unique)
├─ customer_id: 10
├─ source_account_number: "ACC-2026-001" (debits from customer's savings)
├─ destination_type: "LOAN_EMI"
├─ destination_account_number: "LOAN-2026-001"
├─ amount: null (dynamic — resolved from LoanAccount.emi_amount on each execution)
├─ frequency: MONTHLY
├─ execution_day: 19 (day-of-month, every 19th)
├─ start_date: 2026-05-19 (first EMI due date)
├─ end_date: 2027-04-19 (maturity date, auto-cancel when loan closed)
├─ status: ACTIVE (auto-enabled for LOAN_EMI type, no approval required)
├─ priority: 1 (highest — bank's own recovery)
├─ next_execution_date: 2026-05-19 (first EMI execution date)
└─ narration: "Auto-debit for LOAN-2026-001"

INSERT into standing_instructions table

Audit: StandingInstruction created
└─ AuditLog: SI_REGISTERED | "Auto-debit SI for loan account LOAN-2026-001"


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ RESULT RETURNED TO USER                                         │
├─────────────────────────────────────────────────────────────────┤

HTTP 200 OK:
{
  "success": true,
  "data": {
    "loanAccountNumber": "LOAN-2026-001",
    "disbursedAmount": 100000.00,
    "emiAmount": 8791.33,
    "tenure": 12,
    "nextEmiDate": "2026-05-19",
    "maturityDate": "2027-04-19",
    "siRegistered": true,
    "scheduleGenerated": true
  },
  "message": "Loan disbursed & EMI setup complete"
}

User Action:
└─ Download amortization schedule PDF (for customer records)


└─────────────────────────────────────────────────────────────────┘
```

---

## Flow 3: EOD (End-of-Day) Batch Processing

### Scenario
**Time:** 23:55 IST on 2026-04-19 (Saturday)  
**Business Date:** 2026-04-19  
**Active Accounts:** 100 loan accounts, 500 CASA accounts  
**Scheduled Operations:** Update DPD, accrue interest, classify NPA, execute SIs, reconcile

---

### Execution

```
┌─────────────────────────────────────────────────────────────────┐
│ STEP 1: Validate & Lock Day                                    │
├─────────────────────────────────────────────────────────────────┤

EodOrchestrator.executeEod(businessDate='2026-04-19')

Lock day for EOD processing:
├─ Query: SELECT * FROM business_calendars 
          WHERE tenant_id='DEFAULT' AND branch_id=1 
          AND business_date='2026-04-19' FOR UPDATE
│
├─ Validate: day_status = DAY_OPEN? YES ✓
├─ Locked too many times (max retries)? NO ✓
│
├─ Update: day_status = EOD_RUNNING (prevents new transactions)
├─ Update: locked = true
├─ Commit (exclusive lock held)
│
└─ Create BatchJob(jobName='EOD', businessDate='2026-04-19', status=RUNNING)


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 2: Mark Overdue Installments (Parallel)                   │
├─────────────────────────────────────────────────────────────────┤

For each LoanAccount (in 4 parallel threads):
{
    LoanSchedule[] schedules = scheduleRepo.findByAccountId(account.id);
    
    for (LoanSchedule schedule : schedules) {
        if (schedule.due_date < businessDate && schedule.status != PAID) {
            schedule.status = OVERDUE;
            scheduleRepo.save(schedule);
            
            AuditLog: SCHEDULE_MARKED_OVERDUE | account ABC001 | InstallmentNo 3
        }
    }
}

Result: 100 accounts processed, 15 schedules marked OVERDUE
└─ Processed: 100, Failed: 0


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 3: Daily Interest Accrual (Parallel)                       │
├─────────────────────────────────────────────────────────────────┤

For each LoanAccount where status IN (ACTIVE, OVERDUE):
{
    BigDecimal dailyRate = annual_rate / 365;
    BigDecimal daysCounted = (businessDate - lastAccrualDate).days;
    BigDecimal accruedInterest = outstanding * dailyRate * daysCounted;
    
    // Only post if accrued > 0
    if (accruedInterest > 0) {
        TransactionEngine.execute({
            journalLines: [
                JournalLineRequest(glCode="1002", side=DEBIT, amount=accruedInterest),
                JournalLineRequest(glCode="4001", side=CREDIT, amount=accruedInterest)
            ]
        });
        
        LoanTransaction record = new LoanTransaction(...)
            .setInterestComponent(accruedInterest)
            .setVoucherNumber(result.getVoucherNumber());
        loanTransactionRepo.save(record);
        
        AuditLog: INTEREST_ACCRUED | account ABC001 | ₹120.55
    }
}

GL Updates:
├─ 1002 (Interest Receivable): debit_balance = ₹100,000 + ₹2,450 = ₹102,450
└─ 4001 (Interest Income): credit_balance = ₹200,000 + ₹2,450 = ₹202,450

Result: 100 accounts, ₹2,450 total interest accrued
└─ Processed: 100, Failed: 0


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 4: NPA Classification (Parallel)                           │
├─────────────────────────────────────────────────────────────────┤

For each LoanAccount:
{
    int daysPastDue = (businessDate - oldestUnpaidSchedule.dueDate).days;
    
    if (daysPastDue == 0) {
        npaCategory = STANDARD;
        provisioning_percentage = 0%
    } else if (daysPastDue <= 30) {
        npaCategory = STANDARD;
        provisioning_percentage = 0%
    } else if (daysPastDue <= 60) {
        npaCategory = SUBSTANDARD;
        provisioning_percentage = 10%
    } else if (daysPastDue <= 90) {
        npaCategory = DOUBTFUL;
        provisioning_percentage = 50%
    } else {
        npaCategory = LOSS;
        provisioning_percentage = 100%
    }
    
    account.setNpaCategory(npaCategory);
    account.setDaysPastDue(daysPastDue);
    accountRepo.save(account);
    
    // Calculate & post provision GL entry
    BigDecimal outstanding = account.principal_outstanding;
    BigDecimal required_provision = outstanding * provisioning_percentage / 100;
    BigDecimal current_provision = account.provision_amount;
    BigDecimal delta = required_provision - current_provision;
    
    if (delta.compareTo(BigDecimal.ZERO) > 0) {
        // Need to increase provision
        TransactionEngine.execute({
            journalLines: [
                JournalLineRequest(glCode="5100", side=DEBIT, amount=delta),  // Provision Expense
                JournalLineRequest(glCode="1003", side=CREDIT, amount=delta)  // Provision Reserve
            ]
        });
        account.setProvisionAmount(required_provision);
    } else if (delta.compareTo(BigDecimal.ZERO) < 0) {
        // Reduce provision (write-back)
        TransactionEngine.execute({
            journalLines: [
                JournalLineRequest(glCode="1003", side=DEBIT, amount=(-delta)),
                JournalLineRequest(glCode="5100", side=CREDIT, amount=(-delta))
            ]
        });
        account.setProvisionAmount(required_provision);
    }
    
    AuditLog: NPA_CLASSIFIED | account ABC001 | {category: SUBSTANDARD, daysPastDue: 45, provision: ₹5,000}
}

NPA Distribution:
├─ STANDARD: 85 accounts (0 days overdue)
├─ SUBSTANDARD: 10 accounts (15-30 days overdue, ₹50K provision posted)
├─ DOUBTFUL: 4 accounts (60-90 days overdue, ₹200K provision posted)
└─ LOSS: 1 account (90+ days overdue, ₹100K provision posted)

GL Updates:
├─ 5100 (Provision Expense): debit_balance = ₹0 + ₹350K = ₹350,000
└─ 1003 (Provision Reserve): credit_balance = ₹0 + ₹350K = ₹350,000

Result: 100 accounts classified, ₹350K provision posted
└─ Processed: 100, Failed: 0


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 5: Standing Instructions Execution (Sequential, Priority)  │
├─────────────────────────────────────────────────────────────────┤

Query: SELECT * FROM standing_instructions 
       WHERE tenant_id='DEFAULT' AND status='ACTIVE' 
       AND next_execution_date <= businessDate
       ORDER BY priority ASC

Priority Order (Finacle SI_MASTER):
┌─ LOAN_EMI (Priority 1) — Bank's own recovery
│
│  SI-2026-001: Source ACC-2026-001, Debit ₹8,791.33 for LOAN-2026-001
│  ├─ Source account balance: ₹160,000
│  ├─ Required withdrawal: ₹8,791.33
│  ├─ Sufficient balance? YES ✓
│  │
│  ├─ Execute in REQUIRES_NEW transaction:
│  │  ├─ TransactionEngine.execute({
│  │  │    journalLines: [
│  │  │        JournalLineRequest(glCode="2010", side=DEBIT, amount=8791.33),   // Deposits reduced
│  │  │        JournalLineRequest(glCode="1001", side=CREDIT, amount=8791.33)  // Principal reduced
│  │  │    ]
│  │  │  })
│  │  │
│  │  ├─ Allocate repayment to schedule:
│  │  │   ├─ Search oldest UNPAID installment (Schedule 1, due 2026-05-19)
│  │  │   ├─ EMI amount: ₹8,791.33 matches available balance exactly
│  │  │   ├─ Set: paid_date=2026-04-19, paid_amount=8791.33, status=PAID
│  │  │   ├─ Split: principal_paid=7958, interest_paid=833.33
│  │  │   └─ AuditLog: EMI_PAID | schedule 1 | LOAN-2026-001
│  │  │
│  │  ├─ Update account:
│  │  │   ├─ outstanding_principal = 100000 - 7958 = ₹92,042
│  │  │   ├─ days_past_due = 0 (re-active, no longer overdue)
│  │  │   └─ next_emi_date = 2026-06-19 (next unpaid)
│  │  │
│  │  └─ SI state update:
│  │      ├─ next_execution_date = 2026-06-19 (next EMI due date)
│  │      ├─ status = ACTIVE (ready for next cycle)
│  │      └─ last_executed_date = 2026-04-19
│  │
│  ├─ Transaction succeeds ✓
│  │
│  └─ Result: SI-2026-001 EXECUTED (1 SI success)
│
│
├─ RD_CONTRIBUTION (Priority 2)
├─ SIP (Priority 3)
├─ INTERNAL_TRANSFER (Priority 4)
└─ UTILITY (Priority 5)

Overall SI Result:
├─ Total SIs processed: 5
├─ Executed: 5
└─ Failed: 0


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 6: Subledger Reconciliation (GL vs Accounts)               │
├─────────────────────────────────────────────────────────────────┤

Check Module 1: Loan Subledger
├─ Sum all outstanding_principal across all loan accounts: ₹5,000,000
├─ Query GL 1001 (Loan Portfolio) net balance:
│  ├─ Debit total (from ledger_entries): ₹5,100,000 (disbursements)
│  ├─ Credit total (from ledger_entries): ₹100,000 (repayments)
│  ├─ Net: ₹5,000,000 ✓
├─ Reconcile: ₹5,000,000 == ₹5,000,000 → BALANCED ✓
│
└─ AuditLog: RECONCILIATION_PASSED | Loan Subledger vs GL 1001

Check Module 2: CASA Subledger
├─ Sum all ledger_balance across all deposit accounts: ₹50,000,000
├─ Query GL 2010 (Deposits) net balance:
│  ├─ Credit total (from ledger_entries): ₹50,000,000
│  ├─ Net: ₹50,000,000 ✓
├─ Reconcile: ₹50,000,000 == ₹50,000,000 → BALANCED ✓
│
└─ AuditLog: RECONCILIATION_PASSED | CASA Subledger vs GL 2010

Overall Reconciliation Result:
└─ Status: RECONCILED (all major modules balanced)


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 7: Daily Balance Snapshots (Parallel)                      │
├─────────────────────────────────────────────────────────────────┤

For each account (loan + CASA):
{
    DailyBalanceSnapshot snapshot = new DailyBalanceSnapshot()
        .setAccountId(account.id)
        .setSnapshotDate(businessDate='2026-04-19')
        .setOpeningBalance(account.opening_balance_today)
        .setClosingBalance(account.ledger_balance or outstanding_principal)
        .setTotalDeposits(sum_of_deposits_today)
        .setTotalWithdrawals(sum_of_withdrawals_today)
        .setInterestAccrued(interest_accrued_today)
        .setChargesApplied(charges_today)
        .setCreatedAt(systemTime);
    
    snapshotRepo.save(snapshot);
}

Purpose:
├─ Audit trail for customer dispute resolution
├─ Regulatory reporting (RBI balance sheet, CRILC exposure)
├─ NPA investigation (balance at date of NPA classification)
└─ Account reconciliation (prove balance on specific date)

Example Entry:
└─ 2026-04-19 | Account ACC-2026-001 | Opening ₹50K → Closing ₹160K (+₹10K deposit +₹100K loan disbursal)

Result: 600 accounts (500 CASA + 100 loan) have daily snapshots
└─ Snapshots created: 600


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 8: Generate EOD Report                                    │
├─────────────────────────────────────────────────────────────────┤

EOD Report:
├─ Business Date: 2026-04-19
├─ Processing Steps:
│  ├─ Step 1 (Mark Overdue): Processed 100, Failed 0
│  ├─ Step 2 (Update DPD): Processed 100, Failed 0
│  ├─ Step 3 (Interest Accrual): Processed 100, accrued ₹2,450, Failed 0
│  ├─ Step 4 (NPA Classification): Processed 100, Failed 0
│  ├─ Step 5 (SI Execution): Executed 5 SIs, Failed 0
│  ├─ Step 6 (Reconciliation): PASSED (all subledgers balanced)
│  ├─ Step 7 (Balance Snapshots): Created 600 snapshots, Failed 0
│  └─ Step 8 (Report Generation): OK
│
├─ Summary Statistics:
│  ├─ Total Accounts Processed: 600 (500 CASA + 100 Loan)
│  ├─ GL Postings: 8 (accrual, provision, repayment, etc.)
│  ├─ Interest Accrued: ₹2,450
│  ├─ Provision Posted: ₹350K
│  ├─ SIs Executed: 5 (all successful)
│  ├─ Reconciliation: BALANCED
│  └─ Errors: 0
│
├─ Overall Status: COMPLETED
├─ Start Time: 2026-04-19 23:55:00
├─ End Time: 2026-04-20 00:18:00
├─ Duration: 23 minutes
│
└─ Export Report to:
   ├─ Database (batch_jobs table)
   ├─ File system (/reports/eod/2026-04-19-report.pdf)
   └─ Email to Operations Manager


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 9: Finalize EOD & Unlock Day                              │
├─────────────────────────────────────────────────────────────────┤

EodOrchestrator.finalizeEod()

Update BusinessCalendar:
├─ day_status = EOD_COMPLETE (day is marked as EOD-processed)
├─ eod_complete = true (prevents new transactions for this business date)
├─ locked = false (calendar row is released)
└─ Commit

Update BatchJob:
├─ status = COMPLETED
├─ end_time = 2026-04-20 00:18:00
├─ total_records_processed = 600
├─ total_records_failed = 0
└─ error_log = "" (empty — no errors)

Result to Operations:
└─ "EOD 2026-04-19 completed successfully. All 600 accounts processed. Ready for next business day (2026-04-22)."


└─────────────────────────────────────────────────────────────────┘
```

---

## Flow 4: MFA Login with TOTP Verification

### Scenario
**User:** CHECKER (approval officer)  
**Action:** Login with username/password + TOTP second factor  
**MFA Enrollment:** Previously enrolled (has Google Authenticator with FINVANTA secret)

---

### Execution

```
┌─────────────────────────────────────────────────────────────────┐
│ STEP 1: Password Authentication (First Factor)                  │
├─────────────────────────────────────────────────────────────────┤

User navigates to /login

LoginForm submitted:
{
  "username": "checker1",
  "password": "MySecurePassword@2026"
}

Spring Security AuthenticationProvider:
├─ Query: SELECT * FROM app_users WHERE username='checker1'
├─ Found: AppUser(id=2, mfa_enabled=true, mfa_secret='ABCD...XYZ')
├─ BCrypt verify password:
│  ├─ Stored hash: $2a$10$N9qo8uLO...
│  ├─ Provided password BCrypt matches? YES ✓
│  └─ Authentication successful
│
├─ Set SecurityContext:
│  ├─ principal = BranchAwareUserDetails (username='checker1', roles='CHECKER', branch='HQ001')
│  ├─ authenticated = true
│  └─ credentialsNonExpired? Check password_expiry_date
│
├─ MfaAuthenticationSuccessHandler delegates:
│  ├─ If mfa_enabled=true: Redirect to /mfa/verify
│  ├─ Set session.attribute("MFA_PENDING", true)
│  ├─ Session.attribute("USERNAME", "checker1")
│  └─ Forward to MFA verification form
│
└─ Session created: FINVANTA_SESSION=abc123...xyz (HttpOnly, Secure flags)


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 2: TOTP Verification Form (Second Factor)                  │
├─────────────────────────────────────────────────────────────────┤

User sees /mfa/verify form:
├─ Message: "Please verify your identity. Enter the 6-digit code from your authenticator app."
├─ Input field: [______ ] (6 digits)
├─ Submit button: "Verify"
├─ Display: "Remaining attempts: 5"
│
└─ User opens Google Authenticator app:
   ├─ FINVANTA (finvanta:checker1@bank.com) | 234156 (⏱ 12 seconds remaining)
   └─ User types: 234156


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 3: TOTP Verification (Backend)                             │
├─────────────────────────────────────────────────────────────────┤

MfaLoginController.verifyTotp(totpCode="234156")

MfaService.verifyLoginTotp(username="checker1", totpCode="234156"):
├─ Query: SELECT * FROM app_users WHERE username='checker1'
├─ appUser = AppUser(mfa_enabled=true, mfa_secret='ABCD...XYZ', last_totp_time_step=120)
│
├─ Verify TOTP per RFC 6238:
│  ├─ Current time: 2026-04-19 15:30:45 UTC
│  ├─ Current 30-sec time step: floor(1703085045 / 30) = 56769
│  ├─ Generate HMAC-SHA1(secret='ABCD...', counter=56769):
│  │  ├─ Dynamic binary: [dynamic_bits from HMAC]
│  │  ├─ TOTP code: (dynamic % 1,000,000) = 234156
│  │  └─ Expected code matches user's input ✓
│  │
│  ├─ Replay protection (per RFC 6238 §5.2):
│  │  ├─ Is current_time_step (56769) > last_time_step (120)? YES ✓
│  │  ├─ Time step is not in future (±1 tolerance window)? YES ✓
│  │  └─ Not a replay of previous code ✓
│  │
│  └─ Verification successful ✓
│
├─ Update AppUser:
│  ├─ last_totp_time_step = 56769 (prevent replay of "234156" within 30 sec)
│  ├─ last_login = 2026-04-19 15:30:45
│  └─ failed_login_attempts = 0 (reset counter)
│
├─ Audit trail:
│  └─ AuditLog: MFA_VERIFICATION_SUCCESS | user="checker1", status="ACTIVE", result="TOTP_VERIFIED"
│
└─ Return: true (MFA verified)


└─────────────────────────────────────────────────────────────────┘
                             ↓
┌─────────────────────────────────────────────────────────────────┐
│ STEP 4: Session Update & Dashboard Access                       │
├─────────────────────────────────────────────────────────────────┤

Session Updates:
├─ session.setAttribute("MFA_PENDING", false)
├─ session.setAttribute("MFA_VERIFIED", true) ← FLAG SET
├─ session.setAttribute("MFA_VERIFIED_TIME", systemTime)
└─ Session timeout reset to 15 minutes (production)

Spring Security Filter Chain:
├─ For each subsequent request:
│  ├─ Check session.MFA_VERIFIED == true? YES ✓
│  ├─ User has CHECKER role? YES ✓
│  └─ Allow request to proceed
│
└─ All requests within session now scoped to user's branch (HQ001)

Redirect to Dashboard:
├─ Location: /dashboard
├─ Embedded: <user branch info:  HQ001>
├─ Menu options per CHECKER role:
│  ├─ View pending approvals
│  ├─ Approve/reject applications
│  ├─ View audit trail
│  └─ (NO: Admin functions like user creation, EOD execution)
│
└─ Page rendered successfully


└─────────────────────────────────────────────────────────────────┘
```

**Failure Scenario (5 Failed Attempts):**
```
User enters wrong code "123456" five times:
├─ Attempt 1: Failed, remaining=4, status=MFA_VERIFICATION_FAILED
├─ Attempt 2: Failed, remaining=3
├─ Attempt 3: Failed, remaining=2
├─ Attempt 4: Failed, remaining=1
└─ Attempt 5: LOCKOUT
   ├─ session.invalidate()
   ├─ SecurityContextHolder.clearContext()
   ├─ Redirect to /login?mfa_locked=true
   ├─ AuditLog: MFA_LOCKOUT | user="checker1", reason="5_failed_attempts"
   └─ User must re-login with username + password (new authentication cycle)
```

---

## Conclusion

These four flows demonstrate how Finvanta CBS enforces the **10-step TransactionEngine pipeline**, **multi-tenant isolation**, **immutable audit trails**, and **Finacle/Temenos design patterns** across key banking operations.

Each transaction is:
1. **Logged** — Audit trail with before/after state
2. **Balanced** — Double-entry verified
3. **Scoped** — By tenant & branch
4. **Encrypted** — PII at rest, data in-transit over TLS
5. **Verified** — MFA for high-privilege users
6. **Reconciled** — EOD subledger vs GL check

This ensures **Tier-1 CBS reliability** and **RBI compliance** suitable for production deployment at Indian financial institutions.

---

**Last Updated:** April 2026  
**Maintained By:** Senior Core Banking Architect


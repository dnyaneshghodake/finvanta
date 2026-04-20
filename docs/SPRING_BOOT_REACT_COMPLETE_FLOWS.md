# COMPLETE SPRING BOOT + REACT CBS IMPLEMENTATION GUIDE
## End-to-End Flow from Login to Operations

**Document Version:** 1.0  
**Date:** April 19, 2026  
**Complete Package:** All 3 integration documents + operational flows

---

## 📦 COMPLETE DOCUMENTATION PACKAGE

You now have **3 comprehensive integration documents** + all **7 React architecture guides**:

### Backend Integration Documents

1. **SPRING_BOOT_REACT_INTEGRATION.md** (Architecture & Design)
   - CORS & Security Configuration
   - Enhanced API Response format
   - Complete Authentication Flow
   - Account Management API
   - Transfer Operations API
   - Loan Management API
   - Real-time WebSocket Integration
   - Error Handling & Response Mapping

2. **SPRING_BOOT_IMPLEMENTATION.md** (Ready-to-Apply Code)
   - Step-by-step code changes for Finvanta
   - Updated pom.xml dependencies
   - Complete Java classes (copy-paste ready)
   - Configuration files
   - Verification checklist
   - React usage examples

### React Frontend Documents (Created Earlier)

3. REACT_NEXTJS_ARCHITECTURE_DESIGN.md
4. REACT_NEXTJS_CODING_STANDARDS.md
5. REACT_NEXTJS_PROJECT_SETUP.md
6. REACT_NEXTJS_API_INTEGRATION.md
7. REACT_NEXTJS_TESTING_DEPLOYMENT.md
8. REACT_NEXTJS_DESIGN_SYSTEM.md
9. REACT_NEXTJS_QUICK_START.md

---

## 🔄 COMPLETE OPERATIONAL FLOWS

### FLOW 1: USER LOGIN FLOW

```
┌─────────────────────────────────────────────────────────────────┐
│                    LOGIN OPERATIONAL FLOW                        │
└─────────────────────────────────────────────────────────────────┘

STEP 1: REACT FRONTEND
=====================
  1. User enters email & password
  2. Form validation (local)
  3. POST /api/v1/auth/token
     Headers: X-Tenant-Id: bank-001
     Body: { email, password, tenantId }

STEP 2: SPRING BOOT BACKEND
==================
  1. Receive login request
  2. Validate TenantId header (TenantIdValidator filter)
  3. Find user in database
  4. Check account status:
     - Active? ✓
     - Locked? (check auto-unlock) ✓
     - Password expired? ✓
  5. Validate password (BCrypt)
  6. Record failed attempts if invalid
  7. Generate JWT tokens:
     - AccessToken (15 minutes)
     - RefreshToken (30 days, rotatable)
  8. Return ApiResponseV2:
     {
       status: "SUCCESS",
       data: {
         accessToken: "eyJhbG...",
         refreshToken: "eyJhbG...",
         tokenType: "Bearer",
         expiresIn: 900,
         refreshExpiresIn: 2592000,
         user: {
           id: "user-123",
           email: "john@bank.com",
           firstName: "John",
           lastName: "Doe",
           role: "CUSTOMER",
           permissions: ["VIEW_ACCOUNTS", "TRANSFER"],
           branchCode: "BRANCH-001",
           lastLogin: "2024-01-20T10:30:00"
         }
       },
       timestamp: "2024-01-20T10:30:00",
       requestId: "req-uuid-here"
     }

STEP 3: REACT FRONTEND
======================
  1. Receive response
  2. Store tokens:
     localStorage.setItem('accessToken', response.accessToken)
     localStorage.setItem('refreshToken', response.refreshToken)
  3. Store user info in Zustand store
  4. Connect to WebSocket:
     ws://localhost:8080/ws/cbs
     Headers: { token, tenantId }
  5. Redirect to /dashboard

STEP 4: SPRING BOOT - TOKEN VALIDATION
======================================
  1. On next API request:
     - Extract JWT from Authorization header: "Bearer eyJhbG..."
     - Validate signature & expiry
     - Extract claims: username, tenantId, role
     - Set in SecurityContext
     - Continue filter chain

STEP 5: REACT DASHBOARD
=======================
  1. Load dashboard page
  2. API calls with JWT:
     GET /api/v1/accounts
       Headers:
         Authorization: Bearer eyJhbG...
         X-Tenant-Id: bank-001
     ↓
     Response: {
       status: "SUCCESS",
       data: [
         { id: "acc-1", accountNumber: "1234567890", balance: 50000 },
         { id: "acc-2", accountNumber: "1234567891", balance: 100000 }
       ],
       page: 1,
       pageSize: 10,
       total: 2,
       totalPages: 1,
       hasNextPage: false
     }

STEP 6: WEBSOCKET CONNECTION (REAL-TIME)
=======================================
  WebSocket connects → subscribed to:
    /topic/accounts/acc-1/balance
    /topic/accounts/acc-1/transactions
    /topic/loans/loan-1/status

  When balance changes (backend process):
    1. TransactionEngine posts GL
    2. RealtimeUpdateService.publishBalanceUpdate()
    3. WebSocket message sent to all connected clients:
       {
         accountId: "acc-1",
         balance: 49995,
         availableBalance: 49990,
         reason: "TRANSACTION_POSTED",
         timestamp: "2024-01-20T10:35:00"
       }
    4. React updates UI in real-time (no page refresh!)

┌──────────────┐              ┌──────────────┐
│   Browser    │              │ Spring Boot  │
│   (React)    │              │   Backend    │
├──────────────┤              ├──────────────┤
│   Store JWT  │ ─POST JWT ──→│   Validate   │
│   Connect WS │ ←─Response ──│   Generate   │
│   Dashboard  │              │   Tokens     │
│              │ ─GET /api ──→│              │
│   Subscribe  │ ←─Accounts ──│   Database   │
│   /topic/*   │              │              │
│              │ ←WS Update ──│   Publish    │
│   Update UI  │   (balance)  │   Events     │
└──────────────┘              └──────────────┘
```

---

### FLOW 2: FUND TRANSFER OPERATIONAL FLOW

```
┌─────────────────────────────────────────────────────────────────┐
│               FUND TRANSFER OPERATIONAL FLOW                     │
└─────────────────────────────────────────────────────────────────┘

STEP 1: REACT TRANSFER FORM
===========================
  1. User fills transfer form:
     - From Account: account-1
     - To Account Number: 1234567890
     - Amount: ₹10,000
     - Description: "Salary transfer"
  
  2. Client-side validation:
     - Account number format
     - Amount > 0, < daily limit
     - No duplicates

STEP 2: INITIATE TRANSFER
========================
  POST /api/v1/transfers
  Headers:
    Authorization: Bearer JWT
    X-Tenant-Id: bank-001
  
  Body: {
    fromAccountId: "account-1",
    toAccountNumber: "1234567890",
    amount: 10000,
    description: "Salary transfer"
  }

STEP 3: BACKEND VALIDATION & LOCKING
===================================
  1. Validate tenant context (TenantIdValidator)
  2. Validate JWT & extract user (JwtAuthenticationFilter)
  3. Load from account:
     - Check exists
     - Check owner (multi-tenant isolation)
     - Check balance (≥ 10,000 + fees)
     - Check status (ACTIVE)
     - Check daily limit (not exceeded)
  4. Lock from account (pessimistic locking)
  5. Create Transfer entity:
     {
       id: "transfer-123",
       fromAccount: "account-1",
       toAccountNumber: "1234567890",
       amount: 10000,
       status: "INITIATED",
       createdAt: now,
       tenantId: "bank-001"
     }
  6. Request OTP → send to registered email/SMS
  7. Return to React:
     {
       status: "SUCCESS",
       data: {
         transferId: "transfer-123",
         amount: 10000,
         status: "PENDING_VERIFICATION",
         otpId: "otp-456",
         message: "OTP sent to registered email"
       },
       timestamp: now
     }

STEP 4: REACT SHOWS OTP VERIFICATION SCREEN
===========================================
  1. Display OTP input box
  2. Show masked email/SMS destination
  3. Show "Resend OTP" option (after 30s)

STEP 5: USER ENTERS OTP
=======================
  POST /api/v1/transfers/transfer-123/verify-otp
  Headers:
    Authorization: Bearer JWT
    X-Tenant-Id: bank-001
  
  Body: {
    otpCode: "123456"
  }

STEP 6: BACKEND VERIFICATION & GL POSTING
==========================================
  1. Validate OTP (hasn't expired, not wrong attempts)
  2. Mark OTP as used (prevent replay)
  3. **CRITICAL: Execute through TransactionEngine**
     TransactionEngine.execute({
       fromAccountId: "account-1",
       toAccountId: "account-2",    (resolve from number)
       amount: 10000,
       description: "Salary transfer",
       referenceId: "transfer-123"
     })
       ↓
     a) Lock both accounts
     b) Debit from account:
        - Save GL Entry (DEBIT)
        - Update account balance
     c) Credit to account:
        - Save GL Entry (CREDIT)
        - Update account balance
     d) Create Transaction record
     e) Set GL posting date
     f) Unlock accounts
     g) Publish WebSocket events:
        - Balance update for account-1 (creditor perspective)
        - Balance update for account-2 (debtor perspective)
        - Transaction posted event both sides
  4. Mark transfer as COMPLETED
  5. Return to React:
     {
       status: "SUCCESS",
       data: {
         transferId: "transfer-123",
         amount: 10000,
         status: "COMPLETED",
         referenceNumber: "REF-2024-001",
         completedAt: "2024-01-20T10:35:00"
       },
       message: "Transfer completed successfully"
     }

STEP 7: REACT SHOWS SUCCESS
============================
  1. Display confirmation:
     ✓ Transfer completed
       Reference: REF-2024-001
       Amount: ₹10,000
       Timestamp: 2024-01-20 10:35 AM
  2. Show "View Receipt" option (PDF)
  3. Show "Make Another Transfer" option

STEP 8: REAL-TIME UPDATES (WebSocket)
====================================
  FROM ACCOUNT (Creditor):
  ├─ Topic: /topic/accounts/account-1/transactions
  │  Message: {
  │    transactionId: "txn-1",
  │    amount: 10000,
  │    type: "DEBIT",
  │    status: "COMPLETED",
  │    description: "Salary transfer to 1234567890"
  │  }
  │
  ├─ Topic: /topic/accounts/account-1/balance
  │  Message: {
  │    accountId: "account-1",
  │    balance: 40000,        (50000 - 10000)
  │    availableBalance: 39990,
  │    reason: "TRANSACTION_POSTED"
  │  }

  TO ACCOUNT (Debtor):
  ├─ Topic: /topic/accounts/account-2/transactions
  │  Message: {
  │    transactionId: "txn-1",
  │    amount: 10000,
  │    type: "CREDIT",
  │    status: "COMPLETED",
  │    description: "Received from account-1"
  │  }
  │
  └─ Topic: /topic/accounts/account-2/balance
     Message: {
       accountId: "account-2",
       balance: 110000,       (100000 + 10000)
       availableBalance: 110000,
       reason: "TRANSACTION_POSTED"
     }

  React clients subscribed to these topics:
  ├─ Account-1 holder's browser updates balance in real-time
  ├─ Account-1 holder's mobile app sees new transaction
  ├─ Account-2 holder's browser shows new credit
  └─ Account-2 holder's mobile app notifies of deposit

┌──────────────────────┐         ┌───────────────────────┐
│   React Frontend     │         │  Spring Boot Backend  │
│   (Creditor View)    │         │                       │
├──────────────────────┤         ├───────────────────────┤
│  Transfer Form       │         │                       │
│  Enter details       │ ─POST ──→│ Validate & Lock       │
│                      │ ←─ACK ───│ Create Transfer       │
│  Show OTP screen     │         │ Request OTP           │
│  Enter OTP           │ ─POST ──→│ Verify OTP            │
│                      │ ←─ACK ───│ Execute GL Posting    │
│  SUCCESS screen      │         │ Publish WebSocket     │
│  Balance: 40,000     │ ←─WS ───│ Update: 40,000        │
└──────────────────────┘         └───────────────────────┘
```

---

### FLOW 3: LOAN ORIGINATION OPERATIONAL FLOW

```
┌─────────────────────────────────────────────────────────────────┐
│                LOAN ORIGINATION OPERATIONAL FLOW                 │
└─────────────────────────────────────────────────────────────────┘

STEP 1: REACT LOAN APPLICATION FORM
==================================
  1. Multi-step form:
     Step 1: Personal Details
     Step 2: Loan Details (amount, tenure, purpose)
     Step 3: Income & Employment
     Step 4: Documents Upload
     Step 5: Review & Submit

STEP 2: DOCUMENT UPLOAD
======================
  POST /api/v1/loans/applications/upload
  Body: FormData
    files: [KYC, Salary Slips, Bank Statements, ITR]
  
  Backend:
  1. Scan files (S3 or file system)
  2. Generate thumbnails for PDF preview
  3. Return:
     {
       documentId: "doc-123",
       filename: "salary_slip.pdf",
       size: 256000,
       uploadedAt: now,
       status: "UPLOADED"
     }

STEP 3: SUBMIT LOAN APPLICATION
===============================
  POST /api/v1/loans/applications
  Headers: Authorization, X-Tenant-Id
  Body: {
    loanAmount: 500000,
    tenure: 60,                    # months
    loanPurpose: "PERSONAL",
    documentIds: ["doc-123", "doc-124", ...],
    linkedAccountId: "account-1"   # disbursement account
  }

STEP 4: BACKEND LOAN ORIGINATION PROCESS
=======================================
  1. Create LoanApplication entity:
     {
       id: "loan-app-1",
       customerId: "customer-1",
       loanAmount: 500000,
       tenure: 60,
       status: "SUBMITTED",
       createdAt: now
     }

  2. Calculate EMI:
     Using LoanCalculationService:
     - Interest Rate: 10% p.a.
     - Processing Fee: 1% (5,000)
     - EMI = 10,640 per month
     - Total Interest = 138,400
     - Total Payable = 638,400

  3. Run AML/Sanction Check:
     - Query RBI CORE (sanctions list)
     - Query international sanctions database
     - If flagged → Go to MANUAL_REVIEW
     - If clear → Continue

  4. Create Loan Account (GL structure):
     For loan # LOAN-2024-001:
     DR: LOAN_RECEIVABLE (asset)    ₹500,000
     CR: DISBURSEMENT (clearing)    ₹500,000

  5. Set status based on AML:
     - AML Clear → APPROVED_PENDING_DISBURSAL
     - AML Flagged → MANUAL_REVIEW
     - Risk High → REJECTED

  6. Publish WebSocket to React:
     Topic: /topic/loans/loan-app-1/status
     Message: {
       loanId: "loan-app-1",
       oldStatus: "SUBMITTED",
       newStatus: "APPROVED_PENDING_DISBURSAL",
       timestamp: now
     }

  7. Return to React:
     {
       status: "SUCCESS",
       data: {
         loanApplicationId: "loan-app-1",
         loanAmount: 500000,
         approvedAmount: 500000,
         emi: 10640,
         tenure: 60,
         status: "APPROVED_PENDING_DISBURSAL",
         emiStartDate: "2024-03-20",
         documents: {
           kycVerified: true,
           pan: "verified",
           aml: "cleared"
         },
         disbursementInstructions: {
           accountId: "account-1",
           accountNumber: "123456789",
           instructions: "Click 'Disburse' to receive ₹500,000"
         }
       }
     }

STEP 5: REACT DISPLAYS APPROVAL
=============================
  1. Show loan approval details
  2. Show EMI breakdown table:
     Month  | Principal | Interest | EMI     | Balance
     1      | 8,473     | 4,167    | 10,640  | 491,527
     2      | 8,525     | 4,115    | 10,640  | 483,002
     ...
  3. Show disbursement button

STEP 6: USER CLICKS DISBURSE
===========================
  POST /api/v1/loans/loan-app-1/disburse
  Headers: Authorization, X-Tenant-Id
  Body: {} (no payload needed)

STEP 7: BACKEND DISBURSEMENT
==========================
  1. Lock both GL accounts (loan receivable & disbursement)
  2. Execute through TransactionEngine:
     DR: DISBURSEMENT (clearing)   ₹500,000
     CR: LOAN_ACCOUNT (liability)  ₹500,000
  3. Create LoanAccount:
     {
       id: "loan-1",
       loanApplicationId: "loan-app-1",
       loanAccountNumber: "LOAN-2024-001",
       outstandingAmount: 500000,
       status: "ACTIVE"
     }
  4. Schedule EMI postings (60 months):
     - Month 1: Post ₹10,640 debit
     - Month 2: Post ₹10,640 debit
     - ...
  5. Set first EMI due date: 2024-03-20
  6. Transfer ₹500,000 to linked account
  7. Publish WebSocket:
     /topic/loans/loan-1/status:
       { loanId: "loan-1", oldStatus: "PENDING_DISBURSAL", newStatus: "ACTIVE" }
     /topic/accounts/account-1/balance:
       { available: 400000 } (50000 + 500000 - transfer fees)

STEP 8: REACT SHOWS DISBURSEMENT CONFIRMATION
============================================
  ✓ Loan Disbursed Successfully
    Loan Account: LOAN-2024-001
    Amount: ₹500,000
    First EMI Date: March 20, 2024
    EMI Amount: ₹10,640
    View Agreement (PDF)

```

---

## 📊 REQUEST-RESPONSE EXAMPLES

### Example 1: Login Request/Response

**REQUEST:**
```bash
POST /api/v1/auth/token
Content-Type: application/json
X-Tenant-Id: bank-001

{
  "username": "john.doe@bank.com",
  "password": "SecurePass123!",
  "tenantId": "bank-001"
}
```

**RESPONSE (200 OK):**
```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "refreshExpiresIn": 2592000,
    "user": {
      "id": "user-123",
      "email": "john.doe@bank.com",
      "firstName": "John",
      "lastName": "Doe",
      "role": "CUSTOMER",
      "permissions": ["READ_ACCOUNTS", "INITIATE_TRANSFER", "VIEW_LOANS"],
      "branchCode": "BRANCH-001",
      "lastLogin": "2024-01-20T09:15:00"
    }
  },
  "requestId": "req-uuid-12345",
  "timestamp": "2024-01-20T10:30:00"
}
```

### Example 2: Accounts List Request/Response

**REQUEST:**
```bash
GET /api/v1/accounts?page=1&pageSize=10
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
X-Tenant-Id: bank-001
```

**RESPONSE (200 OK):**
```json
{
  "status": "SUCCESS",
  "data": [
    {
      "id": "acc-1",
      "accountNumber": "1234567890123456",
      "accountType": "SAVINGS",
      "balance": 50000,
      "availableBalance": 49500,
      "status": "ACTIVE",
      "currency": "INR",
      "createdAt": "2023-01-15T10:00:00"
    },
    {
      "id": "acc-2",
      "accountNumber": "1234567890123457",
      "accountType": "CURRENT",
      "balance": 100000,
      "availableBalance": 100000,
      "status": "ACTIVE",
      "currency": "INR",
      "createdAt": "2023-06-20T14:30:00"
    }
  ],
  "page": 1,
  "pageSize": 10,
  "total": 2,
  "totalPages": 1,
  "hasNextPage": false,
  "hasPreviousPage": false,
  "requestId": "req-uuid-12346",
  "timestamp": "2024-01-20T10:35:00"
}
```

### Example 3: Validation Error

**REQUEST:**
```bash
POST /api/v1/transfers
Authorization: Bearer JWT
X-Tenant-Id: bank-001
Content-Type: application/json

{
  "fromAccountId": "acc-1",
  "toAccountNumber": "invalid-format",
  "amount": -5000,
  "description": ""
}
```

**RESPONSE (400 Bad Request):**
```json
{
  "status": "VALIDATION_ERROR",
  "errorCode": "VALIDATION_FAILED",
  "message": "Validation failed",
  "fieldErrors": {
    "toAccountNumber": "Invalid account number format",
    "amount": "Amount must be positive",
    "description": "Description is required"
  },
  "requestId": "req-uuid-12347",
  "timestamp": "2024-01-20T10:36:00"
}
```

---

## ✅ IMPLEMENTATION CHECKLIST

### Backend (Spring Boot)

- [ ] Add WebSocket + CORS dependencies to pom.xml
- [ ] Create ApiResponseV2 with pagination
- [ ] Create WebSocketConfigurer
- [ ] Create RealtimeUpdateService
- [ ] Update AuthController with user info response
- [ ] Create AccountsApiControllerV2 for paginated accounts
- [ ] Create TransfersApiController
- [ ] Create LoansApiController
- [ ] Update TransactionEngine to publish WebSocket events
- [ ] Update application.properties for CORS
- [ ] Run mvn spotless:apply (format code)
- [ ] Test all endpoints with Postman/curl
- [ ] Verify WebSocket connections
- [ ] Load test with JMeter (100 concurrent users)

### Frontend (React)

- [ ] Setup Next.js project with TypeScript
- [ ] Configure Axios with JWT interceptors
- [ ] Create Zustand stores (auth, account, transfer, loan)
- [ ] Implement login page
- [ ] Implement accounts list with pagination
- [ ] Implement transfer workflow
- [ ] Implement loan application
- [ ] Setup WebSocket integration (Socket.io)
- [ ] Add real-time balance updates
- [ ] Add error handling with Sentry
- [ ] Setup testing (80%+ coverage)
- [ ] Setup CI/CD pipeline (GitHub Actions)
- [ ] Test on mobile (responsive design)

### Deployment

- [ ] Create Docker image for React
- [ ] Create Kubernetes manifests
- [ ] Setup ingress with SSL/TLS
- [ ] Configure monitoring (Prometheus + Grafana)
- [ ] Setup logging (ELK stack)
- [ ] Configure backup & disaster recovery
- [ ] Run security audit
- [ ] Load test in production-like environment
- [ ] Verify RBI compliance requirements

---

## 🎯 SUCCESS METRICS

**Backend Performance:**
- Login response time: <200ms (P99)
- Account list retrieval: <300ms (P99)
- Transfer approval: <500ms (P99)
- WebSocket real-time delivery: <100ms

**Frontend Performance:**
- Page load time: <1.2s (LCP)
- Login form submission: <500ms
- Account list render: <300ms
- Transfer success page: <200ms

**Availability:**
- Backend uptime: 99.99%
- Frontend CDN uptime: 99.99%
- WebSocket availability: 99.95%

**Security:**
- Zero high-severity CVEs
- OWASP Top 10 compliance
- RBI compliance verified
- JWT expiry: 15 minutes access, 30 days refresh

---

## 📚 DOCUMENT REFERENCE

All files are in: `D:\CBS\finvanta\docs\`

```
SPRING_BOOT_REACT_INTEGRATION.md        ← Architecture & APIs
SPRING_BOOT_IMPLEMENTATION.md           ← Ready-to-apply code
SPRING_BOOT_REACT_COMPLETE_FLOWS.md    ← This file
REACT_NEXTJS_ARCHITECTURE_DESIGN.md     ← React architecture
REACT_NEXTJS_CODING_STANDARDS.md        ← React code patterns
REACT_NEXTJS_PROJECT_SETUP.md           ← React project init
REACT_NEXTJS_API_INTEGRATION.md         ← React backend calls
REACT_NEXTJS_TESTING_DEPLOYMENT.md      ← React testing
REACT_NEXTJS_DESIGN_SYSTEM.md           ← UI components
```

---

## 🚀 RECOMMENDED IMPLEMENTATION ORDER

1. **Day 1-2:** Setup backend integration
   - Add WebSocket & CORS config
   - Create ApiResponseV2
   - Test with Postman

2. **Day 3-4:** Setup React frontend
   - Initialize Next.js project
   - Configure TypeScript & Tailwind
   - Setup API client

3. **Day 5-6:** Implement login flow
   - Backend: Enhanced auth endpoint
   - React: Login form + token storage
   - End-to-end test

4. **Day 7-8:** Implement accounts list
   - Backend: Paginated accounts endpoint
   - React: Accounts list with pagination
   - WebSocket balance updates

5. **Day 9-10:** Implement transfer flow
   - Backend: Transfer endpoints (initiate, verify, status)
   - React: Multi-step transfer form
   - OTP verification screen
   - Real-time balance update

6. **Day 11-12:** Implement loan origination
   - Backend: Loan endpoints
   - React: Loan application form
   - GL posting verification

7. **Day 13-14:** Testing & optimization
   - Unit tests (80%+ coverage)
   - E2E tests with Cypress
   - Performance optimization
   - Load testing

8. **Day 15+:** Deployment
   - Docker containerization
   - Kubernetes deployment
   - CI/CD pipeline setup
   - Monitoring & alerting

---

This complete package provides everything needed for production-grade CBS application!


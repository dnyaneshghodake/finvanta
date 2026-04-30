# Core Banking System — Frontend Documentation
**Version:** 1.0 | **Tier:** Tier-1 (Mission-Critical) | **Last Updated:** 2026-04-30   | **Audience:** Frontend Engineers, UI/UX Architects, Integration Teams
---## 1. Architecture
### 1.1 System Topology (3- Tier Browser → BFF → Backend)
```CLIENT LAYER: Web SPA (React) | Mobile Web (React- Native) | PWA Cache (Offline)          ↓BFF LAYER: Auth Interceptor | Tenant Router | Error Normalizer | Rate Limiter | Cache (Redis)          ↓CORE BANKING BACKEND: Auth Svc | Customer Svc | Txn Svc | Ledger Svc | Deposit Svc | Loan Svc | Teller Svc | Workflow Engine | PostgreSQL```
### 1.2 Layer Responsibilities
| Layer | Component | Responsibility | Non- Responsibility | Technology ||-----|---------|-------------|------------------|-----------|| UI | React/Next SPA | UI rendering, local state, call BFF only | Business validation, auth logic, tenant routing | React 18, Next.14 |
| BFF | API Gateway | Auth interception, tenant injection, error normalization, caching, rate- limiting | Direct DB access, complex business rules | Node.js, Express |
| Backend | Microservices | Business logic, DB ops, audit logging, workflow orchestration | HTTP handling, cookie mgmt | Java/ Spring, Go |
### 1.3 Security Model
#### 1.3.1 Authentication Architecture
| Component | Implementation | Details ||---------|--------------|---------| | Token Type | JWT (RS256 signed) | BFF validates; never exposed to JS |
| Token Storage | HttpOnly, Secure, SameSite= Strict | Browser automatic |
| Access Lifetime | 15- minute sliding window | Silent refresh |
| Refresh Lifetime | 8- hour hard limit | Re- auth required |
| Token Rotation | Rotate on each use | Revoke old |#### 1.3.2 Tenant Isolation Model
| Aspect | Implementation | Enforcement ||---------|---------------|-------------|| Header | X- Tenant-Id: {uuid} | BFF injects; UI sets in login only |
| Tenant Validation | Middleware validates tenant exists + active | 404 if unknown |
| Data Scoping | All queries scoped by tenant_ id | DB row- level security |
| Audit Trail | Tenant ID in all log lines | Compliance |#### 1.3.3 MFA Configuration
| Factor | Standard | Threshold | Fallback ||---------|----------|-----------|---------|| TOTP | RFC 6238 | Required for all users | N/ A |
| OTP SMS | Rate- limited, 3/ day | Secondary factor only | Primary without TOTP |
| Risk- Based MFA | Adaptive | Step- up on txn >$5,000 | Skip if low- risk |#### 1.3.4 Session Security Policies
| Policy | Value | Rationale ||---------|-------|----------|| Idle Timeout | 15 minutes | Industry standard |
| Hard Expiry | 8 hours | PSB/ compliance |
| Concurrent Sessions | Max 3 per user | Security vs. usability |
| Lockout Threshold | 5 failed → 30 min lock | Brute- force protection |#### 1.3.5 Security Headers (BFF)
```X- Content- Type- Options: nosniff
X- Frame- Options: DENYContent- Security- Policy: default- src 'self'Strict- Transport- Security: max- age=31536000```
---## 2. E2E Flows
### 2.1 Login Flow| Step | Actor | Action | UI → BFF → API | Response | Error Handling ||------|-------|--------|---------------|----------|---------------|| 1 | User | Enter credentials | POST /auth/ login {user, password, tenantId} | — | — |
| 2 | BFF | Validate tenant | Extract X- Tenant- Id | — | 404 if not found |
| 3 | BFF | Forward | Pass credentials | — | — |
| 4 | Auth Svc | Validate | BCrypt verify | — | 401 if mismatch |
| 5 | Auth Svc | Check status | Query account | — | 403 if locked |
| 6 | Auth Svc | Create session | Generate sessionToken; store Redis | — | — |
| 7 | BFF | Set cookie | HttpOnly, Secure, SameSite | — | — |
| 8 | Response | — | — | {sessionToken, requiresMfa: true, expiresIn: 300} | — |
| 9 | Error | — | — | — | 401/403/429 |**Success Response:** `{sessionToken: "...", requiresMfa: true, expiresIn: 300, mfaMethods: ["totp", "otp_ sms"]}`
### 2.2 MFA Flow| Step | Actor | Action | UI → BFF → API | Response | Error Handling ||------|-------|--------|---------------|----------|---------------|| 1 | User | Enter OTP | POST /auth/ mfa/ verify {sessionToken, otpCode} | — | — |
| 2 | BFF | Pass | Forward code | — | — |
| 3 | Auth Svc | Validate session | Redis lookup | — | 401 if expired |
| 4 | Auth Svc | Validate MFA | HMAC- SHA1 verify | — | — |
| 5 | Auth Svc | Track attempts | Increment; check threshold | — | 429 if >=3 |
| 6 | Auth Svc | Issue JWT | Generate tokens | — | — |
| 7 | BFF | Set cookies | HttpOnly both tokens | — | — |
| 8 | Response | — | — | {accessToken, refreshToken, expiresIn: 900} | — |
| 9 | Error | — | — | — | 401/429 |**Success Response:** `{accessToken: "...", refreshToken: "...", expiresIn: 900, tokenType: "Bearer"}`
### 2.3 Token Refresh Flow| Step | Actor | Action | UI → BFF → API | Response | Error Handling ||------|-------|--------|---------------|----------|---------------|| 1 | UI | Auto- trigger | POST /auth/ refresh (auto) | — | — |
| 2 | BFF | Read cookie | HttpOnly; extract ID | — | — |
| 3 | BFF | Forward | Pass refresh token | — | — |
| 4 | Auth Svc | Validate | Check not revoked | — | 401 if invalid |
| 5 | Auth Svc | Revoke old | Mark revoked Redis | — | Prevent replay |
| 6 | Auth Svc | Issue new | Generate fresh pair | — | — |
| 7 | BFF | Set cookies | Rotate both | — | — |
| 8 | Response | — | — | {accessToken, refreshToken, expiresIn: 900} | — |
| 9 | Error | — | — | — | 401/REAUTH |### 2.4 Transaction Flow (Maker → Checker → Post)| Step | Actor | Action | UI → BFF → API | Response | Error Handling ||------|-------|--------|---------------|----------|---------------|| 1 | UI (MAKER) | Submit | POST /transactions {payload} | — | — |
| 2 | BFF | Role check | Verify MAKER | — | 403 if not MAKER |
| 3 | BFF | Tenant | Inject X- Tenant- Id | — | — |
| 4 | BFF | Forward | Pass payload | — | — |
| 5 | Txn Svc | Validate | JSON Schema | — | 400 |
| 6 | Txn Svc | Duplicate | Hash check | — | 422 if dup |
| 7 | Txn Svc | Balance | Check available | — | 422 insufficient |
| 8 | Txn Svc | Create | Insert PENDING | — | — |
| 9 | Response | — | — | {txnId, status: PENDING} | — |
| 10 | UI (CHECKER) | Review | GET /transactions?status= PENDING | — | — |
| 11 | UI (CHECKER) | Approve | POST /{id}/ approve {comment} | — | — |
| 12 | BFF | Role check | Verify CHECKER | — | 403 |
| 13 | Txn Svc | Status | Must be PENDING | — | 409 |
| 14 | Txn Svc | Self- approval | maker != checker | — | 403 |
| 15 | Txn Svc | Post | Debit/ credit | — | — |
| 16 | Txn Svc | Ref no | TXN- YYYYMMDD- SEQ | — | — |
| 17 | Response | — | — | {txnId, status: POSTED, refNo} | — |
| 18 | Error | — | — | — | 403/409 |---## 3. Module Flows
### 3.1 Auth Module
| Aspect | Description | Implementation ||--------|-------------|---------------|| Module ID | AUTH | Authentication and session management |
| User Actions | Login, MFA enrollment/ verification, logout, password change |
| API Calls | POST /auth/ login, POST /auth/ mfa/ verify, DELETE /auth/ session, PUT /auth/ password |
| MAKER Role | Password change, MFA enroll, logout | All authenticated users |
| CHECKER Role | View audit, revoke sessions | ADMIN assignment |
| ADMIN Role | Reset password, force logout, MFA policy | System admin |
| Validation | Password: 12+ chars, complexity; MFA: TOTP enrollment; Session: max 3 concurrent |
| Approval | MFA self-approval; Password needs current; Revoke needs CHECKER/ ADMIN |
### 3.2 Customer Module
| Aspect | Description | Implementation ||--------|-------------|---------------|| Module ID | CIF | Customer Information File |
| User Actions | Create customer, update profile, update KYC, view, merge, close |
| API Calls | POST /customers, GET /{id}, PUT /{id}, POST /{id}/ kyc |
| MAKER Role | Create/ update, initiate KYC | Teller/ Relationship Mgr |
| CHECKER Role | Approve KYC upgrade, merge | Supervisor |
| ADMIN Role | Force close, bulk update, export | Ops Admin |
| Validation | CIF uniqueness; KYC docs: PDF/ JPG, <10MB; Address: 2+ docs |
| Approval | KYC 1→2: CHECKER; KYC 2→3: dual; Merge: ADMIN |**KYC Tiers:**| Tier | Requirements | Permissions | Approval ||------|-------------|-------------|----------|| KYC- 0 | Basic info | Onboarding only | None |
| KYC- 1 | ID + Address | Deposits <$10K | MAKER |
| KYC- 2 | + Financial docs | Full deposits, small loans | CHECKER |
| KYC- 3 | + EDD | All products | Dual CHECKER |
### 3.3 Deposit Module
| Aspect | Description | Implementation ||--------|-------------|---------------|| Module ID | DEP | Time Deposits, Fixed Deposits |
| User Actions | Open TD/ FD, view schedule, topup, premature close |
| API Calls | POST /deposits, POST /deposits/ fixed, DELETE /{id}, GET /{id}/ schedule |
| MAKER Role | Initiate open/ close | Teller |
| CHECKER Role | Approve early close, large deposit | Supervisor |
| ADMIN Role | Set rates, penalty rules | Product Admin |
| Validation | Min: $100; Max: $1M; Tenor: 7d-5y; Penalty: 1% <30d, 0.5% 31-90d |
| Approval | Early close >$50K: CHECKER; Large >$500K: CHECKER |
### 3.4 Loan Module
| Aspect | Description | Implementation ||--------|-------------|---------------|| Module ID | LON | Consumer Loans, Micro Loans |
| User Actions | Apply, calculate EMI, approve, disburse, repay, restructure, write off |
| API Calls | POST /loans, GET /calculator, POST /{id}/ approve, POST /{id}/ disburse, POST /{id}/ repay |
| MAKER Role | Create application, initiate disbursement | Loan Officer |
| CHECKER Role | Approve loan, authorize disbursement | Credit Committee |
| ADMIN Role | Override rate, write off, restructure | Credit Admin |
| Validation | Credit score >=650 (CL), >=600 (ML); DTI <40%; Collateral >$50K CL; Age 21-65 |
| Approval | <$10K: MAKER+ CHECKER; $10K-$50K: Officer+ Supervisor; >$50K: Multi- level |
### 3.5 Teller Module
| Aspect | Description | Implementation ||--------|-------------|---------------|| Module ID | TELL | Cash and Transfer Transactions |
| User Actions | Cash deposit/ withdrawal, transfer, demand draft |
| API Calls | POST /teller/ cash- deposit, POST /teller/ cash- withdrawal, POST /teller/ transfer |
| MAKER Role | Execute all teller transactions | Teller |
| CHECKER Role | Override large cash, vault adjustments | Supervisor |
| ADMIN Role | Configure denominations, limits | Branch Mgr |
| Validation | Denomination count; Dual control >$10K; Balance check; $50K limit |
| Approval | Cash >$10K: CHECKER; Vault: dual auth; Transfer >$100K: CHECKER |**Teller Limits:**| Transaction | Teller Limit | CHECKER Required ||-------------|-------------|----------------|| Cash Deposit | $10,000 | >$10K |
| Cash Withdrawal | $5,000 | >$5K |
| Transfer | $25,000 | >$25K |
| Demand Draft | $50,000 | >$50K |
### 3.6 Workflow Module
| Aspect | Description | Implementation ||--------|-------------|---------------|| Module ID | WKF | Generic Workflow Engine |
| User Actions | Submit, approve, reject, reassign, escalate |
| API Calls | POST /workflows, POST /{id}/ approve, POST /{id}/ reject, POST /{id}/ reassign |
| MAKER Role | Initiate any workflow | Authenticated user |
| CHECKER Role | Approve/ reject pending | Assigned approvers |
| ADMIN Role | Reassign, SLA, escalations, templates | Workflow Admin |
| Validation | Required fields per type; SLA per priority; Auto- escalate |
| Approval | Configurable multi- level; Parallel/ sequential; Escalation |
---## 4. API Contracts
### 4.1 Auth APIs
| Endpoint | Method | Headers | Request | Response | Errors | Role ||----------|--------|---------|---------|----------|--------|-------|| /auth/ login | POST | X- Tenant- Id | {user, password} | {sessionToken, requiresMfa, expiresIn} | 401/403/429 | PUBLIC |
| /auth/ mfa/ enroll | POST | Cookie: access | {method} | {qrCode, secret} | 401/400 | AUTHENTICATED |
| /auth/ mfa/ verify | POST | Cookie: session | {otpCode, method} | {accessToken, refreshToken, expiresIn} | 401/429 | AUTHENTICATED |
| /auth/ refresh | POST | Cookie: refresh | — | {accessToken, refreshToken, expiresIn} | 401/REAUTH | AUTHENTICATED |
| /auth/ logout | DELETE | Cookie: all | — | {success} | 500 | AUTHENTICATED |
| /auth/ password | PUT | Cookie: access | {currentPassword, newPassword} | {success} | 401/400 | AUTHENTICATED |
### 4.2 Context Bootstrap APIs
| Endpoint | Method | Headers | Request | Response | Errors | Role ||----------|--------|---------|---------|----------|--------|-------|| /context/ bootstrap | GET | Cookie: access | — | {user, roles, permissions, tenant} | 401 | AUTHENTICATED |
| /context/ features | GET | Cookie: access | — | {flags} | 401 | AUTHENTICATED |
| /context/ config | GET | Cookie: access | {keys} | {config} | 401 | AUTHENTICATED |**Bootstrap Response:**
```json{user: {id: "uuid", username: "john.doe", email: "john@bank.com"}, roles: ["MAKER", "TELLER"], permissions: ["txn:create"], tenant: {id: "uuid", code: "BANK01", name: "First National Bank"}}```
### 4.3 Core Transaction APIs
| Endpoint | Method | Headers | Request | Response | Errors | Role ||----------|--------|---------|---------|----------|--------|-------|| /transactions | POST | Cookie: access | {type, amount, currency, accountFrom, accountTo, narration} | {txnId, status, createdAt} | 400/403/422 | MAKER |
| /transactions | GET | Cookie: access | ?status&from&to&type | {data, total, page} | 401 | AUTHENTICATED |
| /transactions/{id} | GET | Cookie: access | — | {txnId, type, amount, status, createdAt, refNo} | 404 | MAKER/ CHECKER |
| /transactions/{id}/approve | POST | Cookie: access | {comment} | {txnId, status, refNo} | 403/409 | CHECKER |
| /transactions/{id}/reject | POST | Cookie: access | {reason} | {txnId, status} | 403/409 | CHECKER |
| /transactions/{id}/reverse | POST | Cookie: access | {reason, reversalType, amount?} | {txnId, status, reversedTxnId} | 403/409/422 | CHECKER |
| /accounts/{id}/balance | GET | Cookie: access | — | {available, ledger, hold} | 404 | AUTHENTICATED |
---## 5. Frontend Rules
### 5.1 Mandatory Rules
| Rule | Implementation | Rationale | Enforcement ||------|-------------|----------|-------------|| BFF Only | All calls through BFF; no direct backend | Security boundary | Linting; network config |
| HttpOnly Cookies | Never read/ write JWT programmatically | XSS protection | Code review; CSP |
| No JWT Exposure | Never in localStorage/ sessionStorage/ JS | Prevents theft | Security scan |
| Tenant Isolation | X- Tenant- Id by BFF only | Multi- tenant data | BFF middleware |
| Error Mapping | Map codes to user- safe messages | UX; security | Error handler |
| Auto- Refresh | Refresh on 401 OR 5- min timer; redirect on fail | Seamless session | Axios interceptor |
| Role Enforcement | UI hides unauthorized actions | Defense in depth | Permission hook |
| Sensitive Data | Never log passwords/ cards/ PINs | PCI- DSS | Linting |
| Audit Trail | Track click events, navigation | Compliance | Analytics |
### 5.2 Security Checklist
| Item | Status | Implementation ||------|--------|--------------|| No localStorage for tokens | MANDATORY | Cookies only |
| CSP headers | MANDATORY | default- src 'self' |
| X- Content- Type- Options | MANDATORY | BFF response |
| Input sanitization | MANDATORY | DOMPurify |
| HTTPS only | MANDATORY | HSTS header |
| CORS restricted | MANDATORY | Allowlist |
| Rate limiting | RECOMMENDED | Backoff |
| Session tracking | RECOMMENDED | Idle detection |
---## Appendix A: Error Codes| Code | HTTP | Description | Resolution ||------|------|------------|-------------|| AUTH_001 | 401 | Invalid credentials | Verify username/ password |
| AUTH_002 | 403 | Account locked | Wait for expiry |
| AUTH_003 | 429 | Too many attempts | Retry after countdown |
| MFA_001 | 401 | Invalid OTP | Re- enter; check sync |
| MFA_002 | 429 | MFA locked | Contact support |
| TXN_001 | 403 | MAKER role required | Assign role |
| TXN_002 | 403 | Self- approval not allowed | Different checker |
| TXN_003 | 409 | Already processed | Refresh list |
| TXN_004 | 422 | Insufficient funds | Add funds/ reduce |
---**Document Version:** 1.0   | **Last Updated:** 2026-04-30   | **Classification:** Internal - Tier- 1 Banking System   | **Owner:** Core Banking Architecture Team
# Core Banking System — Frontend Documentation

---
## 1. Architecture
### 1.1 System Diagram (Text)
```
[Browser] → [BFF Service] → [Core Banking Backend]
     │              │                    │
  SPA/React    API Gateway         Microservices
  Web/Mobile  Auth Interceptor   PostgreSQL
```
### 1.2 Responsibilities
| Layer       | Responsibility |
|-------------|----------------|
| **UI (SPA)** | Render only; no business logic; call BFF endpoints |
| **BFF**      | Auth intercept, tenant routing, data aggregation, error normalization |
| **Backend**  | Business logic, DB ops, audit logging, workflow orchestration |

### 1.3 Security Model
| Aspect     | Implementation |
|------------|----------------|
| **Auth**   | JWT in HttpOnly cookie (BFF validates, never exposes to UI) |
| **Tenant** | `X-Tenant-Id` header injected by BFF only |
| **Session**| 15-min sliding window; hard expiry 8h |
| **MFA**    | TOTP/OTP validated at Auth service before grant |

---
## 2. E2E Flows
### 2.1 Login Flow
| Step | Actor | Action |
|------|-------|--------|
| 1 | UI | `POST /auth/login` with `{user, password, tenant}` |
| 2 | BFF | Forward to Auth svc; set HttpOnly cookie |
| 3 | API | Validate creds → return `{sessionToken, requiresMfa}` |
| 4 | Response | `{ token, expiresIn, mfaRequired: bool }` |
| 5 | Error | `401` invalid creds; `403` account locked |

### 2.2 MFA Flow
| Step | Actor | Action |
|------|-------|--------|
| 1 | UI | `POST /auth/mfa/verify` with `{sessionToken, otpCode}` |
| 2 | BFF | Pass through; track attempt count |
| 3 | API | Validate TOTP → issue full JWT |
| 4 | Response | `{ accessToken, refreshToken, expiresIn }` |
| 5 | Error | `401` invalid OTP; `429` locked after 3 fails |

### 2.3 Token Refresh Flow
| Step | Actor | Action |
|------|-------|--------|
| 1 | UI | Auto-call on `401` or 5-min timer |
| 2 | BFF | Read HttpOnly refresh cookie; rotate tokens |
| 3 | API | Validate refresh token → issue new pair |
| 4 | Response | `{ accessToken, refreshToken, expiresIn }` |
| 5 | Error | `401` expired/revoked; `REDIRECT` to login |

### 2.4 Transaction Flow (Maker → Checker → Post)
| Step | Actor | Action |
|------|-------|--------|
| 1 | UI (MAKER) | `POST /transactions` with payload |
| 2 | BFF | Role check; forward to Txn service |
| 3 | API | Validate → create with status `PENDING` |
| 4 | Response | `{ txnId, status: PENDING }` |
| 5 | UI (CHECKER) | `POST /transactions/{id}/approve` |
| 6 | API | Verify checker role → update to `APPROVED` → post |
| 7 | Response | `{ txnId, status: POSTED, refNo }` |
| 8 | Error | `403` wrong role; `409` already processed |

---
## 3. Module Flows
### 3.1 Auth Module
| Aspect       | Detail |
|--------------|--------|
| **User Action** | Login, MFA, logout, password change |
| **API Call**    | `POST /auth/login`, `POST /auth/mfa/verify`, `DELETE /auth/session` |
| **Roles**      | Both roles same login; MFA required for all |
| **Validation** | Password policy (12+ chars); MFA enrolled check |
| **Approval**   | MFA is self-approval; lockout after 5 failures |

### 3.2 Customer Module
| Aspect       | Detail |
|--------------|--------|
| **User Action** | Create customer, update KYC, view profile |
| **API Call**    | `POST /customers`, `PUT /customers/{id}`, `GET /customers/{id}` |
| **MAKER**      | Create/update customer records |
| **CHECKER**    | Review and approve KYC changes |
| **ADMIN**      | Merge customers, close accounts |
| **Validation** | CIF uniqueness; KYC doc validation |
| **Approval**   | CHECKER approves KYC tier upgrade |

### 3.3 Deposit Module
| Aspect       | Detail |
|--------------|--------|
| **User Action** | Open deposit, close deposit, view maturity |
| **API Call**    | `POST /deposits`, `DELETE /deposits/{id}`, `GET /deposits/{id}/schedule` |
| **MAKER**      | Initiate deposit open/close |
| **CHECKER**   | Approve early closure (penalty check) |
| **Validation** | Min/max amount; tenor validation; maturity rules |
| **Approval**   | CHECKER required for early close > threshold |

### 3.4 Loan Module
| Aspect       | Detail |
|--------------|--------|
| **User Action** | Apply loan, disburse, repay, restructure |
| **API Call**    | `POST /loans`, `POST /loans/{id}/disburse`, `POST /loans/{id}/repay` |
| **MAKER**      | Create loan application, initiate disbursement |
| **CHECKER**   | Approve loan, authorize disbursement |
| **ADMIN**     | Override rate, write off, restructure |
| **Validation** | Credit score check, DTI ratio, collateral |
| **Approval**   | Multi-level: credit limit tiers determine CHECKER level |

### 3.5 Teller Module
| Aspect       | Detail |
|--------------|--------|
| **User Action** | Cash deposit, cash withdrawal, transfer |
| **API Call**    | `POST /teller/cash-deposit`, `POST /teller/cash-withdrawal`, `POST /teller/transfer` |
| **MAKER**      | All teller transactions |
| **CHECKER**   | Override for large cash txn (>$10K) |
| **Validation** | Denomination count, dual control for vault |
| **Approval**   | CHECKER override for large cash (>limit) |

### 3.6 Workflow Module
| Aspect       | Detail |
|--------------|--------|
| **User Action** | Submit for approval, approve, reject, reassign |
| **API Call**    | `POST /workflows`, `POST /workflows/{id}/approve`, `POST /workflows/{id}/reject` |
| **MAKER**      | Initiate workflow (any module) |
| **CHECKER**   | Approve/reject pending items |
| **ADMIN**     | Reassign tasks, configure SLA, escalations |
| **Validation** | Required fields per workflow type; SLA timeout |
| **Approval**   | Configurable multi-level approval chain |

---
## 4. API Contracts
### 4.1 Auth APIs
| Endpoint          | Method | Headers          | Request           | Response                   | Errors      | Role            |
|-------------------|--------|------------------|-------------------|----------------------------|-------------|-----------------|
| `/auth/login`    | POST   | X-Tenant-Id      | `{user, password}`| `{sessionToken, mfaRequired}`| 401, 403, 429| PUBLIC |
| `/auth/mfa/verify`| POST   | Cookie: session  | `{otpCode}`       | `{accessToken, refreshToken}`| 401, 429    | AUTHENTICATED  |
| `/auth/refresh`   | POST   | Cookie: refresh  | —                 | `{accessToken, refreshToken}`| 401, REAUTH | AUTHENTICATED  |
| `/auth/logout`    | DELETE | Cookie: all      | —                 | `{success}`                | 500         | AUTHENTICATED  |

### 4.2 Context Bootstrap
| Endpoint           | Method | Headers          | Request | Response                              | Errors | Role            |
|--------------------|--------|------------------|---------|---------------------------------------|--------|-----------------|
| `/context/bootstrap`| GET    | Cookie: access   | —       | `{user, roles, permissions, tenant}` | 401    | AUTHENTICATED  |
| `/context/features`| GET    | Cookie: access   | —       | `{flags[]}`                          | 401    | AUTHENTICATED  |

### 4.3 Core Transaction APIs
| Endpoint                   | Method | Headers          | Request                         | Response                     | Errors      | Role       |
|----------------------------|--------|------------------|---------------------------------|------------------------------|-------------|------------|
| `/transactions`           | POST   | Cookie: access   | `{type, amount, account, narration}`| `{txnId, status}`         | 400, 403, 422| MAKER     |
| `/transactions/{id}`       | GET    | Cookie: access   | —                               | `{txnId, type, amount, status, createdAt}`| 404 | MAKER/CHECKER|
| `/transactions/{id}/approve`| POST  | Cookie: access   | `{comment}`                     | `{txnId, status}`            | 403, 409    | CHECKER   |
| `/transactions/{id}/reject`| POST   | Cookie: access   | `{reason}`                      | `{txnId, status}`           | 403, 409    | CHECKER   |
| `/accounts/{id}/balance`   | GET    | Cookie: access   | —                               | `{available, ledger, hold}`| 404        | AUTHENTICATED|

---
## 5. Frontend Rules
| Rule                  | Implementation |
|-----------------------|----------------|
| **BFF Only**          | All API calls MUST route through BFF; no direct backend calls |
| **HttpOnly Cookies**   | Never read/write JWT programmatically; browser handles |
| **No JWT Exposure**   | Token never in localStorage, sessionStorage, or JS vars |
| **Tenant Isolation**  | `X-Tenant-Id` injected by BFF; UI sends tenant in login only |
| **Error Handling**   | Map BFF errors; show user-safe messages only |
| **Refresh Logic**    | Auto-refresh on `401` response; redirect to login on refresh fail |
| **Role Enforcement**| UI hides unauthorized actions; BFF enforces |

---
**Document Version:** 1.0  
**Last Updated:** 2026-04-30  
**Tier:** Core Banking System (Tier-1)

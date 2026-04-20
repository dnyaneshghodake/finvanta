# Finvanta CBS — Login API Contract for Frontend Integration

> **Version:** 3.0
> **Backend Commit:** `5266c0bc`
> **Compliance:** RBI IT Governance Direction 2023 §8.1, §8.3, §8.4, RBI Fair Practices Code 2023
> **Architecture:** Tier-1 CBS (Finacle/T24/Flexcube) — Auth ≠ Context ≠ Dashboard
>
> **What's new in v3.0:**
> - Response envelope upgraded with `meta` (apiVersion, correlationId, timestamp) and structured `error` (code, message, severity, action)
> - All error responses now carry severity (LOW/MEDIUM/HIGH/CRITICAL) and remediation action per RBI Fair Practices Code 2023
> - Password change endpoint moved to `/api/v1/auth/password/change` (JWT API chain)
> - JJWT upgraded to 0.12.6 (CVE-2024-31033 patched)
> - Legacy flat fields (`errorCode`, `message`, `timestamp`) retained for backward compatibility

---

## Table of Contents

1. [Overview](#1-overview)
2. [Base Configuration](#2-base-configuration)
3. [Required Headers](#3-required-headers)
4. [Endpoint 1: POST /api/v1/auth/token](#4-endpoint-1-post-apiv1authtoken)
5. [Endpoint 2: POST /api/v1/auth/mfa/verify](#5-endpoint-2-post-apiv1authmfaverify)
6. [Endpoint 3: POST /api/v1/auth/refresh](#6-endpoint-3-post-apiv1authrefresh)
7. [Endpoint 4: GET /api/v1/context/bootstrap](#7-endpoint-4-get-apiv1contextbootstrap)
8. [Complete Error Code Reference](#8-complete-error-code-reference)
9. [BFF Authentication State Machine](#9-bff-authentication-state-machine)
10. [Sequence Diagrams](#10-sequence-diagrams)
11. [JWT Token Structures](#11-jwt-token-structures)
12. [TypeScript Interfaces](#12-typescript-interfaces)
13. [CORS Configuration](#13-cors-configuration)
14. [Filter Chain Execution Order](#14-filter-chain-execution-order)
15. [Critical Frontend Rules](#15-critical-frontend-rules)

---

## 1. Overview

The Finvanta CBS login API implements a **three-phase post-login hydration** per Tier-1 CBS standards (Finacle/T24/Flexcube) and RBI IT Governance Direction 2023:

- **Phase 1 (Password):** `POST /api/v1/auth/token` — validates credentials, returns **identity + tokens ONLY** (non-MFA users) or a 428 MFA challenge (MFA-enrolled users). **No operational context.**
- **Phase 2 (MFA):** `POST /api/v1/auth/mfa/verify` — exchanges the challenge token + TOTP code for identity + tokens. Only reached if Phase 1 returns 428.
- **Phase 3 (Bootstrap):** `GET /api/v1/context/bootstrap` — fetches the full Controlled Operational Context (branch, business day, permissions, limits, config). Called AFTER login, BEFORE dashboard.
- **Token Refresh:** `POST /api/v1/auth/refresh` — rotates access + refresh tokens. Returns bare token pair.
- **Dashboard:** `GET /api/v1/dashboard/widgets/*` — independent widget endpoints fetched in parallel. See `API_REFERENCE.md` Section 17.

### Tier-1 CBS Golden Rule

> **Login returns ONLY identity + authorization + tokens.**
> **Operational context is ALWAYS fetched separately via dedicated APIs.**

Why:
1. Login must be ultra-fast (<300ms) — authentication only
2. Context data is heavy, aggregated, dynamic (changes on branch switch, day close)
3. Session payload must be minimal (security principle of least privilege)
4. Auth service and Context service scale independently
5. RBI expects clear separation of authentication and business logic modules

### BFF Hydration Flow

```
POST /api/v1/auth/token          → JWT + minimal identity (<300ms)
  ↓
Store JWT in BFF server memory (NEVER browser localStorage)
  ↓
GET /api/v1/context/bootstrap    → branch + businessDay + permissions + limits + config
  ↓
Hydrate BFF server-side session
  ↓
GET /api/v1/dashboard/widgets/*  → parallel widget fetch (skeleton-first)
  ↓
Render dashboard
```

### Security Invariants

- **No account enumeration:** All pre-password-check failures return identical `401 AUTH_FAILED "Invalid credentials"` — including user-not-found, wrong password on disabled accounts, and wrong password on locked accounts.
- **Timing-attack resistance:** User-not-found path executes a dummy BCrypt comparison to match the ~100ms latency of a real password check.
- **MFA counter fairness:** Failed login counter is reset to 0 after successful password validation before the MFA gate, so MFA-enrolled users get a full 5 OTP attempts regardless of prior password failures.
- **Single-use MFA challenges:** Challenge token `jti` is burned in the denylist on successful OTP verification. Failed OTP attempts do NOT burn the challenge — the user can retry with the same `challengeId`.

---

## 2. Base Configuration

| Setting | Value | Source |
|---|---|---|
| Base URL | `/api/v1/auth` | `AuthController.java:54` |
| Access Token Expiry | 15 minutes | `application.properties:25` |
| Refresh Token Expiry | 8 hours | `application.properties:26` |
| MFA Challenge Expiry | 5 minutes | `JwtTokenService.java:55` |
| Account Lockout Threshold | 5 failed attempts | `AppUser.java:34` |
| Auto-Unlock Duration | 30 minutes | `AppUser.java:36` |
| Password Rotation Period | 90 days | `AppUser.java:38` |
| JWT Signing Algorithm | HMAC-SHA256 | `JwtTokenService.java:69` |
| JWT Issuer | `finvanta-cbs` | `application.properties:27` |
| Password Hashing | BCrypt (12 rounds) | `SecurityConfig.java:460` |

---

## 3. Required Headers

### Every API Request

| Header | Required | Format | Validated By |
|---|---|---|---|
| `Content-Type` | YES | `application/json` | Spring MVC |
| `X-Tenant-Id` | **YES** | `[A-Za-z0-9_]{1,20}` | `TenantFilter.java:57` |
| `X-Correlation-Id` | Recommended | `[A-Za-z0-9-]{16,64}` | `CorrelationIdMdcFilter.java:63` |

### Protected Endpoints (after login)

| Header | Required | Format |
|---|---|---|
| `Authorization` | YES | `Bearer {accessToken}` |

### Header Validation Errors

**Missing Tenant ID — HTTP 400:**
```json
{
  "status": "ERROR",
  "errorCode": "MISSING_TENANT_ID",
  "message": "X-Tenant-Id header is required for /api/v1/** requests."
}
```

**Malformed Tenant ID — HTTP 400:**
```json
{
  "status": "ERROR",
  "errorCode": "INVALID_TENANT_ID",
  "message": "X-Tenant-Id header is malformed. Must match [A-Za-z0-9_]{1,20}."
}
```

**Correlation ID Behavior:**
- If `X-Correlation-Id` is absent or malformed → server generates a UUID v4
- Server always echoes `X-Correlation-Id` on the response header
- Use the **same** correlation ID across the password phase and MFA phase of a single login attempt

---

## 4. Endpoint 1: POST /api/v1/auth/token

**Purpose:** Password authentication. Returns **identity + tokens ONLY** on success, or 428 MFA challenge if user has MFA enabled. **No operational context** — that is fetched via `GET /api/v1/context/bootstrap` AFTER login.

### Request

```
POST /api/v1/auth/token HTTP/1.1
Content-Type: application/json
X-Tenant-Id: DEFAULT
X-Correlation-Id: 7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9
```

```json
{
  "username": "maker1",
  "password": "password123"
}
```

| Field | Type | Validation | Source |
|---|---|---|---|
| `username` | string | `@NotBlank` | `AuthController.java:664` |
| `password` | string | `@NotBlank` | `AuthController.java:666` |

### Response: Validation Failure (HTTP 400)

Returned when request body fields are missing or blank.

```json
{
  "status": "ERROR",
  "errorCode": "VALIDATION_FAILED",
  "message": "username: Username is required; password: Password is required",
  "timestamp": "2026-04-19T15:30:00",
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "username: Username is required; password: Password is required",
    "severity": "LOW",
    "action": "Correct the highlighted fields and resubmit"
  },
  "meta": {
    "apiVersion": "v1",
    "correlationId": "7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9",
    "timestamp": "2026-04-19T15:30:00"
  }
}
```

Source: `ApiExceptionHandler.java:83-86`

> **Envelope note:** All error responses now include `error` (structured detail with severity/action) and `meta` (apiVersion, correlationId). Legacy flat fields (`errorCode`, `message`, `timestamp`) are retained for backward compatibility.

### Response: Wrong Password / User Not Found (HTTP 401)

**Identical response for ALL pre-password-check failures** to prevent account enumeration per OWASP ASVS 2.5.2.

```json
{
  "status": "ERROR",
  "errorCode": "AUTH_FAILED",
  "message": "Invalid credentials",
  "timestamp": "2026-04-19T15:30:00",
  "error": {
    "code": "AUTH_FAILED",
    "message": "Invalid credentials"
  },
  "meta": {
    "apiVersion": "v1",
    "correlationId": "7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9",
    "timestamp": "2026-04-19T15:30:00"
  }
}
```

| Scenario | Backend Source | Counter Effect |
|---|---|---|
| User not found | `AuthController.java:125-138` | None (dummy BCrypt for timing parity) |
| Wrong password, active user | `AuthController.java:150-174` | `failedLoginAttempts +1` |
| Wrong password, disabled user | `AuthController.java:150-174` | No increment (line 155) |
| Wrong password, locked user (lockout not expired) | `AuthController.java:150-174` | No increment (already locked) |
| Wrong password, locked user (lockout expired) | `AuthController.java:143-147 → 150-174` | Reset to 0, then +1 |

**Frontend action:** Show generic "Invalid credentials" error. No differentiation between scenarios.

### Response: Account Disabled (HTTP 401)

Returned only when password is **correct** but account is disabled. Per RBI §8.1: account status is revealed only after proving password knowledge.

```json
{
  "status": "ERROR",
  "errorCode": "ACCOUNT_DISABLED",
  "message": "Account is disabled. Contact administrator.",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:183-197`

**Frontend action:** Show "Account disabled — contact administrator." Disable retry.

### Response: Account Locked (HTTP 401)

Returned only when password is **correct** but account is locked and auto-unlock duration has not elapsed.

```json
{
  "status": "ERROR",
  "errorCode": "ACCOUNT_LOCKED",
  "message": "Account locked. Try after 30 minutes",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:199-212`

**Frontend action:** Show "Account locked — try after 30 minutes" with optional countdown timer.

### Response: Password Expired (HTTP 401)

```json
{
  "status": "ERROR",
  "errorCode": "PASSWORD_EXPIRED",
  "message": "Password expired. Change via UI before API access",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:216-222`

**Frontend action:** Redirect to password change screen.

### Response: MFA Step-Up Required (HTTP 428)

Returned when password is correct and user has MFA enrolled. **No tokens are issued at this stage.**

```json
{
  "status": "ERROR",
  "errorCode": "MFA_REQUIRED",
  "message": "MFA step-up required to complete sign-in",
  "data": {
    "challengeId": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtYWtlcjEi...",
    "channel": "TOTP"
  },
  "timestamp": "2026-04-19T15:30:00",
  "error": {
    "code": "MFA_REQUIRED",
    "message": "MFA step-up required to complete sign-in"
  },
  "meta": {
    "apiVersion": "v1",
    "correlationId": "7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9",
    "timestamp": "2026-04-19T15:30:00"
  }
}
```

Source: `AuthController.java:231-248` → throws `MfaRequiredException` → `ApiExceptionHandler.java:38-46`

| Field | Description |
|---|---|
| `data.challengeId` | Signed JWT valid for 5 minutes, single-use on success |
| `data.channel` | Always `"TOTP"` (Google Authenticator / Microsoft Authenticator) |

**Backend side-effect:** `failedLoginAttempts` reset to 0 (line 239). MFA OTP failures start counting fresh.

**Frontend action:** Store `challengeId` in component state (NOT localStorage). Show OTP input modal. Submit to `POST /api/v1/auth/mfa/verify`.

### Response: Login Success — No MFA (HTTP 200)

**Slim `AuthResponse`** returned when password is correct and user does NOT have MFA enrolled. Contains **ONLY identity + tokens** — no branch, no business day, no permissions, no limits.

```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresAt": 1713520800,
    "user": {
      "userId": 2,
      "username": "maker1",
      "displayName": "Rajesh Kumar",
      "role": "MAKER",
      "branchCode": "MUM001",
      "authenticationLevel": "PASSWORD",
      "mfaEnabled": false
    }
  },
  "timestamp": "2026-04-19T15:30:00",
  "meta": {
    "apiVersion": "v1",
    "correlationId": "7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9",
    "timestamp": "2026-04-19T15:30:00"
  }
}
```

Source: `AuthController.java:251-252` → `issueTokens()` → `new AuthResponse(...)`

**AuthResponse Field Reference:**

| Field | Type | UI Usage |
|---|---|---|
| `accessToken` | string | BFF server-side session (NEVER browser) |
| `refreshToken` | string | BFF server-side session (NEVER browser) |
| `tokenType` | string | Always `"Bearer"` |
| `expiresAt` | long | Unix epoch seconds — schedule proactive refresh at `expiresAt - 60` |
| `user.userId` | long | Internal user ID |
| `user.username` | string | Display in header, audit correlation |
| `user.displayName` | string | Dashboard header greeting |
| `user.role` | string | `MAKER` / `CHECKER` / `ADMIN` / `AUDITOR` — determines which dashboard widgets to fetch |
| `user.branchCode` | string | Display in header bar |
| `user.authenticationLevel` | string | `PASSWORD` or `MFA` — display in session info |
| `user.mfaEnabled` | boolean | Show MFA status indicator |

### What is NOT in the login response (fetched via GET /api/v1/context/bootstrap):

| Data | Why Separate |
|---|---|
| Branch details (name, IFSC, type, zone) | Heavy, changes on branch switch |
| Business day (date, status, holiday) | Changes on day open/close/EOD |
| Permission matrix (permissionsByModule) | Heavy, role-dependent |
| Transaction limits | Branch + role dependent |
| Operational config (currency, precision) | Tenant-level, rarely changes |

**Frontend action:** Store tokens in BFF server-side session. Store `user` identity in React context. **Immediately call `GET /api/v1/context/bootstrap`** to hydrate the full operational context before rendering the dashboard. **Never store tokens in localStorage or sessionStorage.**

---

## 5. Endpoint 2: POST /api/v1/auth/mfa/verify

**Purpose:** Complete MFA step-up by exchanging the challenge token + TOTP code for identity + tokens. Same slim `AuthResponse` as `/auth/token` — **no operational context**.

### Request

```
POST /api/v1/auth/mfa/verify HTTP/1.1
Content-Type: application/json
X-Tenant-Id: DEFAULT
X-Correlation-Id: 7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9
```

**Note:** Use the **same** `X-Correlation-Id` as the original `/token` call to tie the two phases together in audit logs.

```json
{
  "challengeId": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJtYWtlcjEi...",
  "otp": "123456"
}
```

| Field | Type | Validation | Source |
|---|---|---|---|
| `challengeId` | string | `@NotBlank` | `AuthController.java:674` |
| `otp` | string | `@NotBlank` | `AuthController.java:676` |

### Response: Invalid/Expired Challenge (HTTP 401)

Challenge JWT expired (>5 min), tampered, wrong type, or malformed (null jti).

```json
{
  "status": "ERROR",
  "errorCode": "INVALID_MFA_CHALLENGE",
  "message": "MFA challenge invalid or expired. Please sign in again.",
  "timestamp": "2026-04-19T15:35:00"
}
```

Source: `AuthController.java:272-280` or `AuthController.java:292-298` (null jti) or `AuthController.java:325-331` (tenant mismatch)

**Frontend action:** Clear OTP modal, redirect to login page with "Session expired — please sign in again."

### Response: Challenge Already Used (HTTP 401)

The challenge `jti` was already burned by a prior successful OTP verification.

```json
{
  "status": "ERROR",
  "errorCode": "MFA_CHALLENGE_REUSED",
  "message": "This MFA challenge has already been used.",
  "timestamp": "2026-04-19T15:35:00"
}
```

Source: `AuthController.java:302-320` or `AuthController.java:398-405` (concurrent race via unique constraint)

**Frontend action:** Clear OTP modal, redirect to login page. Indicates either replay attack or double-submission.

### Response: Account Invalidated Between Phases (HTTP 401)

Admin disabled or locked the account after the password phase but before OTP submission.

```json
{
  "status": "ERROR",
  "errorCode": "ACCOUNT_INVALID",
  "message": "Account no longer valid",
  "timestamp": "2026-04-19T15:35:00"
}
```

Source: `AuthController.java:337-343`

**Frontend action:** Redirect to login with "Account no longer valid" message.

### Response: Wrong OTP Code (HTTP 401)

```json
{
  "status": "ERROR",
  "errorCode": "MFA_VERIFICATION_FAILED",
  "message": "Invalid OTP code",
  "timestamp": "2026-04-19T15:35:00"
}
```

Source: `AuthController.java:360-379`

**Backend side-effect:** `failedLoginAttempts +1` (starting from 0 because password phase reset it). Account locks at 5 failed OTP attempts.

**IMPORTANT:** The same `challengeId` can be reused for multiple OTP attempts. It is only burned on **successful** verification. The user gets up to 5 attempts before account lockout.

**Frontend action:** Show "Invalid OTP" error on the OTP modal. Allow retry with the **same `challengeId`**. Track attempt count client-side (server does not return remaining attempts).

### Response: OTP Correct — Login Success (HTTP 200)

Same slim `AuthResponse` as the non-MFA success, but with `authenticationLevel: "MFA"`:

```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJ...",
    "refreshToken": "eyJ...",
    "tokenType": "Bearer",
    "expiresAt": 1713520800,
    "user": {
      "userId": 2,
      "username": "maker1",
      "displayName": "Rajesh Kumar",
      "role": "MAKER",
      "branchCode": "MUM001",
      "authenticationLevel": "MFA",
      "mfaEnabled": true
    }
  },
  "timestamp": "2026-04-19T15:35:00"
}
```

Source: `AuthController.java:408-409` → `issueTokens()` → `new AuthResponse(...)`

**Frontend action:** Same as non-MFA — store tokens in BFF session, store identity in React context, then **immediately call `GET /api/v1/context/bootstrap`** to hydrate operational context before dashboard.

---

## 6. Endpoint 3: POST /api/v1/auth/refresh

**Purpose:** Exchange a valid refresh token for a new access + refresh token pair. Implements RFC 6749 §10.4 rotation — old refresh token is burned on use.

**NOTE:** Returns bare `TokenResponse`, NOT `AuthResponse`. Refresh is a token rotation, not a login — the BFF already has identity from the original login and COC from bootstrap.

### Request

```
POST /api/v1/auth/refresh HTTP/1.1
Content-Type: application/json
X-Tenant-Id: DEFAULT
X-Correlation-Id: a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

| Field | Type | Validation | Source |
|---|---|---|---|
| `refreshToken` | string | `@NotBlank` | `AuthController.java:670` |

### Response: Refresh Success (HTTP 200)

```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJ...(new)...",
    "refreshToken": "eyJ...(new)...",
    "tokenType": "Bearer",
    "expiresAt": 1713521700
  },
  "timestamp": "2026-04-19T15:45:00"
}
```

Source: `AuthController.java:649-658`

**Backend side-effect:** Old refresh token's `jti` persisted in `revoked_refresh_tokens` with reason `ROTATION`.

**Frontend action:** Replace **both** `accessToken` and `refreshToken` in BFF session. Old refresh token is permanently invalid.

### Response: Invalid/Expired Refresh Token (HTTP 401)

```json
{
  "status": "ERROR",
  "errorCode": "INVALID_REFRESH_TOKEN",
  "message": "Refresh token invalid or expired",
  "timestamp": "2026-04-19T23:30:00"
}
```

Source: `AuthController.java:494-500`

**Frontend action:** Clear session, redirect to login page.

### Response: Not a Refresh Token (HTTP 401)

Returned when an access token is mistakenly submitted instead of a refresh token.

```json
{
  "status": "ERROR",
  "errorCode": "NOT_REFRESH_TOKEN",
  "message": "Provided token is not a refresh token",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:503-510`

**Frontend action:** BFF bug — fix the client to submit the refresh token, not the access token.

### Response: Refresh Token Already Used — Theft Detection (HTTP 401)

**⚠️ CRITICAL SECURITY SIGNAL.** Per OWASP JWT Cheat Sheet: a reused refresh token means either the BFF has a bug (double-submitted) or the token was stolen and replayed.

```json
{
  "status": "ERROR",
  "errorCode": "REFRESH_TOKEN_REUSED",
  "message": "Refresh token has already been used. Re-authenticate via /api/v1/auth/token.",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:542-561` or `AuthController.java:606-635` (concurrent race)

**Frontend action:** **Immediately** clear all session state, redirect to login with "Session expired — please sign in again." Log this event client-side for SOC correlation.

### Response: Account Invalidated (HTTP 401)

Admin disabled/locked the account after the user's last login.

```json
{
  "status": "ERROR",
  "errorCode": "ACCOUNT_INVALID",
  "message": "Account no longer valid",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:570-576`

**Frontend action:** Clear session, redirect to login.

### Response: Legacy Refresh Token (HTTP 401)

Token issued before the rotation policy was deployed (missing `jti` claim).

```json
{
  "status": "ERROR",
  "errorCode": "LEGACY_REFRESH_TOKEN",
  "message": "Refresh token predates rotation policy. Please re-authenticate via /api/v1/auth/token.",
  "timestamp": "2026-04-19T15:30:00"
}
```

Source: `AuthController.java:521-539`

**Frontend action:** Clear session, redirect to login. Only occurs during migration.

---

## 7. Endpoint 4: GET /api/v1/context/bootstrap

**Purpose:** Fetch the full Controlled Operational Context (COC) for the authenticated user. Called AFTER login, BEFORE dashboard rendering. This is the "session activation" step per Finacle USER_SESSION / Temenos EB.USER.CONTEXT.

**Auth:** JWT Bearer required (not `permitAll`)

### Request

```
GET /api/v1/context/bootstrap HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
X-Tenant-Id: DEFAULT
X-Correlation-Id: 7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9
```

No request body.

### Response: Bootstrap Success (HTTP 200)

```json
{
  "status": "SUCCESS",
  "data": {
    "token": null,
    "user": {
      "userId": 2,
      "username": "maker1",
      "displayName": "Rajesh Kumar",
      "authenticationLevel": "SESSION",
      "loginTimestamp": "2026-04-19T15:30:00",
      "lastLoginTimestamp": "2026-04-18T09:15:00",
      "passwordExpiryDate": "2026-07-18",
      "mfaEnabled": true
    },
    "branch": {
      "branchId": 2,
      "branchCode": "MUM001",
      "branchName": "Mumbai Main Branch",
      "ifscCode": "FNVT0MUM001",
      "branchType": "BRANCH",
      "zoneCode": "WEST",
      "regionCode": "MH",
      "headOffice": false
    },
    "businessDay": {
      "businessDate": "2026-04-19",
      "dayStatus": "DAY_OPEN",
      "isHoliday": false,
      "previousBusinessDate": "2026-04-18",
      "nextBusinessDate": "2026-04-21"
    },
    "role": {
      "role": "MAKER",
      "makerCheckerRole": "MAKER",
      "permissionsByModule": {
        "LOAN": ["LOAN_CREATE", "LOAN_REPAYMENT", "LOAN_VIEW"],
        "DEPOSIT": ["DEPOSIT_OPEN", "DEPOSIT_DEPOSIT", "DEPOSIT_WITHDRAW", "DEPOSIT_TRANSFER", "DEPOSIT_VIEW"],
        "CUSTOMER": ["CUSTOMER_CREATE", "CUSTOMER_VIEW"],
        "CLEARING": ["CLEARING_INITIATE"]
      },
      "allowedModules": ["LOAN", "DEPOSIT", "CUSTOMER", "CLEARING"]
    },
    "limits": {
      "transactionLimits": [
        {
          "transactionType": "ALL",
          "channel": null,
          "perTransactionLimit": 1000000,
          "dailyAggregateLimit": 5000000
        }
      ]
    },
    "operationalConfig": {
      "baseCurrency": "INR",
      "decimalPrecision": 2,
      "roundingMode": "HALF_UP",
      "fiscalYearStartMonth": 4,
      "businessDayPolicy": "MON_TO_SAT"
    }
  },
  "timestamp": "2026-04-19T15:30:01"
}
```

Source: `ContextBootstrapController.java:72-77` → `SessionContextService.assembleFromSecurityContext()`

**Key differences from the old login response:**
- `token` is `null` — BFF already has tokens from login
- `user.authenticationLevel` is `"SESSION"` (not PASSWORD/MFA)
- All operational context sections (branch, businessDay, role, limits, config) are populated

**COC Field Reference:**

| Section | Key Fields | UI Usage |
|---|---|---|
| `user` | `displayName`, `lastLoginTimestamp`, `passwordExpiryDate` | Dashboard header, password expiry warning |
| `branch` | `branchCode`, `branchName`, `branchType`, `headOffice` | Header bar, branch context display |
| `businessDay` | `businessDate`, `dayStatus`, `isHoliday` | Transaction date, day-status banner |
| `role` | `role`, `makerCheckerRole`, `permissionsByModule`, `allowedModules` | Sidebar menu, button visibility, widget registry |
| `limits` | `transactionLimits[].perTransactionLimit`, `dailyAggregateLimit` | Amount field pre-validation, limit warnings |
| `operationalConfig` | `baseCurrency`, `decimalPrecision`, `roundingMode` | Currency formatting, amount input precision |

**`businessDay.dayStatus` → UI Behavior:**

| Value | UI Action |
|---|---|
| `DAY_OPEN` | Normal operations — all transaction buttons enabled |
| `EOD_RUNNING` | Banner "End-of-day in progress" — disable transaction buttons |
| `DAY_CLOSED` | Disable all posting buttons — read-only mode |
| `NOT_OPENED` | Banner "Business day not opened — contact administrator" |

**`role.makerCheckerRole` → UI Behavior:**

| Value | Derived From | UI Action |
|---|---|---|
| `MAKER` | Has CREATE/INITIATE, no APPROVE/VERIFY | Show "Submit for Approval", hide approval screens |
| `CHECKER` | Has APPROVE/VERIFY, no CREATE/INITIATE | Show approval screens, hide create screens |
| `BOTH` | Has both (ADMIN) | Show all (self-approval blocked server-side) |
| `VIEWER` | Has neither (AUDITOR) | Read-only — hide all action buttons |

**`branch` can be `null`:** HO/system users without branch assignment. When null, `businessDay` is also null. Show branch-selector for these users.

**When to re-fetch bootstrap:**
- After initial login (once)
- After branch switch (ADMIN switches operating branch)
- After token refresh (role/branch may have changed)
- On day status change event (DAY_OPEN → EOD_RUNNING → DAY_CLOSED)

---

## 8. Complete Error Code Reference

| errorCode | HTTP | Endpoint | Frontend Action |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | Any | Show field-level errors |
| `MISSING_TENANT_ID` | 400 | Any | BFF bug — fix header |
| `INVALID_TENANT_ID` | 400 | Any | BFF bug — fix header |
| `AUTH_FAILED` | 401 | `/token` | Show "Invalid credentials" |
| `ACCOUNT_DISABLED` | 401 | `/token` | Show "Account disabled — contact admin" |
| `ACCOUNT_LOCKED` | 401 | `/token` | Show "Locked — try after 30 min" |
| `PASSWORD_EXPIRED` | 401 | `/token` | Redirect to password change |
| `MFA_REQUIRED` | **428** | `/token` | Show OTP modal with `data.challengeId` |
| `INVALID_MFA_CHALLENGE` | 401 | `/mfa/verify` | Redirect to login ("session expired") |
| `MFA_CHALLENGE_REUSED` | 401 | `/mfa/verify` | Redirect to login |
| `ACCOUNT_INVALID` | 401 | `/mfa/verify`, `/refresh` | Redirect to login |
| `MFA_VERIFICATION_FAILED` | 401 | `/mfa/verify` | Show "Invalid OTP" — allow retry |
| `INVALID_REFRESH_TOKEN` | 401 | `/refresh` | Redirect to login |
| `NOT_REFRESH_TOKEN` | 401 | `/refresh` | BFF bug — fix client |
| `REFRESH_TOKEN_REUSED` | 401 | `/refresh` | **CRITICAL** — clear all state, redirect |
| `LEGACY_REFRESH_TOKEN` | 401 | `/refresh` | Redirect to login |
| `UNAUTHORIZED` | 401 | Any protected | Access token expired — trigger refresh |
| `INTERNAL_ERROR` | 500 | Any | Show "Unexpected error — contact support" |

---

## 9. BFF Authentication State Machine

```
┌─────────────┐
│  LOGGED_OUT  │
└──────┬──────┘
       │ User submits username + password
       ▼
POST /api/v1/auth/token
       │
       ├── 401 AUTH_FAILED ──────────────────► Show error, stay on login
       ├── 401 ACCOUNT_DISABLED ─────────────► Show disabled message
       ├── 401 ACCOUNT_LOCKED ───────────────► Show locked + countdown
       ├── 401 PASSWORD_EXPIRED ─────────────► Redirect to password change
       │
       ├── 428 MFA_REQUIRED ─────────────────► ┌──────────────┐
       │   (store challengeId)                  │  MFA_PENDING  │
       │                                        └──────┬───────┘
       │                                               │ User submits OTP
       │                                               ▼
       │                                   POST /api/v1/auth/mfa/verify
       │                                               │
       │   ┌── 401 MFA_VERIFICATION_FAILED ◄───────────┤ (retry same challengeId)
       │   │                                            │
       │   │   401 INVALID_MFA_CHALLENGE ──────────────►│ Redirect to login
       │   │   401 MFA_CHALLENGE_REUSED ───────────────►│ Redirect to login
       │   │   401 ACCOUNT_INVALID ────────────────────►│ Redirect to login
       │   │                                            │
       │   └───────────────────────────────────────────►│
       │                                               │
       │                                    200 + LoginSessionContext
       │                                               │
       ├── 200 + LoginSessionContext ◄─────────────────┘
       │
       ▼
┌─────────────┐
│  LOGGED_IN   │ ◄── Store tokens + COC in BFF session
└──────┬──────┘
       │
       │ Access token nearing expiry (14 min mark)
       ▼
POST /api/v1/auth/refresh
       │
       ├── 200 + TokenResponse ──────────────► Update tokens, stay logged in
       ├── 401 (any) ────────────────────────► Clear session, redirect to login
       │
       ▼
┌─────────────┐
│  LOGGED_OUT  │
└─────────────┘
```

---

## 10. Sequence Diagrams

### Flow A: Non-MFA Login (Password Only)

```
Browser              Next.js BFF              Spring Boot CBS
  │                      │                          │
  │  Submit login form   │                          │
  │─────────────────────►│                          │
  │                      │  POST /api/v1/auth/token  │
  │                      │  X-Tenant-Id: DEFAULT    │
  │                      │  X-Correlation-Id: uuid1 │
  │                      │  {username, password}     │
  │                      │─────────────────────────►│
  │                      │                          │ Validate password (BCrypt)
  │                      │                          │ Check active/locked/expired
  │                      │                          │ user.mfaEnabled = false
  │                      │                          │ Generate access + refresh JWT
  │                      │                          │ recordSuccessfulLogin()
  │                      │    200 + AuthResponse    │
  │                      │◄─────────────────────────│
  │                      │                          │
  │                      │ Store tokens in server   │
  │                      │ session (NOT browser)    │
  │                      │ Store COC in session     │
  │                      │                          │
  │  Set session cookie  │                          │
  │  Redirect /dashboard │                          │
  │◄─────────────────────│                          │
```

### Flow B: MFA Login (Password + TOTP)

```
Browser              Next.js BFF              Spring Boot CBS
  │                      │                          │
  │  Submit login form   │                          │
  │─────────────────────►│                          │
  │                      │  POST /api/v1/auth/token  │
  │                      │  X-Correlation-Id: uuid1 │
  │                      │  {username, password}     │
  │                      │─────────────────────────►│
  │                      │                          │ Validate password ✓
  │                      │                          │ user.mfaEnabled = true
  │                      │                          │ resetLoginAttempts() → 0
  │                      │                          │ Generate MFA challenge JWT
  │                      │                          │
  │                      │    428 MFA_REQUIRED      │
  │                      │    {challengeId, "TOTP"} │
  │                      │◄─────────────────────────│
  │                      │                          │
  │                      │ Store challengeId in     │
  │                      │ server-side temp state   │
  │                      │                          │
  │  Show OTP modal      │                          │
  │◄─────────────────────│                          │
  │                      │                          │
  │  Submit OTP: 123456  │                          │
  │─────────────────────►│                          │
  │                      │  POST /api/v1/auth/mfa/  │
  │                      │  verify                  │
  │                      │  X-Correlation-Id: uuid1 │ ◄── SAME correlation ID
  │                      │  {challengeId, otp}      │
  │                      │─────────────────────────►│
  │                      │                          │ Validate challenge JWT
  │                      │                          │ Check single-use (jti)
  │                      │                          │ Check tenant match
  │                      │                          │ Verify TOTP code
  │                      │                          │ Burn challenge (denylist jti)
  │                      │                          │ Generate access + refresh JWT
  │                      │                          │
  │                      │    200 + AuthResponse    │
  │                      │    authLevel: "MFA"      │
  │                      │◄─────────────────────────│
  │                      │                          │
  │                      │ Store tokens + COC       │
  │  Redirect /dashboard │                          │
  │◄─────────────────────│                          │
```

### Flow C: MFA Login — Wrong OTP Then Correct OTP

```
Browser              Next.js BFF              Spring Boot CBS
  │                      │                          │
  │  (password phase     │  428 MFA_REQUIRED        │
  │   already done)      │  challengeId: "eyJ..."   │
  │                      │                          │
  │  Submit OTP: 999999  │                          │
  │─────────────────────►│                          │
  │                      │  POST /api/v1/auth/      │
  │                      │  mfa/verify              │
  │                      │  {challengeId, "999999"} │
  │                      │─────────────────────────►│
  │                      │                          │ TOTP verify → false
  │                      │                          │ failedLoginAttempts: 0 → 1
  │                      │                          │
  │                      │    401 MFA_VERIFICATION  │
  │                      │    _FAILED               │
  │                      │◄─────────────────────────│
  │                      │                          │
  │  Show "Invalid OTP"  │                          │
  │  (attempt 1 of 5)    │                          │
  │◄─────────────────────│                          │
  │                      │                          │
  │  Submit OTP: 123456  │                          │
  │─────────────────────►│                          │
  │                      │  POST /api/v1/auth/      │
  │                      │  mfa/verify              │
  │                      │  {challengeId, "123456"} │ ◄── SAME challengeId
  │                      │─────────────────────────►│
  │                      │                          │ TOTP verify → true
  │                      │                          │ Burn challenge jti
  │                      │                          │ Issue tokens + COC
  │                      │                          │
  │                      │    200 + LoginSession    │
  │                      │◄─────────────────────────│
  │                      │                          │
  │  Redirect /dashboard │                          │
  │◄─────────────────────│                          │
```

### Flow D: Token Refresh (Proactive)

```
Browser              Next.js BFF              Spring Boot CBS
  │                      │                          │
  │  (14 min after login │                          │
  │   — proactive timer) │                          │
  │                      │  POST /v1/auth/refresh   │
  │                      │  {refreshToken: "eyJ.."} │
  │                      │─────────────────────────►│
  │                      │                          │ Validate refresh JWT
  │                      │                          │ Check jti not revoked
  │                      │                          │ Re-fetch user (active?)
  │                      │                          │ Revoke old jti (ROTATION)
  │                      │                          │ Generate new access+refresh
  │                      │                          │
  │                      │    200 + TokenResponse   │
  │                      │◄─────────────────────────│
  │                      │                          │
  │                      │ Replace tokens in session│
  │                      │ (COC unchanged)          │
  │  (seamless — user    │                          │
  │   notices nothing)   │                          │
```

---

## 11. JWT Token Structures

### Access Token Claims

```json
{
  "sub": "maker1",
  "iss": "finvanta-cbs",
  "tenant": "DEFAULT",
  "role": "MAKER",
  "branch": "MUM001",
  "type": "ACCESS",
  "iat": 1713520800,
  "exp": 1713521700
}
```

Source: `JwtTokenService.java:81-96` — Expiry: 15 minutes.

### Refresh Token Claims

```json
{
  "sub": "maker1",
  "iss": "finvanta-cbs",
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "tenant": "DEFAULT",
  "type": "REFRESH",
  "iat": 1713520800,
  "exp": 1713549600
}
```

Source: `JwtTokenService.java:110-127` — Expiry: 8 hours. **No `role` or `branch` claim** — refresh tokens cannot be used for authorization.

### MFA Challenge Token Claims

```json
{
  "sub": "maker1",
  "iss": "finvanta-cbs",
  "jti": "f1e2d3c4-b5a6-9807-fedc-ba0987654321",
  "tenant": "DEFAULT",
  "type": "MFA_CHALLENGE",
  "iat": 1713520800,
  "exp": 1713521100
}
```

Source: `JwtTokenService.java:197-208` — Expiry: 5 minutes. Single-use: `jti` burned in `revoked_refresh_tokens` on successful OTP verification.

---

## 12. TypeScript Interfaces

```typescript
// === API Envelope (Tier-1 CBS Grade — v3.0) ===

interface ApiResponse<T> {
  status: 'SUCCESS' | 'ERROR';
  data?: T;
  errorCode?: string;          // Legacy flat field — prefer error.code
  message?: string;            // Legacy flat field — prefer error.message
  timestamp: string;           // Legacy flat field — prefer meta.timestamp
  error?: ErrorDetail;         // NEW in v3.0: structured error with severity/action
  meta: ResponseMeta;          // NEW in v3.0: apiVersion, correlationId, timestamp
}

interface ErrorDetail {
  code: string;                // Same as errorCode (machine-readable)
  message: string;             // Same as top-level message (human-readable)
  severity?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  action?: string;             // User-facing remediation guidance per RBI Fair Practices
}

interface ResponseMeta {
  apiVersion: string;          // Always "v1" — incremented on breaking changes
  correlationId: string | null; // From X-Correlation-Id header (or server-generated UUID)
  timestamp: string;           // ISO 8601
}

// === Login Response (200 from /token or /mfa/verify) — identity + tokens ONLY ===

interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresAt: number;             // Unix epoch seconds
  user: {
    userId: number;
    username: string;
    displayName: string;
    role: 'MAKER' | 'CHECKER' | 'ADMIN' | 'AUDITOR';
    branchCode: string | null;
    authenticationLevel: 'PASSWORD' | 'MFA';
    mfaEnabled: boolean;
  };
}

// === Bootstrap Context (200 from GET /context/bootstrap) — operational context ===

interface LoginSessionContext {
  token: null;                   // BFF already has tokens from login
  user: {
    userId: number;
    username: string;
    displayName: string;
    authenticationLevel: 'SESSION';
    loginTimestamp: string;       // ISO 8601
    lastLoginTimestamp: string | null;
    passwordExpiryDate: string | null;  // YYYY-MM-DD
    mfaEnabled: boolean;
  };
  branch: {
    branchId: number;
    branchCode: string;
    branchName: string;
    ifscCode: string | null;
    branchType: 'HEAD_OFFICE' | 'ZONAL_OFFICE' | 'REGIONAL_OFFICE' | 'BRANCH';
    zoneCode: string | null;
    regionCode: string | null;
    headOffice: boolean;
  } | null;                      // null for HO/system users
  businessDay: {
    businessDate: string | null; // YYYY-MM-DD, null if NOT_OPENED
    dayStatus: 'DAY_OPEN' | 'EOD_RUNNING' | 'DAY_CLOSED' | 'NOT_OPENED';
    isHoliday: boolean;
    previousBusinessDate: string | null;
    nextBusinessDate: string | null;
  } | null;                      // null if no branch assigned
  role: {
    role: 'MAKER' | 'CHECKER' | 'ADMIN' | 'AUDITOR';
    makerCheckerRole: 'MAKER' | 'CHECKER' | 'BOTH' | 'VIEWER';
    permissionsByModule: Record<string, string[]>;
    allowedModules: string[];
  };
  limits: {
    transactionLimits: Array<{
      transactionType: string;
      channel: string | null;
      perTransactionLimit: number | null;
      dailyAggregateLimit: number | null;
    }>;
  };
  operationalConfig: {
    baseCurrency: string;        // 'INR'
    decimalPrecision: number;    // 2
    roundingMode: string;        // 'HALF_UP'
    fiscalYearStartMonth: number; // 4 (April)
    businessDayPolicy: string;   // 'MON_TO_SAT'
  };
}

// === MFA Challenge Response (428 from /token) ===

interface MfaChallengeResponse {
  status: 'ERROR';
  errorCode: 'MFA_REQUIRED';
  message: string;
  data: {
    challengeId: string;         // Opaque JWT — pass back to /mfa/verify
    channel: 'TOTP';
  };
  error: {                       // NEW in v3.0
    code: 'MFA_REQUIRED';
    message: string;
  };
  meta: ResponseMeta;            // NEW in v3.0
  timestamp: string;
}

// === Token Refresh Response (200 from /refresh) ===

interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresAt: number;
}

// === Error Response (401/400/500) ===

interface ErrorResponse {
  status: 'ERROR';
  errorCode: string;             // Legacy — prefer error.code
  message: string;               // Legacy — prefer error.message
  error: ErrorDetail;            // NEW in v3.0: severity + action
  meta: ResponseMeta;            // NEW in v3.0: apiVersion + correlationId
  timestamp: string;
}
```

---

## 13. CORS Configuration

Source: `SecurityConfig.java:392-438`

| Setting | Value |
|---|---|
| Allowed Origins | `spring.boot.app.cors.allowed-origins` (default: `http://localhost:3000`) |
| Allowed Methods | `GET, POST, PUT, DELETE, OPTIONS, PATCH` |
| Allowed Request Headers | `Content-Type, Authorization, X-Tenant-Id, X-Request-ID, X-Correlation-Id, X-Idempotency-Key, X-Branch-Code, X-Client-Version, Accept, Accept-Language, X-CSRF-Token` |
| Exposed Response Headers | `Authorization, X-Request-ID, X-Correlation-Id, X-Total-Count, X-Total-Pages, X-Current-Page, X-Page-Size` |
| Credentials | `false` (stateless JWT — no cookies sent cross-origin) |
| Preflight Max Age | 86400 seconds (24 hours) |

---

## 14. Filter Chain Execution Order

Every API request passes through these filters in order:

| Order | Filter | Purpose | Runs On |
|---|---|---|---|
| 0 | `CorrelationIdMdcFilter` | Read/generate `X-Correlation-Id`, set SLF4J MDC | All requests |
| 1 | `TenantFilter` | Resolve `X-Tenant-Id`, validate format, set `TenantContext` + MDC | All requests |
| — | `AuthRateLimitFilter` | Rate-limit `/v1/auth/**` endpoints | Auth endpoints only |
| — | `JwtAuthenticationFilter` | Validate `Authorization: Bearer` JWT, set `SecurityContext` | Protected endpoints only |

For `/v1/auth/token` and `/v1/auth/mfa/verify`: `JwtAuthenticationFilter` is **skipped** (these are `permitAll`). Only CorrelationId → Tenant → RateLimit → Controller.

---

## 15. Critical Frontend Rules

1. **Never store tokens in browser storage.** Access and refresh tokens live in the Next.js BFF server-side session only. The browser never sees raw JWTs.

2. **Login returns identity + tokens ONLY.** Do NOT expect branch, business day, permissions, limits, or config in the login response. Those come from `GET /api/v1/context/bootstrap` — call it immediately after login.

3. **Three-step post-login hydration.** `POST /auth/token` → store JWT → `GET /context/bootstrap` → hydrate session → `GET /dashboard/widgets/*` → render. Never skip the bootstrap step.

4. **Same `X-Correlation-Id` across MFA flow.** The `/token` call and the `/mfa/verify` call for the same login attempt must carry the same correlation ID so audit logs tie them together.

5. **Retry OTP with the same `challengeId`.** On `MFA_VERIFICATION_FAILED`, re-submit to `/mfa/verify` with the same `challengeId` and a new OTP. The challenge is only burned on success. Track attempt count client-side (max 5 before server locks the account).

6. **On any 401 from `/refresh`, redirect to login.** Do not retry. `REFRESH_TOKEN_REUSED` is a theft signal — clear everything immediately.

7. **Proactive refresh.** Schedule token refresh at `expiresAt - 60 seconds` (14 minutes after login). Do not wait for a 401 on a business endpoint — that creates poor UX. After refresh, consider re-fetching `/context/bootstrap` if role/branch may have changed.

8. **`businessDay.dayStatus` controls the entire UI** (from bootstrap context). If `NOT_OPENED`, disable all transaction buttons and show a banner. If `EOD_RUNNING`, show read-only mode. The server enforces this too, but the UI should prevent the user from even trying.

9. **`role.permissionsByModule` controls sidebar and buttons** (from bootstrap context). If `DEPOSIT` is not in `allowedModules`, hide the entire Deposits sidebar menu. Within a module, check individual permission codes: if `DEPOSIT_WITHDRAW` is absent, hide the Withdrawal button. The server re-validates via `CbsPermissionEvaluator` on every request — the UI is a convenience filter, not the security boundary.

10. **`limits.transactionLimits` is advisory only** (from bootstrap context). Use for client-side pre-validation (highlight amount fields, show warnings). The server re-validates via `TransactionLimitService` on every financial operation.

11. **`operationalConfig` controls all amount formatting** (from bootstrap context). Use `decimalPrecision` and `roundingMode` for every amount input and display. Do not hardcode `"INR"` or `2`.

12. **`user.passwordExpiryDate` — show warning banner** (from bootstrap context). If within 7 days of today, show a non-blocking banner: "Your password expires on {date}."

13. **`user.lastLoginTimestamp` — show on dashboard** (from bootstrap context). Per RBI IT Governance: display "Last login: {timestamp}" so the user can detect unauthorized access.

14. **`branch` can be `null`** (from bootstrap context). HO/system users without branch assignment. When null, `businessDay` is also null. Show a branch-selector dropdown for these users.

15. **Re-fetch bootstrap on context changes.** Call `GET /context/bootstrap` again after: branch switch, token refresh, day status change event. Do NOT cache bootstrap indefinitely.

16. **Dashboard widgets are independent.** Fetch each widget endpoint in parallel. A failed widget does NOT break the dashboard. Use skeleton placeholders. See `API_REFERENCE.md` Section 17 for widget registry and refresh intervals.

17. **Every API call must attach `X-Tenant-Id` and `X-Correlation-Id`.** Use an Axios/fetch interceptor in the BFF to inject these headers automatically from the server-side session.

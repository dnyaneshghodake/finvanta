# Finvanta CBS — Login & Authentication API Contract

**Base path:** `/api/v1/auth` (REST, stateless JWT)
**Auth chain:** `SecurityConfig.apiSecurityFilterChain` (`@Order(1)`), matches `/api/v1/**` and `/api/v2/**`
**Content-Type:** `application/json`
**Response envelope:** `ApiResponse<T>` — `{ status, data, errorCode, message, timestamp }`

> **Important:** there is NO `/api/v1/auth/login` endpoint. The token-issuance
> endpoint is `POST /api/v1/auth/token` (verified at `AuthController.java:93`).
> If the BFF exposes `/auth/login` to the React layer, that is a BFF-internal
> alias; the proxy must forward to the upstream `/auth/token`.

---

## 1. Tenant Header — Required on Every Request

Every call to `/api/v1/**` and `/api/v2/**` MUST carry the tenant header:

```
X-Tenant-Id: DEFAULT
```

Enforced by `TenantFilter` (`TenantFilter.java:152-163`). Missing or
malformed header → HTTP 400 with `errorCode = MISSING_TENANT_ID` or
`INVALID_TENANT_ID`. Validation regex: `^[A-Za-z0-9_]{1,20}$`.

Applies even to `/api/v1/auth/token` — the auth chain permits
unauthenticated access to `/auth/**`, but the tenant filter still
enforces the header on the servlet path before the security chain runs.

---

## 2. Endpoints

### 2.1 POST `/api/v1/auth/token`

Issue access + refresh tokens for a valid username/password pair.
PermitAll on the JWT chain. Source: `AuthController.java:93`.

**Request headers**
```
Content-Type: application/json
X-Tenant-Id: DEFAULT
```

**Request body** — `TokenRequest` (`AuthController.java:674`)
```json
{
  "username": "admin",
  "password": "finvanta123"
}
```

| Field | Constraint |
|---|---|
| `username` | `@NotBlank` |
| `password` | `@NotBlank` |

**Success — HTTP 200** — `ApiResponse<AuthResponse>` (`AuthController.java:712`)
```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresAt": 1735734567,
    "user": {
      "userId": 5,
      "username": "admin",
      "displayName": "Vikram Joshi (Branch Manager)",
      "role": "ADMIN",
      "branchCode": "HQ001",
      "authenticationLevel": "PASSWORD",
      "mfaEnabled": false
    }
  },
  "errorCode": null,
  "message": null,
  "timestamp": "2026-04-01T10:15:30Z"
}
```

`expiresAt` is Unix epoch **seconds** — computed for `/token` and
`/mfa/verify` at `AuthController.java:452-455` (inside `issueTokens`)
and for `/refresh` at `AuthController.java:665-668`.

**MFA Required — HTTP 428** — when `app_users.mfa_enabled = 1`, the controller throws `MfaRequiredException` (`AuthController.java:246`):
```json
{
  "status": "ERROR",
  "errorCode": "MFA_REQUIRED",
  "message": "MFA step-up required to complete sign-in",
  "data": {
    "challengeId": "eyJhbGciOiJIUzI1NiJ9...",
    "channel": "TOTP"
  }
}
```

**BFF flow on 428:** detect `errorCode = MFA_REQUIRED` → capture `challengeId` → prompt for 6-digit TOTP → POST `/api/v1/auth/mfa/verify`. Challenge is single-use, expires in 5 minutes.

**Error responses**

CBS applies anti-enumeration discipline (`AuthController.java:105-138`): every
pre-password failure (user-not-found, wrong-password, wrong-password-on-locked,
wrong-password-on-disabled) returns the same `AUTH_FAILED` code so an attacker
cannot distinguish account-existence from password-failure. Account-status
codes (`ACCOUNT_DISABLED`, `ACCOUNT_LOCKED`, `PASSWORD_EXPIRED`) are revealed
ONLY after the password has been validated.

| HTTP | errorCode | Cause |
|---|---|---|
| 401 | `AUTH_FAILED` | Invalid credentials (user-not-found, wrong password, or any wrong-password against a locked/disabled account — anti-enumeration) (`AuthController.java:137, 173`) |
| 401 | `ACCOUNT_DISABLED` | Correct password but `is_active = false` (`AuthController.java:195`) |
| 401 | `ACCOUNT_LOCKED` | Correct password but account locked from prior failed attempts; auto-unlock not yet eligible (`AuthController.java:208`) |
| 401 | `PASSWORD_EXPIRED` | Correct password but expired per rotation policy (`AuthController.java:220`) |
| 400 | `MISSING_TENANT_ID` | `X-Tenant-Id` absent |
| 400 | `INVALID_TENANT_ID` | `X-Tenant-Id` malformed |
| 400 | `VALIDATION_FAILED` | username/password blank |

> **Auto-unlock** (`AuthController.java:143-147`): if a user's lockout
> duration has elapsed, the next login attempt resets `failedLoginAttempts`
> automatically before the password check runs.

> **Failed-attempt counter on disabled accounts**
> (`AuthController.java:155-167`): even disabled accounts increment the
> failed-attempt counter for SOC forensics (credential-stuffing detection).

> **Login returns ONLY identity + tokens** (`AuthController.java:418-433`).
> Operational context (branch status, business day, permissions, limits,
> feature flags) MUST be fetched via `GET /api/v1/context/bootstrap`
> AFTER login. Login itself targets <300ms and carries no business data.

---

### 2.2 POST `/api/v1/auth/mfa/verify`

Verify TOTP for an MFA challenge. PermitAll on the auth chain.
Source: `AuthController.java:265`.

**Request body** — `MfaVerifyRequest` (`AuthController.java:684`)
```json
{
  "challengeId": "eyJhbGciOiJIUzI1NiJ9...",
  "otp": "123456"
}
```

**Success — HTTP 200** — same `AuthResponse` shape as `/token` (with `user` block).

The challenge token's `jti` is added to `RevokedRefreshToken` for single-use enforcement (`AuthController.java:301`).

**Error responses**

| HTTP | errorCode | Cause |
|---|---|---|
| 401 | `INVALID_MFA_CHALLENGE` | Challenge token invalid / expired (>5 min) / not an MFA-challenge type (`AuthController.java:277`) |
| 401 | `INVALID_MFA_CHALLENGE` | Challenge has no `jti` claim — malformed (`AuthController.java:296`) |
| 401 | `INVALID_MFA_CHALLENGE` | Challenge tenant ≠ request tenant — cross-tenant replay attempt (`AuthController.java:330`) |
| 401 | `MFA_CHALLENGE_REUSED` | Challenge `jti` already on the denylist (`AuthController.java:318`) |
| 401 | `MFA_CHALLENGE_REUSED` | Concurrent verify race — unique-constraint trip on the denylist (`AuthController.java:406`) |
| 401 | `ACCOUNT_INVALID` | User no longer active or now locked between challenge issuance and verify (`AuthController.java:342`) |
| 401 | `MFA_VERIFICATION_FAILED` | TOTP code mismatch — counter increments toward account lockout (`AuthController.java:356, 380`) |

> **MFA failure increments the lockout counter** (`AuthController.java:364-376`):
> an attacker who knows the password cannot brute-force OTPs across unlimited
> 5-minute challenge windows. After `AppUser.MAX_FAILED_ATTEMPTS = 5`
> (`AppUser.java:34`) the account is locked for
> `AppUser.LOCKOUT_DURATION_MINUTES = 30` minutes (`AppUser.java:36`),
> just as it would be on password failures. Auto-unlock applies after
> the 30-minute window elapses.

> **TOTP algorithm** — RFC 6238 (HMAC-SHA1) with replay protection per
> RFC 6238 §5.2: `MfaService.verifyLoginTotp` (`MfaService.java:198`)
> tracks the last verified time-step so an OTP consumed on one channel
> (JSP or API) cannot be replayed on the other.

---

### 2.3 POST `/api/v1/auth/refresh`

Rotate refresh token → new access + refresh pair. PermitAll on the auth chain.
Source: `AuthController.java:496`.

**Request body**
```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

**Success — HTTP 200** — `ApiResponse<TokenResponse>` (`AuthController.java:692`)
```json
{
  "status": "SUCCESS",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresAt": 1735735467
  }
}
```

> ⚠️ `/refresh` returns `TokenResponse` (NO `user` block). `/token` and
> `/mfa/verify` return `AuthResponse` (WITH `user` block). The BFF must
> handle both shapes.

**Rotation semantics** — `AuthController.java:594-602` revokes the presented refresh token's `jti` in the **same transaction** as the new pair is issued. Replay window = zero.

**Error responses**

| HTTP | errorCode | Cause |
|---|---|---|
| 401 | `INVALID_REFRESH_TOKEN` | Token signature invalid, expired, or unparseable (`AuthController.java:509`) |
| 401 | `NOT_REFRESH_TOKEN` | Caller presented an access (or other non-refresh) token (`AuthController.java:518`) |
| 401 | `LEGACY_REFRESH_TOKEN` | Token has no `jti` claim — predates the rotation policy; force re-auth (`AuthController.java:548`) |
| 401 | `REFRESH_TOKEN_REUSED` | Token's `jti` is already on the denylist — replay detection per OWASP JWT Cheat Sheet (`AuthController.java:570`) |
| 401 | `REFRESH_TOKEN_REUSED` | Concurrent rotation — unique-constraint trip on the denylist (`AuthController.java:644`) |
| 401 | `ACCOUNT_INVALID` | User no longer active or now locked (`AuthController.java:586`) |

> **Refresh-token reuse is the canonical theft-detection signal.** Per
> RBI / OWASP JWT Cheat Sheet, if a refresh token's `jti` is presented
> twice, exactly one of two things happened: (a) the legitimate client
> retried after a network glitch — but the new pair was already issued
> on the first attempt; or (b) the token was stolen and replayed. Both
> are surfaced as `REFRESH_TOKEN_REUSED` to force re-authentication and
> emit a `REFRESH_TOKEN_REUSED` audit event the SOC can pivot on.

---

### 2.4 GET `/api/v1/context/bootstrap`

Returns the operational envelope the BFF needs to render the dashboard.
**Authenticated** — requires `Authorization: Bearer <accessToken>`.
Source: `ContextBootstrapController.java:72`, guarded by
`@PreAuthorize("isAuthenticated()")`.

**Request headers**
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
X-Tenant-Id: DEFAULT
```

**Success — HTTP 200** — `ApiResponse<LoginSessionContext>` (`LoginSessionContext.java:39`).

The record carries 8 sub-blocks: `token`, `user`, `branch`, `businessDay`,
`role`, `limits`, `operationalConfig`, `featureFlags`.

> ⚠️ The example below is **illustrative**. The exact field values for
> `permissionsByModule`, `allowedModules`, `featureFlags`, and
> `operationalConfig` are populated at runtime by `SessionContextService`
> from the `permissions` / `role_permissions` / feature-flag config.
> BFF clients SHOULD treat unknown keys as forward-compatible additions
> and rely on the canonical TypeScript types generated from the
> `LoginSessionContext` record rather than pinning to this example.
> Sub-record shapes (field names + types) ARE stable — verified at
> `LoginSessionContext.java:64, 81, 104, 127`.

```json
{
  "status": "SUCCESS",
  "data": {
    "token": {
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
      "tokenType": "Bearer",
      "expiresAt": 1735734567
    },
    "user": {
      "userId": 5,
      "username": "admin",
      "displayName": "Vikram Joshi (Branch Manager)",
      "authenticationLevel": "PASSWORD",
      "loginTimestamp": "2026-04-01T10:00:00",
      "lastLoginTimestamp": "2026-03-31T17:42:11",
      "passwordExpiryDate": "2026-06-30",
      "mfaEnabled": false
    },
    "branch": {
      "branchId": 1,
      "branchCode": "HQ001",
      "branchName": "Headquarters",
      "ifscCode": "FNVA0000001",
      "branchType": "HEAD_OFFICE",
      "zoneCode": "ZONE-N",
      "regionCode": "REGION-NCR",
      "headOffice": true
    },
    "businessDay": {
      "businessDate": "2026-04-01",
      "dayStatus": "DAY_OPEN",
      "isHoliday": false,
      "previousBusinessDate": "2026-03-31",
      "nextBusinessDate": "2026-04-02"
    },
    "role": {
      "role": "ADMIN",
      "makerCheckerRole": "ADMIN",
      "permissionsByModule": {
        "DEPOSIT":  ["DEPOSIT_VIEW", "DEPOSIT_ACTIVATE", "DEPOSIT_REVERSE"],
        "LOAN":     ["LOAN_VIEW", "LOAN_VERIFY", "LOAN_APPROVE", "LOAN_DISBURSE"],
        "CUSTOMER": ["CUSTOMER_VIEW", "CUSTOMER_KYC_VERIFY"]
      },
      "allowedModules": ["DEPOSIT", "LOAN", "CUSTOMER", "TELLER", "ADMIN"]
    },
    "limits": {
      "transactionLimits": [
        {
          "transactionType": "ALL",
          "channel": null,
          "perTransactionLimit": 50000000.00,
          "dailyAggregateLimit": 200000000.00
        }
      ]
    },
    "operationalConfig": {
      "baseCurrency": "INR",
      "decimalPrecision": 2,
      "roundingMode": "HALF_UP",
      "fiscalYearStartMonth": 4,
      "businessDayPolicy": "BRANCH_SCOPED"
    },
    "featureFlags": [
      { "flagCode": "TELLER_MODULE", "category": "MODULE", "enabled": true },
      { "flagCode": "FICN_REGISTER", "category": "MODULE", "enabled": true }
    ]
  }
}
```

**Verified sub-record shapes** (`LoginSessionContext.java`):

| Sub-record | Fields | Source line |
|---|---|---|
| `TokenInfo` | `accessToken`, `refreshToken`, `tokenType`, `expiresAt` | `:51` |
| `UserContext` | `userId`, `username`, `displayName`, `authenticationLevel`, `loginTimestamp`, `lastLoginTimestamp`, `passwordExpiryDate`, `mfaEnabled` | `:64` |
| `BranchContext` | `branchId`, `branchCode`, `branchName`, `ifscCode`, `branchType`, `zoneCode`, `regionCode`, `headOffice` | `:81` |
| `BusinessDayContext` | `businessDate`, `dayStatus`, `isHoliday`, `previousBusinessDate`, `nextBusinessDate` | `:104` |
| `RoleContext` | `role`, `makerCheckerRole`, `permissionsByModule` (`Map<String, List<String>>`), `allowedModules` (`List<String>`) | `:127` |
| `LimitsContext` | `transactionLimits` (`List<TransactionLimitEntry>`) | `:144` |
| `TransactionLimitEntry` | `transactionType`, `channel`, `perTransactionLimit`, `dailyAggregateLimit` | `:147` |
| `OperationalConfig` | `baseCurrency`, `decimalPrecision`, `roundingMode`, `fiscalYearStartMonth`, `businessDayPolicy` | `:160` |
| `FeatureFlagEntry` | `flagCode`, `category`, `enabled` | `:182` |

Serialization uses `@JsonInclude(NON_NULL)` (`LoginSessionContext.java:38`), so any null sub-block is omitted from the wire payload entirely.

**ADMIN bootstrap is significantly larger than other roles.** ADMIN's
`permissionsByModule` covers every module (vs MAKER which is restricted
to its module set), and `limits` carries the highest INR values. If the
BFF stores this blob in a signed cookie, the ADMIN payload may exceed
the browser's 4 KB cookie limit — see Appendix A for the diagnostic.

---

## 3. JWT Claim Shape

Signed HMAC-SHA256 with `cbs.jwt.secret`. Source: `JwtTokenService.java`.

**Token expiry windows are configuration-driven.** The exact properties
read by `JwtTokenService` constructor (`JwtTokenService.java:62-74`) are:

| Property | Used as | Default citation |
|---|---|---|
| `cbs.jwt.secret` | HMAC-SHA256 signing key | (production: must be set via `CBS_JWT_SECRET` env var per Javadoc line 36) |
| `cbs.jwt.access-token-expiry-minutes` | Access token TTL | typically 15 (Javadoc line 29) |
| `cbs.jwt.refresh-token-expiry-hours` | Refresh token TTL | typically 8 (Javadoc line 30) |
| `cbs.jwt.issuer` | `iss` claim value | typically `finvanta-cbs` |

**MFA challenge expiry is hardcoded** at 5 minutes
(`JwtTokenService.java:55`: `MFA_CHALLENGE_EXPIRY_MS = 5L * 60L * 1000L`)
and is NOT externally configurable.

**BFF rule:** read `expiresAt` from every successful login / refresh
response (Unix epoch seconds, computed at `AuthController.java:452-455`)
and schedule refresh based on that value. Do NOT hardcode 15-minute
logic; production deployments may override the access-token TTL.

### 3.1 Access Token (`JwtTokenService.java:81`, TTL = `cbs.jwt.access-token-expiry-minutes`)

```json
{
  "sub":    "admin",
  "iss":    "finvanta-cbs",
  "tenant": "DEFAULT",
  "role":   "ADMIN",
  "branch": "HQ001",
  "type":   "ACCESS",
  "iat":    1735733667,
  "exp":    1735734567
}
```

### 3.2 Refresh Token (`JwtTokenService.java:110`, expires 8 hours, rotation-tracked)

```json
{
  "sub":    "admin",
  "iss":    "finvanta-cbs",
  "jti":    "uuid-v4",
  "tenant": "DEFAULT",
  "type":   "REFRESH",
  "iat":    1735733667,
  "exp":    1735762467
}
```

### 3.3 MFA Challenge Token (`JwtTokenService.java:197`, expires 5 minutes, single-use)

```json
{
  "sub":    "admin",
  "iss":    "finvanta-cbs",
  "jti":    "uuid-v4",
  "tenant": "DEFAULT",
  "type":   "MFA_CHALLENGE",
  "iat":    1735733667,
  "exp":    1735733967
}
```

The `JwtAuthenticationFilter` (line 93) explicitly REJECTS non-ACCESS tokens on `/api/v1/**` and `/api/v2/**` — refresh and challenge tokens cannot authorize API calls.

---

## 4. Roles & Responsibilities

`UserRole` enum: `TELLER`, `MAKER`, `CHECKER`, `ADMIN`, `AUDITOR`.

### 4.1 Responsibility Matrix

| Role | Responsibility | Per-txn limit | Daily limit |
|---|---|---|---|
| **TELLER** | Over-the-counter cash (deposit, withdrawal, till mgmt). Cannot WRITE_OFF / REVERSAL / DISBURSEMENT (zero-limit rows in `transaction_limits`). | INR 2L | INR 10L |
| **MAKER** | Loan applications, customer creation, transaction initiation | INR 10L | INR 50L |
| **CHECKER** | Verification, approval, rejection, KYC verify, disbursement, account activation, vault custodian | INR 50L | INR 2Cr |
| **ADMIN** | Branch management, EOD batch, system config, user mgmt, branch-switch (`/admin/switch-branch`); full ADMIN matcher list in §4.3; **exempt from branch isolation** (`BranchAccessValidator.java:82`) | INR 5Cr | INR 20Cr |
| **AUDITOR** | Read-only audit trail. NOT a transactional role; excluded from `getCurrentUserRole()` so it cannot bypass transaction limits. | (none) | (none) |

### 4.2 Role Hierarchy Resolution (`SecurityUtil.java:69-88`)

For users with multiple grants, **least-privilege wins**:

```java
List<String> leastPrivilegeFirst = List.of("TELLER", "MAKER", "CHECKER", "ADMIN");
return leastPrivilegeFirst.stream()
    .filter(userRoles::contains)
    .findFirst()
    .orElse(null);
```

| User has roles | `getCurrentUserRole()` returns | `isAdminRole()` |
|---|---|---|
| `[ADMIN]` only | `ADMIN` ✅ | `true` |
| `[MAKER]` only | `MAKER` ✅ | `false` |
| `[CHECKER]` only | `CHECKER` ✅ | `false` |
| `[TELLER]` only | `TELLER` ✅ | `false` |
| `[AUDITOR]` only | `null` (intentional — `hasRole("AUDITOR")` is the access-control check) | `false` |
| `[MAKER, ADMIN]` | `MAKER` (least-privilege wins) | `false` |
| `[CHECKER, ADMIN]` | `CHECKER` | `false` |
| `[TELLER, ADMIN]` | `TELLER` | `false` |

> ⚠️ **A user with both ADMIN and any transactional role resolves to the
> lower role.** `isAdminRole()` (`SecurityUtil.java:182-184`) delegates to
> `getCurrentUserRole()` and returns `false` for dual-role admins.

### 4.3 ADMIN-Specific Capabilities

| Capability | Path | URL matcher source |
|---|---|---|
| Branch isolation exemption (sees all branches) | (no path — runtime check) | `BranchAccessValidator.java:82` |
| Switch operational branch context | `/admin/switch-branch` | `SecurityConfig.java:257` |
| Catch-all `/admin/**` | `/admin/**` | `SecurityConfig.java:259` |
| Run EOD / batch jobs | `/batch/**` | `SecurityConfig.java:261` |
| Branch master add | `/branch/add` | `SecurityConfig.java:263` |
| Customer deactivate | `/customer/deactivate/**` | `SecurityConfig.java:269` |
| Branch master edit | `/branch/edit/**` | `SecurityConfig.java:283` |
| Calendar / day-control | `/calendar/**` | `SecurityConfig.java:285` |
| Loan write-off | `/loan/write-off/**` | `SecurityConfig.java:297` |
| Loan restructure / moratorium | `/loan/restructure/**`, `/loan/moratorium/**` | `SecurityConfig.java:325-328` |
| Transaction batch admin | `/batch/txn/**` | `SecurityConfig.java:329` |
| Product / limit / charge config | `/admin/products/**`, `/admin/limits/**`, `/admin/charges/**` | `SecurityConfig.java:331-336` |
| Deposit freeze / unfreeze | `/deposit/freeze/**`, `/deposit/unfreeze/**` | `SecurityConfig.java:357-360` |
| MFA enrollment / reset for other users | `/admin/mfa/**` | `SecurityConfig.java:422` |

All paths are guarded by `hasRole("ADMIN")` on the JSP chain
(`SecurityConfig.uiSecurityFilterChain`). REST API `@PreAuthorize`
annotations on individual controller methods are the second-level gate.

### 4.4 TELLER-Specific Capabilities

TELLER login is functionally identical to MAKER/CHECKER/ADMIN —
`POST /api/v1/auth/token` with the teller's username/password returns
the same `AuthResponse` shape, the JWT carries `role: "TELLER"`, and
`/api/v1/context/bootstrap` returns the operational envelope with
TELLER-specific limits and permissions. There is NO separate
`/auth/teller-token` or `/auth/teller-login` endpoint.

**TELLER capability matrix** (verified against
`SecurityConfig.uiSecurityFilterChain` and `apiSecurityFilterChain`):

| Capability | Path | URL matcher source | Roles allowed |
|---|---|---|---|
| Open till | `/teller/till/open` | `SecurityConfig.java:418` | TELLER, MAKER, ADMIN |
| Close till (request) | `/teller/till/close` | `SecurityConfig.java:418` | TELLER, MAKER, ADMIN |
| View own till | `/teller/till/me` (or JSP equivalent) | `SecurityConfig.java:418` | TELLER, MAKER, ADMIN (REST: also AUDITOR per `:149-150`) |
| Cash deposit | `/teller/cash-deposit` | `SecurityConfig.java:418` | TELLER, MAKER, ADMIN |
| Cash withdrawal | `/teller/cash-withdrawal` | `SecurityConfig.java:418` | TELLER, MAKER, ADMIN |
| Vault dashboard / BUY / SELL | `/teller/vault/**` | `SecurityConfig.java:410` | TELLER, MAKER, CHECKER, ADMIN |
| Pending till approvals (read) | `/teller/till/pending` | `SecurityConfig.java:381` | CHECKER, ADMIN — **NOT TELLER** |
| Approve / reject till open/close | `/teller/till/*/approve`, `/approve-close`, `/reject-open`, `/reject-close` | `SecurityConfig.java:383-391` | CHECKER, ADMIN — **NOT TELLER** |
| Vault open / close | `/teller/vault/open`, `/teller/vault/close` | `SecurityConfig.java:396-399` | CHECKER, ADMIN — **NOT TELLER** |
| Vault movement approve / reject | `/teller/vault/movement/*/approve`, `/reject` | `SecurityConfig.java:402-405` | CHECKER, ADMIN — **NOT TELLER** |

**Identical matrix on the v2 REST API**
(`SecurityConfig.apiSecurityFilterChain`):

| Path | Source | Roles |
|---|---|---|
| `/api/v2/teller/till/me` | `:149-150` | TELLER, MAKER, CHECKER, ADMIN, AUDITOR |
| `/api/v2/teller/vault/**` | `:146-147` | TELLER, MAKER, CHECKER, ADMIN |
| `/api/v2/teller/**` (catch-all: cash-deposit, cash-withdrawal, till/open, till/close) | `:156-157` | TELLER, MAKER, ADMIN |
| `/api/v2/teller/till/pending`, `/till/*/approve`, `/till/*/approve-close`, `/till/*/reject-open`, `/till/*/reject-close` | `:122-131` | CHECKER, ADMIN |
| `/api/v2/teller/vault/open`, `/vault/close`, `/vault/movements/pending`, `/vault/movement/*/approve`, `/vault/movement/*/reject` | `:132-141` | CHECKER, ADMIN |

**TELLER explicit exclusions** (zero-limit rows in `transaction_limits`
seed at `data.sql`):

| Transaction type | Per-txn limit | Daily limit |
|---|---|---|
| `WRITE_OFF` | 0 | 0 |
| `REVERSAL` | 0 | 0 |
| `DISBURSEMENT` | 0 | 0 |
| `ALL` (default) | INR 2L | INR 10L |

The zero-limit rows mean a TELLER cannot post these transaction types
even if a future code change accidentally widens role-based access —
`TransactionEngine` Step 6 enforces the limit and any non-zero amount
against a zero-limit row throws `TRANSACTION_LIMIT_EXCEEDED`.

**TELLER cannot perform supervisor actions** — every till/vault
approve/reject path requires CHECKER or ADMIN. The maker ≠ checker
guard inside the service layer further enforces that even an ADMIN
user cannot approve a till they themselves opened.

**TELLER login symptom check:** if TELLER login is reported as failing,
verify in this order:
1. Does the seed data include a TELLER user? (`teller1` per
   `src/main/resources/data.sql`.)
2. Is `transaction_limits` row for role `TELLER` / type `ALL` present?
   If absent, `TransactionLimitService.validateTransactionLimit` throws
   `NO_TRANSACTIONAL_ROLE` for the TELLER user on every cash post.
3. Does the JWT carry `role: "TELLER"`? (decode at jwt.io.) If it
   carries a different role, the user has multiple `app_user_roles`
   rows and `SecurityUtil.getCurrentUserRole` resolved to a different
   least-privilege role. Check `data.sql` user seeding.

---

## Appendix A — Diagnosing the Admin-Only Login Symptom

If admin login completes upstream (logs show `[BFF login] upstream=200` and
`[BFF login] bootstrap OK`) but the user is immediately redirected to
`/login?reason=session_expired`, the bug is on the **BFF session-cookie
write path**, not on CBS auth.

### Step 1 — Compare cookie sizes

In DevTools → Network → response of `POST /api/cbs/auth/login`:

| User | `set-cookie: fv_sid` size | Cookie present on next request? |
|---|---|---|
| `maker1` | ? | ? |
| `checker1` | ? | ? |
| `teller1` | ? | ? |
| `admin` | ? | ? |

If `admin`'s `set-cookie` is missing, truncated, or > 4 KB while
others fit, that's the smoking gun.

### Step 2 — Check the dual-role hypothesis

Decode the admin's JWT at jwt.io. The `role` claim should be `ADMIN`.

| Observed `role` claim | Diagnosis |
|---|---|
| `ADMIN` | Not the dual-role bug; check Step 1 (cookie size) and Step 3 (token shape) |
| `MAKER` / `CHECKER` / `TELLER` | The admin user has multiple `app_user_roles` rows; `SecurityUtil.getCurrentUserRole()` resolved to the lower role; `isAdminRole()` returns `false` and admin-only paths reject |

### Step 3 — BFF session-blob size

In the BFF login handler, log:
```javascript
console.log('bootstrap size:', JSON.stringify(bootstrap).length);
```

If MAKER ≈ 1-2 KB and ADMIN ≈ 4-6 KB, the cookie write is silently
dropped by the browser. Switch the BFF to a server-side session store
(Redis / DB) keyed by an opaque cookie ID, or compress the bootstrap
before storage.

### Step 4 — Server log

Look for the matching server log line on the failing admin request:
```
API <METHOD> <URI> → <status> <outcome> (<ms>ms) errorCode=<code> user=admin role=ADMIN branch=HQ001 tenant=DEFAULT
```

Pasted server log line localizes whether CBS rejected (status >= 400)
or accepted (status < 400) the request.

---

## Appendix B — Quick BFF Reference

```typescript
// Step 1 — login
POST /api/v1/auth/token
Headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': 'DEFAULT' }
Body: { username, password }
→ 200 { accessToken, refreshToken, user: { role, branchCode, ... } }
→ 428 with errorCode=MFA_REQUIRED → step 2 first

// Step 2 — MFA verify (only when step 1 returned 428)
POST /api/v1/auth/mfa/verify
Headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': 'DEFAULT' }
Body: { challengeId, otp }
→ 200 same shape as step 1 success

// Step 3 — bootstrap (after step 1/2 returns tokens)
GET /api/v1/context/bootstrap
Headers: { 'Authorization': 'Bearer <accessToken>', 'X-Tenant-Id': 'DEFAULT' }
→ 200 { token, user, branch, businessDay, role, limits, operationalConfig, featureFlags }

// Step 4 — refresh (when access token nears expiry)
POST /api/v1/auth/refresh
Headers: { 'Content-Type': 'application/json', 'X-Tenant-Id': 'DEFAULT' }
Body: { refreshToken }
→ 200 { accessToken, refreshToken, tokenType, expiresAt }  // NO user block
```

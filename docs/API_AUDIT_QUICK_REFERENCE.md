# FINVANTA CBS API AUDIT - QUICK REFERENCE CARD

**Print This & Post It** ⚡

---

## 5 CRITICAL CORRECTIONS

| # | Issue | Before | After | Who Fixes | Time |
|---|---|---|---|---|---|
| 1 | API Path | `/api/v1/` | `/v1/` | Frontend | 30 min |
| 2 | Response | `success: true` | `status: "SUCCESS"` | Frontend | 1 hr |
| 3 | MFA HTTP | 428 | 200 | Frontend | 1 hr |
| 4 | Idempotency | Required in docs | NOT implemented | Architect | Decision |
| 5 | CSRF | Required for API | NOT required (JWT) | Documentation | 30 min |

---

## THE FIX (3 FILES TO UPDATE)

### 1. React API Client
```typescript
// src/api/client.ts
const api = axios.create({
  baseURL: 'http://localhost:8080/v1'  // ← CHANGE THIS
});
```

### 2. BFF Reverse Proxy
```typescript
// middleware.ts or similar
proxy('/api/cbs/*', {
  target: 'http://localhost:8080/v1'  // ← CHANGE THIS
});
```

### 3. Response Handler
```typescript
// src/api/responseHandler.ts
if (response.data.status === 'SUCCESS') {  // ← WAS: success
  return response.data.data;
} else if (response.data.status === 'ERROR') {
  throw new ApiError(response.data.errorCode, response.data.message);  // ← WAS: error.code
}
```

---

## RESPONSE FORMAT (Before vs After)

### BEFORE (WRONG)
```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

### AFTER (CORRECT)
```json
{
  "status": "SUCCESS",
  "data": { ... },
  "errorCode": null,
  "message": "...",
  "timestamp": "2026-04-19T10:42:11"
}
```

---

## ERROR RESPONSE EXAMPLE

### BEFORE (WRONG)
```json
{
  "success": false,
  "data": null,
  "error": { "code": "NOT_FOUND", "message": "..." }
}
```

### AFTER (CORRECT)
```json
{
  "status": "ERROR",
  "data": null,
  "errorCode": "NOT_FOUND",
  "message": "Customer not found",
  "timestamp": "2026-04-19T10:42:11"
}
```

---

## MFA FLOW (Critical!)

### BEFORE (WRONG)
- Returns HTTP 428
- Body: `{ "error": { ... } }`
- Can't store challengeId in body

### AFTER (CORRECT)
- Returns HTTP **200** (not 428!)
- Body: `{ "status": "ERROR", "data": { "challengeId": "..." }, "errorCode": "MFA_REQUIRED" }`
- challengeId in response.data.data

```typescript
// MFA Page Code
const response = await login(user);

if (response.status === 200 && response.data.errorCode === 'MFA_REQUIRED') {
  // ← THIS is how to detect MFA required (not HTTP 428!)
  const { challengeId } = response.data.data;
  // Show MFA form
}
```

---

## VERIFICATION TEST (30 seconds)

```bash
# Test 1: Path works
curl http://localhost:8080/v1/customers/1 \
  -H "Authorization: Bearer {token}" \
  -H "X-Tenant-Id: DEFAULT"
# Should get 200, not 404

# Test 2: Response structure
curl http://localhost:8080/v1/auth/token \
  -H "X-Tenant-Id: DEFAULT" \
  -d '{"username":"test","password":"fail"}'
# Should return { "status": "ERROR", "errorCode": "INVALID_CREDENTIALS" }

# Test 3: MFA status code
# Expected: HTTP 200 with errorCode: "MFA_REQUIRED"
# NOT HTTP 428
```

---

## ALL 10 ENDPOINTS (UPDATED PATHS)

```
✅ POST   /v1/auth/token
✅ POST   /v1/auth/mfa/verify
✅ POST   /v1/auth/refresh

✅ POST   /v1/customers
✅ GET    /v1/customers/{id}
✅ PUT    /v1/customers/{id}
✅ POST   /v1/customers/{id}/verify-kyc
✅ POST   /v1/customers/{id}/deactivate

✅ POST   /v1/accounts/open
✅ POST   /v1/accounts/{acct}/activate
✅ POST   /v1/accounts/{acct}/freeze
✅ POST   /v1/accounts/{acct}/unfreeze
✅ POST   /v1/accounts/{acct}/close
✅ POST   /v1/accounts/transfer
✅ GET    /v1/accounts/{acct}
✅ GET    /v1/accounts/{acct}/balance

✅ POST   /v1/loan-applications
✅ GET    /v1/loan-applications/{id}
✅ POST   /v1/loan-applications/{id}/verify
✅ POST   /v1/loan-applications/{id}/approve
✅ POST   /v1/loan-applications/{id}/reject

✅ POST   /v1/loans/create-account/{appId}
✅ POST   /v1/loans/{acct}/disburse
✅ POST   /v1/loans/{acct}/repayment
✅ GET    /v1/loans/{acct}

✅ POST   /v1/charges/levy
✅ POST   /v1/charges/waive
✅ GET    /v1/charges/history/{acct}

✅ POST   /v1/clearing/outward
✅ POST   /v1/clearing/inward
✅ POST   /v1/clearing/settlement

✅ GET    /v1/gl/{code}
✅ GET    /v1/gl/trial-balance

✅ POST   /v1/fixed-deposits/book
✅ GET    /v1/fixed-deposits/{fd}

✅ POST   /v1/notifications/send
✅ GET    /v1/notifications/customer/{id}
```

---

## ERROR CODES (All Endpoints)

| Code | HTTP | Meaning |
|---|---|---|
| `UNAUTHORIZED` | 401 | Invalid JWT |
| `ACCOUNT_LOCKED` | 401 | Too many login failures |
| `INVALID_CREDENTIALS` | 401 | Wrong password |
| `MFA_REQUIRED` | **200** | MFA step-up needed |
| `INVALID_REQUEST` | 400 | Validation failed |
| `NOT_FOUND` | 404 | Resource not found |
| `INSUFFICIENT_FUNDS` | 400 | Balance too low |
| `LIMIT_EXCEEDED` | 400 | Limit exceeded |
| `RATE_LIMITED` | 429 | Too many requests |
| `INTERNAL_ERROR` | 500 | Server error - quote `Ref ID` |

---

## POSTMAN/INSOMNIA QUICK FIX

```
Find:    /api/v1/
Replace: /v1/
```

Do this on ALL requests in your collection.

---

## TIMELINE TO GO-LIVE

```
NOW        → Audit findings (this document)
Hour 1     → Fix React baseURL + BFF proxy (30 min)
Hour 2     → Fix response handlers + MFA page (1 hour, 30 min review)
Hour 3     → Update Postman/tests (30 min)
Hour 4     → Verification + sign-off (30 min)
READY      → GO-LIVE ✅
```

---

## DOCUMENTS TO READ

1. **This Card** ← You are here
2. **API_AUDIT_EXECUTIVE_SUMMARY.md** - 5 min read
3. **API_ENDPOINT_CATALOGUE_CORRECTED.md** - Full reference

---

## WHO TO CONTACT

- **Frontend Issue?** → Frontend Lead
- **Response Parsing?** → Frontend Dev
- **MFA Logic?** → Frontend Dev
- **Postman Tests?** → QA Lead
- **Spring Code?** → Not needed (it's correct!)

---

**Print this. Post it. Use it.** 📌

---

**Audit Date:** April 19, 2026  
**Status:** ⚠️ ACTION REQUIRED  
**Time to Fix:** 3-4 hours  
**Severity:** CRITICAL


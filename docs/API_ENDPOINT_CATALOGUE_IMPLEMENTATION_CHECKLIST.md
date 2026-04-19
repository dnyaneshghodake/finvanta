# API ENDPOINT CATALOGUE - IMPLEMENTATION CHECKLIST

**Status:** ⚠️ Critical Corrections Required  
**Priority:** HIGH - Block releases until fixed  
**Estimated Fix Time:** 4-6 hours

---

## CRITICAL PATH UPDATES (DO FIRST)

### 1. React API Client Config Update

**File:** `src/api/client.ts` or similar

**BEFORE:**
```typescript
const api = axios.create({
  baseURL: 'http://localhost:8080/api/v1'
});
```

**AFTER:**
```typescript
const api = axios.create({
  baseURL: 'http://localhost:8080/v1'  // CRITICAL: /api/v1 → /v1
});
```

**Impact:** ⚠️ **BLOCKS ALL API CALLS** if not fixed

---

### 2. BFF (Next.js) Routing Update

**File:** `middleware.ts` or reverseProxy rules

**BEFORE:**
```typescript
// Proxy /api/cbs/* to Spring
proxy('/api/cbs/*', {
  target: 'http://localhost:8080/api/v1'
});
```

**AFTER:**
```typescript
// Proxy /api/cbs/* to Spring /v1
proxy('/api/cbs/*', {
  target: 'http://localhost:8080/v1'  // CRITICAL: /api/v1 → /v1
});
```

**Impact:** ⚠️ **BLOCKS BFF→Spring communication** if not fixed

---

### 3. Response Handler Update

**File:** `src/api/responseHandler.ts` or similar

**BEFORE:**
```typescript
if (response.data.success) {
  return response.data.data;
} else {
  throw new ApiError(response.data.error.code, response.data.error.message);
}
```

**AFTER:**
```typescript
if (response.data.status === 'SUCCESS') {
  return response.data.data;
} else if (response.data.status === 'ERROR') {
  throw new ApiError(response.data.errorCode, response.data.message);
}
```

**Impact:** ⚠️ **ALL ERRORS broken** if not fixed

---

### 4. MFA Handler Update

**File:** `src/pages/login/mfa.tsx` or similar

**BEFORE:**
```typescript
if (response.status === 428) {
  // MFA required
}
```

**AFTER:**
```typescript
if (response.status === 200 && response.data.errorCode === 'MFA_REQUIRED') {
  // MFA required - extract challengeId from response.data.data
  const { challengeId } = response.data.data;
}
```

**CRITICAL:** MFA returns HTTP 200, not 428!

---

## DOCUMENTATION UPDATES (DO SECOND)

### 5. Update API Endpoint Catalogue

**REPLACE:** `API_ENDPOINT_CATALOGUE.md`  
**WITH:** `API_ENDPOINT_CATALOGUE_CORRECTED.md`

```bash
# Option A: Simple rename
mv API_ENDPOINT_CATALOGUE.md API_ENDPOINT_CATALOGUE_OLD.md
cp API_ENDPOINT_CATALOGUE_CORRECTED.md API_ENDPOINT_CATALOGUE.md

# Option B: Update in-place with all corrections
# See API_ENDPOINT_CATALOGUE_CORRECTED.md for reference
```

**Verify These Changes:**
- [ ] All paths changed `/api/v1/` → `/v1/`
- [ ] Response structure uses `"status": "SUCCESS" | "ERROR"`
- [ ] MFA shows HTTP 200 (not 428)
- [ ] Error codes in centralized table
- [ ] Timestamp field documented in all responses

---

## TESTING UPDATES (DO THIRD)

### 6. Update Postman/Insomnia Collections

**For each request:**

1. **Find:** Changes in path from `/api/v1/` to `/v1/`

   Before:
   ```
   POST http://localhost:8080/api/v1/auth/token
   ```

   After:
   ```
   POST http://localhost:8080/v1/auth/token
   ```

2. **URL Search/Replace:**
   ```
   Find:    /api/v1/
   Replace: /v1/
   ```

3. **Response assertions:** Update to check `status === "SUCCESS"`

---

### 7. Update Test Scripts

**Find all references to `/api/v1/` and replace with `/v1/`**

```javascript
// BEFORE
const response = await fetch('http://localhost:8080/api/v1/customers/1', {
  headers: { 'Authorization': `Bearer ${token}` }
});

// AFTER
const response = await fetch('http://localhost:8080/v1/customers/1', {
  headers: { 'Authorization': `Bearer ${token}` }
});
```

---

### 8. Update Load Test Scripts

**Tool:** JMeter / Locust / K6

**Changes needed:**
- Update all API endpoints from `/api/v1/` to `/v1/`
- Update response validation to check `status === "SUCCESS"`
- Update error code assertions
- No change to performance targets (should be same)

---

## VERIFICATION TESTS (DO LAST)

### Test Case 1: Login Flow
```bash
# Test MFA returns 200 not 428
curl -X POST http://localhost:8080/v1/auth/token \
  -H "X-Tenant-Id: DEFAULT" \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"test"}'

# Verify response structure
# { "status": "ERROR", "errorCode": "MFA_REQUIRED", "data": { "challengeId": "..." } }
# Should be HTTP 200 (not 428!)
```

### Test Case 2: Customer Creation
```bash
curl -X POST http://localhost:8080/v1/customers \
  -H "Authorization: Bearer {token}" \
  -H "X-Tenant-Id: DEFAULT" \
  -H "Content-Type: application/json" \
  -d '{...}'

# Verify response has these TOP-LEVEL fields:
# - status: "SUCCESS" (not statusCode or success)
# - data: {...}
# - errorCode: null
# - message: "..."
# - timestamp: "2026-04-19T..."
```

### Test Case 3: Error Response
```bash
# Request invalid customer
curl -X GET http://localhost:8080/v1/customers/99999 \
  -H "Authorization: Bearer {token}" \
  -H "X-Tenant-Id: DEFAULT"

# Verify error structure:
# {
#   "status": "ERROR",
#   "data": null,
#   "errorCode": "NOT_FOUND",
#   "message": "Customer not found",
#   "timestamp": "..."
# }
```

---

## AFFECTED SYSTEMS CHECKLIST

- [ ] React Frontend - API client baseURL
- [ ] Next.js BFF - Reverse proxy rules
- [ ] Response handlers - error structure parsing
- [ ] MFA page - HTTP 200 vs 428 handling
- [ ] Postman/Insomnia - All endpoints updated
- [ ] Jest tests - Mock responses updated
- [ ] E2E tests (Cypress/Playwright) - All paths updated
- [ ] Load testing (JMeter) - All endpoints updated
- [ ] Alerts/Monitoring - API endpoint list updated
- [ ] CI/CD pipelines - Test URL lists updated
- [ ] API documentation (Swagger/OpenAPI) - All paths updated
- [ ] Mobile app (if exists) - API client updated

---

## ROLLBACK PLAN (IF NEEDED)

**In case of critical issues:**

1. Keep original API_ENDPOINT_CATALOGUE.md as backup
2. If Spring paths haven't changed (they haven't), revert frontend changes:
   - Undo React baseURL change
   - Undo BFF proxy change
   - Undo response handler change

**However:** Code is correct, documentation was wrong. Don't revert Spring code.

---

## SIGN-OFF PROCEDURE

Before going live, verify:

- [ ] **All paths changed** from `/api/v1/` to `/v1/`
- [ ] **Response structure** uses `status` field (not `success`)
- [ ] **MFA handling** expects HTTP 200 with errorCode
- [ ] **Postman tests** pass with new paths
- [ ] **E2E tests** pass with new structure
- [ ] **Manual testing** of login flow works
- [ ] **Load test** passes with new endpoints
- [ ] **Production** config uses correct base URL

**Release Blockers:**
- ❌ Any hardcoded `/api/v1/` paths remaining in code
- ❌ Response parsing still looking for `.success` field
- ❌ MFA page looking for HTTP 428
- ❌ Any test failures on new paths

---

## REFERENCES

- **Full Audit Report:** API_ENDPOINT_CATALOGUE_AUDIT_REPORT.md
- **Corrected Documentation:** API_ENDPOINT_CATALOGUE_CORRECTED.md
- **Critical Findings:** 5 major discrepancies documented

---

**Prepared By:** Senior Core Banking Architect  
**Date:** April 19, 2026  
**Status:** Ready for Implementation

⏰ **Estimated Total Time:** 4-6 hours for complete fix + testing

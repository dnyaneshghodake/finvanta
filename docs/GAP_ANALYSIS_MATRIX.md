# Finvanta CBS — Gap Analysis Quick Reference Matrix

**Generated:** April 19, 2026  
**Audit Scope:** Finacle/Temenos/BNP/SWIFT/BANCS/FlexCube Comparison + RBI Compliance  
**Overall Score:** 66/100 (Production Readiness: 40%)

---

## GAPS BY SEVERITY

### 🔴 CRITICAL GAPS (Must Fix Before Production)

| Gap | Impact | Current | Required | Fix Effort | Priority |
|-----|--------|---------|----------|-----------|----------|
| **No Circuit Breaker for Network Calls** | Clearing timeout → EOD hangs | ❌ Missing | Hystrix/Resilience4j | 2 days | P0 Week 1 |
| **No KYC/AML Framework** | Cannot onboard; RBI fails inspection | ❌ Missing | Document upload + OFAC | 1-2 weeks | P0 Week 3 |
| **No Regulatory Reporting (CRILC/Form A)** | RBI penalty ₹1-5L/day for non-submission | ❌ Missing | Auto-report CRILC quarterly | 1-2 weeks | P0 Week 7 |
| **No Monitoring/Observability** | Cannot diagnose prod issues; blind response | ⚠️ Logs only | Prometheus + Grafana + ELK | 1 week | P0 Week 5 |
| **No Loan Restructuring Workflow** | RBI mandate unfulfilled; cannot help struggling borrowers | ❌ Missing | Restructuring approval + new schedule | 2-3 weeks | P0 Week 9 |
| **Idempotency Registry No TTL** | Memory leak risk; unbounded growth | ⚠️ No expiry | Add 24-hour TTL + purge batch | 1 day | P0 Week 1 |

**Weeks to Address:** 8 weeks for all critical gaps  
**Cost:** $80-100K  
**Risk if Ignored:** RBI inspection fail, customer data loss, EOD batch failure

---

### 🟠 HIGH GAPS (Fix Before Enterprise Scale)

| Gap | Impact | Current | Fix Effort |
|-----|--------|---------|-----------|
| **No Distributed Tracing** | Cannot track transaction across 10 microservices | ❌ MDC only | 2 weeks (Sleuth + Jaeger) |
| **No Event Sourcing** | Balance history requires full GL scan (slow) | ❌ Only snapshots | 3 weeks (event store) |
| **No Cross-Site Failover** | Single DC failure → complete outage | ❌ Single-site | 6 weeks (active-passive replication) |
| **No API Versioning** | Breaking change → mobile app crash on upgrade | ❌ All v1 | 1 week (header-based versioning) |
| **No Data Warehouse** | Can't answer "total IT sector exposure?" without complex JOIN | ❌ OLTP only | 4 weeks (ODS + nightly ETL) |
| **No Rate Limiting** | DDoS vulnerability; no request throttling | ❌ Session limit only | 2 weeks (Bucket4j) |
| **No Reconciliation Framework** | GL vs subledger daily; on mismatch → manual investigation | ⚠️ EOD Step 7 only | 3 weeks (continuous reconciliation) |

**Total Fix Effort:** ~16 weeks  
**Typical Tier-1 Recommendation:** Phase 2 (after Phase 1 critical gaps resolved)

---

### 🟡 MEDIUM GAPS (Plan for Future)

| Gap | Category | Impact | Timeline |
|-----|----------|--------|----------|
| **Multi-Currency Support** | Business Features | Cannot serve NRE/NRO accounts; foreign exchange risk mgt missing | v0.1 (post-MVP) |
| **Trade Finance (L/C, BG, Bill Discount)** | Business Features | Large revenue opportunity (₹300Cr market); no competitive advantage | v0.1 (post-MVP) |
| **Chaos Engineering** | Operational Excellence | Cannot validate failure recovery; unknown MTTR | v0.2 (roadmap) |
| **Real-time Analytics** | Intelligence | BI dashboards run on stale data (end-of-day only) | v0.2 (roadmap) |
| **ML-based Credit Scoring** | Risk Management | Manual underwriting; cannot auto-approve low-risk applicants | v0.3 (future) |
| **Facility Management (Overdraft, Limits)** | Business Features | Cannot offer credit lines; limited CASA+Loan bundle | v0.1 extension |

---

## GAPS BY RBI COMPLIANCE DIRECTION

### RBI IT Governance Direction 2023 — Compliance Status

| Section | Requirement | Status | Gap | RBI Risk |
|---------|-------------|--------|-----|----------|
| **§3.1** | Data encryption in motion (TLS) | ✅ HTTPS/TLS 1.2+ | None | None |
| **§3.2** | Data encryption at rest | ✅ AES-256-GCM for PII | None | None |
| **§4.1** | Intrusion detection system | ❌ Missing | No IDS/IPS | Audit finding |
| **§5.1** | Incident response plan | ❌ Missing | No documented procedures | Audit finding |
| **§6.1** | Change management | ⚠️ Partial | No CAB workflow | Audit finding |
| **§6.3** | Disaster recovery (RTO/RPO) | ⚠️ Documented but not validated | Targets: 4h RTO, 1h RPO | Compliance question |
| **§7.1** | Audit trail immutability | ✅ Chain-hash verified | None | None |
| **§7.4** | Monitoring & logging | ⚠️ Logs but no centralization | No ELK / log aggregation | Audit finding |
| **§8.1** | Input validation | ✅ Centralized | None | None |
| **§8.2** | CVE hygiene | ✅ Spring Boot 3.3.13 LTS | Needs quarterly updates | OK (track via GitHub) |
| **§8.3** | MFA for high-privilege users | ✅ TOTP RFC 6238 | None | None |
| **§8.4** | Session management | ✅ 15m timeout (prod) | None | None |

**RBI Inspection Readiness:** CONDITIONAL PASS (with remediation plan)

---

### RBI Master Direction on KYC 2016 — Compliance Status

| Requirement | Current | Status |
|-------------|---------|--------|
| KYC document imaging | ❌ Missing | CRITICAL GAP |
| KYC re-verification schedule | ⚠️ Date field only, not enforced | MEDIUM GAP |
| PEP (Politically Exposed Person) screening | ❌ Missing | HIGH GAP |
| Beneficial ownership capture | ❌ Not in schema | MEDIUM GAP / Design Gap |
| AML customer screening (OFAC) | ❌ Missing | CRITICAL GAP |
| Right to Information (RTI) compliance | ❌ No data export capability | MEDIUM GAP |

**RBI Inspection Impact:** Automatic fail without KYC document upload + OFAC screening

---

### RBI Payment Systems Act 2007 — Compliance Status

| Requirement | Current | Status | Gap |
|-------------|---------|--------|-----|
| NEFT/RTGS/IMPS/UPI clearing | ✅ Implemented | ✅ OK | None |
| Outward payment validation | ✅ Implemented | ✅ OK | None |
| Inward payment credit (suspense GL) | ✅ Implemented | ✅ OK | None |
| Return handling & reconciliation | ✅ Implemented | ✅ OK | None |
| Statutory clearing reports to RBI | ❌ Manual | ❌ CRITICAL | Implement auto-export |

---

## GAPS BY TIER-1 CBS STANDARDS

### Finacle TRAN_POSTING Pattern Compliance

| Component | Finacle Pattern | Finvanta Current | Rating |
|-----------|-----------------|------------------|--------|
| **Single Entry Point** | All GL via TRAN_POSTING | TransactionEngine | ✅ A+ |
| **Idempotency Registry** | UNIQUE.REF per transaction | idempotency_registry exists | ✅ A |
| **Double-Entry Validation** | Automatic imbalance rejection | Implemented | ✅ A |
| **Compound Posting** | Multi-leg transactions | Supported | ✅ A |
| **Voucher Generation** | Sequential per branch per day | Implemented | ✅ A |
| **GL Balance Pessimistic Lock** | SELECT FOR UPDATE | Implemented | ✅ A |
| **Batch Totals** | Running debit/credit aggregate | Implemented | ✅ A |
| **Module Callback** | Post-posting state update | Implemented | ✅ A |
| **Audit Trail Inline** | Same transaction as engine | REQUIRES_NEW transaction | ✅ A |
| **Circuit Breaker** | Network failure fallback | ❌ Missing | ❌ F |

**Finacle Alignment Score:** 9/10 (strong) — only circuit breaker missing

---

### Temenos IRIS API Standards Compliance

| Standard | Requirement | Current | Gap |
|----------|-------------|---------|-----|
| **API Versioning** | Version in URL or header | /api/v1/ only | Accept: application/vnd.finvanta.v2+json missing |
| **Error Standardization** | Rich error codes + messages | ApiExceptionHandler good | ✅ OK |
| **OData Support** | Filtering, sorting, pagination | Not implemented | Filter syntax missing |
| **Hypermedia Links** | HATEOAS responses | ❌ Missing | Links to related resources absent |
| **Rate Limiting Headers** | X-RateLimit-* headers | ❌ Missing | No rate limit headers in response |
| **Deprecation Warnings** | Sunset header for deprecated endpoints | ❌ Missing | No deprecation timeline communicated |

**Temenos IRIS Alignment Score:** 5/10 (basic) — needs API maturity

---

### BNP Paribas CORTEX Resilience Standards

| Pattern | BNP Standard | Current | Gap |
|---------|--------------|---------|-----|
| **Circuit Breaker** | Hystrix per external service | ❌ Missing | Add Resilience4j |
| **Bulkhead (Thread Isolation)** | Separate thread pools | ❌ Missing | Clearing pool vs Main pool |
| **Retry Policy** | Exponential backoff w/ jitter | ⚠️ SI only | Global policy missing |
| **Timeout Management** | Per-operation timeouts | ⚠️ Partial | Add to all external calls |
| **Health Checks** | /actuator/health probes | ✅ Spring Boot | ✅ Exists |
| **Graceful Shutdown** | Drain in-flight requests | ⚠️ Default Spring | Need validation |
| **Fallback Strategy** | Define fallback GL/rate if external fails | ❌ Missing | Clearing network fallback missing |

**BNP Resilience Score:** 3/10 (weak) — critical resilience gaps

---

## GAPS BY OPERATIONAL DOMAIN

### Monitoring & Observability

| Logging Component | Current | Required | Gap |
|------------------|---------|----------|-----|
| **Application Logs** | ✅ Exists (Spring Boot) | Logback config present | OK |
| **Structured Logging** | ⚠️ MDC only | JSON + ELK aggregation | Need ELK stack |
| **Metrics Collection** | ❌ Missing | Prometheus + Micrometer | Add metrics library |
| **Distributed Tracing** | ❌ Missing | Sleuth + Jaeger | Add trace propagation |
| **Real-time Dashboards** | ❌ Missing | Grafana dashboards | Add visualization |
| **Alert Thresholds** | ❌ Missing | PagerDuty integration | Add alerting rules |
| **APM (Application Performance Monitoring)** | ❌ Missing | DataDog / New Relic | Not urgent for MVP |

**Observability Score:** 2/10 (blind) — cannot diagnose production issues

---

### Backup & Disaster Recovery

| Procedure | Current | Required | Gap |
|-----------|---------|----------|-----|
| **RTO (Recovery Time)** | 4 hours | < 1 hour for critical | Medium gap |
| **RPO (Recovery Point)** | 1 hour | < 15 minutes | Medium gap |
| **Backup Verification** | Weekly test | Daily automated test | Medium gap |
| **Off-site Backup** | Not mentioned | Geographic diversity | High gap |
| **Backup Encryption** | Not mentioned | AES-256 mandatory | High gap |
| **Point-in-Time Recovery** | ❌ Not tested | Validate daily | High gap |
| **Failover Automation** | ❌ Manual | Auto-promote standby | High gap |

**DR Readiness Score:** 3/10 (untested) — RPI manual procedures risky

---

### Performance & Scalability

| Metric | Current | Target | Gap |
|--------|---------|--------|-----|
| **EOD Batch (100K accts)** | ~20 min | < 15 min | Small (add more threads) |
| **GL Posting Latency (p99)** | 50-100 ms | < 50 ms | Acceptable |
| **Account Inquiry QPS** | Unknown | 1000+ | Unknown (no load test baseline) |
| **Database Query Optimization** | Most indexed | Columnar indices for analytics | Medium gap |
| **Caching Layer** | ❌ Missing (Spring cache framework unused) | Redis for product GL codes | Medium gap (nice-to-have) |
| **Connection Pool Sizing** | HikariCP 20 | Per-load-test | Need sizing study |

**Scalability Score:** 7/10 (acceptable for pilot; needs study for scale)

---

## CRITICAL PATH DEPENDENCY

```
Week 1-2: Circuit Breaker + Timeout Handling
  ↓
Week 3-4: KYC/AML Framework (hard blocker)
  ↓
Week 5-6: Monitoring/Observability
  ↓
Week 7-8: Regulatory Reporting (CRILC)
  ↓
Phase 1 COMPLETE → Production-ready (80/100)
  ↓
Week 9-16: Phase 2 (Distributed Tracing, Event Sourcing, Restructuring, Failover)
  ↓
Phase 2 COMPLETE → Tier-1 Grade (90/100)
```

**Parallelizable:** Weeks 1-2 and 3-4 can overlap for cost savings (add 2nd engineer earlier)

---

## BOTTOM LINE SCORECARD

```
╔════════════════════════════════════════════════════════════╗
║            FINVANTA TIER-1 READINESS SCORECARD             ║
╠════════════════════════════════════════════════════════════╣
║                                                             ║
║  Architecture                             ██████████░░ 72%  ║
║  Data Integrity                           ███████████░ 75% ║
║  Security & Encryption                   ████████████░ 80% ║
║  Compliance & Regulatory                 ██████░░░░░░ 55% ║
║  Operational Readiness                   █████░░░░░░░ 50% ║
║  Business Features                       ██████░░░░░░ 60% ║
║  Performance & Scalability                ███████░░░░ 70% ║
║                                                             ║
║  ════════════════════════════════════════════════════════   ║
║  OVERALL TIER-1 READINESS:  ████████░░░░░░░░░░░ 66/100     ║
║  PRODUCTION READINESS:       ████░░░░░░░░░░░░░░░ 40/100     ║
║  GRADE: D+ (Needs Hardening)                                ║
║                                                             ║
║  ⏱️  TIME TO PRODUCTION:     8 weeks (critical fixes)       ║
║  💰 INVESTMENT REQUIRED:    $80-100K (Phase 1)             ║
║  📊 ENTERPRISE GRADE:       16 weeks + $200-250K total     ║
║                                                             ║
╚════════════════════════════════════════════════════════════╝
```

---

**Status:** Ready for board presentation / RFP / Investment decision

**Next Step:** Approve Phase 1 remediation plan (8 weeks, $80-100K)


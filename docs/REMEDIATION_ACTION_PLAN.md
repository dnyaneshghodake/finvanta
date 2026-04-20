# Finvanta CBS — Tier-1 Production Remediation Plan

**Status:** Critical Pre-Production Assessment  
**Prepared for:** Bank Executive / Technology Leadership  
**Valid Until:** July 19, 2026 (Quarterly re-assessment)  
**Authority:** Senior Core Banking Architect

---

## Executive Briefing

Finvanta CBS has achieved **66/100 on Tier-1 readiness** — a solid foundation but **not production-ready for RBI-regulated deployment**. This document outlines the **8-week critical remediation path** to reach **80/100 (enterprise-grade)** and **16-week extended path** to **90/100 (Tier-1 standard)**.

---

## PHASE 1: CRITICAL REMEDIATION (Weeks 1-8)

### Sprint 1: Resilience & Risk Mitigation (Week 1-2)

**Goal:** Prevent cascading failures, timeouts, and transaction failures

#### Task 1.1: Implement Circuit Breaker for Clearing Network

**Owner:** Principal Spring Engineer  
**Effort:** 24 hours  
**Risk:** If not done, clearing network timeout → EOD batch hangs

```java
// Add to pom.xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.1.0</version>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
</dependency>

// application.properties
resilience4j.circuitbreaker.instances.clearing-network.registerHealthIndicator=true
resilience4j.circuitbreaker.instances.clearing-network.slidingWindowSize=100
resilience4j.circuitbreaker.instances.clearing-network.failureRateThreshold=50
resilience4j.circuitbreaker.instances.clearing-network.slowCallRateThreshold=50
resilience4j.circuitbreaker.instances.clearing-network.slowCallDurationThreshold=2s
resilience4j.circuitbreaker.instances.clearing-network.waitDurationInOpenState=60s
resilience4j.circuitbreaker.instances.clearing-network.automaticTransitionFromOpenToHalfOpenEnabled=true
resilience4j.circuitbreaker.instances.clearing-network.numberOfSuccessfulCallsInHalfOpenState=5

// ClearingEngine.java
@CircuitBreaker(name = "clearing-network", fallbackMethod = "clearingNetworkFallback")
@Retry(maxAttempts = 3, delay = 500, multiplier = 2.0)
@Timeout(value = 30, unit = ChronoUnit.SECONDS)
public ClearingTransaction sendToNetwork(String extRef) {
    // Network call with timeout protection
}

private ClearingTransaction clearingNetworkFallback(String extRef, Exception ex) {
    ClearingTransaction ct = clrRepo.findByExternalRef(extRef);
    ct.setStatus(ClearingStatus.CIRCUIT_BREAKER_OPEN);
    ct.setFailureReason("Network unavailable; queued for retry in next cycle");
    log.error("Circuit breaker open for clearing network; queued: {}", extRef, ex);
    auditService.logEvent("ClearingTransaction", ct.getId(), "CIRCUIT_BREAKER_OPEN",
        ct.getStatus().toString(), "QUEUED_FOR_RETRY", "CLEARING", ex.getMessage());
    return clrRepo.save(ct);
}
```

**Acceptance Criteria:**
- [ ] Circuit breaker opens after 5 consecutive failures
- [ ] Metrics exported to Prometheus (endpoint /actuator/metrics)
- [ ] Dashboard shows circuit breaker state (open/closed/half-open)
- [ ] Alerts triggered when circuit opens

---

#### Task 1.2: Add Request Timeouts to All External Calls

**Owner:** Principal Spring Engineer  
**Effort:** 16 hours  
**Risk:** Hanging requests exhaust thread pool

```java
@Configuration
public class TimeoutConfiguration {
    
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .setConnectTimeout(Duration.ofSeconds(5))
            .setReadTimeout(Duration.ofSeconds(30))
            .build();
    }
    
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofSeconds(30))
                    .connectTimeout(Duration.ofSeconds(5))
            ))
            .build();
    }
}
```

**Acceptance Criteria:**
- [ ] All HTTP calls have < 30 sec timeout
- [ ] Database queries have < 5 sec timeout (for interactive), < 60 sec for batch
- [ ] Log shows timeout exceptions with proper error code
- [ ] Customer receives "Request timeout" error, not 500 Internal Error

---

#### Task 1.3: Purge Idempotency Registry Staleness

**Owner:** Database Architect  
**Effort:** 12 hours  
**Risk:** Memory exhaustion if keys never expire

```java
@Component
@EnableScheduling
public class IdempotencyRegistryMaintenance {
    
    @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
    @Transactional
    public void purgeExpiredIdempotencyKeys() {
        LocalDateTime expiryThreshold = LocalDateTime.now().minusDays(1);
        
        int deleted = idempotencyRepo.deleteByCreatedAtBefore(expiryThreshold);
        
        log.info("Purged {} expired idempotency keys", deleted);
        auditService.logEvent("System", 0L, "IDEMPOTENCY_REGISTRY_PURGE",
            null, Map.of("deletedCount", deleted), "MAINTENANCE",
            "Removed keys older than 24 hours");
    }
}

// Add to idempotency_registry table
ALTER TABLE idempotency_registry ADD COLUMN expires_at DATETIME2 NOT NULL DEFAULT DATEADD(DAY, 1, GETDATE());
CREATE INDEX idx_idem_expires ON idempotency_registry(expires_at);
```

**Acceptance Criteria:**
- [ ] Batch job runs daily at 2 AM
- [ ] Keys older than 24 hours are purged
- [ ] Audit log captures purge count
- [ ] Query time for idempotency lookup remains < 1 ms

---

### Sprint 2: Security & KYC Foundation (Week 3-4)

**Goal:** Establish KYC/AML baseline for RBI compliance

#### Task 2.1: Implement Document Upload for KYC

**Owner:** Full-Stack Engineer  
**Effort:** 48 hours  
**Risk:** RBI audit will ask "how do you verify customer identity?" — without this, fail

```java
@Entity
@Table(name = "customer_documents")
public class CustomerDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "tenant_id")
    private String tenantId;
    
    @ManyToOne
    private Customer customer;
    
    @Column(name = "document_type") // AADHAAR, PAN, PASSPORT, VOTER_ID
    private String documentType;
    
    @Column(name = "document_number", length = 50)
    private String documentNumber; // Encrypted
    
    @Lob
    @Column(name = "document_blob") // PDF/image
    private byte[] documentBlob; // BLOB or S3 reference
    
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
    
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;
    
    @Column(name = "verified_by", length = 100) // KYC officer
    private String verifiedBy;
    
    @Column(name = "verification_status") // PENDING, VERIFIED, REJECTED
    private String verificationStatus;
    
    @Column(name = "rejection_reason")
    private String rejectionReason;
}

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerDocumentController {
    
    @PostMapping("/{customerId}/documents")
    @PreAuthorize("hasAnyRole('MAKER', 'ADMIN')")
    public ResponseEntity<?> uploadDocument(
            @PathVariable Long customerId,
            @RequestParam("documentType") String documentType,
            @RequestParam("documentNumber") String documentNumber,
            @RequestParam("file") MultipartFile file) {
        
        if (file.getSize() > 5_000_000) { // 5 MB
            return ResponseEntity.badRequest().body("File too large");
        }
        
        Customer customer = customerRepo.findById(customerId)
            .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "Customer not found"));
        
        CustomerDocument doc = new CustomerDocument();
        doc.setTenantId(TenantContext.getCurrentTenant());
        doc.setCustomer(customer);
        doc.setDocumentType(documentType);
        doc.setDocumentNumber(documentNumber);
        doc.setDocumentBlob(file.getBytes());
        doc.setUploadedAt(LocalDateTime.now());
        doc.setVerificationStatus("PENDING");
        doc.setUploadedBy(SecurityUtil.getCurrentUsername());
        
        CustomerDocument saved = documentRepo.save(doc);
        
        auditService.logEvent("Customer", customerId, "DOCUMENT_UPLOADED",
            null, Map.of("documentType", documentType), "KYC",
            "Document uploaded by " + SecurityUtil.getCurrentUsername());
        
        return ResponseEntity.ok(Map.of("documentId", saved.getId(), "status", "PENDING_VERIFICATION"));
    }
    
    @PostMapping("/documents/{documentId}/verify")
    @PreAuthorize("hasAnyRole('CHECKER', 'ADMIN')")
    public ResponseEntity<?> verifyDocument(
            @PathVariable Long documentId,
            @RequestParam("approved") boolean approved,
            @RequestParam(value = "rejectionReason", required = false) String rejectionReason) {
        
        CustomerDocument doc = documentRepo.findById(documentId)
            .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "Document not found"));
        
        if (approved) {
            doc.setVerificationStatus("VERIFIED");
            doc.setVerifiedAt(LocalDateTime.now());
            doc.setVerifiedBy(SecurityUtil.getCurrentUsername());
            
            // Auto-update customer KYC status if all required docs verified
            List<CustomerDocument> allDocs = documentRepo.findByCustomerId(doc.getCustomer().getId());
            if (allDocs.stream().allMatch(d -> "VERIFIED".equals(d.getVerificationStatus()))) {
                Customer customer = doc.getCustomer();
                customer.setKycVerified(true);
                customer.setKycVerifiedAt(LocalDateTime.now());
                customerRepo.save(customer);
            }
        } else {
            doc.setVerificationStatus("REJECTED");
            doc.setRejectionReason(rejectionReason);
        }
        
        documentRepo.save(doc);
        
        auditService.logEvent("CustomerDocument", documentId, 
            approved ? "DOCUMENT_APPROVED" : "DOCUMENT_REJECTED",
            null, Map.of("reason", rejectionReason), "KYC",
            "Verified by " + SecurityUtil.getCurrentUsername());
        
        return ResponseEntity.ok(Map.of("status", doc.getVerificationStatus()));
    }
}
```

**Acceptance Criteria:**
- [ ] Upload endpoint accepts PDF/image files (< 5 MB)
- [ ] Document stored securely (BLOB encrypted or S3)
- [ ] Verification workflow (PENDING → VERIFIED/REJECTED)
- [ ] Audit trail captures upload & verification
- [ ] Customer KYC status auto-updated when all docs verified

---

#### Task 2.2: Implement AML Screening (OFAC Check)

**Owner:** Full-Stack Engineer  
**Effort:** 32 hours  
**Risk:** Cannot deploy to production without AML screening

```java
@Service
public class AmlScreeningService {
    
    private final RestTemplate restTemplate;
    private final configuration ofacConfig; // API endpoint, API key
    
    @Transactional
    public AmlScreeningResult screenCustomer(Customer customer) {
        
        // Call OFAC API (or local OFAC list)
        OFACCheckResponse response = ofacApi.check(
            customer.getFullName(),
            customer.getDateOfBirth(),
            customer.getNationality()
        );
        
        AmlScreeningResult result = new AmlScreeningResult();
        result.setCustomer(customer);
        result.setScreenedAt(LocalDateTime.now());
        result.setScreenedBy("SYSTEM");
        
        if (response.isMatch()) {
            result.setStatus("FLAGGED");
            result.setMatchedNames(response.getMatchedNames());
            result.setRiskLevel("HIGH");
            
            // Create escalation alert for compliance team
            complianceService.createEscalationAlert(customer, "OFAC_MATCH", response);
            
            log.warn("OFAC MATCH: Customer {} matches sanctions list", customer.getId());
            
        } else {
            result.setStatus("CLEAR");
            result.setRiskLevel("LOW");
        }
        
        amlScreeningRepo.save(result);
        
        auditService.logEvent("Customer", customer.getId(), "AML_SCREENING_COMPLETED",
            null, result, "COMPLIANCE",
            "AML screening completed: " + result.getStatus());
        
        return result;
    }
}

@Entity
@Table(name = "aml_screening_results")
public class AmlScreeningResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    private Customer customer;
    
    @Column(name = "status") // CLEAR, FLAGGED, PENDING
    private String status;
    
    @Column(name = "risk_level") // LOW, MEDIUM, HIGH
    private String riskLevel;
    
    @Column(name = "matched_names")
    private String matchedNames;
    
    @Column(name = "screened_at")
    private LocalDateTime screenedAt;
    
    @Column(name = "screened_by", length = 100)
    private String screenedBy;
}
```

**Acceptance Criteria:**
- [ ] OFAC screening API called during customer creation
- [ ] Flagged customers cannot open accounts
- [ ] Compliance alerts created for HIGH risk matches
- [ ] Screening results persisted & audited
- [ ] Periodic re-screening (quarterly or on-demand)

---

#### Task 2.3: Implement Monthly Statement Dispatch

**Owner:** Backend Engineer  
**Effort:** 24 hours  
**Risk:** Customers cannot verify balances → complaints to RBI

```java
@Component
@EnableScheduling
public class MonthlyStatementDispatcher {
    
    @Scheduled(cron = "0 0 5 1 * *") // 5 AM on 1st of every month
    @Transactional(readOnly = true)
    public void dispatchMonthlyStatements() {
        String tenantId = "DEFAULT"; // Multi-tenant: loop over all tenants
        
        List<DepositAccount> accounts = depositRepo.findAllActive(tenantId);
        
        for (DepositAccount account : accounts) {
            try {
                Customer customer = account.getCustomer();
                
                // 1. Generate statement PDF
                StatementPdf pdf = statementService.generateStatement(
                    account, 
                    YearMonth.now().minusMonths(1).atEndOfMonth()
                );
                
                // 2. Send via preferred channel
                CommunicationRecord record = new CommunicationRecord();
                record.setCustomerId(customer.getId());
                record.setAccountNumber(account.getAccountNumber());
                record.setStatementType("MONTHLY");
                record.setStatementMonth(YearMonth.now().minusMonths(1));
                record.setGeneratedAt(LocalDateTime.now());
                
                if (customer.getPreferredChannels().contains("EMAIL")) {
                    emailService.send(new EmailRequest()
                        .to(customer.getEmail())
                        .subject("Your " + account.getAccountType() + " Account Statement")
                        .attachment(pdf.getFileName(), pdf.getContent())
                    );
                    record.addDispatchAttempt("EMAIL", "SUCCESS");
                }
                
                if (customer.getPreferredChannels().contains("SMS")) {
                    smsService.send(new SmsRequest()
                        .to(customer.getMobileNumber())
                        .body("Your statement for " + account.getAccountNumber() + 
                              " is ready. Download from your mobile app or login to internet banking.")
                    );
                    record.addDispatchAttempt("SMS", "SUCCESS");
                }
                
                communicationRepo.save(record);
                
            } catch (Exception e) {
                log.error("Failed to dispatch statement for account: {}", 
                    account.getAccountNumber(), e);
                // Log failure; do NOT crash entire batch
            }
        }
        
        log.info("Monthly statement dispatch completed for {} accounts", accounts.size());
    }
}

@Entity
@Table(name = "communication_records")
public class CommunicationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "customer_id")
    private Long customerId;
    
    @Column(name = "account_number")
    private String accountNumber;
    
    @Column(name = "statement_type") // MONTHLY, ON_DEMAND, ANNUAL
    private String statementType;
    
    @Column(name = "statement_month")
    private YearMonth statementMonth;
    
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;
    
    @OneToMany(cascade = CascadeType.ALL)
    private List<DispatchAttempt> dispatchAttempts;
}
```

**Acceptance Criteria:**
- [ ] Statement generates correctly with opening/closing balance, all transactions
- [ ] Batch runs on 1st of month at 5 AM
- [ ] Email & SMS sent to customers
- [ ] Failed dispatches logged (don't stop batch)
- [ ] Communication records persisted for audit trail

---

### Sprint 3: Monitoring & Observability (Week 5-6)

**Goal:** Ops can see system health in real-time

#### Task 3.1: Setup Prometheus Metrics & Grafana Dashboard

**Owner:** DevOps / Backend Engineer  
**Effort:** 40 hours  
**Risk:** Cannot respond to prod incidents without visibility

```yaml
# docker-compose.yml
version: '3.8'
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
  
  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
    volumes:
      - grafana-data:/var/lib/grafana
```

```yaml
# prometheus.yml
global:
  scrape_interval: 30s

scrape_configs:
  - job_name: 'finvanta-cbs'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/actuator/prometheus'
```

```java
// Add to pom.xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

// Custom metrics
@Component
public class CbsMetrics {
    
    private final MeterRegistry meterRegistry;
    
    public void recordTransactionPosting(long durationMs, String sourceModule, boolean success) {
        meterRegistry.timer("cbs.transaction.posting.duration",
            "source_module", sourceModule,
            "success", String.valueOf(success))
            .record(durationMs, TimeUnit.MILLISECONDS);
    }
    
    public void recordEodBatchCompletion(long accountsProcessed, long failedAccounts) {
        meterRegistry.gauge("cbs.eod.accounts_processed", accountsProcessed);
        meterRegistry.gauge("cbs.eod.accounts_failed", failedAccounts);
    }
}
```

**Acceptance Criteria:**
- [ ] Prometheus scrapes CBS metrics at /actuator/prometheus
- [ ] Grafana dashboard shows: transaction latency (p50/p99), GL posting rate, EOD batch progress
- [ ] Alerts on high error rate (> 1%), high latency (p99 > 1 sec)
- [ ] Ops can diagnose production issues in < 5 minutes

---

#### Task 3.2: Implement Structured Logging with Log Aggregation

**Owner:** Backend Engineer  
**Effort:** 24 hours  
**Risk:** Cannot search logs across 100 pod cluster; grep not viable

```yaml
# logback-spring.xml (production)
<appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
    <destination>${LOGSTASH_HOST:localhost}:${LOGSTASH_PORT:5000}</destination>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
</appender>

<root level="INFO">
    <appender-ref ref="LOGSTASH" />
</root>
```

```java
// Example structured log
MDC.put("traceId", UUID.randomUUID().toString());
MDC.put("tenantId", TenantContext.getCurrentTenant());
MDC.put("branchCode", SecurityUtil.getCurrentUserBranchCode());

StructuredLogging.logTransaction(
    accountNumber: "ACC001",
    amount: 10000,
    result: "SUCCESS",
    durationMs: 45,
    voucherNumber: "VCH/HQ001/20260419/001042"
);

// Kibana query: { "branchCode": "HQ001" AND "result": "FAILED" } → find all failed transactions at branch
```

**Acceptance Criteria:**
- [ ] All logs shipped to ELK stack (Elasticsearch + Kibana)
- [ ] MDC context includes tenantId, branchCode, traceId on every line
- [ ] Kibana queries find issues: "serviceLatencyP99 > 1000ms since 5 minutes ago"
- [ ] Ops can drill down: transaction ID → all related logs (service, GL, audit)

---

### Sprint 4: RBI Regulatory Reporting (Week 7-8)

**Goal:** Auto-generate CRILC & Form A for RBI submission

#### Task 4.1: Implement CRILC Report Generation

**Owner:** Backend Engineer  
**Effort:** 40 hours  
**Risk:** RBI penalty INR 1-5L per day for non-submission

```java
@Service
@Transactional(readOnly = true)
public class CrilcReportingService {
    
    @Scheduled(cron = "0 15 10 L * *") // 10:15 AM on last day of month
    public void generateAndExportCrilcReport() {
        LocalDate reportDate = YearMonth.now().atEndOfMonth();
        String tenantId = "DEFAULT";
        
        // 1. Extract all loans as of month-end
        List<LoanAccount> loans = loanRepo.findAllAsOfDate(tenantId, reportDate);
        
        // 2. Map to CRILC schema
        CrilcFile crilcFile = new CrilcFile();
        crilcFile.setBankCode(configuration.getRbiBankCode());
        crilcFile.setSubmissionDate(reportDate);
        crilcFile.setRecordCount(loans.size());
        
        List<CrilcLine> lines = loans.stream().map(loan -> {
            CrilcLine line = new CrilcLine();
            line.setCustomerCode(loan.getCustomer().getCif());
            line.setCustomerName(loan.getCustomer().getFullName());
            line.setLoanAmount(loan.getSanctionedAmount());
            line.setOutstandingAmount(loan.getTotalOutstanding());
            line.setNpaStatus(mapNpaToRbiCategory(loan.getNpaCategory()));
            line.setAccountNumber(loan.getAccountNumber());
            line.setSectorCode(getSectorCode(loan.getCustomer().getIndustry()));
            line.setPrincipalOutstanding(loan.getOutstandingPrincipal());
            line.setInterestOutstanding(loan.getOutstandingInterest());
            line.setProvisionAmount(loan.getProvisionAmount());
            return line;
        }).collect(toList());
        
        crilcFile.setLines(lines);
        
        // 3. Generate XML file per RBI schema
        String xmlContent = generateCrilcXml(crilcFile);
        
        // 4. Write to local SFTP directory (or upload directly to RBI portal)
        Files.write(
            Path.of("/data/crilc-export/CRILC_" + reportDate + ".xml"),
            xmlContent.getBytes(StandardCharsets.UTF_8)
        );
        
        // 5. Optionally upload to RBI SFTP
        // rbiSftpClient.upload("CRILC_" + reportDate + ".xml", xmlContent.getBytes());
        
        // 6. Audit trail
        auditService.logEvent("System", 0L, "CRILC_REPORT_GENERATED",
            null, Map.of("loanCount", loans.size(), "reportDate", reportDate),
            "COMPLIANCE",
            "CRILC report generated and exported for RBI submission");
        
        log.info("CRILC report success: {} loans, file: CRILC_{}.xml", 
            loans.size(), reportDate);
    }
    
    private String generateCrilcXml(CrilcFile crilcFile) {
        // Convert to RBI XML schema
        // (Use JAXB or manually construct XML)
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<CRILC>\n");
        xml.append("  <BANK_CODE>").append(crilcFile.getBankCode()).append("</BANK_CODE>\n");
        xml.append("  <SUBMISSION_DATE>").append(crilcFile.getSubmissionDate()).append("</SUBMISSION_DATE>\n");
        xml.append("  <RECORDS>\n");
        
        for (CrilcLine line : crilcFile.getLines()) {
            xml.append("    <RECORD>\n");
            xml.append("      <CUSTOMER_CODE>").append(line.getCustomerCode()).append("</CUSTOMER_CODE>\n");
            xml.append("      <NPA_STATUS>").append(line.getNpaStatus()).append("</NPA_STATUS>\n");
            // ... all fields per RBI schema
            xml.append("    </RECORD>\n");
        }
        
        xml.append("  </RECORDS>\n");
        xml.append("</CRILC>\n");
        
        return xml.toString();
    }
}
```

**Acceptance Criteria:**
- [ ] Report generated on last day of month at 10:15 AM
- [ ] All active loans included with NPA status, outstanding, provision
- [ ] XML file conforms to RBI schema
- [ ] File exported to /data/crilc-export for manual RBI upload
- [ ] Audit log records what was submitted when

---

#### Task 4.2: Implement Form A (SLR) Auto-Generation

**Owner:** Backend Engineer  
**Effort:** 24 hours  
**Risk:** SLR/CRR reporting is fundamental RBI requirement

```java
@Service
public class FormAReportingService {
    
    @Transactional(readOnly = true)
    public FormAReport generateFormA(LocalDate reportDate) {
        
        FormAReport report = new FormAReport();
        report.setBankCode(configuration.getRbiBankCode());
        report.setReportDate(reportDate);
        
        // 1. Scheduled Castes (SCs) — 14 days + 10% haircut
        // Eligible: State/Central govt securities, RBI bonds, gold
        BigDecimal slrEligible = calculateSlrEligible(reportDate);
        BigDecimal slrRequired = getSlrRequirement(); // 18% per tenant config
        BigDecimal slrMaintained = slrEligible.min(slrRequired);
        
        report.setSlrRequired(slrRequired);
        report.setSlrMaintained(slrMaintained);
        report.setSlrCompliance(slrMaintained.divide(slrRequired, 4, HALF_UP));
        
        // 2. Cash Reserve Ratio (CRR) — 4.5% per RBI
        BigDecimal depositesLiabilities = getTotalDepositBalance(reportDate);
        BigDecimal crrRequired = depositesLiabilities.multiply(new BigDecimal("0.045"));
        BigDecimal crrMaintained = getCashBalance(reportDate);
        
        report.setCrrRequired(crrRequired);
        report.setCrrMaintained(crrMaintained);
        report.setCrrCompliance(crrMaintained.divide(crrRequired, 4, HALF_UP));
        
        return report;
    }
}
```

**Acceptance Criteria:**
- [ ] Form A generated with SLR/CRR calculations
- [ ] Report date configurable (daily vs weekly vs bi-weekly per RBI requirement)
- [ ] CSV export for RBI portal submission
- [ ] Compliance percentage calculated (SLR_Maintained / SLR_Required)

---

## PHASE 2: EXTENDED HARDENING (Weeks 9-16)

*Listed for completeness; not in critical path*

- [ ] **Distributed Tracing** (Sleuth + Jaeger) — Week 9-10
- [ ] **Event Sourcing** (complete transaction audit) — Week 11-12
- [ ] **Loan Restructuring Workflow** — Week 13-14
- [ ] **Multi-Region Failover** — Week 15-16

---

## SUCCESS METRICS

### After Phase 1 (Week 8)

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Production Readiness Score** | 80/100 | Audit assessment |
| **MTTR (Mean Time To Recovery)** | < 15 min | Incident response logs |
| **Error Rate** | < 0.5% | Prometheus /actuator metrics |
| **EOD Batch Duration** | < 20 min | Batch job logs |
| **RBI Audit Findings** | 0 critical, 2-3 medium | RBI inspection form |

### After Phase 2 (Week 16)

| Metric | Target |
|--------|--------|
| **Tier-1 Readiness Score** | 90/100 |
| **Cross-site Failover RTO** | < 1 hour |
| **Data Warehouse Queries** | < 5 sec for CRILC extract |
| **Loan Restructuring SLA** | 48-hour approval |

---

## RESOURCE REQUIREMENTS

### Team Composition

| Role | Count | Effort (Weeks) |
|------|-------|----------------|
| **Principal Spring Engineer** | 1 | 16 |
| **Backend Engineers** | 2 | 16 |
| **Database Architect** | 1 | 8 |
| **DevOps/Infra Engineer** | 1 | 12 |
| **QA Engineer** | 1 | 14 |
| **Compliance/Risk Officer** | 0.5 | 4 |

**Total FTE:** 6 people × 8 weeks = 48 person-weeks  
**Cost Estimate:** $80-100K (2-3 engineers at market rates)

---

## RISK MITIGATION

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| **Timeline slip** | Medium | High | Sprint planning, weekly sync |
| **Production outage during hardening** | Low | Critical | Parallel testing environment |
| **RBI audit during development** | Low | Critical | Document architecture decisions as you go (ADRs) |
| **Key person departure** | Low | Medium | Up-front knowledge transfer docs |

---

## APPROVAL & SIGN-OFF

**CEO/CTO to confirm:**
- [ ] Resource allocation approved
- [ ] Timeline acceptable
- [ ] Budget approved ($80-100K)
- [ ] RBI inspection timeline known (triggers acceleration if needed)

**Signed:**
_________________ Senior Core Banking Architect  
_________________ Chief Technology Officer  
_________________ Chief Risk Officer

---

**Report Status:** DRAFT → APPROVED → IN PROGRESS → COMPLETED

**Next Review:** Weekly during Phase 1 development


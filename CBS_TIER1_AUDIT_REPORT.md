# FINVANTA CBS TIER-1 ARCHITECTURE AUDIT REPORT

**Audit Date:** 2026-04-26
**Branch Audited:** `integration_prior_to_master_branch`
**Total Java Files:** 313
**Auditor Role:** Tier-1 Core Banking Solution Architect

---

## EXECUTIVE SUMMARY

The Finvanta CBS platform demonstrates **strong domain knowledge** (RBI IRAC norms, CASA lifecycle, double-entry GL, maker-checker, PII masking) and a **mature transaction engine** (11-step validation pipeline). However, the **package architecture is flat/layer-based** rather than domain-driven, resulting in critical violations of Tier-1 CBS module isolation standards.

**Overall Score: 52/100 -- NON-TIER-1**

---

## 1. SCORING BREAKDOWN

| Category              | Score | Max | Grade |
|-----------------------|-------|-----|-------|
| Architecture          | 8     | 20  | FAIL  |
| Security              | 14    | 20  | PASS  |
| Audit & Compliance    | 16    | 20  | PASS  |
| Scalability           | 6     | 20  | FAIL  |
| Code Structure        | 8     | 20  | FAIL  |
| **TOTAL**             | **52**| **100** | **NON-TIER-1** |

---

## 2. DETAILED VIOLATIONS

### CRITICAL VIOLATIONS (Immediate Remediation Required)

#### C1. FLAT PACKAGE STRUCTURE -- NO DOMAIN ISOLATION
**Severity:** CRITICAL
**Standard Violated:** Finacle Module Isolation / Temenos Component Architecture / DDD Bounded Contexts

**Current State:**
```
com.finvanta.service/          -- ALL services for ALL modules (40+ files)
com.finvanta.repository/       -- ALL repositories for ALL modules (52 files)
com.finvanta.domain.entity/    -- ALL entities for ALL modules (52 files)
com.finvanta.api/              -- ALL REST controllers (20+ files)
com.finvanta.controller/       -- ALL MVC controllers (18 files)
```

**Required State (Tier-1):**
```
com.finvanta.cbs.modules.customer/    -- Bounded context
com.finvanta.cbs.modules.account/     -- Bounded context
com.finvanta.cbs.modules.loan/        -- Bounded context
com.finvanta.cbs.modules.transaction/ -- Bounded context
com.finvanta.cbs.modules.gl/          -- Bounded context
```

**Impact:** In Finacle/Temenos, modules are independently deployable units. A flat structure means any developer can create cross-module dependencies without compiler enforcement. At 1M+ txn/day, this prevents independent scaling of the transaction module.

---

#### C2. CONTROLLER DIRECTLY CALLS REPOSITORY (17 violations)
**Severity:** CRITICAL
**Standard Violated:** Layered Architecture -- controller -> service -> repository

**Violations Found:**
| Controller | Repositories Injected |
|---|---|
| `DashboardApiController` | `CustomerRepository`, `LoanApplicationRepository`, `LoanAccountRepository`, `DepositAccountRepository`, `DepositTransactionRepository`, `ClearingTransactionRepository` (6!) |
| `AuthController` | `AppUserRepository`, `RevokedRefreshTokenRepository` |
| `BranchApiController` | `BranchRepository` |
| `CalendarApiController` | `BusinessCalendarRepository` |
| `UserApiController` | `AppUserRepository` |
| `FixedDepositController` | `FixedDepositRepository` |
| `ReportApiController` | `LoanAccountRepository` |
| `GLInquiryController` | `GLMasterRepository` |
| `ChargeController` | `ChargeTransactionRepository` |
| `ProductApiController` | `ProductMasterRepository` |
| `AuditApiController` | `AuditLogRepository` |

**Impact:** Bypasses service-layer business validations, audit logging, and transaction boundaries. A Tier-1 CBS system MUST enforce `controller -> service -> repository` at all times.

---

#### C3. ENTITY DIRECTLY EXPOSED TO API LAYER (23 violations)
**Severity:** CRITICAL
**Standard Violated:** DTO Isolation / API Contract Stability

**Evidence:** 23 imports of `com.finvanta.domain.entity.*` in API controllers. Entities like `Customer`, `DepositAccount`, `LoanAccount`, `LoanTransaction` are imported directly into controllers.

While some controllers use inline `record` response DTOs (e.g., `CustomerResponse.from(entity)`), the entity itself is still visible to and manipulated in the controller layer. The inline records are a partial mitigation but NOT a proper DTO layer.

**Impact:** Schema changes in JPA entities directly break the API contract. In Finacle/Temenos, the API contract is decoupled from the persistence model via dedicated DTO/mapper layers.

---

#### C4. BUSINESS LOGIC INSIDE CONTROLLERS
**Severity:** CRITICAL
**Standard Violated:** Thin Controller / Service Layer Responsibility

**Violations:**
1. **`AuthController`** -- Contains `@Transactional`, directly queries `AppUserRepository`, performs password validation, MFA challenge logic, token generation, and refresh token rotation. This is 733 lines of business logic in a controller.
2. **`DashboardApiController`** -- 529 lines. Directly queries 6 repositories and performs complex aggregation (NPA ratios, CASA ratios, provision coverage). This is reporting/analytics business logic.
3. **`CustomerApiController`** -- Contains `populateCustomerFromRequest()` mapping logic and business validation for immutable fields directly in the controller.

---

### HIGH VIOLATIONS

#### H1. NO DEDICATED DTO PACKAGE/LAYER
**Severity:** HIGH
**Current:** Request/Response DTOs are defined as inner `record` classes inside controllers.
**Required:** Separate `dto/request/` and `dto/response/` packages per module with dedicated mapper classes.
**Impact:** DTOs cannot be reused across controllers. No compile-time guarantee that entities are never serialized to the wire.

#### H2. NO MAPPER LAYER
**Severity:** HIGH
**Current:** Entity-to-DTO conversion via static `from()` methods on inline records.
**Required:** Dedicated mapper classes (MapStruct or manual) per module in `mapper/` package.
**Impact:** Mapping logic is scattered across controllers, violating SRP. No centralized PII masking enforcement during mapping.

#### H3. NO DEDICATED VALIDATOR LAYER
**Severity:** HIGH
**Current:** Validation is either Jakarta Bean Validation on DTOs or ad-hoc checks in service/controller code.
**Required:** Dedicated `validator/` package per module for complex business validations (e.g., CIF duplicate check, loan eligibility, account opening prerequisites).

#### H4. @Transactional IN CONTROLLER (AuthController)
**Severity:** HIGH
**Standard Violated:** Transaction boundaries belong in the service layer.
**Evidence:** `AuthController` has 3 `@Transactional` annotated methods (lines 94, 266, 497).

#### H5. MISSING APPLICATION PROFILES (SIT/UAT)
**Severity:** HIGH
**Current:** Profiles: `dev` (H2), `prod` (SQL Server), `sqlserver`.
**Required:** `dev`, `sit`, `uat`, `prod` per CBS environment matrix (4-environment standard).

#### H6. NO CACHING LAYER
**Severity:** HIGH
**Current:** No `@Cacheable`, `@CacheEvict`, or `CacheManager` usage found. Caffeine dependency exists but is only used manually in `ProductGLResolver`.
**Required:** Spring Cache abstraction with Caffeine/Redis for GL lookups, product master, branch data, permission evaluations.

---

### MEDIUM VIOLATIONS

#### M1. CROSS-CUTTING CONCERNS MIXED IN /config
**Severity:** MEDIUM
**Current:** Filters, interceptors, and aspects all reside in `com.finvanta.config/` (30 files).
**Required:** Separate packages: `filter/`, `interceptor/`, `aspect/`, `security/`.

#### M2. SINGLE ASPECT ONLY
**Severity:** MEDIUM
**Current:** Only `TenantHibernateFilterAspect` exists.
**Required:** Additional aspects for: performance monitoring, method-level audit, transaction boundary verification, exception translation.

#### M3. NO CONSTANTS PACKAGE
**Severity:** MEDIUM
**Current:** Constants scattered (`GLConstants` in accounting, hardcoded values in services).
**Required:** Centralized `constants/` package with CBS error codes, GL codes, regulatory thresholds.

#### M4. MISSING ASYNC PROCESSING HOOKS
**Severity:** MEDIUM
**Current:** `AsyncConfig` exists but no `@Async` methods found in service layer. Outbox processor uses scheduled polling.
**Required:** Async hooks for notification dispatch, CTR filing, credit bureau reporting, SMS/email delivery.

#### M5. NO FACADE/ORCHESTRATION LAYER
**Severity:** MEDIUM
**Current:** Controllers directly call services. Complex workflows (e.g., loan disbursement involving 5+ services) are orchestrated inside service implementations.
**Required:** `facade/` layer per module for multi-service orchestration.

---

## 3. WHAT WORKS WELL (Credit)

| Area | Assessment |
|------|-----------|
| **TransactionEngine** | Excellent 11-step validation pipeline with idempotency, maker-checker, GL posting. Proper `PESSIMISTIC_WRITE` locking. |
| **AuditService** | SHA-256 hash chain, immutable append-only, REQUIRES_NEW + inline variants for deadlock prevention. |
| **Domain Rules** | `NpaClassificationRule`, `ProvisioningRule`, `InterestCalculationRule`, `LoanEligibilityRule` -- proper RBI IRAC norms. |
| **Security Config** | Dual filter chain (API JWT + UI session), `@PreAuthorize` on all API endpoints, MFA support, RBAC with 4 roles. |
| **PII Masking** | `PiiMaskingUtil`, `PiiEncryptionConverter` (AES-256-GCM), hash-based search. |
| **Error Handling** | `ApiExceptionHandler` with standardized error envelope, severity, correlation ID. |
| **Tenant Isolation** | Hibernate filters + `TenantContext` + aspect enforcement. |
| **CVE Hygiene** | Proactive dependency pinning (Tomcat, jjwt, AssertJ). |

---

## 4. REFACTORED FOLDER STRUCTURE

### Target Architecture: Domain-Driven Modular Monolith

```
com.finvanta.cbs
|
|-- FinvantaApplication.java
|
|-- common/                              # Shared cross-cutting infrastructure
|   |-- config/                          # Spring configuration
|   |   |-- SecurityConfig.java
|   |   |-- AsyncConfig.java
|   |   |-- JacksonConfig.java
|   |   |-- OpenApiConfig.java
|   |   |-- WebMvcConfig.java
|   |   |-- I18nMessageConfig.java
|   |   |-- CbsBootstrapInitializer.java
|   |   |-- CbsStartupLogger.java
|   |   +-- CbsEncryptedPropertyProcessor.java
|   |
|   |-- security/                        # Security infrastructure
|   |   |-- JwtAuthenticationFilter.java
|   |   |-- JwtTokenService.java
|   |   |-- AuthRateLimitFilter.java
|   |   |-- MfaVerificationFilter.java
|   |   |-- MfaAuthenticationSuccessHandler.java
|   |   |-- MfaSecretEncryptor.java
|   |   |-- CbsPermissionEvaluator.java
|   |   |-- CbsAuthenticationEventListener.java
|   |   |-- CbsSessionListener.java
|   |   |-- BranchAwareUserDetails.java
|   |   +-- CustomUserDetailsService.java
|   |
|   |-- audit/                           # Centralized audit infrastructure
|   |   |-- AuditService.java
|   |   +-- AuditInterceptor.java
|   |
|   |-- exception/                       # Global exception handling
|   |   |-- GlobalExceptionHandler.java
|   |   |-- ApiExceptionHandler.java
|   |   |-- BusinessException.java
|   |   |-- ValidationException.java
|   |   +-- MfaRequiredException.java
|   |
|   |-- filter/                          # Servlet filters
|   |   |-- CorrelationIdMdcFilter.java
|   |   |-- TenantFilter.java
|   |   +-- AuthRateLimitFilter.java
|   |
|   |-- interceptor/                     # MVC interceptors
|   |   |-- HibernateTenantFilterInterceptor.java
|   |   +-- PerformanceLoggingInterceptor.java
|   |
|   |-- aspect/                          # AOP aspects
|   |   |-- TenantHibernateFilterAspect.java
|   |   |-- AuditAspect.java
|   |   +-- PerformanceMonitorAspect.java
|   |
|   |-- constants/                       # CBS-wide constants
|   |   |-- CbsErrorCodes.java
|   |   |-- GLConstants.java
|   |   +-- RbiRegulatory.java
|   |
|   |-- util/                            # Shared utilities
|   |   |-- SecurityUtil.java
|   |   |-- TenantContext.java
|   |   |-- PiiMaskingUtil.java
|   |   |-- PiiHashUtil.java
|   |   |-- ReferenceGenerator.java
|   |   |-- BranchAccessValidator.java
|   |   +-- CbsSecurityContext.java
|   |
|   |-- domain/                          # Shared base classes
|   |   |-- BaseEntity.java
|   |   +-- AuditLog.java
|   |
|   |-- dto/                             # Shared API envelope
|   |   +-- ApiResponse.java
|   |
|   +-- converter/                       # JPA converters
|       +-- PiiEncryptionConverter.java
|
|-- modules/
|   |
|   |-- customer/                        # CIF Module (Bounded Context)
|   |   |-- controller/
|   |   |   |-- CustomerApiController.java
|   |   |   +-- CustomerWebController.java
|   |   |-- service/
|   |   |   |-- CustomerCifService.java
|   |   |   +-- CustomerCifServiceImpl.java
|   |   |-- domain/
|   |   |   |-- Customer.java
|   |   |   +-- CustomerDocument.java
|   |   |-- repository/
|   |   |   |-- CustomerRepository.java
|   |   |   +-- CustomerDocumentRepository.java
|   |   |-- dto/
|   |   |   |-- request/
|   |   |   |   +-- CreateCustomerRequest.java
|   |   |   +-- response/
|   |   |       |-- CustomerResponse.java
|   |   |       +-- CifLookupResponse.java
|   |   |-- mapper/
|   |   |   +-- CustomerMapper.java
|   |   |-- validator/
|   |   |   +-- CustomerValidator.java
|   |   +-- event/
|   |       +-- CustomerCreatedEvent.java
|   |
|   |-- account/                         # CASA Module (Bounded Context)
|   |   |-- controller/
|   |   |   |-- DepositAccountApiController.java
|   |   |   +-- DepositWebController.java
|   |   |-- service/
|   |   |   |-- DepositAccountService.java
|   |   |   +-- DepositAccountServiceImpl.java
|   |   |-- domain/
|   |   |   |-- DepositAccount.java
|   |   |   |-- DepositTransaction.java
|   |   |   |-- FixedDeposit.java
|   |   |   |-- RecurringDeposit.java
|   |   |   |-- StandingInstruction.java
|   |   |   +-- DailyBalanceSnapshot.java
|   |   |-- repository/
|   |   |   |-- DepositAccountRepository.java
|   |   |   |-- DepositTransactionRepository.java
|   |   |   |-- FixedDepositRepository.java
|   |   |   |-- RecurringDepositRepository.java
|   |   |   |-- StandingInstructionRepository.java
|   |   |   +-- DailyBalanceSnapshotRepository.java
|   |   |-- dto/
|   |   |   |-- request/
|   |   |   |   |-- OpenAccountRequest.java
|   |   |   |   |-- FinancialRequest.java
|   |   |   |   +-- TransferRequest.java
|   |   |   +-- response/
|   |   |       |-- AccountResponse.java
|   |   |       |-- BalanceResponse.java
|   |   |       |-- TxnResponse.java
|   |   |       +-- StatementResponse.java
|   |   |-- mapper/
|   |   |   +-- AccountMapper.java
|   |   |-- validator/
|   |   |   +-- AccountValidator.java
|   |   +-- event/
|   |       +-- AccountOpenedEvent.java
|   |
|   |-- loan/                            # Loan Module (Bounded Context)
|   |   |-- controller/
|   |   |   |-- LoanAccountApiController.java
|   |   |   |-- LoanApplicationApiController.java
|   |   |   +-- LoanWebController.java
|   |   |-- service/
|   |   |   |-- LoanAccountService.java
|   |   |   |-- LoanAccountServiceImpl.java
|   |   |   |-- LoanApplicationService.java
|   |   |   +-- LoanApplicationServiceImpl.java
|   |   |-- domain/
|   |   |   |-- LoanAccount.java
|   |   |   |-- LoanApplication.java
|   |   |   |-- LoanSchedule.java
|   |   |   |-- LoanTransaction.java
|   |   |   |-- LoanDocument.java
|   |   |   |-- LoanBalanceSnapshot.java
|   |   |   |-- DisbursementSchedule.java
|   |   |   +-- Collateral.java
|   |   |-- repository/
|   |   |   |-- LoanAccountRepository.java
|   |   |   |-- LoanApplicationRepository.java
|   |   |   |-- LoanScheduleRepository.java
|   |   |   |-- LoanTransactionRepository.java
|   |   |   |-- LoanDocumentRepository.java
|   |   |   |-- LoanBalanceSnapshotRepository.java
|   |   |   |-- DisbursementScheduleRepository.java
|   |   |   +-- CollateralRepository.java
|   |   |-- dto/
|   |   |   |-- request/
|   |   |   |   +-- LoanApplicationRequest.java
|   |   |   +-- response/
|   |   |       |-- LoanAccountResponse.java
|   |   |       +-- LoanApplicationResponse.java
|   |   |-- mapper/
|   |   |   +-- LoanMapper.java
|   |   |-- validator/
|   |   |   +-- LoanValidator.java
|   |   |-- rules/                       # Domain business rules
|   |   |   |-- NpaClassificationRule.java
|   |   |   |-- ProvisioningRule.java
|   |   |   |-- LoanEligibilityRule.java
|   |   |   +-- InterestCalculationRule.java
|   |   +-- event/
|   |       +-- LoanDisbursedEvent.java
|   |
|   |-- transaction/                     # Transaction Engine (Bounded Context)
|   |   |-- controller/
|   |   |   +-- TransactionBatchApiController.java
|   |   |-- service/
|   |   |   |-- TransactionEngine.java
|   |   |   |-- TransactionValidationService.java
|   |   |   |-- TransactionReExecutionService.java
|   |   |   |-- TransactionLimitService.java
|   |   |   |-- TransactionBatchService.java
|   |   |   |-- MakerCheckerService.java
|   |   |   +-- SequenceGeneratorService.java
|   |   |-- domain/
|   |   |   |-- TransactionRequest.java
|   |   |   |-- TransactionResult.java
|   |   |   |-- TransactionPreview.java
|   |   |   |-- TransactionBatch.java
|   |   |   |-- TransactionOutbox.java
|   |   |   |-- TransactionLimit.java
|   |   |   +-- IdempotencyRegistry.java
|   |   |-- repository/
|   |   |   |-- TransactionBatchRepository.java
|   |   |   |-- TransactionOutboxRepository.java
|   |   |   |-- TransactionLimitRepository.java
|   |   |   +-- IdempotencyRegistryRepository.java
|   |   |-- dto/
|   |   |   +-- response/
|   |   |       +-- TransactionBatchResponse.java
|   |   |-- mapper/
|   |   |   +-- TransactionMapper.java
|   |   +-- event/
|   |       +-- OutboxEventProcessor.java
|   |
|   |-- gl/                              # General Ledger Module (Bounded Context)
|   |   |-- controller/
|   |   |   |-- GLInquiryApiController.java
|   |   |   +-- AccountingWebController.java
|   |   |-- service/
|   |   |   |-- AccountingService.java
|   |   |   |-- LedgerService.java
|   |   |   |-- SuspenseService.java
|   |   |   |-- ProductGLResolver.java
|   |   |   |-- ClearingGLResolver.java
|   |   |   |-- PostingIntegrityGuard.java
|   |   |   |-- FinancialStatementService.java
|   |   |   +-- AccountingReconciliationEngine.java
|   |   |-- domain/
|   |   |   |-- GLMaster.java
|   |   |   |-- GLBranchBalance.java
|   |   |   |-- JournalEntry.java
|   |   |   |-- JournalEntryLine.java
|   |   |   |-- LedgerEntry.java
|   |   |   |-- InterestAccrual.java
|   |   |   +-- TenantLedgerState.java
|   |   |-- repository/
|   |   |   |-- GLMasterRepository.java
|   |   |   |-- GLBranchBalanceRepository.java
|   |   |   |-- JournalEntryRepository.java
|   |   |   |-- LedgerEntryRepository.java
|   |   |   |-- InterestAccrualRepository.java
|   |   |   +-- TenantLedgerStateRepository.java
|   |   |-- dto/
|   |   |   +-- response/
|   |   |       |-- GLBalanceResponse.java
|   |   |       +-- JournalEntryResponse.java
|   |   |-- mapper/
|   |   |   +-- GLMapper.java
|   |   +-- bootstrap/
|   |       |-- GLBranchBalanceBootstrap.java
|   |       +-- TenantLedgerStateBootstrap.java
|   |
|   |-- workflow/                        # Maker-Checker Workflow Module
|   |   |-- controller/
|   |   |   +-- WorkflowApiController.java
|   |   |-- service/
|   |   |   +-- ApprovalWorkflowService.java
|   |   |-- domain/
|   |   |   +-- ApprovalWorkflow.java
|   |   |-- repository/
|   |   |   +-- ApprovalWorkflowRepository.java
|   |   |-- dto/
|   |   |   +-- response/
|   |   |       +-- WorkflowResponse.java
|   |   +-- mapper/
|   |       +-- WorkflowMapper.java
|   |
|   |-- compliance/                      # RBI Compliance Module
|   |   |-- service/
|   |   |   |-- AmlComplianceService.java
|   |   |   |-- PslComplianceService.java
|   |   |   |-- RbiReturnsService.java
|   |   |   |-- CreditBureauService.java
|   |   |   |-- CapitalAdequacyService.java
|   |   |   +-- CustomerCertificateService.java
|   |   |-- domain/
|   |   |   |-- AmlCtrReport.java
|   |   |   |-- AmlStrReport.java
|   |   |   +-- CreditBureauInquiry.java
|   |   |-- repository/
|   |   |   |-- AmlCtrReportRepository.java
|   |   |   |-- AmlStrReportRepository.java
|   |   |   +-- CreditBureauInquiryRepository.java
|   |   +-- controller/
|   |       +-- ComplianceApiController.java
|   |
|   |-- batch/                           # EOD/BOD Batch Module
|   |   |-- service/
|   |   |   |-- EodOrchestrator.java
|   |   |   |-- EodTrialService.java
|   |   |   |-- ClearingEngine.java
|   |   |   |-- ClearingStateManager.java
|   |   |   |-- ChargeEngine.java
|   |   |   |-- ProvisioningService.java
|   |   |   |-- ReconciliationService.java
|   |   |   |-- SubledgerReconciliationService.java
|   |   |   |-- InterBranchSettlementService.java
|   |   |   +-- CalendarStartupRecovery.java
|   |   |-- domain/
|   |   |   |-- BatchJob.java
|   |   |   |-- ClearingCycle.java
|   |   |   |-- ClearingTransaction.java
|   |   |   |-- SettlementBatch.java
|   |   |   |-- InterBranchTransaction.java
|   |   |   |-- ChargeConfig.java
|   |   |   |-- ChargeDefinition.java
|   |   |   +-- ChargeTransaction.java
|   |   |-- repository/
|   |   |   |-- BatchJobRepository.java
|   |   |   |-- ClearingCycleRepository.java
|   |   |   |-- ClearingTransactionRepository.java
|   |   |   |-- SettlementBatchRepository.java
|   |   |   |-- InterBranchSettlementRepository.java
|   |   |   |-- ChargeConfigRepository.java
|   |   |   |-- ChargeDefinitionRepository.java
|   |   |   +-- ChargeTransactionRepository.java
|   |   +-- controller/
|   |       +-- BatchWebController.java
|   |
|   |-- admin/                           # Admin / User / Branch / Product Module
|   |   |-- controller/
|   |   |   |-- UserApiController.java
|   |   |   |-- BranchApiController.java
|   |   |   |-- ProductApiController.java
|   |   |   |-- CalendarApiController.java
|   |   |   |-- DashboardApiController.java
|   |   |   +-- AdminWebController.java
|   |   |-- service/
|   |   |   |-- UserService.java
|   |   |   |-- BranchService.java
|   |   |   |-- ProductMasterService.java
|   |   |   |-- BusinessDateService.java
|   |   |   |-- SessionContextService.java
|   |   |   |-- DashboardService.java       # NEW: Extract from controller
|   |   |   +-- AuthService.java            # NEW: Extract from AuthController
|   |   |-- domain/
|   |   |   |-- AppUser.java
|   |   |   |-- Branch.java
|   |   |   |-- ProductMaster.java
|   |   |   |-- BusinessCalendar.java
|   |   |   |-- Permission.java
|   |   |   |-- RolePermission.java
|   |   |   |-- Tenant.java
|   |   |   |-- DbSequence.java
|   |   |   |-- FeatureFlag.java
|   |   |   |-- NotificationLog.java
|   |   |   |-- NotificationTemplate.java
|   |   |   +-- RevokedRefreshToken.java
|   |   |-- repository/
|   |   |   |-- AppUserRepository.java
|   |   |   |-- BranchRepository.java
|   |   |   |-- ProductMasterRepository.java
|   |   |   |-- BusinessCalendarRepository.java
|   |   |   |-- PermissionRepository.java
|   |   |   |-- RolePermissionRepository.java
|   |   |   |-- TenantRepository.java
|   |   |   |-- DbSequenceRepository.java
|   |   |   |-- FeatureFlagRepository.java
|   |   |   |-- NotificationLogRepository.java
|   |   |   |-- NotificationTemplateRepository.java
|   |   |   +-- RevokedRefreshTokenRepository.java
|   |   |-- dto/
|   |   |   |-- request/
|   |   |   +-- response/
|   |   |       |-- UserResponse.java
|   |   |       |-- BranchResponse.java
|   |   |       |-- DashboardSummary.java
|   |   |       +-- AuthResponse.java
|   |   +-- mapper/
|   |       +-- AdminMapper.java
|   |
|   |-- notification/                    # Notification Module
|   |   |-- service/
|   |   |   |-- NotificationService.java
|   |   |   +-- NotificationPersistenceManager.java
|   |   +-- controller/
|   |       +-- NotificationApiController.java
|   |
|   |-- charge/                          # Charge/Fee Module
|   |   |-- service/
|   |   |   |-- ChargeKernel.java
|   |   |   +-- GstTaxResolver.java
|   |   |-- domain/
|   |   |   +-- ChargeResult.java
|   |   +-- controller/
|   |       +-- ChargeApiController.java
|   |
|   +-- report/                          # Reporting Module
|       |-- controller/
|       |   +-- ReportApiController.java
|       +-- service/
|           +-- ReportService.java       # NEW: Extract from controller
```

---

## 5. REFACTORING STRATEGY

### Phase 1: Package Restructuring (Week 1-2)
1. Create `com.finvanta.cbs.modules.*` directory structure
2. Move entities to their bounded context `domain/` packages
3. Move repositories to their bounded context `repository/` packages
4. Move services to their bounded context `service/` packages
5. Move controllers to their bounded context `controller/` packages
6. Create `com.finvanta.cbs.common.*` for cross-cutting concerns
7. Update all imports (IDE refactor + verify compilation)

### Phase 2: DTO Isolation (Week 2-3)
1. Extract inline record DTOs from controllers into `dto/request/` and `dto/response/`
2. Create dedicated `Mapper` classes per module
3. Remove all entity imports from controller layer
4. Enforce compile-time check: no `domain.*` imports in `controller/` package

### Phase 3: Service Layer Enforcement (Week 3-4)
1. Extract `AuthService` from `AuthController` (733 lines -> thin controller + service)
2. Extract `DashboardService` from `DashboardApiController`
3. Extract `ReportService` from `ReportApiController`
4. Remove all repository imports from controller layer
5. Move `@Transactional` from controllers to services

### Phase 4: Cross-Cutting Separation (Week 4)
1. Create `filter/`, `interceptor/`, `aspect/` packages under `common/`
2. Move filters from `config/` to `common/filter/`
3. Move security classes from `config/` to `common/security/`
4. Create `AuditAspect` for method-level audit logging
5. Create `PerformanceMonitorAspect` for CBS performance SLA tracking

### Phase 5: Scalability & Caching (Week 5)
1. Add Spring Cache abstraction with `@Cacheable` on GL lookups, product master, branch data
2. Add `@Async` on notification dispatch, CTR filing, credit bureau reporting
3. Add application profiles for SIT and UAT environments
4. Create `CbsErrorCodes` constants class with CBS-MODULE-NNN format

---

## 6. POST-REFACTORING TARGET SCORE

| Category              | Current | Target | Delta |
|-----------------------|---------|--------|-------|
| Architecture          | 8       | 18     | +10   |
| Security              | 14      | 18     | +4    |
| Audit & Compliance    | 16      | 19     | +3    |
| Scalability           | 6       | 17     | +11   |
| Code Structure        | 8       | 18     | +10   |
| **TOTAL**             | **52**  | **90** | **+38** |

**Target Grade: TIER-1 COMPLIANT (90/100)**

---

*Report generated by Tier-1 CBS Architecture Audit Engine v1.0*
*Standards: Finacle 11.x, Temenos T24 R23, Oracle FLEXCUBE 14.x, RBI IT Governance Direction 2023*

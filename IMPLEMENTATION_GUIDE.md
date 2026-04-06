# Finvanta CBS - Q2 2026 Enhancement Implementation Guide

**Branch:** feature/april_2026_releases  
**Status:** IN PROGRESS  
**Last Updated:** April 7, 2026

---

## Implementation Status

### Completed
✅ P0-1: ChargeConfig entity, ChargeConfigRepository, ChargeEngine service  
✅ P0-2: InterestAccrual entity, InterestAccrualRepository  
✅ P1-1: InterBranchTransaction entity, InterBranchSettlementRepository, InterBranchSettlementService  
✅ P1-2: ClearingTransaction entity, ClearingTransactionRepository, ClearingService  
✅ DDL: All new table definitions (charge_config, interest_accruals, inter_branch_transactions, clearing_transactions)  
✅ Seed Data: GL codes (1300, 2200, 2201, 2300, 2400) and charge configurations  
✅ Test: ChargeEngineTest unit tests  

### Remaining (In-Progress)

#### P0-1: Charges Engine
- [ ] **Modify LoanAccountServiceImpl.chargeFee()** to delegate to ChargeEngine.applyCharge()
  - Location: `src/main/java/com/finvanta/service/impl/LoanAccountServiceImpl.java`
  - Change: Remove direct GL posting logic, call chargeEngine.applyCharge() instead
  - Inject: Add ChargeEngine to constructor
  - Test: Existing tests should still pass (LoanEligibilityRuleTest)

- [ ] **Update LoanController.chargeFee() endpoint**
  - Location: `src/main/java/com/finvanta/controller/LoanController.java`
  - Change: Accept `charge_code` parameter instead of `feeType`
  - Signature: `@PostMapping("/loan/fee") public ResponseEntity<?> applyCharge(@RequestParam String chargeCode, ...)`
  - Test: Manual test with new charge_code parameter

- [ ] **Create admin JSP page**
  - Location: `src/main/webapp/WEB-INF/views/admin/charges.jsp`
  - Content: Table of charge configurations with Add/Edit/Delete forms
  - Features: 
    - Display all active charges per tenant
    - CRUD operations
    - CSRF token on all forms
    - <c:out> for XSS prevention
  - Styling: Bootstrap per existing JSPs

- [ ] **Update SecurityConfig**
  - Location: `src/main/java/com/finvanta/config/SecurityConfig.java`
  - Add rule: `@RequestMapping("/admin/charges/**").hasRole("ADMIN")`

- [ ] **Add GLConstants entries**
  - File: `src/main/java/com/finvanta/accounting/GLConstants.java`
  - Add: `CGST_PAYABLE = "2200"`, `SGST_PAYABLE = "2201"`

#### P0-2: Interest Accrual Table
- [ ] **Modify LoanAccountServiceImpl.applyInterestAccrual()**
  - Location: `src/main/java/com/finvanta/service/impl/LoanAccountServiceImpl.java`
  - Change: After GL posting, insert row into interest_accruals table
  - Fields to populate: account_id, accrual_date, principal_base, rate_applied, days_count, accrued_amount, accrual_type, posted_flag=true, journal_entry_id, transaction_ref
  - Inject: Add InterestAccrualRepository to constructor

- [ ] **Modify LoanAccountServiceImpl.applyPenalInterest()**
  - Same as above but with accrual_type='PENAL'

- [ ] **Update account-details.jsp**
  - Location: `src/main/webapp/WEB-INF/views/loan/account-details.jsp`
  - Add: "Interest Accrual Trail" collapsible section
  - Query: Call service method to fetch accrual history
  - Display: Table with Date, Type, Principal Base, Rate, Days, Amount, Posted flag, Journal Ref

#### P1-1: Inter-Branch Settlement
- [ ] **Create InterBranchSettlementRepository interface** ✅ Done

- [ ] **Create InterBranchSettlementService** ✅ Done

- [ ] **Integrate into EodOrchestrator**
  - Location: `src/main/java/com/finvanta/batch/EodOrchestrator.java`
  - Change: Add call to settlementService.settleInterBranch(businessDate)
  - Position: After Step 7 (GL Reconciliation), before finalize
  - Add new step number (Step 7.5)
  - Inject: Add InterBranchSettlementService to constructor

- [ ] **Create InterBranchSettlementServiceTest**
  - Test: recordInterBranchTransfer() GL posting
  - Test: settleInterBranch() balance validation
  - Test: Mismatch detection and FAILED status

#### P1-2: Clearing/Settlement Suspense
- [ ] **Create ClearingTransactionRepository interface** ✅ Done

- [ ] **Create ClearingService** ✅ Done

- [ ] **Integrate into EodOrchestrator**
  - Location: `src/main/java/com/finvanta/batch/EodOrchestrator.java`
  - Change: Add call to clearingService.validateSuspenseBalance(businessDate)
  - Position: After Step 7 (GL Reconciliation)
  - Behavior: Log warning if non-zero (don't block EOD)
  - Inject: Add ClearingService to constructor

- [ ] **Create ClearingServiceTest**
  - Test: initiateClearingTransaction() suspense posting
  - Test: confirmClearing() settlement GL posting
  - Test: failClearing() reversal
  - Test: validateSuspenseBalance() zero check

#### P2-1: Ledger Partitioning Strategy
- [ ] **Add @QueryHint annotations**
  - Location: `src/main/java/com/finvanta/repository/LedgerEntryRepository.java`
  - Add: Partition pruning hints on heavy queries
  - Example: `@Query(...) @QueryHint(name="org.hibernate.query.HINT_SQL_COMMENT", value="/* business_date filter */")`

#### P2-2: Parallel EOD Processing
- [ ] **Modify EodOrchestrator Steps 2-5**
  - Location: `src/main/java/com/finvanta/batch/EodOrchestrator.java`
  - Change: Use CompletableFuture for per-account processing
  - ExecutorService: `newFixedThreadPool(eod.parallel.threads)`
  - Track: Per-thread processed/failed counts
  - Add property: `eod.parallel.threads=4` to application.properties
  - Log: Progress every 1000 accounts per thread

---

## Files to Modify (Remaining)

1. **LoanAccountServiceImpl.java** (4 changes)
   - Inject ChargeEngine
   - Refactor chargeFee() to delegate to ChargeEngine
   - Modify applyInterestAccrual() to insert interest_accruals record
   - Modify applyPenalInterest() to insert interest_accruals record

2. **LoanController.java** (1 change)
   - Update chargeFee() endpoint to accept charge_code

3. **EodOrchestrator.java** (3 changes)
   - Integrate InterBranchSettlementService (settleInterBranch call)
   - Integrate ClearingService (validateSuspenseBalance call)
   - Implement parallel processing with CompletableFuture (Steps 2-5)

4. **SecurityConfig.java** (1 change)
   - Add rule for /admin/charges/**

5. **GLConstants.java** (2 changes)
   - Add CGST_PAYABLE, SGST_PAYABLE

6. **application.properties** (1 change)
   - Add eod.parallel.threads=4

7. **LedgerEntryRepository.java** (1 change)
   - Add @QueryHint annotations for partition pruning

---

## Files to Create (Remaining)

1. **src/main/webapp/WEB-INF/views/admin/charges.jsp**
   - Charge configuration management UI
   - CRUD operations

2. **Test files:**
   - InterestAccrualEntityTest (entity lifecycle)
   - InterBranchSettlementServiceTest (service logic)
   - ClearingServiceTest (service logic)

---

## Key Implementation Notes

### P0-1: Charges Engine
- ChargeEngine.applyCharge() MUST call TransactionEngine.execute()
- All GL postings route through engine (architectural rule)
- 3-leg journal: DR Bank / CR Charge Income / CR GST Payable
- GST auto-calculated if gst_applicable=true
- Product-specific GL codes resolved via glResolver fallback to global

### P0-2: Interest Accrual
- Insert into interest_accruals AFTER GL posting succeeds
- posted_flag lifecycle: false (pre-insert) → true (on GL posting success)
- Enables deterministic replay: any date range can be recalculated from table
- RBI audit requirement: all financial calculations logged and reproducible

### P1-1: Inter-Branch Settlement
- Dual GL posting: one at source, one at target
- Settlement during EOD: validate receivables = payables
- If balanced: status=SETTLED; if not: status=FAILED (log for investigation)
- No blocking of EOD (non-blocking error)

### P1-2: Clearing Suspense
- Suspense GL (2400) is temporary holding account
- Lifecycle: INITIATED → PENDING → CONFIRMED → SETTLED
- FAILED status triggers reversal posting
- EOD check: Clearing Suspense GL balance MUST = 0
- Non-zero suspense logged as warning (doesn't block EOD)

### P2-1: Ledger Partitioning
- For H2 (dev/test): No actual partitioning, just DDL comments
- For SQL Server (prod): Partition by business_date (monthly)
- Benefits: Query pruning, parallel scans, archive old data
- @QueryHint annotations guide query optimizer for partition elimination

### P2-2: Parallel EOD
- Steps 2-5 (DPD, accrual, penal, NPA) are per-account independent
- Use CompletableFuture.supplyAsync() with fixed thread pool
- Per-thread error isolation already exists (try/catch per account)
- Merge thread-level counts at completion
- Log progress every 1000 accounts per thread
- Default: 4 threads (configurable via property)

---

## Testing Checklist

### Unit Tests (create new files)
- [ ] ChargeEngineTest ✅ (created)
- [ ] InterestAccrualEntityTest
- [ ] InterBranchSettlementServiceTest
- [ ] ClearingServiceTest

### Integration Tests (existing must pass)
- [ ] LoanEligibilityRuleTest (mock LoanAccountRepository check)
- [ ] Existing loan creation tests
- [ ] Existing disbursement tests
- [ ] Existing EOD tests

### Manual Tests
- [ ] Charge application via new endpoint
- [ ] Interest accrual history in account details
- [ ] Inter-branch settlement in EOD
- [ ] Clearing suspense EOD check
- [ ] Admin charges page CRUD

---

## Code Quality Checklist

Before submitting PR:

- [ ] No ₹ or — or → characters in Java files, JSPs, or SQL
- [ ] All new @PostMapping endpoints have SecurityConfig rules
- [ ] All new @RequestParam annotated (CSRF protection)
- [ ] All new JSP forms include CSRF tokens
- [ ] All new JSP outputs use <c:out> for XSS prevention
- [ ] All GL postings via TransactionEngine.execute()
- [ ] All new tables have matching entities with @Table annotation
- [ ] All new GL codes in seed data AND gl_master INSERT statements
- [ ] No direct AccountingService.postJournalEntry() calls (only via engine)
- [ ] AccountingService.generateEngineToken() pattern used in charge/settlement code
- [ ] All @Version fields on mutable entities
- [ ] Pessimistic @Lock on mutations (GL, batches)
- [ ] Transactional boundaries correct (@Transactional on service methods)

---

## Git Commit Structure

Each commit should represent a logical feature:

1. `feat: add Charges Engine with GST per Finacle CHRG_MASTER`
   - ChargeConfig entity, repository, service
   - DDL, seed data, GL codes
   - Admin JSP, controller endpoint update, SecurityConfig

2. `feat: add Interest Accrual Table for audit-grade per-day records`
   - InterestAccrual entity, repository
   - Modified applyInterestAccrual/applyPenalInterest methods
   - account-details.jsp update

3. `feat: add Inter-Branch Settlement Engine per Finacle IB_SETTLEMENT`
   - InterBranchTransaction entity, repository, service
   - GL codes, EOD integration

4. `feat: add Clearing/Settlement Suspense Engine`
   - ClearingTransaction entity, repository, service
   - GL codes, EOD suspense check

5. `feat: add ledger partitioning strategy for 10M+ TPS`
   - DDL comments, @QueryHint annotations

6. `feat: add parallel EOD processing with configurable thread pool`
   - EodOrchestrator modification, application.properties

7. `test: add unit tests for ChargeEngine, InterBranchSettlement, ClearingService`
   - All test files

---

## Production Deployment Checklist

- [ ] Data migration: existing charges → charge_config table
- [ ] Data migration: existing interest accruals → interest_accruals table
- [ ] SQL Server: Uncomment partition DDL in ddl-sqlserver.sql
- [ ] Configure: eod.parallel.threads based on CPU cores
- [ ] Test: Parallel EOD with large account population
- [ ] Monitoring: Add ledger sequence gaps detection
- [ ] Monitoring: Add Clearing Suspense GL non-zero alerts

---

## Known Limitations & Future Work

- InterBranchSettlementService: Simplified balance matching (full prod would aggregate per-branch)
- ClearingService: Depends on external clearing network confirmations (mock for now)
- Parallel EOD: Accounts locked independently (no cross-account locking required)
- Ledger Partitioning: Manual partition management (could add automated sliding window)
- ChargeEngine: Caching for charge configs (could add Caffeine cache)

---

## References

- RBI IRAC Master Circular: Provisioning percentages
- RBI Fair Lending Code 2023: Penal interest, charges transparency
- Finacle CHRG_MASTER: Charge configuration standards
- Finacle IB_SETTLEMENT: Inter-branch settlement engine
- Temenos AA.PRODUCT.CATALOG: Product GL mapping



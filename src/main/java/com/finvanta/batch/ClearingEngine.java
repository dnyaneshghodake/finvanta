package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ClearingGLResolver;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.ClearingCycle;
import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.PaymentRail;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.ClearingCycleRepository;
import com.finvanta.repository.ClearingTransactionRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Clearing Engine per Finacle CLG_ENGINE.
 * Central orchestrator for NEFT/RTGS/IMPS/UPI clearing.
 * Rail-specific suspense GLs per RBI Payment Systems Act.
 * All GL via TransactionEngine. CASA via DepositAccountService.
 */
@Service
public class ClearingEngine {

    private static final Logger log =
            LoggerFactory.getLogger(ClearingEngine.class);
    private static final BigDecimal RTGS_MIN =
            new BigDecimal("200000.00");

    private final ClearingTransactionRepository clrRepo;
    private final ClearingCycleRepository cycleRepo;
    private final DepositAccountRepository depAcctRepo;
    private final DepositAccountService depAcctSvc;
    private final BranchRepository branchRepo;
    private final GLMasterRepository glRepo;
    private final TransactionEngine txnEngine;
    private final BusinessDateService bizDateSvc;
    private final AuditService auditSvc;

    public ClearingEngine(
            ClearingTransactionRepository clrRepo,
            ClearingCycleRepository cycleRepo,
            DepositAccountRepository depAcctRepo,
            DepositAccountService depAcctSvc,
            BranchRepository branchRepo,
            GLMasterRepository glRepo,
            TransactionEngine txnEngine,
            BusinessDateService bizDateSvc,
            AuditService auditSvc) {
        this.clrRepo = clrRepo;
        this.cycleRepo = cycleRepo;
        this.depAcctRepo = depAcctRepo;
        this.depAcctSvc = depAcctSvc;
        this.branchRepo = branchRepo;
        this.glRepo = glRepo;
        this.txnEngine = txnEngine;
        this.bizDateSvc = bizDateSvc;
        this.auditSvc = auditSvc;
    }

    /** OUTWARD: Validate, Debit Customer, Post Suspense */
    @Transactional
    public ClearingTransaction initiateOutward(
            String extRef, PaymentRail rail, BigDecimal amt,
            String custAcct, String cpIfsc, String cpAcct,
            String cpName, String narr, Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        if (clrRepo.existsByTenantIdAndExternalRefNo(tid, extRef))
            throw new BusinessException(
                    "DUPLICATE_CLEARING_REF", extRef);
        if (cpIfsc == null
                || !cpIfsc.matches("^[A-Z]{4}0[A-Z0-9]{6}$"))
            throw new BusinessException("INVALID_IFSC", cpIfsc);
        if (amt == null || amt.signum() <= 0)
            throw new BusinessException(
                    "INVALID_AMOUNT", "positive required");
        if (rail == PaymentRail.RTGS
                && amt.compareTo(RTGS_MIN) < 0)
            throw new BusinessException(
                    "RTGS_MIN_AMOUNT", "min " + RTGS_MIN);
        Branch br = branchRepo.findById(branchId)
                .filter(b -> b.getTenantId().equals(tid)
                        && b.isActive())
                .orElseThrow(() -> new BusinessException(
                        "BRANCH_NOT_FOUND", "" + branchId));
        ClearingTransaction ct = new ClearingTransaction();
        ct.setTenantId(tid);
        ct.setExternalRefNo(extRef);
        ct.setPaymentRail(rail);
        ct.setDirection(ClearingDirection.OUTWARD);
        ct.setAmount(amt);
        ct.setCustomerAccountRef(custAcct);
        ct.setCounterpartyIfsc(cpIfsc);
        ct.setCounterpartyAccount(cpAcct);
        ct.setCounterpartyName(cpName);
        ct.setNarration(narr);
        ct.setBranch(br);
        ct.setBranchCode(br.getBranchCode());
        ct.setStatus(ClearingStatus.INITIATED);
        ct.setValueDate(bd);
        ct.setInitiatedAt(LocalDateTime.now());
        ct.setMakerId(user);
        ct.setCreatedBy(user);
        ClearingTransaction saved = clrRepo.save(ct);
        // CBS State Machine: INITIATED → VALIDATED
        transitionStatus(saved, ClearingStatus.VALIDATED);
        // CBS: Debit customer account for outward clearing.
        // Use "SYSTEM" channel to bypass branch access validation — clearing operations
        // are centralized and may debit accounts at any branch.
        // Per Finacle CLG_ENGINE: clearing debits are system-initiated, not user-initiated.
        //
        // NOTE: If withdraw() throws, the entire @Transactional rolls back — the INITIATED
        // clearing record is NOT persisted. The caller receives the exception directly.
        // Per Spring: RuntimeException from a REQUIRED participant marks the outer transaction
        // rollback-only. The catch-set-save-rethrow pattern cannot persist VALIDATION_FAILED
        // because the save is rolled back with the rethrown exception.
        //
        // CBS FUTURE: To persist VALIDATION_FAILED for audit trail, save the INITIATED record
        // in a REQUIRES_NEW transaction before attempting the debit.
        depAcctSvc.withdraw(custAcct, amt, bd,
                rail.getCode() + " outward: " + cpName,
                "CLR-OUT-" + extRef, "SYSTEM");
        String sgl = ClearingGLResolver.getSuspenseGL(
                rail, ClearingDirection.OUTWARD);
        TransactionResult sr = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CLEARING")
                        .transactionType("CLR_OUTWARD_SUSPENSE")
                        .accountReference(custAcct)
                        .amount(amt).valueDate(bd)
                        .branchCode(br.getBranchCode())
                        .narration(rail.getCode()
                                + " outward suspense")
                        .journalLines(List.of(
                                new JournalLineRequest(
                                        GLConstants.BANK_OPERATIONS,
                                        DebitCredit.DEBIT, amt,
                                        "Outward"),
                                new JournalLineRequest(sgl,
                                        DebitCredit.CREDIT, amt,
                                        "Suspense")))
                        .systemGenerated(true)
                        .initiatedBy(user).build());
        saved.setSuspenseJournalId(sr.getJournalEntryId());
        // CBS State Machine: VALIDATED → SUSPENSE_POSTED
        transitionStatus(saved, ClearingStatus.SUSPENSE_POSTED);
        if (rail.requiresCycleNetting())
            linkToCycle(saved, tid, rail, bd);
        clrRepo.save(saved);
        auditSvc.logEvent("ClearingTransaction",
                saved.getId(), "OUTWARD_INITIATED",
                null, extRef, "CLEARING",
                rail.getCode() + " outward INR " + amt);
        return saved;
    }

    /** INWARD: Suspense → Credit Customer → Complete */
    @Transactional
    public ClearingTransaction processInward(
            String extRef, String utr,
            PaymentRail rail, BigDecimal amt,
            String benAcct, String remIfsc,
            String remAcct, String remName,
            String narr, Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        if (clrRepo.existsByTenantIdAndExternalRefNo(tid, extRef))
            throw new BusinessException(
                    "DUPLICATE_CLEARING_REF", extRef);
        DepositAccount ben = depAcctRepo
                .findByTenantIdAndAccountNumber(tid, benAcct)
                .orElseThrow(() -> new BusinessException(
                        "BENEFICIARY_NOT_FOUND", benAcct));
        if (!ben.isCreditAllowed())
            throw new BusinessException(
                    "BENEFICIARY_NOT_CREDITABLE",
                    ben.getAccountStatus().name());
        Branch br = branchRepo.findById(branchId)
                .filter(b -> b.getTenantId().equals(tid)
                        && b.isActive())
                .orElseThrow(() -> new BusinessException(
                        "BRANCH_NOT_FOUND", "" + branchId));
        ClearingTransaction ct = new ClearingTransaction();
        ct.setTenantId(tid);
        ct.setExternalRefNo(extRef);
        ct.setUtrNumber(utr);
        ct.setPaymentRail(rail);
        ct.setDirection(ClearingDirection.INWARD);
        ct.setAmount(amt);
        ct.setCustomerAccountRef(benAcct);
        ct.setCounterpartyIfsc(remIfsc);
        ct.setCounterpartyAccount(remAcct);
        ct.setCounterpartyName(remName);
        ct.setNarration(narr);
        ct.setBranch(br);
        ct.setBranchCode(br.getBranchCode());
        ct.setStatus(ClearingStatus.RECEIVED);
        ct.setValueDate(bd);
        ct.setInitiatedAt(LocalDateTime.now());
        ct.setCreatedBy("SYSTEM");
        ClearingTransaction saved = clrRepo.save(ct);
        // CBS State Machine: RECEIVED → VALIDATED
        transitionStatus(saved, ClearingStatus.VALIDATED);
        // Post to inward suspense GL
        String sgl = ClearingGLResolver.getSuspenseGL(
                rail, ClearingDirection.INWARD);
        TransactionResult suspResult = txnEngine.execute(
                TransactionRequest.builder()
                .sourceModule("CLEARING")
                .transactionType("CLR_INWARD_SUSPENSE")
                .accountReference(benAcct)
                .amount(amt).valueDate(bd)
                .branchCode(br.getBranchCode())
                .narration(rail.getCode() + " inward suspense")
                .journalLines(List.of(
                        new JournalLineRequest(
                                ClearingGLResolver
                                        .getRbiSettlementGL(),
                                DebitCredit.DEBIT, amt,
                                "Inward"),
                        new JournalLineRequest(sgl,
                                DebitCredit.CREDIT, amt,
                                "Suspense")))
                .systemGenerated(true)
                .initiatedBy("SYSTEM").build());
        // CBS: Capture suspense journal ID for reconciliation traceability
        saved.setSuspenseJournalId(
                suspResult.getJournalEntryId());
        // CBS State Machine: VALIDATED → SUSPENSE_POSTED
        transitionStatus(saved, ClearingStatus.SUSPENSE_POSTED);
        // Credit customer and clear suspense
        // CBS: Credit customer and clear suspense GL.
        // Per Finacle CLG_ENGINE: if the credit fails, the entire transaction rolls back
        // (including the suspense posting) because deposit/txnEngine join this @Transactional.
        // The clearing record in SUSPENSE_POSTED state will NOT be persisted — the caller
        // receives a BusinessException and must retry the entire inward processing.
        //
        // CBS FUTURE: To persist CREDIT_FAILED state for investigation, the suspense posting
        // should be committed in a REQUIRES_NEW transaction before attempting the credit.
        // Per Finacle CLG_CYCLE: each state transition is its own committed step.
        // CBS: Use "SYSTEM" channel to bypass branch access validation.
        // Inward clearing is system-initiated (processing payments from RBI/NPCI)
        // and credits customers at ANY branch. Per Finacle CLG_ENGINE: inward credits
        // are not user-initiated — no branch context exists for the clearing system.
        depAcctSvc.deposit(benAcct, amt, bd,
                rail.getCode() + " from " + remName
                        + " UTR:" + utr,
                "CLR-IN-" + extRef, "SYSTEM");
        TransactionResult cr = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CLEARING")
                        .transactionType("CLR_INWARD_CREDIT")
                        .accountReference(benAcct)
                        .amount(amt).valueDate(bd)
                        .branchCode(br.getBranchCode())
                        .narration(rail.getCode()
                                + " inward credit")
                        .journalLines(List.of(
                                new JournalLineRequest(sgl,
                                        DebitCredit.DEBIT, amt,
                                        "Clear suspense"),
                                new JournalLineRequest(
                                        GLConstants
                                                .BANK_OPERATIONS,
                                        DebitCredit.CREDIT, amt,
                                        "Settled")))
                        .systemGenerated(true)
                        .initiatedBy("SYSTEM").build());
        saved.setCreditJournalId(cr.getJournalEntryId());
        // CBS State Machine: SUSPENSE_POSTED → COMPLETED
        // Per ClearingStatus state machine: SUSPENSE_POSTED → COMPLETED is allowed
        // for real-time rails. For NEFT (deferred net), the flow would go through
        // SENT_TO_NETWORK → SETTLED → COMPLETED via confirmOutwardSettlement().
        // Inward processing completes in one step since the credit is immediate.
        transitionStatus(saved, ClearingStatus.COMPLETED);
        saved.setCompletedAt(LocalDateTime.now());
        if (rail.requiresCycleNetting())
            linkToCycle(saved, tid, rail, bd);
        clrRepo.save(saved);
        auditSvc.logEvent("ClearingTransaction",
                saved.getId(),
                saved.getStatus() == ClearingStatus.COMPLETED
                        ? "INWARD_COMPLETED"
                        : "INWARD_FAILED",
                null, extRef, "CLEARING",
                rail.getCode() + " inward INR " + amt);
        return saved;
    }

    /** Confirm outward settlement — clears suspense GL */
    @Transactional
    public ClearingTransaction confirmOutwardSettlement(
            String extRef, String rbiRef) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        ClearingTransaction ct = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef)
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", extRef));
        if (ct.getDirection() != ClearingDirection.OUTWARD)
            throw new BusinessException(
                    "NOT_OUTWARD", "Not outward");
        if (ct.getStatus() != ClearingStatus.SUSPENSE_POSTED
                && ct.getStatus()
                        != ClearingStatus.SENT_TO_NETWORK)
            throw new BusinessException("INVALID_STATUS",
                    "Cannot confirm: " + ct.getStatus());
        String sgl = ClearingGLResolver.getSuspenseGL(
                ct.getPaymentRail(),
                ClearingDirection.OUTWARD);
        TransactionResult sr = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CLEARING")
                        .transactionType(
                                "CLR_SETTLEMENT_CONFIRM")
                        .accountReference(
                                ct.getCustomerAccountRef())
                        .amount(ct.getAmount())
                        .valueDate(bd)
                        .branchCode(ct.getBranchCode())
                        .narration(ct.getPaymentRail()
                                .getCode()
                                + " settlement confirmed")
                        .journalLines(List.of(
                                new JournalLineRequest(sgl,
                                        DebitCredit.DEBIT,
                                        ct.getAmount(),
                                        "Clear suspense"),
                                new JournalLineRequest(
                                        ClearingGLResolver
                                                .getRbiSettlementGL(),
                                        DebitCredit.CREDIT,
                                        ct.getAmount(),
                                        "RBI settlement")))
                        .systemGenerated(true)
                        .initiatedBy("SYSTEM").build());
        ct.setSettlementJournalId(sr.getJournalEntryId());
        ct.setUtrNumber(rbiRef);
        // CBS State Machine: transition through proper states.
        // SUSPENSE_POSTED → COMPLETED (direct, for real-time rails)
        // SENT_TO_NETWORK → SETTLED → COMPLETED (two-step, for deferred rails)
        if (ct.getStatus() == ClearingStatus.SENT_TO_NETWORK) {
            transitionStatus(ct, ClearingStatus.SETTLED);
            ct.setSettledAt(LocalDateTime.now());
        }
        transitionStatus(ct, ClearingStatus.COMPLETED);
        ct.setCompletedAt(LocalDateTime.now());
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "SETTLEMENT_CONFIRMED", null, rbiRef,
                "CLEARING", ct.getPaymentRail().getCode()
                        + " settled: " + extRef);
        return ct;
    }

    /**
     * Reverse a clearing transaction — posts contra GL entries.
     *
     * Per Finacle CLG_REVERSAL / RBI Payment Systems:
     * - Never delete a clearing record (immutable audit trail)
     * - Post contra GL to reverse the suspense posting
     * - If customer was debited (OUTWARD), credit them back
     * - If customer was credited (INWARD), debit them back
     * - Mandatory reason for audit trail
     * - Only non-terminal, non-completed transactions can be reversed
     */
    @Transactional
    public ClearingTransaction reverseClearingTransaction(
            String extRef, String reason) {
        String tid = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        if (reason == null || reason.isBlank())
            throw new BusinessException("REASON_REQUIRED",
                    "Reversal reason mandatory per RBI");
        ClearingTransaction ct = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef)
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", extRef));
        if (ct.isTerminal())
            throw new BusinessException("ALREADY_TERMINAL",
                    "Cannot reverse: " + ct.getStatus());
        // Reverse suspense GL if it was posted
        if (ct.getSuspenseJournalId() != null) {
            String sgl = ClearingGLResolver.getSuspenseGL(
                    ct.getPaymentRail(), ct.getDirection());
            // Contra: swap DR/CR from original suspense
            JournalLineRequest dr;
            JournalLineRequest cr;
            if (ct.getDirection() == ClearingDirection.OUTWARD) {
                // Original: DR BankOps / CR Suspense
                // Contra:   DR Suspense / CR BankOps
                dr = new JournalLineRequest(sgl,
                        DebitCredit.DEBIT, ct.getAmount(),
                        "Reversal suspense");
                cr = new JournalLineRequest(
                        GLConstants.BANK_OPERATIONS,
                        DebitCredit.CREDIT, ct.getAmount(),
                        "Reversal bank ops");
            } else {
                // Original: DR RBI / CR Suspense
                // Contra:   DR Suspense / CR RBI
                dr = new JournalLineRequest(sgl,
                        DebitCredit.DEBIT, ct.getAmount(),
                        "Reversal suspense");
                cr = new JournalLineRequest(
                        ClearingGLResolver.getRbiSettlementGL(),
                        DebitCredit.CREDIT, ct.getAmount(),
                        "Reversal RBI settlement");
            }
            TransactionResult rr = txnEngine.execute(
                    TransactionRequest.builder()
                            .sourceModule("CLEARING")
                            .transactionType("CLR_REVERSAL")
                            .accountReference(
                                    ct.getCustomerAccountRef())
                            .amount(ct.getAmount())
                            .valueDate(bd)
                            .branchCode(ct.getBranchCode())
                            .narration("Clearing reversal: "
                                    + extRef + " — " + reason)
                            .journalLines(List.of(dr, cr))
                            .systemGenerated(false)
                            .initiatedBy(user).build());
            ct.setReversalJournalId(
                    rr.getJournalEntryId());
        }
        // CBS CRITICAL: Restore customer balance if debited (OUTWARD).
        // If the refund fails, the reversal MUST NOT proceed — otherwise
        // the customer loses money AND the clearing is marked REVERSED.
        // Per Finacle CLG_REVERSAL: reversal is atomic — GL + CASA or nothing.
        if (ct.getDirection() == ClearingDirection.OUTWARD
                && ct.getStatus() != ClearingStatus.INITIATED
                && ct.getStatus()
                        != ClearingStatus.VALIDATION_FAILED) {
            // CBS: Use "SYSTEM" channel — reversal refund is system-initiated,
            // customer account may be at a different branch from the operator.
            depAcctSvc.deposit(
                    ct.getCustomerAccountRef(),
                    ct.getAmount(), bd,
                    ct.getPaymentRail().getCode()
                            + " reversal: " + reason,
                    "CLR-REV-" + extRef,
                    "SYSTEM");
            // If deposit() throws, the entire @Transactional rolls back
            // — both the GL reversal and this CASA credit are undone atomically.
        }
        transitionStatus(ct, ClearingStatus.REVERSED);
        ct.setReversalReason(reason);
        ct.setCompletedAt(LocalDateTime.now());
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "CLEARING_REVERSED",
                ct.getPaymentRail().getCode(), extRef,
                "CLEARING", "Reversed: " + reason
                        + " | INR " + ct.getAmount()
                        + " | " + ct.getDirection());
        log.info("Clearing reversed: ref={}, reason={}",
                extRef, reason);
        return ct;
    }

    /**
     * Close a NEFT clearing cycle — no more transactions accepted.
     *
     * Per Finacle CLG_CYCLE / RBI NEFT Settlement Windows:
     * At the end of each half-hourly window, the cycle is closed
     * and the net obligation is calculated. No new transactions
     * can be added to a closed cycle.
     */
    @Transactional
    public ClearingCycle closeClearingCycle(Long cycleId) {
        String tid = TenantContext.getCurrentTenant();
        ClearingCycle cycle = cycleRepo
                .findAndLockById(cycleId)
                .filter(c -> c.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CYCLE_NOT_FOUND", "" + cycleId));
        if (!cycle.isOpen())
            throw new BusinessException("CYCLE_NOT_OPEN",
                    "Cycle " + cycleId + " is "
                            + cycle.getStatus());
        cycle.setStatus("CLOSED");
        cycle.setCycleEndTime(LocalDateTime.now());
        cycleRepo.save(cycle);
        auditSvc.logEvent("ClearingCycle", cycle.getId(),
                "CYCLE_CLOSED", "OPEN", "CLOSED", "CLEARING",
                cycle.getRailType().getCode() + " cycle "
                        + cycle.getCycleNumber()
                        + " closed: netObligation="
                        + cycle.getNetObligation()
                        + ", txnCount="
                        + cycle.getTransactionCount());
        log.info("Cycle closed: id={}, rail={}, net={}",
                cycleId, cycle.getRailType(),
                cycle.getNetObligation());
        return cycle;
    }

    /** Suspense-active statuses — reusable constant for all EOD queries */
    private static final List<ClearingStatus> SUSPENSE_ACTIVE_STATUSES =
            List.of(ClearingStatus.SUSPENSE_POSTED,
                    ClearingStatus.SENT_TO_NETWORK,
                    ClearingStatus.SETTLED);

    /** EOD: per-rail active suspense check */
    @Transactional(readOnly = true)
    public boolean validateAllSuspenseBalances(
            LocalDate bizDate) {
        String tid = TenantContext.getCurrentTenant();
        boolean ok = true;
        for (PaymentRail rail : PaymentRail.values()) {
            long active = clrRepo.countActiveSuspenseByRail(
                    tid, rail, SUSPENSE_ACTIVE_STATUSES);
            if (active > 0) {
                log.warn("Suspense active: rail={}, count={}",
                        rail, active);
                ok = false;
            }
        }
        return ok;
    }

    /**
     * EOD: Detailed per-rail suspense reconciliation.
     *
     * Per Finacle CLG_RECON / RBI Payment Systems:
     * Compares the GL balance of each suspense GL against the
     * sum of active clearing transactions for that rail+direction.
     * Any mismatch indicates a data integrity issue.
     *
     * @return List of discrepancy descriptions (empty = balanced)
     */
    @Transactional(readOnly = true)
    public List<String> reconcileSuspensePerRail(
            LocalDate bizDate) {
        String tid = TenantContext.getCurrentTenant();
        java.util.ArrayList<String> issues =
                new java.util.ArrayList<>();
        for (PaymentRail rail : PaymentRail.values()) {
            for (ClearingDirection dir
                    : ClearingDirection.values()) {
                String glCode = ClearingGLResolver
                        .getSuspenseGL(rail, dir);
                // CBS: Use aggregate query instead of loading all entities.
                // Per Finacle CLG_RECON: reconciliation must be efficient —
                // loading thousands of clearing records into memory for
                // summation is an N+1 anti-pattern. The DB does the SUM.
                BigDecimal clrSum = BigDecimal.ZERO;
                for (ClearingStatus activeStatus
                        : SUSPENSE_ACTIVE_STATUSES) {
                    clrSum = clrSum.add(
                            clrRepo.sumAmountByRailDirectionStatus(
                                    tid, rail, dir,
                                    activeStatus, bizDate));
                }
                // GL balance (credit - debit for liability)
                var gl = glRepo
                        .findByTenantIdAndGlCode(tid, glCode)
                        .orElse(null);
                BigDecimal glNet = gl != null
                        ? gl.getCreditBalance()
                                .subtract(gl.getDebitBalance())
                        : BigDecimal.ZERO;
                if (clrSum.compareTo(glNet) != 0) {
                    String msg = rail + " " + dir
                            + " suspense mismatch: "
                            + "clearing=" + clrSum
                            + ", GL(" + glCode + ")=" + glNet;
                    issues.add(msg);
                    log.warn("RECON: {}", msg);
                }
            }
        }
        if (issues.isEmpty())
            log.info("Clearing suspense reconciliation: "
                    + "all rails balanced");
        return issues;
    }

    /**
     * CBS State Machine: Validate and apply a status transition.
     * Per RBI Payment Systems: no state skipping. Every intermediate state must
     * be recorded for audit trail and TAT tracking.
     *
     * @throws BusinessException if the transition is not allowed
     */
    private void transitionStatus(ClearingTransaction ct,
            ClearingStatus target) {
        ClearingStatus current = ct.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(
                    "INVALID_STATE_TRANSITION",
                    "Cannot transition clearing " + ct.getExternalRefNo()
                            + " from " + current + " to " + target
                            + ". Allowed transitions from " + current
                            + " are defined in ClearingStatus state machine.");
        }
        ct.setStatus(target);
    }

    private void linkToCycle(ClearingTransaction ct,
            String tid, PaymentRail rail, LocalDate bd) {
        ClearingCycle cycle = cycleRepo
                .findOpenCycle(tid, rail, bd)
                .orElseGet(() -> {
                    ClearingCycle c = new ClearingCycle();
                    c.setTenantId(tid);
                    c.setRailType(rail);
                    c.setCycleDate(bd);
                    c.setCycleNumber(cycleRepo
                            .getNextCycleNumber(tid, rail, bd));
                    c.setCycleStartTime(LocalDateTime.now());
                    c.setCreatedBy("SYSTEM");
                    return cycleRepo.save(c);
                });
        ClearingCycle locked = cycleRepo
                .findAndLockById(cycle.getId())
                .orElseThrow();
        // CBS: Guard against TOCTOU race — between findOpenCycle (unlocked read)
        // and findAndLockById (pessimistic lock), another thread may have closed
        // the cycle. Re-validate after acquiring the lock.
        if (!locked.isOpen()) {
            throw new BusinessException(
                    "CYCLE_CLOSED_RACE",
                    "Clearing cycle " + locked.getId()
                            + " was closed between lookup and lock. "
                            + "Retry the clearing transaction.");
        }
        if (ct.getDirection() == ClearingDirection.OUTWARD)
            locked.addOutward(ct.getAmount());
        else
            locked.addInward(ct.getAmount());
        cycleRepo.save(locked);
        ct.setClearingCycle(locked);
    }
}

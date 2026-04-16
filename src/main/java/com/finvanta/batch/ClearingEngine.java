package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ClearingGLResolver;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.ClearingCycle;
import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.entity.DepositAccount;
import com.finvanta.domain.entity.SettlementBatch;
import com.finvanta.domain.enums.ClearingCycleStatus;
import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.PaymentRail;
import com.finvanta.domain.enums.SettlementBatchStatus;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.ClearingCycleRepository;
import com.finvanta.repository.ClearingTransactionRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.SettlementBatchRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.charge.ChargeKernel;
import com.finvanta.domain.enums.ChargeEventType;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import com.finvanta.workflow.ApprovalWorkflowService;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /**
     * Per RBI Payment Systems / Finacle CLG_LIMIT:
     * Outward clearing above this threshold requires maker-checker
     * dual authorization before suspense posting.
     * RTGS minimum is 2L, but maker-checker threshold is 5L
     * (configurable — in production loaded from limit_config table).
     */
    private static final BigDecimal MAKER_CHECKER_THRESHOLD =
            new BigDecimal("500000.00");

    private final ClearingTransactionRepository clrRepo;
    private final ClearingCycleRepository cycleRepo;
    private final DepositAccountRepository depAcctRepo;
    private final DepositAccountService depAcctSvc;
    private final BranchRepository branchRepo;
    private final GLMasterRepository glRepo;
    private final SettlementBatchRepository batchRepo;
    private final TransactionEngine txnEngine;
    private final BusinessDateService bizDateSvc;
    private final AuditService auditSvc;
    private final ApprovalWorkflowService workflowSvc;
    private final ChargeKernel chargeKernel;
    private final ClearingStateManager stateMgr;

    public ClearingEngine(
            ClearingTransactionRepository clrRepo,
            ClearingCycleRepository cycleRepo,
            DepositAccountRepository depAcctRepo,
            DepositAccountService depAcctSvc,
            BranchRepository branchRepo,
            GLMasterRepository glRepo,
            SettlementBatchRepository batchRepo,
            TransactionEngine txnEngine,
            BusinessDateService bizDateSvc,
            AuditService auditSvc,
            ApprovalWorkflowService workflowSvc,
            ChargeKernel chargeKernel,
            ClearingStateManager stateMgr) {
        this.clrRepo = clrRepo;
        this.cycleRepo = cycleRepo;
        this.depAcctRepo = depAcctRepo;
        this.depAcctSvc = depAcctSvc;
        this.branchRepo = branchRepo;
        this.glRepo = glRepo;
        this.batchRepo = batchRepo;
        this.txnEngine = txnEngine;
        this.bizDateSvc = bizDateSvc;
        this.auditSvc = auditSvc;
        this.workflowSvc = workflowSvc;
        this.chargeKernel = chargeKernel;
        this.stateMgr = stateMgr;
    }

    /**
     * OUTWARD: Validate, Debit Customer, Post Suspense.
     *
     * Per Finacle CLG_STATE_MGR: NOT @Transactional — orchestrates
     * independently committed steps:
     *   Step 1: persistInitialState() — INITIATED committed (REQUIRES_NEW)
     *   Step 2: debitAndPostOutwardSuspense() — SUSPENSE_POSTED committed (REQUIRES_NEW)
     *
     * If Step 2 fails (insufficient balance, account frozen), INITIATED record
     * survives with VALIDATION_FAILED state for investigation.
     */
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
        // CBS: Per-rail daily outward limit per RBI / Finacle ACCTLIMIT
        validateDailyRailLimit(tid, custAcct, rail, amt, bd);
        Branch br = branchRepo.findById(branchId)
                .filter(b -> b.getTenantId().equals(tid)
                        && b.isActive())
                .orElseThrow(() -> new BusinessException(
                        "BRANCH_NOT_FOUND", "" + branchId));

        // === Step 1: Persist INITIATED record (REQUIRES_NEW — committed) ===
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
        ClearingTransaction saved =
                stateMgr.persistInitialState(ct);

        // CBS Maker-Checker: High-value outward payments require dual authorization.
        if (amt.compareTo(MAKER_CHECKER_THRESHOLD) >= 0) {
            String payload = rail.getCode() + "|" + amt
                    + "|" + custAcct + "|" + cpIfsc
                    + "|" + cpAcct + "|" + cpName;
            workflowSvc.initiateApproval(
                    "ClearingTransaction", saved.getId(),
                    "CLR_OUTWARD_HIGH_VALUE",
                    "High-value " + rail.getCode()
                            + " outward INR " + amt
                            + " to " + cpName,
                    payload);
            auditSvc.logEvent("ClearingTransaction",
                    saved.getId(),
                    "MAKER_CHECKER_PENDING",
                    null, extRef, "CLEARING",
                    rail.getCode() + " INR " + amt
                            + " — pending checker approval");
            log.info("High-value clearing parked: ref={}, "
                    + "amt={}, threshold={}",
                    extRef, amt, MAKER_CHECKER_THRESHOLD);
            return saved;
        }

        // === Step 2: Debit + suspense GL (REQUIRES_NEW — committed) ===
        // If debit fails (insufficient balance, frozen account),
        // INITIATED record survives with VALIDATION_FAILED state.
        try {
            saved = stateMgr.debitAndPostOutwardSuspense(
                    saved.getId(), custAcct, amt, rail,
                    cpName, extRef, br.getBranchCode(), user);
        } catch (Exception e) {
            log.error("Outward debit failed: ref={}, err={}",
                    extRef, e.getMessage());
            stateMgr.markValidationFailed(saved.getId(),
                    "Debit failed: " + e.getMessage());
            throw new BusinessException(
                    "OUTWARD_DEBIT_FAILED",
                    "Debit failed for " + extRef
                            + ": " + e.getMessage());
        }

        // CBS: Post-debit ancillary operations (cycle linking, charge levy) are
        // non-critical — the customer debit and suspense GL are already committed
        // via ClearingStateManager (REQUIRES_NEW). If cycle linking or charge levy
        // fails, the clearing transaction is still valid and will proceed to network.
        // Per Finacle CLG_ENGINE: ancillary failures are logged, not propagated.
        if (rail.requiresCycleNetting()) {
            try {
                linkToCycle(saved, tid, rail, bd);
            } catch (Exception e) {
                log.warn("Cycle linking failed for {}: {} — "
                        + "clearing proceeds without cycle",
                        extRef, e.getMessage());
                auditSvc.logEvent("ClearingTransaction",
                        saved.getId(), "CYCLE_LINK_FAILED",
                        null, extRef, "CLEARING",
                        "Cycle link failed: " + e.getMessage());
            }
        }
        // CBS: Levy outward clearing charge per Finacle CHG_ENGINE.
        try {
            ChargeEventType chargeEvent = switch (rail) {
                case NEFT -> ChargeEventType.NEFT_OUTWARD;
                case RTGS -> ChargeEventType.RTGS_OUTWARD;
                case IMPS -> ChargeEventType.IMPS_OUTWARD;
                case UPI -> ChargeEventType.UPI_OUTWARD;
            };
            chargeKernel.levyCharge(chargeEvent, custAcct,
                    GLConstants.SB_DEPOSITS, amt, null,
                    "CLEARING", extRef, br.getBranchCode());
        } catch (Exception e) {
            log.warn("Charge levy failed for {}: {} — "
                    + "clearing proceeds without charge",
                    extRef, e.getMessage());
            auditSvc.logEvent("ClearingTransaction",
                    saved.getId(), "CHARGE_LEVY_FAILED",
                    null, extRef, "CLEARING",
                    "Charge levy failed: " + e.getMessage());
        }
        auditSvc.logEvent("ClearingTransaction",
                saved.getId(), "OUTWARD_INITIATED",
                null, extRef, "CLEARING",
                rail.getCode() + " outward INR " + amt);
        return saved;
    }

    /**
     * Checker approves a high-value outward clearing transaction.
     *
     * Per Finacle CLG_STATE_MGR: NOT @Transactional — orchestrates
     * independently committed steps:
     *   Step 1: Approve workflow (committed by ApprovalWorkflowService)
     *   Step 2: Record checker + debit + suspense via ClearingStateManager (REQUIRES_NEW)
     *
     * If Step 2 fails (insufficient balance), the workflow approval survives
     * (it was committed in its own transaction). The INITIATED record gets
     * VALIDATION_FAILED state. The checker can investigate and retry.
     */
    public ClearingTransaction approveOutward(
            String extRef, Long workflowId,
            String checkerRemarks) {
        String tid = TenantContext.getCurrentTenant();
        String checker = SecurityUtil.getCurrentUsername();

        // Step 1: Approve the workflow — committed independently
        workflowSvc.approve(workflowId, checkerRemarks);

        ClearingTransaction ct = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef)
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", extRef));
        if (ct.getStatus() != ClearingStatus.INITIATED)
            throw new BusinessException("INVALID_STATUS",
                    "Expected INITIATED for approval, got: "
                            + ct.getStatus());

        // Step 2: Debit + suspense via ClearingStateManager (REQUIRES_NEW)
        try {
            ClearingTransaction saved =
                    stateMgr.debitAndPostOutwardSuspense(
                            ct.getId(),
                            ct.getCustomerAccountRef(),
                            ct.getAmount(),
                            ct.getPaymentRail(),
                            ct.getCounterpartyName(),
                            extRef,
                            ct.getBranchCode(),
                            checker);
            // Record checker identity in a separate save
            saved.setCheckerId(checker);
            saved.setCheckerApprovedAt(LocalDateTime.now());
            clrRepo.save(saved);

            if (saved.getPaymentRail().requiresCycleNetting())
                linkToCycle(saved, tid,
                        saved.getPaymentRail(),
                        bizDateSvc.getCurrentBusinessDate());
            auditSvc.logEvent("ClearingTransaction",
                    saved.getId(), "OUTWARD_APPROVED",
                    "INITIATED", extRef, "CLEARING",
                    saved.getPaymentRail().getCode()
                            + " outward INR "
                            + saved.getAmount()
                            + " approved by " + checker);
            log.info("High-value clearing approved: "
                    + "ref={}, checker={}",
                    extRef, checker);
            return saved;
        } catch (Exception e) {
            log.error("Outward debit failed after approval: "
                    + "ref={}, err={}",
                    extRef, e.getMessage());
            stateMgr.markValidationFailed(ct.getId(),
                    "Debit failed after checker approval: "
                            + e.getMessage());
            throw new BusinessException(
                    "OUTWARD_DEBIT_FAILED",
                    "Debit failed for " + extRef
                            + " after approval: "
                            + e.getMessage());
        }
    }

    /**
     * INWARD: Suspense → Credit Customer → Complete.
     *
     * Per Finacle CLG_STATE_MGR: NOT @Transactional — this method orchestrates
     * three independently committed steps via ClearingStateManager:
     *   Step 1: persistInitialState() — commits RECEIVED record (REQUIRES_NEW)
     *   Step 2: postInwardSuspense() — commits SUSPENSE_POSTED + GL (REQUIRES_NEW)
     *   Step 3: creditAndComplete() — commits CREDITED → COMPLETED (REQUIRES_NEW)
     *
     * If Step 3 fails, Step 2's SUSPENSE_POSTED state survives in the database.
     * Operations can see the stuck transaction and investigate/retry/return.
     * This eliminates the "invisible stuck transaction" problem.
     */
    public ClearingTransaction processInward(
            String extRef, String utr,
            PaymentRail rail, BigDecimal amt,
            String benAcct, String remIfsc,
            String remAcct, String remName,
            String narr, Long branchId) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();

        // CBS CRITICAL: Validate amount BEFORE idempotency check.
        // A null amt would NPE in resumption paths (postInwardSuspense, creditAndComplete)
        // and a negative amt would reverse the GL direction (debit instead of credit).
        // Per Finacle CLG_ENGINE: fail-fast on invalid input before any DB lookup.
        if (amt == null || amt.signum() <= 0)
            throw new BusinessException(
                    "INVALID_AMOUNT", "positive required");

        // === P1 Item 2: Idempotent Retry / Resumption ===
        // Per Finacle CLG_ENGINE: if a previous attempt failed mid-flow,
        // the same extRef retry should RESUME from the last committed state
        // instead of rejecting as duplicate. This handles:
        // - DB timeout after suspense posted but before credit
        // - Network adapter retry after transient failure
        // - Manual re-submission after investigation
        var existing = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef);
        if (existing.isPresent()) {
            ClearingTransaction ex = existing.get();
            if (ex.isTerminal()) {
                // Already completed/reversed/returned — true duplicate
                throw new BusinessException(
                        "DUPLICATE_CLEARING_REF",
                        extRef + " already " + ex.getStatus());
            }
            // Resume from last committed checkpoint
            if (ex.getStatus() == ClearingStatus.SUSPENSE_POSTED
                    || ex.getStatus()
                            == ClearingStatus.CREDIT_FAILED) {
                // Suspense already posted — retry credit only
                log.info("Resuming inward from {}: ref={}",
                        ex.getStatus(), extRef);
                try {
                    return stateMgr.creditAndComplete(
                            ex.getId(), remName, utr);
                } catch (Exception e) {
                    stateMgr.markCreditFailed(ex.getId(),
                            "Retry credit failed: "
                                    + e.getMessage());
                    throw new BusinessException(
                            "INWARD_CREDIT_FAILED",
                            "Retry failed: " + e.getMessage());
                }
            }
            if (ex.getStatus() == ClearingStatus.RECEIVED
                    || ex.getStatus()
                            == ClearingStatus.VALIDATED) {
                // Record exists but suspense not posted — retry from Step 2
                log.info("Resuming inward from {}: ref={}",
                        ex.getStatus(), extRef);
                var resumed = stateMgr.postInwardSuspense(
                        ex.getId(), rail, benAcct, amt,
                        ex.getBranchCode());
                try {
                    return stateMgr.creditAndComplete(
                            resumed.getId(), remName, utr);
                } catch (Exception e) {
                    stateMgr.markCreditFailed(resumed.getId(),
                            "Retry credit failed: "
                                    + e.getMessage());
                    throw new BusinessException(
                            "INWARD_CREDIT_FAILED",
                            "Retry failed: " + e.getMessage());
                }
            }
            // Any other non-terminal state — reject as in-progress
            throw new BusinessException(
                    "CLEARING_IN_PROGRESS",
                    extRef + " is in " + ex.getStatus()
                            + " state");
        }

        // Amount already validated at method entry (before idempotency check)
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

        // === Step 1: Persist RECEIVED record (REQUIRES_NEW — committed) ===
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
        ClearingTransaction saved =
                stateMgr.persistInitialState(ct);

        // === Step 2: Post suspense GL (REQUIRES_NEW — committed) ===
        // If this fails, RECEIVED record survives for investigation.
        saved = stateMgr.postInwardSuspense(
                saved.getId(), rail, benAcct, amt,
                br.getBranchCode());

        // === Step 3: Credit customer (REQUIRES_NEW — committed) ===
        // If this fails, SUSPENSE_POSTED state survives — operations
        // can see the stuck transaction and the suspense GL balance
        // reflects the pending credit. markCreditFailed() persists
        // the failure reason for investigation.
        try {
            saved = stateMgr.creditAndComplete(
                    saved.getId(), remName, utr);
        } catch (Exception e) {
            // CBS: Credit failed — persist CREDIT_FAILED state.
            // The SUSPENSE_POSTED GL is already committed.
            // Operations can investigate and retry or return.
            log.error("Inward credit failed: ref={}, err={}",
                    extRef, e.getMessage());
            stateMgr.markCreditFailed(saved.getId(),
                    "Credit failed: " + e.getMessage());
            throw new BusinessException(
                    "INWARD_CREDIT_FAILED",
                    "Credit failed for " + extRef
                            + ": " + e.getMessage());
        }

        if (rail.requiresCycleNetting())
            linkToCycle(saved, tid, rail, bd);
        auditSvc.logEvent("ClearingTransaction",
                saved.getId(), "INWARD_COMPLETED",
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

        // CBS: Create SettlementBatch — RBI legal proof of inter-bank settlement.
        // Per Finacle SETTLEMENT_MASTER: every confirmed settlement gets a batch
        // record linking the RBI reference to the clearing transaction(s).
        // For RTGS/IMPS/UPI: one batch per transaction (gross settlement).
        // For NEFT: one batch per clearing cycle (net settlement) — future.
        SettlementBatch batch = new SettlementBatch();
        batch.setTenantId(tid);
        batch.setRailType(ct.getPaymentRail());
        batch.setSettlementDate(bd);
        batch.setTotalNetAmount(ct.getAmount());
        batch.setTransactionCount(1);
        batch.setRbiSettlementRef(rbiRef);
        batch.setStatus(SettlementBatchStatus.CONFIRMED);
        batch.setConfirmedAt(LocalDateTime.now());
        batch.setSettlementJournalId(
                sr.getJournalEntryId());
        batch.setCreatedBy("SYSTEM");
        SettlementBatch savedBatch = batchRepo.save(batch);
        ct.setSettlementBatch(savedBatch);

        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "SETTLEMENT_CONFIRMED", null, rbiRef,
                "CLEARING", ct.getPaymentRail().getCode()
                        + " settled: " + extRef
                        + " | batchId=" + savedBatch.getId());
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
        computeAuditHash(ct);
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
     * Mark outward clearing as sent to payment network.
     *
     * Per Finacle CLG_ENGINE / RBI Payment Systems:
     * After suspense posting, the transaction is submitted to the RBI/NPCI
     * payment network. This intermediate state is required for:
     * - TAT tracking (time between posting and network submission)
     * - Network timeout monitoring
     * - Retry logic for failed submissions
     *
     * State: SUSPENSE_POSTED → SENT_TO_NETWORK
     */
    @Transactional
    public ClearingTransaction sendToNetwork(
            String extRef) {
        String tid = TenantContext.getCurrentTenant();
        ClearingTransaction ct = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef)
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", extRef));
        if (ct.getDirection() != ClearingDirection.OUTWARD)
            throw new BusinessException(
                    "NOT_OUTWARD",
                    "sendToNetwork is for outward only");
        transitionStatus(ct,
                ClearingStatus.SENT_TO_NETWORK);
        ct.setSentToNetworkAt(LocalDateTime.now());
        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "SENT_TO_NETWORK", "SUSPENSE_POSTED",
                "SENT_TO_NETWORK", "CLEARING",
                ct.getPaymentRail().getCode()
                        + " sent to network: " + extRef);
        log.info("Clearing sent to network: ref={}, rail={}",
                extRef, ct.getPaymentRail());
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
        cycle.setStatus(ClearingCycleStatus.CLOSED);
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

    // ========================================================================
    // PHASE 2: NEFT Cycle Submission/Settlement, Inward Return,
    //          Network Timeout Escalation, Per-Rail Daily Limits
    // ========================================================================

    /** Submit closed NEFT cycle to RBI. State: CLOSED → SUBMITTED */
    @Transactional
    public ClearingCycle submitCycleToRbi(Long cycleId) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        ClearingCycle cycle = cycleRepo
                .findAndLockById(cycleId)
                .filter(c -> c.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CYCLE_NOT_FOUND", "" + cycleId));
        if (cycle.getStatus() != ClearingCycleStatus.CLOSED)
            throw new BusinessException("CYCLE_NOT_CLOSED",
                    "Expected CLOSED, got: "
                            + cycle.getStatus());
        BigDecimal netObligation = cycle.getNetObligation();
        if (netObligation.signum() != 0) {
            String sgl = ClearingGLResolver.getSuspenseGL(
                    cycle.getRailType(),
                    ClearingDirection.OUTWARD);
            BigDecimal absNet = netObligation.abs();
            JournalLineRequest dr;
            JournalLineRequest cr;
            if (netObligation.signum() > 0) {
                dr = new JournalLineRequest(sgl,
                        DebitCredit.DEBIT, absNet,
                        "NEFT cycle net settlement");
                cr = new JournalLineRequest(
                        ClearingGLResolver.getRbiSettlementGL(),
                        DebitCredit.CREDIT, absNet,
                        "Net obligation to RBI");
            } else {
                dr = new JournalLineRequest(
                        ClearingGLResolver.getRbiSettlementGL(),
                        DebitCredit.DEBIT, absNet,
                        "Net receivable from RBI");
                cr = new JournalLineRequest(sgl,
                        DebitCredit.CREDIT, absNet,
                        "NEFT cycle net settlement");
            }
            txnEngine.execute(TransactionRequest.builder()
                    .sourceModule("CLEARING")
                    .transactionType("CLR_CYCLE_SUBMISSION")
                    .accountReference("CYCLE-" + cycleId)
                    .amount(absNet).valueDate(bd)
                    .branchCode("HQ001")
                    .narration(cycle.getRailType().getCode()
                            + " cycle " + cycle.getCycleNumber()
                            + " net settlement")
                    .journalLines(List.of(dr, cr))
                    .systemGenerated(true)
                    .initiatedBy("SYSTEM").build());
        }
        cycle.setStatus(ClearingCycleStatus.SUBMITTED);
        cycleRepo.save(cycle);
        auditSvc.logEvent("ClearingCycle", cycle.getId(),
                "CYCLE_SUBMITTED", "CLOSED", "SUBMITTED",
                "CLEARING",
                cycle.getRailType().getCode() + " cycle "
                        + cycle.getCycleNumber()
                        + " submitted: net=" + netObligation);
        log.info("Cycle submitted: id={}, net={}",
                cycleId, netObligation);
        return cycle;
    }

    /** Confirm RBI settlement for NEFT cycle. State: SUBMITTED → SETTLED */
    @Transactional
    public ClearingCycle settleCycle(Long cycleId, String rbiRef) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        ClearingCycle cycle = cycleRepo.findAndLockById(cycleId)
                .filter(c -> c.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CYCLE_NOT_FOUND", "" + cycleId));
        if (cycle.getStatus() != ClearingCycleStatus.SUBMITTED)
            throw new BusinessException("CYCLE_NOT_SUBMITTED",
                    "Expected SUBMITTED, got: " + cycle.getStatus());
        cycle.setStatus(ClearingCycleStatus.SETTLED);
        cycle.setSettlementReference(rbiRef);
        cycleRepo.save(cycle);
        var cycleTxns = clrRepo
                .findByTenantIdAndClearingCycleIdOrderByInitiatedAtAsc(tid, cycleId);
        int completed = 0;
        for (var ct : cycleTxns) {
            if (ct.getDirection() == ClearingDirection.OUTWARD
                    && ct.getStatus() == ClearingStatus.SENT_TO_NETWORK) {
                transitionStatus(ct, ClearingStatus.SETTLED);
                ct.setSettledAt(LocalDateTime.now());
                transitionStatus(ct, ClearingStatus.COMPLETED);
                ct.setCompletedAt(LocalDateTime.now());
                ct.setUtrNumber(rbiRef);
                SettlementBatch batch = new SettlementBatch();
                batch.setTenantId(tid);
                batch.setRailType(ct.getPaymentRail());
                batch.setSettlementDate(bd);
                batch.setTotalNetAmount(ct.getAmount());
                batch.setTransactionCount(1);
                batch.setRbiSettlementRef(rbiRef);
                batch.setStatus(SettlementBatchStatus.CONFIRMED);
                batch.setConfirmedAt(LocalDateTime.now());
                batch.setCreatedBy("SYSTEM");
                ct.setSettlementBatch(batchRepo.save(batch));
                computeAuditHash(ct);
                clrRepo.save(ct);
                completed++;
            }
        }
        auditSvc.logEvent("ClearingCycle", cycle.getId(),
                "CYCLE_SETTLED", "SUBMITTED", "SETTLED", "CLEARING",
                cycle.getRailType().getCode() + " cycle "
                        + cycle.getCycleNumber() + " settled: rbiRef="
                        + rbiRef + ", completed=" + completed);
        log.info("Cycle settled: id={}, rbiRef={}, completed={}",
                cycleId, rbiRef, completed);
        return cycle;
    }

    /**
     * Return an inward clearing transaction to originating bank.
     * Per Finacle CLG_RETURN: reverses suspense GL, transitions to RETURNED.
     * Only for pre-CREDITED transactions. If already CREDITED, use reverse.
     */
    @Transactional
    public ClearingTransaction returnInward(String extRef, String reason) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        if (reason == null || reason.isBlank())
            throw new BusinessException("REASON_REQUIRED",
                    "Return reason mandatory per RBI");
        ClearingTransaction ct = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef)
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", extRef));
        if (ct.getDirection() != ClearingDirection.INWARD)
            throw new BusinessException("NOT_INWARD",
                    "returnInward is for inward only");
        if (ct.isTerminal())
            throw new BusinessException("ALREADY_TERMINAL",
                    ct.getStatus().name());
        if (ct.getStatus() == ClearingStatus.CREDITED
                || ct.getStatus() == ClearingStatus.COMPLETED)
            throw new BusinessException("ALREADY_CREDITED",
                    "Customer already credited. Use reverseClearingTransaction()");
        if (ct.getSuspenseJournalId() != null) {
            String sgl = ClearingGLResolver.getSuspenseGL(
                    ct.getPaymentRail(), ClearingDirection.INWARD);
            txnEngine.execute(TransactionRequest.builder()
                    .sourceModule("CLEARING")
                    .transactionType("CLR_RETURN")
                    .accountReference(ct.getCustomerAccountRef())
                    .amount(ct.getAmount()).valueDate(bd)
                    .branchCode(ct.getBranchCode())
                    .narration("Inward return: " + reason)
                    .journalLines(List.of(
                            new JournalLineRequest(sgl,
                                    DebitCredit.DEBIT, ct.getAmount(),
                                    "Return suspense"),
                            new JournalLineRequest(
                                    ClearingGLResolver.getRbiSettlementGL(),
                                    DebitCredit.CREDIT, ct.getAmount(),
                                    "Return to RBI")))
                    .systemGenerated(true)
                    .initiatedBy("SYSTEM").build());
        }
        if (ct.getStatus() == ClearingStatus.SUSPENSE_POSTED) {
            transitionStatus(ct, ClearingStatus.CREDIT_FAILED);
        } else if (ct.getStatus() == ClearingStatus.VALIDATED
                || ct.getStatus() == ClearingStatus.RECEIVED) {
            transitionStatus(ct, ClearingStatus.VALIDATION_FAILED);
        }
        transitionStatus(ct, ClearingStatus.RETURNED);
        ct.setFailureReason(reason);
        ct.setCompletedAt(LocalDateTime.now());
        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "INWARD_RETURNED", ct.getPaymentRail().getCode(),
                extRef, "CLEARING",
                "Returned: " + reason + " | INR " + ct.getAmount());
        log.info("Inward returned: ref={}, reason={}", extRef, reason);
        return ct;
    }

    /**
     * Escalate outward transactions stuck in SENT_TO_NETWORK.
     * Per Finacle CLG_MONITOR / RBI TAT:
     * RTGS: 30 min, NEFT: 60 min, IMPS/UPI: 5 min.
     * @return Number of transactions escalated
     */
    @Transactional
    public int escalateStuckNetworkTransactions() {
        String tid = TenantContext.getCurrentTenant();
        int escalated = 0;
        for (PaymentRail rail : PaymentRail.values()) {
            int timeoutMinutes = switch (rail) {
                case RTGS -> 30;
                case IMPS, UPI -> 5;
                case NEFT -> 60;
            };
            LocalDateTime cutoff = LocalDateTime.now()
                    .minusMinutes(timeoutMinutes);
            var stuck = clrRepo.findStuckInNetworkBefore(
                    tid, ClearingStatus.SENT_TO_NETWORK, cutoff);
            for (var ct : stuck) {
                if (ct.getPaymentRail() != rail) continue;
                auditSvc.logEvent("ClearingTransaction", ct.getId(),
                        "NETWORK_TIMEOUT_ESCALATED",
                        "SENT_TO_NETWORK",
                        String.valueOf(timeoutMinutes), "CLEARING",
                        rail.getCode() + " stuck > " + timeoutMinutes
                                + "min: " + ct.getExternalRefNo()
                                + " | INR " + ct.getAmount());
                log.warn("NETWORK TIMEOUT: ref={}, rail={}, sent={}, timeout={}min",
                        ct.getExternalRefNo(), rail,
                        ct.getSentToNetworkAt(), timeoutMinutes);
                escalated++;
            }
        }
        if (escalated > 0)
            log.warn("Network timeout: {} escalated", escalated);
        return escalated;
    }

    /**
     * Validates per-rail daily outward limit for a customer.
     * Per RBI / Finacle ACCTLIMIT:
     * IMPS: INR 5L/day, UPI: INR 1L/day per account.
     * NEFT/RTGS: no daily cap (RTGS has minimum only).
     */
    void validateDailyRailLimit(String tid, String custAcct,
            PaymentRail rail, BigDecimal amt, LocalDate bd) {
        BigDecimal limit = DAILY_OUTWARD_LIMITS.get(rail);
        if (limit == null) return;
        BigDecimal dailyTotal = clrRepo.sumDailyAmountByAccountAndRail(
                tid, custAcct, rail, ClearingDirection.OUTWARD,
                bd, LIMIT_EXCLUDED_STATUSES);
        if (dailyTotal.add(amt).compareTo(limit) > 0)
            throw new BusinessException("DAILY_RAIL_LIMIT_EXCEEDED",
                    rail.getCode() + " daily limit INR " + limit
                            + " exceeded for " + custAcct
                            + ". Today: INR " + dailyTotal
                            + ", requested: INR " + amt);
    }

    /** Per-rail daily outward limits per RBI */
    private static final java.util.Map<PaymentRail, BigDecimal>
            DAILY_OUTWARD_LIMITS = java.util.Map.of(
                    PaymentRail.IMPS, new BigDecimal("500000.00"),
                    PaymentRail.UPI, new BigDecimal("100000.00"));

    /** Statuses excluded from daily limit aggregation */
    private static final List<ClearingStatus> LIMIT_EXCLUDED_STATUSES =
            List.of(ClearingStatus.VALIDATION_FAILED,
                    ClearingStatus.REVERSED,
                    ClearingStatus.RETURNED);

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
     * CBS CRITICAL: Uses date-UNFILTERED aggregate (sumActiveSuspenseByRailAndDirection)
     * because GLMaster running totals are cumulative across all dates. The date-filtered
     * variant (sumAmountByRailDirectionStatus) would miss active suspense from prior
     * business dates — e.g., NEFT submitted at 5:30 PM yesterday, settled this morning —
     * causing false reconciliation mismatches. This is consistent with
     * validateAllSuspenseBalances which also uses a date-unfiltered count.
     *
     * @param bizDate Business date (retained for audit logging, not used in query)
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
                            clrRepo.sumActiveSuspenseByRailAndDirection(
                                    tid, rail, dir,
                                    activeStatus));
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

    /**
     * Computes SHA-256 tamper-detection hash over immutable clearing fields.
     *
     * Per RBI IT Governance Direction 2023 §8.3:
     * Financial records must have integrity checks to detect unauthorized
     * modification. The hash covers all critical business fields that must
     * NOT change after initial creation (amount, accounts, counterparty,
     * rail, direction). Status and timestamps are excluded because they
     * change legitimately during the lifecycle.
     *
     * The hash is recomputed at each state transition. If a previous hash
     * exists and differs from the recomputed value, it indicates tampering
     * of the immutable fields between transitions.
     */
    private void computeAuditHash(ClearingTransaction ct) {
        try {
            String payload = ct.getTenantId()
                    + "|" + ct.getExternalRefNo()
                    + "|" + ct.getPaymentRail()
                    + "|" + ct.getDirection()
                    + "|" + ct.getAmount().toPlainString()
                    + "|" + ct.getCustomerAccountRef()
                    + "|" + ct.getCounterpartyIfsc()
                    + "|" + ct.getCounterpartyAccount()
                    + "|" + ct.getValueDate()
                    + "|" + ct.getBranchCode();
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            String newHash = hex.toString();
            // CBS: Tamper detection — if hash was previously set
            // and now differs, the immutable fields were modified.
            if (ct.getAuditHash() != null
                    && !ct.getAuditHash().equals(newHash)) {
                log.error("TAMPER DETECTED: Clearing {} hash "
                        + "mismatch. Previous={}, Computed={}. "
                        + "Immutable fields may have been "
                        + "modified outside the engine.",
                        ct.getExternalRefNo(),
                        ct.getAuditHash(), newHash);
                auditSvc.logEvent("ClearingTransaction",
                        ct.getId(), "TAMPER_DETECTED",
                        ct.getAuditHash(), newHash,
                        "SECURITY",
                        "Audit hash mismatch on clearing "
                                + ct.getExternalRefNo());
            }
            ct.setAuditHash(newHash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JVM per Java spec
            throw new IllegalStateException(
                    "SHA-256 not available", e);
        }
    }

    private void linkToCycle(ClearingTransaction ct,
            String tid, PaymentRail rail, LocalDate bd) {
        ClearingCycle cycle = cycleRepo
                .findOpenCycle(tid, rail, bd,
                        ClearingCycleStatus.OPEN)
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

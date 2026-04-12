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
            ApprovalWorkflowService workflowSvc) {
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
        computeAuditHash(saved);

        // CBS Maker-Checker: High-value outward payments require dual authorization.
        // Per RBI Payment Systems / Finacle CLG_LIMIT: transactions above the
        // threshold are parked in INITIATED state with a pending approval workflow.
        // The checker calls approveOutward() which resumes processing.
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
            clrRepo.save(saved);
            return saved;
        }

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
        computeAuditHash(saved);
        clrRepo.save(saved);
        auditSvc.logEvent("ClearingTransaction",
                saved.getId(), "OUTWARD_INITIATED",
                null, extRef, "CLEARING",
                rail.getCode() + " outward INR " + amt);
        return saved;
    }

    /**
     * Checker approves a high-value outward clearing transaction.
     *
     * Per RBI Payment Systems / Finacle CLG_LIMIT:
     * After maker-checker approval, this method resumes the outward flow:
     * INITIATED → VALIDATED → debit customer → post suspense → SUSPENSE_POSTED.
     *
     * The checker's identity is recorded on the ClearingTransaction for audit.
     * Self-approval is blocked by ApprovalWorkflowService.approve().
     */
    @Transactional
    public ClearingTransaction approveOutward(
            String extRef, Long workflowId,
            String checkerRemarks) {
        String tid = TenantContext.getCurrentTenant();
        String checker = SecurityUtil.getCurrentUsername();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();

        // CBS: Approve the workflow first — blocks self-approval
        workflowSvc.approve(workflowId, checkerRemarks);

        ClearingTransaction ct = clrRepo
                .findByTenantIdAndExternalRefNo(tid, extRef)
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", extRef));
        if (ct.getStatus() != ClearingStatus.INITIATED)
            throw new BusinessException("INVALID_STATUS",
                    "Expected INITIATED for approval, got: "
                            + ct.getStatus());
        ct.setCheckerId(checker);
        ct.setCheckerApprovedAt(LocalDateTime.now());

        // Resume outward flow: INITIATED → VALIDATED → debit → suspense
        transitionStatus(ct, ClearingStatus.VALIDATED);
        depAcctSvc.withdraw(
                ct.getCustomerAccountRef(),
                ct.getAmount(), bd,
                ct.getPaymentRail().getCode()
                        + " outward: "
                        + ct.getCounterpartyName(),
                "CLR-OUT-" + extRef, "SYSTEM");
        String sgl = ClearingGLResolver.getSuspenseGL(
                ct.getPaymentRail(),
                ClearingDirection.OUTWARD);
        TransactionResult sr = txnEngine.execute(
                TransactionRequest.builder()
                        .sourceModule("CLEARING")
                        .transactionType(
                                "CLR_OUTWARD_SUSPENSE")
                        .accountReference(
                                ct.getCustomerAccountRef())
                        .amount(ct.getAmount())
                        .valueDate(bd)
                        .branchCode(ct.getBranchCode())
                        .narration(ct.getPaymentRail()
                                .getCode()
                                + " outward suspense"
                                + " (checker approved)")
                        .journalLines(List.of(
                                new JournalLineRequest(
                                        GLConstants
                                                .BANK_OPERATIONS,
                                        DebitCredit.DEBIT,
                                        ct.getAmount(),
                                        "Outward"),
                                new JournalLineRequest(sgl,
                                        DebitCredit.CREDIT,
                                        ct.getAmount(),
                                        "Suspense")))
                        .systemGenerated(true)
                        .initiatedBy(checker).build());
        ct.setSuspenseJournalId(sr.getJournalEntryId());
        transitionStatus(ct, ClearingStatus.SUSPENSE_POSTED);
        if (ct.getPaymentRail().requiresCycleNetting())
            linkToCycle(ct, tid,
                    ct.getPaymentRail(), bd);
        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "OUTWARD_APPROVED",
                "INITIATED", extRef, "CLEARING",
                ct.getPaymentRail().getCode()
                        + " outward INR " + ct.getAmount()
                        + " approved by " + checker);
        log.info("High-value clearing approved: ref={}, "
                + "checker={}", extRef, checker);
        return ct;
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
        // CBS: Defensive input validation — inward clearing is system-initiated
        // from RBI/NPCI network adapter, but null/negative amounts must never
        // reach CASA deposit(). A negative amount would subtract from the
        // customer's ledger balance (BigDecimal.add(negative)), effectively
        // debiting them instead of crediting. A null causes NPE downstream.
        // RTGS minimum is NOT enforced on inward — the originating bank owns
        // that check per RBI Payment Systems Act. Rejecting a valid sub-2L
        // RTGS inward from RBI would strand funds in the network.
        if (amt == null || amt.signum() <= 0)
            throw new BusinessException(
                    "INVALID_AMOUNT", "positive required");
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
        computeAuditHash(saved);
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

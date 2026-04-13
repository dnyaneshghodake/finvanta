package com.finvanta.batch;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.ClearingGLResolver;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.ClearingTransaction;
import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.PaymentRail;
import com.finvanta.repository.ClearingTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS Clearing State Manager per Finacle CLG_STATE_MGR.
 *
 * Provides REQUIRES_NEW transaction boundaries for clearing state transitions.
 * Each method commits independently — if a later step fails, the earlier
 * committed state survives for operational investigation and retry.
 *
 * Per Finacle CLG_CYCLE: each state transition is its own committed step.
 * This prevents the "invisible stuck transaction" problem where a mid-flow
 * failure rolls back the entire clearing record including already-posted GL.
 *
 * Architecture:
 *   ClearingEngine (orchestrator, no @Transactional on multi-step methods)
 *     → calls ClearingStateManager (each method = REQUIRES_NEW)
 *       → state transition committed independently
 *       → if next step fails, previous state is visible for investigation
 */
@Service
public class ClearingStateManager {

    private static final Logger log =
            LoggerFactory.getLogger(ClearingStateManager.class);

    private final ClearingTransactionRepository clrRepo;
    private final TransactionEngine txnEngine;
    private final DepositAccountService depAcctSvc;
    private final BusinessDateService bizDateSvc;
    private final AuditService auditSvc;

    public ClearingStateManager(
            ClearingTransactionRepository clrRepo,
            TransactionEngine txnEngine,
            DepositAccountService depAcctSvc,
            BusinessDateService bizDateSvc,
            AuditService auditSvc) {
        this.clrRepo = clrRepo;
        this.txnEngine = txnEngine;
        this.depAcctSvc = depAcctSvc;
        this.bizDateSvc = bizDateSvc;
        this.auditSvc = auditSvc;
    }

    /** Persist initial clearing record. REQUIRES_NEW: survives subsequent failures. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClearingTransaction persistInitialState(ClearingTransaction ct) {
        ClearingTransaction saved = clrRepo.save(ct);
        computeAuditHash(saved);
        clrRepo.save(saved);
        return saved;
    }

    /** Post inward suspense GL. REQUIRES_NEW: if credit fails, suspense survives. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClearingTransaction postInwardSuspense(
            Long clearingId, PaymentRail rail, String benAcct,
            BigDecimal amt, String branchCode) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        ClearingTransaction ct = loadClearing(tid, clearingId);
        transitionStatus(ct, ClearingStatus.VALIDATED);
        String sgl = ClearingGLResolver.getSuspenseGL(rail, ClearingDirection.INWARD);
        TransactionResult sr = txnEngine.execute(TransactionRequest.builder()
                .sourceModule("CLEARING").transactionType("CLR_INWARD_SUSPENSE")
                .accountReference(benAcct).amount(amt).valueDate(bd)
                .branchCode(branchCode).narration(rail.getCode() + " inward suspense")
                .journalLines(List.of(
                        new JournalLineRequest(ClearingGLResolver.getRbiSettlementGL(),
                                DebitCredit.DEBIT, amt, "Inward"),
                        new JournalLineRequest(sgl, DebitCredit.CREDIT, amt, "Suspense")))
                .systemGenerated(true).initiatedBy("SYSTEM").build());
        ct.setSuspenseJournalId(sr.getJournalEntryId());
        transitionStatus(ct, ClearingStatus.SUSPENSE_POSTED);
        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "SUSPENSE_COMMITTED", "RECEIVED", "SUSPENSE_POSTED", "CLEARING",
                rail.getCode() + " suspense committed | " + ct.getExternalRefNo());
        return ct;
    }

    /** Credit customer and complete. REQUIRES_NEW: if fails, SUSPENSE_POSTED survives. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClearingTransaction creditAndComplete(
            Long clearingId, String remName, String utr) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        ClearingTransaction ct = loadClearing(tid, clearingId);
        if (ct.getStatus() != ClearingStatus.SUSPENSE_POSTED)
            throw new BusinessException("INVALID_STATUS",
                    "Expected SUSPENSE_POSTED, got: " + ct.getStatus());
        String sgl = ClearingGLResolver.getSuspenseGL(
                ct.getPaymentRail(), ClearingDirection.INWARD);
        depAcctSvc.deposit(ct.getCustomerAccountRef(), ct.getAmount(), bd,
                ct.getPaymentRail().getCode() + " from " + remName + " UTR:" + utr,
                "CLR-IN-" + ct.getExternalRefNo(), "SYSTEM");
        TransactionResult cr = txnEngine.execute(TransactionRequest.builder()
                .sourceModule("CLEARING").transactionType("CLR_INWARD_CREDIT")
                .accountReference(ct.getCustomerAccountRef())
                .amount(ct.getAmount()).valueDate(bd).branchCode(ct.getBranchCode())
                .narration(ct.getPaymentRail().getCode() + " inward credit")
                .journalLines(List.of(
                        new JournalLineRequest(sgl, DebitCredit.DEBIT,
                                ct.getAmount(), "Clear suspense"),
                        new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                                DebitCredit.CREDIT, ct.getAmount(), "Settled")))
                .systemGenerated(true).initiatedBy("SYSTEM").build());
        ct.setCreditJournalId(cr.getJournalEntryId());
        transitionStatus(ct, ClearingStatus.CREDITED);
        transitionStatus(ct, ClearingStatus.COMPLETED);
        ct.setCompletedAt(LocalDateTime.now());
        computeAuditHash(ct);
        clrRepo.save(ct);
        return ct;
    }

    /** Mark CREDIT_FAILED. REQUIRES_NEW: persists failure for investigation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCreditFailed(Long clearingId, String reason) {
        String tid = TenantContext.getCurrentTenant();
        ClearingTransaction ct = loadClearing(tid, clearingId);
        if (ct.getStatus() != ClearingStatus.SUSPENSE_POSTED) return;
        transitionStatus(ct, ClearingStatus.CREDIT_FAILED);
        ct.setFailureReason(reason);
        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "CREDIT_FAILED", "SUSPENSE_POSTED", "CREDIT_FAILED", "CLEARING",
                "Credit failed: " + reason + " | " + ct.getExternalRefNo());
        log.warn("Credit failed: ref={}, reason={}", ct.getExternalRefNo(), reason);
    }

    /** Debit customer + post outward suspense. REQUIRES_NEW. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClearingTransaction debitAndPostOutwardSuspense(
            Long clearingId, String custAcct, BigDecimal amt,
            PaymentRail rail, String cpName, String extRef,
            String branchCode, String user) {
        String tid = TenantContext.getCurrentTenant();
        LocalDate bd = bizDateSvc.getCurrentBusinessDate();
        ClearingTransaction ct = loadClearing(tid, clearingId);
        transitionStatus(ct, ClearingStatus.VALIDATED);
        depAcctSvc.withdraw(custAcct, amt, bd,
                rail.getCode() + " outward: " + cpName,
                "CLR-OUT-" + extRef, "SYSTEM");
        String sgl = ClearingGLResolver.getSuspenseGL(rail, ClearingDirection.OUTWARD);
        TransactionResult sr = txnEngine.execute(TransactionRequest.builder()
                .sourceModule("CLEARING").transactionType("CLR_OUTWARD_SUSPENSE")
                .accountReference(custAcct).amount(amt).valueDate(bd)
                .branchCode(branchCode).narration(rail.getCode() + " outward suspense")
                .journalLines(List.of(
                        new JournalLineRequest(GLConstants.BANK_OPERATIONS,
                                DebitCredit.DEBIT, amt, "Outward"),
                        new JournalLineRequest(sgl, DebitCredit.CREDIT, amt, "Suspense")))
                .systemGenerated(true).initiatedBy(user).build());
        ct.setSuspenseJournalId(sr.getJournalEntryId());
        transitionStatus(ct, ClearingStatus.SUSPENSE_POSTED);
        computeAuditHash(ct);
        clrRepo.save(ct);
        return ct;
    }

    /** Mark VALIDATION_FAILED. REQUIRES_NEW: persists failure when debit fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markValidationFailed(Long clearingId, String reason) {
        String tid = TenantContext.getCurrentTenant();
        ClearingTransaction ct = loadClearing(tid, clearingId);
        if (ct.getStatus() != ClearingStatus.INITIATED
                && ct.getStatus() != ClearingStatus.VALIDATED)
            return;
        // CBS: Capture old status BEFORE transition for accurate audit trail.
        // Per RBI IT Governance §8.3: audit logs must record the true before/after state.
        String oldStatus = ct.getStatus().name();
        transitionStatus(ct, ClearingStatus.VALIDATION_FAILED);
        ct.setFailureReason(reason);
        computeAuditHash(ct);
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "VALIDATION_FAILED", oldStatus,
                "VALIDATION_FAILED", "CLEARING",
                "Validation failed: " + reason + " | " + ct.getExternalRefNo());
    }

    private ClearingTransaction loadClearing(String tid, Long id) {
        return clrRepo.findById(id)
                .filter(c -> c.getTenantId().equals(tid))
                .orElseThrow(() -> new BusinessException(
                        "CLEARING_NOT_FOUND", "" + id));
    }

    private void transitionStatus(ClearingTransaction ct, ClearingStatus target) {
        if (!ct.getStatus().canTransitionTo(target))
            throw new BusinessException("INVALID_STATE_TRANSITION",
                    ct.getExternalRefNo() + " from " + ct.getStatus() + " to " + target);
        ct.setStatus(target);
    }

    private void computeAuditHash(ClearingTransaction ct) {
        try {
            String payload = ct.getTenantId() + "|" + ct.getExternalRefNo()
                    + "|" + ct.getPaymentRail() + "|" + ct.getDirection()
                    + "|" + ct.getAmount().toPlainString()
                    + "|" + ct.getCustomerAccountRef()
                    + "|" + ct.getCounterpartyIfsc()
                    + "|" + ct.getCounterpartyAccount()
                    + "|" + ct.getValueDate() + "|" + ct.getBranchCode();
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            String newHash = hex.toString();
            if (ct.getAuditHash() != null && !ct.getAuditHash().equals(newHash)) {
                log.error("TAMPER DETECTED: {} hash mismatch", ct.getExternalRefNo());
                auditSvc.logEvent("ClearingTransaction", ct.getId(),
                        "TAMPER_DETECTED", ct.getAuditHash(), newHash, "SECURITY",
                        "Hash mismatch on " + ct.getExternalRefNo());
            }
            ct.setAuditHash(newHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

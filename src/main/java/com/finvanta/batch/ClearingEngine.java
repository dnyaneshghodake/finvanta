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
        try {
            depAcctSvc.withdraw(custAcct, amt, bd,
                    rail.getCode() + " outward: " + cpName,
                    "CLR-OUT-" + extRef, rail.getCode());
        } catch (Exception e) {
            saved.setStatus(ClearingStatus.VALIDATION_FAILED);
            saved.setFailureReason(
                    "Debit failed: " + e.getMessage());
            clrRepo.save(saved);
            throw new BusinessException(
                    "OUTWARD_DEBIT_FAILED", e.getMessage());
        }
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
        saved.setStatus(ClearingStatus.SUSPENSE_POSTED);
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
        // Post to inward suspense GL
        String sgl = ClearingGLResolver.getSuspenseGL(
                rail, ClearingDirection.INWARD);
        txnEngine.execute(TransactionRequest.builder()
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
        saved.setStatus(ClearingStatus.SUSPENSE_POSTED);
        // Credit customer and clear suspense
        try {
            depAcctSvc.deposit(benAcct, amt, bd,
                    rail.getCode() + " from " + remName
                            + " UTR:" + utr,
                    "CLR-IN-" + extRef, rail.getCode());
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
            saved.setStatus(ClearingStatus.COMPLETED);
            saved.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            saved.setStatus(ClearingStatus.CREDIT_FAILED);
            saved.setFailureReason(
                    "Credit failed: " + e.getMessage());
        }
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
        ct.setStatus(ClearingStatus.COMPLETED);
        ct.setSettledAt(LocalDateTime.now());
        ct.setCompletedAt(LocalDateTime.now());
        clrRepo.save(ct);
        auditSvc.logEvent("ClearingTransaction", ct.getId(),
                "SETTLEMENT_CONFIRMED", null, rbiRef,
                "CLEARING", ct.getPaymentRail().getCode()
                        + " settled: " + extRef);
        return ct;
    }

    /** EOD: per-rail active suspense check */
    @Transactional(readOnly = true)
    public boolean validateAllSuspenseBalances(
            LocalDate bizDate) {
        String tid = TenantContext.getCurrentTenant();
        boolean ok = true;
        for (PaymentRail rail : PaymentRail.values()) {
            long active = clrRepo.countActiveSuspenseByRail(
                    tid, rail);
            if (active > 0) {
                log.warn("Suspense active: rail={}, count={}",
                        rail, active);
                ok = false;
            }
        }
        return ok;
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
        if (ct.getDirection() == ClearingDirection.OUTWARD)
            locked.addOutward(ct.getAmount());
        else
            locked.addInward(ct.getAmount());
        cycleRepo.save(locked);
        ct.setClearingCycle(locked);
    }
}

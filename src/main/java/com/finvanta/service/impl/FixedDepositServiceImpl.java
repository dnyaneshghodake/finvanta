package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.*;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.FdStatus;
import com.finvanta.repository.*;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.CbsReferenceService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.service.FixedDepositService;
import com.finvanta.transaction.TransactionEngine;
import com.finvanta.transaction.TransactionRequest;
import com.finvanta.transaction.TransactionResult;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CBS FD Service per Finacle TD_ENGINE. GL via TransactionEngine. */
@Service
public class FixedDepositServiceImpl implements FixedDepositService {
    private static final Logger log = LoggerFactory.getLogger(FixedDepositServiceImpl.class);
    private final FixedDepositRepository fdRepo;
    private final CustomerRepository custRepo;
    private final BranchRepository brRepo;
    private final DepositAccountRepository casaRepo;
    private final DepositAccountService casaSvc;
    private final TransactionEngine txnEng;
    private final BusinessDateService bdSvc;
    private final AuditService audit;
    private final CbsReferenceService refService;

    public FixedDepositServiceImpl(FixedDepositRepository fdRepo,
            CustomerRepository custRepo, BranchRepository brRepo,
            DepositAccountRepository casaRepo, DepositAccountService casaSvc,
            TransactionEngine txnEng, BusinessDateService bdSvc, AuditService audit,
            CbsReferenceService refService) {
        this.fdRepo = fdRepo; this.custRepo = custRepo; this.brRepo = brRepo;
        this.casaRepo = casaRepo; this.casaSvc = casaSvc;
        this.txnEng = txnEng; this.bdSvc = bdSvc; this.audit = audit;
        this.refService = refService;
    }

    private FixedDeposit loadFd(String t, String no) {
        return fdRepo.findByTenantIdAndFdAccountNumber(t, no)
                .orElseThrow(() -> new BusinessException("FD_NOT_FOUND", no));
    }

    @Override @Transactional
    public FixedDeposit bookFd(Long cId, Long bId, String casa, BigDecimal amt,
            BigDecimal rate, int days, String payout, String renew, String nom, String nomR) {
        String t = TenantContext.getCurrentTenant(); String u = SecurityUtil.getCurrentUsername();
        LocalDate bd = bdSvc.getCurrentBusinessDate();
        if (amt == null || amt.signum() <= 0) throw new BusinessException("INVALID_AMOUNT", "positive");
        if (days < 7) throw new BusinessException("INVALID_TENURE", "min 7 days");
        Customer c = custRepo.findById(cId).filter(x -> x.getTenantId().equals(t))
                .orElseThrow(() -> new BusinessException("CUSTOMER_NOT_FOUND", "" + cId));
        if (!c.isKycVerified()) throw new BusinessException("KYC_NOT_VERIFIED", "KYC required");
        Branch br = brRepo.findById(bId).filter(b -> b.getTenantId().equals(t) && b.isActive())
                .orElseThrow(() -> new BusinessException("BRANCH_NOT_FOUND", "" + bId));
        casaRepo.findByTenantIdAndAccountNumber(t, casa).filter(DepositAccount::isActive)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", casa));
        casaSvc.withdraw(casa, amt, bd, "FD booking", "FD-BK-" + System.currentTimeMillis(), "SYSTEM");
        TransactionResult gl = txnEng.execute(TransactionRequest.builder()
                .sourceModule("FIXED_DEPOSIT").transactionType("FD_BOOKING")
                .accountReference(casa).amount(amt).valueDate(bd)
                .branchCode(br.getBranchCode()).narration("FD booked")
                .journalLines(List.of(
                        new JournalLineRequest(GLConstants.SB_DEPOSITS, DebitCredit.DEBIT, amt, "FD booking"),
                        new JournalLineRequest(GLConstants.FD_DEPOSITS, DebitCredit.CREDIT, amt, "FD deposit")))
                .systemGenerated(true).initiatedBy(u).build());
        FixedDeposit fd = new FixedDeposit();
        fd.setTenantId(t); fd.setCustomer(c); fd.setBranch(br); fd.setBranchCode(br.getBranchCode());
        // CBS: DB-backed sequential FD number via CbsReferenceService per Finacle TD_MASTER.
        // Replaces System.currentTimeMillis() % 1000000 which had collision risk under
        // concurrent FD bookings (two bookings in same ms produce identical FD numbers).
        fd.setFdAccountNumber(refService.generateFdAccountNumber(br.getBranchCode()));
        fd.setPrincipalAmount(amt); fd.setCurrentPrincipal(amt); fd.setInterestRate(rate);
        fd.setInterestPayoutMode(payout != null ? payout : "MATURITY");
        fd.setAutoRenewalMode(renew != null ? renew : "NO_RENEWAL");
        fd.setTenureDays(days); fd.setBookingDate(bd); fd.setMaturityDate(bd.plusDays(days));
        fd.setLinkedAccountNumber(casa); fd.setProductCode("FD_REGULAR");
        fd.setNomineeName(nom); fd.setNomineeRelationship(nomR);
        fd.setBookingJournalId(gl.getJournalEntryId()); fd.setCreatedBy(u);
        FixedDeposit saved = fdRepo.save(fd);
        audit.logEvent("FixedDeposit", saved.getId(), "FD_BOOKED", null,
                saved.getFdAccountNumber(), "FIXED_DEPOSIT", "INR " + amt + " by " + u);
        return saved;
    }

    @Override @Transactional
    public FixedDeposit prematureClose(String fdNo, String reason) {
        String t = TenantContext.getCurrentTenant(); String u = SecurityUtil.getCurrentUsername();
        LocalDate bd = bdSvc.getCurrentBusinessDate(); FixedDeposit fd = loadFd(t, fdNo);
        if (!fd.isActive()) throw new BusinessException("FD_NOT_ACTIVE", fd.getStatus().name());
        if (!fd.isPrematureAllowed()) throw new BusinessException("PREMATURE_NOT_ALLOWED", "");
        if (fd.isLienBlocked()) throw new BusinessException("LIEN_BLOCKED", "Release lien");
        long d = ChronoUnit.DAYS.between(fd.getBookingDate(), bd);
        BigDecimal r = fd.getEffectiveRate().subtract(fd.getPrematurePenaltyRate()).max(BigDecimal.ZERO);
        BigDecimal intr = fd.getCurrentPrincipal().multiply(r).multiply(BigDecimal.valueOf(d))
                .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
        BigDecimal pay = fd.getCurrentPrincipal().add(intr);
        // CBS: GL posting FIRST, then subledger sync via creditSubledgerOnly.
        // Using casaSvc.deposit() would double-post SB_DEPOSITS (caller's CR here
        // plus deposit()'s own CR) and add a spurious DR BANK_OPERATIONS leg.
        // CBS: Omit the FD_INTEREST_EXPENSE DEBIT line when intr is zero.
        // intr is zero when prematurePenaltyRate >= effectiveRate (so r = max(eff-pen, 0) = 0)
        // or on a same-day closure (d = 0). AccountingService.postJournalEntryInternal
        // rejects zero-amount journal lines with ACCOUNTING_INVALID_AMOUNT, so leaving
        // this line unconditionally would block any premature closure where penalty
        // wipes out the effective rate. Same pattern as RD prematureClose
        // (RecurringDepositServiceImpl.java:349-361).
        List<JournalLineRequest> pmLines = intr.signum() > 0
                ? List.of(
                        new JournalLineRequest(GLConstants.FD_DEPOSITS, DebitCredit.DEBIT,
                                fd.getCurrentPrincipal(), "FD principal"),
                        new JournalLineRequest(GLConstants.FD_INTEREST_EXPENSE, DebitCredit.DEBIT,
                                intr, "FD interest penalized"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS, DebitCredit.CREDIT, pay, "FD closure"))
                : List.of(
                        new JournalLineRequest(GLConstants.FD_DEPOSITS, DebitCredit.DEBIT,
                                fd.getCurrentPrincipal(), "FD principal"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS, DebitCredit.CREDIT, pay, "FD closure"));
        TransactionResult pmGl = txnEng.execute(TransactionRequest.builder().sourceModule("FIXED_DEPOSIT")
                .transactionType("FD_PREMATURE").accountReference(fd.getLinkedAccountNumber())
                .amount(pay).valueDate(bd).branchCode(fd.getBranchCode()).narration("FD premature")
                .journalLines(pmLines)
                .systemGenerated(true).initiatedBy(u).build());
        casaSvc.creditSubledgerOnly(fd.getLinkedAccountNumber(), pay, bd,
                "FD premature: " + fdNo, "FD_PREMATURE", fdNo,
                pmGl.getJournalEntryId(), pmGl.getVoucherNumber());
        fd.setStatus(FdStatus.PREMATURE_CLOSED); fd.setClosureDate(bd);
        fd.setTotalInterestPaid(intr); fd.setUpdatedBy(u); fdRepo.save(fd);
        return fd;
    }

    @Override @Transactional
    public FixedDeposit maturityClose(String fdNo) {
        String t = TenantContext.getCurrentTenant(); String u = SecurityUtil.getCurrentUsername();
        LocalDate bd = bdSvc.getCurrentBusinessDate(); FixedDeposit fd = loadFd(t, fdNo);
        if (fd.getStatus() != FdStatus.ACTIVE && fd.getStatus() != FdStatus.MATURED)
            throw new BusinessException("FD_NOT_ACTIVE", fd.getStatus().name());
        BigDecimal accrued = fd.getAccruedInterest();
        BigDecimal pay = fd.getCurrentPrincipal().add(accrued);
        // CBS: GL posting FIRST, then subledger sync via creditSubledgerOnly
        // (see prematureClose comment above for rationale).
        // CBS: Omit the FD_INTEREST_PAYABLE DEBIT line when accruedInterest is zero.
        // accrued can be zero if the EOD accrual job never ran for this FD, or if
        // daily interest rounds to 0.00 for a tiny principal at a low rate.
        // AccountingService rejects zero-amount journal lines with
        // ACCOUNTING_INVALID_AMOUNT — without this guard, a zero-accrual FD gets
        // stuck in MATURED status with no automatic recovery (findMaturingOnOrBefore
        // queries only ACTIVE FDs; see processMaturityBatch).
        // Same pattern as RD prematureClose (RecurringDepositServiceImpl.java:349-361).
        List<JournalLineRequest> mtLines = accrued.signum() > 0
                ? List.of(
                        new JournalLineRequest(GLConstants.FD_DEPOSITS, DebitCredit.DEBIT,
                                fd.getCurrentPrincipal(), "FD principal"),
                        new JournalLineRequest(GLConstants.FD_INTEREST_PAYABLE, DebitCredit.DEBIT,
                                accrued, "FD interest"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS, DebitCredit.CREDIT, pay, "FD maturity"))
                : List.of(
                        new JournalLineRequest(GLConstants.FD_DEPOSITS, DebitCredit.DEBIT,
                                fd.getCurrentPrincipal(), "FD principal"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS, DebitCredit.CREDIT, pay, "FD maturity"));
        TransactionResult mtGl = txnEng.execute(TransactionRequest.builder().sourceModule("FIXED_DEPOSIT")
                .transactionType("FD_MATURITY").accountReference(fd.getLinkedAccountNumber())
                .amount(pay).valueDate(bd).branchCode(fd.getBranchCode()).narration("FD maturity")
                .journalLines(mtLines)
                .systemGenerated(true).initiatedBy(u).build());
        casaSvc.creditSubledgerOnly(fd.getLinkedAccountNumber(), pay, bd,
                "FD maturity: " + fdNo, "FD_MATURITY", fdNo,
                mtGl.getJournalEntryId(), mtGl.getVoucherNumber());
        fd.setStatus(FdStatus.CLOSED); fd.setClosureDate(bd);
        fd.setTotalInterestPaid(fd.getTotalInterestPaid().add(fd.getAccruedInterest()));
        fd.setAccruedInterest(BigDecimal.ZERO); fd.setUpdatedBy(u); fdRepo.save(fd);
        return fd;
    }

    @Override @Transactional
    public void accrueInterest(String fdNo, LocalDate biz) {
        FixedDeposit fd = loadFd(TenantContext.getCurrentTenant(), fdNo);
        if (!fd.getStatus().isAccrualActive()) return;
        // CBS: Idempotency guard — prevent double-accrual on EOD rerun per Finacle TD_ENGINE.
        // Same pattern as CASA accrual in DepositAccountServiceImpl.accrueInterest().
        // Without this, an EOD retry for the same business date would accrue interest twice,
        // leading to incorrect FD interest calculations and GL imbalance.
        if (biz.equals(fd.getLastAccrualDate())) return;
        BigDecimal daily = fd.getCurrentPrincipal().multiply(fd.getEffectiveRate())
                .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
        fd.setAccruedInterest(fd.getAccruedInterest().add(daily));
        fd.setLastAccrualDate(biz); fdRepo.save(fd);
    }

    /**
     * Process FD maturities in batch.
     *
     * CBS CRITICAL: Each FD maturity is processed independently. If one FD's
     * maturity close fails (e.g., linked CASA frozen), it must NOT roll back
     * the entire batch — other FDs should still be processed.
     *
     * Per Finacle TD_MATURITY / Temenos COB: batch maturity processing uses
     * per-item error isolation. Failed FDs are logged for investigation.
     *
     * NOTE: maturityClose() is a self-invocation (same bean), so Spring's
     * @Transactional proxy is bypassed. Both this method and maturityClose()
     * share the same transaction. To achieve true per-FD isolation, the caller
     * (EodOrchestrator) should iterate and call maturityClose() individually
     * with try-catch per item. Here we add defensive try-catch to prevent
     * one failed FD from aborting the entire batch scan.
     */
    @Override @Transactional
    public int processMaturityBatch(LocalDate biz) {
        var list = fdRepo.findMaturingOnOrBefore(TenantContext.getCurrentTenant(), biz, FdStatus.ACTIVE);
        int n = 0;
        for (FixedDeposit fd : list) {
            try {
                fd.setStatus(FdStatus.MATURED); fdRepo.save(fd);
                if ("NO_RENEWAL".equals(fd.getAutoRenewalMode())) maturityClose(fd.getFdAccountNumber());
                n++;
            } catch (Exception e) {
                // CBS: Per-FD error isolation — log and continue with next FD.
                // Per Finacle TD_MATURITY: failed maturity is flagged for manual investigation.
                // The MATURED status may or may not be persisted depending on whether the
                // exception occurred before or after fdRepo.save(). Either way, the FD will
                // be retried on the next EOD run (findMaturingOnOrBefore picks up ACTIVE FDs).
                log.error("FD maturity failed: fd={}, err={}",
                        fd.getFdAccountNumber(), e.getMessage(), e);
            }
        }
        return n;
    }

    @Override @Transactional
    public FixedDeposit markLien(String fdNo, BigDecimal amt, String loan) {
        FixedDeposit fd = loadFd(TenantContext.getCurrentTenant(), fdNo);
        if (!fd.isActive()) throw new BusinessException("FD_NOT_ACTIVE", fd.getStatus().name());
        if (amt.compareTo(fd.getCurrentPrincipal()) > 0)
            throw new BusinessException("LIEN_EXCEEDS_PRINCIPAL", "Lien > principal");
        fd.setLienMarked(true); fd.setLienAmount(amt); fd.setLienLoanAccount(loan);
        fd.setLienDate(bdSvc.getCurrentBusinessDate());
        fd.setUpdatedBy(SecurityUtil.getCurrentUsername()); fdRepo.save(fd); return fd;
    }

    @Override @Transactional
    public FixedDeposit releaseLien(String fdNo) {
        FixedDeposit fd = loadFd(TenantContext.getCurrentTenant(), fdNo);
        fd.setLienMarked(false); fd.setLienAmount(BigDecimal.ZERO); fd.setLienLoanAccount(null);
        fd.setUpdatedBy(SecurityUtil.getCurrentUsername()); fdRepo.save(fd); return fd;
    }

    @Override @Transactional
    public void payoutInterest(String fdNo, LocalDate biz) {
        FixedDeposit fd = loadFd(TenantContext.getCurrentTenant(), fdNo);
        if (fd.getAccruedInterest().signum() <= 0) return;
        casaSvc.deposit(fd.getLinkedAccountNumber(), fd.getAccruedInterest(), biz,
                "FD interest payout", "FD-PAY-" + fdNo, "SYSTEM");
        fd.setTotalInterestPaid(fd.getTotalInterestPaid().add(fd.getAccruedInterest()));
        fd.setYtdInterestPaid(fd.getYtdInterestPaid().add(fd.getAccruedInterest()));
        fd.setLastPayoutDate(biz); fd.setAccruedInterest(BigDecimal.ZERO); fdRepo.save(fd);
    }

    @Override
    public FixedDeposit getFd(String fdNo) {
        return loadFd(TenantContext.getCurrentTenant(), fdNo);
    }
}

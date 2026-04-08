package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.StandingInstruction;
import com.finvanta.domain.enums.SIFrequency;
import com.finvanta.domain.enums.SIStatus;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.StandingInstructionRepository;
import com.finvanta.service.DepositAccountService;
import com.finvanta.service.LoanAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.ReferenceGenerator;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * CBS Standing Instruction Execution Engine per Finacle SI_MASTER.
 *
 * FINANCIAL SAFETY:
 * 1. ATOMIC: CASA debit + loan repayment in SAME transaction (REQUIRES_NEW).
 * 2. IDEMPOTENT: Key "SI-{ref}-{date}" prevents double-debit on EOD retry.
 * 3. DYNAMIC EMI: Amount fetched from LoanAccount at execution (never cached).
 * 4. MIN BALANCE: SI respects CASA minimum balance per RBI norms.
 * 5. PRIORITY: LOAN_EMI(1) before UTILITY(5) per Finacle SI_MASTER.
 *
 * IMPORTANT: Uses self-proxy pattern for executeSingleSI() so that
 * @Transactional(REQUIRES_NEW) is intercepted by Spring AOP.
 * Without this, self-invocation from executeAllDueSIs() would bypass
 * the proxy and REQUIRES_NEW would be silently ignored — breaking
 * per-SI transaction isolation and atomicity guarantees.
 */
@Service
public class StandingInstructionServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(StandingInstructionServiceImpl.class);

    private final StandingInstructionRepository siRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final LoanAccountService loanAccountService;
    private final DepositAccountService depositAccountService;
    private final AuditService auditService;

    /**
     * Self-proxy for per-SI transaction isolation via Spring AOP.
     * Without this, executeSingleSI()'s @Transactional(REQUIRES_NEW) would be
     * bypassed on self-invocation from executeAllDueSIs(), causing ALL SIs to
     * share one transaction — financially unsafe (one failure rolls back all).
     */
    private final StandingInstructionServiceImpl self;

    public StandingInstructionServiceImpl(StandingInstructionRepository siRepository,
                                           LoanAccountRepository loanAccountRepository,
                                           DepositAccountRepository depositAccountRepository,
                                           LoanAccountService loanAccountService,
                                           DepositAccountService depositAccountService,
                                           AuditService auditService,
                                           @Lazy StandingInstructionServiceImpl self) {
        this.siRepository = siRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.loanAccountService = loanAccountService;
        this.depositAccountService = depositAccountService;
        this.auditService = auditService;
        this.self = self;
    }

    @Transactional
    public StandingInstruction createLoanEmiSI(LoanAccount loanAccount, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String casaAccNo = loanAccount.getDisbursementAccountNumber();
        if (casaAccNo == null || casaAccNo.isBlank()) return null;
        if (siRepository.existsActiveLoanEmiSI(tenantId, loanAccount.getAccountNumber())) return null;

        int execDay = loanAccount.getNextEmiDate() != null
            ? Math.min(loanAccount.getNextEmiDate().getDayOfMonth(), 28) : 5;
        LocalDate firstEmi = loanAccount.getNextEmiDate() != null
            ? loanAccount.getNextEmiDate() : businessDate.plusMonths(1);

        StandingInstruction si = new StandingInstruction();
        si.setTenantId(tenantId);
        si.setSiReference(ReferenceGenerator.generateTransactionRef().replace("TXN", "SI"));
        si.setCustomer(loanAccount.getCustomer());
        si.setSourceAccountNumber(casaAccNo);
        si.setDestinationType("LOAN_EMI");
        si.setDestinationAccountNumber(loanAccount.getAccountNumber());
        si.setLoanAccountNumber(loanAccount.getAccountNumber());
        si.setAmount(null);
        si.setFrequency(SIFrequency.MONTHLY);
        si.setExecutionDay(execDay);
        si.setStartDate(firstEmi);
        si.setEndDate(loanAccount.getMaturityDate());
        si.setNextExecutionDate(firstEmi);
        si.setStatus(SIStatus.ACTIVE);
        si.setPriority(1);
        si.setNarration("EMI auto-debit for loan " + loanAccount.getAccountNumber());
        si.setCreatedBy("SYSTEM");

        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", saved.getId(), "SI_CREATED",
            null, saved.getSiReference(), "STANDING_INSTRUCTION",
            "LOAN_EMI SI: " + saved.getSiReference() + " | Loan: " + loanAccount.getAccountNumber()
                + " | CASA: " + casaAccNo + " | First EMI: " + firstEmi);
        log.info("LOAN_EMI SI created: si={}, loan={}, casa={}", saved.getSiReference(), loanAccount.getAccountNumber(), casaAccNo);
        return saved;
    }

    /**
     * EOD: Execute all due SIs. NOT @Transactional — each SI gets its own
     * REQUIRES_NEW transaction via self-proxy call to executeSingleSI().
     * @return int[2]: [0]=executed, [1]=failed
     */
    public int[] executeAllDueSIs(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        List<StandingInstruction> dueSIs = siRepository.findDueSIs(tenantId, businessDate);
        int executed = 0, failed = 0;
        for (StandingInstruction si : dueSIs) {
            try {
                // CBS: MUST use self-proxy so @Transactional(REQUIRES_NEW) is intercepted.
                // Direct call (this.executeSingleSI) would bypass Spring AOP proxy.
                if (self.executeSingleSI(si, businessDate)) { executed++; } else { failed++; }
            } catch (Exception e) {
                failed++;
                log.error("SI fatal: si={}, error={}", si.getSiReference(), e.getMessage(), e);
            }
        }
        log.info("SI EOD: date={}, executed={}, failed={}, total={}", businessDate, executed, failed, dueSIs.size());
        return new int[]{executed, failed};
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean executeSingleSI(StandingInstruction si, LocalDate businessDate) {
        String idempotencyKey = "SI-" + si.getSiReference() + "-" + businessDate;
        try {
            if (si.isLoanEmi()) return executeLoanEmiSI(si, businessDate, si.getTenantId(), idempotencyKey);
            else if (si.isInternalTransfer()) return executeTransferSI(si, businessDate, idempotencyKey);
            else { markSkipped(si, businessDate, "Type not implemented"); return false; }
        } catch (BusinessException e) {
            return handleFailure(si, businessDate, e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            return handleFailure(si, businessDate, "SYSTEM_ERROR", e.getMessage());
        }
    }

    private boolean executeLoanEmiSI(StandingInstruction si, LocalDate businessDate,
                                      String tenantId, String idempotencyKey) {
        LoanAccount loan = loanAccountRepository.findByTenantIdAndAccountNumber(tenantId, si.getLoanAccountNumber())
            .orElseThrow(() -> new BusinessException("LOAN_NOT_FOUND", "Loan not found: " + si.getLoanAccountNumber()));

        if (loan.getStatus().isTerminal()) {
            si.setStatus(SIStatus.EXPIRED);
            si.setLastExecutionStatus("EXPIRED_LOAN_CLOSED");
            si.setUpdatedBy("SYSTEM_EOD");
            siRepository.save(si);
            return false;
        }

        BigDecimal emiAmount = loan.getEmiAmount();
        if (emiAmount == null || emiAmount.signum() <= 0) {
            markFailed(si, businessDate, "INVALID_EMI", "EMI is zero/null");
            return false;
        }

        depositAccountService.withdraw(si.getSourceAccountNumber(), emiAmount, businessDate,
            si.getNarration() + " | EMI INR " + emiAmount, idempotencyKey, "STANDING_INSTRUCTION");

        loanAccountService.processRepayment(loan.getAccountNumber(), emiAmount, businessDate, idempotencyKey + "-LOAN");

        markSuccess(si, businessDate, emiAmount);
        log.info("SI LOAN_EMI: si={}, loan={}, emi={}", si.getSiReference(), loan.getAccountNumber(), emiAmount);
        return true;
    }

    private boolean executeTransferSI(StandingInstruction si, LocalDate businessDate, String idempotencyKey) {
        if (si.getAmount() == null || si.getAmount().signum() <= 0) {
            markFailed(si, businessDate, "INVALID_AMOUNT", "Amount is zero/null");
            return false;
        }
        depositAccountService.transfer(si.getSourceAccountNumber(), si.getDestinationAccountNumber(),
            si.getAmount(), businessDate, si.getNarration(), idempotencyKey);
        markSuccess(si, businessDate, si.getAmount());
        return true;
    }

    private void markSuccess(StandingInstruction si, LocalDate businessDate, BigDecimal amount) {
        si.setLastExecutionDate(businessDate);
        si.setLastExecutionStatus("SUCCESS");
        si.setLastFailureReason(null);
        si.setRetriesDone(0);
        si.setTotalExecutions(si.getTotalExecutions() + 1);
        si.setNextExecutionDate(computeNextDate(si, businessDate));
        si.setUpdatedBy("SYSTEM_EOD");
        if (si.getEndDate() != null && si.getNextExecutionDate().isAfter(si.getEndDate())) {
            si.setStatus(SIStatus.EXPIRED);
        }
        siRepository.save(si);
    }

    private boolean handleFailure(StandingInstruction si, LocalDate businessDate, String code, String msg) {
        si.setRetriesDone(si.getRetriesDone() + 1);
        si.setLastExecutionDate(businessDate);
        si.setLastExecutionStatus("FAILED_" + code);
        si.setLastFailureReason(msg);
        si.setTotalFailures(si.getTotalFailures() + 1);
        si.setUpdatedBy("SYSTEM_EOD");
        if (!si.canRetry()) {
            si.setNextExecutionDate(computeNextDate(si, businessDate));
            si.setRetriesDone(0);
        }
        siRepository.save(si);
        log.warn("SI failed: si={}, code={}, retries={}/{}", si.getSiReference(), code, si.getRetriesDone(), si.getMaxRetries());
        return false;
    }

    private void markFailed(StandingInstruction si, LocalDate bd, String code, String reason) {
        handleFailure(si, bd, code, reason);
    }

    private void markSkipped(StandingInstruction si, LocalDate bd, String reason) {
        si.setLastExecutionStatus("SKIPPED");
        si.setLastFailureReason(reason);
        si.setNextExecutionDate(computeNextDate(si, bd));
        si.setUpdatedBy("SYSTEM_EOD");
        siRepository.save(si);
    }

    private LocalDate computeNextDate(StandingInstruction si, LocalDate currentDate) {
        SIFrequency freq = si.getFrequency();
        if (freq.getMonthsIncrement() > 0) {
            LocalDate next = currentDate.plusMonths(freq.getMonthsIncrement());
            return next.withDayOfMonth(Math.min(si.getExecutionDay(), next.lengthOfMonth()));
        } else if (freq.getDaysIncrement() > 0) {
            return currentDate.plusDays(freq.getDaysIncrement());
        }
        return currentDate.plusMonths(1);
    }

    /** Pause an active SI — per RBI Customer Rights, customer can pause at any time. */
    @Transactional
    public StandingInstruction pauseSI(String siReference) {
        String tenantId = TenantContext.getCurrentTenant();
        StandingInstruction si = siRepository.findByTenantIdAndSiReference(tenantId, siReference)
            .orElseThrow(() -> new BusinessException("SI_NOT_FOUND", "Not found: " + siReference));
        if (si.getStatus() != SIStatus.ACTIVE) throw new BusinessException("SI_NOT_ACTIVE", "Cannot pause: " + si.getStatus());
        si.setStatus(SIStatus.PAUSED);
        si.setUpdatedBy(SecurityUtil.getCurrentUsername());
        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", si.getId(), "SI_PAUSED",
            "ACTIVE", "PAUSED", "STANDING_INSTRUCTION",
            "SI paused: " + siReference + " by " + SecurityUtil.getCurrentUsername());
        return saved;
    }

    /** Resume a paused SI — restores automatic execution from next cycle. */
    @Transactional
    public StandingInstruction resumeSI(String siReference) {
        String tenantId = TenantContext.getCurrentTenant();
        StandingInstruction si = siRepository.findByTenantIdAndSiReference(tenantId, siReference)
            .orElseThrow(() -> new BusinessException("SI_NOT_FOUND", "Not found: " + siReference));
        if (si.getStatus() != SIStatus.PAUSED) throw new BusinessException("SI_NOT_PAUSED", "Cannot resume: " + si.getStatus());
        si.setStatus(SIStatus.ACTIVE);
        si.setUpdatedBy(SecurityUtil.getCurrentUsername());
        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", si.getId(), "SI_RESUMED",
            "PAUSED", "ACTIVE", "STANDING_INSTRUCTION",
            "SI resumed: " + siReference + " by " + SecurityUtil.getCurrentUsername());
        return saved;
    }

    /** Cancel SI permanently — per RBI Customer Rights, customer can cancel at any time. */
    @Transactional
    public StandingInstruction cancelSI(String siReference) {
        String tenantId = TenantContext.getCurrentTenant();
        StandingInstruction si = siRepository.findByTenantIdAndSiReference(tenantId, siReference)
            .orElseThrow(() -> new BusinessException("SI_NOT_FOUND", "Not found: " + siReference));
        if (si.getStatus().isTerminal()) throw new BusinessException("SI_TERMINAL", "Already terminal: " + si.getStatus());
        String prevStatus = si.getStatus().name();
        si.setStatus(SIStatus.CANCELLED);
        si.setUpdatedBy(SecurityUtil.getCurrentUsername());
        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", si.getId(), "SI_CANCELLED",
            prevStatus, "CANCELLED", "STANDING_INSTRUCTION",
            "SI cancelled: " + siReference + " by " + SecurityUtil.getCurrentUsername());
        return saved;
    }
}

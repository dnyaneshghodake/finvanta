package com.finvanta.service.impl;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.StandingInstruction;
import com.finvanta.domain.enums.SIFrequency;
import com.finvanta.domain.enums.SIStatus;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.repository.StandingInstructionRepository;
import com.finvanta.service.DepositAccountService;
import com.finvanta.service.LoanAccountService;
import com.finvanta.util.BusinessException;
import com.finvanta.util.PiiMaskingUtil;
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
    private final BusinessCalendarRepository calendarRepository;
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
                                           BusinessCalendarRepository calendarRepository,
                                           LoanAccountService loanAccountService,
                                           DepositAccountService depositAccountService,
                                           AuditService auditService,
                                           @Lazy StandingInstructionServiceImpl self) {
        this.siRepository = siRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.calendarRepository = calendarRepository;
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
            } catch (BusinessException e) {
                // CBS: Financial transaction rolled back — record failure in separate transaction.
                // executeSingleSI's REQUIRES_NEW transaction was marked rollback-only by the
                // exception, so any save() inside it was lost. We must record the failure
                // in a new, clean REQUIRES_NEW transaction via recordSIFailure().
                failed++;
                try {
                    self.recordSIFailure(si.getId(), businessDate, e.getErrorCode(), e.getMessage());
                } catch (Exception recordErr) {
                    log.error("SI failure recording also failed: si={}, err={}", si.getSiReference(), recordErr.getMessage());
                }
                log.warn("SI failed: si={}, code={}, msg={}", si.getSiReference(), e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                failed++;
                try {
                    self.recordSIFailure(si.getId(), businessDate, "SYSTEM_ERROR", e.getMessage());
                } catch (Exception recordErr) {
                    log.error("SI failure recording also failed: si={}, err={}", si.getSiReference(), recordErr.getMessage());
                }
                log.error("SI fatal: si={}, error={}", si.getSiReference(), e.getMessage(), e);
            }
        }
        log.info("SI EOD: date={}, executed={}, failed={}, total={}", businessDate, executed, failed, dueSIs.size());
        return new int[]{executed, failed};
    }

    /**
     * Execute a single SI in its own REQUIRES_NEW transaction.
     *
     * CBS FINANCIAL SAFETY: When withdraw() or processRepayment() throws (e.g.,
     * INSUFFICIENT_BALANCE), Spring marks this REQUIRES_NEW transaction as rollback-only.
     * Any subsequent siRepository.save() within the SAME transaction would be rolled back,
     * losing the failure tracking (retriesDone, lastExecutionStatus, etc.).
     *
     * Fix: Let the exception propagate to roll back the financial operations cleanly,
     * then record the failure in a SEPARATE REQUIRES_NEW transaction via
     * recordSIFailure(). This ensures:
     *   1. Financial operations (CASA debit + loan repayment) are fully rolled back
     *   2. SI failure metadata is persisted in a separate committed transaction
     *   3. No partial financial state (debit without repayment, or vice versa)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean executeSingleSI(StandingInstruction si, LocalDate businessDate) {
        String idempotencyKey = "SI-" + si.getSiReference() + "-" + businessDate;
        if (si.isLoanEmi()) return executeLoanEmiSI(si, businessDate, si.getTenantId(), idempotencyKey);
        else if (si.isInternalTransfer()) return executeTransferSI(si, businessDate, idempotencyKey);
        else { markSkipped(si, businessDate, "Type not implemented"); return false; }
    }

    /**
     * Record SI failure in a separate REQUIRES_NEW transaction.
     * Called from executeAllDueSIs() AFTER the financial transaction has rolled back.
     * This ensures failure metadata is always persisted regardless of financial rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordSIFailure(Long siId, LocalDate businessDate, String code, String msg) {
        StandingInstruction si = siRepository.findById(siId).orElse(null);
        if (si == null) {
            log.error("SI not found for failure recording: id={}", siId);
            return false;
        }
        return handleFailure(si, businessDate, code, msg);
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
            auditService.logEvent("StandingInstruction", si.getId(), "SI_EXPIRED",
                "ACTIVE", "EXPIRED", "STANDING_INSTRUCTION",
                "SI expired (loan closed): " + si.getSiReference()
                    + " | Loan: " + si.getLoanAccountNumber()
                    + " | Loan status: " + loan.getStatus());
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

        // Gap 2: CBS Notification per RBI Digital Banking Framework 2023.
        // Per RBI: bank MUST notify customer on every SI execution (success or failure).
        // In Tier-1 CBS (Finacle/Temenos), the core records a notification event and
        // an external gateway (SMS/email microservice) picks it up asynchronously.
        // Here we log the notification event for the gateway to consume.
        logNotification(si, "SI_EXECUTION_SUCCESS",
            "EMI of INR " + emiAmount + " debited from A/c " + si.getSourceAccountNumber()
                + " towards loan " + loan.getAccountNumber() + " on " + businessDate
                + ". Ref: " + si.getSiReference());

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
            auditService.logEvent("StandingInstruction", si.getId(), "SI_EXPIRED",
                "ACTIVE", "EXPIRED", "STANDING_INSTRUCTION",
                "SI expired (end date reached): " + si.getSiReference()
                    + " | End date: " + si.getEndDate()
                    + " | Total executions: " + si.getTotalExecutions());
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

        // Gap 2: Notify customer on SI failure per RBI Digital Banking Framework 2023.
        logNotification(si, "SI_EXECUTION_FAILED",
            "Standing Instruction " + si.getSiReference() + " failed on " + businessDate
                + ". Reason: " + msg + ". Please ensure sufficient balance in A/c "
                + si.getSourceAccountNumber() + ".");

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

    // ========================================================================
    // GAP 2: NOTIFICATION LOGGING (RBI Digital Banking Framework 2023)
    // ========================================================================

    /**
     * Log a customer notification event for the external SMS/email gateway.
     *
     * Per RBI Digital Banking Framework 2023 and NPCI NACH rules:
     * - Bank MUST notify customer on every SI execution (success or failure)
     * - Notification must include: amount, account, date, reference
     *
     * In Tier-1 CBS (Finacle/Temenos), the core records notification events in the
     * audit log with a specific module code ("NOTIFICATION"). An external gateway
     * service (SMS/email microservice) polls or subscribes to these events and
     * delivers the actual notification. This decouples the CBS from the delivery
     * channel and ensures the financial transaction is never blocked by notification
     * delivery failures.
     *
     * For production: replace this with a dedicated notification_queue table or
     * message broker (Kafka/RabbitMQ) for guaranteed delivery.
     */
    private void logNotification(StandingInstruction si, String eventType, String message) {
        try {
            // CBS: Mask mobile number in audit log per RBI IT Governance Direction 2023.
            // PII must never appear in plaintext in logs, audit trails, or error messages.
            String maskedMobile = (si.getCustomer() != null && si.getCustomer().getMobileNumber() != null)
                ? PiiMaskingUtil.maskMobile(si.getCustomer().getMobileNumber()) : "N/A";
            auditService.logEvent("StandingInstruction", si.getId(), eventType,
                si.getSiReference(), message, "NOTIFICATION",
                "Customer: " + (si.getCustomer() != null ? si.getCustomer().getId() : "N/A")
                    + " | Mobile: " + maskedMobile
                    + " | Message: " + message);
            log.debug("SI notification logged: si={}, event={}", si.getSiReference(), eventType);
        } catch (Exception e) {
            // Notification logging failure must NEVER block financial processing
            log.warn("SI notification logging failed (non-blocking): si={}, error={}",
                si.getSiReference(), e.getMessage());
        }
    }

    // ========================================================================
    // GAP 3: MANUAL SI REGISTRATION WITH MAKER-CHECKER
    // ========================================================================

    /**
     * Register a manual Standing Instruction (INTERNAL_TRANSFER, UTILITY, SIP, RD).
     * Per Finacle SI_MASTER: manually registered SIs require maker-checker approval.
     * Auto-created SIs (LOAN_EMI at disbursement) are ACTIVE immediately.
     *
     * Per RBI Payment Systems Act 2007: SI requires explicit customer mandate.
     * The maker-checker workflow ensures dual authorization before activation.
     *
     * @param customerId Customer CIF
     * @param sourceAccountNumber CASA account to debit
     * @param destinationType INTERNAL_TRANSFER, UTILITY, SIP, RD_CONTRIBUTION
     * @param destinationAccountNumber Target account
     * @param amount Fixed SI amount
     * @param frequency Execution frequency
     * @param executionDay Day of month (1-28)
     * @param startDate SI start date
     * @param endDate SI end date (null = perpetual)
     * @param narration Custom narration
     * @return SI in PENDING_APPROVAL status (requires checker activation)
     */
    @Transactional
    public StandingInstruction registerManualSI(Long customerId, String sourceAccountNumber,
                                                 String destinationType, String destinationAccountNumber,
                                                 BigDecimal amount, SIFrequency frequency,
                                                 int executionDay, LocalDate startDate,
                                                 LocalDate endDate, String narration) {
        String tenantId = TenantContext.getCurrentTenant();

        if ("LOAN_EMI".equals(destinationType)) {
            throw new BusinessException("USE_AUTO_SI",
                "LOAN_EMI SIs are auto-created at disbursement. Use the disbursement flow instead.");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("INVALID_AMOUNT", "SI amount must be positive for manual SIs");
        }
        if (executionDay < 1 || executionDay > 28) {
            throw new BusinessException("INVALID_EXECUTION_DAY", "Execution day must be 1-28");
        }

        // Validate source CASA exists and belongs to the customer
        var casaOpt = depositAccountRepository.findByTenantIdAndAccountNumber(tenantId, sourceAccountNumber);
        if (casaOpt.isEmpty()) {
            throw new BusinessException("CASA_NOT_FOUND", "Source CASA not found: " + sourceAccountNumber);
        }
        var casa = casaOpt.get();
        if (!casa.getCustomer().getId().equals(customerId)) {
            throw new BusinessException("CIF_MISMATCH",
                "CASA " + sourceAccountNumber + " does not belong to customer " + customerId);
        }

        var customer = casa.getCustomer();

        StandingInstruction si = new StandingInstruction();
        si.setTenantId(tenantId);
        si.setSiReference(ReferenceGenerator.generateTransactionRef().replace("TXN", "SI"));
        si.setCustomer(customer);
        si.setSourceAccountNumber(sourceAccountNumber);
        si.setDestinationType(destinationType);
        si.setDestinationAccountNumber(destinationAccountNumber);
        si.setAmount(amount);
        si.setFrequency(frequency);
        si.setExecutionDay(executionDay);
        si.setStartDate(startDate);
        si.setEndDate(endDate);
        si.setNextExecutionDate(startDate);
        // Gap 3: Manual SIs require maker-checker — starts in PENDING_APPROVAL
        si.setStatus(SIStatus.PENDING_APPROVAL);
        si.setPriority(destinationType.equals("INTERNAL_TRANSFER") ? 4 : 5);
        si.setNarration(narration != null ? narration : destinationType + " SI for " + sourceAccountNumber);
        si.setCreatedBy(SecurityUtil.getCurrentUsername());

        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", saved.getId(), "SI_REGISTERED",
            null, saved.getSiReference(), "STANDING_INSTRUCTION",
            "Manual SI registered (PENDING_APPROVAL): " + saved.getSiReference()
                + " | Type: " + destinationType + " | Amount: INR " + amount
                + " | Source: " + sourceAccountNumber + " | Maker: " + SecurityUtil.getCurrentUsername());
        log.info("Manual SI registered: si={}, type={}, status=PENDING_APPROVAL, maker={}",
            saved.getSiReference(), destinationType, SecurityUtil.getCurrentUsername());
        return saved;
    }

    /**
     * Approve a PENDING_APPROVAL SI — CHECKER activates it per maker-checker workflow.
     * Per Finacle SI_MASTER: checker cannot be the same user as maker.
     */
    @Transactional
    public StandingInstruction approveSI(String siReference) {
        String tenantId = TenantContext.getCurrentTenant();
        String checker = SecurityUtil.getCurrentUsername();
        StandingInstruction si = siRepository.findByTenantIdAndSiReference(tenantId, siReference)
            .orElseThrow(() -> new BusinessException("SI_NOT_FOUND", "Not found: " + siReference));
        if (si.getStatus() != SIStatus.PENDING_APPROVAL) {
            throw new BusinessException("SI_NOT_PENDING", "SI is not in PENDING_APPROVAL: " + si.getStatus());
        }
        // Maker-checker: checker must be different from maker
        if (checker.equals(si.getCreatedBy())) {
            throw new BusinessException("SELF_APPROVAL",
                "Maker and checker must be different users per RBI dual-authorization norms");
        }
        si.setStatus(SIStatus.ACTIVE);
        si.setUpdatedBy(checker);
        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", si.getId(), "SI_APPROVED",
            "PENDING_APPROVAL", "ACTIVE", "STANDING_INSTRUCTION",
            "SI approved by checker: " + siReference + " | Checker: " + checker
                + " | Maker: " + si.getCreatedBy());
        log.info("SI approved: si={}, checker={}, maker={}", siReference, checker, si.getCreatedBy());
        return saved;
    }

    /**
     * Reject a PENDING_APPROVAL SI — CHECKER rejects it.
     */
    @Transactional
    public StandingInstruction rejectSI(String siReference, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        StandingInstruction si = siRepository.findByTenantIdAndSiReference(tenantId, siReference)
            .orElseThrow(() -> new BusinessException("SI_NOT_FOUND", "Not found: " + siReference));
        if (si.getStatus() != SIStatus.PENDING_APPROVAL) {
            throw new BusinessException("SI_NOT_PENDING", "SI is not in PENDING_APPROVAL: " + si.getStatus());
        }
        si.setStatus(SIStatus.REJECTED);
        si.setLastFailureReason(reason);
        si.setUpdatedBy(SecurityUtil.getCurrentUsername());
        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", si.getId(), "SI_REJECTED",
            "PENDING_APPROVAL", "REJECTED", "STANDING_INSTRUCTION",
            "SI rejected: " + siReference + " | Reason: " + reason
                + " | By: " + SecurityUtil.getCurrentUsername());
        return saved;
    }

    /**
     * Compute next execution date with holiday awareness per Finacle SI_MASTER.
     *
     * Per RBI Payment Systems Act 2007 and Finacle SI_MASTER:
     *   1. Calculate raw next date from frequency (plusMonths/plusDays)
     *   2. Adjust to execution day of month (capped at month length)
     *   3. If the date falls on a holiday → shift to next business day
     *   4. If calendar data is unavailable → use raw date (graceful fallback)
     *
     * This ensures the customer sees the correct next execution date on their
     * CASA statement and the SI dashboard. Without holiday adjustment, the
     * displayed date would be wrong even though execution would still happen
     * on the next business day (due to findDueSIs using <= comparison).
     */
    private LocalDate computeNextDate(StandingInstruction si, LocalDate currentDate) {
        SIFrequency freq = si.getFrequency();
        LocalDate rawNext;
        if (freq.getMonthsIncrement() > 0) {
            LocalDate next = currentDate.plusMonths(freq.getMonthsIncrement());
            rawNext = next.withDayOfMonth(Math.min(si.getExecutionDay(), next.lengthOfMonth()));
        } else if (freq.getDaysIncrement() > 0) {
            rawNext = currentDate.plusDays(freq.getDaysIncrement());
        } else {
            rawNext = currentDate.plusMonths(1);
        }

        // CBS: Holiday-aware adjustment per Finacle SI_MASTER / RBI Payment Systems.
        // If the raw next date falls on a holiday, shift to the next business day.
        // Graceful fallback: if calendar data is unavailable, use raw date.
        try {
            String tenantId = si.getTenantId();
            return calendarRepository.findNextBusinessDayOnOrAfter(tenantId, rawNext)
                .orElse(rawNext);
        } catch (Exception e) {
            // Calendar lookup failure should not break SI processing
            log.debug("Holiday lookup failed for {}, using raw date {}", si.getSiReference(), rawNext);
            return rawNext;
        }
    }

    // ========================================================================
    // SI LIFECYCLE MANAGEMENT (Gap 4: Amendment support)
    // ========================================================================

    /**
     * Amend an active SI — modify amount, frequency, or execution day.
     * Per Finacle SI_MASTER: amendment creates an audit trail with before/after values.
     * Only ACTIVE or PAUSED SIs can be amended.
     * Per RBI Payment Systems: customer can modify SI at any time.
     */
    @Transactional
    public StandingInstruction amendSI(String siReference, BigDecimal newAmount,
                                        SIFrequency newFrequency, Integer newExecutionDay) {
        String tenantId = TenantContext.getCurrentTenant();
        StandingInstruction si = siRepository.findByTenantIdAndSiReference(tenantId, siReference)
            .orElseThrow(() -> new BusinessException("SI_NOT_FOUND", "Not found: " + siReference));
        if (si.getStatus().isTerminal()) {
            throw new BusinessException("SI_TERMINAL", "Cannot amend terminal SI: " + si.getStatus());
        }
        if (si.isLoanEmi() && newAmount != null) {
            throw new BusinessException("SI_LOAN_EMI_AMOUNT_FIXED",
                "LOAN_EMI SI amount cannot be amended — it is resolved dynamically from LoanAccount.emiAmount");
        }

        StringBuilder changes = new StringBuilder();
        if (newAmount != null && !si.isLoanEmi()) {
            changes.append("amount: ").append(si.getAmount()).append(" → ").append(newAmount).append("; ");
            si.setAmount(newAmount);
        }
        if (newFrequency != null && newFrequency != si.getFrequency()) {
            changes.append("frequency: ").append(si.getFrequency()).append(" → ").append(newFrequency).append("; ");
            si.setFrequency(newFrequency);
        }
        if (newExecutionDay != null && newExecutionDay != si.getExecutionDay()) {
            if (newExecutionDay < 1 || newExecutionDay > 28) {
                throw new BusinessException("INVALID_EXECUTION_DAY", "Execution day must be 1-28");
            }
            changes.append("executionDay: ").append(si.getExecutionDay()).append(" → ").append(newExecutionDay).append("; ");
            si.setExecutionDay(newExecutionDay);
        }

        if (changes.length() == 0) {
            throw new BusinessException("NO_CHANGES", "No amendment fields provided");
        }

        // Recompute next execution date with new parameters
        if (si.getLastExecutionDate() != null) {
            si.setNextExecutionDate(computeNextDate(si, si.getLastExecutionDate()));
        }

        si.setUpdatedBy(SecurityUtil.getCurrentUsername());
        StandingInstruction saved = siRepository.save(si);
        auditService.logEvent("StandingInstruction", si.getId(), "SI_AMENDED",
            siReference, changes.toString(), "STANDING_INSTRUCTION",
            "SI amended: " + siReference + " | Changes: " + changes + " by " + SecurityUtil.getCurrentUsername());
        log.info("SI amended: si={}, changes={}", siReference, changes);
        return saved;
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

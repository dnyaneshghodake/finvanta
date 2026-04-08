package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.LoanStatus;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS Loan Restructuring Service per RBI CDR/SDR Framework.
 *
 * Per RBI Master Direction on Resolution of Stressed Assets:
 * - Restructuring modifies loan terms (rate, tenure, moratorium) for stressed borrowers
 * - Restructured accounts are flagged separately for CRILC reporting
 * - Higher provisioning applies (5% for first 2 years post-restructuring)
 * - Account retains DPD-based SMA/NPA classification for provisioning purposes
 * - NPA accounts can be restructured but remain NPA until arrears are cleared
 *
 * Restructuring types supported:
 *   1. Rate Reduction: lower interest rate for a period or permanently
 *   2. Tenure Extension: extend maturity date, reduce EMI
 *   3. Moratorium: defer EMI payments for a period (interest continues to accrue)
 *   4. Combined: rate + tenure modification
 *
 * Per Finacle/Temenos: restructuring requires maker-checker approval and
 * generates a new amortization schedule from the restructuring date.
 */
@Service
public class LoanRestructuringService {

    private static final Logger log = LoggerFactory.getLogger(LoanRestructuringService.class);

    private final LoanAccountRepository accountRepository;
    private final LoanScheduleService scheduleService;
    private final AuditService auditService;
    private final com.finvanta.domain.rules.InterestCalculationRule interestRule;

    public LoanRestructuringService(LoanAccountRepository accountRepository,
                                     LoanScheduleService scheduleService,
                                     AuditService auditService,
                                     com.finvanta.domain.rules.InterestCalculationRule interestRule) {
        this.accountRepository = accountRepository;
        this.scheduleService = scheduleService;
        this.auditService = auditService;
        this.interestRule = interestRule;
    }

    /**
     * Restructures a loan account by modifying rate and/or tenure.
     *
     * @param accountNumber   Loan account number
     * @param newRate         New interest rate (null = no rate change)
     * @param additionalMonths Additional tenure months (0 = no tenure change)
     * @param reason          Restructuring reason (mandatory for audit)
     * @param businessDate    CBS business date
     * @return The restructured account
     */
    @Transactional
    public LoanAccount restructureLoan(String accountNumber,
                                        BigDecimal newRate,
                                        int additionalMonths,
                                        String reason,
                                        LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("RESTRUCTURE_REASON_REQUIRED",
                "Restructuring reason is mandatory per RBI CDR norms");
        }

        if (newRate == null && additionalMonths <= 0) {
            throw new BusinessException("RESTRUCTURE_NO_CHANGE",
                "At least one term must be modified (rate or tenure)");
        }

        LoanAccount account = accountRepository
            .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_TERMINAL",
                "Cannot restructure " + account.getStatus() + " account");
        }

        // Capture before state for audit
        BigDecimal oldRate = account.getInterestRate();
        int oldTenure = account.getTenureMonths();
        int oldRemaining = account.getRemainingTenure() != null
            ? account.getRemainingTenure() : 0;
        LoanStatus oldStatus = account.getStatus();

        // Apply rate change
        if (newRate != null) {
            if (newRate.compareTo(BigDecimal.ZERO) <= 0
                    || newRate.compareTo(new BigDecimal("50")) > 0) {
                throw new BusinessException("INVALID_RATE",
                    "Interest rate must be between 0 and 50%. Provided: " + newRate);
            }
            account.setInterestRate(newRate);
        }

        // Apply tenure extension
        if (additionalMonths > 0) {
            if (additionalMonths > 120) {
                throw new BusinessException("INVALID_TENURE_EXTENSION",
                    "Tenure extension cannot exceed 120 months. Provided: "
                        + additionalMonths);
            }
            int newTotalTenure = account.getTenureMonths() + additionalMonths;
            int newRemaining = oldRemaining + additionalMonths;
            account.setTenureMonths(newTotalTenure);
            account.setRemainingTenure(newRemaining);

            if (account.getMaturityDate() != null) {
                account.setMaturityDate(
                    account.getMaturityDate().plusMonths(additionalMonths));
            }
        }

        // Recalculate EMI based on new terms
        BigDecimal newEmi = interestRule.calculateEmi(
            account.getOutstandingPrincipal(),
            account.getInterestRate(),
            account.getRemainingTenure() != null ? account.getRemainingTenure() : 1
        );
        account.setEmiAmount(newEmi);

        // CBS: Regenerate amortization schedule from restructuring date.
        // Per Finacle/Temenos: restructuring invalidates the existing schedule.
        // Future unpaid installments must be cancelled and new ones generated
        // with the revised EMI, rate, and tenure. Without this, the schedule
        // shows stale amounts that don't match the restructured terms, causing
        // DPD miscalculation and incorrect overdue marking during EOD.
        try {
            scheduleService.regenerateSchedule(account, businessDate);
            log.info("Schedule regenerated after restructuring: accNo={}, newEmi={}",
                accountNumber, newEmi);
        } catch (Exception e) {
            // Schedule regeneration failure should not block restructuring.
            // The restructured terms (rate, tenure, EMI) are already saved on the account.
            // Schedule can be regenerated manually via admin action.
            log.warn("Schedule regeneration failed after restructuring for {}: {}",
                accountNumber, e.getMessage());
        }

        // Mark as RESTRUCTURED per RBI CDR (unless already NPA)
        // NPA accounts remain NPA per RBI norms -- restructuring does not
        // auto-upgrade NPA to standard. The RESTRUCTURED flag is tracked
        // separately via NpaClassificationRule.
        if (!account.getStatus().isNpa()) {
            account.setStatus(LoanStatus.RESTRUCTURED);
        }

        account.setUpdatedBy(currentUser);
        LoanAccount saved = accountRepository.save(account);

        // Audit trail with before/after
        String auditDesc = "Loan restructured: reason=" + reason
            + " | Rate: " + oldRate + " -> " + account.getInterestRate()
            + " | Tenure: " + oldTenure + " -> " + account.getTenureMonths()
            + " | Remaining: " + oldRemaining + " -> " + account.getRemainingTenure()
            + " | EMI: " + newEmi
            + " | Status: " + oldStatus + " -> " + account.getStatus();

        auditService.logEvent("LoanAccount", account.getId(), "RESTRUCTURE",
            oldStatus.name(), account.getStatus().name(), "LOAN_RESTRUCTURING",
            auditDesc);

        log.info("Loan restructured: accNo={}, rate={}->{}, tenure={}->{}, emi={}",
            accountNumber, oldRate, account.getInterestRate(),
            oldTenure, account.getTenureMonths(), newEmi);

        return saved;
    }

    /**
     * Applies a moratorium (payment holiday) to a loan account.
     * During moratorium, EMI payments are deferred but interest continues to accrue.
     * Per RBI COVID-19 moratorium guidelines and general CDR framework.
     *
     * @param accountNumber    Loan account number
     * @param moratoriumMonths Number of months to defer payments
     * @param reason           Reason for moratorium
     * @param businessDate     CBS business date
     * @return The modified account
     */
    @Transactional
    public LoanAccount applyMoratorium(String accountNumber,
                                        int moratoriumMonths,
                                        String reason,
                                        LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        String currentUser = SecurityUtil.getCurrentUsername();

        if (moratoriumMonths <= 0 || moratoriumMonths > 24) {
            throw new BusinessException("INVALID_MORATORIUM",
                "Moratorium period must be 1-24 months. Provided: " + moratoriumMonths);
        }

        if (reason == null || reason.isBlank()) {
            throw new BusinessException("MORATORIUM_REASON_REQUIRED",
                "Moratorium reason is mandatory per RBI guidelines");
        }

        LoanAccount account = accountRepository
            .findAndLockByTenantIdAndAccountNumber(tenantId, accountNumber)
            .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND",
                "Loan account not found: " + accountNumber));

        if (account.getStatus().isTerminal()) {
            throw new BusinessException("ACCOUNT_TERMINAL",
                "Cannot apply moratorium on " + account.getStatus() + " account");
        }

        // Extend next EMI date and maturity by moratorium period
        LocalDate oldNextEmi = account.getNextEmiDate();
        LocalDate oldMaturity = account.getMaturityDate();

        if (account.getNextEmiDate() != null) {
            account.setNextEmiDate(
                account.getNextEmiDate().plusMonths(moratoriumMonths));
        }
        if (account.getMaturityDate() != null) {
            account.setMaturityDate(
                account.getMaturityDate().plusMonths(moratoriumMonths));
        }

        // Extend tenure
        int oldTenure = account.getTenureMonths();
        account.setTenureMonths(oldTenure + moratoriumMonths);
        if (account.getRemainingTenure() != null) {
            account.setRemainingTenure(
                account.getRemainingTenure() + moratoriumMonths);
        }

        if (!account.getStatus().isNpa()) {
            account.setStatus(LoanStatus.RESTRUCTURED);
        }

        account.setUpdatedBy(currentUser);
        LoanAccount saved = accountRepository.save(account);

        auditService.logEvent("LoanAccount", account.getId(), "MORATORIUM",
            null, String.valueOf(moratoriumMonths), "LOAN_RESTRUCTURING",
            "Moratorium applied: " + moratoriumMonths + " months"
                + " | NextEMI: " + oldNextEmi + " -> " + account.getNextEmiDate()
                + " | Maturity: " + oldMaturity + " -> " + account.getMaturityDate()
                + " | Reason: " + reason);

        log.info("Moratorium applied: accNo={}, months={}, nextEmi={}, maturity={}",
            accountNumber, moratoriumMonths,
            account.getNextEmiDate(), account.getMaturityDate());

        return saved;
    }
}

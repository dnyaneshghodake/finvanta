package com.finvanta.service;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.entity.LoanSchedule;
import com.finvanta.domain.rules.InterestCalculationRule;
import com.finvanta.repository.LoanScheduleRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * CBS Loan Amortization Schedule Generator per Finacle/Temenos standards.
 *
 * Generates the full EMI schedule at disbursement using the reducing balance method:
 *   EMI = P × r × (1+r)^n / ((1+r)^n - 1)
 *
 * Each installment splits EMI into:
 *   Interest = Outstanding × Monthly Rate
 *   Principal = EMI - Interest
 *   Closing Balance = Opening - Principal
 *
 * Example (₹10,00,000 at 10% for 12 months):
 *   EMI = ₹87,916
 *   Inst 1: Principal ₹79,583, Interest ₹8,333, Closing ₹9,20,417
 *   Inst 2: Principal ₹80,246, Interest ₹7,670, Closing ₹8,40,171
 *   ...
 *   Inst 12: Principal ₹87,183, Interest ₹733, Closing ₹0
 *
 * Per RBI, schedule is generated once at disbursement. Restructuring generates a new schedule.
 */
@Service
public class LoanScheduleService {

    private static final Logger log = LoggerFactory.getLogger(LoanScheduleService.class);
    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);
    private static final int SCALE = 2;

    private final LoanScheduleRepository scheduleRepository;
    private final InterestCalculationRule interestRule;
    private final AuditService auditService;

    public LoanScheduleService(LoanScheduleRepository scheduleRepository,
                                InterestCalculationRule interestRule,
                                AuditService auditService) {
        this.scheduleRepository = scheduleRepository;
        this.interestRule = interestRule;
        this.auditService = auditService;
    }

    /**
     * Generates the full amortization schedule for a loan account at disbursement.
     * Idempotent — will not regenerate if schedule already exists.
     *
     * @param account    The disbursed loan account
     * @param businessDate The CBS business date (not system date)
     * @return List of generated schedule lines
     */
    @Transactional
    public List<LoanSchedule> generateSchedule(LoanAccount account, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();

        // Idempotency: don't regenerate
        if (scheduleRepository.existsByTenantIdAndLoanAccountId(tenantId, account.getId())) {
            throw new BusinessException("SCHEDULE_EXISTS",
                "Amortization schedule already exists for account: " + account.getAccountNumber());
        }

        BigDecimal principal = account.getSanctionedAmount();
        BigDecimal annualRate = account.getInterestRate();
        int tenureMonths = account.getTenureMonths();
        BigDecimal emi = account.getEmiAmount();

        if (emi == null || emi.compareTo(BigDecimal.ZERO) <= 0) {
            emi = interestRule.calculateEmi(principal, annualRate, tenureMonths);
        }

        BigDecimal monthlyRate = annualRate
            .divide(BigDecimal.valueOf(100), MC)
            .divide(BigDecimal.valueOf(12), MC);

        BigDecimal openingBalance = principal;
        LocalDate dueDate = account.getDisbursementDate().plusMonths(1);
        List<LoanSchedule> scheduleLines = new ArrayList<>();

        for (int i = 1; i <= tenureMonths; i++) {
            BigDecimal interestComponent = openingBalance
                .multiply(monthlyRate, MC)
                .setScale(SCALE, RoundingMode.HALF_UP);

            BigDecimal principalComponent;
            if (i == tenureMonths) {
                // Last installment: principal = remaining balance (avoids rounding residual)
                principalComponent = openingBalance;
                interestComponent = emi.subtract(principalComponent)
                    .max(BigDecimal.ZERO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            } else {
                principalComponent = emi.subtract(interestComponent)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            }

            BigDecimal closingBalance = openingBalance.subtract(principalComponent)
                .max(BigDecimal.ZERO)
                .setScale(SCALE, RoundingMode.HALF_UP);

            LoanSchedule line = new LoanSchedule();
            line.setTenantId(tenantId);
            line.setLoanAccount(account);
            line.setInstallmentNumber(i);
            line.setDueDate(dueDate);
            line.setEmiAmount(emi);
            line.setPrincipalAmount(principalComponent);
            line.setInterestAmount(interestComponent);
            line.setClosingBalance(closingBalance);
            line.setStatus("SCHEDULED");
            line.setBusinessDate(businessDate);
            line.setCreatedBy("SYSTEM");

            scheduleLines.add(line);

            openingBalance = closingBalance;
            dueDate = dueDate.plusMonths(1);
        }

        List<LoanSchedule> saved = scheduleRepository.saveAll(scheduleLines);

        auditService.logEvent("LoanSchedule", account.getId(), "GENERATE",
            null, account.getAccountNumber(), "LOAN_SCHEDULE",
            "Amortization schedule generated: " + tenureMonths + " installments, EMI: " + emi);

        log.info("Loan schedule generated: accNo={}, installments={}, emi={}",
            account.getAccountNumber(), tenureMonths, emi);

        return saved;
    }

    /**
     * Returns the full schedule for display.
     */
    public List<LoanSchedule> getSchedule(Long accountId) {
        String tenantId = TenantContext.getCurrentTenant();
        return scheduleRepository.findByTenantIdAndLoanAccountIdOrderByInstallmentNumberAsc(
            tenantId, accountId);
    }

    /**
     * Returns unpaid installments for repayment allocation.
     */
    public List<LoanSchedule> getUnpaidInstallments(Long accountId) {
        String tenantId = TenantContext.getCurrentTenant();
        return scheduleRepository.findUnpaidInstallments(tenantId, accountId);
    }

    /**
     * Returns overdue installments as of a business date.
     */
    public List<LoanSchedule> getOverdueInstallments(Long accountId, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        return scheduleRepository.findOverdueInstallments(tenantId, accountId, businessDate);
    }
}
package com.finvanta.service.impl;

import com.finvanta.accounting.AccountingService.JournalLineRequest;
import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.Branch;
import com.finvanta.domain.entity.Customer;
import com.finvanta.domain.entity.RecurringDeposit;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.domain.enums.RdStatus;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.repository.RecurringDepositRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.service.DepositAccountService;
import com.finvanta.service.RecurringDepositService;
import com.finvanta.service.SequenceGeneratorService;
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

/**
 * CBS Recurring Deposit Service Implementation per Finacle RD_MASTER.
 *
 * <p>All financial operations route through {@link TransactionEngine} for
 * double-entry GL posting, business date validation, branch access, audit trail.
 *
 * @see RecurringDepositService
 */
@Service
public class RecurringDepositServiceImpl implements RecurringDepositService {

    private static final Logger log = LoggerFactory.getLogger(RecurringDepositServiceImpl.class);

    private final RecurringDepositRepository rdRepository;
    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;
    private final TransactionEngine transactionEngine;
    private final BusinessDateService businessDateService;
    private final SequenceGeneratorService sequenceGenerator;
    private final AuditService auditService;
    private final DepositAccountService casaSvc;

    public RecurringDepositServiceImpl(
            RecurringDepositRepository rdRepository,
            CustomerRepository customerRepository,
            BranchRepository branchRepository,
            TransactionEngine transactionEngine,
            BusinessDateService businessDateService,
            SequenceGeneratorService sequenceGenerator,
            AuditService auditService,
            DepositAccountService casaSvc) {
        this.rdRepository = rdRepository;
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
        this.transactionEngine = transactionEngine;
        this.businessDateService = businessDateService;
        this.sequenceGenerator = sequenceGenerator;
        this.auditService = auditService;
        this.casaSvc = casaSvc;
    }

    @Override
    @Transactional
    public RecurringDeposit bookRd(
            Long customerId, Long branchId, String linkedAccount,
            BigDecimal installmentAmt, BigDecimal interestRate,
            int tenureMonths, String nomineeName, String nomineeRel) {

        String tenantId = TenantContext.getCurrentTenant();
        String user = SecurityUtil.getCurrentUsername();
        LocalDate bd = businessDateService.getCurrentBusinessDate();

        Customer cust = customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException(
                        "CUSTOMER_NOT_FOUND", "Customer not found: " + customerId));
        Branch branch = branchRepository.findById(branchId)
                .orElseThrow(() -> new BusinessException(
                        "BRANCH_NOT_FOUND", "Branch not found: " + branchId));

        if (!cust.isKycVerified()) {
            throw new BusinessException("KYC_NOT_VERIFIED",
                    "KYC must be verified before opening RD");
        }

        TransactionResult gl = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("RECURRING_DEPOSIT").transactionType("RD_BOOKING")
                .accountReference(linkedAccount).amount(installmentAmt)
                .valueDate(bd).branchCode(branch.getBranchCode())
                .narration("RD booked — first installment")
                .journalLines(List.of(
                        new JournalLineRequest(GLConstants.SB_DEPOSITS,
                                DebitCredit.DEBIT, installmentAmt, "RD booking"),
                        new JournalLineRequest(GLConstants.RD_DEPOSITS,
                                DebitCredit.CREDIT, installmentAmt, "RD deposit")))
                .systemGenerated(true).initiatedBy(user).build());

        RecurringDeposit rd = new RecurringDeposit();
        rd.setTenantId(tenantId);
        rd.setRdAccountNumber("RD/" + branch.getBranchCode() + "/"
                + sequenceGenerator.nextFormattedValue("RD_SEQ_" + branch.getBranchCode(), 6));
        rd.setCustomer(cust);
        rd.setBranch(branch);
        rd.setBranchCode(branch.getBranchCode());
        rd.setInstallmentAmount(installmentAmt);
        rd.setTotalInstallments(tenureMonths);
        rd.setPaidInstallments(1);
        rd.setCumulativeDeposit(installmentAmt);
        rd.setInterestRate(interestRate);
        rd.setBookingDate(bd);
        rd.setMaturityDate(bd.plusMonths(tenureMonths));
        rd.setNextInstallmentDate(bd.plusMonths(1));
        rd.setLastInstallmentDate(bd);
        rd.setLinkedAccountNumber(linkedAccount);
        rd.setProductCode("RD_REGULAR");
        rd.setNomineeName(nomineeName);
        rd.setNomineeRelationship(nomineeRel);
        rd.setStatus(RdStatus.ACTIVE);
        rd.setBookingJournalId(gl.getJournalEntryId());
        rd.setCreatedBy(user);

        RecurringDeposit saved = rdRepository.save(rd);
        auditService.logEvent("RecurringDeposit", saved.getId(), "RD_BOOKED",
                null, saved.getRdAccountNumber(), "RECURRING_DEPOSIT",
                "RD booked: " + saved.getRdAccountNumber());
        return saved;
    }

    @Override
    @Transactional
    public void processInstallment(String rdAccountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        RecurringDeposit rd = rdRepository
                .findByTenantIdAndRdAccountNumber(tenantId, rdAccountNumber)
                .orElseThrow(() -> new BusinessException(
                        "RD_NOT_FOUND", "RD not found: " + rdAccountNumber));

        if (!rd.isActive() || rd.isFullyPaid()) return;

        BigDecimal amt = rd.getInstallmentAmount();
        try {
            transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("RECURRING_DEPOSIT").transactionType("RD_INSTALLMENT")
                    .accountReference(rd.getLinkedAccountNumber()).amount(amt)
                    .valueDate(businessDate).branchCode(rd.getBranchCode())
                    .narration("RD installment #" + (rd.getPaidInstallments() + 1))
                    .journalLines(List.of(
                            new JournalLineRequest(GLConstants.SB_DEPOSITS,
                                    DebitCredit.DEBIT, amt, "RD installment"),
                            new JournalLineRequest(GLConstants.RD_DEPOSITS,
                                    DebitCredit.CREDIT, amt, "RD installment")))
                    .systemGenerated(true).build());

            rd.setPaidInstallments(rd.getPaidInstallments() + 1);
            rd.setCumulativeDeposit(rd.getCumulativeDeposit().add(amt));
            rd.setLastInstallmentDate(businessDate);
            rd.setNextInstallmentDate(businessDate.plusMonths(1));
            rd.setMissedInstallments(0);
            rdRepository.save(rd);
        } catch (BusinessException e) {
            if ("INSUFFICIENT_BALANCE".equals(e.getErrorCode())) {
                rd.setMissedInstallments(rd.getMissedInstallments() + 1);
                rd.setNextInstallmentDate(businessDate.plusMonths(1));
                if (rd.getMissedInstallments() >= 3) {
                    rd.setStatus(RdStatus.DEFAULTED);
                }
                rdRepository.save(rd);
            } else {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public void accrueInterest(String rdAccountNumber, LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        RecurringDeposit rd = rdRepository
                .findByTenantIdAndRdAccountNumber(tenantId, rdAccountNumber)
                .orElseThrow(() -> new BusinessException(
                        "RD_NOT_FOUND", "RD not found: " + rdAccountNumber));

        if (!rd.isActive() || rd.getCumulativeDeposit().signum() == 0) return;
        if (businessDate.equals(rd.getLastAccrualDate())) return;

        BigDecimal daily = rd.getCumulativeDeposit().multiply(rd.getInterestRate())
                .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);

        // CBS CRITICAL: GL posting BEFORE subledger update.
        // Per Finacle RD_ENGINE / Temenos FIXED.DEPOSIT accrual:
        // DR RD Interest Expense (5012) / CR RD Interest Payable (2041)
        // Without this, the maturity GL posting debits 2041 which was never
        // credited, and EOD reconciliation flags subledger-vs-GL mismatch daily.
        // CBS CRITICAL: GL posting and subledger update MUST be coupled.
        // If daily rounds to 0.00, skip BOTH GL posting AND subledger update.
        // Without this guard, accruedInterest drifts from GL 2041 (RD_INTEREST_PAYABLE)
        // over time, and the maturity posting at processMaturity() debits an amount
        // from 2041 that was never fully credited — creating a GL imbalance.
        // Per Finacle RD_ENGINE: zero-accrual days are valid — advance lastAccrualDate
        // only when a non-zero amount is posted to maintain GL-subledger parity.
        if (daily.signum() > 0) {
            transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("RECURRING_DEPOSIT").transactionType("RD_INTEREST_ACCRUAL")
                    .accountReference(rd.getRdAccountNumber()).amount(daily)
                    .valueDate(businessDate).branchCode(rd.getBranchCode())
                    .narration("RD daily interest accrual")
                    .journalLines(List.of(
                            new JournalLineRequest(GLConstants.RD_INTEREST_EXPENSE,
                                    DebitCredit.DEBIT, daily, "RD interest expense"),
                            new JournalLineRequest(GLConstants.RD_INTEREST_PAYABLE,
                                    DebitCredit.CREDIT, daily, "RD interest payable")))
                    .systemGenerated(true).build());

            rd.setAccruedInterest(rd.getAccruedInterest().add(daily));
            rd.setLastAccrualDate(businessDate);
            rdRepository.save(rd);
        }
    }

    @Override
    @Transactional
    public void processMaturity(String rdAccountNumber) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        RecurringDeposit rd = rdRepository
                .findByTenantIdAndRdAccountNumber(tenantId, rdAccountNumber)
                .orElseThrow(() -> new BusinessException(
                        "RD_NOT_FOUND", "RD not found: " + rdAccountNumber));

        if (!rd.isActive()) {
            throw new BusinessException("RD_NOT_ACTIVE",
                    "RD is not active for maturity processing: " + rdAccountNumber);
        }

        BigDecimal maturityAmt = rd.getMaturityAmount();
        transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("RECURRING_DEPOSIT").transactionType("RD_MATURITY")
                .accountReference(rd.getLinkedAccountNumber()).amount(maturityAmt)
                .valueDate(bd).branchCode(rd.getBranchCode())
                .narration("RD maturity: " + rdAccountNumber)
                .journalLines(List.of(
                        new JournalLineRequest(GLConstants.RD_DEPOSITS,
                                DebitCredit.DEBIT, rd.getCumulativeDeposit(), "RD principal"),
                        new JournalLineRequest(GLConstants.RD_INTEREST_PAYABLE,
                                DebitCredit.DEBIT, rd.getAccruedInterest(), "RD interest"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS,
                                DebitCredit.CREDIT, maturityAmt, "RD maturity to CASA")))
                .systemGenerated(true).build());

        rd.setStatus(RdStatus.MATURED);
        rd.setClosureDate(bd);
        rdRepository.save(rd);
    }

    @Override
    @Transactional
    public RecurringDeposit prematureClose(String rdAccountNumber, String reason) {
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate bd = businessDateService.getCurrentBusinessDate();
        RecurringDeposit rd = rdRepository
                .findByTenantIdAndRdAccountNumber(tenantId, rdAccountNumber)
                .orElseThrow(() -> new BusinessException(
                        "RD_NOT_FOUND", "RD not found: " + rdAccountNumber));

        if (!rd.isActive()) {
            throw new BusinessException("RD_NOT_ACTIVE",
                    "RD is not active: " + rdAccountNumber);
        }

        BigDecimal effectiveRate = rd.getInterestRate()
                .subtract(rd.getPrematurePenaltyRate()).max(BigDecimal.ZERO);
        long days = ChronoUnit.DAYS.between(rd.getBookingDate(), bd);
        BigDecimal adjustedInterest = rd.getCumulativeDeposit()
                .multiply(effectiveRate).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(36500), 2, RoundingMode.HALF_UP);
        // CBS CRITICAL: Cap adjustedInterest at accruedInterest to prevent GL imbalance.
        // adjustedInterest uses simple-interest formula over actual tenure at the penalized rate.
        // accruedInterest is the sum of daily accruals at the full rate (what was credited to GL 2041).
        // If simple-interest calc > sum of daily accruals (due to rounding or partial-accrual gaps),
        // the closure posting would debit more from 2041 than was ever credited — GL imbalance.
        // Per Finacle RD_ENGINE: the payout is always bounded by what was actually accrued.
        // CBS GUARD: reject negative-tenure scenarios (business date before booking date).
        // Should never happen under a correctly-configured BusinessDateService, but a
        // backdated run or corrupt state would produce a negative days count →
        // negative adjustedInterest → GL imbalance that the cap below would not catch.
        if (bd.isBefore(rd.getBookingDate())) {
            throw new BusinessException("INVALID_TENURE",
                    "Business date " + bd + " is before RD booking date " + rd.getBookingDate());
        }
        if (adjustedInterest.compareTo(rd.getAccruedInterest()) > 0) {
            adjustedInterest = rd.getAccruedInterest();
        }
        BigDecimal closureAmt = rd.getCumulativeDeposit().add(adjustedInterest);

        // CBS CRITICAL: Reverse excess accrued interest before closure posting.
        // Daily accruals were posted to GL 2041 (RD_INTEREST_PAYABLE) at the full rate.
        // Premature closure uses a penalized rate, so adjustedInterest < accruedInterest.
        // The difference (accruedInterest - adjustedInterest) is an orphan credit in GL 2041
        // that must be reversed: DR RD_INTEREST_PAYABLE (2041) / CR RD_INTEREST_EXPENSE (5012).
        // Without this reversal, the closure posting debits only adjustedInterest from 2041,
        // leaving a permanent credit residual that corrupts EOD GL reconciliation.
        BigDecimal excessInterest = rd.getAccruedInterest().subtract(adjustedInterest);
        if (excessInterest.signum() > 0) {
            transactionEngine.execute(TransactionRequest.builder()
                    .sourceModule("RECURRING_DEPOSIT").transactionType("RD_INTEREST_REVERSAL")
                    .accountReference(rd.getRdAccountNumber()).amount(excessInterest)
                    .valueDate(bd).branchCode(rd.getBranchCode())
                    .narration("RD premature penalty interest reversal: " + rdAccountNumber)
                    .journalLines(List.of(
                            new JournalLineRequest(GLConstants.RD_INTEREST_PAYABLE,
                                    DebitCredit.DEBIT, excessInterest, "Reverse excess accrued interest"),
                            new JournalLineRequest(GLConstants.RD_INTEREST_EXPENSE,
                                    DebitCredit.CREDIT, excessInterest, "Reverse excess interest expense")))
                    .systemGenerated(true).build());
            log.info("RD interest reversal: rdNo={}, accrued={}, adjusted={}, reversed={}",
                    rdAccountNumber, rd.getAccruedInterest(), adjustedInterest, excessInterest);
        }

        // CBS: Omit the interest DEBIT line when adjustedInterest is zero.
        // A zero-amount journal line may be rejected by TransactionEngine's amount
        // validation, and is accounting-meaningless. When effective rate is zero
        // (penalty >= rate) or the full accrued interest was already reversed above,
        // the closure posting becomes a simple DR RD_DEPOSITS / CR SB_DEPOSITS.
        List<JournalLineRequest> closureLines = adjustedInterest.signum() > 0
                ? List.of(
                        new JournalLineRequest(GLConstants.RD_DEPOSITS,
                                DebitCredit.DEBIT, rd.getCumulativeDeposit(), "RD principal"),
                        new JournalLineRequest(GLConstants.RD_INTEREST_PAYABLE,
                                DebitCredit.DEBIT, adjustedInterest, "RD interest (penalized)"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS,
                                DebitCredit.CREDIT, closureAmt, "RD premature to CASA"))
                : List.of(
                        new JournalLineRequest(GLConstants.RD_DEPOSITS,
                                DebitCredit.DEBIT, rd.getCumulativeDeposit(), "RD principal"),
                        new JournalLineRequest(GLConstants.SB_DEPOSITS,
                                DebitCredit.CREDIT, closureAmt, "RD premature to CASA"));
        TransactionResult closureGl = transactionEngine.execute(TransactionRequest.builder()
                .sourceModule("RECURRING_DEPOSIT").transactionType("RD_PREMATURE_CLOSE")
                .accountReference(rd.getLinkedAccountNumber()).amount(closureAmt)
                .valueDate(bd).branchCode(rd.getBranchCode())
                .narration("RD premature closure: " + reason)
                .journalLines(closureLines)
                .systemGenerated(true).build());

        // CBS CRITICAL: RD↔CASA subledger sync. The closure journal above credits
        // GL SB_DEPOSITS (2030) but does not update the linked CASA account's
        // ledgerBalance on the subledger. Per FD prematureClose precedent
        // (FixedDepositServiceImpl), we must call casaSvc.deposit() so the
        // customer's CASA balance reflects the RD payout. Without this, GL and
        // CASA subledger drift and the customer cannot withdraw the payout.
        casaSvc.deposit(rd.getLinkedAccountNumber(), closureAmt, bd,
                "RD premature closure: " + rdAccountNumber,
                "RD-PM-" + rdAccountNumber, "SYSTEM");

        rd.setStatus(RdStatus.PREMATURE_CLOSED);
        rd.setClosureDate(bd);
        rd.setAccruedInterest(adjustedInterest);
        rd.setClosureJournalId(closureGl.getJournalEntryId());
        rdRepository.save(rd);
        return rd;
    }
}

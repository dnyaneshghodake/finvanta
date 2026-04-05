package com.finvanta.accounting;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.LoanAccount;
import com.finvanta.domain.enums.DebitCredit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * CBS Suspense Management Service per RBI IRAC Master Circular.
 *
 * Per RBI norms on Income Recognition for NPA accounts:
 * 1. Interest on NPA accounts must NOT be recognized as income in P&L
 * 2. Previously recognized interest must be REVERSED from P&L when account becomes NPA
 * 3. Interest on NPA accounts is tracked in Interest Suspense (GL 2100)
 * 4. When NPA interest is actually collected, it moves from Suspense to Income
 *
 * GL Entries:
 *
 * When account transitions to NPA (income reversal):
 *   DR Interest Income (4001)          — reverse previously recognized income
 *   CR Interest Suspense (2100)        — park in suspense
 *
 * When NPA interest is actually collected:
 *   DR Interest Suspense (2100)        — release from suspense
 *   CR Interest Income (4001)          — recognize as income (now actually received)
 *
 * Example:
 *   Loan ₹10,00,000 at 10% — ₹8,333/month interest
 *   Account accrued interest for 3 months before NPA: ₹25,000
 *   On NPA classification:
 *     DR Interest Income (4001)    ₹25,000
 *     CR Interest Suspense (2100)  ₹25,000
 *   Account's accruedInterest moves to suspense tracking
 */
@Service
public class SuspenseService {

    private static final Logger log = LoggerFactory.getLogger(SuspenseService.class);

    private final AccountingService accountingService;
    private final AuditService auditService;
    private final ProductGLResolver glResolver;

    public SuspenseService(AccountingService accountingService,
                            AuditService auditService,
                            ProductGLResolver glResolver) {
        this.accountingService = accountingService;
        this.auditService = auditService;
        this.glResolver = glResolver;
    }

    /**
     * Reverses previously recognized interest income to suspense when account becomes NPA.
     * Called during NPA classification in EOD batch.
     *
     * Per RBI IRAC: "Interest accrued and credited to income account in the past
     * periods which has not been realized should be reversed."
     *
     * IMPORTANT: This method modifies account.accruedInterest on the passed entity reference
     * but does NOT save the account. The caller (classifyNPA) is responsible for saving
     * to avoid the double-save overwrite problem where two saves in the same transaction
     * with different field modifications cause one to overwrite the other.
     *
     * @param account The loan account that just became NPA (modified in-place, not saved)
     * @param businessDate CBS business date
     */
    @Transactional
    public void reverseInterestToSuspense(LoanAccount account, LocalDate businessDate) {
        BigDecimal accruedInterest = account.getAccruedInterest();

        if (accruedInterest.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("No accrued interest to reverse for NPA account: {}", account.getAccountNumber());
            return;
        }

        // Post GL entry: reverse interest from P&L to suspense
        // CBS: GL codes resolved through product definition per Finacle PDDEF
        String productType = account.getProductType();
        List<AccountingService.JournalLineRequest> lines = List.of(
            new AccountingService.JournalLineRequest(
                glResolver.getInterestIncomeGL(productType), DebitCredit.DEBIT, accruedInterest,
                "NPA income reversal - " + account.getAccountNumber()),
            new AccountingService.JournalLineRequest(
                glResolver.getInterestSuspenseGL(productType), DebitCredit.CREDIT, accruedInterest,
                "Interest to suspense - " + account.getAccountNumber())
        );

        accountingService.postJournalEntry(
            businessDate,
            "RBI IRAC income reversal for NPA account " + account.getAccountNumber(),
            "SUSPENSE", account.getAccountNumber(),
            lines
        );

        // Update account in-place: move accrued interest to suspense tracking.
        // The accruedInterest on the account represents what was recognized in P&L.
        // After reversal, it's in suspense — tracked but not in P&L.
        // NOTE: Do NOT save here — caller (classifyNPA) saves after all modifications.
        account.setAccruedInterest(BigDecimal.ZERO);

        auditService.logEvent("LoanAccount", account.getId(), "SUSPENSE_REVERSAL",
            accruedInterest.toString(), "0", "SUSPENSE",
            "Interest reversed to suspense: ₹" + accruedInterest + " for " + account.getAccountNumber());

        log.info("Interest reversed to suspense: accNo={}, amount={}",
            account.getAccountNumber(), accruedInterest);
    }

    /**
     * Releases interest from suspense to income when NPA interest is actually collected.
     * Called during repayment processing when payment is received on NPA account.
     *
     * @param account The NPA loan account
     * @param interestCollected Amount of interest actually collected
     * @param businessDate CBS business date
     */
    @Transactional
    public void releaseFromSuspense(LoanAccount account, BigDecimal interestCollected,
                                     LocalDate businessDate) {
        if (interestCollected.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // Post GL entry: release from suspense to income
        // CBS: GL codes resolved through product definition per Finacle PDDEF
        String productType = account.getProductType();
        List<AccountingService.JournalLineRequest> lines = List.of(
            new AccountingService.JournalLineRequest(
                glResolver.getInterestSuspenseGL(productType), DebitCredit.DEBIT, interestCollected,
                "Suspense release - " + account.getAccountNumber()),
            new AccountingService.JournalLineRequest(
                glResolver.getInterestIncomeGL(productType), DebitCredit.CREDIT, interestCollected,
                "NPA interest collected - " + account.getAccountNumber())
        );

        accountingService.postJournalEntry(
            businessDate,
            "NPA interest collected for " + account.getAccountNumber(),
            "SUSPENSE", account.getAccountNumber(),
            lines
        );

        auditService.logEvent("LoanAccount", account.getId(), "SUSPENSE_RELEASE",
            null, interestCollected.toString(), "SUSPENSE",
            "Interest released from suspense: ₹" + interestCollected + " for " + account.getAccountNumber());

        log.info("Interest released from suspense: accNo={}, amount={}",
            account.getAccountNumber(), interestCollected);
    }
}
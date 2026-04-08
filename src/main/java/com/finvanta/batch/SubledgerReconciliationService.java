package com.finvanta.batch;

import com.finvanta.accounting.GLConstants;
import com.finvanta.audit.AuditService;
import com.finvanta.repository.DepositAccountRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.LoanAccountRepository;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * CBS Subledger-to-GL Reconciliation Service.
 *
 * Per RBI audit requirements and Finacle/Temenos reconciliation standards:
 * The subledger (account-level balances) must match the GL (general ledger)
 * at all times. Any discrepancy indicates either:
 *   1. A bug in balance update logic (subledger updated but GL not, or vice versa)
 *   2. Direct DB manipulation bypassing the application layer
 *   3. Concurrent modification causing lost updates
 *
 * Reconciliation checks:
 *   1. Sum(loan_accounts.outstanding_principal) == GL 1001 net debit balance
 *   2. Sum(deposit_accounts.ledger_balance) for SAVINGS == GL 2010 net credit balance
 *   3. Sum(deposit_accounts.ledger_balance) for CURRENT == GL 2020 net credit balance
 *
 * This complements the existing ReconciliationService (ledger-vs-GL) and
 * AccountingReconciliationEngine (journal-vs-GL) to form the complete
 * three-way reconciliation required for Tier-1 CBS:
 *   Subledger ↔ GL ↔ Ledger/Journal
 *
 * Runs as part of EOD after GL reconciliation (Step 7.1).
 */
@Service
public class SubledgerReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SubledgerReconciliationService.class);

    private final LoanAccountRepository loanAccountRepository;
    private final DepositAccountRepository depositAccountRepository;
    private final GLMasterRepository glMasterRepository;
    private final AuditService auditService;

    public SubledgerReconciliationService(LoanAccountRepository loanAccountRepository,
                                           DepositAccountRepository depositAccountRepository,
                                           GLMasterRepository glMasterRepository,
                                           AuditService auditService) {
        this.loanAccountRepository = loanAccountRepository;
        this.depositAccountRepository = depositAccountRepository;
        this.glMasterRepository = glMasterRepository;
        this.auditService = auditService;
    }

    /**
     * Runs subledger-to-GL reconciliation for all major account types.
     *
     * @return SubledgerReconciliationResult with balanced flag and discrepancy details
     */
    public SubledgerReconciliationResult reconcile() {
        String tenantId = TenantContext.getCurrentTenant();
        List<SubledgerDiscrepancy> discrepancies = new ArrayList<>();

        // Check 1: Loan outstanding principal vs GL 1001 (Loan Asset)
        BigDecimal subledgerLoanPrincipal = loanAccountRepository
            .calculateTotalOutstandingPrincipal(tenantId);
        BigDecimal glLoanAssetNet = getGlNetDebitBalance(tenantId, GLConstants.LOAN_ASSET);
        checkBalance("LOAN_PRINCIPAL", "Loan Outstanding Principal",
            GLConstants.LOAN_ASSET, subledgerLoanPrincipal, glLoanAssetNet, discrepancies);

        // Check 2: CASA Savings deposits vs GL 2010 (SB Deposits)
        BigDecimal subledgerSavings = calculateSavingsBalance(tenantId);
        BigDecimal glSavingsNet = getGlNetCreditBalance(tenantId, GLConstants.SB_DEPOSITS);
        checkBalance("CASA_SAVINGS", "Savings Deposit Balance",
            GLConstants.SB_DEPOSITS, subledgerSavings, glSavingsNet, discrepancies);

        // Check 3: CASA Current deposits vs GL 2020 (CA Deposits)
        BigDecimal subledgerCurrent = calculateCurrentBalance(tenantId);
        BigDecimal glCurrentNet = getGlNetCreditBalance(tenantId, GLConstants.CA_DEPOSITS);
        checkBalance("CASA_CURRENT", "Current Account Balance",
            GLConstants.CA_DEPOSITS, subledgerCurrent, glCurrentNet, discrepancies);

        boolean balanced = discrepancies.isEmpty();

        // Audit trail
        String status = balanced
            ? "SUBLEDGER_BALANCED"
            : "SUBLEDGER_IMBALANCED (" + discrepancies.size() + " discrepancies)";
        auditService.logEvent("Reconciliation", 0L, "SUBLEDGER_RECONCILE",
            null, status, "RECONCILIATION",
            "Subledger reconciliation: " + status);

        if (balanced) {
            log.info("Subledger reconciliation BALANCED: all subledger totals match GL");
        } else {
            for (SubledgerDiscrepancy d : discrepancies) {
                log.error("SUBLEDGER MISMATCH: check={}, subledger={}, gl={}, variance={}",
                    d.checkName(), d.subledgerTotal(), d.glTotal(), d.variance());
            }
        }

        return new SubledgerReconciliationResult(balanced, discrepancies);
    }

    private void checkBalance(String checkCode, String checkName, String glCode,
                               BigDecimal subledgerTotal, BigDecimal glTotal,
                               List<SubledgerDiscrepancy> discrepancies) {
        BigDecimal variance = subledgerTotal.subtract(glTotal);
        if (variance.abs().compareTo(new BigDecimal("0.01")) > 0) {
            discrepancies.add(new SubledgerDiscrepancy(
                checkCode, checkName, glCode, subledgerTotal, glTotal, variance));
        }
    }

    /**
     * GL net debit balance = debitBalance - creditBalance.
     * For asset GLs (1xxx), net debit is the outstanding amount.
     */
    private BigDecimal getGlNetDebitBalance(String tenantId, String glCode) {
        return glMasterRepository.findByTenantIdAndGlCode(tenantId, glCode)
            .map(gl -> gl.getDebitBalance().subtract(gl.getCreditBalance()))
            .orElse(BigDecimal.ZERO);
    }

    /**
     * GL net credit balance = creditBalance - debitBalance.
     * For liability GLs (2xxx), net credit is the deposit balance.
     */
    private BigDecimal getGlNetCreditBalance(String tenantId, String glCode) {
        return glMasterRepository.findByTenantIdAndGlCode(tenantId, glCode)
            .map(gl -> gl.getCreditBalance().subtract(gl.getDebitBalance()))
            .orElse(BigDecimal.ZERO);
    }

    /**
     * Sum of all non-closed SAVINGS account ledger balances.
     */
    private BigDecimal calculateSavingsBalance(String tenantId) {
        return depositAccountRepository.findAllNonClosedAccounts(tenantId).stream()
            .filter(a -> a.isSavings())
            .map(a -> a.getLedgerBalance())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Sum of all non-closed CURRENT account ledger balances.
     */
    private BigDecimal calculateCurrentBalance(String tenantId) {
        return depositAccountRepository.findAllNonClosedAccounts(tenantId).stream()
            .filter(a -> a.isCurrent())
            .map(a -> a.getLedgerBalance())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record SubledgerReconciliationResult(
        boolean isBalanced,
        List<SubledgerDiscrepancy> discrepancies
    ) {
        public int discrepancyCount() {
            return discrepancies != null ? discrepancies.size() : 0;
        }
    }

    public record SubledgerDiscrepancy(
        String checkCode,
        String checkName,
        String glCode,
        BigDecimal subledgerTotal,
        BigDecimal glTotal,
        BigDecimal variance
    ) {}
}

package com.finvanta.batch;

import com.finvanta.domain.entity.GLMaster;
import com.finvanta.repository.GLBranchBalanceRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CBS GL Reconciliation Service per Finacle/Temenos EOD standards.
 *
 * Compares the immutable ledger totals (sum of all ledger entries per GL code)
 * against the GL master running balances. Any discrepancy indicates either:
 *   1. A bug in the GL balance update logic (lost update)
 *   2. Direct DB manipulation bypassing the application layer
 *   3. Data corruption
 *
 * Per RBI audit requirements, this reconciliation must run daily during EOD
 * and any discrepancies must be logged for investigation.
 *
 * Reconciliation formula per GL code:
 *   Ledger sum(debit) must equal GL master debitBalance
 *   Ledger sum(credit) must equal GL master creditBalance
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final GLMasterRepository glMasterRepository;
    private final GLBranchBalanceRepository glBranchBalanceRepository;
    private final LedgerEntryRepository ledgerRepository;

    public ReconciliationService(
            GLMasterRepository glMasterRepository,
            GLBranchBalanceRepository glBranchBalanceRepository,
            LedgerEntryRepository ledgerRepository) {
        this.glMasterRepository = glMasterRepository;
        this.glBranchBalanceRepository = glBranchBalanceRepository;
        this.ledgerRepository = ledgerRepository;
    }

    /**
     * Reconciles ledger totals against GL master balances for all postable GL codes.
     *
     * @return ReconciliationResult with balanced flag and list of discrepancies
     */
    public ReconciliationResult reconcileLedgerVsGL() {
        String tenantId = TenantContext.getCurrentTenant();
        List<GLMaster> glAccounts = glMasterRepository.findAllPostableAccounts(tenantId);

        List<Discrepancy> discrepancies = new ArrayList<>();
        int checkedCount = 0;

        for (GLMaster gl : glAccounts) {
            BigDecimal ledgerDebit = ledgerRepository.sumDebitByGlCode(tenantId, gl.getGlCode());
            BigDecimal ledgerCredit = ledgerRepository.sumCreditByGlCode(tenantId, gl.getGlCode());

            BigDecimal glDebit = gl.getDebitBalance();
            BigDecimal glCredit = gl.getCreditBalance();

            boolean debitMatch = ledgerDebit.compareTo(glDebit) == 0;
            boolean creditMatch = ledgerCredit.compareTo(glCredit) == 0;

            if (!debitMatch || !creditMatch) {
                Discrepancy d =
                        new Discrepancy(gl.getGlCode(), gl.getGlName(), glDebit, ledgerDebit, glCredit, ledgerCredit);
                discrepancies.add(d);

                log.error(
                        "GL RECONCILIATION MISMATCH: glCode={}, glName={}, "
                                + "glDebit={}, ledgerDebit={}, glCredit={}, ledgerCredit={}",
                        gl.getGlCode(),
                        gl.getGlName(),
                        glDebit,
                        ledgerDebit,
                        glCredit,
                        ledgerCredit);
            }

            checkedCount++;
        }

        boolean balanced = discrepancies.isEmpty();
        if (balanced) {
            log.info("GL reconciliation BALANCED: {} GL accounts verified", checkedCount);
        } else {
            log.error(
                    "GL reconciliation FAILED: {}/{} GL accounts have discrepancies",
                    discrepancies.size(),
                    checkedCount);
        }

        return new ReconciliationResult(balanced, checkedCount, discrepancies);
    }

    /**
     * CBS Tier-1 Branch Balance Reconciliation per Finacle GL_BRANCH architecture.
     *
     * Verifies the core invariant:
     *   GLMaster.debitBalance == SUM(GLBranchBalance.debitBalance) across all branches
     *   GLMaster.creditBalance == SUM(GLBranchBalance.creditBalance) across all branches
     *
     * This is called as EOD Step 7.2 to ensure branch-level balances are consistent
     * with tenant-level aggregate balances. Any discrepancy indicates a bug in the
     * dual-update logic in AccountingService.updateGLBalances().
     *
     * @return ReconciliationResult with balanced flag and list of discrepancies
     */
    public ReconciliationResult reconcileBranchBalancesVsGL() {
        String tenantId = TenantContext.getCurrentTenant();
        List<GLMaster> glAccounts = glMasterRepository.findAllPostableAccounts(tenantId);

        List<Discrepancy> discrepancies = new ArrayList<>();
        int checkedCount = 0;

        for (GLMaster gl : glAccounts) {
            BigDecimal branchSumDebit = glBranchBalanceRepository.sumDebitBalanceByGlCode(tenantId, gl.getGlCode());
            BigDecimal branchSumCredit = glBranchBalanceRepository.sumCreditBalanceByGlCode(tenantId, gl.getGlCode());

            BigDecimal glDebit = gl.getDebitBalance();
            BigDecimal glCredit = gl.getCreditBalance();

            boolean debitMatch = branchSumDebit.compareTo(glDebit) == 0;
            boolean creditMatch = branchSumCredit.compareTo(glCredit) == 0;

            if (!debitMatch || !creditMatch) {
                Discrepancy d = new Discrepancy(
                        gl.getGlCode(), gl.getGlName(),
                        glDebit, branchSumDebit,
                        glCredit, branchSumCredit);
                discrepancies.add(d);

                log.error(
                        "BRANCH BALANCE RECONCILIATION MISMATCH: glCode={}, glName={}, "
                                + "glMasterDebit={}, branchSumDebit={}, glMasterCredit={}, branchSumCredit={}",
                        gl.getGlCode(),
                        gl.getGlName(),
                        glDebit,
                        branchSumDebit,
                        glCredit,
                        branchSumCredit);
            }

            checkedCount++;
        }

        boolean balanced = discrepancies.isEmpty();
        if (balanced) {
            log.info("Branch balance reconciliation BALANCED: {} GL accounts verified", checkedCount);
        } else {
            log.error(
                    "Branch balance reconciliation FAILED: {}/{} GL accounts have discrepancies",
                    discrepancies.size(),
                    checkedCount);
        }

        return new ReconciliationResult(balanced, checkedCount, discrepancies);
    }

    /**
     * Result of a GL reconciliation run.
     */
    public record ReconciliationResult(boolean isBalanced, int checkedCount, List<Discrepancy> discrepancies) {
        public int discrepancyCount() {
            return discrepancies != null ? discrepancies.size() : 0;
        }
    }

    /**
     * A single GL code discrepancy between ledger totals and GL master,
     * or between branch balance sum and GL master.
     */
    public record Discrepancy(
            String glCode,
            String glName,
            BigDecimal glMasterDebit,
            BigDecimal ledgerDebit,
            BigDecimal glMasterCredit,
            BigDecimal ledgerCredit) {}
}

package com.finvanta.accounting;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.enums.DebitCredit;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.util.BusinessException;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CBS GL Reconciliation Engine per Finacle/Temenos standards.
 *
 * Performs subledger-vs-GL comparison to detect accounting imbalances.
 * Per RBI audit requirements, reconciliation must run before every Day Close.
 *
 * Reconciliation checks:
 * 1. Trial Balance: Sum(all GL debits) == Sum(all GL credits)
 * 2. Journal Integrity: Sum(journal line debits) == Sum(journal line credits) per GL code
 * 3. GL vs Journal: GL balance matches sum of all journal postings to that GL
 *
 * If any mismatch is found:
 * - Variance report is generated
 * - Day Close is BLOCKED
 * - Admin is alerted
 *
 * Example:
 *   GL 1001 (Loan Portfolio): Debit balance = ₹50,00,000
 *   Sum of all journal lines to GL 1001: Debit = ₹52,00,000, Credit = ₹2,00,000 → Net = ₹50,00,000
 *   Match: ✅
 *
 *   GL 4001 (Interest Income): Credit balance = ₹1,50,000
 *   Sum of all journal lines to GL 4001: Credit = ₹1,55,000 → Mismatch: ❌ (₹5,000 variance)
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final GLMasterRepository glMasterRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AuditService auditService;

    public ReconciliationService(GLMasterRepository glMasterRepository,
                                  JournalEntryRepository journalEntryRepository,
                                  AuditService auditService) {
        this.glMasterRepository = glMasterRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.auditService = auditService;
    }

    /**
     * Runs full GL reconciliation for a business date.
     * Returns a reconciliation report with pass/fail status per GL code.
     *
     * @param businessDate The CBS business date to reconcile
     * @return Reconciliation result map
     */
    public Map<String, Object> runReconciliation(LocalDate businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        List<GLMaster> glAccounts = glMasterRepository.findAllPostableAccounts(tenantId);

        BigDecimal totalGlDebit = BigDecimal.ZERO;
        BigDecimal totalGlCredit = BigDecimal.ZERO;
        List<Map<String, Object>> variances = new ArrayList<>();
        boolean isBalanced = true;

        // Check 1: Trial Balance — Sum(GL debits) == Sum(GL credits)
        for (GLMaster gl : glAccounts) {
            totalGlDebit = totalGlDebit.add(gl.getDebitBalance());
            totalGlCredit = totalGlCredit.add(gl.getCreditBalance());
        }

        boolean trialBalanceOk = totalGlDebit.compareTo(totalGlCredit) == 0;
        if (!trialBalanceOk) {
            isBalanced = false;
            log.error("RECONCILIATION FAILED: Trial balance mismatch — Debit={}, Credit={}, Diff={}",
                totalGlDebit, totalGlCredit, totalGlDebit.subtract(totalGlCredit));
        }

        // Check 2: Per-GL journal integrity
        for (GLMaster gl : glAccounts) {
            BigDecimal journalDebit = journalEntryRepository.sumJournalLinesByGlCode(
                tenantId, gl.getGlCode(), DebitCredit.DEBIT.name());
            BigDecimal journalCredit = journalEntryRepository.sumJournalLinesByGlCode(
                tenantId, gl.getGlCode(), DebitCredit.CREDIT.name());

            if (journalDebit == null) journalDebit = BigDecimal.ZERO;
            if (journalCredit == null) journalCredit = BigDecimal.ZERO;

            BigDecimal glNetBalance = gl.getDebitBalance().subtract(gl.getCreditBalance());
            BigDecimal journalNetBalance = journalDebit.subtract(journalCredit);
            BigDecimal variance = glNetBalance.subtract(journalNetBalance);

            if (variance.compareTo(BigDecimal.ZERO) != 0) {
                isBalanced = false;
                Map<String, Object> var = new HashMap<>();
                var.put("glCode", gl.getGlCode());
                var.put("glName", gl.getGlName());
                var.put("glDebit", gl.getDebitBalance());
                var.put("glCredit", gl.getCreditBalance());
                var.put("glNet", glNetBalance);
                var.put("journalDebit", journalDebit);
                var.put("journalCredit", journalCredit);
                var.put("journalNet", journalNetBalance);
                var.put("variance", variance);
                variances.add(var);

                log.warn("GL VARIANCE: code={}, glNet={}, journalNet={}, variance={}",
                    gl.getGlCode(), glNetBalance, journalNetBalance, variance);
            }
        }

        // Build result
        Map<String, Object> result = new HashMap<>();
        result.put("businessDate", businessDate);
        result.put("totalGlDebit", totalGlDebit);
        result.put("totalGlCredit", totalGlCredit);
        result.put("trialBalanceOk", trialBalanceOk);
        result.put("isBalanced", isBalanced);
        result.put("variances", variances);
        result.put("glAccountCount", glAccounts.size());
        result.put("varianceCount", variances.size());

        // Audit the reconciliation run
        String status = isBalanced ? "BALANCED" : "IMBALANCED (" + variances.size() + " variances)";
        auditService.logEvent("Reconciliation", 0L, "RECONCILE",
            null, status, "RECONCILIATION",
            "GL reconciliation for " + businessDate + ": " + status
                + ", GL Debit=" + totalGlDebit + ", GL Credit=" + totalGlCredit);

        log.info("Reconciliation complete: date={}, balanced={}, variances={}",
            businessDate, isBalanced, variances.size());

        return result;
    }

    /**
     * Validates GL balance integrity — called by EOD engine.
     * Throws BusinessException if GL is imbalanced (blocks day close).
     */
    public void validateForDayClose(LocalDate businessDate) {
        Map<String, Object> result = runReconciliation(businessDate);
        boolean isBalanced = (boolean) result.get("isBalanced");

        if (!isBalanced) {
            int varianceCount = (int) result.get("varianceCount");
            throw new BusinessException("GL_RECONCILIATION_FAILED",
                "GL reconciliation failed for " + businessDate + ": " + varianceCount
                    + " variance(s) detected. Resolve before closing the day.");
        }
    }
}
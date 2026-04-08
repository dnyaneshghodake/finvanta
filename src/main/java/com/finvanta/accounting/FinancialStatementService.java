package com.finvanta.accounting;

import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.util.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CBS Financial Statement Generation Service per RBI/Ind AS standards.
 *
 * Generates the three primary financial statements from the GL:
 *   1. Balance Sheet (Statement of Financial Position)
 *      Assets = Liabilities + Equity
 *   2. Profit & Loss Statement (Income Statement)
 *      Net Profit = Income - Expenses
 *   3. Trial Balance
 *      Sum(Debits) = Sum(Credits)
 *
 * Per RBI Banking Regulation Act 1949 Section 29:
 * "Every banking company shall prepare a balance sheet and profit and loss
 *  account as on the last working day of each year."
 *
 * GL Account Types and Normal Balances:
 *   ASSET    → Normal Debit  → Net = Debit - Credit (positive = asset)
 *   LIABILITY → Normal Credit → Net = Credit - Debit (positive = liability)
 *   EQUITY   → Normal Credit → Net = Credit - Debit (positive = equity)
 *   INCOME   → Normal Credit → Net = Credit - Debit (positive = income)
 *   EXPENSE  → Normal Debit  → Net = Debit - Credit (positive = expense)
 *
 * Balance Sheet equation: Assets = Liabilities + Equity + (Income - Expenses)
 * The retained earnings (Income - Expenses) are implicitly part of Equity
 * until the GL period is closed and P&L is transferred to retained earnings.
 */
@Service
public class FinancialStatementService {

    private static final Logger log = LoggerFactory.getLogger(FinancialStatementService.class);

    private final GLMasterRepository glMasterRepository;

    public FinancialStatementService(GLMasterRepository glMasterRepository) {
        this.glMasterRepository = glMasterRepository;
    }

    /**
     * Generates a Balance Sheet from the current GL balances.
     *
     * @return BalanceSheet with assets, liabilities, equity sections and totals
     */
    public BalanceSheet generateBalanceSheet() {
        String tenantId = TenantContext.getCurrentTenant();

        List<GLLineItem> assets = buildSection(tenantId, GLAccountType.ASSET, true);
        List<GLLineItem> liabilities = buildSection(tenantId, GLAccountType.LIABILITY, false);
        List<GLLineItem> equity = buildSection(tenantId, GLAccountType.EQUITY, false);

        BigDecimal totalAssets = assets.stream()
            .map(GLLineItem::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLiabilities = liabilities.stream()
            .map(GLLineItem::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEquity = equity.stream()
            .map(GLLineItem::balance).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Retained earnings = Income - Expenses (current period P&L not yet closed)
        BigDecimal retainedEarnings = calculateRetainedEarnings(tenantId);
        BigDecimal totalEquityWithRetained = totalEquity.add(retainedEarnings);
        BigDecimal totalLiabilitiesAndEquity = totalLiabilities.add(totalEquityWithRetained);

        boolean isBalanced = totalAssets.compareTo(totalLiabilitiesAndEquity) == 0;

        log.info("Balance Sheet generated: assets={}, liabilities={}, equity={}, retained={}, balanced={}",
            totalAssets, totalLiabilities, totalEquity, retainedEarnings, isBalanced);

        return new BalanceSheet(
            assets, liabilities, equity,
            totalAssets, totalLiabilities, totalEquity,
            retainedEarnings, totalLiabilitiesAndEquity, isBalanced
        );
    }

    /**
     * Generates a Profit & Loss Statement from the current GL balances.
     *
     * @return ProfitAndLoss with income, expense sections and net profit
     */
    public ProfitAndLoss generateProfitAndLoss() {
        String tenantId = TenantContext.getCurrentTenant();

        List<GLLineItem> income = buildSection(tenantId, GLAccountType.INCOME, false);
        List<GLLineItem> expenses = buildSection(tenantId, GLAccountType.EXPENSE, true);

        BigDecimal totalIncome = income.stream()
            .map(GLLineItem::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalExpenses = expenses.stream()
            .map(GLLineItem::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netProfit = totalIncome.subtract(totalExpenses);

        log.info("P&L generated: income={}, expenses={}, netProfit={}",
            totalIncome, totalExpenses, netProfit);

        return new ProfitAndLoss(income, expenses, totalIncome, totalExpenses, netProfit);
    }

    /**
     * Generates a Trial Balance from all postable GL accounts.
     *
     * @return TrialBalance with all GL accounts and debit/credit totals
     */
    public TrialBalance generateTrialBalance() {
        String tenantId = TenantContext.getCurrentTenant();
        List<GLMaster> accounts = glMasterRepository.findAllPostableAccounts(tenantId);

        List<TrialBalanceLine> lines = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (GLMaster gl : accounts) {
            lines.add(new TrialBalanceLine(
                gl.getGlCode(), gl.getGlName(), gl.getAccountType().name(),
                gl.getDebitBalance(), gl.getCreditBalance(), gl.getNetBalance()
            ));
            totalDebit = totalDebit.add(gl.getDebitBalance());
            totalCredit = totalCredit.add(gl.getCreditBalance());
        }

        boolean isBalanced = totalDebit.compareTo(totalCredit) == 0;

        log.info("Trial Balance: accounts={}, debit={}, credit={}, balanced={}",
            lines.size(), totalDebit, totalCredit, isBalanced);

        return new TrialBalance(lines, totalDebit, totalCredit, isBalanced);
    }

    // === Helpers ===

    /**
     * Builds a financial statement section from GL accounts of a specific type.
     *
     * @param tenantId    Current tenant
     * @param type        GL account type (ASSET, LIABILITY, etc.)
     * @param debitNormal true if normal balance is debit (ASSET, EXPENSE)
     * @return List of GL line items with computed balances
     */
    private List<GLLineItem> buildSection(String tenantId, GLAccountType type, boolean debitNormal) {
        List<GLMaster> accounts = glMasterRepository.findPostableByType(tenantId, type);
        List<GLLineItem> items = new ArrayList<>();

        for (GLMaster gl : accounts) {
            BigDecimal balance = debitNormal
                ? gl.getDebitBalance().subtract(gl.getCreditBalance())
                : gl.getCreditBalance().subtract(gl.getDebitBalance());

            // Only include accounts with non-zero balance
            if (balance.signum() != 0) {
                items.add(new GLLineItem(gl.getGlCode(), gl.getGlName(), balance));
            }
        }

        return items;
    }

    /**
     * Calculates retained earnings (Income - Expenses) for the current period.
     * This is the P&L that hasn't been closed to retained earnings yet.
     */
    private BigDecimal calculateRetainedEarnings(String tenantId) {
        List<GLMaster> incomeAccounts = glMasterRepository.findPostableByType(tenantId, GLAccountType.INCOME);
        List<GLMaster> expenseAccounts = glMasterRepository.findPostableByType(tenantId, GLAccountType.EXPENSE);

        BigDecimal totalIncome = incomeAccounts.stream()
            .map(gl -> gl.getCreditBalance().subtract(gl.getDebitBalance()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalExpenses = expenseAccounts.stream()
            .map(gl -> gl.getDebitBalance().subtract(gl.getCreditBalance()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return totalIncome.subtract(totalExpenses);
    }

    // === Record Types ===

    public record GLLineItem(String glCode, String glName, BigDecimal balance) {}

    public record BalanceSheet(
        List<GLLineItem> assets,
        List<GLLineItem> liabilities,
        List<GLLineItem> equity,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        BigDecimal retainedEarnings,
        BigDecimal totalLiabilitiesAndEquity,
        boolean isBalanced
    ) {}

    public record ProfitAndLoss(
        List<GLLineItem> income,
        List<GLLineItem> expenses,
        BigDecimal totalIncome,
        BigDecimal totalExpenses,
        BigDecimal netProfit
    ) {}

    public record TrialBalanceLine(
        String glCode, String glName, String accountType,
        BigDecimal debitBalance, BigDecimal creditBalance, BigDecimal netBalance
    ) {}

    public record TrialBalance(
        List<TrialBalanceLine> lines,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        boolean isBalanced
    ) {}
}

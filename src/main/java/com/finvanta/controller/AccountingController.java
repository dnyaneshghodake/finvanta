package com.finvanta.controller;

import com.finvanta.accounting.AccountingService;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.domain.entity.GLMaster;
import com.finvanta.domain.enums.GLAccountType;
import com.finvanta.repository.DepositTransactionRepository;
import com.finvanta.repository.GLMasterRepository;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.repository.LedgerEntryRepository;
import com.finvanta.repository.LoanTransactionRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/accounting")
public class AccountingController {

    private final AccountingService accountingService;
    private final JournalEntryRepository journalEntryRepository;
    private final GLMasterRepository glMasterRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final DepositTransactionRepository depositTransactionRepository;
    private final BusinessDateService businessDateService;

    public AccountingController(
            AccountingService accountingService,
            JournalEntryRepository journalEntryRepository,
            GLMasterRepository glMasterRepository,
            LedgerEntryRepository ledgerEntryRepository,
            LoanTransactionRepository loanTransactionRepository,
            DepositTransactionRepository depositTransactionRepository,
            BusinessDateService businessDateService) {
        this.accountingService = accountingService;
        this.journalEntryRepository = journalEntryRepository;
        this.glMasterRepository = glMasterRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.loanTransactionRepository = loanTransactionRepository;
        this.depositTransactionRepository = depositTransactionRepository;
        this.businessDateService = businessDateService;
    }

    @GetMapping("/trial-balance")
    public ModelAndView trialBalance() {
        ModelAndView mav = new ModelAndView("accounting/trial-balance");
        mav.addObject("trialBalance", accountingService.getTrialBalance());
        return mav;
    }

    /**
     * CBS GL Account Search per Finacle GLINQ / Temenos GL.ENQUIRY.
     * Searches by GL code, GL name, or account type (ASSET/LIABILITY/INCOME/EXPENSE/EQUITY).
     * Per RBI Inspection Manual: inspectors require instant GL lookup during on-site examination.
     * Tenant-scoped (GL is bank-wide, no branch isolation per Finacle GL_MASTER).
     */
    @GetMapping("/gl/search")
    public ModelAndView searchGLAccounts(@RequestParam(required = false) String q) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("accounting/trial-balance");
        if (q != null && !q.isBlank() && q.trim().length() >= 2) {
            List<GLMaster> results = glMasterRepository.searchGLAccounts(tenantId, q.trim());
            // CBS: Build trial balance format matching AccountingService.getTrialBalance() structure
            Map<String, Object> trialBalance = new java.util.HashMap<>();
            Map<String, Map<String, Object>> accountBalances = new LinkedHashMap<>();
            BigDecimal totalDebit = BigDecimal.ZERO;
            BigDecimal totalCredit = BigDecimal.ZERO;

            for (GLMaster gl : results) {
                Map<String, Object> balance = new java.util.HashMap<>();
                balance.put("glCode", gl.getGlCode());
                balance.put("glName", gl.getGlName());
                balance.put("accountType", gl.getAccountType().name());
                balance.put("debitBalance", gl.getDebitBalance());
                balance.put("creditBalance", gl.getCreditBalance());
                balance.put("netBalance", gl.getNetBalance());

                totalDebit = totalDebit.add(gl.getDebitBalance());
                totalCredit = totalCredit.add(gl.getCreditBalance());

                accountBalances.put(gl.getGlCode(), balance);
            }

            trialBalance.put("accounts", accountBalances);
            trialBalance.put("totalDebit", totalDebit);
            trialBalance.put("totalCredit", totalCredit);
            trialBalance.put("isBalanced", totalDebit.compareTo(totalCredit) == 0);

            mav.addObject("trialBalance", trialBalance);
            mav.addObject("searchQuery", q);
        } else {
            mav.addObject("trialBalance", accountingService.getTrialBalance());
        }
        return mav;
    }

    /** CBS: Max journal search results to prevent OOM on large journal tables */
    private static final int MAX_JOURNAL_RESULTS = 500;

    /**
     * CBS Journal Entry Search per Finacle JRNL_INQUIRY / Temenos STMT.ENTRY.BOOK.
     * Searches by journal ref, narration, source module, source ref, or branch code.
     * Branch-scoped for MAKER/CHECKER per Finacle BRANCH_CONTEXT.
     * Per RBI Inspection Manual: inspectors require instant journal lookup during on-site.
     */
    @GetMapping("/journal-entries/search")
    public ModelAndView searchJournalEntries(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("accounting/journal-entries");

        LocalDate currentBizDate = resolveCurrentBusinessDate();
        LocalDate from;
        LocalDate to;
        try {
            from = (fromDate != null && !fromDate.isBlank()) ? LocalDate.parse(fromDate) : currentBizDate.minusDays(30);
            to = (toDate != null && !toDate.isBlank()) ? LocalDate.parse(toDate) : currentBizDate;
        } catch (Exception e) {
            // CBS: Graceful fallback on malformed date input per Finacle JRNL_INQUIRY.
            // Per RBI IT Governance: user input errors must not cause HTTP 500 on
            // operational screens used during RBI on-site inspection.
            from = currentBizDate.minusDays(30);
            to = currentBizDate;
            mav.addObject("error", "Invalid date format. Showing default 30-day range.");
        }
        mav.addObject("fromDate", from);
        mav.addObject("toDate", to);

        List<com.finvanta.domain.entity.JournalEntry> entries;
        if (q != null && !q.isBlank() && q.trim().length() >= 2) {
            String trimmed = q.trim();
            if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
                entries = journalEntryRepository.searchJournalEntries(
                        tenantId, trimmed, PageRequest.of(0, MAX_JOURNAL_RESULTS));
            } else {
                Long branchId = SecurityUtil.getCurrentUserBranchId();
                if (branchId != null) {
                    entries = journalEntryRepository.searchJournalEntriesByBranch(
                            tenantId, branchId, trimmed, PageRequest.of(0, MAX_JOURNAL_RESULTS));
                } else {
                    entries = java.util.Collections.emptyList();
                }
            }
            mav.addObject("searchQuery", q);
        } else {
            // CBS Tier-1: limit at DB level via Pageable to prevent OOM on busy date ranges.
            // The previous approach loaded ALL rows then truncated with subList() — on busy
            // systems with 100K+ entries, the full result set caused OOM before the cap.
            entries = journalEntryRepository.findByTenantIdAndValueDateBetweenPaged(
                    tenantId, from, to, PageRequest.of(0, MAX_JOURNAL_RESULTS));
            if (entries.size() >= MAX_JOURNAL_RESULTS) {
                mav.addObject("warning", "Results capped at " + MAX_JOURNAL_RESULTS
                        + " entries. Narrow the date range or use search for specific entries.");
            }
        }
        mav.addObject("entries", entries);
        addJournalTotals(mav, entries);
        return mav;
    }

    @GetMapping("/journal-entries")
    public ModelAndView journalEntries(
            @RequestParam(required = false) String fromDate, @RequestParam(required = false) String toDate) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("accounting/journal-entries");

        // CBS: Default date range is current business date minus 30 days to current date.
        // Per Finacle JRNL_REGISTER: journal entries default to a meaningful range,
        // not a single day. A single-day default shows "no entries" for most dates
        // because journal postings are sparse — the user must manually expand the range.
        // 30 days matches the standard CBS monthly journal register view.
        LocalDate currentBizDate = resolveCurrentBusinessDate();
        LocalDate from;
        LocalDate to;
        try {
            from = (fromDate != null && !fromDate.isBlank()) ? LocalDate.parse(fromDate) : currentBizDate.minusDays(30);
            to = (toDate != null && !toDate.isBlank()) ? LocalDate.parse(toDate) : currentBizDate;
        } catch (Exception e) {
            from = currentBizDate.minusDays(30);
            to = currentBizDate;
            mav.addObject("error", "Invalid date format. Showing default 30-day range.");
        }

        // CBS Tier-1: limit at DB level via Pageable to prevent OOM on busy date ranges.
        List<com.finvanta.domain.entity.JournalEntry> entries =
                journalEntryRepository.findByTenantIdAndValueDateBetweenPaged(
                        tenantId, from, to, PageRequest.of(0, MAX_JOURNAL_RESULTS));
        if (entries.size() >= MAX_JOURNAL_RESULTS) {
            mav.addObject("warning", "Results capped at " + MAX_JOURNAL_RESULTS
                    + " entries. Narrow the date range for complete data.");
        }
        mav.addObject("entries", entries);
        addJournalTotals(mav, entries);
        mav.addObject("fromDate", from);
        mav.addObject("toDate", to);

        return mav;
    }

    /**
     * CBS Tier-1: aggregate Debit/Credit totals for the journal-entry tfoot totals row
     * per Finacle JRNL_INQUIRY standard. Null-safe against entities with unset totals.
     */
    private void addJournalTotals(
            ModelAndView mav, List<com.finvanta.domain.entity.JournalEntry> entries) {
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (com.finvanta.domain.entity.JournalEntry entry : entries) {
            if (entry.getTotalDebit() != null) {
                totalDebit = totalDebit.add(entry.getTotalDebit());
            }
            if (entry.getTotalCredit() != null) {
                totalCredit = totalCredit.add(entry.getTotalCredit());
            }
        }
        mav.addObject("totalDebit", totalDebit);
        mav.addObject("totalCredit", totalCredit);
    }

    /**
     * CBS Balance Sheet & Income Statement per Finacle BALSHEET / Temenos FINANCIAL.STATEMENT.
     *
     * Per RBI regulatory reporting and Ind AS:
     * - Balance Sheet: Assets = Liabilities + Equity (must balance)
     * - Income Statement: Income - Expenses = Net Profit/Loss
     *
     * Generated from GL Master balances (net balance = debit - credit for assets/expenses,
     * credit - debit for liabilities/equity/income).
     */
    @GetMapping("/financial-statements")
    public ModelAndView financialStatements() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("accounting/financial-statements");
        mav.addObject("pageTitle", "Financial Statements");

        List<GLMaster> allGLs = glMasterRepository.findAllPostableAccounts(tenantId);

        // Balance Sheet: Assets, Liabilities, Equity
        Map<String, BigDecimal> assets = new LinkedHashMap<>();
        Map<String, BigDecimal> liabilities = new LinkedHashMap<>();
        Map<String, BigDecimal> equity = new LinkedHashMap<>();
        // Income Statement: Income, Expenses
        Map<String, BigDecimal> income = new LinkedHashMap<>();
        Map<String, BigDecimal> expenses = new LinkedHashMap<>();

        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;
        BigDecimal totalIncome = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;

        for (GLMaster gl : allGLs) {
            BigDecimal netBalance;
            String label = gl.getGlCode() + " — " + gl.getGlName();

            if (gl.getAccountType() == GLAccountType.ASSET || gl.getAccountType() == GLAccountType.EXPENSE) {
                netBalance = gl.getDebitBalance().subtract(gl.getCreditBalance());
            } else {
                netBalance = gl.getCreditBalance().subtract(gl.getDebitBalance());
            }

            // Skip zero-balance GLs for cleaner display
            if (netBalance.signum() == 0) continue;

            switch (gl.getAccountType()) {
                case ASSET -> {
                    assets.put(label, netBalance);
                    totalAssets = totalAssets.add(netBalance);
                }
                case LIABILITY -> {
                    liabilities.put(label, netBalance);
                    totalLiabilities = totalLiabilities.add(netBalance);
                }
                case EQUITY -> {
                    equity.put(label, netBalance);
                    totalEquity = totalEquity.add(netBalance);
                }
                case INCOME -> {
                    income.put(label, netBalance);
                    totalIncome = totalIncome.add(netBalance);
                }
                case EXPENSE -> {
                    expenses.put(label, netBalance);
                    totalExpenses = totalExpenses.add(netBalance);
                }
            }
        }

        BigDecimal netProfit = totalIncome.subtract(totalExpenses);

        mav.addObject("assets", assets);
        mav.addObject("liabilities", liabilities);
        mav.addObject("equity", equity);
        mav.addObject("income", income);
        mav.addObject("expenses", expenses);
        mav.addObject("totalAssets", totalAssets);
        mav.addObject("totalLiabilities", totalLiabilities);
        mav.addObject("totalEquity", totalEquity);
        mav.addObject("totalIncome", totalIncome);
        mav.addObject("totalExpenses", totalExpenses);
        mav.addObject("netProfit", netProfit);
        // Balance check: Assets should equal Liabilities + Equity + Net Profit
        mav.addObject(
                "balanceCheck",
                totalAssets.subtract(totalLiabilities).subtract(totalEquity).subtract(netProfit));

        return mav;
    }

    /**
     * CBS Voucher Register per Finacle VCHREGISTER / Temenos STMT.ENTRY.
     * Daily report of all vouchers posted on a business date.
     * Essential for branch-level daily reconciliation.
     */
    @GetMapping("/voucher-register")
    public ModelAndView voucherRegister(@RequestParam(required = false) String businessDate) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("accounting/voucher-register");
        mav.addObject("pageTitle", "Voucher Register");

        // CBS: Default to current business date (DAY_OPEN), not system date.
        // Per Finacle VCHREGISTER: voucher register is a daily report for the business date.
        LocalDate date;
        try {
            date = (businessDate != null && !businessDate.isBlank())
                    ? LocalDate.parse(businessDate)
                    : resolveCurrentBusinessDate();
        } catch (Exception e) {
            date = resolveCurrentBusinessDate();
            mav.addObject("error", "Invalid date format. Showing current business date.");
        }

        // Ledger entries for the date (all vouchers)
        mav.addObject(
                "ledgerEntries",
                ledgerEntryRepository.findByTenantIdAndBusinessDateOrderByLedgerSequenceAsc(tenantId, date));

        // Loan transactions for the date
        mav.addObject(
                "loanTransactions", loanTransactionRepository.findByTenantIdAndValueDateBetween(tenantId, date, date));

        // Deposit transactions for the date
        mav.addObject("depositTransactions", depositTransactionRepository.findByTenantIdAndValueDate(tenantId, date));

        mav.addObject("reportDate", date);
        return mav;
    }

    /**
     * Resolve current business date from the DAY_OPEN calendar entry.
     * Per Finacle/Temenos: all accounting reports default to the current business date,
     * NOT the system date. Falls back to system date only if no day is open.
     */
    private LocalDate resolveCurrentBusinessDate() {
        BusinessCalendar openDay = businessDateService.getOpenDayOrNull();
        return openDay != null ? openDay.getBusinessDate() : LocalDate.now();
    }
}

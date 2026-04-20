package com.finvanta.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * CBS Transaction Preview per Finacle TRAN_PREVIEW / Temenos OFS.VALIDATE.
 *
 * <p>Captures the result of a dry-run validation through the TransactionEngine pipeline
 * WITHOUT committing any GL posting. Every validation step from the 10-step chain is
 * executed and its pass/fail status recorded. This enables the UI to show a complete
 * pre-posting checklist before the operator confirms.
 *
 * <p><b>Tier-1 CBS Rationale:</b> In Finacle, the teller sees a "Preview" screen after
 * entering transaction details but BEFORE clicking "Post". This screen shows:
 * <ul>
 *   <li>All validation results (amount, limits, day status, batch, GL, etc.)</li>
 *   <li>Whether maker-checker approval will be required</li>
 *   <li>The GL journal lines that will be posted (DR/CR legs)</li>
 *   <li>Any blockers that would prevent the posting</li>
 * </ul>
 *
 * <p>Per RBI Operational Risk Guidelines: operators must be able to verify the
 * transaction details and GL impact BEFORE committing an irreversible posting.
 * This is especially critical for high-value transactions where a reversal
 * requires dual authorization (REVERSAL is in ALWAYS_REQUIRE_APPROVAL).
 *
 * @see TransactionEngine#validate(TransactionRequest)
 */
public class TransactionPreview {

    /** Overall result: true if ALL checks passed (transaction can be posted) */
    private final boolean canPost;

    /** Whether this transaction will require maker-checker approval */
    private final boolean requiresApproval;

    /** Individual validation check results in pipeline order */
    private final List<CheckResult> checks;

    /** GL journal lines that would be posted (for operator verification) */
    private final List<JournalLinePreview> journalLines;

    /** Account details for display */
    private final String accountNumber;
    private final String accountHolder;
    private final String branchCode;
    private final BigDecimal currentBalance;
    private final BigDecimal projectedBalance;
    private final BigDecimal amount;
    private final String transactionType;
    private final LocalDate valueDate;
    private final String narration;

    /** Per-transaction and daily aggregate limit info */
    private final BigDecimal perTransactionLimit;
    private final BigDecimal dailyAggregateLimit;
    private final BigDecimal dailyUsedAmount;

    private TransactionPreview(Builder builder) {
        this.canPost = builder.checks.stream().allMatch(CheckResult::passed);
        this.requiresApproval = builder.requiresApproval;
        this.checks = List.copyOf(builder.checks);
        this.journalLines = builder.journalLines != null ? List.copyOf(builder.journalLines) : List.of();
        this.accountNumber = builder.accountNumber;
        this.accountHolder = builder.accountHolder;
        this.branchCode = builder.branchCode;
        this.currentBalance = builder.currentBalance;
        this.projectedBalance = builder.projectedBalance;
        this.amount = builder.amount;
        this.transactionType = builder.transactionType;
        this.valueDate = builder.valueDate;
        this.narration = builder.narration;
        this.perTransactionLimit = builder.perTransactionLimit;
        this.dailyAggregateLimit = builder.dailyAggregateLimit;
        this.dailyUsedAmount = builder.dailyUsedAmount;
    }

    // --- Getters ---
    public boolean isCanPost() { return canPost; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public List<CheckResult> getChecks() { return checks; }
    public List<JournalLinePreview> getJournalLines() { return journalLines; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountHolder() { return accountHolder; }
    public String getBranchCode() { return branchCode; }
    public BigDecimal getCurrentBalance() { return currentBalance; }
    public BigDecimal getProjectedBalance() { return projectedBalance; }
    public BigDecimal getAmount() { return amount; }
    public String getTransactionType() { return transactionType; }
    public LocalDate getValueDate() { return valueDate; }
    public String getNarration() { return narration; }
    public BigDecimal getPerTransactionLimit() { return perTransactionLimit; }
    public BigDecimal getDailyAggregateLimit() { return dailyAggregateLimit; }
    public BigDecimal getDailyUsedAmount() { return dailyUsedAmount; }

    /** Count of failed checks (blockers) */
    public long getBlockerCount() {
        return checks.stream().filter(c -> !c.passed()).count();
    }

    /**
     * Individual validation check result per Finacle TRAN_PREVIEW checklist.
     *
     * @param step     Pipeline step name (e.g., "AMOUNT_VALIDATION", "DAY_STATUS")
     * @param category Display category (e.g., "Amount", "Day Control", "GL")
     * @param description Human-readable description of what was checked
     * @param passed   true if the check passed, false if it's a blocker
     * @param detail   Additional detail (e.g., limit values, GL codes, error message)
     */
    public record CheckResult(String step, String category, String description, boolean passed, String detail) {}

    /**
     * GL journal line preview — shows the DR/CR legs that would be posted.
     * Per Finacle TRAN_PREVIEW: the operator must see the full double-entry
     * impact before confirming.
     */
    public record JournalLinePreview(String glCode, String glName, String debitCredit, BigDecimal amount, String narration) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private boolean requiresApproval = false;
        private final List<CheckResult> checks = new ArrayList<>();
        private List<JournalLinePreview> journalLines;
        private String accountNumber;
        private String accountHolder;
        private String branchCode;
        private BigDecimal currentBalance;
        private BigDecimal projectedBalance;
        private BigDecimal amount;
        private String transactionType;
        private LocalDate valueDate;
        private String narration;
        private BigDecimal perTransactionLimit;
        private BigDecimal dailyAggregateLimit;
        private BigDecimal dailyUsedAmount;

        public Builder addCheck(String step, String category, String description, boolean passed, String detail) {
            checks.add(new CheckResult(step, category, description, passed, detail));
            return this;
        }

        public Builder addCheck(String step, String category, String description, boolean passed) {
            return addCheck(step, category, description, passed, null);
        }

        public Builder requiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; return this; }
        public Builder journalLines(List<JournalLinePreview> journalLines) { this.journalLines = journalLines; return this; }
        public Builder accountNumber(String v) { this.accountNumber = v; return this; }
        public Builder accountHolder(String v) { this.accountHolder = v; return this; }
        public Builder branchCode(String v) { this.branchCode = v; return this; }
        public Builder currentBalance(BigDecimal v) { this.currentBalance = v; return this; }
        public Builder projectedBalance(BigDecimal v) { this.projectedBalance = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder transactionType(String v) { this.transactionType = v; return this; }
        public Builder valueDate(LocalDate v) { this.valueDate = v; return this; }
        public Builder narration(String v) { this.narration = v; return this; }
        public Builder perTransactionLimit(BigDecimal v) { this.perTransactionLimit = v; return this; }
        public Builder dailyAggregateLimit(BigDecimal v) { this.dailyAggregateLimit = v; return this; }
        public Builder dailyUsedAmount(BigDecimal v) { this.dailyUsedAmount = v; return this; }

        public TransactionPreview build() { return new TransactionPreview(this); }
    }
}

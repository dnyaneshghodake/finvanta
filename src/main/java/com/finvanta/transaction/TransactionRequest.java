package com.finvanta.transaction;

import com.finvanta.accounting.AccountingService.JournalLineRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * CBS Generic Transaction Request per Finacle TRAN_POSTING / Temenos TRANSACTION framework.
 *
 * This is the universal input to the Transaction Engine. Every CBS module (Loan, Deposit,
 * Remittance, Trade Finance) builds a TransactionRequest and submits it to
 * {@link TransactionEngine#execute(TransactionRequest)}. The engine enforces the
 * 21-step validation chain in the correct order, regardless of which module initiated it.
 *
 * Per Finacle/Temenos standards, a transaction request must declare:
 * - WHAT: amount, transaction type, journal lines (DR/CR legs)
 * - WHO: source module, account reference, initiating user
 * - WHEN: value date (CBS business date, not system date)
 * - WHERE: branch (for inter-branch accounting and branch-level limits)
 * - WHY: narration (mandatory for audit trail)
 *
 * Optional:
 * - Idempotency key (for client-initiated transactions)
 * - Maker-checker override (for system-generated transactions like EOD accrual)
 * - Product type (for GL code resolution)
 *
 * Example — Loan Repayment:
 *   TransactionRequest.builder()
 *       .sourceModule("LOAN")
 *       .transactionType("REPAYMENT")
 *       .accountReference("LN001HQ00120260401000001")
 *       .amount(new BigDecimal("87916.00"))
 *       .valueDate(LocalDate.of(2026, 4, 2))
 *       .branchCode("HQ001")
 *       .productType("TERM_LOAN")
 *       .narration("EMI repayment")
 *       .journalLines(List.of(
 *           new JournalLineRequest("1100", DEBIT, amount, "Bank receipt"),
 *           new JournalLineRequest("1001", CREDIT, principal, "Principal repayment"),
 *           new JournalLineRequest("1002", CREDIT, interest, "Interest repayment")
 *       ))
 *       .idempotencyKey("WEB-LN001-20260402-abc123")
 *       .requiresMakerChecker(false)
 *       .build();
 */
public class TransactionRequest {

    // --- WHAT ---
    private final BigDecimal amount;
    private final String transactionType;
    private final List<JournalLineRequest> journalLines;

    // --- WHO ---
    private final String sourceModule;
    private final String accountReference;
    private final String initiatedBy;

    // --- WHEN ---
    private final LocalDate valueDate;

    // --- WHERE ---
    private final String branchCode;

    // --- WHY ---
    private final String narration;

    // --- OPTIONAL ---
    private final String productType;
    private final String idempotencyKey;
    private final boolean systemGenerated;

    private TransactionRequest(Builder builder) {
        this.amount = builder.amount;
        this.transactionType = builder.transactionType;
        this.journalLines = builder.journalLines;
        this.sourceModule = builder.sourceModule;
        this.accountReference = builder.accountReference;
        this.initiatedBy = builder.initiatedBy;
        this.valueDate = builder.valueDate;
        this.branchCode = builder.branchCode;
        this.narration = builder.narration;
        this.productType = builder.productType;
        this.idempotencyKey = builder.idempotencyKey;
        this.systemGenerated = builder.systemGenerated;
    }

    public BigDecimal getAmount() { return amount; }
    public String getTransactionType() { return transactionType; }
    public List<JournalLineRequest> getJournalLines() { return journalLines; }
    public String getSourceModule() { return sourceModule; }
    public String getAccountReference() { return accountReference; }
    public String getInitiatedBy() { return initiatedBy; }
    public LocalDate getValueDate() { return valueDate; }
    public String getBranchCode() { return branchCode; }
    public String getNarration() { return narration; }
    public String getProductType() { return productType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public boolean isSystemGenerated() { return systemGenerated; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private BigDecimal amount;
        private String transactionType;
        private List<JournalLineRequest> journalLines;
        private String sourceModule;
        private String accountReference;
        private String initiatedBy;
        private LocalDate valueDate;
        private String branchCode;
        private String narration;
        private String productType;
        private String idempotencyKey;
        private boolean systemGenerated = false;

        public Builder amount(BigDecimal amount) { this.amount = amount; return this; }
        public Builder transactionType(String transactionType) { this.transactionType = transactionType; return this; }
        public Builder journalLines(List<JournalLineRequest> journalLines) { this.journalLines = journalLines; return this; }
        public Builder sourceModule(String sourceModule) { this.sourceModule = sourceModule; return this; }
        public Builder accountReference(String accountReference) { this.accountReference = accountReference; return this; }
        public Builder initiatedBy(String initiatedBy) { this.initiatedBy = initiatedBy; return this; }
        public Builder valueDate(LocalDate valueDate) { this.valueDate = valueDate; return this; }
        public Builder branchCode(String branchCode) { this.branchCode = branchCode; return this; }
        public Builder narration(String narration) { this.narration = narration; return this; }
        public Builder productType(String productType) { this.productType = productType; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder systemGenerated(boolean systemGenerated) { this.systemGenerated = systemGenerated; return this; }

        public TransactionRequest build() {
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Transaction amount must be positive");
            }
            if (journalLines == null || journalLines.size() < 2) {
                throw new IllegalArgumentException("Transaction must have at least 2 journal lines (double-entry)");
            }
            if (sourceModule == null || sourceModule.isBlank()) {
                throw new IllegalArgumentException("Source module is mandatory");
            }
            if (valueDate == null) {
                throw new IllegalArgumentException("Value date is mandatory");
            }
            if (narration == null || narration.isBlank()) {
                throw new IllegalArgumentException("Narration is mandatory per CBS audit rules");
            }
            return new TransactionRequest(this);
        }
    }
}

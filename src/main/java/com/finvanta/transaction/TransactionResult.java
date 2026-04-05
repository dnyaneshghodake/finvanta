package com.finvanta.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CBS Generic Transaction Result per Finacle TRAN_POSTING / Temenos TRANSACTION framework.
 *
 * Returned by {@link TransactionEngine#execute(TransactionRequest)} after the 21-step
 * validation chain completes successfully. Contains all references needed by the
 * calling module to update its own subledger.
 *
 * The calling module (Loan, Deposit, etc.) uses this result to:
 * 1. Record the module-specific transaction (LoanTransaction, DepositTransaction, etc.)
 * 2. Update the module-specific account balance
 * 3. Link to the journal entry and voucher for traceability
 *
 * Per Finacle/Temenos, the Transaction Engine is responsible for GL posting and
 * voucher generation. The module is responsible for subledger updates.
 */
public class TransactionResult {

    private final String transactionRef;
    private final String voucherNumber;
    private final Long journalEntryId;
    private final String journalRef;
    private final BigDecimal totalDebit;
    private final BigDecimal totalCredit;
    private final LocalDate valueDate;
    private final LocalDateTime postingDate;
    private final String status;

    public TransactionResult(String transactionRef, String voucherNumber,
                              Long journalEntryId, String journalRef,
                              BigDecimal totalDebit, BigDecimal totalCredit,
                              LocalDate valueDate, LocalDateTime postingDate,
                              String status) {
        this.transactionRef = transactionRef;
        this.voucherNumber = voucherNumber;
        this.journalEntryId = journalEntryId;
        this.journalRef = journalRef;
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
        this.valueDate = valueDate;
        this.postingDate = postingDate;
        this.status = status;
    }

    public String getTransactionRef() { return transactionRef; }
    public String getVoucherNumber() { return voucherNumber; }
    public Long getJournalEntryId() { return journalEntryId; }
    public String getJournalRef() { return journalRef; }
    public BigDecimal getTotalDebit() { return totalDebit; }
    public BigDecimal getTotalCredit() { return totalCredit; }
    public LocalDate getValueDate() { return valueDate; }
    public LocalDateTime getPostingDate() { return postingDate; }
    public String getStatus() { return status; }

    public boolean isPosted() { return "POSTED".equals(status); }
    public boolean isPendingApproval() { return "PENDING_APPROVAL".equals(status); }
}

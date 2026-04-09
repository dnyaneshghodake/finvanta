package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Deposit Transaction Entity per Finacle TRAN_DETAIL / Temenos STMT.ENTRY.
 *
 * Records every financial movement on a deposit (savings/current) account.
 * All transactions are posted via TransactionEngine for GL integrity.
 *
 * Transaction Types:
 *   CASH_DEPOSIT        - Over-the-counter cash deposit
 *   CASH_WITHDRAWAL     - Over-the-counter cash withdrawal
 *   TRANSFER_CREDIT     - Fund transfer received (own account or third party)
 *   TRANSFER_DEBIT      - Fund transfer sent
 *   INTEREST_CREDIT     - Quarterly interest credit (EOD)
 *   TDS_DEBIT           - TDS deduction on interest (Section 194A)
 *   CHEQUE_DEPOSIT       - Cheque deposit (uncleared until clearing cycle)
 *   CHEQUE_WITHDRAWAL   - Cheque encashment
 *   STANDING_INSTRUCTION - Auto-debit for EMI, SIP, utility
 *   CHARGE_DEBIT        - Service charges, min balance penalty
 *   REVERSAL            - Reversal of any transaction
 *   LOAN_DISBURSEMENT   - Loan disbursement credit
 *   LOAN_EMI_DEBIT      - Auto-debit for loan EMI
 */
@Entity
@Table(
        name = "deposit_transactions",
        indexes = {
            @Index(name = "idx_deptxn_tenant_account", columnList = "tenant_id, deposit_account_id"),
            @Index(name = "idx_deptxn_tenant_ref", columnList = "tenant_id, transaction_ref", unique = true),
            @Index(name = "idx_deptxn_value_date", columnList = "tenant_id, value_date"),
            @Index(name = "idx_deptxn_type", columnList = "tenant_id, transaction_type"),
            @Index(name = "idx_deptxn_voucher", columnList = "tenant_id, voucher_number"),
            @Index(name = "idx_deptxn_branch_date", columnList = "tenant_id, branch_id, value_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class DepositTransaction extends BaseEntity {

    @Column(name = "transaction_ref", nullable = false, length = 40)
    private String transactionRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deposit_account_id", nullable = false)
    private DepositAccount depositAccount;

    /**
     * Branch where this transaction was posted.
     * Per Finacle TRAN_DETAIL: every transaction carries the posting branch (SOL).
     * This enables branch-level transaction reports, Day Book, and reconciliation.
     * For inter-branch transfers, each leg has its own branch attribution.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** Branch code denormalized for efficient reporting and mini-statement display. */
    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    /** DEBIT or CREDIT */
    @Column(name = "debit_credit", nullable = false, length = 10)
    private String debitCredit;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** Balance after this transaction */
    @Column(name = "balance_after", nullable = false, precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "posting_date", nullable = false)
    private LocalDateTime postingDate;

    @Column(name = "narration", length = 500)
    private String narration;

    /** Counterparty account for transfers */
    @Column(name = "counterparty_account", length = 40)
    private String counterpartyAccount;

    /** Counterparty name for transfers */
    @Column(name = "counterparty_name", length = 200)
    private String counterpartyName;

    /** Channel: BRANCH, ATM, INTERNET, MOBILE, UPI, NEFT, RTGS, IMPS */
    @Column(name = "channel", length = 20)
    private String channel;

    /** Cheque number (for cheque transactions) */
    @Column(name = "cheque_number", length = 20)
    private String chequeNumber;

    @Column(name = "is_reversed", nullable = false)
    private boolean reversed = false;

    @Column(name = "reversed_by_ref", length = 40)
    private String reversedByRef;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    @Column(name = "voucher_number", length = 40)
    private String voucherNumber;

    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;
}

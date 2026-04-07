package com.finvanta.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CBS CASA (Current Account Savings Account) Entity per Finacle CUSTACCT / Temenos ACCOUNT.
 *
 * Per RBI Banking Regulation Act and Finacle/Temenos CASA standards:
 *
 * Account Types:
 *   SAVINGS         - Individual savings (SB), interest-bearing, RBI regulated rate
 *   SAVINGS_NRI     - NRE/NRO savings per FEMA guidelines
 *   SAVINGS_MINOR   - Minor's account (guardian-operated until 18)
 *   SAVINGS_JOINT   - Joint account (Either/Survivor, Former/Survivor)
 *   SAVINGS_PMJDY   - Pradhan Mantri Jan Dhan Yojana (zero-balance, RBI mandated)
 *   CURRENT         - Business current account, zero interest
 *   CURRENT_OD      - Current with overdraft facility
 *
 * Account Lifecycle:
 *   PENDING_ACTIVATION -> ACTIVE -> DORMANT (2yr no txn) -> INOPERATIVE (10yr)
 *   ACTIVE -> FROZEN (regulatory/court order) -> ACTIVE (unfreeze)
 *   ACTIVE -> CLOSED (customer request + zero balance)
 *   ACTIVE -> DECEASED (death claim processing)
 *
 * Interest Calculation (Savings only):
 *   Daily product method: (Closing Balance * Rate * 1) / (Days in Year)
 *   Credited quarterly (Mar 31, Jun 30, Sep 30, Dec 31) per RBI directive
 *   TDS deducted if annual interest exceeds INR 40,000 (INR 50,000 for senior citizens)
 *
 * GL Mapping:
 *   Deposit: DR Cash/Bank Ops (1100) / CR Customer Deposits (2010/2020)
 *   Withdrawal: DR Customer Deposits / CR Cash/Bank Ops
 *   Interest Credit: DR Interest Expense on Deposits (5010) / CR Customer Deposits
 *   TDS: DR Customer Deposits / CR TDS Payable (2500)
 */
@Entity
@Table(name = "deposit_accounts", indexes = {
    @Index(name = "idx_depacc_tenant_accno", columnList = "tenant_id, account_number", unique = true),
    @Index(name = "idx_depacc_tenant_customer", columnList = "tenant_id, customer_id"),
    @Index(name = "idx_depacc_tenant_status", columnList = "tenant_id, account_status"),
    @Index(name = "idx_depacc_tenant_branch", columnList = "tenant_id, branch_id"),
    @Index(name = "idx_depacc_tenant_type", columnList = "tenant_id, account_type")
})
@Getter
@Setter
@NoArgsConstructor
public class DepositAccount extends BaseEntity {

    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** SAVINGS, SAVINGS_NRI, SAVINGS_MINOR, SAVINGS_JOINT, SAVINGS_PMJDY, CURRENT, CURRENT_OD */
    @Column(name = "account_type", nullable = false, length = 30)
    private String accountType;

    /** Product code from deposit_product_master for GL/interest config */
    @Column(name = "product_code", nullable = false, length = 50)
    private String productCode;

    /** ISO 4217 currency code */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode = "INR";

    // --- Balances ---

    /** Current available balance (ledger balance - holds - uncleared) */
    @Column(name = "available_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    /** Ledger balance (all posted transactions) */
    @Column(name = "ledger_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal ledgerBalance = BigDecimal.ZERO;

    /** Hold/lien amount (blocked for cheque clearing, loan collateral, court order) */
    @Column(name = "hold_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal holdAmount = BigDecimal.ZERO;

    /** Uncleared funds (cheque deposits not yet cleared) */
    @Column(name = "uncleared_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal unclearedAmount = BigDecimal.ZERO;

    /** Overdraft limit (CURRENT_OD accounts only) */
    @Column(name = "od_limit", precision = 18, scale = 2)
    private BigDecimal odLimit = BigDecimal.ZERO;

    /** Minimum balance required per product (penalty if breached) */
    @Column(name = "minimum_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal minimumBalance = BigDecimal.ZERO;

    // --- Interest ---

    /** Annual interest rate (% p.a.) — 0 for current accounts */
    @Column(name = "interest_rate", nullable = false, precision = 8, scale = 4)
    private BigDecimal interestRate = BigDecimal.ZERO;

    /** Accrued interest not yet credited (credited quarterly per RBI) */
    @Column(name = "accrued_interest", nullable = false, precision = 18, scale = 2)
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    /** Last date interest was accrued (daily EOD) */
    @Column(name = "last_interest_accrual_date")
    private LocalDate lastInterestAccrualDate;

    /** Last date interest was credited to account (quarterly) */
    @Column(name = "last_interest_credit_date")
    private LocalDate lastInterestCreditDate;

    /** YTD interest credited — for TDS calculation per RBI Section 194A */
    @Column(name = "ytd_interest_credited", nullable = false, precision = 18, scale = 2)
    private BigDecimal ytdInterestCredited = BigDecimal.ZERO;

    /** YTD TDS deducted */
    @Column(name = "ytd_tds_deducted", nullable = false, precision = 18, scale = 2)
    private BigDecimal ytdTdsDeducted = BigDecimal.ZERO;

    // --- Account Status & Lifecycle ---

    /** PENDING_ACTIVATION, ACTIVE, DORMANT, INOPERATIVE, FROZEN, CLOSED, DECEASED */
    @Column(name = "account_status", nullable = false, length = 30)
    private String accountStatus = "PENDING_ACTIVATION";

    @Column(name = "opened_date")
    private LocalDate openedDate;

    @Column(name = "closed_date")
    private LocalDate closedDate;

    @Column(name = "closure_reason", length = 500)
    private String closureReason;

    /** Last transaction date — for dormancy check (2yr = DORMANT, 10yr = INOPERATIVE) */
    @Column(name = "last_transaction_date")
    private LocalDate lastTransactionDate;

    /** Date account became dormant (null if active) */
    @Column(name = "dormant_date")
    private LocalDate dormantDate;

    /** Freeze reason (court order, regulatory, AML) */
    @Column(name = "freeze_reason", length = 500)
    private String freezeReason;

    /** DEBIT_FREEZE, CREDIT_FREEZE, TOTAL_FREEZE */
    @Column(name = "freeze_type", length = 20)
    private String freezeType;

    // --- Nomination & Joint Holder ---

    /** Nominee name per RBI nomination guidelines */
    @Column(name = "nominee_name", length = 200)
    private String nomineeName;

    /** Nominee relationship: SPOUSE, CHILD, PARENT, SIBLING, OTHER */
    @Column(name = "nominee_relationship", length = 30)
    private String nomineeRelationship;

    /** Joint holder mode: EITHER_SURVIVOR, FORMER_SURVIVOR, JOINTLY */
    @Column(name = "joint_holder_mode", length = 30)
    private String jointHolderMode;

    // --- Cheque Book ---

    @Column(name = "cheque_book_enabled", nullable = false)
    private boolean chequeBookEnabled = false;

    @Column(name = "last_cheque_number", length = 20)
    private String lastChequeNumber;

    // --- ATM/Debit Card ---

    @Column(name = "debit_card_enabled", nullable = false)
    private boolean debitCardEnabled = false;

    @Column(name = "daily_withdrawal_limit", precision = 18, scale = 2)
    private BigDecimal dailyWithdrawalLimit;

    @Column(name = "daily_transfer_limit", precision = 18, scale = 2)
    private BigDecimal dailyTransferLimit;

    // --- Helpers ---

    public boolean isActive() {
        return "ACTIVE".equals(accountStatus);
    }

    public boolean isFrozen() {
        return "FROZEN".equals(accountStatus);
    }

    public boolean isDormant() {
        return "DORMANT".equals(accountStatus);
    }

    public boolean isClosed() {
        return "CLOSED".equals(accountStatus);
    }

    public boolean isSavings() {
        return accountType != null && accountType.startsWith("SAVINGS");
    }

    public boolean isCurrent() {
        return accountType != null && accountType.startsWith("CURRENT");
    }

    public boolean isDebitAllowed() {
        return isActive() && !"DEBIT_FREEZE".equals(freezeType) && !"TOTAL_FREEZE".equals(freezeType);
    }

    /**
     * Per PMLA / RBI Freeze Guidelines:
     * - DEBIT_FREEZE: credits allowed, debits blocked
     * - CREDIT_FREEZE: debits allowed, credits blocked
     * - TOTAL_FREEZE: both blocked
     * - DORMANT: credits allowed (reactivates account)
     * - CLOSED: nothing allowed
     */
    public boolean isCreditAllowed() {
        if (isClosed()) return false;
        if ("CREDIT_FREEZE".equals(freezeType) || "TOTAL_FREEZE".equals(freezeType)) return false;
        return isActive() || isDormant() || (isFrozen() && "DEBIT_FREEZE".equals(freezeType));
    }

    /** Effective available balance including OD limit */
    public BigDecimal getEffectiveAvailable() {
        return availableBalance.add(odLimit != null ? odLimit : BigDecimal.ZERO);
    }

    /** Check if withdrawal amount is within available balance + OD limit */
    public boolean hasSufficientFunds(BigDecimal amount) {
        return getEffectiveAvailable().compareTo(amount) >= 0;
    }
}

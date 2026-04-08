package com.finvanta.domain.entity;

import com.finvanta.domain.enums.GLAccountType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "gl_master", indexes = {
    @Index(name = "idx_gl_tenant_code", columnList = "tenant_id, gl_code", unique = true),
    @Index(name = "idx_gl_type", columnList = "tenant_id, account_type")
})
@Getter
@Setter
@NoArgsConstructor
public class GLMaster extends BaseEntity {

    @Column(name = "gl_code", nullable = false, length = 20)
    private String glCode;

    @Column(name = "gl_name", nullable = false, length = 200)
    private String glName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private GLAccountType accountType;

    @Column(name = "parent_gl_code", length = 20)
    private String parentGlCode;

    @Column(name = "debit_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal debitBalance = BigDecimal.ZERO;

    @Column(name = "credit_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditBalance = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_header_account", nullable = false)
    private boolean headerAccount = false;

    @Column(name = "description", length = 500)
    private String description;

    // === GL Period Close (per Finacle GL_PERIOD / Temenos PERIOD.CLOSE) ===

    /**
     * GL level in the hierarchy: 1 = top-level group, 2 = sub-group, 3+ = leaf account.
     * Used for indented display in Chart of Accounts and financial statements.
     */
    @Column(name = "gl_level")
    private Integer glLevel = 1;

    /**
     * Last period close date — the most recent month-end/year-end when this GL's
     * balance was carried forward. Postings to dates before this are blocked.
     * Per Finacle GL_PERIOD: prevents back-dated postings to closed periods.
     * Null = no period close has been performed (all dates are open).
     */
    @Column(name = "last_period_close_date")
    private java.time.LocalDate lastPeriodCloseDate;

    /**
     * Opening balance for the current period (set during period close).
     * Per Finacle/Temenos: at year-end, P&L accounts (INCOME/EXPENSE) are zeroed
     * and the net is transferred to retained earnings (EQUITY).
     * Balance Sheet accounts (ASSET/LIABILITY/EQUITY) carry forward their balances.
     */
    @Column(name = "opening_debit_balance", precision = 18, scale = 2)
    private BigDecimal openingDebitBalance = BigDecimal.ZERO;

    @Column(name = "opening_credit_balance", precision = 18, scale = 2)
    private BigDecimal openingCreditBalance = BigDecimal.ZERO;

    public BigDecimal getNetBalance() {
        return debitBalance.subtract(creditBalance);
    }

    /** Returns true if this is a P&L account (Income or Expense) — zeroed at year-end */
    public boolean isProfitAndLossAccount() {
        return accountType == GLAccountType.INCOME || accountType == GLAccountType.EXPENSE;
    }

    /** Returns true if this is a Balance Sheet account — carries forward at year-end */
    public boolean isBalanceSheetAccount() {
        return accountType == GLAccountType.ASSET || accountType == GLAccountType.LIABILITY
            || accountType == GLAccountType.EQUITY;
    }
}

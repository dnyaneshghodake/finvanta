package com.finvanta.domain.entity;

import com.finvanta.domain.enums.DepositAccountType;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Daily Balance Snapshot per RBI Savings Interest Directive.
 *
 * Per RBI directive on savings account interest calculation:
 * Interest must be calculated on the MINIMUM daily balance (not closing balance).
 * This entity captures the closing balance of every CASA account at EOD,
 * enabling month-end minimum daily balance computation for interest calculation.
 *
 * <h3>Interest Calculation Methods:</h3>
 * <pre>
 *   Closing Balance Method (current):  interest = closingBalance * rate / 36500
 *   Min Daily Balance Method (Tier-1): interest = minDailyBalance * rate / 36500
 *   Average Daily Balance Method:      interest = avgDailyBalance * rate / 36500
 * </pre>
 *
 * <h3>Minimum Daily Balance Computation:</h3>
 * At quarter-end (Mar 31, Jun 30, Sep 30, Dec 31), the system:
 * 1. Queries all DailyBalanceSnapshot rows for the quarter
 * 2. Finds MIN(closing_balance) for each account
 * 3. Uses that as the interest base instead of current closing balance
 *
 * <h3>Per Finacle ACCT_BAL_HIST / Temenos ACCT.BALANCE.HISTORY:</h3>
 * Every Tier-1 CBS maintains daily balance history for:
 * - Interest calculation (minimum/average daily balance)
 * - Regulatory reporting (average balance for CRR/SLR computation)
 * - Customer dispute resolution (balance on a specific date)
 * - AML transaction monitoring (unusual balance patterns)
 *
 * Captured during EOD after all transactions for the day are complete.
 * One row per account per business date.
 */
@Entity
@Table(
        name = "daily_balance_snapshots",
        indexes = {
            @Index(
                    name = "idx_dbs_tenant_acct_date",
                    columnList = "tenant_id, account_id, business_date",
                    unique = true),
            @Index(name = "idx_dbs_tenant_date", columnList = "tenant_id, business_date"),
            @Index(name = "idx_dbs_tenant_acct", columnList = "tenant_id, account_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class DailyBalanceSnapshot extends BaseEntity {

    /**
     * The deposit account this snapshot belongs to.
     * Per Finacle ACCT_BAL_HIST: FK to account master for referential integrity.
     * Stored as raw ID (not @ManyToOne) for batch insert performance — the EOD step
     * inserts thousands of snapshots per run and JPA relationship loading would cause
     * N+1 queries. The unique index on (tenant_id, account_id, business_date) provides
     * application-level integrity, and the account_id references deposit_accounts.id.
     *
     * CBS Design Decision: Denormalized for batch performance. The accountNumber and
     * branchId fields are also denormalized to avoid joins in reporting queries.
     * Per Finacle/Temenos: balance history tables prioritize read/write throughput
     * over normalized relational design because they are high-volume, append-only.
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Account number denormalized for efficient reporting */
    @Column(name = "account_number", nullable = false, length = 40)
    private String accountNumber;

    /** Branch ID for branch-level balance reporting (denormalized from deposit_accounts) */
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    /** CBS business date this snapshot was captured on */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Closing ledger balance at EOD */
    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance = BigDecimal.ZERO;

    /** Available balance at EOD (ledger - holds - uncleared) */
    @Column(name = "available_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal availableBalance = BigDecimal.ZERO;

    /** Hold/lien amount at EOD */
    @Column(name = "hold_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal holdAmount = BigDecimal.ZERO;

    /**
     * Account type per Finacle PDDEF ACCT_TYPE / Temenos ACCOUNT CATEGORY.
     * Enum-backed for type safety — prevents data corruption from typos that would
     * silently break interest calculation and regulatory reporting queries.
     * Denormalized from DepositAccount for reporting efficiency (avoids join).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private DepositAccountType accountType;
}

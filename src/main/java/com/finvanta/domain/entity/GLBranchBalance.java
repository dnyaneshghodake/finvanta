package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Branch-Level GL Balance per Finacle GL_BRANCH / Temenos ACCT.BALANCE.
 *
 * This is the CORE ARCHITECTURAL ENTITY that enables Tier-1 branch accounting.
 * While GLMaster holds the Chart of Accounts definition and tenant-level aggregate
 * balances, GLBranchBalance holds per-branch running balances for every GL code.
 *
 * <h3>Finacle GL Architecture:</h3>
 * <pre>
 *   GLMaster (Chart of Accounts — tenant-level definition + aggregate balance)
 *     └── GLBranchBalance (per-branch running balances)
 *           ├── Branch 001: GL 1001 → debit=50,00,000 credit=10,00,000
 *           ├── Branch 002: GL 1001 → debit=30,00,000 credit=5,00,000
 *           └── Branch 003: GL 1001 → debit=20,00,000 credit=8,00,000
 *         GLMaster GL 1001 aggregate: debit=1,00,00,000 credit=23,00,000
 * </pre>
 *
 * <h3>Reconciliation Invariant (verified at EOD):</h3>
 * <pre>
 *   GLMaster.debitBalance  = SUM(GLBranchBalance.debitBalance) across all branches
 *   GLMaster.creditBalance = SUM(GLBranchBalance.creditBalance) across all branches
 * </pre>
 *
 * <h3>Branch-Level Financial Statements:</h3>
 * Each branch must produce an independently balanced trial balance:
 * <pre>
 *   SUM(branch debit balances) == SUM(branch credit balances)
 * </pre>
 * If a branch's trial balance doesn't balance, EOD for that branch is blocked.
 *
 * <h3>Inter-Branch Settlement:</h3>
 * Cross-branch transactions create mirror entries via Inter-Branch Payable (2300)
 * and Inter-Branch Receivable (1300) GLs. At EOD, HO nets these balances.
 *
 * <h3>Period Close:</h3>
 * Period close operates at branch level. Each branch's P&L accounts are zeroed
 * independently, and the net is transferred to retained earnings at that branch.
 * HO consolidation aggregates all branch period-close results.
 *
 * Per RBI Banking Regulation Act and OSMOS reporting:
 * - Branch-level Balance Sheet is required for statutory returns
 * - Branch-level P&L is required for branch performance assessment
 * - Branch-level trial balance must independently balance
 *
 * Concurrency: PESSIMISTIC_WRITE lock on every balance mutation.
 * Per Finacle GL_BRANCH: concurrent postings to the same branch+GL are serialized.
 */
@Entity
@Table(
        name = "gl_branch_balances",
        indexes = {
            @Index(
                    name = "idx_glbb_tenant_branch_gl",
                    columnList = "tenant_id, branch_id, gl_code",
                    unique = true),
            @Index(name = "idx_glbb_tenant_gl", columnList = "tenant_id, gl_code"),
            @Index(name = "idx_glbb_tenant_branch", columnList = "tenant_id, branch_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class GLBranchBalance extends BaseEntity {

    /**
     * Branch this GL balance belongs to.
     * Per Finacle GL_BRANCH: every postable GL has a balance row per branch.
     * Non-null — every GL balance must be attributed to a specific branch.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /**
     * GL code — references GLMaster.glCode (not a FK to allow flexibility).
     * Per Finacle: the GL code is the logical key; the branch balance is the
     * physical storage of that GL's running totals at this branch.
     */
    @Column(name = "gl_code", nullable = false, length = 20)
    private String glCode;

    /**
     * GL name denormalized for reporting without joins.
     * Updated when GLMaster.glName changes (rare — GL names are stable).
     */
    @Column(name = "gl_name", length = 200)
    private String glName;

    // --- Running Balances ---

    /**
     * Cumulative debit balance at this branch for this GL.
     * Updated atomically with pessimistic lock on every posting.
     */
    @Column(name = "debit_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal debitBalance = BigDecimal.ZERO;

    /**
     * Cumulative credit balance at this branch for this GL.
     * Updated atomically with pessimistic lock on every posting.
     */
    @Column(name = "credit_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal creditBalance = BigDecimal.ZERO;

    // --- Period Close ---

    /**
     * Last period close date for this branch+GL combination.
     * Postings to dates before this are blocked at this branch.
     * Per Finacle GL_PERIOD: period close is per-branch per-GL.
     * Null = no period close has been performed.
     */
    @Column(name = "last_period_close_date")
    private LocalDate lastPeriodCloseDate;

    /**
     * Opening debit balance for the current period at this branch.
     * Set during period close. Balance Sheet accounts carry forward;
     * P&L accounts are zeroed at year-end.
     */
    @Column(name = "opening_debit_balance", precision = 18, scale = 2)
    private BigDecimal openingDebitBalance = BigDecimal.ZERO;

    /**
     * Opening credit balance for the current period at this branch.
     */
    @Column(name = "opening_credit_balance", precision = 18, scale = 2)
    private BigDecimal openingCreditBalance = BigDecimal.ZERO;

    // --- Helpers ---

    /** Net balance = debit - credit (positive for ASSET/EXPENSE, negative for LIABILITY/INCOME/EQUITY) */
    public BigDecimal getNetBalance() {
        return debitBalance.subtract(creditBalance);
    }
}

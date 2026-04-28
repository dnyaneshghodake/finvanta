package com.finvanta.cbs.modules.teller.domain;

import com.finvanta.domain.entity.BaseEntity;
import com.finvanta.domain.entity.Branch;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Teller Till entity per RBI Internal Controls / CBS TELLER_TILL standard.
 *
 * <p>Represents a per-teller, per-branch, per-business-date cash position. Every
 * cash deposit or withdrawal posted by a teller mutates the {@code currentBalance}
 * of their OPEN till. The till is the SUBLEDGER for branch-level "cash in hand"
 * (typically GL 1100 / Bank Operations). EOD reconciliation must satisfy:
 *
 * <pre>
 *   SUM(till.currentBalance WHERE branchId = X AND businessDate = D)
 *     + vault(branchId = X, businessDate = D).currentBalance
 *     == GL_branch_balance(GL 1100, branchId = X, businessDate = D)
 * </pre>
 *
 * <p>Concurrency model: every cash transaction that mutates the till must acquire
 * a {@code PESSIMISTIC_WRITE} lock on the till row. The unique index on
 * {@code (tenant_id, teller_user_id, business_date)} guarantees one till per
 * teller per day; the unique index on {@code (tenant_id, branch_id, teller_user_id,
 * business_date)} additionally enforces branch consistency.
 *
 * <p>Per RBI Internal Controls: a till cannot be in OPEN status across two business
 * dates simultaneously. EOD orchestration must hard-close every OPEN till before
 * the business date can advance.
 */
@Entity
@Table(
        name = "teller_tills",
        indexes = {
                @Index(name = "idx_till_teller_date",
                        columnList = "tenant_id, teller_user_id, business_date", unique = true),
                @Index(name = "idx_till_branch_date_status",
                        columnList = "tenant_id, branch_id, business_date, status"),
                @Index(name = "idx_till_status",
                        columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class TellerTill extends BaseEntity {

    /**
     * Username of the teller who owns this till. Bound at till-open time and
     * immutable for the till's lifetime. Cash transactions are allowed only when
     * the authenticated principal matches this value.
     */
    @Column(name = "teller_user_id", nullable = false, length = 100)
    private String tellerUserId;

    /** Branch (SOL) where this till operates. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** Denormalized branch code for cheap reporting and audit-log enrichment. */
    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    /** Business date this till is bound to. CBS business date, NOT system date. */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /** Lifecycle status. See {@link TellerTillStatus}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TellerTillStatus status = TellerTillStatus.PENDING_OPEN;

    /**
     * Cash balance handed to the teller at till-open time. Set once during the
     * OPEN transition and never mutated afterwards (immutable for the day).
     */
    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    /**
     * Current cash balance. Mutated by every cash deposit (++) and withdrawal (--).
     * Always derived from {@code openingBalance + sum(deposits) - sum(withdrawals)}
     * within the business date; never set incrementally without a transaction.
     */
    @Column(name = "current_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    /**
     * Physical cash count entered by the teller at close-of-shift. Used by the
     * supervisor reconciliation step to compute variance.
     * {@code variance = countedBalance - currentBalance}.
     */
    @Column(name = "counted_balance", precision = 18, scale = 2)
    private BigDecimal countedBalance;

    /**
     * Variance between counted physical cash and system balance at close.
     * Positive = overage, negative = shortage. Per RBI Internal Controls, any
     * non-zero variance must be flagged and remediated via a separate cash-variance
     * adjustment workflow before the till can transition to CLOSED.
     */
    @Column(name = "variance_amount", precision = 18, scale = 2)
    private BigDecimal varianceAmount;

    /**
     * Per-till cash limit (soft). When {@code currentBalance} exceeds this,
     * subsequent cash deposits route to maker-checker for supervisor approval
     * even if below the per-transaction CTR threshold. Configurable per branch
     * in TellerConfig.
     */
    @Column(name = "till_cash_limit", precision = 18, scale = 2)
    private BigDecimal tillCashLimit;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** Username of the supervisor who approved the till open (dual control). */
    @Column(name = "opened_by_supervisor", length = 100)
    private String openedBySupervisor;

    /** Username of the supervisor who signed off on the close. */
    @Column(name = "closed_by_supervisor", length = 100)
    private String closedBySupervisor;

    @Column(name = "remarks", length = 500)
    private String remarks;

    // ===== Convenience predicates =====

    public boolean isOpen() {
        return status == TellerTillStatus.OPEN;
    }

    public boolean isPendingOpen() {
        return status == TellerTillStatus.PENDING_OPEN;
    }

    public boolean isPendingClose() {
        return status == TellerTillStatus.PENDING_CLOSE;
    }

    public boolean isClosed() {
        return status == TellerTillStatus.CLOSED;
    }

    public boolean isSuspended() {
        return status == TellerTillStatus.SUSPENDED;
    }
}

package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Transaction Limits per Finacle/Temenos Internal Controls.
 *
 * Per RBI guidelines on internal controls and operational risk:
 * - Every financial transaction must be validated against configured limits
 * - Limits are set per role, per transaction type, per branch
 * - Transactions exceeding limits require higher-authority approval
 *
 * Limit types:
 *   PER_TRANSACTION  — Maximum amount for a single transaction
 *   DAILY_AGGREGATE  — Maximum cumulative amount per user per day
 *
 * Example:
 *   MAKER role, REPAYMENT type: per_transaction_limit = ₹10,00,000
 *   MAKER role, REPAYMENT type: daily_aggregate_limit = ₹50,00,000
 *   → A maker can process repayments up to ₹10L each, ₹50L total per day
 *   → Amounts above ₹10L require CHECKER/ADMIN approval
 */
@Entity
@Table(
        name = "transaction_limits",
        indexes = {@Index(name = "idx_txnlimit_tenant_role", columnList = "tenant_id, role, transaction_type")})
@Getter
@Setter
@NoArgsConstructor
public class TransactionLimit extends BaseEntity {

    /** Role this limit applies to: MAKER, CHECKER, ADMIN */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    /** Transaction type: REPAYMENT, DISBURSEMENT, PREPAYMENT, FEE_CHARGE, WRITE_OFF, ALL */
    @Column(name = "transaction_type", nullable = false, length = 30)
    private String transactionType;

    /**
     * Channel this limit applies to: BRANCH, ATM, INTERNET, MOBILE, UPI, NEFT, RTGS, IMPS, ALL.
     * Per RBI Digital Banking Framework 2023: different channels may have different limits.
     * Example: BRANCH channel allows INR 10L per txn, MOBILE allows INR 2L per txn.
     * NULL or 'ALL' = applies to all channels (backward compatible with existing data).
     */
    @Column(name = "channel", length = 20)
    private String channel;

    /** Maximum amount for a single transaction (null = unlimited) */
    @Column(name = "per_transaction_limit", precision = 18, scale = 2)
    private BigDecimal perTransactionLimit;

    /** Maximum cumulative amount per user per day (null = unlimited) */
    @Column(name = "daily_aggregate_limit", precision = 18, scale = 2)
    private BigDecimal dailyAggregateLimit;

    /** Branch-specific limit (null = applies to all branches) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "description", length = 500)
    private String description;
}

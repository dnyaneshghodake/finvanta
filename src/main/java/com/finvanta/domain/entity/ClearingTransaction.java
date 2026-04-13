package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ClearingDirection;
import com.finvanta.domain.enums.ClearingStatus;
import com.finvanta.domain.enums.PaymentRail;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Clearing Transaction per Finacle CLG_MASTER / Temenos CLEARING.TRANSACTION.
 *
 * Records every inbound and outbound payment clearing transaction across all
 * RBI-regulated payment rails (NEFT/RTGS/IMPS/UPI) with full lifecycle tracking.
 *
 * OUTWARD Flow (Customer → Other Bank):
 *   1. INITIATED: Request received from customer/channel
 *   2. VALIDATED: Balance, limits, IFSC, AML checks passed
 *   3. SUSPENSE_POSTED: DR Customer Account / CR {Rail}_OUTWARD_SUSPENSE
 *   4. SENT_TO_NETWORK: Submitted to RBI/NPCI payment network
 *   5. SETTLED: RBI/NPCI confirms settlement
 *   6. COMPLETED: DR {Rail}_OUTWARD_SUSPENSE / CR RBI_SETTLEMENT (1400)
 *
 * INWARD Flow (Other Bank → Customer):
 *   1. RECEIVED: Incoming payment from RBI/NPCI
 *   2. VALIDATED: Beneficiary account verified
 *   3. SUSPENSE_POSTED: DR RBI_SETTLEMENT (1400) / CR {Rail}_INWARD_SUSPENSE
 *   4. CREDITED: DR {Rail}_INWARD_SUSPENSE / CR Customer Account
 *   5. COMPLETED: Fully processed
 *
 * Per RBI Payment Systems Act 2007:
 * - Every transaction has a Unique Transaction Reference (UTR) from RBI/NPCI
 * - External reference (from originating bank) must be unique per tenant
 * - Suspense GLs must reconcile to zero at EOD per rail
 * - TAT (Turnaround Time) tracked from initiated to completed
 * - Maker-checker required for high-value outward payments
 */
@Entity
@Table(
        name = "clearing_transactions",
        indexes = {
            @Index(name = "idx_clrg_tenant_extref", columnList = "tenant_id, external_ref_no", unique = true),
            @Index(name = "idx_clrg_tenant_branch_status", columnList = "tenant_id, branch_id, status"),
            @Index(name = "idx_clrg_tenant_rail_status", columnList = "tenant_id, payment_rail, status"),
            @Index(name = "idx_clrg_tenant_date_rail", columnList = "tenant_id, value_date, payment_rail"),
            @Index(name = "idx_clrg_cycle", columnList = "clearing_cycle_id"),
            @Index(name = "idx_clrg_utr", columnList = "tenant_id, utr_number")
        })
@Getter
@Setter
@NoArgsConstructor
public class ClearingTransaction extends BaseEntity {

    // === Payment Identity ===

    /** Unique external reference from originating system (idempotency key) */
    @Column(name = "external_ref_no", length = 64, nullable = false)
    private String externalRefNo;

    /** RBI/NPCI Unique Transaction Reference — assigned by payment network */
    @Column(name = "utr_number", length = 30)
    private String utrNumber;

    /** Payment rail type per RBI Payment Systems Act */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_rail", length = 10, nullable = false)
    private PaymentRail paymentRail;

    /** Direction: INWARD (receiving) or OUTWARD (sending) */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 10, nullable = false)
    private ClearingDirection direction;

    // === Amount & Accounts ===

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    /** Our customer's account number (sender for OUTWARD, receiver for INWARD) */
    @Column(name = "customer_account_ref", length = 50, nullable = false)
    private String customerAccountRef;

    /** Counterparty bank IFSC code (validated against RBI IFSC directory) */
    @Column(name = "counterparty_ifsc", length = 11)
    private String counterpartyIfsc;

    /** Counterparty account number at the other bank */
    @Column(name = "counterparty_account", length = 50)
    private String counterpartyAccount;

    /** Counterparty name (beneficiary for OUTWARD, remitter for INWARD) */
    @Column(name = "counterparty_name", length = 200)
    private String counterpartyName;

    /** Payment narration/purpose */
    @Column(name = "narration", length = 500)
    private String narration;

    // === Branch Attribution ===

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "branch_code", length = 20, nullable = false)
    private String branchCode;

    // === Lifecycle State Machine ===

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 25, nullable = false)
    private ClearingStatus status;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "sent_to_network_at")
    private LocalDateTime sentToNetworkAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // === GL Journal References ===

    /** Journal ID for the suspense posting (DR Customer / CR Suspense or vice versa) */
    @Column(name = "suspense_journal_id")
    private Long suspenseJournalId;

    /** Journal ID for the settlement posting (DR Suspense / CR RBI Settlement or vice versa) */
    @Column(name = "settlement_journal_id")
    private Long settlementJournalId;

    /** Journal ID for the customer credit posting (INWARD only) */
    @Column(name = "credit_journal_id")
    private Long creditJournalId;

    /** Journal ID for reversal posting (if REVERSED/RETURNED) */
    @Column(name = "reversal_journal_id")
    private Long reversalJournalId;

    // === Clearing Cycle (NEFT batch netting) ===

    /** FK to ClearingCycle — null for real-time rails (RTGS/IMPS/UPI) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "clearing_cycle_id")
    private ClearingCycle clearingCycle;

    /** FK to SettlementBatch — assigned when settlement is confirmed */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_batch_id")
    private SettlementBatch settlementBatch;

    // === Failure/Reversal ===

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    // === Maker-Checker (high-value outward payments) ===

    @Column(name = "maker_id", length = 100)
    private String makerId;

    @Column(name = "checker_id", length = 100)
    private String checkerId;

    @Column(name = "checker_approved_at")
    private LocalDateTime checkerApprovedAt;

    // === Audit Hash ===

    /** SHA-256 hash for tamper detection per RBI IT Governance */
    @Column(name = "audit_hash", length = 64)
    private String auditHash;

    // === Helpers ===

    /** Whether this transaction is in a terminal state */
    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    /** Whether suspense GL is still active (needs clearing) */
    public boolean isSuspenseActive() {
        return status != null && status.isSuspenseActive();
    }
}

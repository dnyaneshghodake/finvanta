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
 * CBS Teller Cash Movement per RBI Internal Controls / CBS CASH_MOVE standard.
 *
 * <p>Records every cash transfer between a teller till and the branch vault.
 * Two movement types:
 * <ul>
 *   <li><b>BUY:</b> teller buys cash FROM the vault (vault→till). The vault
 *       balance decrements, the till balance increments. Used when the teller
 *       needs more cash to service withdrawals.</li>
 *   <li><b>SELL:</b> teller sells cash TO the vault (till→vault). The till
 *       balance decrements, the vault balance increments. Used when the till
 *       exceeds the soft limit or at end-of-shift.</li>
 * </ul>
 *
 * <p>Per RBI Internal Controls: vault access requires dual control. The
 * {@code requestedBy} is the teller (maker) and the {@code approvedBy} is the
 * vault custodian (checker). A movement in PENDING status has been requested
 * but not yet approved — the vault custodian must physically verify the cash
 * and approve before the balances move.
 *
 * <p>Each movement has an associated {@code movementRef} (format:
 * {@code VMOV/{branchCode}/{YYYYMMDD}/{seq}}) that links to
 * {@link CashDenomination} rows carrying the per-denomination breakdown of
 * the moved cash. The denomination rows use {@code direction = 'IN'} for
 * vault-buy (cash entering the till) and {@code direction = 'OUT'} for
 * vault-sell (cash leaving the till).
 *
 * <p>This entity does NOT touch the GL. Vault↔till movements are internal
 * cash-management operations that redistribute cash within the branch's
 * GL BANK_OPERATIONS balance — the GL total is unchanged. The EOD
 * reconciliation invariant (SUM tills + vault == GL) holds because every
 * movement decrements one side and increments the other by the same amount.
 */
@Entity
@Table(
        name = "teller_cash_movements",
        indexes = {
                @Index(name = "uq_cashmov_ref",
                        columnList = "tenant_id, movement_ref", unique = true),
                @Index(name = "idx_cashmov_till_date",
                        columnList = "tenant_id, till_id, business_date"),
                @Index(name = "idx_cashmov_vault_date",
                        columnList = "tenant_id, vault_id, business_date"),
                @Index(name = "idx_cashmov_status",
                        columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class TellerCashMovement extends BaseEntity {

    /**
     * Permanent movement reference. Format:
     * {@code VMOV/{branchCode}/{YYYYMMDD}/{seq}}.
     * Links to {@link CashDenomination} rows via
     * {@code CashDenomination.transactionRef}.
     */
    @Column(name = "movement_ref", nullable = false, length = 60)
    private String movementRef;

    /** BUY (vault→till) or SELL (till→vault). */
    @Column(name = "movement_type", nullable = false, length = 10)
    private String movementType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    /** The till involved in the movement. */
    @Column(name = "till_id", nullable = false)
    private Long tillId;

    /** The vault involved in the movement. */
    @Column(name = "vault_id", nullable = false)
    private Long vaultId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /**
     * Lifecycle: PENDING (requested by teller, awaiting vault custodian
     * approval), APPROVED (custodian verified cash + approved, balances
     * moved), REJECTED (custodian rejected, no balance change).
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    /** Teller who requested the movement (maker). */
    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** Vault custodian who approved/rejected (checker). */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "remarks", length = 500)
    private String remarks;

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    public boolean isBuy() {
        return "BUY".equals(movementType);
    }

    public boolean isSell() {
        return "SELL".equals(movementType);
    }
}

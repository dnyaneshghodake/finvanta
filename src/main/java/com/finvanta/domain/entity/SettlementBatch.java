package com.finvanta.domain.entity;

import com.finvanta.domain.enums.PaymentRail;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Settlement Batch per Finacle SETTLEMENT_MASTER / RBI Settlement Framework.
 *
 * Represents a confirmed settlement from RBI/NPCI for a group of clearing
 * transactions. Each settlement batch carries the RBI settlement reference
 * which is the authoritative proof of fund movement between banks.
 *
 * For NEFT: one settlement batch per clearing cycle (half-hourly net settlement).
 * For RTGS/IMPS/UPI: one settlement batch per individual transaction (gross).
 *
 * Lifecycle:
 *   PENDING → settlement submitted to RBI
 *   CONFIRMED → RBI confirms settlement (funds moved)
 *   RECONCILED → CBS confirms all clearing transactions are completed
 *   FAILED → settlement rejected by RBI
 *
 * Per RBI: the rbi_settlement_ref is the legal proof of inter-bank settlement.
 * Must be stored immutably for minimum 8 years per RBI data retention norms.
 */
@Entity
@Table(
        name = "settlement_batches",
        indexes = {
            @Index(name = "idx_stlbatch_tenant_date_rail", columnList = "tenant_id, settlement_date, rail_type"),
            @Index(name = "idx_stlbatch_rbi_ref", columnList = "tenant_id, rbi_settlement_ref"),
            @Index(name = "idx_stlbatch_status", columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class SettlementBatch extends BaseEntity {

    /** Payment rail this settlement belongs to */
    @Enumerated(EnumType.STRING)
    @Column(name = "rail_type", length = 10, nullable = false)
    private PaymentRail railType;

    /** CBS business date of settlement */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** Total net settlement amount (positive = bank pays RBI, negative = bank receives) */
    @Column(name = "total_net_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal totalNetAmount = BigDecimal.ZERO;

    /** Number of clearing transactions in this batch */
    @Column(name = "transaction_count", nullable = false)
    private int transactionCount = 0;

    /** RBI/NPCI settlement reference — legal proof of inter-bank fund movement */
    @Column(name = "rbi_settlement_ref", length = 50)
    private String rbiSettlementRef;

    /** Settlement status: PENDING, CONFIRMED, RECONCILED, FAILED */
    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";

    /** When RBI/NPCI confirmed the settlement */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** When CBS reconciliation completed for this batch */
    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    /** Journal ID for the net settlement GL posting */
    @Column(name = "settlement_journal_id")
    private Long settlementJournalId;

    /** Failure reason if settlement was rejected by RBI */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // === Helpers ===

    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isConfirmed() { return "CONFIRMED".equals(status); }
    public boolean isReconciled() { return "RECONCILED".equals(status); }
}

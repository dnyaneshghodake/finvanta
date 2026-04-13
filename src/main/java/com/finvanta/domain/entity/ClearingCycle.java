package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ClearingCycleStatus;
import com.finvanta.domain.enums.PaymentRail;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Clearing Cycle per Finacle CLG_CYCLE / RBI NEFT Settlement Windows.
 *
 * Per RBI: NEFT operates in half-hourly batch settlement windows.
 * Each window is a "clearing cycle" that aggregates transactions and
 * calculates the net obligation between banks.
 *
 * For real-time rails (RTGS/IMPS/UPI), each transaction is its own
 * implicit cycle (no aggregation needed). However, a cycle record is
 * still created for settlement tracking and reconciliation.
 *
 * Lifecycle:
 *   OPEN → transactions accumulate during the window
 *   CLOSED → window ends, net obligation calculated, no more transactions
 *   SUBMITTED → net obligation submitted to RBI/NPCI for settlement
 *   SETTLED → RBI/NPCI confirms settlement
 *
 * Per Finacle CLG_CYCLE:
 *   net_debit = sum of all outward transactions in this cycle
 *   net_credit = sum of all inward transactions in this cycle
 *   net_obligation = net_debit - net_credit (positive = bank owes RBI)
 */
@Entity
@Table(
        name = "clearing_cycles",
        indexes = {
            @Index(name = "idx_clrcyc_tenant_rail_date", columnList = "tenant_id, rail_type, cycle_date"),
            @Index(name = "idx_clrcyc_status", columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class ClearingCycle extends BaseEntity {

    /** Payment rail this cycle belongs to */
    @Enumerated(EnumType.STRING)
    @Column(name = "rail_type", length = 10, nullable = false)
    private PaymentRail railType;

    /** Business date this cycle belongs to */
    @Column(name = "cycle_date", nullable = false)
    private LocalDate cycleDate;

    /** Cycle sequence number within the day (e.g., NEFT cycle 1, 2, 3...) */
    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    /** When this cycle window opened */
    @Column(name = "cycle_start_time", nullable = false)
    private LocalDateTime cycleStartTime;

    /** When this cycle window closed (null if still OPEN) */
    @Column(name = "cycle_end_time")
    private LocalDateTime cycleEndTime;

    /** Total outward amount in this cycle */
    @Column(name = "net_debit", precision = 18, scale = 2, nullable = false)
    private BigDecimal netDebit = BigDecimal.ZERO;

    /** Total inward amount in this cycle */
    @Column(name = "net_credit", precision = 18, scale = 2, nullable = false)
    private BigDecimal netCredit = BigDecimal.ZERO;

    /** Number of transactions in this cycle */
    @Column(name = "transaction_count", nullable = false)
    private int transactionCount = 0;

    /** Cycle status per ClearingCycleStatus state machine */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ClearingCycleStatus status = ClearingCycleStatus.OPEN;

    /** RBI/NPCI settlement reference (assigned on settlement confirmation) */
    @Column(name = "settlement_reference", length = 50)
    private String settlementReference;

    // === Helpers ===

    /** Net obligation = outward - inward. Positive = bank owes RBI. */
    public BigDecimal getNetObligation() {
        return netDebit.subtract(netCredit);
    }

    public boolean isOpen() { return status == ClearingCycleStatus.OPEN; }
    public boolean isClosed() { return status == ClearingCycleStatus.CLOSED; }
    public boolean isSettled() { return status == ClearingCycleStatus.SETTLED; }

    /** Add an outward transaction amount to this cycle */
    public void addOutward(BigDecimal amount) {
        this.netDebit = this.netDebit.add(amount);
        this.transactionCount++;
    }

    /** Add an inward transaction amount to this cycle */
    public void addInward(BigDecimal amount) {
        this.netCredit = this.netCredit.add(amount);
        this.transactionCount++;
    }
}

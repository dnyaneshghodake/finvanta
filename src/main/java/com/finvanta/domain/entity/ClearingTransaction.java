package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * CBS Clearing Transaction — Payment clearing/settlement ledger per Finacle CLG_MASTER.
 *
 * Records inbound and outbound clearing transactions (NEFT/RTGS/IMPS/CHEQUE/UPI)
 * with suspense account management.
 *
 * Lifecycle:
 * 1. INITIATED: Entry created when clearing request is made
 *    GL: DR Customer / CR Clearing Suspense (GL 2400)
 * 2. PENDING: Waiting for clearing network confirmation
 * 3. CONFIRMED: Clearing network confirmed successful
 *    GL: DR Clearing Suspense / CR Settlement GL (actual receiving GL)
 * 4. SETTLED: Final settlement completed
 * 5. FAILED: Clearing failed
 *    GL: Reversal posted (DR Settlement / CR Clearing Suspense)
 * 6. REVERSED: Manual reversal after settlement
 *
 * Per RBI guidelines: All intra-day clearing must settle before EOD.
 * EOD validation checks that Clearing Suspense GL balance = 0.
 * Non-zero suspense indicates stuck transactions (flagged for investigation).
 */
@Entity
@Table(
        name = "clearing_transactions",
        indexes = {
            @Index(name = "idx_clrg_tenant_ref", columnList = "tenant_id, clearing_ref"),
            @Index(name = "idx_clrg_status", columnList = "status"),
            @Index(name = "idx_clrg_initiated_date", columnList = "initiated_date"),
            @Index(name = "idx_clrg_settlement_date", columnList = "settlement_date")
        })
@Getter
@Setter
public class ClearingTransaction extends BaseEntity {

    @Column(name = "clearing_ref", length = 50, nullable = false)
    private String clearingRef; // Unique reference for this clearing transaction

    @Column(name = "source_type", length = 20, nullable = false)
    private String sourceType; // NEFT, RTGS, IMPS, CHEQUE, UPI

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount; // Clearing amount

    @Column(name = "customer_account_ref", length = 50, nullable = false)
    private String customerAccountRef; // Account reference (sender or receiver)

    @Column(name = "counterparty_details", length = 500)
    private String counterpartyDetails; // Counterparty details (for audit trail)

    @Column(name = "status", length = 20, nullable = false)
    private String status; // INITIATED, PENDING, CONFIRMED, SETTLED, FAILED, REVERSED

    @Column(name = "initiated_date", nullable = false)
    private LocalDateTime initiatedDate; // When clearing was initiated

    @Column(name = "settlement_date")
    private LocalDateTime settlementDate; // When settlement occurred

    @Column(name = "suspense_journal_id")
    private Long suspenseJournalId; // FK to journal_entries (suspense posting)

    @Column(name = "settlement_journal_id")
    private Long settlementJournalId; // FK to journal_entries (settlement posting)

    @Column(name = "failure_reason", length = 500)
    private String failureReason; // If FAILED, reason for failure

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate; // CBS business date

    // Audit trail: tenantId, createdAt, createdBy inherited from BaseEntity
}

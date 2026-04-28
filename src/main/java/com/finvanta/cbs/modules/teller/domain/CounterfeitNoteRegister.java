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
 * CBS Counterfeit Note Register per RBI Master Direction on Counterfeit
 * Notes (FICN — Forged Indian Currency Notes).
 *
 * <p>One row per counterfeit-note batch detected at the teller counter
 * during a cash deposit. The bank:
 * <ol>
 *   <li>Refuses to credit the customer for the counterfeit portion.</li>
 *   <li>Issues a printed FICN acknowledgement receipt (register reference
 *       + branch + denomination + count) to the customer.</li>
 *   <li>Impounds the counterfeit notes pending dispatch to the nearest
 *       currency chest per RBI Master Direction.</li>
 *   <li>Files an FIR with local police if the count exceeds the
 *       {@code FIR_MANDATORY_THRESHOLD} (5 counterfeit notes per RBI).</li>
 * </ol>
 *
 * <p><b>Tier-1 invariant:</b> this entity is INSERT-ONLY. The {@code register_ref}
 * is permanent — it is printed on the customer receipt and reproduced on the
 * FIR. The DB triggers in {@code ddl-sqlserver.sql}
 * ({@code trg_ficn_no_update} / {@code trg_ficn_no_delete}) reject any
 * update/delete attempt at the storage layer; the service layer never
 * loads-then-modifies an existing row.
 *
 * <p><b>Why not on {@link CashDenomination}?</b> The denomination row already
 * carries {@code counterfeitCount} for accounting reconciliation, but the FICN
 * register has additional regulatory fields (FIR ref, currency-chest
 * dispatch tracking, depositor identifier capture) that don't belong on a
 * pure cash-flow record. Keeping them separate also lets the FICN report
 * be queried independently of the deposit audit trail.
 */
@Entity
@Table(
        name = "counterfeit_note_register",
        indexes = {
                @Index(name = "uq_ficn_register_ref",
                        columnList = "tenant_id, register_ref", unique = true),
                @Index(name = "idx_ficn_branch_date",
                        columnList = "tenant_id, branch_id, detection_date"),
                @Index(name = "idx_ficn_chest_status",
                        columnList = "tenant_id, chest_dispatch_status"),
                @Index(name = "idx_ficn_denom",
                        columnList = "tenant_id, denomination, detection_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class CounterfeitNoteRegister extends BaseEntity {

    /**
     * Permanent register reference (printed on the customer receipt and the
     * FIR). Format: {@code FICN/{branchCode}/{YYYYMMDD}/{seq}}, e.g.
     * {@code FICN/HQ001/20260401/000003}. Generated server-side via
     * {@code CbsReferenceService}.
     */
    @Column(name = "register_ref", nullable = false, length = 60)
    private String registerRef;

    /**
     * Reference to the originating cash-deposit transaction, if any.
     * Most FICN entries originate from a deposit that was rejected, so this
     * matches the rejected {@code DepositTransaction.transactionRef} or the
     * idempotency key the rejected request carried. Nullable to allow
     * out-of-counter FICN entries (e.g. cash brought back from an ATM cassette).
     */
    @Column(name = "originating_txn_ref", length = 40)
    private String originatingTxnRef;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** Denormalized branch code for cheap reporting. */
    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    /** Till that detected the counterfeit (FK to teller_tills). */
    @Column(name = "till_id", nullable = false)
    private Long tillId;

    /** Username of the teller who detected the counterfeit. */
    @Column(name = "detected_by_teller", nullable = false, length = 100)
    private String detectedByTeller;

    /** CBS business date when the counterfeit was detected. */
    @Column(name = "detection_date", nullable = false)
    private LocalDate detectionDate;

    @Column(name = "detection_timestamp", nullable = false)
    private LocalDateTime detectionTimestamp;

    /**
     * Denomination of the counterfeit notes. The register tracks one row per
     * (transaction, denomination) batch; if a single deposit had counterfeits
     * across multiple denominations (rare), a separate FICN row is written
     * for each denomination. This keeps each register entry trivially
     * matchable to a single line of the RBI quarterly counterfeit report.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "denomination", nullable = false, length = 20)
    private IndianCurrencyDenomination denomination;

    /** Number of counterfeit notes in this batch. */
    @Column(name = "counterfeit_count", nullable = false)
    private long counterfeitCount;

    /**
     * Total face value of the counterfeit batch (denomination * count).
     * Stored (not derived) so RBI reports can SUM directly without joining
     * to the enum-aware service layer.
     */
    @Column(name = "total_face_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalFaceValue;

    /**
     * Depositor identification captured at the counter. PMLA Rule 9
     * mandates capture of the tendering party's ID for any FICN incident
     * regardless of amount. Stored as a single free-form field rather than
     * structured (PAN/Aadhaar/Form60) because RBI also accepts driving
     * license, passport, etc. for FICN ID capture; the depositor may
     * legitimately have only one of these.
     */
    @Column(name = "depositor_name", length = 200)
    private String depositorName;

    @Column(name = "depositor_id_type", length = 30)
    private String depositorIdType;

    @Column(name = "depositor_id_number", length = 100)
    private String depositorIdNumber;

    @Column(name = "depositor_mobile", length = 20)
    private String depositorMobile;

    /**
     * FIR (First Information Report) reference. Mandatory per RBI when the
     * counterfeit count in a single transaction exceeds 5 notes. Captured
     * post-detection by the supervisor; nullable on initial insert.
     */
    @Column(name = "fir_reference", length = 100)
    private String firReference;

    @Column(name = "fir_filed_date")
    private LocalDate firFiledDate;

    @Column(name = "fir_filed_by", length = 100)
    private String firFiledBy;

    /**
     * Currency-chest dispatch status. Counterfeit notes must be remitted to
     * the nearest currency chest per RBI Master Direction; this lifecycle
     * tracks whether they're still impounded at the branch ("PENDING"),
     * dispatched ("DISPATCHED"), or chest-acknowledged ("REMITTED").
     */
    @Column(name = "chest_dispatch_status", nullable = false, length = 20)
    private String chestDispatchStatus = "PENDING";

    @Column(name = "chest_dispatch_ref", length = 60)
    private String chestDispatchRef;

    @Column(name = "chest_dispatch_date")
    private LocalDate chestDispatchDate;

    /** Free-form remarks captured by the teller / supervisor. */
    @Column(name = "remarks", length = 1000)
    private String remarks;

    /**
     * RBI mandate threshold for filing an FIR. Per RBI Master Direction:
     * if 5 or more counterfeit notes are detected in a single transaction,
     * the bank MUST file an FIR with local police. Below this, an FIR is
     * optional but the chest dispatch is still required.
     */
    public static final long FIR_MANDATORY_THRESHOLD = 5L;

    public boolean requiresFir() {
        return counterfeitCount >= FIR_MANDATORY_THRESHOLD;
    }
}

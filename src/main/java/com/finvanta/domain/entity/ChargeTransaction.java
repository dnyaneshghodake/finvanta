package com.finvanta.domain.entity;

import com.finvanta.domain.enums.ChargeEventType;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Charge Transaction per Finacle CHG_DETAIL / Temenos FT.CHARGE.
 *
 * Immutable audit record of every charge levied on a customer account.
 * Per RBI IT Governance Direction 2023: charge records must be retained
 * for minimum 8 years and must be reproducible for dispute resolution.
 *
 * Each ChargeTransaction links to:
 * - The ChargeDefinition that determined the fee
 * - The source transaction that triggered the charge (via sourceRef)
 * - The GL journal entry that posted the fee + GST
 * - The customer account that was debited
 *
 * GL Flow recorded:
 *   DR Customer Account (2010/2020) — totalDebit (fee + CGST + SGST)
 *   CR Fee Income (4002) — baseFee
 *   CR CGST Payable (2200) — cgstAmount
 *   CR SGST Payable (2201) — sgstAmount
 */
@Entity
@Table(
        name = "charge_transactions",
        indexes = {
            @Index(name = "idx_chgtxn_tenant_acct_date",
                    columnList = "tenant_id, account_number, value_date"),
            @Index(name = "idx_chgtxn_tenant_event",
                    columnList = "tenant_id, event_type"),
            @Index(name = "idx_chgtxn_tenant_source",
                    columnList = "tenant_id, source_module, source_ref"),
            @Index(name = "idx_chgtxn_journal",
                    columnList = "journal_entry_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ChargeTransaction extends BaseEntity {

    /** Chargeable event that triggered this charge */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private ChargeEventType eventType;

    /** FK to the ChargeDefinition used for calculation */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charge_definition_id", nullable = false)
    private ChargeDefinition chargeDefinition;

    /** Customer account number debited for this charge */
    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    /** Branch where the charge was levied */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    /** CBS business date of the charge */
    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    /** Source module that triggered the charge (CLEARING, DEPOSIT, LOAN) */
    @Column(name = "source_module", nullable = false, length = 30)
    private String sourceModule;

    /** Source transaction reference (extRef for clearing, txnRef for CASA) */
    @Column(name = "source_ref", nullable = false, length = 64)
    private String sourceRef;

    /** Transaction amount that the charge was calculated on */
    @Column(name = "transaction_amount", precision = 18, scale = 2,
            nullable = false)
    private BigDecimal transactionAmount;

    /** Base fee amount (before GST) */
    @Column(name = "base_fee", precision = 18, scale = 2,
            nullable = false)
    private BigDecimal baseFee;

    /** CGST amount (9% of base fee, per GST Act 2017) */
    @Column(name = "cgst_amount", precision = 18, scale = 2,
            nullable = false)
    private BigDecimal cgstAmount = BigDecimal.ZERO;

    /** SGST amount (9% of base fee, per GST Act 2017) */
    @Column(name = "sgst_amount", precision = 18, scale = 2,
            nullable = false)
    private BigDecimal sgstAmount = BigDecimal.ZERO;

    /** Total amount debited from customer (baseFee + CGST + SGST) */
    @Column(name = "total_debit", precision = 18, scale = 2,
            nullable = false)
    private BigDecimal totalDebit;

    /** Whether this charge was waived */
    @Column(name = "waived", nullable = false)
    private boolean waived = false;

    /** Waiver reason (mandatory if waived = true) */
    @Column(name = "waiver_reason", length = 500)
    private String waiverReason;

    /** User who approved the waiver */
    @Column(name = "waived_by", length = 100)
    private String waivedBy;

    /** FK to the GL journal entry that posted this charge */
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    /** Voucher number from TransactionEngine */
    @Column(name = "voucher_number", length = 50)
    private String voucherNumber;

    /** When the charge was posted */
    @Column(name = "posted_at", nullable = false)
    private LocalDateTime postedAt;
}

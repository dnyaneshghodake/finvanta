package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/**
 * CBS Interest Accrual — Audit-grade per-day accrual record.
 *
 * Every interest accrual (regular and penal) is recorded here with full detail:
 * - Principal outstanding at time of accrual
 * - Interest rate applied
 * - Days counted (day-count convention: Actual/365, Actual/360, 30/360)
 * - Accrued amount
 * - Posting status and GL linkage
 *
 * Use case: Deterministic accrual replay
 * Any date range can be replayed from this table to regenerate GL postings,
 * enabling audit reconciliation, correction postings, and forensic analysis.
 *
 * Per RBI audit requirements (IT Governance Direction 2023):
 * All financial calculations must be logged and reproducible.
 * This table provides the audit trail for interest accrual decisions.
 *
 * Lifecycle:
 * 1. LoanAccountServiceImpl.applyInterestAccrual() inserts row with posted_flag=false
 * 2. GL posting created by TransactionEngine
 * 3. Row updated with posted_flag=true, journal_entry_id, transaction_ref
 */
@Entity
@Table(
        name = "interest_accruals",
        indexes = {
            @Index(name = "idx_intaccrual_tenant_account_date", columnList = "tenant_id, account_id, accrual_date"),
            @Index(name = "idx_intaccrual_tenant_account_type", columnList = "tenant_id, account_id, accrual_type"),
            @Index(name = "idx_intaccrual_posted_flag", columnList = "posted_flag"),
            @Index(name = "idx_intaccrual_business_date", columnList = "business_date")
        })
@Getter
@Setter
public class InterestAccrual extends BaseEntity {

    @Column(name = "account_id", nullable = false)
    private Long accountId; // FK to loan_accounts

    @Column(name = "accrual_date", nullable = false)
    private LocalDate accrualDate; // Date when interest was accrued

    @Column(name = "principal_base", precision = 18, scale = 2, nullable = false)
    private BigDecimal principalBase; // Outstanding principal at accrual time

    @Column(name = "rate_applied", precision = 8, scale = 4, nullable = false)
    private BigDecimal rateApplied; // Interest rate (% p.a.)

    @Column(name = "days_count", nullable = false)
    private Integer daysCount; // Number of days in accrual period (1 for daily)

    @Column(name = "accrued_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal accruedAmount; // Interest amount accrued

    @Column(name = "accrual_type", length = 20, nullable = false)
    private String accrualType; // REGULAR or PENAL

    @Column(name = "posted_flag", nullable = false)
    private Boolean postedFlag; // false until GL posting succeeds

    @Column(name = "posting_date")
    private LocalDate postingDate; // Date GL posting was made (null until posted)

    @Column(name = "journal_entry_id")
    private Long journalEntryId; // FK to journal_entries (null until posted)

    @Column(name = "transaction_ref", length = 50)
    private String transactionRef; // Transaction reference from GL posting

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate; // CBS business date of posting

    // Audit trail: who accrued, when
    // tenantId, createdAt, createdBy inherited from BaseEntity
}

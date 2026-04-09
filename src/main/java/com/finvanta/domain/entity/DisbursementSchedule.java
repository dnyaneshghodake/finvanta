package com.finvanta.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Disbursement Schedule per Finacle DISB_MASTER / Temenos AA.DISBURSEMENT.ARRANGEMENT.
 *
 * For multi-tranche products (Home Loan, Construction Finance, Education Loan),
 * the disbursement is planned in stages linked to milestones or conditions.
 *
 * Example - Home Loan (INR 1,00,00,000 sanctioned):
 *   Tranche 1: Foundation complete    -> 15% -> INR 15,00,000
 *   Tranche 2: Structure complete     -> 30% -> INR 30,00,000
 *   Tranche 3: Finishing/plastering   -> 35% -> INR 35,00,000
 *   Tranche 4: Completion certificate -> 20% -> INR 20,00,000
 *
 * Each tranche requires:
 *   - Milestone/condition to be met (verified by checker)
 *   - Supporting documents (engineer certificate, photos, etc.)
 *   - Maker-checker approval before disbursement
 *
 * Tranche lifecycle: PLANNED -> CONDITION_MET -> APPROVED -> DISBURSED / CANCELLED
 *
 * Per RBI Housing Finance guidelines:
 *   - Disbursement must be linked to construction progress
 *   - Bank must verify construction stage before each tranche
 *   - Interest is charged only on the disbursed amount
 */
@Entity
@Table(
        name = "disbursement_schedules",
        indexes = {
            @Index(name = "idx_disbsched_account", columnList = "tenant_id, loan_account_id"),
            @Index(name = "idx_disbsched_status", columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class DisbursementSchedule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    /** Tranche sequence: 1, 2, 3... */
    @Column(name = "tranche_number", nullable = false)
    private int trancheNumber;

    /** Disbursement amount for this tranche */
    @Column(name = "tranche_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal trancheAmount;

    /** Percentage of sanctioned amount (e.g., 15.00 for 15%) */
    @Column(name = "tranche_percentage", precision = 8, scale = 2)
    private BigDecimal tranchePercentage;

    /** Milestone/condition that must be met before disbursement */
    @Column(name = "milestone_description", nullable = false, length = 500)
    private String milestoneDescription;

    /** Expected date for this tranche (estimated) */
    @Column(name = "expected_date")
    private LocalDate expectedDate;

    /** Actual disbursement date (set when tranche is disbursed) */
    @Column(name = "actual_date")
    private LocalDate actualDate;

    /**
     * Tranche status lifecycle:
     *   PLANNED -> CONDITION_MET -> APPROVED -> DISBURSED
     *   PLANNED -> CANCELLED (if tranche is not needed)
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PLANNED";

    /** Who verified the milestone condition was met */
    @Column(name = "condition_verified_by", length = 100)
    private String conditionVerifiedBy;

    @Column(name = "condition_verified_date")
    private LocalDate conditionVerifiedDate;

    /** Who approved this tranche for disbursement */
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_date")
    private LocalDate approvedDate;

    /** Transaction reference from the actual disbursement posting */
    @Column(name = "transaction_ref", length = 40)
    private String transactionRef;

    /** Voucher number from the disbursement posting */
    @Column(name = "voucher_number", length = 40)
    private String voucherNumber;

    @Column(name = "remarks", length = 500)
    private String remarks;

    /** Beneficiary for this tranche (e.g., builder name for home loan) */
    @Column(name = "beneficiary_name", length = 200)
    private String beneficiaryName;

    /** Beneficiary account number (for direct credit to builder/vendor) */
    @Column(name = "beneficiary_account", length = 40)
    private String beneficiaryAccount;

    public boolean isPlanned() {
        return "PLANNED".equals(status);
    }

    public boolean isConditionMet() {
        return "CONDITION_MET".equals(status);
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    public boolean isDisbursed() {
        return "DISBURSED".equals(status);
    }

    public boolean isCancelled() {
        return "CANCELLED".equals(status);
    }
}

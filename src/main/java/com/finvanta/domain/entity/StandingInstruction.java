package com.finvanta.domain.entity;

import com.finvanta.domain.enums.SIFrequency;
import com.finvanta.domain.enums.SIStatus;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Standing Instruction Entity per Finacle SI_MASTER / Temenos STANDING.ORDER.
 *
 * A Standing Instruction is a pre-authorized recurring payment mandate registered
 * by the customer on their CASA account. The CBS EOD batch engine automatically
 * executes due SIs on the business date without manual intervention.
 *
 * <b>Primary Use Case: Loan EMI Auto-Debit</b>
 *
 * Per Tier-1 CBS (Finacle/Temenos/BNP) and RBI Payment Systems Act 2007:
 *   - SI is registered at loan disbursement (auto-created for LOAN_EMI type)
 *   - Source: borrower's CASA account (LoanAccount.disbursementAccountNumber)
 *   - Amount: dynamic from LoanAccount.emiAmount (changes after restructuring)
 *   - Frequency: MONTHLY (aligned with LoanAccount.repaymentFrequency)
 *   - Execution day: derived from LoanAccount.nextEmiDate day-of-month
 *   - End date: LoanAccount.maturityDate (auto-expires when loan closes)
 *
 * Destination Types:
 *   LOAN_EMI           - Auto-debit for loan EMI (amount resolved dynamically)
 *   INTERNAL_TRANSFER  - Recurring transfer to another CASA within the bank
 *   RD_CONTRIBUTION    - Recurring Deposit monthly contribution
 *   SIP                - Systematic Investment Plan debit
 *   UTILITY            - Utility bill payment (electricity, insurance, etc.)
 *
 * EOD Execution Order (per Finacle SI_MASTER priority):
 *   1. LOAN_EMI (highest priority — bank's own recovery)
 *   2. RD_CONTRIBUTION
 *   3. SIP
 *   4. INTERNAL_TRANSFER
 *   5. UTILITY (lowest priority)
 *
 * Financial Safety:
 *   - CASA debit + loan repayment are ATOMIC (same @Transactional boundary)
 *   - Idempotency key per execution date prevents double-debit on EOD retry
 *   - Insufficient balance → SI marked FAILED for that date, retried next business day
 *   - Maximum 3 retries per cycle (configurable), then marked SKIPPED until next cycle
 *   - Minimum balance check on CASA is enforced (SI cannot breach min balance)
 *   - FROZEN/DORMANT CASA → SI execution skipped with reason logged
 *
 * GL Entries for Loan EMI Auto-Debit (compound cross-module posting):
 *   Leg 1 (CASA side):  DR Customer Deposits (2010/2020) / CR Bank Ops (1100)
 *   Leg 2 (Loan side):  DR Bank Ops (1100) / CR Loan Asset (1001) + Interest Receivable (1002)
 *   Bank Ops GL (1100) acts as the settlement bridge between CASA and Loan modules.
 */
@Entity
@Table(
        name = "standing_instructions",
        indexes = {
            @Index(name = "idx_si_tenant_ref", columnList = "tenant_id, si_reference", unique = true),
            @Index(name = "idx_si_tenant_status_nextdate", columnList = "tenant_id, status, next_execution_date"),
            @Index(name = "idx_si_tenant_source", columnList = "tenant_id, source_account_number"),
            @Index(name = "idx_si_tenant_customer", columnList = "tenant_id, customer_id"),
            @Index(name = "idx_si_tenant_loan", columnList = "tenant_id, loan_account_number")
        })
@Getter
@Setter
@NoArgsConstructor
public class StandingInstruction extends BaseEntity {

    /** Unique SI reference: SI/branchCode/timestamp/seq */
    @Column(name = "si_reference", nullable = false, length = 40)
    private String siReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    // --- Source (CASA account to debit) ---

    /** Borrower's CASA account number — the account to be debited */
    @Column(name = "source_account_number", nullable = false, length = 40)
    private String sourceAccountNumber;

    // --- Destination ---

    /** LOAN_EMI, INTERNAL_TRANSFER, RD_CONTRIBUTION, SIP, UTILITY */
    @Column(name = "destination_type", nullable = false, length = 30)
    private String destinationType;

    /**
     * Target account number:
     *   LOAN_EMI → loan account number (e.g., LN001HQ001...)
     *   INTERNAL_TRANSFER → target CASA account number
     *   Others → beneficiary reference
     */
    @Column(name = "destination_account_number", length = 40)
    private String destinationAccountNumber;

    /** For LOAN_EMI: the linked loan account number for quick lookup */
    @Column(name = "loan_account_number", length = 40)
    private String loanAccountNumber;

    // --- Amount ---

    /**
     * Fixed SI amount. NULL for LOAN_EMI type (resolved dynamically from LoanAccount.emiAmount).
     * Per Finacle SI_MASTER: LOAN_EMI amount is ALWAYS fetched at execution time because
     * EMI changes after restructuring, rate reset, or partial prepayment.
     * Using a stale cached amount would cause under/over-recovery — financially unsafe.
     */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    // --- Schedule ---

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private SIFrequency frequency;

    /** Day of month for execution (1-28). Per CBS: 29-31 maps to last business day of month. */
    @Column(name = "execution_day", nullable = false)
    private int executionDay;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** End date (null = perpetual until cancelled or loan closed) */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** Next scheduled execution date — computed by EOD after each execution */
    @Column(name = "next_execution_date")
    private LocalDate nextExecutionDate;

    // --- Status & Execution Tracking ---

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SIStatus status = SIStatus.PENDING_APPROVAL;

    /** Execution priority: lower number = higher priority. LOAN_EMI=1, UTILITY=5 */
    @Column(name = "priority", nullable = false)
    private int priority = 3;

    /** Maximum retry attempts per execution cycle (default 3 per Finacle SI_MASTER) */
    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    /** Retries done in current execution cycle (resets on successful execution or next cycle) */
    @Column(name = "retries_done", nullable = false)
    private int retriesDone = 0;

    @Column(name = "last_execution_date")
    private LocalDate lastExecutionDate;

    /** SUCCESS, FAILED_INSUFFICIENT_BALANCE, FAILED_ACCOUNT_FROZEN, FAILED_LOAN_CLOSED, SKIPPED */
    @Column(name = "last_execution_status", length = 50)
    private String lastExecutionStatus;

    /** Last execution failure reason (for operations dashboard) */
    @Column(name = "last_failure_reason", length = 500)
    private String lastFailureReason;

    /** Total successful executions (lifetime counter for audit) */
    @Column(name = "total_executions", nullable = false)
    private int totalExecutions = 0;

    /** Total failed executions (lifetime counter for audit) */
    @Column(name = "total_failures", nullable = false)
    private int totalFailures = 0;

    /** Last successful execution transaction reference (for cross-referencing) */
    @Column(name = "last_transaction_ref", length = 40)
    private String lastTransactionRef;

    // --- Narration ---

    /** Custom narration for CASA statement (e.g., "EMI for Home Loan LN001...") */
    @Column(name = "narration", length = 200)
    private String narration;

    // --- Helpers ---

    public boolean isLoanEmi() {
        return "LOAN_EMI".equals(destinationType);
    }

    public boolean isInternalTransfer() {
        return "INTERNAL_TRANSFER".equals(destinationType);
    }

    /** Returns true if this SI can be retried (retries not exhausted) */
    public boolean canRetry() {
        return retriesDone < maxRetries;
    }
}

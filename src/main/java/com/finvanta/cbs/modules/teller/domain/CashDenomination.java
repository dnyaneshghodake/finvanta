package com.finvanta.cbs.modules.teller.domain;

import com.finvanta.domain.entity.BaseEntity;

import jakarta.persistence.*;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Cash Denomination Detail per RBI Currency Management / CBS DENOMS standard.
 *
 * <p>Immutable child record attached to a teller cash transaction (deposit or
 * withdrawal). Captures the per-denomination breakdown so the bank can:
 * <ul>
 *   <li>Reconcile till physical cash against the system balance at EOD</li>
 *   <li>Generate currency-chest reports per RBI Currency Management Dept</li>
 *   <li>Track ₹2000 note inflows for chest remittance per RBI 2023 directive</li>
 *   <li>Audit fake/counterfeit note registers per FICN guidelines</li>
 * </ul>
 *
 * <p>Tier-1 invariant: this entity is INSERT-ONLY. Once a transaction is
 * committed, its denomination rows are immutable. Reversals create new
 * denomination rows on the contra transaction; they do NOT update or delete
 * the original rows. The service layer must never call save() on an existing
 * denomination row -- this is enforced by ArchUnit.
 *
 * <p>The link is by {@code transactionRef} (string) rather than
 * {@code DepositTransaction} FK because the same denomination structure is
 * reused for till-to-vault movements which do not have a {@code DepositTransaction}.
 */
@Entity
@Table(
        name = "cash_denominations",
        indexes = {
                @Index(name = "idx_denom_txn_ref",
                        columnList = "tenant_id, transaction_ref"),
                @Index(name = "idx_denom_till_date",
                        columnList = "tenant_id, till_id, value_date"),
                @Index(name = "idx_denom_denom",
                        columnList = "tenant_id, denomination")
        })
@Getter
@Setter
@NoArgsConstructor
public class CashDenomination extends BaseEntity {

    /**
     * Reference to the associated teller transaction. For a customer cash
     * deposit/withdrawal, this matches {@code DepositTransaction.transactionRef}.
     * For till-to-vault and vault-to-till movements, it matches the
     * {@code TellerCashMovement.movementRef}.
     */
    @Column(name = "transaction_ref", nullable = false, length = 40)
    private String transactionRef;

    /**
     * Till that originated or received the cash. Required for till-level
     * EOD denomination reports and chest remittance breakdowns.
     */
    @Column(name = "till_id", nullable = false)
    private Long tillId;

    /** Business date of the parent transaction. Denormalized for reporting. */
    @Column(name = "value_date", nullable = false)
    private java.time.LocalDate valueDate;

    /** Denomination kind/face value. See {@link IndianCurrencyDenomination}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "denomination", nullable = false, length = 20)
    private IndianCurrencyDenomination denomination;

    /**
     * Number of units of this denomination in the transaction. For COIN_BUCKET,
     * this is the rupee value of coins (since face value = 1).
     */
    @Column(name = "unit_count", nullable = false)
    private long unitCount;

    /**
     * Total INR value contributed by this row. Equals
     * {@code denomination.value() * unitCount}. Stored (not derived in queries)
     * so the chest-remittance and till-reconciliation reports can SUM directly
     * without joining to the enum-aware service code.
     */
    @Column(name = "total_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalValue;

    /**
     * Direction: IN (received from customer) or OUT (paid to customer).
     * Used by chest-remittance reports to net inflow vs outflow per denomination.
     */
    @Column(name = "direction", nullable = false, length = 4)
    private String direction;

    /**
     * Counterfeit flag per FICN guidelines. Set to true if the teller / supervisor
     * marks any note in this denomination batch as counterfeit during physical
     * verification. Triggers a separate FICN report and prevents the value from
     * being credited to the customer account.
     */
    @Column(name = "counterfeit_flag", nullable = false)
    private boolean counterfeitFlag = false;

    @Column(name = "counterfeit_count")
    private Long counterfeitCount;
}

package com.finvanta.cbs.modules.teller.domain;

import com.finvanta.domain.entity.BaseEntity;
import com.finvanta.domain.entity.Branch;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Branch Vault Position per RBI Internal Controls / CBS VAULT_POS standard.
 *
 * <p>One row per branch per business date. Tracks the branch-level cash safe
 * balance. The vault is the complement of the teller tills for the EOD
 * reconciliation invariant:
 * <pre>
 *   SUM(till.currentBalance @ branch X, date D)
 *     + vault.currentBalance(branch X, date D)
 *     == GL BANK_OPERATIONS branch balance(branch X, date D)
 * </pre>
 *
 * <p>Cash flows between vault and tills via {@link TellerCashMovement}:
 * <ul>
 *   <li><b>Vault Buy (vault→till):</b> teller requests cash from vault;
 *       vault decrements, till increments. Used at start-of-shift or
 *       mid-day when till runs low for withdrawals.</li>
 *   <li><b>Vault Sell (till→vault):</b> teller returns excess cash to
 *       vault; till decrements, vault increments. Used when till exceeds
 *       soft limit or at end-of-shift before till close.</li>
 * </ul>
 *
 * <p>The vault does NOT interact with customer accounts — it is purely an
 * internal cash-management layer between the branch safe and the teller
 * counters. Customer deposits/withdrawals mutate tills, not the vault
 * directly.
 *
 * <p>Per RBI Internal Controls: vault access requires dual control
 * (joint custody of keys). The {@code TellerCashMovement} entity captures
 * the maker (requesting teller) and checker (vault custodian) for every
 * vault↔till movement.
 */
@Entity
@Table(
        name = "vault_positions",
        indexes = {
                @Index(name = "uq_vault_branch_date",
                        columnList = "tenant_id, branch_id, business_date", unique = true),
                @Index(name = "idx_vault_status",
                        columnList = "tenant_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
public class VaultPosition extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** Denormalized branch code for cheap reporting. */
    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    /** CBS business date this vault position is bound to. */
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    /**
     * Lifecycle: OPEN (accepting movements) or CLOSED (EOD reconciled).
     * A vault cannot be CLOSED while any of its tills are still OPEN.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    /**
     * Cash balance at the start of the business date. Set once during
     * vault-open (typically carried forward from the previous day's
     * closing balance). Immutable for the day.
     */
    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    /**
     * Current cash balance. Mutated by every vault↔till movement.
     * Decremented on vault-buy (cash leaves vault to till);
     * incremented on vault-sell (cash returns from till to vault).
     */
    @Column(name = "current_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    /**
     * Physical count at EOD. Used by the vault custodian reconciliation
     * step to compute variance.
     */
    @Column(name = "counted_balance", precision = 18, scale = 2)
    private BigDecimal countedBalance;

    /** counted - current (+ overage / - shortage). */
    @Column(name = "variance_amount", precision = 18, scale = 2)
    private BigDecimal varianceAmount;

    /** Username of the vault custodian who opened the vault for the day. */
    @Column(name = "opened_by", length = 100)
    private String openedBy;

    /** Username of the vault custodian who closed and reconciled the vault. */
    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @Column(name = "remarks", length = 500)
    private String remarks;

    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    public boolean isClosed() {
        return "CLOSED".equals(status);
    }
}

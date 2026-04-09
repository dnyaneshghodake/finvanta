package com.finvanta.domain.entity;

import com.finvanta.domain.enums.BranchType;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Branch Entity per Finacle BRANCH_MASTER (SOL) / Temenos COMPANY.
 *
 * Represents an operational or administrative unit in the bank's organizational
 * hierarchy. Per Finacle SOL architecture:
 *
 *   HEAD_OFFICE (HO) — single per tenant, consolidation point
 *     └── ZONAL_OFFICE (ZO) — zone-level supervision (North/South/East/West)
 *         └── REGIONAL_OFFICE (RO) — state/region-level supervision
 *             └── BRANCH (SOL) — operational banking unit
 *
 * Per RBI Banking Regulation Act 1949 Section 23:
 * - Every branch must be licensed by RBI (IFSC code assigned)
 * - Branch hierarchy is reported in statutory returns (OSMOS)
 * - Head Office is the primary regulatory interface
 *
 * Tier-1 CBS Enforcement:
 * - Every financial entity carries branch_id for operational isolation
 * - GL balances are maintained per-branch (GLBranchBalance)
 * - Day control (open/close/EOD) is per-branch
 * - Transaction batches are per-branch
 * - Users are restricted to their home branch (MAKER/CHECKER)
 * - ADMIN/HO roles bypass branch isolation for consolidated view
 *
 * Inter-branch operations:
 * - Customer transfer between branches requires maker-checker approval
 * - Fund transfers across branches create mirror GL entries (IB Payable/Receivable)
 * - Inter-branch settlement is netted at HO during EOD consolidation
 */
@Entity
@Table(
        name = "branches",
        indexes = {
            @Index(name = "idx_branch_tenant_code", columnList = "tenant_id, branch_code", unique = true),
            @Index(name = "idx_branch_tenant_parent", columnList = "tenant_id, parent_branch_id"),
            @Index(name = "idx_branch_tenant_type", columnList = "tenant_id, branch_type"),
            @Index(name = "idx_branch_tenant_zone", columnList = "tenant_id, zone_code"),
            @Index(name = "idx_branch_tenant_region", columnList = "tenant_id, region_code")
        })
@Getter
@Setter
@NoArgsConstructor
public class Branch extends BaseEntity {

    @Column(name = "branch_code", nullable = false, length = 20)
    private String branchCode;

    @Column(name = "branch_name", nullable = false, length = 200)
    private String branchName;

    @Column(name = "ifsc_code", length = 11)
    private String ifscCode;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "pin_code", length = 6)
    private String pinCode;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "region", length = 100)
    private String region;

    // === Branch Hierarchy (per Finacle SOL / Temenos COMPANY hierarchy) ===

    /**
     * Branch type in the organizational hierarchy.
     * Per Finacle SOL_TYPE: HEAD_OFFICE, ZONAL_OFFICE, REGIONAL_OFFICE, BRANCH.
     * Only BRANCH type can have customers, accounts, and transactions.
     * HO/ZO/RO are administrative — no direct customer operations.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "branch_type", nullable = false, length = 20)
    private BranchType branchType = BranchType.BRANCH;

    /**
     * Parent branch in the hierarchy. Null for HEAD_OFFICE (root).
     * Per Finacle: BRANCH → REGIONAL_OFFICE → ZONAL_OFFICE → HEAD_OFFICE.
     * Used for hierarchical reporting, approval escalation, and consolidation.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_branch_id")
    private Branch parentBranch;

    /**
     * Zone code for zone-level aggregation (e.g., NORTH, SOUTH, EAST, WEST).
     * Denormalized from the ZONAL_OFFICE ancestor for efficient reporting.
     * Per Finacle: zone-level P&L, NPA reports, and MIS dashboards.
     */
    @Column(name = "zone_code", length = 20)
    private String zoneCode;

    /**
     * Region code for region/state-level aggregation (e.g., MH, KA, TN).
     * Denormalized from the REGIONAL_OFFICE ancestor for efficient reporting.
     * Per Finacle: region-level regulatory returns and holiday management.
     */
    @Column(name = "region_code", length = 20)
    private String regionCode;

    /**
     * Whether this is the Head Office branch for the tenant.
     * Per Finacle: exactly one HO per tenant. HO is the consolidation point
     * for inter-branch settlement, regulatory reporting, and GL aggregation.
     * Constraint: only one branch with is_head_office=true per tenant.
     */
    @Column(name = "is_head_office", nullable = false)
    private boolean headOffice = false;

    // === Lifecycle Validation ===

    /**
     * CBS Hierarchy Invariant: Ensure headOffice flag is consistent with branchType.
     * Per Finacle: branchType is the single source of truth. If branchType is HEAD_OFFICE,
     * headOffice must be true, and vice versa. This prevents dual-truth divergence.
     */
    @PrePersist
    @PreUpdate
    protected void validateHierarchyInvariants() {
        // Sync headOffice flag with branchType
        if (branchType == BranchType.HEAD_OFFICE) {
            this.headOffice = true;
        } else if (branchType != null) {
            this.headOffice = false;
        }
        // HEAD_OFFICE must not have a parent (it's the root)
        if (branchType == BranchType.HEAD_OFFICE && parentBranch != null) {
            throw new IllegalStateException(
                    "HEAD_OFFICE branch cannot have a parent branch. Branch: " + branchCode);
        }
    }

    // === Helpers ===

    /** Returns true if this is an operational branch (Finacle SOL) that can have customers/accounts */
    public boolean isOperational() {
        return branchType != null && branchType.isOperational();
    }

    /**
     * Returns true if this is the Head Office.
     * Per Finacle: branchType is the single source of truth.
     * The headOffice flag must be consistent with branchType == HEAD_OFFICE.
     * If they diverge, branchType takes precedence (fail-safe: restrict, don't expand).
     */
    public boolean isHO() {
        return branchType == BranchType.HEAD_OFFICE;
    }

    /** Returns true if this is an administrative/supervisory level (HO/ZO/RO) */
    public boolean isAdministrative() {
        return branchType != null && branchType.isAdministrative();
    }
}

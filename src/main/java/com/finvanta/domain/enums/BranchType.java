package com.finvanta.domain.enums;

/**
 * CBS Branch Type per Finacle SOL_TYPE / Temenos COMPANY.TYPE.
 *
 * Defines the branch's position in the organizational hierarchy:
 *
 *   HEAD_OFFICE (HO)
 *     └── ZONAL_OFFICE (ZO) — Zone-level aggregation
 *         └── REGIONAL_OFFICE (RO) — Region/State-level aggregation
 *             └── BRANCH — Operational banking unit (RBI-licensed SOL)
 *
 * Per Finacle architecture:
 * - HEAD_OFFICE: Centralized functions (treasury, HR, IT, regulatory reporting)
 * - ZONAL_OFFICE: Zone-level supervision and consolidated reporting
 * - REGIONAL_OFFICE: State/region-level supervision, regional holiday management
 * - BRANCH: Customer-facing operations, cash vault, teller, day control
 *
 * Per RBI Banking Regulation Act 1949 Section 23:
 * - Every branch must be licensed by RBI
 * - Branch hierarchy must be reported in statutory returns
 * - Head Office is the primary regulatory interface
 *
 * Hierarchy rules:
 * - Only BRANCH type can have customers, accounts, and transactions
 * - HO/ZO/RO are administrative — no direct customer operations
 * - GL balances exist at BRANCH level; HO aggregates from branches
 * - EOD runs per BRANCH; HO consolidation runs after all branches
 */
public enum BranchType {
    /** Head Office — single per tenant, parent of all zones */
    HEAD_OFFICE,

    /** Zonal Office — supervises regions (North, South, East, West) */
    ZONAL_OFFICE,

    /** Regional Office — supervises branches within a state/region */
    REGIONAL_OFFICE,

    /** Branch — operational banking unit (Finacle SOL) with customers and accounts */
    BRANCH;

    /** Returns true if this is an operational branch that can have customers/accounts */
    public boolean isOperational() {
        return this == BRANCH;
    }

    /** Returns true if this is an administrative/supervisory level */
    public boolean isAdministrative() {
        return this != BRANCH;
    }
}

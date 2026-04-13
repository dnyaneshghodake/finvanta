package com.finvanta.domain.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Permission Entity per Finacle AUTH_PERMISSION / Temenos EB.PERMISSION.
 *
 * Defines granular, operation-level permissions that can be assigned to roles.
 * Per RBI IT Governance Direction 2023 §8.3 and SWIFT CSCF:
 * - Access control must be based on least-privilege principle
 * - Permissions must be granular (not just role-level)
 * - Permission matrix must be documented and auditable
 *
 * Permission Naming Convention per Finacle/Temenos:
 *   {MODULE}_{OPERATION}
 *   Examples: LOAN_CREATE, LOAN_APPROVE, DEPOSIT_WITHDRAW, GL_VIEW, USER_MANAGE
 *
 * Per Finacle AUTH_PERMISSION / BNP RBAC:
 * - Permissions are the atomic unit of access control
 * - Roles are collections of permissions (via RolePermission mapping)
 * - @PreAuthorize("hasPermission('LOAN_APPROVE')") checks the permission matrix
 * - New permissions can be added without code deployment (data-driven)
 *
 * Module Categories:
 *   LOAN     — Loan origination, disbursement, repayment, write-off
 *   DEPOSIT  — CASA open, deposit, withdraw, transfer, close
 *   FD       — Fixed deposit book, close, lien
 *   CLEARING — NEFT/RTGS/IMPS/UPI initiation, settlement, reversal
 *   CUSTOMER — CIF create, KYC verify, deactivate
 *   GL       — Chart of accounts view, trial balance, period close
 *   USER     — User create, role assign, lock/unlock, MFA manage
 *   CALENDAR — Day open/close, holiday manage, EOD run
 *   REPORT   — Regulatory reports, MIS, audit trail view
 *   SYSTEM   — Product config, charge config, limit config
 */
@Entity
@Table(
        name = "permissions",
        indexes = {
            @Index(name = "idx_perm_tenant_code",
                    columnList = "tenant_id, permission_code",
                    unique = true),
            @Index(name = "idx_perm_module",
                    columnList = "tenant_id, module")
        })
@Getter
@Setter
@NoArgsConstructor
public class Permission extends BaseEntity {

    /**
     * Unique permission code. Format: {MODULE}_{OPERATION}.
     * Per Finacle AUTH_PERMISSION: this is the key used in
     * @PreAuthorize("hasPermission('LOAN_APPROVE')") checks.
     */
    @Column(name = "permission_code", nullable = false, length = 50)
    private String permissionCode;

    /** Human-readable description for admin UI and audit reports */
    @Column(name = "description", nullable = false, length = 200)
    private String description;

    /**
     * Module this permission belongs to.
     * Per Finacle: permissions are grouped by module for admin UI display.
     * Values: LOAN, DEPOSIT, FD, CLEARING, CUSTOMER, GL, USER, CALENDAR, REPORT, SYSTEM
     */
    @Column(name = "module", nullable = false, length = 30)
    private String module;

    /** Whether this permission is active (soft-delete for deprecated permissions) */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Risk level of this permission for audit and compliance reporting.
     * Per RBI IT Governance: high-risk permissions require additional controls.
     * Values: LOW, MEDIUM, HIGH, CRITICAL
     *   LOW      — Read-only operations (GL_VIEW, REPORT_VIEW)
     *   MEDIUM   — Standard operations (DEPOSIT_DEPOSIT, LOAN_REPAYMENT)
     *   HIGH     — Approval operations (LOAN_APPROVE, DEPOSIT_CLOSE)
     *   CRITICAL — System operations (USER_MANAGE, EOD_RUN, WRITE_OFF)
     */
    @Column(name = "risk_level", nullable = false, length = 10)
    private String riskLevel = "MEDIUM";
}

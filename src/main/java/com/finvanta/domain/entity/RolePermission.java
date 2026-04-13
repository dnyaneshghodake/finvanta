package com.finvanta.domain.entity;

import com.finvanta.domain.enums.UserRole;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * CBS Role-Permission Mapping per Finacle AUTH_ROLE_PERM / Temenos EB.ROLE.PERMISSION.
 *
 * Maps roles to granular permissions. This is the CORE ACCESS CONTROL TABLE
 * that determines what each role can do in the CBS.
 *
 * Per RBI IT Governance Direction 2023 §8.3:
 * - Role-permission matrix must be documented and auditable
 * - Changes to the matrix must go through change management
 * - Matrix must enforce segregation of duties (maker ≠ checker)
 *
 * Per Finacle AUTH_ROLE_PERM / SWIFT CSCF:
 * - Permissions are additive — a role has exactly the permissions mapped to it
 * - No implicit permissions — ADMIN must have explicit mappings for every operation
 * - Permission changes take effect on next request (no session caching)
 *
 * Segregation of Duties Matrix (per RBI / Finacle):
 *   MAKER:   Can CREATE but NOT APPROVE
 *   CHECKER: Can APPROVE but NOT CREATE (for same entity type)
 *   ADMIN:   Can do both but self-approval is blocked by workflow engine
 *   AUDITOR: Read-only — no financial operations
 *
 * Example mappings:
 *   MAKER  → LOAN_CREATE, DEPOSIT_DEPOSIT, DEPOSIT_WITHDRAW, CUSTOMER_CREATE
 *   CHECKER → LOAN_VERIFY, LOAN_APPROVE, DEPOSIT_ACTIVATE, CUSTOMER_KYC_VERIFY
 *   ADMIN  → ALL permissions (explicitly mapped, not implied)
 *   AUDITOR → GL_VIEW, REPORT_VIEW, AUDIT_VIEW (read-only subset)
 */
@Entity
@Table(
        name = "role_permissions",
        indexes = {
            @Index(name = "idx_rp_tenant_role",
                    columnList = "tenant_id, role"),
            @Index(name = "idx_rp_tenant_role_perm",
                    columnList = "tenant_id, role, permission_id",
                    unique = true)
        })
@Getter
@Setter
@NoArgsConstructor
public class RolePermission extends BaseEntity {

    /**
     * Role this permission is assigned to.
     * Per Finacle AUTH_ROLE: roles are the grouping mechanism for permissions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    /**
     * Permission assigned to this role.
     * Per Finacle AUTH_ROLE_PERM: each row is one role-permission mapping.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    /** Whether this mapping is active (allows temporary permission revocation) */
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Grant type: ALLOW or DENY.
     * Per BNP RBAC: DENY takes precedence over ALLOW.
     * Default: ALLOW. DENY is used for explicit permission revocation
     * (e.g., ADMIN cannot WRITE_OFF without dual approval).
     */
    @Column(name = "grant_type", nullable = false, length = 10)
    private String grantType = "ALLOW";
}

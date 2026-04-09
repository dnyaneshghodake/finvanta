package com.finvanta.util;

import com.finvanta.domain.entity.Branch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Note: BusinessException is in the same package (com.finvanta.util) — no import needed.
// SecurityUtil is also in the same package — static calls are used intentionally
// for lightweight access to the Spring Security context. Consider refactoring to
// an injected dependency for testability in a future PR.

/**
 * CBS Branch Access Validator per Finacle BRANCH_CONTEXT / Temenos COMPANY.CHECK.
 *
 * This is the CENTRAL ENFORCEMENT POINT for branch-level data isolation.
 * Every service method that accesses branch-scoped data must call this validator
 * to ensure the current user has access to the target branch.
 *
 * <h3>Finacle SOL Access Rules:</h3>
 * <ul>
 *   <li>MAKER/CHECKER: restricted to their home branch (branchId must match)</li>
 *   <li>ADMIN: exempt from branch filtering (HO-level access)</li>
 *   <li>AUDITOR: read-only, sees all branches (branch filter not applied)</li>
 *   <li>SYSTEM: EOD batch operations bypass branch check (systemGenerated=true)</li>
 * </ul>
 *
 * <h3>Usage Pattern:</h3>
 * <pre>
 *   // In any service method that touches branch-scoped data:
 *   branchAccessValidator.validateAccess(account.getBranch());
 *
 *   // For transaction posting:
 *   branchAccessValidator.validateTransactionBranch(branchCode);
 * </pre>
 *
 * Per RBI Operational Risk Guidelines and IT Governance Direction 2023:
 * Branch staff must only access their branch's customers, accounts, and transactions.
 * Cross-branch access requires explicit authorization (ADMIN/HO role).
 */
@Component
public class BranchAccessValidator {

    private static final Logger log = LoggerFactory.getLogger(BranchAccessValidator.class);

    /**
     * Validates that the current user has access to the specified branch.
     * Throws BusinessException if access is denied.
     *
     * @param targetBranch The branch being accessed
     * @throws BusinessException if user's branch doesn't match and user is not ADMIN
     */
    public void validateAccess(Branch targetBranch) {
        if (targetBranch == null) {
            return; // Tenant-level entity (no branch restriction)
        }
        validateAccess(targetBranch.getId(), targetBranch.getBranchCode());
    }

    /**
     * Validates that the current user has access to the specified branch by ID.
     *
     * @param targetBranchId   The branch ID being accessed
     * @param targetBranchCode The branch code (for error messages)
     * @throws BusinessException if user's branch doesn't match and user is not ADMIN
     */
    public void validateAccess(Long targetBranchId, String targetBranchCode) {
        if (targetBranchId == null) {
            return; // Tenant-level entity (no branch restriction)
        }

        // ADMIN and AUDITOR bypass branch isolation (HO-level access)
        // Per RBI IT Governance: ADMIN has full access, AUDITOR has read-only all-branch access.
        // Uses hasRole()/isAuditorRole() instead of getCurrentUserRole() because the latter
        // excludes AUDITOR from its return values (designed for transaction limit resolution only).
        if (SecurityUtil.isAdminRole() || SecurityUtil.isAuditorRole()) {
            return;
        }

        Long userBranchId = SecurityUtil.getCurrentUserBranchId();
        if (userBranchId == null) {
            // System user or user without branch assignment — allow
            // This handles SYSTEM-initiated operations (EOD, batch)
            String username = SecurityUtil.getCurrentUsername();
            if ("SYSTEM".equals(username) || "SYSTEM_EOD".equals(username)) {
                return;
            }
            log.warn(
                    "User {} has no branch assigned — denying access to branch {}",
                    username,
                    targetBranchCode);
            throw new BusinessException(
                    "BRANCH_NOT_ASSIGNED",
                    "User " + username + " has no home branch assigned. "
                            + "Contact administrator to assign a branch.");
        }

        if (!userBranchId.equals(targetBranchId)) {
            String username = SecurityUtil.getCurrentUsername();
            String userBranchCode = SecurityUtil.getCurrentUserBranchCode();
            log.warn(
                    "BRANCH_ACCESS_DENIED: user={}, userBranch={}, targetBranch={}, role={}",
                    username,
                    userBranchCode,
                    targetBranchCode,
                    currentRole);
            throw new BusinessException(
                    "BRANCH_ACCESS_DENIED",
                    "User " + username + " (branch " + userBranchCode + ", role " + currentRole
                            + ") cannot access branch " + targetBranchCode
                            + ". Per RBI operational controls, branch staff can only access their home branch. "
                            + "Contact ADMIN for cross-branch authorization.");
        }
    }

    /**
     * Validates that the current user can post transactions to the specified branch.
     * This is called by TransactionEngine Step 5 (Branch Validation).
     *
     * @param branchCode The branch code of the transaction
     * @param branchId   The branch ID of the transaction
     * @param isSystemGenerated Whether this is a system-generated transaction (EOD)
     * @throws BusinessException if user's branch doesn't match and not system-generated
     */
    public void validateTransactionBranch(String branchCode, Long branchId, boolean isSystemGenerated) {
        if (isSystemGenerated) {
            return; // EOD batch operations bypass branch check
        }
        validateAccess(branchId, branchCode);
    }
}

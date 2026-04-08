package com.finvanta.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * CBS Branch-Aware UserDetails per Finacle/Temenos Branch Context standards.
 *
 * Extends Spring Security User to carry the user's home branch ID.
 * This enables branch-level data isolation across all modules:
 * - MAKER/CHECKER: see only customers/accounts at their branch
 * - ADMIN: sees all branches (branchId is still set but isolation is bypassed)
 *
 * Per RBI Operational Risk Guidelines and Finacle BRANCH_CONTEXT:
 * Every user operation must be traceable to a specific branch.
 * The branch context is set at login and available throughout the session.
 */
public class BranchAwareUserDetails extends User {

    private final Long branchId;
    private final String branchCode;

    public BranchAwareUserDetails(String username, String password,
                                   Collection<? extends GrantedAuthority> authorities,
                                   Long branchId, String branchCode) {
        super(username, password, authorities);
        this.branchId = branchId;
        this.branchCode = branchCode;
    }

    /** Returns the user's home branch ID for data isolation queries. */
    public Long getBranchId() {
        return branchId;
    }

    /** Returns the user's home branch code for display and voucher generation. */
    public String getBranchCode() {
        return branchCode;
    }
}

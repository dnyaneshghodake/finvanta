package com.finvanta.config;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

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
    private final boolean mfaRequired;

    /**
     * Full constructor with account status flags for lockout, password expiry, and MFA.
     *
     * @param username              Login username
     * @param password              Encoded password hash
     * @param accountNonLocked      false if account is locked (failed login attempts)
     * @param credentialsNonExpired false if password has expired (90-day rotation)
     * @param authorities           Granted roles (ROLE_MAKER, ROLE_CHECKER, etc.)
     * @param branchId              User's home branch ID
     * @param branchCode            User's home branch code
     * @param mfaRequired           true if user has MFA enabled with valid secret (needs TOTP verification)
     */
    public BranchAwareUserDetails(
            String username,
            String password,
            boolean accountNonLocked,
            boolean credentialsNonExpired,
            Collection<? extends GrantedAuthority> authorities,
            Long branchId,
            String branchCode,
            boolean mfaRequired) {
        super(username, password, true, true, credentialsNonExpired, accountNonLocked, authorities);
        this.branchId = branchId;
        this.branchCode = branchCode;
        this.mfaRequired = mfaRequired;
    }

    /** Backward-compatible constructor (all flags true, no MFA) */
    public BranchAwareUserDetails(
            String username,
            String password,
            boolean accountNonLocked,
            boolean credentialsNonExpired,
            Collection<? extends GrantedAuthority> authorities,
            Long branchId,
            String branchCode) {
        this(username, password, accountNonLocked, credentialsNonExpired, authorities,
                branchId, branchCode, false);
    }

    /** Backward-compatible constructor (all flags true, no MFA) */
    public BranchAwareUserDetails(
            String username,
            String password,
            Collection<? extends GrantedAuthority> authorities,
            Long branchId,
            String branchCode) {
        super(username, password, authorities);
        this.branchId = branchId;
        this.branchCode = branchCode;
        this.mfaRequired = false;
    }

    /** Returns the user's home branch ID for data isolation queries. */
    public Long getBranchId() {
        return branchId;
    }

    /** Returns the user's home branch code for display and voucher generation. */
    public String getBranchCode() {
        return branchCode;
    }

    /**
     * Returns true if the user has MFA enabled with a valid secret enrolled.
     * Per RBI IT Governance Direction 2023 Section 8.4:
     * When true, the authentication success handler should redirect to a TOTP
     * verification page instead of the dashboard. The password-based authentication
     * is only the first factor; the second factor (TOTP) must be verified before
     * granting full session access.
     */
    public boolean isMfaRequired() {
        return mfaRequired;
    }
}

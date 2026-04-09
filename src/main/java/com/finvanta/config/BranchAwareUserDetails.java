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
    private final boolean passwordExpired;

    /**
     * Full constructor with account status flags for lockout, password expiry, and MFA.
     *
     * @param username              Login username
     * @param password              Encoded password hash
     * @param accountNonLocked      false if account is locked (failed login attempts)
     * @param passwordExpired       true if password has expired (90-day rotation)
     * @param authorities           Granted roles (ROLE_MAKER, ROLE_CHECKER, etc.)
     * @param branchId              User's home branch ID
     * @param branchCode            User's home branch code
     * @param mfaRequired           true if user has MFA enabled with valid secret (needs TOTP verification)
     */
    public BranchAwareUserDetails(
            String username,
            String password,
            boolean accountNonLocked,
            boolean passwordExpired,
            Collection<? extends GrantedAuthority> authorities,
            Long branchId,
            String branchCode,
            boolean mfaRequired) {
        // CBS: Always pass credentialsNonExpired=true to Spring Security.
        // We handle password expiry OURSELVES in MfaAuthenticationSuccessHandler
        // by redirecting to /password/change. If we pass false here, Spring blocks
        // login entirely with CredentialsExpiredException — user can never reach
        // the password change page. This is the Finacle/Temenos pattern:
        // allow login, then force password change before granting access.
        super(username, password, true, true, true, accountNonLocked, authorities);
        this.branchId = branchId;
        this.branchCode = branchCode;
        this.mfaRequired = mfaRequired;
        this.passwordExpired = passwordExpired;
    }

    /** Backward-compatible constructor (no MFA, no expiry) */
    public BranchAwareUserDetails(
            String username,
            String password,
            boolean accountNonLocked,
            boolean passwordExpired,
            Collection<? extends GrantedAuthority> authorities,
            Long branchId,
            String branchCode) {
        this(username, password, accountNonLocked, passwordExpired, authorities,
                branchId, branchCode, false);
    }

    /** Backward-compatible constructor (all flags true, no MFA, no expiry) */
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
        this.passwordExpired = false;
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

    /**
     * Returns true if the user's password has expired (90-day rotation).
     * Per RBI IT Governance Direction 2023 §8.2:
     * This is checked by MfaAuthenticationSuccessHandler AFTER login succeeds.
     * The user is redirected to /password/change and blocked from all other pages
     * until they set a new password.
     *
     * NOTE: This is separate from Spring Security's credentialsNonExpired flag.
     * We always pass credentialsNonExpired=true to Spring so login is not blocked.
     */
    public boolean isPasswordExpired() {
        return passwordExpired;
    }
}

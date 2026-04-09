package com.finvanta.config;

import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.TenantContext;

import java.util.Collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CBS User Authentication Service per Finacle USER_MASTER / Temenos USER.
 *
 * Per RBI IT Governance Direction 2023:
 * - Account lockout after 5 consecutive failed login attempts
 * - Auto-unlock after 30 minutes (configurable via AppUser.LOCKOUT_DURATION_MINUTES)
 * - Password expiry enforced (expired passwords flagged via credentialsNonExpired=false)
 * - Locked accounts flagged via accountNonLocked=false (Spring Security handles rejection)
 * - All authentication events should be audited (login success/failure)
 */
@Transactional(readOnly = true)
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final AppUserRepository userRepository;

    public CustomUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional // Write transaction needed for auto-unlock
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String tenantId;
        try {
            tenantId = TenantContext.getCurrentTenant();
        } catch (Exception e) {
            tenantId = "DEFAULT";
        }

        AppUser appUser = userRepository
                .findByTenantIdAndUsername(tenantId, username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        if (!appUser.isActive()) {
            throw new UsernameNotFoundException("User account is disabled: " + username);
        }

        // CBS: Auto-unlock check per RBI IT Governance Direction 2023.
        // If the account is locked but the lockout duration has elapsed,
        // automatically unlock it. This prevents permanent lockout from
        // accidental failed attempts while maintaining security.
        if (appUser.isLocked() && appUser.isAutoUnlockEligible()) {
            appUser.resetLoginAttempts();
            userRepository.save(appUser);
            log.info("User auto-unlocked after lockout duration: {}", username);
        }

        // CBS MFA Gate: Per RBI IT Governance Direction 2023 Section 8.4:
        // If MFA is enabled but the user has not yet enrolled (no TOTP secret),
        // reject login. This prevents a user with mfa_enabled=true from bypassing
        // MFA by simply never enrolling. The admin must either:
        //   1. Provide an MFA enrollment flow before enabling mfa_enabled, OR
        //   2. Disable mfa_enabled until enrollment endpoints are available.
        // Without this gate, the mfa_enabled flag is purely decorative.
        if (appUser.isMfaEnrollmentRequired()) {
            log.warn("MFA enrollment required but not completed for user: {}", username);
            throw new UsernameNotFoundException(
                    "MFA enrollment required for user: " + username
                            + ". Contact administrator to complete MFA setup.");
        }

        Long branchId = appUser.getBranch() != null ? appUser.getBranch().getId() : null;
        String branchCode = appUser.getBranch() != null ? appUser.getBranch().getBranchCode() : null;

        // CBS: Use Spring Security's built-in account status flags:
        //   - accountNonLocked=false → Spring rejects with LockedException
        //   - credentialsNonExpired=false → Spring rejects with CredentialsExpiredException
        // This is cleaner than throwing UsernameNotFoundException for locked/expired accounts
        // because it provides proper error codes to the login page.
        //
        // CBS MFA Note: When MFA enrollment/verification endpoints are implemented,
        // this method should return a custom UserDetails that carries mfaEnabled/mfaSecret
        // so the authentication success handler can redirect to the TOTP verification page
        // instead of the dashboard. For now, mfa_enabled=true with a valid secret would
        // pass through (TOTP verification is a Phase 2 enhancement).
        return new BranchAwareUserDetails(
                appUser.getUsername(),
                appUser.getPasswordHash(),
                !appUser.isLocked(), // accountNonLocked
                !appUser.isPasswordExpired(), // credentialsNonExpired
                Collections.singletonList(
                        new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name())),
                branchId,
                branchCode);
    }
}

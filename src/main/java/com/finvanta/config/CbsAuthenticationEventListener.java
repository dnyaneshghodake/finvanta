package com.finvanta.config;

import com.finvanta.audit.AuditService;
import com.finvanta.domain.entity.AppUser;
import com.finvanta.repository.AppUserRepository;
import com.finvanta.util.TenantContext;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * CBS Authentication Event Listener per RBI IT Governance Direction 2023 Section 8.3.
 *
 * Listens to Spring Security authentication events and:
 * 1. Records successful logins (IP, timestamp, reset failed attempts)
 * 2. Records failed logins (increment counter, lock after 5 failures)
 * 3. Logs all auth events to AuditService for immutable audit trail
 *
 * Per Finacle USER_MASTER / Temenos USER:
 * - Every login attempt (success or failure) must be recorded
 * - Failed attempts must trigger account lockout per RBI policy
 * - Login history must be queryable for security investigation
 * - All events are branch-attributed via AuditService
 *
 * CRITICAL: Without this listener, AppUser.recordFailedLogin() and
 * recordSuccessfulLogin() are never called — account lockout is non-functional.
 */
@Component
public class CbsAuthenticationEventListener {

    private static final Logger log = LoggerFactory.getLogger(CbsAuthenticationEventListener.class);

    private final AppUserRepository userRepository;
    private final AuditService auditService;

    public CbsAuthenticationEventListener(AppUserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * CBS: Successful authentication handler.
     * Per RBI IT Governance Direction 2023:
     * - Record login timestamp and IP address
     * - Reset failed login attempt counter
     * - Log to immutable audit trail
     */
    @EventListener
    @Transactional
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        String ipAddress = resolveClientIp();
        String tenantId = resolveTenantId();

        try {
            // CBS: Ensure TenantContext is set for this thread before calling any
            // tenant-scoped service (AuditService, repositories).
            // The AuthenticationSuccessEvent may fire before or after TenantFilter
            // depending on filter registration order. Spring Security's FilterChainProxy
            // can execute before @Order(1) servlet filters in some configurations.
            // This is defensive: set it if missing, restore original state after.
            boolean tenantWasNull = !TenantContext.isSet();
            if (tenantWasNull) {
                TenantContext.setCurrentTenant(tenantId);
            }
            try {
                userRepository.findByTenantIdAndUsername(tenantId, username).ifPresent(user -> {
                    user.recordSuccessfulLogin(ipAddress);
                    user.setUpdatedBy("SYSTEM_AUTH");
                    userRepository.save(user);

                    auditService.logEvent(
                            "AppUser", user.getId(), "LOGIN_SUCCESS", null,
                            "ip=" + ipAddress,
                            "SECURITY",
                            "Login successful: user=" + username
                                    + " | IP=" + ipAddress
                                    + " | Role=" + user.getRole()
                                    + " | Branch="
                                    + (user.getBranch() != null ? user.getBranch().getBranchCode() : "N/A"));

                    log.info("LOGIN SUCCESS: user={}, ip={}, role={}", username, ipAddress, user.getRole());
                });
            } finally {
                if (tenantWasNull) {
                    TenantContext.clear();
                }
            }
        } catch (Exception e) {
            // Auth event handlers must NEVER block login — log and continue
            log.error("Failed to record login success for {}: {}", username, e.getMessage());
        }
    }

    /**
     * CBS: Failed authentication handler (bad credentials).
     * Per RBI IT Governance Direction 2023:
     * - Increment failed login attempt counter
     * - Lock account after MAX_FAILED_ATTEMPTS (5) consecutive failures
     * - Log to immutable audit trail (including lockout events)
     */
    @EventListener
    @Transactional
    public void onAuthenticationFailure(AuthenticationFailureBadCredentialsEvent event) {
        String username = event.getAuthentication().getName();
        String ipAddress = resolveClientIp();
        String tenantId = resolveTenantId();

        try {
            // CBS: Same tenant context guard as onAuthenticationSuccess — see comment there.
            boolean tenantWasNull = !TenantContext.isSet();
            if (tenantWasNull) {
                TenantContext.setCurrentTenant(tenantId);
            }
            try {
                userRepository.findByTenantIdAndUsername(tenantId, username).ifPresent(user -> {
                    boolean justLocked = user.recordFailedLogin();
                    user.setUpdatedBy("SYSTEM_AUTH");
                    userRepository.save(user);

                    if (justLocked) {
                        // Account just locked — critical security event
                        auditService.logEvent(
                                "AppUser", user.getId(), "ACCOUNT_LOCKED",
                                "failedAttempts=" + user.getFailedLoginAttempts(),
                                "LOCKED",
                                "SECURITY",
                                "Account LOCKED after " + AppUser.MAX_FAILED_ATTEMPTS
                                        + " failed login attempts: user=" + username
                                        + " | IP=" + ipAddress
                                        + " | Auto-unlock in " + AppUser.LOCKOUT_DURATION_MINUTES + " min");

                        log.error("ACCOUNT LOCKED: user={}, ip={}, attempts={}",
                                username, ipAddress, user.getFailedLoginAttempts());
                    } else {
                        auditService.logEvent(
                                "AppUser", user.getId(), "LOGIN_FAILED",
                                "failedAttempts=" + user.getFailedLoginAttempts(),
                                "ip=" + ipAddress,
                                "SECURITY",
                                "Login failed: user=" + username
                                        + " | IP=" + ipAddress
                                        + " | Attempt " + user.getFailedLoginAttempts()
                                        + "/" + AppUser.MAX_FAILED_ATTEMPTS);

                        log.warn("LOGIN FAILED: user={}, ip={}, attempt={}/{}",
                                username, ipAddress, user.getFailedLoginAttempts(), AppUser.MAX_FAILED_ATTEMPTS);
                    }
                });
            } finally {
                if (tenantWasNull) {
                    TenantContext.clear();
                }
            }
        } catch (Exception e) {
            // Auth event handlers must NEVER block login flow — log and continue
            log.error("Failed to record login failure for {}: {}", username, e.getMessage());
        }
    }

    /**
     * Resolve client IP address from the current HTTP request.
     * Handles X-Forwarded-For header for reverse proxy deployments.
     */
    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xForwarded = request.getHeader("X-Forwarded-For");
                if (xForwarded != null && !xForwarded.isEmpty()) {
                    return xForwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception e) {
            log.debug("Could not resolve client IP: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    /**
     * Resolve tenant ID — falls back to DEFAULT if TenantContext not initialized.
     */
    private String resolveTenantId() {
        try {
            return TenantContext.getCurrentTenant();
        } catch (Exception e) {
            return "DEFAULT";
        }
    }
}

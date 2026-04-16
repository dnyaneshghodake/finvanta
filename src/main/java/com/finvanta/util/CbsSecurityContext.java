package com.finvanta.util;

import org.springframework.stereotype.Component;

/**
 * CBS Security Context Facade per Finacle USER_CONTEXT / Temenos EB.USER.CONTEXT.
 *
 * <p>Injectable wrapper over the static {@link SecurityUtil} helpers. Services
 * and validators should depend on {@code CbsSecurityContext} rather than call
 * {@code SecurityUtil.*} directly so they are <b>unit-testable without the
 * Spring Security context</b> -- the production bean delegates to
 * {@code SecurityUtil}, while tests substitute a Mockito mock.
 *
 * <p>Per Finacle/Temenos Tier-1 engineering practice: every cross-cutting
 * concern (tenant, branch, user, role) must be reachable through an injected
 * dependency so that the {@code TransactionEngine} and validators can be
 * exercised with pure unit tests and never require a full
 * {@code @SpringBootTest} context just to stub a username.
 */
@Component
public class CbsSecurityContext {

    public String getCurrentUsername() {
        return SecurityUtil.getCurrentUsername();
    }

    public Long getCurrentUserBranchId() {
        return SecurityUtil.getCurrentUserBranchId();
    }

    public String getCurrentUserBranchCode() {
        return SecurityUtil.getCurrentUserBranchCode();
    }

    public String getCurrentUserRole() {
        return SecurityUtil.getCurrentUserRole();
    }

    public boolean isAdminRole() {
        return SecurityUtil.isAdminRole();
    }

    public boolean isAuditorRole() {
        return SecurityUtil.isAuditorRole();
    }
}

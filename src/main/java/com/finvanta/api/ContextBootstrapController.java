package com.finvanta.api;

import com.finvanta.service.SessionContextService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Context Bootstrap REST API per Finacle USER_SESSION / Temenos EB.USER.CONTEXT.
 *
 * <p><b>Tier-1 CBS Principle:</b> Login returns ONLY identity + tokens.
 * The operational context (branch status, business day, role/permission matrix,
 * financial authority limits, operational config) is fetched via this dedicated
 * endpoint AFTER login, BEFORE dashboard rendering.
 *
 * <p><b>BFF Flow:</b>
 * <pre>
 *   POST /auth/token → store JWT in memory → GET /context/bootstrap
 *   → hydrate BFF server-side session → GET /dashboard/widgets/*
 *   → render dashboard
 * </pre>
 *
 * <p><b>Why separate from login:</b>
 * <ul>
 *   <li>Login must be ultra-fast (&lt;300ms) — authentication only</li>
 *   <li>Context data is heavy (permissions, limits, business calendar)</li>
 *   <li>Context changes during session (branch switch, day close/open)</li>
 *   <li>Auth service and Context service scale independently</li>
 *   <li>RBI expects clear separation of auth &amp; business logic</li>
 *   <li>Minimal session payload (security principle of least privilege)</li>
 * </ul>
 *
 * <p><b>Refresh strategy:</b> The BFF should re-fetch bootstrap context:
 * <ul>
 *   <li>On initial login (once)</li>
 *   <li>After branch switch</li>
 *   <li>After token refresh (role/branch may have changed)</li>
 *   <li>On day status change event (DAY_OPEN → EOD_RUNNING → DAY_CLOSED)</li>
 * </ul>
 *
 * <p>Per RBI IT Governance Direction 2023 §8.1, §8.3, §8.4: the bootstrap
 * context carries the sanitized operational envelope safe for the BFF to
 * cache in its server-side session. No PII, no credentials, no deny lists.
 */
@RestController
@RequestMapping("/api/v1/context")
public class ContextBootstrapController {

    private final SessionContextService sessionContextService;

    public ContextBootstrapController(
            SessionContextService sessionContextService) {
        this.sessionContextService = sessionContextService;
    }

    /**
     * Bootstrap the Controlled Operational Context (COC) for the
     * authenticated user's current branch and business day.
     *
     * <p>Returns: branch details, business day status, role/permission
     * matrix, financial authority limits, and operational config.
     *
     * <p>Per Finacle USER_SESSION: this is the equivalent of the
     * "session activation" step that happens after authentication
     * but before the user can perform any operations.
     *
     * <p>The JWT carries username/tenant/role/branch — this endpoint
     * uses those claims to assemble the full operational context
     * from the domain model (read-only, no mutations).
     */
    @GetMapping("/bootstrap")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<LoginSessionContext>>
            bootstrap() {
        return ResponseEntity.ok(ApiResponse.success(
                sessionContextService.assembleFromSecurityContext()));
    }
}

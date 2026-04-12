package com.finvanta.config;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

/**
 * CBS Session Lifecycle Listener per RBI IT Governance Direction 2023 Section 8.3.
 *
 * Logs all session creation and destruction events for security monitoring.
 * Per RBI: all session lifecycle events must be auditable for detecting:
 * - Session hijacking (unexpected session creation patterns)
 * - Concurrent access violations (multiple sessions for same user)
 * - Idle session abuse (sessions that should have timed out)
 *
 * Per Finacle/Temenos: session events are logged to the SECURITY log channel
 * (configured in logback-spring.xml) for SIEM/SOC integration.
 */
@Component
public class CbsSessionListener implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(CbsSessionListener.class);

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        String sessionId = event.getSession().getId();
        int timeoutSeconds = event.getSession().getMaxInactiveInterval();
        log.info("SESSION CREATED: id={}, timeout={}s ({}m)",
                maskSessionId(sessionId), timeoutSeconds, timeoutSeconds / 60);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        String sessionId = event.getSession().getId();
        String username = resolveUsername(event);
        log.info("SESSION DESTROYED: id={}, user={}",
                maskSessionId(sessionId), username != null ? username : "UNKNOWN");
    }

    /**
     * Resolve username from the session's SecurityContext (if available).
     * The SecurityContext may already be cleared if logout was initiated.
     */
    private String resolveUsername(HttpSessionEvent event) {
        try {
            Object ctx = event.getSession().getAttribute("SPRING_SECURITY_CONTEXT");
            if (ctx instanceof SecurityContext securityContext) {
                Authentication auth = securityContext.getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    return auth.getName();
                }
            }
        } catch (Exception e) {
            // Session may be invalidated — ignore
        }
        return null;
    }

    /**
     * Mask session ID for log safety — show only last 8 chars.
     * Per OWASP: full session IDs must not appear in logs to prevent
     * session hijacking via log file access.
     */
    private String maskSessionId(String sessionId) {
        if (sessionId == null || sessionId.length() <= 8) {
            return "****";
        }
        return "****" + sessionId.substring(sessionId.length() - 8);
    }
}

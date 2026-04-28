package com.finvanta.cbs.common.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Tier-1 Audit Interceptor per RBI IT Governance Direction 2023 SS8.3.
 *
 * <p>Logs every HTTP request/response pair with:
 * <ul>
 *   <li>Correlation ID (from MDC, set by CorrelationIdMdcFilter)</li>
 *   <li>Request method and URI</li>
 *   <li>Authenticated user principal</li>
 *   <li>Client IP address (X-Forwarded-For aware)</li>
 *   <li>Response status code</li>
 *   <li>Elapsed time in milliseconds</li>
 * </ul>
 *
 * <p>Per RBI guidelines: all API access must be logged for forensic analysis.
 * PII is NOT logged -- request/response bodies are handled by AuditService
 * with explicit masking, not by this interceptor.
 *
 * <p>Performance: this interceptor adds less than 1ms overhead per request.
 * Uses MDC for zero-allocation correlation ID propagation.
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);
    private static final String START_TIME_ATTR = "cbs.audit.startTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        request.setAttribute(START_TIME_ATTR, System.nanoTime());

        String correlationId = MDC.get("correlationId");
        String user = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName() : "anonymous";
        String clientIp = resolveClientIp(request);

        log.info("CBS-AUDIT-REQ: method={} uri={} user={} ip={} correlationId={}",
                request.getMethod(),
                request.getRequestURI(),
                user,
                clientIp,
                correlationId);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler, ModelAndView modelAndView) {
        // No-op: response logging in afterCompletion for accurate timing
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        Long startNanos = (Long) request.getAttribute(START_TIME_ATTR);
        long elapsedMs = startNanos != null
                ? (System.nanoTime() - startNanos) / 1_000_000 : -1;

        String correlationId = MDC.get("correlationId");
        String user = request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName() : "anonymous";

        if (ex != null) {
            log.warn("CBS-AUDIT-RES: method={} uri={} status={} elapsed={}ms user={} "
                            + "correlationId={} error={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsedMs,
                    user,
                    correlationId,
                    ex.getClass().getSimpleName());
        } else {
            log.info("CBS-AUDIT-RES: method={} uri={} status={} elapsed={}ms user={} "
                            + "correlationId={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsedMs,
                    user,
                    correlationId);
        }

        // CBS SLA: warn if request exceeds 500ms
        if (elapsedMs > 500) {
            log.warn("CBS-SLA-BREACH: uri={} elapsed={}ms exceeds 500ms SLA threshold",
                    request.getRequestURI(), elapsedMs);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

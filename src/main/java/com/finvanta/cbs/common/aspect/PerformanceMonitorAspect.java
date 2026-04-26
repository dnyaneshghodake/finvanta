package com.finvanta.cbs.common.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CBS Tier-1 Performance Monitoring Aspect.
 *
 * <p>Per CBS Tier-1 SLA standards: all service-layer methods must be
 * monitored for execution time to detect performance regressions early.
 *
 * <p>Thresholds (per CBS operations benchmark):
 * <ul>
 *   <li>Under 100ms -- normal</li>
 *   <li>100ms-500ms -- warn (acceptable for complex operations like transfers)</li>
 *   <li>Over 500ms -- error (SLA breach, investigate immediately)</li>
 * </ul>
 *
 * <p>This aspect targets service implementations only (not controllers or
 * repositories) to measure business logic execution time without HTTP or
 * JPA overhead noise.
 */
@Aspect
@Component
public class PerformanceMonitorAspect {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitorAspect.class);

    private static final long WARN_THRESHOLD_MS = 100;
    private static final long ERROR_THRESHOLD_MS = 500;

    /**
     * Monitors execution time of all service implementation methods in the CBS modules.
     * Includes both legacy (com.finvanta.service.impl) and refactored (cbs.modules.*.service) packages.
     */
    @Around("execution(* com.finvanta.cbs.modules..service..*Impl.*(..))"
            + " || execution(* com.finvanta.service.impl..*(..))")
    public Object monitorServicePerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        long startNanos = System.nanoTime();
        String methodSignature = joinPoint.getSignature().toShortString();

        try {
            Object result = joinPoint.proceed();
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            logPerformance(methodSignature, elapsedMs, null);
            return result;
        } catch (Throwable ex) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            logPerformance(methodSignature, elapsedMs, ex);
            throw ex;
        }
    }

    private void logPerformance(String method, long elapsedMs, Throwable error) {
        if (error != null) {
            log.error("CBS-PERF: method={} elapsed={}ms status=FAILED error={}",
                    method, elapsedMs, error.getClass().getSimpleName());
        } else if (elapsedMs > ERROR_THRESHOLD_MS) {
            log.error("CBS-PERF-SLA-BREACH: method={} elapsed={}ms exceeds {}ms threshold",
                    method, elapsedMs, ERROR_THRESHOLD_MS);
        } else if (elapsedMs > WARN_THRESHOLD_MS) {
            log.warn("CBS-PERF-SLOW: method={} elapsed={}ms exceeds {}ms threshold",
                    method, elapsedMs, WARN_THRESHOLD_MS);
        } else {
            log.debug("CBS-PERF: method={} elapsed={}ms", method, elapsedMs);
        }
    }
}

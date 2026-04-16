package com.finvanta.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CBS Auth Rate Limiter per Finacle CONNECT rate-gating / RBI Cyber Security Framework 2024 §6.2.
 *
 * <p>Token-bucket per source IP applied to {@code /api/v1/auth/**} only. The auth
 * endpoints issue JWTs and are therefore the primary brute-force target; the
 * rest of {@code /api/v1/**} is already protected by a valid access token.
 *
 * <p>Defaults chosen per OWASP Automated Threats (OAT-008 Credential Stuffing):
 * <ul>
 *   <li>Capacity: 20 requests per IP</li>
 *   <li>Refill: 1 token every 6 seconds (10 requests / minute sustained)</li>
 * </ul>
 *
 * <p>Returns HTTP 429 with a stable JSON error payload. Per Finacle/Temenos
 * Tier-1 standards: the rate limit response MUST use the same
 * {@code ApiResponse} envelope as every other API error so clients have one
 * error path to handle.
 *
 * <p><b>Implementation note:</b> this is an in-process limiter -- adequate for a
 * single-node deployment. A production multi-node cluster should front this
 * with Redis (e.g. Bucket4j + Lettuce) so buckets are shared across nodes.
 * The {@link Bucket} abstraction is isolated here so the swap is local.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private static final int BUCKET_CAPACITY = 20;
    private static final Duration REFILL_INTERVAL = Duration.ofSeconds(6);
    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;

        if (!path.startsWith(AUTH_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(clientIp, ip -> new Bucket());
        if (!bucket.tryConsume()) {
            log.warn("AUTH_RATE_LIMIT_TRIPPED: ip={}, path={}", clientIp, path);
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After",
                    Long.toString(REFILL_INTERVAL.toSeconds()));
            response.getWriter().write(
                    "{\"status\":\"ERROR\","
                            + "\"errorCode\":\"AUTH_RATE_LIMIT_EXCEEDED\","
                            + "\"message\":\"Too many authentication attempts. "
                            + "Retry after " + REFILL_INTERVAL.toSeconds()
                            + " seconds.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xreal = request.getHeader("X-Real-IP");
        if (xreal != null && !xreal.isBlank()) {
            return xreal.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Single-IP token bucket. Lock-free via {@link AtomicReference}; state is a
     * snapshot of (tokens, lastRefillNanos) so CAS guarantees every refill +
     * consume step is atomic without synchronization.
     */
    private static final class Bucket {
        private final AtomicReference<State> state =
                new AtomicReference<>(new State(BUCKET_CAPACITY, Instant.now().toEpochMilli()));

        boolean tryConsume() {
            long nowMs = Instant.now().toEpochMilli();
            while (true) {
                State cur = state.get();
                long elapsedMs = nowMs - cur.lastRefillMs;
                long refill = elapsedMs / REFILL_INTERVAL.toMillis();
                int tokens = (int) Math.min(BUCKET_CAPACITY, cur.tokens + refill);
                long newLast = refill > 0
                        ? cur.lastRefillMs + refill * REFILL_INTERVAL.toMillis()
                        : cur.lastRefillMs;
                if (tokens <= 0) {
                    return false;
                }
                State next = new State(tokens - 1, newLast);
                if (state.compareAndSet(cur, next)) {
                    return true;
                }
            }
        }

        private record State(int tokens, long lastRefillMs) {}
    }
}

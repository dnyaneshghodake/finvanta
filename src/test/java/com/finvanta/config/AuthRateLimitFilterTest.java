package com.finvanta.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBS Auth Rate Limiter Tests — brute-force protection contract.
 *
 * Per RBI Cyber Security Framework 2024 §6.2 and OWASP OAT-008:
 * token-issuance endpoints must be rate-limited per source IP so
 * credential-stuffing attacks are throttled before account lockout.
 *
 * Bucket capacity is 20 requests; refill is 1 token every 6 seconds.
 * Non-auth paths must pass through unthrottled.
 */
class AuthRateLimitFilterTest {

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // Fresh filter per test so bucket state doesn't leak between tests
        filter = new AuthRateLimitFilter();
    }

    @Test
    void nonAuthPathPassesThroughWithoutRateLimit() throws Exception {
        // Even 50 requests on a non-auth path must all pass
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void authPathAllowsBurstUpToCapacity() throws Exception {
        // 20 requests (bucket capacity) must all succeed
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus())
                    .as("Request %d should pass", i + 1)
                    .isNotEqualTo(429);
        }
    }

    @Test
    void authPathReturns429AfterBucketExhausted() throws Exception {
        // Exhaust the 20-token bucket
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // 21st request must be rejected
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentType()).isEqualTo("application/json");
        assertThat(res.getHeader("Retry-After")).isEqualTo("6");
        String body = res.getContentAsString();
        assertThat(body).contains("AUTH_RATE_LIMIT_EXCEEDED");
        assertThat(body).contains("Too many authentication attempts");
    }

    @Test
    void mfaVerifyPathIsAlsoRateLimited() throws Exception {
        // Exhaust bucket via /api/v1/auth/mfa/verify (also under /api/v1/auth/)
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/mfa/verify");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/mfa/verify");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
    }

    @Test
    void differentIpsHaveIndependentBuckets() throws Exception {
        // Exhaust bucket for IP-A via X-Forwarded-For
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            req.addHeader("X-Forwarded-For", "10.0.0.1");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // IP-A is now exhausted
        MockHttpServletRequest reqA = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        reqA.addHeader("X-Forwarded-For", "10.0.0.1");
        MockHttpServletResponse resA = new MockHttpServletResponse();
        filter.doFilter(reqA, resA, new MockFilterChain());
        assertThat(resA.getStatus()).isEqualTo(429);

        // IP-B must still have a full bucket
        MockHttpServletRequest reqB = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        reqB.addHeader("X-Forwarded-For", "10.0.0.2");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        filter.doFilter(reqB, resB, new MockFilterChain());
        assertThat(resB.getStatus()).isNotEqualTo(429);
    }

    @Test
    void xForwardedForFirstIpIsUsedForBucket() throws Exception {
        // Multi-hop XFF: "client, proxy1, proxy2" — client IP is first
        for (int i = 0; i < 20; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            req.addHeader("X-Forwarded-For", "192.168.1.100, 10.0.0.1, 10.0.0.2");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // Same client IP exhausted even with different proxy chain
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        req.addHeader("X-Forwarded-For", "192.168.1.100, 10.0.0.99");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
    }

    @Test
    void refreshEndpointSharesBucketWithTokenEndpoint() throws Exception {
        // Mix of /auth/token and /auth/refresh from same IP share the bucket
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
            filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        }

        // 21st request (either endpoint) must be rejected
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
    }
}

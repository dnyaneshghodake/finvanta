package com.finvanta.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CBS Tier-1: Correlation-ID filter must (a) accept a valid inbound header,
 * (b) reject and regenerate a malformed one, (c) generate a fresh UUID when
 * the header is absent, (d) always echo the resolved value on the response,
 * (e) populate MDC during chain execution for downstream log lines, and
 * (f) clean up MDC after the request completes.
 *
 * Per RBI IT Governance Direction 2023 §7.4: traceability is a hard invariant.
 */
class CorrelationIdMdcFilterTest {

    private final CorrelationIdMdcFilter filter = new CorrelationIdMdcFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesValidInboundCorrelationId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        req.addHeader(CorrelationIdMdcFilter.HEADER_NAME, "7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9");

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader(CorrelationIdMdcFilter.HEADER_NAME)).isEqualTo("7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9");
        // MDC cleared in finally block — must not leak between requests
        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesFreshIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        String id = res.getHeader(CorrelationIdMdcFilter.HEADER_NAME);
        assertThat(id).isNotBlank();
        // UUID v4 length is 36; our generated UUIDs must satisfy the accepted pattern.
        assertThat(id).hasSize(36);
    }

    @Test
    void rejectsMalformedInboundAndSynthesisesNew() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        // Includes whitespace -> disallowed (header injection defence).
        req.addHeader(CorrelationIdMdcFilter.HEADER_NAME, "bad id with spaces");

        filter.doFilter(req, res, chain);

        String echoed = res.getHeader(CorrelationIdMdcFilter.HEADER_NAME);
        assertThat(echoed).isNotEqualTo("bad id with spaces");
        assertThat(echoed).hasSize(36);
    }

    @Test
    void exposesCorrelationIdAsRequestAttribute() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(req.getAttribute("fvCorrelationId")).isEqualTo(res.getHeader(CorrelationIdMdcFilter.HEADER_NAME));
    }

    /**
     * CBS CRITICAL: The entire purpose of this filter is to make the
     * correlationId available in MDC DURING chain.doFilter() so that
     * every downstream log line / audit row carries the same id.
     * The four tests above only verify post-finally state and response
     * headers — this test verifies the MDC is populated at the moment
     * the downstream servlet/filter actually executes.
     */
    @Test
    void mdcIsPopulatedDuringChainExecution() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        String validId = "7f3c1e40-52ea-4c7a-9b8d-19f2a51bf4d9";
        req.addHeader(CorrelationIdMdcFilter.HEADER_NAME, validId);

        // Capture MDC value at the moment chain.doFilter() runs
        AtomicReference<String> capturedMdc = new AtomicReference<>();
        FilterChain capturingChain = (ServletRequest r, ServletResponse s) -> {
            capturedMdc.set(MDC.get(CorrelationIdMdcFilter.MDC_KEY));
        };

        filter.doFilter(req, res, capturingChain);

        // MDC must have been populated with the inbound id during execution
        assertThat(capturedMdc.get()).isEqualTo(validId);
        // And cleaned up after
        assertThat(MDC.get(CorrelationIdMdcFilter.MDC_KEY)).isNull();
    }

    /**
     * Boundary: a 15-char id is below the 16-char minimum and must be
     * rejected. A 16-char id is the minimum accepted length.
     */
    @Test
    void rejectsTooShortInboundId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        // 15 chars — below the 16-char minimum
        req.addHeader(CorrelationIdMdcFilter.HEADER_NAME, "abcdef012345678");

        filter.doFilter(req, res, new MockFilterChain());

        String echoed = res.getHeader(CorrelationIdMdcFilter.HEADER_NAME);
        assertThat(echoed).isNotEqualTo("abcdef012345678");
        assertThat(echoed).hasSize(36); // Synthesised UUID
    }

    @Test
    void acceptsMinimumLengthInboundId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse res = new MockHttpServletResponse();
        // Exactly 16 chars — minimum accepted
        req.addHeader(CorrelationIdMdcFilter.HEADER_NAME, "abcdef0123456789");

        filter.doFilter(req, res, new MockFilterChain());

        assertThat(res.getHeader(CorrelationIdMdcFilter.HEADER_NAME))
                .isEqualTo("abcdef0123456789");
    }
}

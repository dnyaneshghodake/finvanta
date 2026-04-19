package com.finvanta.config;

import jakarta.servlet.FilterChain;

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
 * the header is absent, (d) always echo the resolved value on the response.
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
}

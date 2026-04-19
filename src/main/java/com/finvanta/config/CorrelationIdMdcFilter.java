package com.finvanta.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * CBS Correlation-ID Filter per Finacle TRAN_REF / Temenos OFS.ID standards.
 *
 * <p>Reads the inbound {@code X-Correlation-Id} header sent by the Next.js BFF
 * (ultimately originating at the browser "user-action start") and places it
 * into SLF4J MDC so every downstream log line, audit row, and TransactionEngine
 * post carries the same end-to-end correlation key.
 *
 * <p>Distinct from TenantFilter's per-HTTP-request {@code requestId} counter:
 * <ul>
 *   <li>{@code correlationId} = stable across retries / MFA step-up / preview->confirm
 *       within a single user action, generated at the BFF or browser.</li>
 *   <li>{@code requestId} = generated fresh by TenantFilter per HTTP hit for
 *       intra-cluster request dedup.</li>
 * </ul>
 *
 * <p>Runs at {@link Order}(0) — strictly BEFORE {@link TenantFilter} (Order 1)
 * so that tenant/branch MDC population and the API tenant-header guard already
 * see a correlationId in MDC.
 *
 * <p>If the inbound header is absent or malformed, this filter generates a
 * UUID v4 and echoes it on the response header so the client can record it.
 * Per RBI IT Governance Direction 2023 §7.4: every operation must be traceable
 * by a stable correlation key.
 *
 * <p>Malformed headers are discarded (not echoed) to prevent log-injection via
 * the response header: only the strict UUID format or a short hex variant is
 * accepted; otherwise we synthesise a fresh one.
 */
@Component
@Order(0)
public class CorrelationIdMdcFilter implements Filter {

    public static final String HEADER_NAME = "X-Correlation-Id";

    /** MDC key matching logback-spring.xml pattern (add {@code %X{correlationId}} there). */
    public static final String MDC_KEY = "correlationId";

    /**
     * Accepted correlation-id formats:
     *   - UUID v4 lowercase/uppercase (36 chars)
     *   - 16-64 char hex or alphanumeric-dash (e.g. "7fA3-9ce-...-xyz")
     * Strict enough to prevent header-injection (no whitespace, no control chars).
     */
    private static final Pattern ACCEPTED = Pattern.compile("^[A-Za-z0-9-]{16,64}$");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String inbound = req.getHeader(HEADER_NAME);
        String correlationId;
        if (inbound != null && ACCEPTED.matcher(inbound).matches()) {
            correlationId = inbound;
        } else {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            MDC.put(MDC_KEY, correlationId);
            // Echo back so the BFF/browser can persist in the same record
            // and render it on error toasts. Always present — never leaked
            // stack traces or PII.
            res.setHeader(HEADER_NAME, correlationId);
            // Also surface as a request attribute so JSP error pages and
            // ApiExceptionHandler can retrieve it without re-parsing headers.
            req.setAttribute("fvCorrelationId", correlationId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

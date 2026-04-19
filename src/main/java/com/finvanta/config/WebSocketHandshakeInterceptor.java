package com.finvanta.config;

import com.finvanta.util.TenantContext;
import io.jsonwebtoken.Claims;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket Handshake Interceptor - Validates JWT and Tenant Context
 *
 * Per RBI IT Governance Direction 2023 §8.1:
 * - Every WebSocket connection must be authenticated with valid JWT
 * - Tenant context must be established before any message exchange
 * - Handshake failure must be logged for audit trail
 *
 * Flow:
 *   1. Extract JWT from query parameter or header
 *   2. Validate JWT signature and expiry
 *   3. Extract tenant ID from token claims
 *   4. Validate tenant context (prevent cross-tenant access)
 *   5. Store in session attributes for message handlers
 */
@Component
public class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandshakeInterceptor.class);

    private final JwtTokenService jwtTokenService;

    public WebSocketHandshakeInterceptor(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * Intercept WebSocket handshake before connection is established
     *
     * CBS SECURITY:
     * - Reject invalid/expired JWT immediately
     * - Prevent cross-tenant message subscription
     * - Log authentication failures for fraud detection
     */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            java.util.Map<String, Object> attributes) throws Exception {

        try {
            // Extract JWT from query parameter or Authorization header
            String token = extractToken(request);

            if (token == null || token.isBlank()) {
                log.warn("WebSocket connection rejected: No JWT token provided");
                return false;
            }

            // Validate JWT
            Claims claims = jwtTokenService.validateToken(token);
            if (claims == null) {
                log.warn("WebSocket connection rejected: Invalid JWT token");
                return false;
            }

            // Extract tenant ID from JWT claims
            String tenantId = jwtTokenService.getTenantId(claims);
            if (tenantId == null || tenantId.isBlank()) {
                log.warn("WebSocket connection rejected: No tenant in JWT");
                return false;
            }

            // Extract username for audit
            String username = jwtTokenService.getUsername(claims);

            // Store in session attributes for message handlers
            attributes.put("tenantId", tenantId);
            attributes.put("username", username);
            attributes.put("token", token);

            // Set tenant context
            TenantContext.setCurrentTenant(tenantId);

            log.info("WebSocket connection established: user={}, tenant={}", username, tenantId);

            return true; // Allow connection

        } catch (Exception ex) {
            log.error("WebSocket handshake error: {}", ex.getMessage());
            return false; // Reject connection
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {

        if (exception != null) {
            log.error("WebSocket handshake failed: {}", exception.getMessage());
        }
    }

    /**
     * Extract JWT from query parameter (?token=...) or Authorization header
     * React client sends token as query parameter or in header
     */
    private String extractToken(ServerHttpRequest request) {
        // Try query parameter first
        String uri = request.getURI().toString();
        if (uri.contains("?token=")) {
            int start = uri.indexOf("?token=") + 7;
            int end = uri.indexOf("&", start);
            if (end < 0) {
                end = uri.length();
            }
            return uri.substring(start, end);
        }

        // Try Authorization header
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }
}


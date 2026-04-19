package com.finvanta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * CBS WebSocket Configuration for React + Next.js Frontend Real-time Updates
 *
 * Per RBI IT Governance Direction 2023:
 * - Real-time balance updates must reach customers within 100ms
 * - Transaction posting must notify both parties (creditor & debtor)
 * - Loan status changes must be pushed immediately
 * - All WebSocket messages must be auditable
 *
 * Topics:
 *   /topic/accounts/{accountId}/balance        → Balance updates
 *   /topic/accounts/{accountId}/transactions    → Transaction postings
 *   /topic/loans/{loanId}/status                → Loan status changes
 *   /topic/deposits/{depositId}/maturity        → Deposit maturity notifications
 *
 * Security:
 *   - WebSocket endpoint requires JWT authentication
 *   - Tenant context enforced (can only subscribe to own accounts)
 *   - Message size limited to prevent DoS
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configure message broker for topic-based publishing
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple in-memory broker for /topic destinations
        // For production, consider RabbitMQ or ActiveMQ
        config.enableSimpleBroker("/topic");

        // Set application destination prefix for controller methods
        config.setApplicationDestinationPrefixes("/app");

        // Set user destination prefix for user-specific messages
        config.setUserDestinationPrefix("/user");
    }

    /**
     * Register STOMP endpoints for WebSocket connections
     *
     * /ws/cbs is the endpoint React client connects to:
     *   ws://localhost:8080/ws/cbs
     *   Authorization: Bearer {JWT}
     *   X-Tenant-Id: {tenantId}
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/cbs")
            // Allow React frontend origins
            .setAllowedOrigins(
                "http://localhost:3000",           // Development
                "http://localhost:3001",           // Development (alternative)
                "https://cbs.example.com",         // Production
                "https://www.cbs.example.com",     // Production with www
                "https://mobile.cbs.example.com")  // Mobile
            // Fallback to SockJS for browsers without WebSocket support
            .withSockJS()
            .setSessionCookieNeeded(false)         // Stateless, no cookies
            .setInterceptors(new WebSocketHandshakeInterceptor());
    }

    /**
     * Configure WebSocket container with message size limits
     * Per CBS security: Prevent DoS attacks with oversized messages
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();

        // Message size limits (prevent DoS)
        container.setMaxTextMessageSize(65536);       // 64KB text messages
        container.setMaxBinaryMessageSize(65536);     // 64KB binary messages
        container.setMaxSessionIdleTimeout(30 * 60 * 1000);  // 30 minutes

        return container;
    }
}


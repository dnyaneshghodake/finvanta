package com.finvanta.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessEventPublishingLogoutHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CBS Security Configuration — Role-Based Access Control per Finacle/Temenos standards.
 *
 * CBS Role Matrix:
 *   MAKER   → Loan applications, customer creation, repayment processing
 *   CHECKER → Verification, approval, rejection, KYC verification, disbursement, account creation
 *   ADMIN   → All CHECKER permissions + EOD batch, branch management, system config
 *   AUDITOR → Read-only audit trail access
 *
 * Per RBI guidelines on internal controls:
 * - Maker cannot verify/approve their own transactions (enforced in service layer)
 * - Verifier and approver must be different users (enforced in service layer)
 * - EOD batch processing restricted to ADMIN only
 * - Audit logs accessible only to AUDITOR and ADMIN
 *
 * SECURITY: H2 console access is restricted to 'dev' profile only.
 * In production, /h2-console/** is denied by the default authenticated() rule.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final MfaAuthenticationSuccessHandler mfaSuccessHandler;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public SecurityConfig(MfaAuthenticationSuccessHandler mfaSuccessHandler,
            org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.mfaSuccessHandler = mfaSuccessHandler;
        this.eventPublisher = eventPublisher;
    }

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    @Value("${spring.boot.app.cors.allowed-origins:http://localhost:3000}")
    private String corsAllowedOrigins;

    private boolean isDevProfile() {
        return activeProfile != null && activeProfile.contains("dev");
    }

    /**
     * CBS API SecurityFilterChain — Stateless JWT authentication.
     * Per Finacle Connect / Temenos IRIS: REST APIs use JWT tokens,
     * no session, no CSRF, no form login.
     *
     * MUST be ordered BEFORE the UI chain (@Order(1)) so that
     * /api/v1/** requests are matched by this chain first.
     *
     * Auth flow: Authorization: Bearer {jwt} → JwtAuthenticationFilter
     *            → validates HMAC-SHA256 → sets SecurityContext
     *            → @PreAuthorize on controller method
     *
     * /api/v1/auth/** is permitAll (token issuance endpoints).
     * All other /api/v1/** require valid JWT ACCESS token.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            AuthRateLimitFilter authRateLimitFilter)
            throws Exception {
        http.securityMatcher("/api/v1/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (req, res, authEx) -> {
                                    res.setStatus(401);
                                    res.setContentType(
                                            "application/json");
                                    res.getWriter().write(
                                            "{\"status\":\"ERROR\","
                                            + "\"errorCode\":"
                                            + "\"UNAUTHORIZED\","
                                            + "\"message\":"
                                            + "\"Authentication "
                                            + "required. Provide "
                                            + "Bearer token.\"}");
                                }))
                // CBS: rate limit auth endpoints BEFORE JWT auth so token issuance
                // cannot be brute-forced. Per RBI Cyber Security Framework 2024 §6.2.
                .addFilterBefore(authRateLimitFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter,
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CBS UI SecurityFilterChain — Session-based authentication.
     * Per Finacle/Temenos: Thymeleaf UI uses form login + session + CSRF.
     * This chain handles ALL non-API paths (/, /login, /deposit/*, /loan/*, etc.)
     *
     * @Order(2) ensures this is the fallback chain after the API chain.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain uiSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                                    "/login",
                                    "/error",
                                    "/error/**",
                                    "/WEB-INF/**",
                                    "/resources/**",
                                    "/css/**",
                                    "/js/**",
                                    "/fonts/**",
                                    "/img/**",
                                    "/actuator/health",
                                    "/actuator/info")
                            .permitAll();
                    if (isDevProfile()) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    // CBS: Explicit rule for branch-switching admin endpoint per Finacle
                    // BRANCH_CONTEXT / Temenos BRANCH.SWITCH. Declared BEFORE /admin/**
                    // so it is resolved first and survives any future reordering of the
                    // /admin/** wildcard. The wildcard already guards it, but per Tier-1
                    // CBS security review: every mutable admin endpoint must have an
                    // explicit RBAC rule for auditability and defence in depth.
                    auth.requestMatchers("/admin/switch-branch")
                            .hasRole("ADMIN")
                            .requestMatchers("/admin/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/batch/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/branch/add")
                            .hasRole("ADMIN")
                            .requestMatchers("/customer/add")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/customer/edit/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/customer/deactivate/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/customer/verify-kyc/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            // CBS Document Management: role restrictions per Finacle DOC_MASTER
                            // MAKER uploads documents, CHECKER verifies/rejects, both can download.
                            // Without these rules, document endpoints fall to .anyRequest().authenticated()
                            // allowing any authenticated user (including AUDITOR) to upload/verify.
                            .requestMatchers("/customer/document/upload/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/customer/document/verify/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/customer/document/download/**")
                            .hasAnyRole("MAKER", "CHECKER", "ADMIN")
                            .requestMatchers("/branch/edit/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/calendar/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/loan/verify/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/approve/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/reject/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/create-account/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/disburse/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/write-off/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/loan/reversal/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/fee/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/loan/collateral/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/document/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/disburse-tranche/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/pause/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/resume/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/cancel/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/register")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/loan/si/approve/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/reject/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/amend/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/si/dashboard")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/loan/restructure/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/loan/moratorium/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/batch/txn/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/admin/products/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/admin/limits/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/admin/charges/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/workflow/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/reconciliation/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/reports/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/deposit/pipeline")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/deposit/accounts")
                            .hasAnyRole("MAKER", "CHECKER", "ADMIN")
                            .requestMatchers("/deposit/open")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/deposit/view/**")
                            .hasAnyRole("MAKER", "CHECKER", "ADMIN")
                            .requestMatchers("/deposit/deposit/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/deposit/withdraw/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/deposit/transfer")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/deposit/freeze/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/deposit/unfreeze/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/deposit/close/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/deposit/activate/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/deposit/reversal/**")
                            .hasAnyRole("CHECKER", "ADMIN")
                            .requestMatchers("/deposit/statement/**")
                            .hasAnyRole("MAKER", "CHECKER", "ADMIN")
                            .requestMatchers("/loan/apply")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/loan/repayment/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/loan/prepayment/**")
                            .hasAnyRole("MAKER", "ADMIN")
                            .requestMatchers("/audit/**")
                            .hasAnyRole("AUDITOR", "ADMIN")
                            .requestMatchers("/admin/mfa/**")
                            .hasRole("ADMIN")
                            .requestMatchers("/mfa/verify")
                            .authenticated()
                            .requestMatchers("/password/change")
                            .authenticated()
                            .anyRequest()
                            .authenticated();
                })
                // CBS MFA: Register MfaVerificationFilter INSIDE the Spring Security filter chain.
                // Must run AFTER UsernamePasswordAuthenticationFilter so that the session
                // and authentication context are available when the filter checks MFA_VERIFIED.
                // This filter blocks access to all pages except /mfa/verify and /logout
                // while MFA verification is pending (MFA_VERIFIED=false in session).
                .addFilterAfter(new MfaVerificationFilter(), UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form.loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(mfaSuccessHandler)
                        .failureUrl("/login?error")
                        .permitAll())
                .logout(logout -> logout.logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        // CBS Audit: Register LogoutSuccessEventPublishingLogoutHandler to publish
                        // LogoutSuccessEvent via ApplicationEventPublisher. Without this handler,
                        // Spring Security 6.2.x does NOT publish LogoutSuccessEvent by default —
                        // the CbsAuthenticationEventListener.onLogoutSuccess() would be dead code.
                        // Per RBI IT Governance Direction 2023 §8.3: all session lifecycle events
                        // (login, logout, session expiry) must be audited.
                        //
                        // CBS CRITICAL: The handler implements ApplicationEventPublisherAware but
                        // is not a Spring-managed bean (created with new). We must manually inject
                        // the ApplicationEventPublisher — without it, the handler's logout() method
                        // checks if (this.eventPublisher == null) { return; } and silently does nothing.
                        .addLogoutHandler(createLogoutEventHandler())
                        .invalidateHttpSession(true)
                        .deleteCookies("FINVANTA_SESSION")
                        .permitAll())
                .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.sendRedirect(request.getContextPath() + "/error/403");
                }))
                .sessionManagement(session -> session
                        // CBS: Migrate session on login to prevent session fixation attacks (OWASP A2)
                        .sessionFixation().migrateSession()
                        // CBS: invalidSessionUrl is intentionally NOT set.
                        // Per Finacle/Temenos: invalidSessionUrl intercepts ALL requests with stale
                        // session cookies — including intentional redirects after session.invalidate()
                        // in PasswordController (/login?password_changed) and MfaLoginController
                        // (/login?mfa_locked). With invalidSessionUrl set, these redirects get
                        // overridden to /login?timeout — showing the wrong message to the user.
                        // Without it, Spring Security redirects to /login (the configured loginPage)
                        // which is the correct behavior for both timeout and explicit invalidation.
                        // CBS: Only one active session per user per RBI IT Governance Direction 2023 §8.3.
                        // Per Finacle USER_MASTER: concurrent login from a second browser/device
                        // terminates the first session (last-login-wins policy).
                        // expiredUrl: shown when the FIRST session is invalidated by the second login.
                        .maximumSessions(1)
                        .expiredUrl("/login?expired"))
                .csrf(csrf -> {
                    // CBS SECURITY: CSRF enforced on ALL UI endpoints.
                    // /api/v1/** is handled by the stateless API chain (no CSRF needed).
                    // H2 console CSRF bypass only in dev profile.
                    if (isDevProfile()) {
                        csrf.ignoringRequestMatchers("/h2-console/**");
                    }
                })
                .headers(headers -> {
                    // CBS SECURITY: sameOrigin frame options only needed for H2 console in dev.
                    // In production, default DENY is used (no framing allowed).
                    if (isDevProfile()) {
                        headers.frameOptions(frame -> frame.sameOrigin());
                    }
                    // CBS SECURITY: Additional security headers per OWASP and RBI IT Governance.
                    // These headers protect against common web vulnerabilities:
                    //   - X-Content-Type-Options: prevents MIME sniffing attacks
                    //   - X-XSS-Protection: enables browser XSS filter (legacy browsers)
                    //   - Referrer-Policy: prevents leaking internal URLs to external sites
                    //   - Permissions-Policy: disables unnecessary browser features
                    //   - Content-Security-Policy: restricts resource loading to same origin
                    // HSTS (Strict-Transport-Security) is only enabled in production
                    // to avoid HTTPS enforcement issues in dev/test environments.
                    headers.contentTypeOptions(cto -> {}); // X-Content-Type-Options: nosniff
                    headers.xssProtection(xss -> {}); // X-XSS-Protection: 1; mode=block
                    headers.referrerPolicy(ref -> ref.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.permissionsPolicy(pp -> pp.policy("camera=(), microphone=(), geolocation=()"));
                    if (!isDevProfile()) {
                        headers.httpStrictTransportSecurity(
                                hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)); // 1 year
                    }
                });

        return http.build();
    }

    /**
     * Creates a LogoutSuccessEventPublishingLogoutHandler with the ApplicationEventPublisher
     * manually injected. This handler is NOT a Spring-managed bean (created inline in the
     * security filter chain), so Spring's ApplicationEventPublisherAware callback never fires.
     * Without manual injection, the handler silently does nothing on logout.
     */
    private LogoutSuccessEventPublishingLogoutHandler createLogoutEventHandler() {
        LogoutSuccessEventPublishingLogoutHandler handler = new LogoutSuccessEventPublishingLogoutHandler();
        handler.setApplicationEventPublisher(this.eventPublisher);
        return handler;
    }

    /**
     * CORS Configuration for React + Next.js Frontend
     *
     * Per RBI IT Governance Direction 2023 §8.1:
     * - CORS origins must be explicitly whitelisted (no wildcards in production)
     * - Allowed methods restricted to needed HTTP verbs
     * - Credentials not needed (stateless JWT)
     * - Max age 24 hours for preflight caching
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Parse comma-separated allowed origins
        String[] origins = corsAllowedOrigins.split(",");
        config.setAllowedOrigins(java.util.Arrays.stream(origins)
            .map(String::trim)
            .toList());

        // Allowed HTTP methods for React frontend
        config.setAllowedMethods(java.util.Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Allowed headers
        config.setAllowedHeaders(java.util.Arrays.asList(
            "Content-Type",
            "Authorization",        // JWT token
            "X-Tenant-Id",         // Tenant context
            "X-Request-ID",        // Request tracing
            "X-Client-Version",    // Client version
            "Accept",
            "Accept-Language",
            "X-CSRF-Token"));      // For forms

        // Exposed headers (visible to JavaScript)
        config.setExposedHeaders(java.util.Arrays.asList(
            "Authorization",       // New access token
            "X-Request-ID",       // For error reporting
            "X-Total-Count",      // Pagination total
            "X-Total-Pages",      // Pagination pages
            "X-Current-Page",     // Pagination current
            "X-Page-Size"));      // Pagination size

        // Don't allow credentials (stateless JWT)
        config.setAllowCredentials(false);

        // Max age of preflight response (24 hours)
        config.setMaxAge(86400L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Password Encoder for CBS User Authentication
     *
     * Per RBI IT Governance Direction 2023 §8.3:
     * - BCrypt minimum 12 rounds for banking-grade password hashing
     * - Spring Security default is 10 rounds — insufficient for Tier-1 CBS
     * - 12 rounds provides ~4x the computational cost of 10 rounds
     *
     * Uses DelegatingPasswordEncoder to support multiple formats:
     * - {bcrypt} → BCryptPasswordEncoder(12) — production standard
     * - {noop}   → NoOpPasswordEncoder — dev seed data only (NEVER in production)
     *
     * Per Finacle USER_MASTER / Temenos USER: password hashing strength must
     * exceed the minimum recommended by the national banking regulator.
     */
    @Bean
    @SuppressWarnings("deprecation")
    public PasswordEncoder passwordEncoder() {
        java.util.Map<String, PasswordEncoder> encoders = new java.util.HashMap<>();
        encoders.put("bcrypt", new BCryptPasswordEncoder(12));
        encoders.put("noop", NoOpPasswordEncoder.getInstance());
        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("bcrypt", encoders);
        delegating.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder(12));
        return delegating;
    }
}

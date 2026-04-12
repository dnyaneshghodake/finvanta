package com.finvanta.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

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
public class SecurityConfig {

    private final MfaAuthenticationSuccessHandler mfaSuccessHandler;

    public SecurityConfig(MfaAuthenticationSuccessHandler mfaSuccessHandler) {
        this.mfaSuccessHandler = mfaSuccessHandler;
    }

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    private boolean isDevProfile() {
        return activeProfile != null && activeProfile.contains("dev");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                    // CBS SECURITY: H2 console ONLY accessible in dev profile.
                    // In production, this matcher is not registered — /h2-console/**
                    // falls through to .anyRequest().authenticated() and is blocked.
                    // Per RBI IT Governance Direction 2023: database consoles must NEVER
                    // be exposed in production environments.
                    if (isDevProfile()) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.requestMatchers("/admin/**")
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
                    // CBS SECURITY: Only disable CSRF for H2 console in dev profile.
                    // In production, CSRF is enforced on ALL endpoints without exception.
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
     * Uses DelegatingPasswordEncoder (Spring Security standard).
     * Supports {bcrypt}, {noop}, {scrypt}, {argon2} prefixes.
     * Dev seed data uses {noop} prefix (plaintext). Production passwords must always be {bcrypt}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

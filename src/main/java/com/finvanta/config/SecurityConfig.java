package com.finvanta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/WEB-INF/**", "/resources/**", "/css/**", "/js/**", "/fonts/**", "/img/**", "/h2-console/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/batch/**").hasRole("ADMIN")
                .requestMatchers("/branch/add").hasRole("ADMIN")
                .requestMatchers("/customer/add").hasAnyRole("MAKER", "ADMIN")
                .requestMatchers("/customer/edit/**").hasAnyRole("MAKER", "ADMIN")
                .requestMatchers("/customer/deactivate/**").hasRole("ADMIN")
                .requestMatchers("/customer/verify-kyc/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/branch/edit/**").hasRole("ADMIN")
                .requestMatchers("/calendar/**").hasRole("ADMIN")
                .requestMatchers("/loan/verify/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/loan/approve/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/loan/reject/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/loan/create-account/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/loan/disburse/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/loan/write-off/**").hasRole("ADMIN")
                .requestMatchers("/workflow/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/reconciliation/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/reports/**").hasAnyRole("CHECKER", "ADMIN")
                .requestMatchers("/loan/apply").hasAnyRole("MAKER", "ADMIN")
                .requestMatchers("/loan/repayment/**").hasAnyRole("MAKER", "ADMIN")
                .requestMatchers("/loan/prepayment/**").hasAnyRole("MAKER", "ADMIN")
                .requestMatchers("/audit/**").hasAnyRole("AUDITOR", "ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/dashboard", true)
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionFixation().migrateSession()
                .maximumSessions(1)
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/h2-console/**")
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
            );

        return http.build();
    }

    /**
     * Uses DelegatingPasswordEncoder (Spring Security standard).
     * Supports {bcrypt}, {noop}, {scrypt}, {argon2} prefixes.
     * Dev seed data uses {noop} prefix (plaintext). Production passwords must always be {bcrypt}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    }
}

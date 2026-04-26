package com.finvanta.cbs.common.config;

import com.finvanta.cbs.common.interceptor.AuditInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CBS Tier-1 Web MVC Configuration for the refactored architecture.
 *
 * <p>Registers cross-cutting interceptors that apply to ALL endpoints
 * (both legacy v1 and refactored v2 API paths). Per Finacle/Temenos
 * architecture: interceptors handle infrastructure concerns (audit, timing,
 * correlation) while controllers handle business request/response mapping.
 *
 * <p>Interceptor ordering:
 * <ol>
 *   <li>AuditInterceptor -- logs request/response pairs with timing</li>
 * </ol>
 *
 * <p>Note: This configuration supplements the existing {@code WebMvcConfig}
 * and {@code SecurityConfig}. It registers additional interceptors for the
 * refactored modules without modifying the existing security filter chains.
 */
@Configuration
public class CbsSecurityConfig implements WebMvcConfigurer {

    private final AuditInterceptor auditInterceptor;

    public CbsSecurityConfig(AuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns("/api/v2/**")
                .order(1);
    }
}

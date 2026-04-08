package com.finvanta.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CBS Web MVC Configuration.
 *
 * Registers the Hibernate tenant filter interceptor so that every HTTP request
 * automatically enables tenant-scoped query filtering on the Hibernate Session.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final HibernateTenantFilterInterceptor tenantFilterInterceptor;

    public WebMvcConfig(HibernateTenantFilterInterceptor tenantFilterInterceptor) {
        this.tenantFilterInterceptor = tenantFilterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantFilterInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login", "/error/**", "/css/**", "/js/**", "/fonts/**", "/img/**", "/resources/**");
    }
}

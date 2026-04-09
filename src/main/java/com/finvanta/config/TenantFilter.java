package com.finvanta.config;

import com.finvanta.util.TenantContext;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class TenantFilter implements Filter {

    private static final String DEFAULT_TENANT = "DEFAULT";
    private static final String TENANT_SESSION_KEY = "TENANT_ID";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest httpRequest = (HttpServletRequest) request;

            String tenantId = httpRequest.getHeader("X-Tenant-Id");

            if (tenantId == null || tenantId.isBlank()) {
                HttpSession session = httpRequest.getSession(false);
                if (session != null) {
                    Object sessionTenant = session.getAttribute(TENANT_SESSION_KEY);
                    if (sessionTenant != null) {
                        tenantId = sessionTenant.toString();
                    }
                }
            }

            if (tenantId == null || tenantId.isBlank()) {
                tenantId = DEFAULT_TENANT;
            }

            TenantContext.setCurrentTenant(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}

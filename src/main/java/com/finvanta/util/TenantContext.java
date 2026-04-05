package com.finvanta.util;

public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static String getCurrentTenant() {
        String tenant = CURRENT_TENANT.get();
        if (tenant == null) {
            throw new BusinessException("TENANT_NOT_SET", "Tenant context is not initialized");
        }
        return tenant;
    }

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}

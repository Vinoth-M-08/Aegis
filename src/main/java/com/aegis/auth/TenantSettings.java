package com.aegis.auth;

import java.util.List;

import com.aegis.guard.InjectionMode;

/**
 * The editable fields of a tenant.
 */
public record TenantSettings(
        String name,
        boolean active,
        Long dailyTokenLimit,
        Long monthlyTokenLimit,
        Integer requestsPerMinute,
        List<String> allowedModels,
        InjectionMode injectionMode,
        boolean piiRedaction,
        boolean cacheEnabled,
        Double cacheSimilarityThreshold) {

    public static TenantSettings defaults(String name) {
        return new TenantSettings(name, true, null, null, null, List.of(), InjectionMode.BLOCK, true, true, null);
    }

    public static TenantSettings of(Tenant tenant) {
        return new TenantSettings(tenant.name(), tenant.active(), tenant.dailyTokenLimit(), tenant.monthlyTokenLimit(),
                tenant.requestsPerMinute(), tenant.allowedModels(), tenant.injectionMode(), tenant.piiRedaction(),
                tenant.cacheEnabled(), tenant.cacheSimilarityThreshold());
    }
}

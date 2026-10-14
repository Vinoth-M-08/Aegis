package com.aegis.auth;

/**
 * Who is making the current request.
 */
public record TenantContext(Tenant tenant, ApiKey apiKey) {

    /** A model is allowed if both the tenant and the key allow it (empty lists allow all). */
    public boolean allowsModel(String model) {
        return allowedBy(tenant.allowedModels(), model) && allowedBy(apiKey.allowedModels(), model);
    }

    private static boolean allowedBy(java.util.List<String> allowed, String model) {
        return allowed.isEmpty() || allowed.contains(model);
    }
}

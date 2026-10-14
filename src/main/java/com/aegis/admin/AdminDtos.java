package com.aegis.admin;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.aegis.auth.ApiKey;
import com.aegis.auth.Tenant;
import com.aegis.auth.TenantSettings;
import com.aegis.budget.BudgetService;
import com.aegis.guard.InjectionMode;

public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Full tenant settings for create (POST) and replace (PUT). Omitted limits mean "use the
     * gateway default"; omitted switches default to on, and the injection guard to BLOCK.
     */
    public record TenantRequest(
            @NotBlank @Size(max = 100) String name,
            Boolean active,
            @PositiveOrZero Long dailyTokenLimit,
            @PositiveOrZero Long monthlyTokenLimit,
            @PositiveOrZero Integer requestsPerMinute,
            List<String> allowedModels,
            InjectionMode injectionMode,
            Boolean piiRedaction,
            Boolean cacheEnabled,
            @DecimalMin("0") @DecimalMax("1") Double cacheSimilarityThreshold) {

        TenantSettings toSettings() {
            return new TenantSettings(name.trim(), active == null || active, dailyTokenLimit, monthlyTokenLimit,
                    requestsPerMinute, allowedModels == null ? List.of() : clean(allowedModels),
                    injectionMode == null ? InjectionMode.BLOCK : injectionMode,
                    piiRedaction == null || piiRedaction, cacheEnabled == null || cacheEnabled, cacheSimilarityThreshold);
        }
    }

    public record CreateKeyRequest(
            @NotBlank @Size(max = 100) String name,
            List<String> allowedModels,
            @PositiveOrZero Long dailyTokenLimit) {

        List<String> models() {
            return allowedModels == null ? List.of() : clean(allowedModels);
        }
    }

    /** @param secret the plaintext key; returned once and never stored */
    public record CreatedKey(ApiKey key, String secret) {
    }

    public record TenantDetail(Tenant tenant, List<ApiKey> keys, BudgetService.BudgetUsage budget, long cacheEntries) {
    }

    public record Deleted(int deleted) {
    }

    private static List<String> clean(List<String> models) {
        return models.stream().map(String::trim).filter(m -> !m.isEmpty() && !m.contains(",")).distinct().toList();
    }
}

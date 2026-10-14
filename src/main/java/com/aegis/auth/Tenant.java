package com.aegis.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.aegis.guard.InjectionMode;

/**
 * A tenant and its policy. Null limits and thresholds fall back to the gateway defaults.
 */
public record Tenant(
        UUID id,
        String name,
        boolean active,
        Long dailyTokenLimit,
        Long monthlyTokenLimit,
        Integer requestsPerMinute,
        List<String> allowedModels,
        InjectionMode injectionMode,
        boolean piiRedaction,
        boolean cacheEnabled,
        Double cacheSimilarityThreshold,
        Instant createdAt,
        Instant updatedAt) {
}

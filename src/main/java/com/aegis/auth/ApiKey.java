package com.aegis.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stored API key metadata. The key itself is never stored, only its SHA-256 hash.
 */
public record ApiKey(
        UUID id,
        UUID tenantId,
        String name,
        String keyPrefix,
        List<String> allowedModels,
        Long dailyTokenLimit,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt) {

    public boolean revoked() {
        return revokedAt != null;
    }
}

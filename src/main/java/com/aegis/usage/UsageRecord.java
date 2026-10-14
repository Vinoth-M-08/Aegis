package com.aegis.usage;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One gateway request as written to the usage ledger.
 *
 * @param outcome     success, error, rate_limited, budget_exceeded, blocked
 * @param cacheStatus HIT, MISS or BYPASS
 * @param savedUsd    what a cache hit would have cost at the provider
 */
public record UsageRecord(
        String requestId,
        UUID tenantId,
        UUID apiKeyId,
        String model,
        String provider,
        String outcome,
        String cacheStatus,
        boolean streamed,
        long promptTokens,
        long completionTokens,
        BigDecimal costUsd,
        BigDecimal savedUsd,
        long latencyMs,
        String errorCode) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}

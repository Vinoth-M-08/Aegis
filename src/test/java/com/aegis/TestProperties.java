package com.aegis;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.aegis.config.AegisProperties;
import com.aegis.guard.InjectionMode;

/**
 * Builds {@link AegisProperties} with production defaults for unit tests.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static AegisProperties defaults() {
        return with(Map.of());
    }

    public static AegisProperties with(Map<String, AegisProperties.ModelPrice> pricing) {
        return new AegisProperties(
                List.of(provider("test", 1)),
                resilience(),
                new AegisProperties.Budgets(100_000L, null, 60, 512),
                new AegisProperties.Cache(true, 0.92, Duration.ofHours(24),
                        new AegisProperties.Embedding(AegisProperties.EmbeddingType.HASHING, "http://localhost", null,
                                "text-embedding-3-small", Duration.ofSeconds(10))),
                new AegisProperties.Security(true, true, InjectionMode.BLOCK),
                pricing,
                new AegisProperties.Admin(null, List.of()),
                new AegisProperties.Demo(false, "aegis_tenant_demo_key"));
    }

    public static AegisProperties.Resilience resilience() {
        return new AegisProperties.Resilience(2, Duration.ofMillis(1), 50, 4, 2, Duration.ofSeconds(30));
    }

    public static AegisProperties.Provider provider(String name, int priority) {
        return provider(name, priority, List.of(), Map.of());
    }

    public static AegisProperties.Provider provider(String name, int priority, List<String> models,
                                                    Map<String, String> aliases) {
        return new AegisProperties.Provider(name, AegisProperties.ProviderType.OPENAI, "http://localhost", "key",
                priority, Duration.ofSeconds(5), models, aliases, "2024-10-21", Duration.ZERO, 0);
    }
}

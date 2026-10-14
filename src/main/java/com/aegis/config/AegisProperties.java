package com.aegis.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.aegis.guard.InjectionMode;

@Validated
@ConfigurationProperties(prefix = "aegis")
public record AegisProperties(
        @Valid @NotEmpty List<Provider> providers,
        @Valid @DefaultValue Resilience resilience,
        @Valid @DefaultValue Budgets budgets,
        @Valid @DefaultValue Cache cache,
        @Valid @DefaultValue Security security,
        @DefaultValue Map<String, ModelPrice> pricing,
        @Valid @DefaultValue Admin admin,
        @DefaultValue Demo demo) {

    public enum ProviderType {
        /** api.openai.com, or anything else speaking the OpenAI API with a bearer key (Gemini, Ollama, vLLM). */
        OPENAI,
        /** Azure OpenAI deployments: api-key header, deployment in the path. */
        AZURE_OPENAI,
        /** In-process fake for demos and load tests. Never calls the network. */
        MOCK
    }

    /**
     * One upstream LLM provider. Lower {@code priority} values are tried first.
     *
     * @param models       request models this provider serves; empty means all
     * @param modelAliases request model to upstream model (or Azure deployment) name
     */
    public record Provider(
            @NotBlank String name,
            @DefaultValue("openai") ProviderType type,
            String baseUrl,
            String apiKey,
            @DefaultValue("100") int priority,
            @NotNull @DefaultValue("20s") Duration timeout,
            @DefaultValue List<String> models,
            @DefaultValue Map<String, String> modelAliases,
            @DefaultValue("2024-10-21") String apiVersion,
            @DefaultValue("50ms") Duration mockLatency,
            @DefaultValue("0") double mockFailureRate) {

        public boolean serves(String model) {
            return models.isEmpty() || models.contains(model);
        }

        public String upstreamModel(String model) {
            return modelAliases.getOrDefault(model, model);
        }
    }

    public record Resilience(
            @Positive @DefaultValue("2") int maxAttempts,
            @NotNull @DefaultValue("200ms") Duration initialBackoff,
            @DecimalMin("1") @DefaultValue("50") float failureRateThreshold,
            @Positive @DefaultValue("20") int slidingWindowSize,
            @Positive @DefaultValue("5") int minimumNumberOfCalls,
            @NotNull @DefaultValue("30s") Duration openStateDuration) {
    }

    /** Gateway-wide defaults; tenants and keys can override them. Null means unlimited. */
    public record Budgets(
            @DefaultValue("100000") Long defaultDailyTokens,
            Long defaultMonthlyTokens,
            @DefaultValue("60") Integer defaultRequestsPerMinute,
            @Positive @DefaultValue("512") int defaultCompletionTokens) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DecimalMin("0") @DecimalMax("1") @DefaultValue("0.92") double similarityThreshold,
            @NotNull @DefaultValue("24h") Duration ttl,
            @Valid @DefaultValue Embedding embedding) {
    }

    public enum EmbeddingType {
        /** OpenAI-compatible /embeddings endpoint. */
        OPENAI,
        /** Local bag-of-words hashing. No API calls; good enough for demos and tests. */
        HASHING
    }

    public record Embedding(
            @DefaultValue("openai") EmbeddingType type,
            @DefaultValue("https://api.openai.com/v1") String baseUrl,
            String apiKey,
            @DefaultValue("text-embedding-3-small") String model,
            @NotNull @DefaultValue("10s") Duration timeout) {

        /** Fixed by the vector(1536) column in the schema. */
        public static final int DIMENSIONS = 1536;
    }

    public record Security(
            @DefaultValue("true") boolean piiRedaction,
            @DefaultValue("true") boolean restorePii,
            @DefaultValue("block") InjectionMode injectionGuard) {
    }

    /** USD per one million tokens. */
    public record ModelPrice(@NotNull BigDecimal inputPer1m, @NotNull BigDecimal outputPer1m) {
    }

    /**
     * @param token bearer token for /admin; when blank the admin API is disabled
     */
    public record Admin(String token, @DefaultValue List<String> corsOrigins) {

        public boolean enabled() {
            return token != null && !token.isBlank();
        }
    }

    /**
     * @param seed creates a demo tenant with {@code apiKey} on startup if it does not exist
     */
    public record Demo(@DefaultValue("false") boolean seed, @DefaultValue("aegis_tenant_demo_key") String apiKey) {
    }
}

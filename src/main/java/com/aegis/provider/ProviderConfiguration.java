package com.aegis.provider;

import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.aegis.config.AegisProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;

@Configuration
public class ProviderConfiguration {

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    @Bean
    ProviderRouter providerRouter(AegisProperties properties, WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                                  CircuitBreakerRegistry circuitBreakers) {
        List<ProviderRouter.Route> routes = properties.providers().stream()
                .sorted(Comparator.comparingInt(AegisProperties.Provider::priority))
                .map(config -> ProviderRouter.route(createProvider(config, webClientBuilder, objectMapper), config,
                        properties.resilience(), circuitBreakers))
                .toList();
        return new ProviderRouter(routes);
    }

    /** Publishes circuit breaker state and call outcomes as resilience4j_circuitbreaker_* metrics. */
    @Bean
    MeterBinder circuitBreakerMetrics(CircuitBreakerRegistry circuitBreakers) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers);
    }

    private static LlmProvider createProvider(AegisProperties.Provider config, WebClient.Builder webClientBuilder,
                                              ObjectMapper objectMapper) {
        switch (config.type()) {
            case MOCK:
                return new MockProvider(config, objectMapper);
            case AZURE_OPENAI:
                requireConnection(config);
                return new AzureOpenAiProvider(config, webClientBuilder);
            case OPENAI:
            default:
                requireConnection(config);
                return new OpenAiProvider(config, webClientBuilder);
        }
    }

    private static void requireConnection(AegisProperties.Provider config) {
        if (config.baseUrl() == null || config.baseUrl().isBlank()) {
            throw new IllegalStateException("aegis.providers '" + config.name() + "': base-url must be set");
        }
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("aegis.providers '" + config.name() + "': api-key must be set"
                    + " (for the default openai provider, set OPENAI_API_KEY in the environment or .env,"
                    + " or run with SPRING_PROFILES_ACTIVE=demo to use the mock provider)");
        }
    }
}

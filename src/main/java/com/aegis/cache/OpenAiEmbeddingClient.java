package com.aegis.cache;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.aegis.config.AegisProperties;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * Embeddings from an OpenAI-compatible {@code /embeddings} endpoint.
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final AegisProperties.Embedding config;
    private final WebClient webClient;

    public OpenAiEmbeddingClient(AegisProperties.Embedding config, WebClient.Builder builder) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new IllegalStateException("aegis.cache.embedding.api-key must be set for the openai embedding type "
                    + "(or set aegis.cache.embedding.type=hashing, or disable the cache)");
        }
        this.config = config;
        this.webClient = builder.clone()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .build();
    }

    @Override
    public Mono<float[]> embed(String text) {
        return webClient.post()
                .uri("/embeddings")
                .bodyValue(Map.of(
                        "model", config.model(),
                        "input", text,
                        "dimensions", AegisProperties.Embedding.DIMENSIONS))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(config.timeout())
                .map(body -> {
                    JsonNode values = body.path("data").path(0).path("embedding");
                    if (values.size() != AegisProperties.Embedding.DIMENSIONS) {
                        throw new IllegalStateException("Expected " + AegisProperties.Embedding.DIMENSIONS
                                + " embedding dimensions but got " + values.size());
                    }
                    float[] vector = new float[values.size()];
                    for (int i = 0; i < vector.length; i++) {
                        vector[i] = (float) values.get(i).asDouble();
                    }
                    return vector;
                });
    }
}

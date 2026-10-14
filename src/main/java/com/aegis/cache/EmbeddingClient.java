package com.aegis.cache;

import reactor.core.publisher.Mono;

public interface EmbeddingClient {

    /** Returns a vector of {@link com.aegis.config.AegisProperties.Embedding#DIMENSIONS} floats. */
    Mono<float[]> embed(String text);
}

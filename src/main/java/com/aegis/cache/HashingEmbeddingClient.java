package com.aegis.cache;

import java.util.Locale;

import com.aegis.config.AegisProperties;

import reactor.core.publisher.Mono;

/**
 * Bag-of-words feature hashing: each word and word pair is hashed into a signed bucket and the
 * vector is L2-normalized. Prompts that share most of their words land close together. It knows
 * nothing about meaning, so it is meant for demos and tests rather than production.
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSIONS = AegisProperties.Embedding.DIMENSIONS;

    @Override
    public Mono<float[]> embed(String text) {
        return Mono.fromSupplier(() -> vectorize(text));
    }

    static float[] vectorize(String text) {
        float[] vector = new float[DIMENSIONS];
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}<>_]+");
        String previous = null;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            add(vector, word, 1.0f);
            if (previous != null) {
                add(vector, previous + " " + word, 0.5f);
            }
            previous = word;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }

    private static void add(float[] vector, String feature, float weight) {
        int hash = feature.hashCode() * 0x9E3779B1;
        int bucket = Math.floorMod(hash, DIMENSIONS);
        vector[bucket] += (hash & 0x10000) == 0 ? weight : -weight;
    }
}

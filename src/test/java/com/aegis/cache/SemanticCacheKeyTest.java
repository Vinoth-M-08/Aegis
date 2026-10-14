package com.aegis.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aegis.TestProperties;
import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

class SemanticCacheKeyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticCache cache = new SemanticCache(null, new HashingEmbeddingClient(), mapper, TestProperties.defaults());

    @Test
    void systemPromptAndParametersSplitTheScopeButStreamingDoesNot() {
        ChatCompletionRequest base = request("Be brief.", "What is CAP?");
        ChatCompletionRequest otherSystem = request("Be verbose.", "What is CAP?");
        ChatCompletionRequest otherTemperature = request("Be brief.", "What is CAP?");
        otherTemperature.setAdditionalProperty("temperature", 1.5);
        ChatCompletionRequest streamed = request("Be brief.", "What is CAP?");
        streamed.setAdditionalProperty("stream_options", java.util.Map.of("include_usage", true));

        String scope = cache.key(base).scopeHash();
        assertThat(cache.key(otherSystem).scopeHash()).isNotEqualTo(scope);
        assertThat(cache.key(otherTemperature).scopeHash()).isNotEqualTo(scope);
        assertThat(cache.key(streamed).scopeHash()).isEqualTo(scope);
    }

    @Test
    void normalizesConversationText() {
        assertThat(cache.key(request("s", "  What   IS cap? ")).promptText()).isEqualTo("user: what is cap?");
    }

    @Test
    void onlyPlainTextConversationsAreCacheable() throws Exception {
        assertThat(cache.isCacheable(request("s", "hello"))).isTrue();

        ChatCompletionRequest withImage = request("s", "hello");
        withImage.getMessages().get(1).setContent(mapper.readTree(
                "[{\"type\":\"text\",\"text\":\"what is this\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"u\"}}]"));
        assertThat(cache.isCacheable(withImage)).isFalse();

        ChatCompletionRequest withToolCalls = request("s", "hello");
        withToolCalls.getMessages().get(1).setAdditionalProperty("tool_calls", List.of());
        assertThat(cache.isCacheable(withToolCalls)).isFalse();
    }

    @Test
    void hashingEmbeddingsRankSimilarPromptsCloser() {
        float[] a = HashingEmbeddingClient.vectorize("Summarize the CAP theorem in two lines");
        float[] b = HashingEmbeddingClient.vectorize("summarize the CAP theorem in 2 lines");
        float[] c = HashingEmbeddingClient.vectorize("Write a haiku about autumn leaves");

        assertThat(cosine(a, b)).isGreaterThan(0.7);
        assertThat(cosine(a, c)).isLessThan(0.2);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot; // vectors are unit length
    }

    private static ChatCompletionRequest request(String system, String user) {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("gpt-4o-mini");
        request.setMessages(new java.util.ArrayList<>(List.of(message("system", system), message("user", user))));
        return request;
    }

    private static ChatMessage message(String role, String text) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(TextNode.valueOf(text));
        return message;
    }
}

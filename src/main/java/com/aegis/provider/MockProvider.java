package com.aegis.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatCompletionResponse;
import com.aegis.api.dto.ChatMessage;
import com.aegis.api.dto.Usage;
import com.aegis.budget.TokenEstimator;
import com.aegis.config.AegisProperties;
import com.aegis.support.MessageText;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Answers without calling any API, after a configurable delay and with an optional failure rate.
 * Used for demos, local development without keys, failover drills and load tests.
 */
public class MockProvider implements LlmProvider {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final String name;
    private final Duration latency;
    private final double failureRate;
    private final ObjectMapper objectMapper;

    public MockProvider(AegisProperties.Provider config, ObjectMapper objectMapper) {
        this.name = config.name();
        this.latency = config.mockLatency();
        this.failureRate = config.mockFailureRate();
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        return Mono.delay(latency)
                .flatMap(tick -> maybeFail())
                .then(Mono.fromSupplier(() -> response(request)));
    }

    @Override
    public Flux<String> streamChatCompletion(ChatCompletionRequest request) {
        String id = "chatcmpl-mock-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        String answer = answer(request);
        List<String> words = List.of(answer.split("(?<= )"));
        Duration perWord = latency.dividedBy(Math.max(1, words.size()));
        Flux<String> content = Flux.fromIterable(words)
                .delayElements(perWord)
                .map(word -> chunk(id, created, request.getModel(), delta("content", word), null));
        Flux<String> finish = Flux.just(chunk(id, created, request.getModel(), JSON.objectNode(), "stop"));
        Flux<String> usage = includeUsage(request)
                ? Flux.just(usageChunk(id, created, request, answer))
                : Flux.empty();
        return maybeFail().thenMany(Flux.concat(
                Flux.just(chunk(id, created, request.getModel(), delta("role", "assistant"), null)),
                content, finish, usage));
    }

    private Mono<Void> maybeFail() {
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            return Mono.error(new ProviderException(name, 503, "{\"error\":{\"message\":\"Simulated mock failure\"}}"));
        }
        return Mono.empty();
    }

    private ChatCompletionResponse response(ChatCompletionRequest request) {
        String answer = answer(request);
        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setId("chatcmpl-mock-" + UUID.randomUUID());
        response.setObject("chat.completion");
        response.setCreated(Instant.now().getEpochSecond());
        response.setModel(request.getModel());
        ObjectNode message = JSON.objectNode().put("role", "assistant").put("content", answer);
        ArrayNode choices = JSON.arrayNode();
        choices.addObject().put("index", 0).put("finish_reason", "stop").set("message", message);
        response.setChoices(choices);
        response.setUsage(usage(request, answer));
        return response;
    }

    private static String answer(ChatCompletionRequest request) {
        String prompt = "";
        for (ChatMessage message : request.getMessages()) {
            if ("user".equals(message.getRole())) {
                prompt = MessageText.of(message);
            }
        }
        String shortened = prompt.length() > 120 ? prompt.substring(0, 120) + "..." : prompt;
        return "This is a mock response to: " + shortened;
    }

    private static Usage usage(ChatCompletionRequest request, String answer) {
        long promptTokens = 0;
        for (ChatMessage message : request.getMessages()) {
            promptTokens += TokenEstimator.estimateText(MessageText.of(message)) + 4;
        }
        long completionTokens = TokenEstimator.estimateText(answer);
        Usage usage = new Usage();
        usage.setPromptTokens((int) promptTokens);
        usage.setCompletionTokens((int) completionTokens);
        usage.setTotalTokens((int) (promptTokens + completionTokens));
        return usage;
    }

    private static boolean includeUsage(ChatCompletionRequest request) {
        Object options = request.getAdditionalProperties().get("stream_options");
        return options instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("include_usage"));
    }

    private static ObjectNode delta(String field, String value) {
        return JSON.objectNode().put(field, value);
    }

    private String chunk(String id, long created, String model, ObjectNode delta, String finishReason) {
        ObjectNode chunk = JSON.objectNode()
                .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model);
        ObjectNode choice = chunk.putArray("choices").addObject().put("index", 0);
        choice.set("delta", delta);
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return chunk.toString();
    }

    private String usageChunk(String id, long created, ChatCompletionRequest request, String answer) {
        ObjectNode chunk = JSON.objectNode()
                .put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", request.getModel());
        chunk.putArray("choices");
        chunk.set("usage", objectMapper.valueToTree(usage(request, answer)));
        return chunk.toString();
    }
}

package com.aegis.provider;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatCompletionResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Adapter for one upstream LLM API. Implementations translate to and from the
 * OpenAI wire format and signal failures with {@link ProviderException}.
 */
public interface LlmProvider {

    String name();

    Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request);

    /**
     * Streams a completion. Emits the JSON payload of each {@code chat.completion.chunk} event,
     * without the {@code [DONE]} terminator.
     */
    Flux<String> streamChatCompletion(ChatCompletionRequest request);
}

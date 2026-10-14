package com.aegis.provider;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatCompletionResponse;
import com.aegis.config.AegisProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared HTTP plumbing for providers that speak the OpenAI chat completions format.
 */
abstract class HttpChatProvider implements LlmProvider {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {
            };

    protected final String name;
    protected final Duration timeout;
    protected final WebClient webClient;

    HttpChatProvider(AegisProperties.Provider config, WebClient webClient) {
        this.name = config.name();
        this.timeout = config.timeout();
        this.webClient = webClient;
    }

    /** Path (and query) of the chat completions endpoint for an upstream model. */
    protected abstract String chatPath(String model);

    @Override
    public String name() {
        return name;
    }

    @Override
    public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        return send(request)
                .bodyToMono(ChatCompletionResponse.class)
                .timeout(timeout)
                .onErrorMap(this::translate);
    }

    @Override
    public Flux<String> streamChatCompletion(ChatCompletionRequest request) {
        return send(request)
                .bodyToFlux(SSE)
                .timeout(timeout)
                .mapNotNull(ServerSentEvent::data)
                .takeWhile(data -> !"[DONE]".equals(data.trim()))
                .onErrorMap(this::translate);
    }

    private WebClient.ResponseSpec send(ChatCompletionRequest request) {
        return webClient.post()
                .uri(chatPath(request.getModel()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(request.isStreaming() ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new ProviderException(name, response.statusCode().value(), body)));
    }

    private Throwable translate(Throwable error) {
        if (error instanceof TimeoutException) {
            return new ProviderTimeoutException(name, timeout, error);
        }
        if (error instanceof WebClientRequestException) {
            return new ProviderException(name, "Provider '" + name + "' is unreachable", error);
        }
        return error;
    }
}

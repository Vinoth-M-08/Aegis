package com.aegis.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.gateway.ChatGatewayService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatGatewayService gateway;

    public ChatCompletionController(ChatGatewayService gateway) {
        this.gateway = gateway;
    }

    /**
     * Returns JSON, or an SSE stream when {@code stream} is true. The body type differs, so the
     * return type is left open and WebFlux picks the writer from the runtime value.
     */
    @PostMapping(path = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> createChatCompletion(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody ChatCompletionRequest request,
            ServerWebExchange exchange) {
        String requestId = exchange.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (request.isStreaming()) {
            return gateway.stream(authorization, request, requestId)
                    .map(stream -> {
                        Flux<ServerSentEvent<String>> events = stream.events()
                                .map(data -> ServerSentEvent.builder(data).build());
                        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .header(HttpHeaders.CACHE_CONTROL, "no-cache");
                        stream.headers().forEach(builder::header);
                        return builder.body((Object) events);
                    });
        }
        return gateway.complete(authorization, request, requestId)
                .map(result -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON);
                    result.headers().forEach(builder::header);
                    return builder.body((Object) result.body());
                });
    }
}

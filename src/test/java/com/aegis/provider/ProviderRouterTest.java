package com.aegis.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.aegis.TestProperties;
import com.aegis.api.GatewayException;
import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatCompletionResponse;
import com.aegis.api.dto.ChatMessage;
import com.aegis.config.AegisProperties;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProviderRouterTest {

    @Test
    void usesHighestPriorityProviderWhenHealthy() {
        FakeProvider primary = FakeProvider.ok("primary");
        FakeProvider fallback = FakeProvider.ok("fallback");
        ProviderRouter router = router(primary, fallback);

        StepVerifier.create(router.route(request("gpt-4o-mini")))
                .assertNext(routed -> {
                    assertThat(routed.provider()).isEqualTo("primary");
                    assertThat(routed.failedOver()).isFalse();
                })
                .verifyComplete();
        assertThat(fallback.calls.get()).isZero();
    }

    @Test
    void retriesTransientErrorThenFailsOver() {
        FakeProvider primary = FakeProvider.failing("primary", 503);
        FakeProvider fallback = FakeProvider.ok("fallback");
        ProviderRouter router = router(primary, fallback);

        StepVerifier.create(router.route(request("gpt-4o-mini")))
                .assertNext(routed -> {
                    assertThat(routed.provider()).isEqualTo("fallback");
                    assertThat(routed.failedOver()).isTrue();
                })
                .verifyComplete();
        assertThat(primary.calls.get()).isEqualTo(2); // maxAttempts = 2
    }

    @Test
    void failsOverOnAuthErrorWithoutRetrying() {
        FakeProvider primary = FakeProvider.failing("primary", 401);
        FakeProvider fallback = FakeProvider.ok("fallback");

        StepVerifier.create(router(primary, fallback).route(request("gpt-4o-mini")))
                .assertNext(routed -> assertThat(routed.provider()).isEqualTo("fallback"))
                .verifyComplete();
        assertThat(primary.calls.get()).isEqualTo(1);
    }

    @Test
    void returnsClientErrorsWithoutFailover() {
        FakeProvider primary = FakeProvider.failing("primary", 400);
        FakeProvider fallback = FakeProvider.ok("fallback");

        StepVerifier.create(router(primary, fallback).route(request("gpt-4o-mini")))
                .expectErrorSatisfies(e -> assertThat(((ProviderException) e).getUpstreamStatus()).isEqualTo(400))
                .verify();
        assertThat(fallback.calls.get()).isZero();
    }

    @Test
    void returnsLastErrorWhenEveryProviderFails() {
        StepVerifier.create(router(FakeProvider.failing("a", 500), FakeProvider.failing("b", 502)).route(request("m")))
                .expectErrorSatisfies(e -> assertThat(((ProviderException) e).getProvider()).isEqualTo("b"))
                .verify();
    }

    @Test
    void openCircuitSkipsProviderEntirely() {
        FakeProvider primary = FakeProvider.failing("primary", 500);
        FakeProvider fallback = FakeProvider.ok("fallback");
        ProviderRouter router = router(primary, fallback);

        for (int i = 0; i < 3; i++) {
            router.route(request("m")).block();
        }
        int callsWhenOpen = primary.calls.get();
        router.route(request("m")).block();

        assertThat(router.health().get(0).circuitState()).isEqualTo(CircuitBreaker.State.OPEN.name());
        assertThat(primary.calls.get()).isEqualTo(callsWhenOpen);
    }

    @Test
    void onlyRoutesToProvidersServingTheModelAndAppliesAliases() {
        FakeProvider openai = FakeProvider.ok("openai");
        FakeProvider gemini = FakeProvider.ok("gemini");
        ProviderRouter router = new ProviderRouter(List.of(
                ProviderRouter.route(openai, TestProperties.provider("openai", 1, List.of("gpt-4o"), Map.of()),
                        TestProperties.resilience()),
                ProviderRouter.route(gemini, TestProperties.provider("gemini", 2, List.of("gpt-4o-mini"),
                        Map.of("gpt-4o-mini", "gemini-2.0-flash")), TestProperties.resilience())));

        StepVerifier.create(router.route(request("gpt-4o-mini")))
                .assertNext(routed -> assertThat(routed.provider()).isEqualTo("gemini"))
                .verifyComplete();
        assertThat(gemini.models).containsExactly("gemini-2.0-flash");
        assertThat(openai.calls.get()).isZero();
    }

    @Test
    void rejectsModelNoProviderServes() {
        ProviderRouter router = new ProviderRouter(List.of(ProviderRouter.route(FakeProvider.ok("openai"),
                TestProperties.provider("openai", 1, List.of("gpt-4o"), Map.of()), TestProperties.resilience())));

        StepVerifier.create(router.route(request("claude")))
                .expectErrorSatisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("model_not_supported"))
                .verify();
    }

    @Test
    void streamFailsOverBeforeFirstChunkAndCallsProviderOnce() {
        FakeProvider primary = FakeProvider.failing("primary", 503);
        FakeProvider fallback = FakeProvider.ok("fallback");

        ProviderRouter.RoutedStream stream = router(primary, fallback).stream(request("m")).block();

        assertThat(stream.provider()).isEqualTo("fallback");
        StepVerifier.create(stream.chunks()).expectNext("{\"n\":1}", "{\"n\":2}").verifyComplete();
        assertThat(fallback.streamSubscriptions.get()).isEqualTo(1);
    }

    private static ProviderRouter router(FakeProvider... providers) {
        List<ProviderRouter.Route> routes = new ArrayList<>();
        for (int i = 0; i < providers.length; i++) {
            AegisProperties.Provider config = TestProperties.provider(providers[i].name(), i + 1);
            routes.add(ProviderRouter.route(providers[i], config, TestProperties.resilience()));
        }
        return new ProviderRouter(routes);
    }

    private static ChatCompletionRequest request(String model) {
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent(TextNode.valueOf("hi"));
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel(model);
        request.setMessages(List.of(message));
        return request;
    }

    static final class FakeProvider implements LlmProvider {

        final String name;
        final Integer failStatus;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger streamSubscriptions = new AtomicInteger();
        final List<String> models = new ArrayList<>();

        private FakeProvider(String name, Integer failStatus) {
            this.name = name;
            this.failStatus = failStatus;
        }

        static FakeProvider ok(String name) {
            return new FakeProvider(name, null);
        }

        static FakeProvider failing(String name, int status) {
            return new FakeProvider(name, status);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
            return Mono.defer(() -> {
                calls.incrementAndGet();
                models.add(request.getModel());
                if (failStatus != null) {
                    return Mono.error(new ProviderException(name, failStatus, ""));
                }
                ChatCompletionResponse response = new ChatCompletionResponse();
                response.setId(name);
                return Mono.just(response);
            });
        }

        @Override
        public Flux<String> streamChatCompletion(ChatCompletionRequest request) {
            return Flux.defer(() -> {
                streamSubscriptions.incrementAndGet();
                if (failStatus != null) {
                    return Flux.error(new ProviderException(name, failStatus, ""));
                }
                return Flux.just("{\"n\":1}", "{\"n\":2}");
            });
        }
    }
}

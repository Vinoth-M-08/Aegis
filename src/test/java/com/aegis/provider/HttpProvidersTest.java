package com.aegis.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.aegis.config.AegisProperties;
import com.fasterxml.jackson.databind.node.TextNode;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.test.StepVerifier;

class HttpProvidersTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void openAiSendsBearerKeyAndParsesResponse() throws Exception {
        server.enqueue(json(200, "{\"id\":\"c1\",\"model\":\"gpt-4o-mini\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}"));
        OpenAiProvider provider = new OpenAiProvider(config(AegisProperties.ProviderType.OPENAI,
                server.url("/v1").toString()), WebClient.builder());

        StepVerifier.create(provider.chatCompletion(request(false)))
                .assertNext(response -> assertThat(response.getUsage().getTotalTokens()).isEqualTo(5))
                .verifyComplete();

        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer secret");
    }

    @Test
    void azureUsesDeploymentPathAndApiKeyHeader() throws Exception {
        server.enqueue(json(200, "{\"id\":\"c1\",\"choices\":[]}"));
        AzureOpenAiProvider provider = new AzureOpenAiProvider(config(AegisProperties.ProviderType.AZURE_OPENAI,
                server.url("/").toString()), WebClient.builder());

        StepVerifier.create(provider.chatCompletion(request(false).withModel("my-deployment")))
                .expectNextCount(1)
                .verifyComplete();

        RecordedRequest sent = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(sent.getPath()).isEqualTo("/openai/deployments/my-deployment/chat/completions?api-version=2024-10-21");
        assertThat(sent.getHeader("api-key")).isEqualTo("secret");
        assertThat(sent.getHeader("Authorization")).isNull();
    }

    @Test
    void streamsServerSentEventsUntilDone() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"a\":1}\n\ndata: {\"a\":2}\n\ndata: [DONE]\n\n"));
        OpenAiProvider provider = new OpenAiProvider(config(AegisProperties.ProviderType.OPENAI,
                server.url("/v1").toString()), WebClient.builder());

        StepVerifier.create(provider.streamChatCompletion(request(true)))
                .expectNext("{\"a\":1}", "{\"a\":2}")
                .verifyComplete();
    }

    @Test
    void mapsErrorStatusToProviderException() {
        server.enqueue(json(429, "{\"error\":{\"message\":\"slow down\"}}"));
        OpenAiProvider provider = new OpenAiProvider(config(AegisProperties.ProviderType.OPENAI,
                server.url("/v1").toString()), WebClient.builder());

        StepVerifier.create(provider.chatCompletion(request(false)))
                .expectErrorSatisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.getUpstreamStatus()).isEqualTo(429);
                    assertThat(pe.isTransient()).isTrue();
                    assertThat(pe.getUpstreamBody()).contains("slow down");
                })
                .verify();
    }

    @Test
    void mapsSlowResponseToTimeout() {
        server.enqueue(json(200, "{}").setHeadersDelay(2, TimeUnit.SECONDS));
        AegisProperties.Provider config = new AegisProperties.Provider("p", AegisProperties.ProviderType.OPENAI,
                server.url("/v1").toString(), "secret", 1, Duration.ofMillis(200), List.of(), Map.of(), "x",
                Duration.ZERO, 0);

        StepVerifier.create(new OpenAiProvider(config, WebClient.builder()).chatCompletion(request(false)))
                .expectError(ProviderTimeoutException.class)
                .verify();
    }

    private static AegisProperties.Provider config(AegisProperties.ProviderType type, String baseUrl) {
        return new AegisProperties.Provider("p", type, baseUrl, "secret", 1, Duration.ofSeconds(5), List.of(), Map.of(),
                "2024-10-21", Duration.ZERO, 0);
    }

    private static ChatCompletionRequest request(boolean stream) {
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent(TextNode.valueOf("hi"));
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of(message));
        request.setStream(stream ? true : null);
        return request;
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}

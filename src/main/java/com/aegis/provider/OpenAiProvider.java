package com.aegis.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import com.aegis.config.AegisProperties;

/**
 * OpenAI, or any server exposing the same API with a bearer key: Gemini's OpenAI-compatible
 * endpoint, Ollama, vLLM, LM Studio and similar.
 */
public class OpenAiProvider extends HttpChatProvider {

    public OpenAiProvider(AegisProperties.Provider config, WebClient.Builder webClientBuilder) {
        super(config, webClientBuilder.clone()
                .baseUrl(config.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .build());
    }

    @Override
    protected String chatPath(String model) {
        return "/chat/completions";
    }
}

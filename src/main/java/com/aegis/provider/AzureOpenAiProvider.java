package com.aegis.provider;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import com.aegis.config.AegisProperties;

/**
 * Azure OpenAI. The upstream model name (after {@code model-aliases}) is used as the deployment
 * name, and {@code base-url} is the resource endpoint, e.g. {@code https://my-resource.openai.azure.com}.
 */
public class AzureOpenAiProvider extends HttpChatProvider {

    private final String apiVersion;

    public AzureOpenAiProvider(AegisProperties.Provider config, WebClient.Builder webClientBuilder) {
        super(config, webClientBuilder.clone()
                .baseUrl(config.baseUrl())
                .defaultHeader("api-key", config.apiKey())
                .build());
        this.apiVersion = config.apiVersion();
    }

    @Override
    protected String chatPath(String deployment) {
        return "/openai/deployments/" + UriUtils.encodePathSegment(deployment, java.nio.charset.StandardCharsets.UTF_8)
                + "/chat/completions?api-version=" + UriUtils.encodeQueryParam(apiVersion, java.nio.charset.StandardCharsets.UTF_8);
    }
}

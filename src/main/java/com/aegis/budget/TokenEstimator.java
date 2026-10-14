package com.aegis.budget;

import org.springframework.stereotype.Component;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.aegis.config.AegisProperties;
import com.aegis.support.MessageText;

/**
 * Rough upper estimate of a request's token cost, used for the up-front budget reservation.
 * The reservation is corrected to the provider's real count afterwards, so this only needs
 * to be in the right ballpark (about 4 characters per token for English).
 */
@Component
public class TokenEstimator {

    private static final int CHARS_PER_TOKEN = 4;
    private static final int TOKENS_PER_MESSAGE = 4;

    private final int defaultCompletionTokens;

    public TokenEstimator(AegisProperties properties) {
        this.defaultCompletionTokens = properties.budgets().defaultCompletionTokens();
    }

    public long estimatePrompt(ChatCompletionRequest request) {
        long chars = 0;
        for (ChatMessage message : request.getMessages()) {
            chars += MessageText.of(message).length();
        }
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + (long) TOKENS_PER_MESSAGE * request.getMessages().size();
    }

    public long estimateTotal(ChatCompletionRequest request) {
        long completion = intParam(request, "max_completion_tokens", intParam(request, "max_tokens", defaultCompletionTokens));
        long choices = Math.max(1, intParam(request, "n", 1));
        return estimatePrompt(request) + completion * choices;
    }

    /** Estimates tokens for generated text when a provider does not report usage. */
    public static long estimateText(String text) {
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    private static int intParam(ChatCompletionRequest request, String name, int fallback) {
        Object value = request.getAdditionalProperties().get(name);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }
}

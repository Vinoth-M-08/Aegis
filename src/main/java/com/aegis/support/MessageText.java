package com.aegis.support;

import com.aegis.api.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads the text out of a message whose content is either a string or an array of parts.
 */
public final class MessageText {

    private MessageText() {
    }

    public static String of(ChatMessage message) {
        return of(message.getContent());
    }

    public static String of(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.path("text").asText());
                }
            }
            return sb.toString();
        }
        return "";
    }
}

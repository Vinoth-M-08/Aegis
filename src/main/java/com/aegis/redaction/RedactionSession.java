package com.aegis.redaction;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Placeholder mapping for one request. The same value always gets the same placeholder, and
 * placeholders are numbered in order of appearance, so a cached answer containing
 * {@code <EMAIL_1>} can be restored with a later request's own first email.
 */
public class RedactionSession {

    static final Pattern PLACEHOLDER = Pattern.compile("<(EMAIL|CREDIT_CARD|SSN|AADHAAR|IP_ADDRESS|PHONE)_(\\d+)>");
    /** Longest placeholder we emit, used to hold back partial placeholders while streaming. */
    static final int MAX_PLACEHOLDER_LENGTH = "<CREDIT_CARD_9999>".length();

    private final List<PiiRedactor.Detector> detectors;
    private final Map<String, String> valueToPlaceholder = new HashMap<>();
    private final Map<String, String> placeholderToValue = new HashMap<>();
    private final Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);

    RedactionSession(List<PiiRedactor.Detector> detectors) {
        this.detectors = detectors;
    }

    /** Redacts every message in place. */
    public void redact(ChatCompletionRequest request) {
        for (ChatMessage message : request.getMessages()) {
            message.setContent(redactContent(message.getContent()));
        }
    }

    public String redact(String text) {
        String result = text;
        for (PiiRedactor.Detector detector : detectors) {
            Matcher matcher = detector.pattern().matcher(result);
            StringBuilder sb = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String value = matcher.group();
                if (!detector.validator().test(value)) {
                    continue;
                }
                matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholderFor(detector.type(), value)));
                changed = true;
            }
            if (changed) {
                matcher.appendTail(sb);
                result = sb.toString();
            }
        }
        return result;
    }

    public String restore(String text) {
        if (placeholderToValue.isEmpty() || text.indexOf('<') < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String original = placeholderToValue.get(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(original != null ? original : matcher.group()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Applies {@link #redact(String)} or {@link #restore(String)} to every string value in a JSON tree. */
    public JsonNode redactTree(JsonNode node) {
        return mapStrings(node, true);
    }

    public JsonNode restoreTree(JsonNode node) {
        return mapStrings(node, false);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<PiiType, Integer> counts() {
        return Map.copyOf(counts);
    }

    private JsonNode redactContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return content;
        }
        if (content.isTextual()) {
            return TextNode.valueOf(redact(content.asText()));
        }
        if (content.isArray()) {
            ArrayNode copy = ((ArrayNode) content).deepCopy();
            for (JsonNode part : copy) {
                if (part instanceof ObjectNode obj && "text".equals(obj.path("type").asText()) && obj.path("text").isTextual()) {
                    obj.put("text", redact(obj.get("text").asText()));
                }
            }
            return copy;
        }
        return content;
    }

    private JsonNode mapStrings(JsonNode node, boolean redact) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            return TextNode.valueOf(redact ? redact(text) : restore(text));
        }
        if (node.isArray()) {
            ArrayNode copy = ((ArrayNode) node).arrayNode();
            node.forEach(child -> copy.add(mapStrings(child, redact)));
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).objectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), mapStrings(e.getValue(), redact)));
            return copy;
        }
        return node;
    }

    private String placeholderFor(PiiType type, String value) {
        String existing = valueToPlaceholder.get(value);
        if (existing != null) {
            return existing;
        }
        int n = counts.merge(type, 1, Integer::sum);
        String placeholder = "<" + type.name() + "_" + n + ">";
        valueToPlaceholder.put(value, placeholder);
        placeholderToValue.put(placeholder, value);
        return placeholder;
    }
}

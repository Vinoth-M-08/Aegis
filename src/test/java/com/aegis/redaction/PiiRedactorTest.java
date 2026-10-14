package com.aegis.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aegis.api.dto.ChatCompletionRequest;
import com.aegis.api.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    void replacesEachPiiTypeWithTypedPlaceholders() {
        RedactionSession session = redactor.newSession();

        String redacted = session.redact("Mail jane.doe@example.com or call +1 (415) 555-0132. "
                + "Card 4111 1111 1111 1111, SSN 123-45-6789, Aadhaar 2345 6789 0123, host 10.0.12.7.");

        assertThat(redacted).isEqualTo("Mail <EMAIL_1> or call <PHONE_1>. "
                + "Card <CREDIT_CARD_1>, SSN <SSN_1>, Aadhaar <AADHAAR_1>, host <IP_ADDRESS_1>.");
        assertThat(session.total()).isEqualTo(6);
    }

    @Test
    void reusesPlaceholderForRepeatedValueAndNumbersDistinctOnes() {
        RedactionSession session = redactor.newSession();

        String redacted = session.redact("a@x.io, b@x.io, a@x.io");

        assertThat(redacted).isEqualTo("<EMAIL_1>, <EMAIL_2>, <EMAIL_1>");
        assertThat(session.counts()).containsEntry(PiiType.EMAIL, 2);
    }

    @Test
    void leavesNumbersThatAreNotPiiAlone() {
        RedactionSession session = redactor.newSession();
        String text = "Order 2024-05-01 shipped 3 items for $1,299.99; card 4111 1111 1111 1112 failed Luhn.";

        assertThat(session.redact(text)).isEqualTo(text);
    }

    @Test
    void restoresPlaceholdersAndKeepsUnknownOnes() {
        RedactionSession session = redactor.newSession();
        session.redact("Contact jane@example.com");

        assertThat(session.restore("Sure, I emailed <EMAIL_1> and <EMAIL_7>."))
                .isEqualTo("Sure, I emailed jane@example.com and <EMAIL_7>.");
    }

    @Test
    void redactsStringAndTextPartContentInRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatMessage plain = new ChatMessage();
        plain.setRole("user");
        plain.setContent(TextNode.valueOf("I am bob@corp.com"));
        ChatMessage parts = new ChatMessage();
        parts.setRole("user");
        parts.setContent(mapper.readTree("""
                [{"type":"text","text":"call 9876543210"},{"type":"image_url","image_url":{"url":"https://x/y.png"}}]
                """));
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("gpt-4o-mini");
        request.setMessages(List.of(plain, parts));

        redactor.newSession().redact(request);

        assertThat(plain.getContent().asText()).isEqualTo("I am <EMAIL_1>");
        assertThat(parts.getContent().get(0).get("text").asText()).isEqualTo("call <PHONE_1>");
        assertThat(parts.getContent().get(1).path("image_url").path("url").asText()).isEqualTo("https://x/y.png");
    }
}

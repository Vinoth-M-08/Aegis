package com.aegis.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StreamingRestorerTest {

    @Test
    void restoresPlaceholderSplitAcrossChunks() {
        RedactionSession session = new PiiRedactor().newSession();
        session.redact("my email is jane@example.com");
        StreamingRestorer restorer = new StreamingRestorer(session);

        StringBuilder out = new StringBuilder();
        for (String chunk : new String[] {"I'll write to ", "<EM", "AIL", "_1> to", "day. a < b"}) {
            out.append(restorer.accept(chunk));
        }
        out.append(restorer.flush());

        assertThat(out.toString()).isEqualTo("I'll write to jane@example.com today. a < b");
    }

    @Test
    void doesNotHoldBackTextForeverWhenAngleBracketIsNotAPlaceholder() {
        RedactionSession session = new PiiRedactor().newSession();
        session.redact("x@y.io");
        StreamingRestorer restorer = new StreamingRestorer(session);

        assertThat(restorer.accept("if a <")).isEqualTo("if a ");
        assertThat(restorer.accept(" b then this sentence is clearly long enough")).startsWith("< b then");
    }
}

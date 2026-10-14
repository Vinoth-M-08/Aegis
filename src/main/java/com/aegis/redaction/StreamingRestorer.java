package com.aegis.redaction;

/**
 * Restores placeholders in streamed text, where a placeholder may be split across chunks.
 * Text that could be the start of a placeholder is held back until it is complete or clearly
 * is not one.
 */
public class StreamingRestorer {

    private final RedactionSession session;
    private final StringBuilder pending = new StringBuilder();

    public StreamingRestorer(RedactionSession session) {
        this.session = session;
    }

    public String accept(String fragment) {
        pending.append(fragment);
        int open = pending.lastIndexOf("<");
        int cut = pending.length();
        if (open >= 0 && pending.indexOf(">", open) < 0
                && pending.length() - open < RedactionSession.MAX_PLACEHOLDER_LENGTH) {
            cut = open;
        }
        String ready = pending.substring(0, cut);
        pending.delete(0, cut);
        return session.restore(ready);
    }

    public String flush() {
        String rest = session.restore(pending.toString());
        pending.setLength(0);
        return rest;
    }
}

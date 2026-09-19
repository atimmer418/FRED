package com.investingapp.backend.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Builds the quick-reply chip block that the chat UI renders as tappable
 * options. The model appends this block itself on normal replies (see the
 * "Follow-up Questions" rule in {@link FredConstitution#SYSTEM_PROMPT}), but
 * canned server-side replies (the FRED story swaps, safety redirections) bypass
 * the model, so they need the block attached explicitly.
 *
 * Format must match the frontend fence parser in ai-chat.page.ts:
 * <pre>```suggestions\n["..."]\n```</pre>
 */
public final class ChatSuggestions {

    public static final String FENCE = "```suggestions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatSuggestions() {
    }

    /** Returns "\n\n```suggestions\n[...]\n```" for the given chip labels (2-3 expected). */
    public static String block(String... options) {
        try {
            return "\n\n" + FENCE + "\n" + MAPPER.writeValueAsString(List.of(options)) + "\n```";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize suggestion chips", e);
        }
    }
}

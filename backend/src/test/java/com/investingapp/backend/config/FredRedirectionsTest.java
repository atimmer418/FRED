package com.investingapp.backend.config;

import com.investingapp.backend.dto.SafetyReason;
import com.investingapp.backend.dto.SafetyVerdict;
import com.investingapp.backend.service.SafetyService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.investingapp.backend.config.ChipAssertions.*;
import static org.junit.jupiter.api.Assertions.*;

class FredRedirectionsTest {

    @Test
    void everyAdviceRedirectionEndsWithSafeChips() throws Exception {
        for (SafetyReason reason : SafetyReason.values()) {
            if (reason == SafetyReason.TOXIC || reason == SafetyReason.NONE) continue;
            String reply = FredRedirections.get(reason);
            assertTrue(reply.trim().endsWith("```"), reason + " chips must be the last thing in the reply");
            assertWellFormedChips(chips(reply));
        }
    }

    @Test
    void defaultFallbackHasChips() throws Exception {
        // NONE maps to "", so exercise the fallback via a reason not in the map
        String fallback = FredRedirections.RESPONSES.containsKey(SafetyReason.NONE)
                ? FredRedirections.get(SafetyReason.NONE)
                : null;
        assertEquals("", fallback);
        // Reach DEFAULT through reflection-free path: remove-and-restore is
        // not possible on the shared map, so assert via a temporary copy
        java.util.Map<SafetyReason, String> saved = new java.util.EnumMap<>(FredRedirections.RESPONSES);
        try {
            FredRedirections.RESPONSES.remove(SafetyReason.COMPARISON);
            assertWellFormedChips(chips(FredRedirections.get(SafetyReason.COMPARISON)));
        } finally {
            FredRedirections.RESPONSES.putAll(saved);
        }
    }

    @Test
    void toxicRedirectionOffersNoChips() {
        assertFalse(FredRedirections.get(SafetyReason.TOXIC).contains(ChatSuggestions.FENCE));
        assertEquals("", FredRedirections.get(SafetyReason.NONE));
    }

    @Test
    void redirectionBodyTextIsUnchanged() {
        assertTrue(FredRedirections.get(SafetyReason.STOCK_PICKING)
                .startsWith("I can't recommend specific stocks or investments."));
        assertTrue(FredRedirections.get(SafetyReason.MARKET_TIMING)
                .startsWith("I don't predict market movements."));
    }

    @Test
    void chatSuggestionsBlockEscapesJson() throws Exception {
        String block = ChatSuggestions.block("Yes, \"quoted\"", "Back\\slash");
        assertEquals(List.of("Yes, \"quoted\"", "Back\\slash"), chips(block));
        assertTrue(block.startsWith("\n\n```suggestions\n"));
    }
}

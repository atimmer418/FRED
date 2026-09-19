package com.investingapp.backend.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.SafetyVerdict;
import com.investingapp.backend.service.SafetyService;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Shared test helpers for the quick-reply chip block on canned replies. */
public final class ChipAssertions {

    /** Mirrors the fence regex in frontend ai-chat.page.ts parseSegments(). */
    public static final Pattern FENCE = Pattern.compile("```(suggestions)\\s*\\n([\\s\\S]*?)```");

    private ChipAssertions() {
    }

    public static List<String> chips(String reply) throws Exception {
        Matcher m = FENCE.matcher(reply);
        assertTrue(m.find(), "no suggestions fence in: " + reply);
        List<String> chips = new ObjectMapper().readValue(m.group(2), new TypeReference<List<String>>() {});
        assertFalse(m.find(), "more than one suggestions fence");
        return chips;
    }

    public static void assertWellFormedChips(List<String> chips) {
        assertTrue(chips.size() >= 2 && chips.size() <= 3, "expected 2-3 chips, got " + chips);
        SafetyService safety = new SafetyService();
        for (String chip : chips) {
            assertTrue(chip.length() <= 40, "chip over 40 chars: " + chip);
            assertEquals(SafetyVerdict.SAFE, safety.check(chip).verdict(),
                    "tapping this chip would re-trigger a safety block: " + chip);
        }
        assertTrue(chips.get(0).startsWith("Yes"), "first chip should be the yes: " + chips.get(0));
    }
}

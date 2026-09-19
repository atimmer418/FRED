package com.investingapp.backend.service;

import com.investingapp.backend.config.ChatSuggestions;
import com.investingapp.backend.config.FredConstitution;
import com.investingapp.backend.config.ChipAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceStoryReplyTest {

    @Test
    void originSwapCarriesStoryFollowUpAndChips() throws Exception {
        String reply = ChatService.canonicalStoryReply(FredConstitution.FRED_STORY_ORIGIN_TRIGGER);
        assertTrue(reply.startsWith(FredConstitution.FRED_STORY_ORIGIN));
        assertTrue(reply.contains("master strategist retirement plan?"));
        List<String> chips = ChipAssertions.chips(reply);
        ChipAssertions.assertWellFormedChips(chips);
        assertEquals("What was your salary?", chips.get(1));
        assertEquals("How could you afford to invest so much?", chips.get(2));
    }

    @Test
    void strategySwapCarriesFollowUpAndChips() throws Exception {
        String reply = ChatService.canonicalStoryReply(FredConstitution.FRED_STORY_STRATEGY_TRIGGER);
        assertTrue(reply.startsWith(FredConstitution.FRED_STORY_STRATEGY));
        assertTrue(reply.indexOf("Disclaimer") < reply.indexOf(ChatSuggestions.FENCE),
                "chips must come after the disclaimer so the fence is the last block");
        ChipAssertions.assertWellFormedChips(ChipAssertions.chips(reply));
    }

    @Test
    void inventedTagVariantsFallBackToOrigin() {
        assertTrue(ChatService.canonicalStoryReply("{{TRIGGER_FRED_STORY}}")
                .startsWith(FredConstitution.FRED_STORY_ORIGIN));
    }
}

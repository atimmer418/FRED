package com.investingapp.backend.config;

import com.investingapp.backend.dto.SafetyReason;
import java.util.Map;
import java.util.EnumMap;

/**
 * Canned replies for messages the {@code SafetyService} blocks. Each
 * non-harmful redirection ends with a follow-up question plus quick-reply
 * chips, matching the shape of every model-written reply, so the user has a
 * forward path instead of a dead end.
 *
 * Chip labels are written to pass {@code SafetyService.check} as SAFE, so
 * tapping one never lands the user on the same redirection again (pinned by
 * FredRedirectionsTest).
 */
public class FredRedirections {

    public static final Map<SafetyReason, String> RESPONSES = new EnumMap<>(SafetyReason.class);

    private static final String DEFAULT = "I can't answer that specific question, but I can help you understand FRED's long-term investing principles."
            + "\n\nWant me to walk you through them?"
            + ChatSuggestions.block(
                    "Yes, walk me through them",
                    "What does FRED stand for?",
                    "Why does automation matter so much?");

    static {
        RESPONSES.put(SafetyReason.STOCK_PICKING,
                "I can't recommend specific stocks or investments. My role is to help you build a system for long-term growth using diversified portfolios, rather than picking individual winners."
                        + "\n\nWant me to explain why diversification usually beats chasing winners?"
                        + ChatSuggestions.block(
                                "Yes, explain it",
                                "What is in FRED's default portfolio?",
                                "How does diversification work?"));

        RESPONSES.put(SafetyReason.MARKET_TIMING,
                "I don't predict market movements. FRED is built on the belief that time in the market beats timing the market. The best strategy is usually staying consistent regardless of short-term volatility."
                        + "\n\nWant to see why staying invested through the dips tends to win?"
                        + ChatSuggestions.block(
                                "Yes, show me",
                                "What should I do during a downturn?",
                                "Show me the historical data"));

        RESPONSES.put(SafetyReason.SPECIFIC_AMOUNT,
                "I can't tell you exactly how much to invest, as that depends on your personal budget. However, FRED recommends automating a consistent amount that you can sustain for the long term."
                        + "\n\nWant me to walk through how to choose a contribution you can actually sustain?"
                        + ChatSuggestions.block(
                                "Yes, walk me through it",
                                "What percentage of income is typical?",
                                "Show me my current schedule"));

        RESPONSES.put(SafetyReason.COMPARISON,
                "I can't provide a personalized comparison against other specific accounts. Generally, FRED prioritizes automation, simplicity, and flexibility compared to traditional retirement accounts that may lock up your funds."
                        + "\n\nWant me to explain how that flexibility works in practice?"
                        + ChatSuggestions.block(
                                "Yes, explain it",
                                "What does liquidity mean here?",
                                "Can I use FRED alongside a 401k?"));

        RESPONSES.put(SafetyReason.LEVERAGE,
                "I can't advise on when to use leverage or SBLOCs. These are advanced tools for accessing liquidity without selling assets, but they carry risks and require a stable, diversified portfolio."
                        + "\n\nWant me to explain what an SBLOC actually is, risks included?"
                        + ChatSuggestions.block(
                                "Yes, explain it",
                                "What are the risks?",
                                "Why does FRED value liquidity?"));

        // Harmful input gets a full stop, no invitation to continue
        RESPONSES.put(SafetyReason.TOXIC,
                "I cannot continue this conversation.");

        RESPONSES.put(SafetyReason.NONE, "");
    }

    public static String get(SafetyReason reason) {
        return RESPONSES.getOrDefault(reason, DEFAULT);
    }
}

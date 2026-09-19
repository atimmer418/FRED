package com.investingapp.backend.service;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase A agentic tools: READ-ONLY lookups Claude can call mid-conversation to
 * answer questions about the asking user's real data.
 *
 * Security invariant: tools are bound to the authenticated request's User —
 * the model never chooses whose data to read, and nothing here mutates state.
 */
@Service
public class FredChatToolsService {

    private static final Logger logger = LoggerFactory.getLogger(FredChatToolsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HISTORY_POINTS = 24;
    private static final int MAX_POSITIONS = 12;

    private final PortfolioDashboardService dashboardService;
    private final InvestmentScheduleService scheduleService;

    public FredChatToolsService(PortfolioDashboardService dashboardService,
            InvestmentScheduleService scheduleService) {
        this.dashboardService = dashboardService;
        this.scheduleService = scheduleService;
    }

    /** Tool definitions + executor bound to one authenticated user. */
    public interface BoundTools {
        List<Tool> definitions();

        String execute(String toolName, JsonValue input);
    }

    public BoundTools forUser(User user) {
        return new BoundTools() {
            @Override
            public List<Tool> definitions() {
                return TOOL_DEFINITIONS;
            }

            @Override
            public String execute(String toolName, JsonValue input) {
                return FredChatToolsService.this.execute(user, toolName, input);
            }
        };
    }

    private static final List<Tool> TOOL_DEFINITIONS = List.of(
            Tool.builder()
                    .name("get_portfolio_summary")
                    .description("Get the user's current portfolio: total value, today's change, cash, "
                            + "total invested, overall gain/loss, and current positions. Use whenever the "
                            + "user asks how their portfolio or investments are doing.")
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(Tool.InputSchema.Properties.builder().build())
                            .build())
                    .build(),
            Tool.builder()
                    .name("get_portfolio_history")
                    .description("Get the user's portfolio value over time (timestamps + values). "
                            + "Great for showing a chart of their actual growth.")
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(Tool.InputSchema.Properties.builder()
                                    .putAdditionalProperty("period", JsonValue.from(Map.of(
                                            "type", "string",
                                            "enum", List.of("1M", "3M", "6M", "1Y", "ALL"),
                                            "description", "Time window for the history series")))
                                    .build())
                            .build())
                    .build(),
            Tool.builder()
                    .name("get_investment_schedule")
                    .description("Get the user's automated investing schedule: amount per run, frequency, "
                            + "next investment date, paused/active status, and monthly streak.")
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(Tool.InputSchema.Properties.builder().build())
                            .build())
                    .build(),
            Tool.builder()
                    .name("get_investor_profile")
                    .description("Get the user's investing setup: age, monthly investment target, pay "
                            + "frequency, time horizon, and risk tolerance. Use for questions about whether "
                            + "their setup/habits fit their goals. NOTE: the app's Freedom tab computes the "
                            + "official freedom estimate. Do not compute a competing freedom date.")
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(Tool.InputSchema.Properties.builder().build())
                            .build())
                    .build());

    private static final List<String> VALID_PERIODS = List.of("1M", "3M", "6M", "1Y", "ALL");

    private String execute(User user, String toolName, JsonValue input) {
        try {
            return switch (toolName) {
                case "get_portfolio_summary" -> portfolioSummary(user);
                case "get_portfolio_history" -> portfolioHistory(user, periodArg(input));
                case "get_investment_schedule" -> investmentSchedule(user);
                case "get_investor_profile" -> investorProfile(user);
                default -> MAPPER.writeValueAsString(Map.of("error", "unknown tool: " + toolName));
            };
        } catch (Exception e) {
            logger.error("Chat tool {} failed for user {}", toolName, user.getId(), e);
            // Deliberately ambiguous: don't coach the model into telling a
            // fully-onboarded user their account isn't set up when Alpaca hiccuped.
            return "{\"available\": false, \"reason\": \"Couldn't fetch this data right now. It may be "
                    + "a temporary issue worth retrying later. Or, if the user hasn't finished setting "
                    + "up their investment account, that could be why.\"}";
        }
    }

    private static String periodArg(JsonValue input) {
        String period = stringArg(input, "period", "3M").toUpperCase();
        return VALID_PERIODS.contains(period) ? period : "3M";
    }

    private String portfolioSummary(User user) throws Exception {
        PortfolioDashboardService.PortfolioDashboardData data = dashboardService.getPortfolioDashboard(user);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("summary", data.summary);
        out.put("totalInvested", data.totalInvested);
        out.put("totalGainLoss", data.totalGainLoss);
        out.put("totalGainLossPercent", data.totalGainLossPercent);
        List<PortfolioDashboardService.Position> positions = data.positions == null ? List.of()
                : data.positions.subList(0, Math.min(data.positions.size(), MAX_POSITIONS));
        out.put("positions", positions);
        return MAPPER.writeValueAsString(out);
    }

    private String portfolioHistory(User user, String period) throws Exception {
        PortfolioDashboardService.PortfolioHistory history = dashboardService.getPortfolioHistoryForPeriod(user, period);

        int n = history.timestamps.size();
        if (n == 0) {
            // The dashboard service swallows fetch errors into an empty series —
            // don't present that as a real (empty) portfolio history.
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", "No history data came back. The account may be new, "
                    + "or the data is temporarily unavailable.");
            return MAPPER.writeValueAsString(out);
        }

        List<Map<String, Object>> points = new ArrayList<>();
        int step = Math.max(1, (int) Math.ceil(n / (double) MAX_HISTORY_POINTS));
        for (int i = 0; i < n; i += step) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", history.timestamps.get(i));
            point.put("value", history.values.get(i));
            points.add(point);
        }
        // Always include the latest point
        if (n > 0 && (n - 1) % step != 0) {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("t", history.timestamps.get(n - 1));
            last.put("value", history.values.get(n - 1));
            points.add(last);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("period", period);
        out.put("points", points);
        if (!history.profitLossPercent.isEmpty()) {
            out.put("periodReturnPercent", history.profitLossPercent.get(history.profitLossPercent.size() - 1));
        }
        return MAPPER.writeValueAsString(out);
    }

    private String investmentSchedule(User user) throws Exception {
        Optional<InvestmentSchedule> schedule = scheduleService.getCurrentSchedule(user);
        Map<String, Object> out = new LinkedHashMap<>();
        if (schedule.isEmpty()) {
            out.put("active", false);
            out.put("note", "No automated investing schedule set up yet.");
        } else {
            InvestmentSchedule s = schedule.get();
            out.put("active", !Boolean.TRUE.equals(s.getIsPaused()));
            out.put("paused", Boolean.TRUE.equals(s.getIsPaused()));
            // Money at 2dp — schedule math carries scale-4 internally, and the
            // model echoes whatever it sees (e.g. "$2054.9900")
            out.put("amountPerRun", money2(s.getInvestmentAmount()));
            out.put("monthlyAmount", money2(s.getMonthlyAmount()));
            out.put("frequency", s.getFrequency());
            out.put("nextInvestmentDate", s.getNextInvestmentDate() != null ? s.getNextInvestmentDate().toString() : null);
            out.put("monthlyStreak", s.getMonthlyStreak());
        }
        return MAPPER.writeValueAsString(out);
    }

    private static BigDecimal money2(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String investorProfile(User user) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("age", user.getAge());
        BigDecimal monthly = user.getMonthlyInvestment() != null ? BigDecimal.valueOf(user.getMonthlyInvestment()) : null;
        out.put("monthlyInvestmentTarget", monthly);
        out.put("payFrequency", user.getPayFrequency());
        out.put("timeHorizon", user.getTimeToFI());
        out.put("riskTolerance", user.getRiskTolerance());
        out.put("note", "The app's Freedom tab holds the official freedom estimate; discuss habits and trajectory, don't compute a competing date.");
        return MAPPER.writeValueAsString(out);
    }

    private static String stringArg(JsonValue input, String key, String fallback) {
        try {
            Object converted = input.convert(Map.class);
            if (converted instanceof Map<?, ?> map) {
                Object v = map.get(key);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}

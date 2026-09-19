package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.investingapp.backend.model.MarketBreakdown;
import com.investingapp.backend.repository.MarketBreakdownRepository;
import com.investingapp.backend.util.MarketNarrativeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates and stores the once-per-month Market Breakdown narrative.
 * Numbers come from Alpaca Market Data (real monthly closes); the narrative
 * comes from one web-search-grounded Claude call and must pass the
 * deterministic compliance gate before it can be stored as GENERATED.
 */
@Service
public class MarketBreakdownService {

    private static final Logger logger = LoggerFactory.getLogger(MarketBreakdownService.class);

    /** FRED default portfolio (see PortfolioService default allocation). */
    static final List<String> PORTFOLIO_SYMBOLS = List.of("VTI", "VXUS", "VBR");
    static final Map<String, Integer> PORTFOLIO_WEIGHTS = Map.of("VTI", 75, "VXUS", 20, "VBR", 5);
    /** Included for "the market" context in the narrative — not in the portfolio. */
    static final String MARKET_CONTEXT_SYMBOL = "SPY";

    private final MarketBreakdownRepository repository;
    private final LLMService llmService;
    private final RestTemplate restTemplate = new RestTemplate();
    // DEVIATION FROM BRIEF (see task-4-report.md): must match the tree-building
    // config used in extractMonthlyReturns' tests, or a live Alpaca response with
    // a round close (e.g. "530.00") would silently lose its trailing zero the
    // same way — see MarketBreakdownServiceReturnsTest.MAPPER for the full why.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    @Value("${alpaca.market.key:}")
    private String marketDataKey;

    @Value("${alpaca.market.secret:}")
    private String marketDataSecret;

    @Value("${alpaca.market.base-url:https://data.alpaca.markets/v2}")
    private String marketDataBaseUrl;

    @Autowired
    public MarketBreakdownService(MarketBreakdownRepository repository, LLMService llmService) {
        this.repository = repository;
        this.llmService = llmService;
    }

    private static List<String> allSymbols() {
        List<String> symbols = new ArrayList<>(PORTFOLIO_SYMBOLS);
        symbols.add(MARKET_CONTEXT_SYMBOL);
        return symbols;
    }

    /**
     * Fetch monthly bars for the target month and the month before it, and
     * compute close-to-close monthly returns. Protected so orchestration tests
     * can stub the HTTP hop.
     */
    protected List<SymbolMonthlyReturn> fetchMonthlyReturns(YearMonth target) {
        YearMonth prior = target.minusMonths(1);
        String url = String.format(
                "%s/stocks/bars?symbols=%s&timeframe=1Month&start=%s&end=%s&limit=1000",
                marketDataBaseUrl, String.join(",", allSymbols()),
                prior.atDay(1), target.atEndOfMonth());

        HttpHeaders headers = new HttpHeaders();
        headers.set("APCA-API-KEY-ID", marketDataKey);
        headers.set("APCA-API-SECRET-KEY", marketDataSecret);
        headers.set("Accept", "application/json");

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new IllegalStateException("Alpaca bars request returned HTTP " + response.getStatusCode());
            }
            return extractMonthlyReturns(objectMapper.readTree(response.getBody()), target, allSymbols());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch monthly bars: " + e.getMessage(), e);
        }
    }

    /**
     * Pure math: month-over-month close returns from an Alpaca multi-symbol
     * 1Month bars response. Throws IllegalStateException when any requested
     * symbol is missing either month's bar — a partial narrative must never
     * generate from partial numbers.
     */
    static List<SymbolMonthlyReturn> extractMonthlyReturns(JsonNode root, YearMonth target,
            List<String> symbols) {
        YearMonth prior = target.minusMonths(1);
        JsonNode bars = root.path("bars");
        List<SymbolMonthlyReturn> out = new ArrayList<>();

        for (String symbol : symbols) {
            JsonNode symbolBars = bars.path(symbol);
            if (!symbolBars.isArray() || symbolBars.isEmpty()) {
                throw new IllegalStateException("No bars returned for symbol " + symbol);
            }
            // Defensive sort by timestamp (ISO strings sort lexicographically)
            List<JsonNode> sorted = new ArrayList<>();
            symbolBars.forEach(sorted::add);
            sorted.sort(Comparator.comparing(b -> b.path("t").asText()));

            BigDecimal priorClose = closeForMonth(sorted, prior);
            BigDecimal endClose = closeForMonth(sorted, target);
            if (priorClose == null || endClose == null
                    || priorClose.compareTo(BigDecimal.ZERO) <= 0
                    || endClose.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Missing monthly close for symbol " + symbol
                        + " (prior=" + priorClose + ", end=" + endClose + ")");
            }
            BigDecimal returnPct = endClose.subtract(priorClose)
                    .divide(priorClose, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP);
            out.add(new SymbolMonthlyReturn(symbol, priorClose, endClose, returnPct));
        }
        return out;
    }

    private static BigDecimal closeForMonth(List<JsonNode> sortedBars, YearMonth month) {
        String prefix = month.toString(); // "2026-06" matches "2026-06-01T04:00:00Z"
        for (JsonNode bar : sortedBars) {
            if (bar.path("t").asText().startsWith(prefix) && bar.has("c")) {
                return new BigDecimal(bar.path("c").asText());
            }
        }
        return null;
    }

    static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    /** Voice + hard compliance rules for the narrative generation call. */
    static String buildSystemPrompt() {
        return """
                You are FRED, a calm, plain-English investing companion for everyday long-term \
                investors. You write the market recap section of FRED's Monthly Market Breakdown email.

                Hard rules (violating any of these makes the output unusable):
                - NEVER recommend any action. No changes to allocation, contributions, or strategy. \
                Never tell the reader to buy, sell, hold, wait, or "consider" anything. You explain what \
                happened; you do not advise.
                - NEVER predict future performance and never imply outcomes are guaranteed.
                - Use ONLY the portfolio numbers provided in the request. Never invent, estimate, or \
                round differently any figure. Facts about news events must come from your web search results.
                - Voice: calm, clear, friendly, jargon-free. If a term like "yield" or "index" is needed, \
                explain it in a few plain words. Never salesy, never urgent.
                - NEVER use em dashes (—) or en dashes (–); they read as machine-written. Write complete sentences instead, or use commas, colons, or parentheses.
                - Output: an HTML fragment using ONLY <p>, <strong>, <em>, <h3>, <ul>, <li>, <br> tags. \
                No other tags, no attributes, no links, no markdown, no code fences.
                """;
    }

    /** The month's real numbers + the grounded task. */
    static String buildUserPrompt(YearMonth month, List<SymbolMonthlyReturn> returns) {
        String label = month.format(MONTH_LABEL);
        StringBuilder numbers = new StringBuilder();
        for (SymbolMonthlyReturn r : returns) {
            Integer weight = PORTFOLIO_WEIGHTS.get(r.symbol);
            String role = weight != null
                    ? weight + "% of the FRED default portfolio"
                    : "S&P 500, market context only (NOT in the portfolio)";
            numbers.append(String.format(Locale.US,
                    "- %s (%s): %s%% for %s (closed the prior month at $%s, %s at $%s)%n",
                    r.symbol, role, r.returnPct, label, r.priorClose, label, r.endClose));
        }
        return String.format(Locale.US, """
                Write the market recap for FRED's Monthly Market Breakdown for %1$s.

                The FRED default portfolio holds three ETFs: VTI (Vanguard Total US Stock Market, 75%%), \
                VXUS (Vanguard Total International Stock, 20%%), VBR (Vanguard Small-Cap Value, 5%%).

                Actual %1$s monthly results (month-end close to month-end close):
                %2$s
                Search the web for the major market-moving events of %1$s (for example Federal Reserve \
                decisions, inflation reports, jobs data, notable earnings themes, and world events) and \
                explain in plain English what happened and why these funds likely moved the way they did.

                Structure exactly:
                <h3>What happened in %1$s</h3> followed by 2-4 short <p> paragraphs, then
                <h3>Why your funds moved</h3> followed by a <ul> with one <li> per portfolio fund \
                (VTI, VXUS, VBR), each mentioning that fund's actual return from the numbers above.

                Length: 300-500 words. Remember: explain only. No advice, no predictions, no invented numbers.
                """, label, numbers);
    }

    static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");

    /**
     * Return the month's breakdown, generating and persisting it if needed.
     * Idempotent: an existing GENERATED row is returned as-is (every recipient
     * gets identical narrative text, and retries never regenerate mid-batch).
     * A FAILED row is regenerated in place. On any failure a FAILED row is
     * persisted and IllegalStateException is thrown — callers (scheduler
     * catch-up runs, manual endpoint) retry by calling again.
     */
    public MarketBreakdown getOrGenerate(YearMonth month) {
        if (!month.isBefore(YearMonth.now(MARKET_ZONE))) {
            throw new IllegalArgumentException(
                    "Market breakdown can only be generated for completed months, got " + month);
        }
        String periodKey = month.toString();
        Optional<MarketBreakdown> existing = repository.findByPeriodKey(periodKey);
        if (existing.isPresent() && MarketBreakdown.STATUS_GENERATED.equals(existing.get().getStatus())) {
            return existing.get();
        }

        MarketBreakdown row = existing.orElseGet(MarketBreakdown::new);
        row.setPeriodKey(periodKey);
        row.setPeriodLabel(month.format(MONTH_LABEL));

        try {
            List<SymbolMonthlyReturn> returns = fetchMonthlyReturns(month);
            row.setEtfReturnsJson(objectMapper.writeValueAsString(returns));

            LLMService.GroundedSummary summary = llmService.generateGroundedMarketSummary(
                    buildSystemPrompt(), buildUserPrompt(month, returns));

            if (summary.sources.isEmpty()) {
                throw new IllegalStateException(
                        "Narrative not grounded: the model performed no web searches. "
                        + "Market facts must come from search results");
            }

            MarketNarrativeValidator.ValidationResult check =
                    MarketNarrativeValidator.validate(summary.text);
            if (!check.valid) {
                throw new IllegalStateException(
                        "Narrative failed compliance validation: " + String.join("; ", check.violations));
            }

            row.setNarrativeHtml(summary.text.trim());
            row.setSourcesJson(objectMapper.writeValueAsString(summary.sources));
            row.setModel(summary.model);
            row.setStatus(MarketBreakdown.STATUS_GENERATED);
            row.setErrorMessage(null);
            logger.info("Market breakdown generated for {} ({} chars, {} sources)",
                    periodKey, row.getNarrativeHtml().length(), summary.sources.size());
            return repository.save(row);
        } catch (Exception e) {
            row.setNarrativeHtml(null); // never leave a rejected/partial narrative sendable
            row.setStatus(MarketBreakdown.STATUS_FAILED);
            row.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().substring(0, Math.min(e.getMessage().length(), 2000)));
            try {
                repository.save(row);
            } catch (Exception persistError) {
                logger.error("Could not persist FAILED market_breakdown row for {}: {}",
                        periodKey, persistError.getMessage());
            }
            throw new IllegalStateException(
                    "Market breakdown generation failed for " + periodKey + ": " + e.getMessage(), e);
        }
    }

    /** Monthly close-to-close result for one symbol. */
    public static class SymbolMonthlyReturn {
        public final String symbol;
        public final BigDecimal priorClose;
        public final BigDecimal endClose;
        public final BigDecimal returnPct;

        public SymbolMonthlyReturn(String symbol, BigDecimal priorClose, BigDecimal endClose,
                BigDecimal returnPct) {
            this.symbol = symbol;
            this.priorClose = priorClose;
            this.endClose = endClose;
            this.returnPct = returnPct;
        }
    }
}

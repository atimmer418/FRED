package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.User;
import com.investingapp.backend.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;

@Service
public class PortfolioDashboardService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioDashboardService.class);

    // Short-TTL cache for parsed Alpaca portfolio-history responses. The dashboard
    // load, chart period switches, and the /performance period rows can all request
    // the same (account, period) window within seconds — fetch once, serve parsed.
    private static final long HISTORY_CACHE_TTL_MS = 60_000;
    private final Map<String, CachedHistory> historyCache = new ConcurrentHashMap<>();

    private record CachedHistory(PortfolioHistory history, long fetchedAt) {
    }

    // Short-TTL single-flight cache for the assembled portfolio dashboard. getPortfolioDashboard()
    // makes four uncached Alpaca round-trips (account summary, real-time positions, 1M history,
    // recent transactions), and the dashboard load, the Monthly Freedom Update /check + /generate,
    // and my-profile can each request the SAME account within a couple of seconds.
    //   - dashboardCache serves repeat callers within the TTL (the same idea as historyCache above).
    //   - dashboardInFlight single-flights CONCURRENT callers onto ONE fetch — the backend analog of
    //     the frontend shareReplay(1) single-flight in PortfolioStoreService. A plain TTL cache alone
    //     would let a simultaneous burst (e.g. tab1 /check + my-profile + tab3) each stampede Alpaca
    //     before the first result populates the cache.
    private static final long DASHBOARD_CACHE_TTL_MS = 60_000;
    private final Map<String, CachedDashboard> dashboardCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PortfolioDashboardData>> dashboardInFlight = new ConcurrentHashMap<>();

    private record CachedDashboard(PortfolioDashboardData data, long fetchedAt) {
    }

    // Broker API credentials (for account management, portfolio data, etc.)
    @Value("${alpaca.api.key}")
    private String alpacaApiKey;

    @Value("${alpaca.api.secret}")
    private String alpacaApiSecret;

    // Market Data API credentials (for real-time stock prices, quotes, etc.)
    @Value("${alpaca.market.key}")
    private String alpacaMarketDataKey;

    @Value("${alpaca.market.secret}")
    private String alpacaMarketDataSecret;

    @Value("${alpaca.broker.base-url:https://broker-api.sandbox.alpaca.markets/v1}")
    private String alpacaBrokerBaseUrl;

    @Value("${alpaca.trading.base-url:https://paper-api.alpaca.markets/v2}")
    private String alpacaTradingBaseUrl;

    @Value("${alpaca.market.base-url:https://data.alpaca.markets/v2}")
    private String alpacaMarketDataBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Cache for company names to avoid repeated lookups
    private final Cache<String, String> companyNameCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    @org.springframework.beans.factory.annotation.Autowired
    private EncryptionService encryptionService;

    public PortfolioDashboardService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Use HTTP Basic authentication for Broker API
        String credentials = alpacaApiKey + ":" + alpacaApiSecret;
        String base64Credentials = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + base64Credentials);

        return headers;
    }

    /**
     * Calculate total invested amount from current positions (sum of cost basis)
     */
    private BigDecimal calculateTotalInvestedFromPositions(List<Position> positions) {
        return positions.stream()
                .map(position -> position.costBasis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get comprehensive portfolio dashboard data for a user
     */
    public PortfolioDashboardData getPortfolioDashboard(User user) {
        if (user.getAlpacaAccountId() == null) {
            throw new IllegalArgumentException("User does not have an Alpaca account");
        }

        String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

        // 1. Fresh cache hit → serve without touching Alpaca.
        CachedDashboard cached = dashboardCache.get(accountId);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < DASHBOARD_CACHE_TTL_MS) {
            logger.debug("Portfolio dashboard cache hit for {}", accountId);
            return cached.data();
        }

        // 2. Single-flight: if another thread is already fetching this account, join its result
        //    instead of firing a second (identical) set of Alpaca calls.
        CompletableFuture<PortfolioDashboardData> myFetch = new CompletableFuture<>();
        CompletableFuture<PortfolioDashboardData> inFlight = dashboardInFlight.putIfAbsent(accountId, myFetch);
        if (inFlight != null) {
            try {
                return inFlight.join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Failed to fetch portfolio data: " + cause.getMessage(), cause);
            }
        }

        // 3. We own the fetch for this account.
        try {
            PortfolioDashboardData data = fetchPortfolioDashboard(user, accountId);
            // Only cache real data — mirror historyCache: the error path caches nothing and retries next call.
            dashboardCache.put(accountId, new CachedDashboard(data, System.currentTimeMillis()));
            myFetch.complete(data);
            return data;
        } catch (RuntimeException e) {
            myFetch.completeExceptionally(e); // joined callers see the same failure; nothing cached → next call retries
            throw e;
        } finally {
            dashboardInFlight.remove(accountId, myFetch);
        }
    }

    /**
     * Uncached assembly of the portfolio dashboard from Alpaca (account summary, real-time
     * positions, history, recent transactions). Extracted so getPortfolioDashboard() can wrap it in
     * the single-flight TTL cache above. Package-private so tests can override it to count fetches.
     */
    PortfolioDashboardData fetchPortfolioDashboard(User user, String accountId) {
        try {
            // Get all required data from Alpaca
            // initialSummary contains buyingPower and last_equity (as portfolioValue)
            AccountSummary initialSummary = getAccountSummary(accountId);

            // Use real-time positions for the dashboard display to ensure accuracy
            List<Position> positions = getRealTimePositions(accountId);

            PortfolioHistory portfolioHistory = getPortfolioHistory(accountId, "1M");
            List<Transaction> recentTransactions = getRecentTransactions(accountId);

            // Calculate totals from positions data instead of account activities
            BigDecimal totalInvested = calculateTotalInvestedFromPositions(positions);

            // Calculate Real-Time Portfolio Value (Equity)
            // Equity = Sum(Position Market Values) + Cash
            // We use Cash (not Buying Power) because Buying Power can include margin
            // leverage
            BigDecimal positionsTotalValue = positions.stream()
                    .map(p -> p.marketValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal realTimeEquity = positionsTotalValue.add(initialSummary.cash);

            // Calculate Today's Change
            // Change = RealTimeEquity - Yesterday's Equity
            // Note: initialSummary.portfolioValue holds 'last_equity' (Yesterday's Close)
            // from getAccountSummary
            BigDecimal yesterdayEquity = initialSummary.portfolioValue;
            BigDecimal todayChange = realTimeEquity.subtract(yesterdayEquity);

            BigDecimal todayChangePercent = BigDecimal.ZERO;
            if (yesterdayEquity.compareTo(BigDecimal.ZERO) > 0) {
                todayChangePercent = todayChange.divide(yesterdayEquity, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }

            // Create Updated Account Summary with Real-Time values
            // Portfolio Value = Positions Only (excluding cash)
            AccountSummary realTimeSummary = new AccountSummary(
                    positionsTotalValue,
                    todayChange,
                    todayChangePercent,
                    initialSummary.buyingPower,
                    initialSummary.cash,
                    initialSummary.withdrawableCash,
                    realTimeEquity);

            // Calculate total gain/loss based on positions' unrealized P&L
            // This ensures consistency with the positions list display
            BigDecimal totalGainLoss = positions.stream()
                    .map(position -> position.unrealizedPL)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate percent of account for each position
            // Total portfolio value for allocation calculation (positions only, excluding
            // cash)
            final BigDecimal totalPositionsValue = positionsTotalValue;
            positions = positions.stream()
                    .map(position -> {
                        BigDecimal percentOfAccount = BigDecimal.ZERO;
                        if (totalPositionsValue.compareTo(BigDecimal.ZERO) > 0) {
                            percentOfAccount = position.marketValue
                                    .divide(totalPositionsValue, 4, RoundingMode.HALF_UP)
                                    .multiply(new BigDecimal("100"));
                        }
                        return new Position(
                                position.symbol,
                                position.name,
                                position.quantity,
                                position.quantityAvailable,
                                position.marketValue,
                                position.costBasis,
                                position.unrealizedPL,
                                position.unrealizedPLPercent,
                                position.currentPrice,
                                position.averageCostBasis,
                                percentOfAccount,
                                position.todayGainLoss,
                                position.todayGainLossPercent);
                    })
                    .collect(java.util.stream.Collectors.toList());

            return new PortfolioDashboardData(
                    realTimeSummary,
                    positions,
                    portfolioHistory,
                    recentTransactions,
                    totalInvested,
                    totalGainLoss);

        } catch (Exception e) {
            logger.error("Error fetching portfolio dashboard data for user {}", user.getId(), e);
            throw new RuntimeException("Failed to fetch portfolio data: " + e.getMessage());
        }
    }

    /**
     * Get portfolio history for a specific time period
     */
    public PortfolioHistory getPortfolioHistoryForPeriod(User user, String period) {
        if (user.getAlpacaAccountId() == null) {
            throw new IllegalArgumentException("User does not have an Alpaca account");
        }

        String accountId = encryptionService.decrypt(user.getAlpacaAccountId());

        try {
            return getPortfolioHistory(accountId, period);
        } catch (Exception e) {
            logger.error("Error fetching portfolio history for user {} with period {}", user.getId(), period, e);
            throw new RuntimeException("Failed to fetch portfolio history: " + e.getMessage());
        }
    }

    /**
     * Compute 1W/1M/3M/6M/YTD/1Y performance period rows for the given user.
     *
     * <p>Fetches one year of portfolio history (to cover all four windows including
     * YTD) and delegates window math to {@link PerformancePeriodCalculator}.
     * Returns an empty list if history retrieval fails — never throws — so the
     * caller's Today and Total rows are always unaffected.
     *
     * @param user      The authenticated user with an Alpaca account
     * @param endValue  Current portfolio equity (same value used by the Total row)
     * @return          Ordered period rows for 1W/1M/3M/6M/YTD/1Y; omits any window
     *                  older than the account's earliest history point
     */
    public List<Map<String, Object>> computePerformancePeriodRows(User user, BigDecimal endValue) {
        try {
            String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
            PortfolioHistory history = getPortfolioHistory(accountId, "1A");
            LocalDate today = LocalDate.now(PerformancePeriodCalculator.MARKET_ZONE);
            return PerformancePeriodCalculator.computePeriods(
                    history.timestamps, history.values, endValue, today);
        } catch (Exception e) {
            logger.warn("Period-window computation failed, returning empty periods: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get account summary (current values, buying power, etc.)
     */
    private AccountSummary getAccountSummary(String accountId) {
        try {
            String url = alpacaBrokerBaseUrl + "/accounts/" + accountId;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Fetching account summary for account: {}", accountId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode accountData = objectMapper.readTree(response.getBody());

                // Parse account data based on actual Alpaca response structure
                BigDecimal lastEquity = parseDecimalSafely(accountData, "last_equity", BigDecimal.ZERO);

                // Get USD specific data if available
                BigDecimal usdEquity = BigDecimal.ZERO;
                if (accountData.has("usd") && accountData.get("usd").has("last_equity")) {
                    usdEquity = parseDecimalSafely(accountData.get("usd"), "last_equity", BigDecimal.ZERO);
                }

                // Use the more specific USD equity if available, otherwise use general
                // last_equity
                BigDecimal portfolioValue = usdEquity.compareTo(BigDecimal.ZERO) > 0 ? usdEquity : lastEquity;

                // Calculate today's change by getting position-level performance
                BigDecimal todayChange = BigDecimal.ZERO;
                BigDecimal todayChangePercent = BigDecimal.ZERO;

                try {
                    // Calculate today's change based on current positions vs yesterday's close
                    BigDecimal[] todayChangeData = calculateIntradayPerformance(accountId, portfolioValue);
                    todayChange = todayChangeData[0];
                    todayChangePercent = todayChangeData[1];
                    logger.info("Calculated intraday performance: {} ({}%)", todayChange, todayChangePercent);
                } catch (Exception e) {
                    logger.warn("Could not calculate intraday performance for account {}: {}", accountId,
                            e.getMessage());
                    // Keep default values of 0
                }

                // Get trading account data (buying power and cash) from Trading API
                // This is more accurate for "cash" than the Broker API response
                TradingAccountData tradingData = getTradingAccountData(accountId);
                BigDecimal buyingPower = tradingData.buyingPower;
                BigDecimal cash = tradingData.cash;
                BigDecimal withdrawableCash = tradingData.withdrawableCash;

                String accountStatus = accountData.has("status") ? accountData.get("status").asText() : "UNKNOWN";
                String currency = accountData.has("currency") ? accountData.get("currency").asText() : "USD";

                logger.info(
                        "Account summary retrieved - Status: {}, Currency: {}, Last Equity: {}, Cash: {}, Withdrawable: {}",
                        accountStatus, currency, portfolioValue, cash, withdrawableCash);

                return new AccountSummary(portfolioValue, todayChange, todayChangePercent, buyingPower, cash,
                        withdrawableCash, portfolioValue);
            } else {
                logger.error("Failed to fetch account summary. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                throw new RuntimeException("Failed to fetch account summary");
            }
        } catch (Exception e) {
            logger.error("Error fetching account summary for account {}", accountId, e);
            throw new RuntimeException("Failed to fetch account summary: " + e.getMessage());
        }
    }

    /**
     * Get trading account data (buying power, cash) from Trading Account endpoint
     */
    private TradingAccountData getTradingAccountData(String accountId) {
        try {
            String url = alpacaBrokerBaseUrl + "/trading/accounts/" + accountId + "/account";
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Fetching trading account data for account: {}", accountId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode accountData = objectMapper.readTree(response.getBody());

                // Parse buying power - use effective_buying_power as it's the most
                // comprehensive
                BigDecimal buyingPower = parseDecimalSafely(accountData, "effective_buying_power", BigDecimal.ZERO);

                // Parse cash from Trading API - this is the source of truth for
                // settled/available cash
                BigDecimal cash = parseDecimalSafely(accountData, "cash", BigDecimal.ZERO);

                // Parse withdrawable cash - this is what can actually be transferred out
                BigDecimal withdrawableCash = parseDecimalSafely(accountData, "cash_withdrawable", BigDecimal.ZERO);

                logger.info("Retrieved trading data - Buying Power: ${}, Cash: ${}, Withdrawable: ${}", buyingPower,
                        cash, withdrawableCash);
                return new TradingAccountData(buyingPower, cash, withdrawableCash);

            } else {
                logger.warn("Failed to fetch trading account data. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error fetching trading account data for {}: {}", accountId, e.getMessage());
        }

        return new TradingAccountData(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // Helper class for trading account data
    private static class TradingAccountData {
        public final BigDecimal buyingPower;
        public final BigDecimal cash;
        public final BigDecimal withdrawableCash;

        public TradingAccountData(BigDecimal buyingPower, BigDecimal cash, BigDecimal withdrawableCash) {
            this.buyingPower = buyingPower;
            this.cash = cash;
            this.withdrawableCash = withdrawableCash;
        }
    }

    /**
     * Get current positions using EOD positions endpoint
     * Uses the /v1/reporting/eod/positions endpoint from Broker API
     */
    private List<Position> getEODPositions(String accountId) {
        try {
            List<Position> positions = null;
            Exception lastException = null;

            // First try: Explicit dates (preferred for production environments)
            // Try to get EOD positions for the most recent trading day
            // We'll try up to 10 days back to account for weekends, holidays, and
            // processing delays
            // Note: EOD data is often 4-5 days behind due to:
            // - Weekends (Sat/Sun - no trading)
            // - Market holidays (e.g., Veterans Day, etc.)
            // - Processing time (available after 4 AM ET next business day)
            for (int daysBack = 1; daysBack <= 10; daysBack++) {
                try {
                    LocalDate targetDate = LocalDate.now().minusDays(daysBack);
                    String asofDate = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

                    // Build URL with explicit asof parameter
                    String url = alpacaBrokerBaseUrl + "/reporting/eod/positions" +
                            "?account_id=" + accountId +
                            "&asof=" + asofDate +
                            "&limit=10000"; // Maximum limit to ensure we get all positions

                    HttpHeaders headers = createAuthHeaders();
                    HttpEntity<Void> entity = new HttpEntity<>(headers);

                    logger.info("Fetching EOD positions for account: {} as of: {} (daysBack: {})", accountId, asofDate,
                            daysBack);
                    ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                    if (response.getStatusCode() == HttpStatus.OK) {
                        positions = parseEODPositionsResponse(response.getBody(), accountId);
                        logger.info("Successfully fetched {} EOD positions for account: {} as of: {}",
                                positions.size(), accountId, asofDate);
                        return positions; // Success, return immediately
                    }
                } catch (HttpClientErrorException e) {
                    lastException = e;
                    if (e.getStatusCode().value() == 422) {
                        logger.warn("Invalid asof date for {} days back ({}): {} - Response: {}", daysBack,
                                LocalDate.now().minusDays(daysBack), e.getMessage(), e.getResponseBodyAsString());
                        // Continue to next date - 422 is expected for invalid dates in sandbox
                    } else if (e.getStatusCode().value() == 401) {
                        logger.error("Authentication failed for EOD positions API: {}", e.getMessage());
                        throw new RuntimeException("Alpaca API authentication failed", e);
                    } else {
                        logger.warn("HTTP {} error for {} days back: {}", e.getStatusCode().value(), daysBack,
                                e.getMessage());
                    }
                } catch (Exception e) {
                    lastException = e;
                    logger.warn("Failed to fetch EOD positions for {} days back: {}", daysBack, e.getMessage());
                }
            }

            // Fallback: Get the default EOD positions without specifying asof parameter
            // This works better in sandbox environments that restrict explicit date
            // specification
            // but still provide the latest available EOD data
            try {
                String url = alpacaBrokerBaseUrl + "/reporting/eod/positions" +
                        "?account_id=" + accountId +
                        "&limit=10000"; // Maximum limit to ensure we get all positions

                HttpHeaders headers = createAuthHeaders();
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                logger.info("Fetching default EOD positions for account: {} (fallback to latest available)", accountId);
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode() == HttpStatus.OK) {
                    positions = parseEODPositionsResponse(response.getBody(), accountId);
                    logger.info("Successfully fetched {} default EOD positions for account: {}",
                            positions.size(), accountId);
                    return positions; // Success, return immediately
                }
            } catch (HttpClientErrorException e) {
                lastException = e;
                if (e.getStatusCode().value() == 401) {
                    logger.error("Authentication failed for EOD positions API: {}", e.getMessage());
                    throw new RuntimeException("Alpaca API authentication failed", e);
                } else {
                    logger.warn("Failed to fetch default EOD positions: {} - {}", e.getStatusCode(), e.getMessage());
                }
            } catch (Exception e) {
                lastException = e;
                logger.warn("Failed to fetch default EOD positions: {}", e.getMessage());
            }

            if (positions == null) {
                logger.warn(
                        "Could not fetch EOD positions for any recent date. Last error: {}. Falling back to current positions API.",
                        lastException != null ? lastException.getMessage() : "Unknown");

                // Fallback to current positions API (real-time positions)
                try {
                    positions = getRealTimePositions(accountId);
                    logger.info("Successfully fetched current positions as fallback for account: {}", accountId);
                } catch (Exception fallbackException) {
                    logger.error("Fallback to current positions also failed: {}", fallbackException.getMessage());

                    // Last resort: return empty positions for sandbox environments
                    logger.warn("Returning empty positions for sandbox environment");
                    return new ArrayList<>();
                }
            }

            return positions;

        } catch (Exception e) {
            logger.error("Error fetching current positions", e);
            throw new RuntimeException("Failed to fetch positions: " + e.getMessage());
        }
    }

    /**
     * Get current positions using the positions API (real-time)
     */
    private List<Position> getRealTimePositions(String accountId) {
        try {
            String url = alpacaBrokerBaseUrl + "/trading/accounts/" + accountId + "/positions";

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Fetching current positions (fallback) for account: {}", accountId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                List<Position> positions = parseCurrentPositionsResponse(response.getBody(), accountId);
                logger.info("Successfully fetched current positions (fallback) for account: {}", accountId);
                return positions;
            } else {
                throw new RuntimeException("Failed to fetch current positions. Status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("Error fetching current positions (fallback) for account {}: {}", accountId, e.getMessage());
            throw new RuntimeException("Failed to fetch current positions: " + e.getMessage(), e);
        }
    }

    /**
     * Parse current positions response (different format than EOD positions)
     */
    private List<Position> parseCurrentPositionsResponse(String responseBody, String accountId) {
        try {
            JsonNode responseArray = objectMapper.readTree(responseBody);
            List<Position> positions = new ArrayList<>();

            if (responseArray.isArray()) {
                for (JsonNode positionNode : responseArray) {
                    String symbol = positionNode.has("symbol") ? positionNode.get("symbol").asText() : "";
                    BigDecimal quantity = parseDecimalSafely(positionNode, "qty", BigDecimal.ZERO);
                    BigDecimal marketValue = parseDecimalSafely(positionNode, "market_value", BigDecimal.ZERO);
                    BigDecimal costBasis = parseDecimalSafely(positionNode, "cost_basis", BigDecimal.ZERO);
                    BigDecimal unrealizedPL = parseDecimalSafely(positionNode, "unrealized_pl", BigDecimal.ZERO);
                    BigDecimal currentPrice = parseDecimalSafely(positionNode, "current_price", BigDecimal.ZERO);
                    BigDecimal quantityAvailable = parseDecimalSafely(positionNode, "qty_available", quantity);

                    // Filter out positions with negligible quantity (dust/rounding errors)
                    // Minimum threshold: 0.001 shares to avoid showing positions like 1e-9 shares
                    BigDecimal minShareThreshold = new BigDecimal("0.001");
                    if (!symbol.isEmpty() && quantity.compareTo(minShareThreshold) >= 0) {
                        // Calculate unrealized P/L percentage
                        BigDecimal unrealizedPLPercent = BigDecimal.ZERO;
                        if (costBasis.compareTo(BigDecimal.ZERO) > 0) {
                            unrealizedPLPercent = unrealizedPL.divide(costBasis, 4, RoundingMode.HALF_UP)
                                    .multiply(BigDecimal.valueOf(100));
                        }

                        // Calculate average cost basis (cost per share)
                        BigDecimal averageCostBasis = BigDecimal.ZERO;
                        if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                            averageCostBasis = costBasis.divide(quantity, 4, RoundingMode.HALF_UP);
                        }

                        // Get company name
                        String name = getCompanyName(symbol);
                        // parse today's gain/loss
                        BigDecimal todayGainLoss = parseDecimalSafely(positionNode, "unrealized_intraday_pl",
                                BigDecimal.ZERO);
                        // parse today's gain/loss percent (usually decimal like 0.015 for 1.5%)
                        // We multiply by 100 to make it a percentage
                        BigDecimal todayGainLossPercent = parseDecimalSafely(positionNode, "unrealized_intraday_plpc",
                                BigDecimal.ZERO)
                                .multiply(new BigDecimal("100"));

                        // percentOfAccount will be calculated later in getPortfolioDashboard
                        Position position = new Position(symbol, name, quantity, quantityAvailable, marketValue,
                                costBasis,
                                unrealizedPL, unrealizedPLPercent, currentPrice, averageCostBasis, BigDecimal.ZERO,
                                todayGainLoss, todayGainLossPercent);
                        positions.add(position);

                        logger.info(
                                "Added position: {} shares ({} available) of {} with market value ${}, avgCost=${}, costBasis=${}",
                                quantity, quantityAvailable, symbol, marketValue, averageCostBasis, costBasis);
                    } else if (!symbol.isEmpty() && quantity.compareTo(BigDecimal.ZERO) > 0) {
                        logger.debug("Filtered out dust position: {} shares of {} (below threshold of {})",
                                quantity, symbol, minShareThreshold);
                    }
                }
            }

            logger.info("Parsed {} positions from current positions API for account {}", positions.size(), accountId);
            return positions;

        } catch (Exception e) {
            logger.error("Error parsing current positions response for account {}: {}", accountId, e.getMessage());
            throw new RuntimeException("Failed to parse current positions: " + e.getMessage(), e);
        }
    }

    /**
     * Parse EOD positions response from the /v1/reporting/eod/positions endpoint
     * 
     * Response format typically includes positions nested under account IDs
     */
    private List<Position> parseEODPositionsResponse(String responseBody, String accountId) {
        try {
            JsonNode responseData = objectMapper.readTree(responseBody);
            List<Position> positions = new ArrayList<>();

            JsonNode positionsNode = responseData.get("positions");
            if (positionsNode != null && positionsNode.isObject()) {
                // The positions object contains account IDs as keys
                for (JsonNode accountPositions : positionsNode) {
                    if (accountPositions.isArray()) {
                        for (JsonNode positionNode : accountPositions) {
                            String symbol = positionNode.has("symbol") ? positionNode.get("symbol").asText() : "";
                            BigDecimal quantity = parseDecimalSafely(positionNode, "qty", BigDecimal.ZERO);
                            BigDecimal marketValue = parseDecimalSafely(positionNode, "market_value", BigDecimal.ZERO);
                            BigDecimal costBasis = parseDecimalSafely(positionNode, "cost_basis", BigDecimal.ZERO);
                            BigDecimal unrealizedPL = parseDecimalSafely(positionNode, "unrealized_pl",
                                    BigDecimal.ZERO);
                            BigDecimal currentPrice = parseDecimalSafely(positionNode, "current_price",
                                    BigDecimal.ZERO);
                            BigDecimal quantityAvailable = parseDecimalSafely(positionNode, "qty_available", quantity);

                            // Filter out positions with negligible quantity (dust/rounding errors)
                            // Minimum threshold: 0.001 shares
                            BigDecimal minShareThreshold = new BigDecimal("0.001");
                            if (symbol.isEmpty() || quantity.compareTo(minShareThreshold) < 0) {
                                if (!symbol.isEmpty() && quantity.compareTo(BigDecimal.ZERO) > 0) {
                                    logger.debug(
                                            "Filtered out dust position from EOD: {} shares of {} (below threshold)",
                                            quantity, symbol);
                                }
                                continue; // Skip this position
                            }

                            // Calculate unrealized P&L percentage
                            BigDecimal unrealizedPLPercent = BigDecimal.ZERO;
                            if (costBasis.compareTo(BigDecimal.ZERO) > 0) {
                                unrealizedPLPercent = unrealizedPL.divide(costBasis, 4, RoundingMode.HALF_UP);
                            }

                            // Calculate average cost basis (cost per share)
                            BigDecimal averageCostBasis = BigDecimal.ZERO;
                            if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                                averageCostBasis = costBasis.divide(quantity, 4, RoundingMode.HALF_UP);
                            }

                            // percentOfAccount will be calculated later in getPortfolioDashboard
                            Position position = new Position(
                                    symbol,
                                    getCompanyName(symbol),
                                    quantity,
                                    quantityAvailable,
                                    marketValue,
                                    costBasis,
                                    unrealizedPL,
                                    unrealizedPLPercent,
                                    currentPrice,
                                    averageCostBasis,
                                    BigDecimal.ZERO,
                                    BigDecimal.ZERO, // todayGainLoss unavailable in EOD
                                    BigDecimal.ZERO // todayGainLossPercent unavailable in EOD
                            );

                            positions.add(position);
                        }
                    }
                }
            }

            logger.info("Retrieved {} positions", positions.size());
            return positions;

        } catch (Exception e) {
            logger.error("Error parsing positions response", e);
            throw new RuntimeException("Failed to parse positions: " + e.getMessage());
        }
    }

    /**
     * Get portfolio value history for charting using Broker API, served from a
     * short-TTL cache so repeat requests (dashboard bundle + performance rows +
     * chart period flips) don't each round-trip to Alpaca.
     */
    private PortfolioHistory getPortfolioHistory(String accountId, String period) {
        String cacheKey = accountId + ":" + period;
        CachedHistory cached = historyCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < HISTORY_CACHE_TTL_MS) {
            logger.debug("Portfolio history cache hit for {}", cacheKey);
            return cached.history();
        }

        PortfolioHistory fresh = fetchPortfolioHistory(accountId, period);
        // Only cache real data — an empty result (error path) should retry next call
        if (fresh != null && !fresh.timestamps.isEmpty()) {
            historyCache.put(cacheKey, new CachedHistory(fresh, System.currentTimeMillis()));
        }
        return fresh;
    }

    /**
     * Fetch portfolio value history for charting using Broker API
     * Uses the /v1/trading/accounts/{account_id}/account/portfolio/history endpoint
     */
    private PortfolioHistory fetchPortfolioHistory(String accountId, String period) {
        try {
            // Map 1Y to 1A as Alpaca uses 1A for "1 Annum/Year"
            String alpacaPeriod = period;
            if ("1Y".equals(period)) {
                alpacaPeriod = "1A";
            }

            // Build URL with account ID for Broker API
            // Use timeframe=1D to get daily data points
            // Removed cashflow_types=NONE to ensure we get proper P/L calculations
            // accounting for deposits
            String url = alpacaBrokerBaseUrl + "/trading/accounts/" + accountId + "/account/portfolio/history" +
                    "?period=" + alpacaPeriod +
                    "&timeframe=1D" +
                    "&intraday_reporting=market_hours";

            HttpHeaders headers = createAuthHeaders(); // Use broker API headers
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Fetching portfolio history for period: {} from URL: {}", period, url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                logger.info("Portfolio history API response: {}", response.getBody());
                JsonNode historyData = objectMapper.readTree(response.getBody());

                List<String> dates = new ArrayList<>();
                List<BigDecimal> values = new ArrayList<>();
                List<BigDecimal> profitLoss = new ArrayList<>();
                List<BigDecimal> profitLossPercent = new ArrayList<>();

                // Parse timestamps, equity arrays, and profit_loss
                JsonNode timestamps = historyData.get("timestamp");
                JsonNode equityValues = historyData.get("equity");
                JsonNode profitLossValues = historyData.get("profit_loss");
                JsonNode profitLossPercentValues = historyData.get("profit_loss_pct");

                if (timestamps != null && timestamps.isArray() &&
                        equityValues != null && equityValues.isArray() &&
                        timestamps.size() == equityValues.size()) {

                    for (int i = 0; i < timestamps.size(); i++) {
                        // Skip entries where equity is null (non-trading days, missing data)
                        JsonNode equityNode = equityValues.get(i);
                        if (equityNode == null || equityNode.isNull()) {
                            continue;
                        }
                        String equityText = equityNode.asText();
                        if ("null".equals(equityText) || equityText.trim().isEmpty()) {
                            continue;
                        }

                        // Convert timestamp to date string (timestamps are in epoch seconds)
                        long epochSeconds = timestamps.get(i).asLong();

                        // Use NY timezone to determine the date, as Alpaca returns UTC timestamps
                        // that might be 1am UTC of the next day for the previous trading day's close.
                        // e.g. 1764637200 is Dec 2 01:00 UTC, which is Dec 1 20:00 NY -> Dec 1
                        LocalDate date = Instant.ofEpochSecond(epochSeconds)
                                .atZone(ZoneId.of("America/New_York"))
                                .toLocalDate();
                        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

                        BigDecimal equity;
                        try {
                            equity = new BigDecimal(equityText);
                        } catch (NumberFormatException e) {
                            logger.warn("Skipping portfolio history entry at index {} with unparseable equity '{}'", i, equityText);
                            continue;
                        }

                        dates.add(dateStr);
                        values.add(equity);

                        // Also parse profit/loss data if available
                        if (profitLossValues != null && profitLossValues.isArray() && i < profitLossValues.size()) {
                            JsonNode plNode = profitLossValues.get(i);
                            if (plNode != null && !plNode.isNull()) {
                                String plText = plNode.asText();
                                if (!"null".equals(plText) && !plText.trim().isEmpty()) {
                                    try {
                                        profitLoss.add(new BigDecimal(plText));
                                    } catch (NumberFormatException e) {
                                        logger.warn("Skipping profit_loss at index {} with unparseable value '{}'", i, plText);
                                    }
                                }
                            }
                        }

                        // Parse profit/loss percent data if available
                        if (profitLossPercentValues != null && profitLossPercentValues.isArray()
                                && i < profitLossPercentValues.size()) {
                            JsonNode plPctNode = profitLossPercentValues.get(i);
                            if (plPctNode != null && !plPctNode.isNull()) {
                                String plPctText = plPctNode.asText();
                                if (!"null".equals(plPctText) && !plPctText.trim().isEmpty()) {
                                    try {
                                        BigDecimal plPct = new BigDecimal(plPctText)
                                                .multiply(new BigDecimal("100"));
                                        profitLossPercent.add(plPct);
                                    } catch (NumberFormatException e) {
                                        logger.warn("Skipping profit_loss_pct at index {} with unparseable value '{}'", i, plPctText);
                                    }
                                }
                            }
                        }
                    }
                }

                logger.info("Retrieved {} portfolio history data points", dates.size());
                return new PortfolioHistory(dates, values, profitLoss, profitLossPercent);

            } else {
                logger.warn("Failed to fetch portfolio history: {}", response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("Error fetching portfolio history", e);
        }

        // Return empty history on error
        return new PortfolioHistory(new ArrayList<>(), new ArrayList<>());
    }

    /**
     * Get recent transactions/trades using correct Alpaca API structure
     */
    private List<Transaction> getRecentTransactions(String accountId) {
        try {
            // Use the activities endpoint with account_id query parameter
            // Include CSD (Cash Settlement/Deposits) to track funding activities
            String url = alpacaBrokerBaseUrl + "/accounts/activities?account_id=" + accountId
                    + "&activity_types=FILL,CSD&limit=50";
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Fetching recent transactions for account: {}", accountId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode transactionsArray = objectMapper.readTree(response.getBody());
                List<Transaction> transactions = new ArrayList<>();

                if (transactionsArray.isArray()) {
                    for (JsonNode transactionNode : transactionsArray) {
                        // Parse according to the structure you provided
                        String id = transactionNode.has("id") ? transactionNode.get("id").asText()
                                : (transactionNode.has("order_id") ? transactionNode.get("order_id").asText() : "");
                        String type = transactionNode.has("activity_type")
                                ? transactionNode.get("activity_type").asText()
                                : (transactionNode.has("type") ? transactionNode.get("type").asText() : "fill");
                        String symbol = transactionNode.has("symbol") ? transactionNode.get("symbol").asText() : "";
                        BigDecimal quantity = parseDecimalSafely(transactionNode, "qty", BigDecimal.ZERO);
                        BigDecimal price = parseDecimalSafely(transactionNode, "price", BigDecimal.ZERO);

                        // Calculate amount: prefer net_amount (handles deposits), fallback to price *
                        // qty
                        BigDecimal amount = parseDecimalSafely(transactionNode, "net_amount", BigDecimal.ZERO);
                        if (amount.compareTo(BigDecimal.ZERO) == 0 && price.compareTo(BigDecimal.ZERO) != 0) {
                            amount = price.multiply(quantity);
                        }

                        // Parse date: prefer transaction_time (ISO), fallback to date (YYYY-MM-DD) for
                        // CSD/ACH
                        String date = "";
                        if (transactionNode.has("transaction_time")
                                && !transactionNode.get("transaction_time").isNull()) {
                            date = transactionNode.get("transaction_time").asText();
                        } else if (transactionNode.has("date") && !transactionNode.get("date").isNull()) {
                            date = transactionNode.get("date").asText();
                        }

                        String status = transactionNode.has("order_status")
                                ? transactionNode.get("order_status").asText()
                                : "filled";

                        transactions.add(new Transaction(id, type, symbol, quantity, price, amount, date, status));
                    }
                }

                logger.info("Retrieved {} recent transactions for account {}", transactions.size(), accountId);
                return transactions;
            } else {
                logger.error("Failed to fetch transactions. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return new ArrayList<>();
            }
        } catch (Exception e) {
            logger.error("Error fetching transactions for account {}", accountId, e);
            return new ArrayList<>();
        }
    }

    /**
     * Safely parse decimal values from JSON, handling null and "null" strings
     */
    private BigDecimal parseDecimalSafely(JsonNode node, String fieldName, BigDecimal defaultValue) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return defaultValue;
        }

        String value = node.get(fieldName).asText();
        if ("null".equals(value) || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse decimal value '{}' for field '{}', using default: {}",
                    value, fieldName, defaultValue);
            return defaultValue;
        }
    }

    // DTOs for response structure

    public static class PortfolioDashboardData {
        public final AccountSummary summary;
        public final List<Position> positions;
        public final PortfolioHistory history;
        public final List<Transaction> recentTransactions;
        public final BigDecimal totalInvested;
        public final BigDecimal totalGainLoss;
        public final BigDecimal totalGainLossPercent;

        public PortfolioDashboardData(AccountSummary summary, List<Position> positions,
                PortfolioHistory history, List<Transaction> recentTransactions,
                BigDecimal totalInvested, BigDecimal totalGainLoss) {
            this.summary = summary;
            this.positions = positions;
            this.history = history;
            this.recentTransactions = recentTransactions;
            this.totalInvested = totalInvested;
            this.totalGainLoss = totalGainLoss;

            // Calculate percentage gain/loss
            this.totalGainLossPercent = totalInvested.compareTo(BigDecimal.ZERO) > 0
                    ? totalGainLoss.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                    : BigDecimal.ZERO;
        }
    }

    public static class AccountSummary {
        public final BigDecimal portfolioValue;
        public final BigDecimal todayChange;
        public final BigDecimal todayChangePercent;
        public final BigDecimal buyingPower;
        public final BigDecimal cash;
        public final BigDecimal withdrawableCash;
        public final BigDecimal equity;

        public AccountSummary(BigDecimal portfolioValue, BigDecimal todayChange,
                BigDecimal todayChangePercent, BigDecimal buyingPower, BigDecimal cash,
                BigDecimal withdrawableCash, BigDecimal equity) {
            this.portfolioValue = portfolioValue;
            this.todayChange = todayChange;
            this.todayChangePercent = todayChangePercent;
            this.buyingPower = buyingPower;
            this.cash = cash;
            this.withdrawableCash = withdrawableCash;
            this.equity = equity;
        }
    }

    public static class Position {
        public final String symbol;
        public final String name;
        public final BigDecimal quantity;
        public final BigDecimal quantityAvailable;
        public final BigDecimal marketValue;
        public final BigDecimal costBasis;
        public final BigDecimal unrealizedPL;
        public final BigDecimal unrealizedPLPercent;
        public final BigDecimal currentPrice;
        public final BigDecimal averageCostBasis;
        public final BigDecimal percentOfAccount;
        public final BigDecimal todayGainLoss;
        public final BigDecimal todayGainLossPercent;

        public Position(String symbol, String name, BigDecimal quantity, BigDecimal quantityAvailable,
                BigDecimal marketValue,
                BigDecimal costBasis, BigDecimal unrealizedPL, BigDecimal unrealizedPLPercent,
                BigDecimal currentPrice, BigDecimal averageCostBasis, BigDecimal percentOfAccount,
                BigDecimal todayGainLoss, BigDecimal todayGainLossPercent) {
            this.symbol = symbol;
            this.name = name;
            this.quantity = quantity;
            this.quantityAvailable = quantityAvailable;
            this.marketValue = marketValue;
            this.costBasis = costBasis;
            this.unrealizedPL = unrealizedPL;
            this.unrealizedPLPercent = unrealizedPLPercent;
            this.currentPrice = currentPrice;
            this.averageCostBasis = averageCostBasis;
            this.percentOfAccount = percentOfAccount;
            this.todayGainLoss = todayGainLoss;
            this.todayGainLossPercent = todayGainLossPercent;
        }

        // Getters for JSON serialization
        public String getSymbol() {
            return symbol;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getQuantity() {
            return quantity;
        }

        public BigDecimal getQuantityAvailable() {
            return quantityAvailable;
        }

        public BigDecimal getMarketValue() {
            return marketValue;
        }

        public BigDecimal getCostBasis() {
            return costBasis;
        }

        public BigDecimal getUnrealizedPL() {
            return unrealizedPL;
        }

        public BigDecimal getUnrealizedPLPercent() {
            return unrealizedPLPercent;
        }

        public BigDecimal getCurrentPrice() {
            return currentPrice;
        }

        public BigDecimal getAverageCostBasis() {
            return averageCostBasis;
        }

        public BigDecimal getPercentOfAccount() {
            return percentOfAccount;
        }

        public BigDecimal getTodayGainLoss() {
            return todayGainLoss;
        }

        public BigDecimal getTodayGainLossPercent() {
            return todayGainLossPercent;
        }
    }

    public static class PortfolioHistory {
        public final List<String> timestamps;
        public final List<BigDecimal> values;
        public final List<BigDecimal> profitLoss;
        public final List<BigDecimal> profitLossPercent;

        public PortfolioHistory(List<String> timestamps, List<BigDecimal> values) {
            this.timestamps = timestamps;
            this.values = values;
            this.profitLoss = new ArrayList<>();
            this.profitLossPercent = new ArrayList<>();
        }

        public PortfolioHistory(List<String> timestamps, List<BigDecimal> values, List<BigDecimal> profitLoss,
                List<BigDecimal> profitLossPercent) {
            this.timestamps = timestamps;
            this.values = values;
            this.profitLoss = profitLoss;
            this.profitLossPercent = profitLossPercent;
        }
    }

    public static class Transaction {
        public final String id;
        public final String type;
        public final String symbol;
        public final BigDecimal quantity;
        public final BigDecimal price;
        public final BigDecimal amount;
        public final String date;
        public final String status;

        public Transaction(String id, String type, String symbol, BigDecimal quantity,
                BigDecimal price, BigDecimal amount, String date, String status) {
            this.id = id;
            this.type = type;
            this.symbol = symbol;
            this.quantity = quantity;
            this.price = price;
            this.amount = amount;
            this.date = date;
            this.status = status;
        }
    }

    /**
     * Calculate real intraday performance using current market prices vs
     * yesterday's close
     * Gets real-time quotes for each position and calculates actual market movement
     * Returns [todayChange, todayChangePercent]
     */
    private BigDecimal[] calculateIntradayPerformance(String accountId, BigDecimal currentPortfolioValue) {
        try {
            // Get yesterday's EOD positions (quantities and symbols)
            List<Position> yesterdayPositions = getEODPositions(accountId);

            if (yesterdayPositions.isEmpty()) {
                return new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO };
            }

            // Calculate yesterday's total value and prepare for real-time calculation
            BigDecimal yesterdayTotalValue = BigDecimal.ZERO;
            BigDecimal currentCalculatedValue = BigDecimal.ZERO;

            logger.info("Calculating real intraday performance for {} positions", yesterdayPositions.size());

            for (Position position : yesterdayPositions) {
                // Yesterday's value (from EOD data)
                BigDecimal yesterdayValue = position.marketValue;
                yesterdayTotalValue = yesterdayTotalValue.add(yesterdayValue);

                // Get current real-time price for this symbol
                BigDecimal currentPrice = getCurrentPrice(position.symbol);

                if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    // Calculate current value: quantity × current_price
                    BigDecimal currentValue = position.quantity.multiply(currentPrice);
                    currentCalculatedValue = currentCalculatedValue.add(currentValue);

                    logger.info("Position {}: {} shares, Yesterday: ${}, Current price: ${}, Current value: ${}",
                            position.symbol, position.quantity, position.marketValue, currentPrice, currentValue);
                } else {
                    // Fallback to yesterday's value if we can't get current price
                    currentCalculatedValue = currentCalculatedValue.add(yesterdayValue);
                    logger.warn("Could not get current price for {}, using yesterday's value", position.symbol);
                }
            }

            logger.info("Intraday calculation: Yesterday total = ${}, Current calculated = ${}",
                    yesterdayTotalValue, currentCalculatedValue);

            if (yesterdayTotalValue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal todayChange = currentCalculatedValue.subtract(yesterdayTotalValue);
                BigDecimal todayChangePercent = todayChange.divide(yesterdayTotalValue, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                logger.info("Real intraday performance: {} ({}%)", todayChange, todayChangePercent);
                return new BigDecimal[] { todayChange, todayChangePercent };
            }

        } catch (Exception e) {
            logger.error("Error calculating real intraday performance: {}", e.getMessage());
        }

        return new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO };
    }

    /**
     * Get current real-time price for a symbol using Alpaca market data API
     */
    public BigDecimal getCurrentPrice(String symbol) {
        try {
            // Use Alpaca's latest trade endpoint for real-time pricing
            String url = alpacaMarketDataBaseUrl + "/stocks/" + symbol + "/trades/latest";

            HttpHeaders headers = new HttpHeaders();
            // Use dedicated market data API credentials
            headers.set("APCA-API-KEY-ID", alpacaMarketDataKey);
            headers.set("APCA-API-SECRET-KEY", alpacaMarketDataSecret);
            headers.set("Accept", "application/json");

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode responseData = objectMapper.readTree(response.getBody());
                JsonNode trade = responseData.get("trade");

                if (trade != null && trade.has("p")) {
                    // Get the current trade price
                    BigDecimal currentPrice = parseDecimalSafely(trade, "p", BigDecimal.ZERO);

                    if (currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                        logger.debug("Current trade price for {}: ${}", symbol, currentPrice);
                        return currentPrice;
                    }
                }
            } else {
                logger.warn("Failed to get current price for {}: HTTP {}", symbol, response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("Error getting current price for {}: {}", symbol, e.getMessage());
        }

        return BigDecimal.ZERO;
    }

    /**
     * Get current ask price for a symbol using Alpaca market data API
     * Useful for calculating buy quantities
     */
    public BigDecimal getAskPrice(String symbol) {
        try {
            // Use Alpaca's latest quote endpoint
            String url = alpacaMarketDataBaseUrl + "/stocks/" + symbol + "/quotes/latest";

            HttpHeaders headers = new HttpHeaders();
            headers.set("APCA-API-KEY-ID", alpacaMarketDataKey);
            headers.set("APCA-API-SECRET-KEY", alpacaMarketDataSecret);
            headers.set("Accept", "application/json");

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode responseData = objectMapper.readTree(response.getBody());
                JsonNode quote = responseData.get("quote");

                if (quote != null && quote.has("ap")) {
                    // Get the ask price (ap)
                    BigDecimal askPrice = parseDecimalSafely(quote, "ap", BigDecimal.ZERO);

                    if (askPrice.compareTo(BigDecimal.ZERO) > 0) {
                        logger.debug("Current ask price for {}: ${}", symbol, askPrice);
                        return askPrice;
                    }
                }
            } else {
                logger.warn("Failed to get ask price for {}: HTTP {}", symbol, response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Error getting ask price for {}: {}", symbol, e.getMessage());
        }
        
        return BigDecimal.ZERO;
    }

    /**
     * Get company name for a symbol, using cache if available
     */
    private String getCompanyName(String symbol) {
        String cached = companyNameCache.getIfPresent(symbol);
        if (cached != null) {
            return cached;
        }

        try {
            // Try to get asset details from Broker API
            String url = alpacaBrokerBaseUrl + "/assets/" + symbol;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // logger.debug("Fetching company name for symbol: {}", symbol);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode assetNode = objectMapper.readTree(response.getBody());
                if (assetNode.has("name")) {
                    String name = assetNode.get("name").asText();
                    companyNameCache.put(symbol, name);
                    return name;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch company name for {}: {}", symbol, e.getMessage());
        }

        // Fallback to symbol if name fetch fails
        return symbol;
    }
}

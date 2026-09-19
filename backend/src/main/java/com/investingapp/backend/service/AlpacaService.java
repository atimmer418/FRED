package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlpacaService {

    private static final Logger logger = LoggerFactory.getLogger(AlpacaService.class);

    @Value("${alpaca.api.key}")
    private String alpacaApiKey;

    @Value("${alpaca.api.secret}")
    private String alpacaApiSecret;

    @Value("${alpaca.broker.base-url:https://broker-api.sandbox.alpaca.markets/v1}")
    private String alpacaBaseUrl;

    private final RestTemplate restTemplate;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AlpacaService() {
        this.restTemplate = new RestTemplate();

        // Create a custom request factory that supports PATCH
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);

                // Enable PATCH method using reflection
                if ("PATCH".equals(httpMethod)) {
                    try {
                        // Use reflection to set the method field directly
                        java.lang.reflect.Field methodField = java.net.HttpURLConnection.class
                                .getDeclaredField("method");
                        methodField.setAccessible(true);
                        methodField.set(connection, "PATCH");
                    } catch (Exception e) {
                        logger.warn("Could not enable PATCH method via reflection: {}", e.getMessage());
                        throw new java.io.IOException("PATCH method not supported", e);
                    }
                }
            }
        };

        // Bounded timeouts so a hung Alpaca call can never black-hole a
        // scheduler pool thread (TLH pollers share the pool with the
        // funding/trading crons).
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(30000);

        this.restTemplate.setRequestFactory(requestFactory);

        // Create WebClient for PATCH requests (better PATCH support)
        this.webClient = WebClient.builder()
                .baseUrl(alpacaBaseUrl)
                .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get buying power for an account
     */
    public BigDecimal getBuyingPower(String accountId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/account";
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Fetching buying power for account: {}", accountId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode accountData = objectMapper.readTree(response.getBody());
                
                if (accountData.has("effective_buying_power")) {
                    return new BigDecimal(accountData.get("effective_buying_power").asText());
                } else if (accountData.has("buying_power")) {
                    return new BigDecimal(accountData.get("buying_power").asText());
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching buying power for account {}: {}", accountId, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /**
     * Initiate ACH transfer to fund account
     */
    public AlpacaTransferResponse initiateAchTransfer(String accountId, String relationshipId, BigDecimal amount) {
        try {
            String url = alpacaBaseUrl + "/accounts/" + accountId + "/transfers";

            Map<String, Object> request = new HashMap<>();
            request.put("transfer_type", "ach");
            request.put("relationship_id", relationshipId);
            request.put("amount", amount.toString());
            request.put("direction", "INCOMING");

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("Initiating ACH transfer for account {} with amount {}", accountId, amount);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());

                // Parse amount safely
                BigDecimal parsedAmount = amount;
                if (jsonResponse.has("amount") && !jsonResponse.get("amount").isNull()) {
                    String amountStr = jsonResponse.get("amount").asText();
                    if (!"null".equals(amountStr) && !amountStr.trim().isEmpty()) {
                        try {
                            parsedAmount = new BigDecimal(amountStr);
                        } catch (NumberFormatException e) {
                            logger.warn("Failed to parse amount from response: '{}', using original amount: {}",
                                    amountStr, amount);
                        }
                    }
                }

                return new AlpacaTransferResponse(
                        jsonResponse.get("id").asText(),
                        jsonResponse.get("status").asText(),
                        parsedAmount,
                        jsonResponse.get("created_at").asText(),
                        null);
            } else {
                logger.error("Failed to initiate ACH transfer. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return new AlpacaTransferResponse(null, "FAILED", amount, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error initiating ACH transfer for account {}", accountId, e);
            return new AlpacaTransferResponse(null, "FAILED", amount, null, e.getMessage());
        }
    }

    /**
     * Check transfer status by retrieving the account's transfers list and
     * finding the one matching our transfer ID.
     * 
     * API Reference: GET /v1/accounts/{account_id}/transfers?direction=INCOMING
     * 
     * Alpaca transfer statuses:
     *   QUEUED, APPROVAL_PENDING, PENDING, SENT_TO_CLEARING → still in progress
     *   APPROVED → approved but not yet settled
     *   COMPLETE → funds available
     *   REJECTED → transfer denied
     *   CANCELED → client-initiated cancellation
     *   RETURNED → bank issued an ACH return after initial processing
     */
    /**
     * Initial page size for the transfers list lookup.
     * Since transfers are ordered by created_at and we're always checking recent
     * transfers, this will almost always contain the one we're looking for.
     */
    private static final int TRANSFER_LOOKUP_PAGE_SIZE = 20;

    /**
     * Maximum number of pages to search through before giving up.
     * 20 per page × 5 pages = 100 transfers max.
     */
    private static final int TRANSFER_LOOKUP_MAX_PAGES = 5;

    public AlpacaTransferResponse checkTransferStatus(String accountId, String transferId) {
        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Checking transfer status for account {} transfer {}", accountId, transferId);

            // Paginate through the transfers list to find our transfer ID.
            // The list is ordered by created_at, and we're looking for recent transfers,
            // so the first page will almost always contain it.
            int offset = 0;
            for (int page = 0; page < TRANSFER_LOOKUP_MAX_PAGES; page++) {
                String url = alpacaBaseUrl + "/accounts/" + accountId
                        + "/transfers?direction=INCOMING&limit=" + TRANSFER_LOOKUP_PAGE_SIZE
                        + "&offset=" + offset;

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode() != HttpStatus.OK) {
                    logger.error("Failed to fetch transfers. Status: {}, Response: {}",
                            response.getStatusCode(), response.getBody());
                    return new AlpacaTransferResponse(transferId, "UNKNOWN", null, null, null, null,
                            "HTTP " + response.getStatusCode() + ": " + response.getBody());
                }

                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                if (!jsonResponse.isArray() || jsonResponse.isEmpty()) {
                    // No more transfers to check
                    break;
                }

                // Search this page for our transfer
                AlpacaTransferResponse found = findTransferInPage(jsonResponse, transferId);
                if (found != null) {
                    return found;
                }

                // If we got fewer results than the page size, there are no more pages
                if (jsonResponse.size() < TRANSFER_LOOKUP_PAGE_SIZE) {
                    break;
                }

                offset += TRANSFER_LOOKUP_PAGE_SIZE;
            }

            // Transfer ID not found after searching all pages
            logger.warn("Transfer {} not found in transfers list for account {} (searched {} records)",
                    transferId, accountId, offset > 0 ? offset : TRANSFER_LOOKUP_PAGE_SIZE);
            return new AlpacaTransferResponse(transferId, "UNKNOWN", null, null, null, null,
                    "Transfer not found in account transfers list");

        } catch (Exception e) {
            logger.error("Error checking transfer status for transfer {}", transferId, e);
            return new AlpacaTransferResponse(transferId, "ERROR", null, null, null, null, e.getMessage());
        }
    }

    /**
     * Search a single page of transfer results for the given transfer ID.
     * Returns the parsed response if found, null otherwise.
     */
    private AlpacaTransferResponse findTransferInPage(JsonNode transfers, String transferId) {
        for (JsonNode transfer : transfers) {
            if (!transfer.has("id")) {
                continue;
            }

            String id = transfer.get("id").asText();
            if (!transferId.equals(id)) {
                continue;
            }

            // Found our transfer — extract status and metadata
            String status = transfer.has("status") ? transfer.get("status").asText() : "UNKNOWN";
            String createdAt = transfer.has("created_at") ? transfer.get("created_at").asText() : null;
            String updatedAt = transfer.has("updated_at") ? transfer.get("updated_at").asText() : null;
            String reason = transfer.has("reason") && !transfer.get("reason").isNull()
                    ? transfer.get("reason").asText() : null;

            BigDecimal amount = null;
            if (transfer.has("amount") && !transfer.get("amount").isNull()) {
                String amountStr = transfer.get("amount").asText();
                if (!"null".equals(amountStr) && !amountStr.trim().isEmpty()) {
                    try {
                        amount = new BigDecimal(amountStr);
                    } catch (NumberFormatException e) {
                        logger.warn("Failed to parse transfer amount '{}'", amountStr);
                    }
                }
            }

            logger.info("Transfer {} status: {} (reason: {}, updated: {})",
                    transferId, status, reason, updatedAt);

            return new AlpacaTransferResponse(
                    id, status, amount, createdAt, updatedAt, reason, null);
        }

        return null;
    }

    /**
     * Place a market buy order
     */
    public AlpacaOrderResponse placeBuyOrder(String accountId, String symbol, BigDecimal notionalAmount) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders";

            Map<String, Object> request = new HashMap<>();
            request.put("symbol", symbol);
            request.put("notional", notionalAmount.toString());
            request.put("side", "buy");
            request.put("type", "market");
            request.put("time_in_force", "day");

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("Placing buy order for account {} - Symbol: {}, Amount: {}",
                    accountId, symbol, notionalAmount);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                return new AlpacaOrderResponse(
                        jsonResponse.get("id").asText(),
                        symbol,
                        jsonResponse.get("status").asText(),
                        (jsonResponse.has("qty") && !jsonResponse.get("qty").isNull()
                                && !"null".equals(jsonResponse.get("qty").asText()))
                                        ? new BigDecimal(jsonResponse.get("qty").asText())
                                        : null,
                        notionalAmount,
                        (jsonResponse.has("filled_qty") && !jsonResponse.get("filled_qty").isNull()
                                && !"null".equals(jsonResponse.get("filled_qty").asText()))
                                        ? new BigDecimal(jsonResponse.get("filled_qty").asText())
                                        : BigDecimal.ZERO,
                        (jsonResponse.has("filled_avg_price") && !jsonResponse.get("filled_avg_price").isNull()
                                && !"null".equals(jsonResponse.get("filled_avg_price").asText()))
                                        ? new BigDecimal(jsonResponse.get("filled_avg_price").asText())
                                        : null,
                        jsonResponse.get("submitted_at").asText(),
                        null);
            } else {
                logger.error("Failed to place buy order. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return new AlpacaOrderResponse(null, symbol, "FAILED", null, notionalAmount,
                        BigDecimal.ZERO, null, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error placing buy order for account {} - Symbol: {}", accountId, symbol, e);
            return new AlpacaOrderResponse(null, symbol, "FAILED", null, notionalAmount,
                    BigDecimal.ZERO, null, null, e.getMessage());
        }
    }

    /**
     * Check if an asset is fractionable
     */
    public boolean isFractionable(String symbol) {
        try {
            String url = alpacaBaseUrl + "/assets/" + symbol;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode asset = objectMapper.readTree(response.getBody());
                return asset.has("fractionable") && asset.get("fractionable").asBoolean();
            }
        } catch (Exception e) {
            logger.error("Error checking fractionable status for {}", symbol, e);
        }
        return false; // Default to false if check fails
    }

    /** Asset vetting in one call — TLH replacement candidates must be active and tradable. */
    public AssetInfo getAssetInfo(String symbol) {
        try {
            String url = alpacaBaseUrl + "/assets/" + symbol;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode asset = objectMapper.readTree(response.getBody());
                return new AssetInfo(true,
                        "active".equalsIgnoreCase(asset.path("status").asText("")),
                        asset.path("tradable").asBoolean(false),
                        asset.path("fractionable").asBoolean(false));
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return new AssetInfo(false, false, false, false);
        } catch (Exception e) {
            logger.error("Error fetching asset info for {}: {}", symbol, e.getMessage());
        }
        return new AssetInfo(false, false, false, false);
    }

    /** Minimal asset facts for TLH vetting. */
    public static class AssetInfo {
        public final boolean exists;
        public final boolean active;
        public final boolean tradable;
        public final boolean fractionable;

        public AssetInfo(boolean exists, boolean active, boolean tradable, boolean fractionable) {
            this.exists = exists;
            this.active = active;
            this.tradable = tradable;
            this.fractionable = fractionable;
        }
    }

    /**
     * Place a market buy order with quantity (shares)
     */
    public AlpacaOrderResponse placeBuyOrderWithQuantity(String accountId, String symbol, BigDecimal quantity) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders";

            Map<String, Object> request = new HashMap<>();
            request.put("symbol", symbol);
            request.put("qty", quantity.toString());
            request.put("side", "buy");
            request.put("type", "market");
            request.put("time_in_force", "day");

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("Placing quantity buy order for account {} - Symbol: {}, Qty: {}",
                    accountId, symbol, quantity);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                return new AlpacaOrderResponse(
                        jsonResponse.get("id").asText(),
                        symbol,
                        jsonResponse.get("status").asText(),
                        (jsonResponse.has("qty") && !jsonResponse.get("qty").isNull()
                                && !"null".equals(jsonResponse.get("qty").asText()))
                                        ? new BigDecimal(jsonResponse.get("qty").asText())
                                        : quantity,
                        null,
                        (jsonResponse.has("filled_qty") && !jsonResponse.get("filled_qty").isNull()
                                && !"null".equals(jsonResponse.get("filled_qty").asText()))
                                        ? new BigDecimal(jsonResponse.get("filled_qty").asText())
                                        : BigDecimal.ZERO,
                        (jsonResponse.has("filled_avg_price") && !jsonResponse.get("filled_avg_price").isNull()
                                && !"null".equals(jsonResponse.get("filled_avg_price").asText()))
                                        ? new BigDecimal(jsonResponse.get("filled_avg_price").asText())
                                        : null,
                        jsonResponse.get("submitted_at").asText(),
                        null);
            } else {
                logger.error("Failed to place buy order (qty). Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return new AlpacaOrderResponse(null, symbol, "FAILED", quantity, null,
                        BigDecimal.ZERO, null, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error placing buy order (qty) for account {} - Symbol: {}", accountId, symbol, e);
            return new AlpacaOrderResponse(null, symbol, "FAILED", quantity, null,
                    BigDecimal.ZERO, null, null, e.getMessage());
        }
    }

    /**
     * Check order status
     */
    public AlpacaOrderResponse checkOrderStatus(String accountId, String orderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders/" + orderId;

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                return new AlpacaOrderResponse(
                        jsonResponse.get("id").asText(),
                        jsonResponse.get("symbol").asText(),
                        jsonResponse.get("status").asText(),
                        (jsonResponse.has("qty") && !jsonResponse.get("qty").isNull()
                                && !"null".equals(jsonResponse.get("qty").asText()))
                                        ? new BigDecimal(jsonResponse.get("qty").asText())
                                        : null,
                        (jsonResponse.has("notional") && !jsonResponse.get("notional").isNull()
                                && !"null".equals(jsonResponse.get("notional").asText()))
                                        ? new BigDecimal(jsonResponse.get("notional").asText())
                                        : null,
                        (jsonResponse.has("filled_qty") && !jsonResponse.get("filled_qty").isNull()
                                && !"null".equals(jsonResponse.get("filled_qty").asText()))
                                        ? new BigDecimal(jsonResponse.get("filled_qty").asText())
                                        : BigDecimal.ZERO,
                        (jsonResponse.has("filled_avg_price") && !jsonResponse.get("filled_avg_price").isNull()
                                && !"null".equals(jsonResponse.get("filled_avg_price").asText()))
                                        ? new BigDecimal(jsonResponse.get("filled_avg_price").asText())
                                        : null,
                        jsonResponse.get("submitted_at").asText(),
                        null);
            } else {
                logger.error("Failed to check order status. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return new AlpacaOrderResponse(orderId, null, "UNKNOWN", null, null,
                        BigDecimal.ZERO, null, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error checking order status for order {}", orderId, e);
            return new AlpacaOrderResponse(orderId, null, "ERROR", null, null,
                    BigDecimal.ZERO, null, null, e.getMessage());
        }
    }

    // ==================== Tax Loss Harvesting order support ====================

    /**
     * Null-guarded parse of an Alpaca order JSON into AlpacaOrderResponse,
     * including filled_qty / filled_avg_price / filled_at. The existing
     * placeSellOrderByPercentage parser hardcodes filledQuantity=0 — TLH
     * paths must never reuse it.
     */
    private AlpacaOrderResponse parseOrderJson(JsonNode order, String fallbackSymbol) {
        return new AlpacaOrderResponse(
                textOrNull(order, "id"),
                order.hasNonNull("symbol") ? order.get("symbol").asText() : fallbackSymbol,
                textOrNull(order, "status"),
                decimalOrNull(order, "qty"),
                decimalOrNull(order, "notional"),
                decimalOrNull(order, "filled_qty") == null ? BigDecimal.ZERO : decimalOrNull(order, "filled_qty"),
                decimalOrNull(order, "filled_avg_price"),
                textOrNull(order, "submitted_at"),
                textOrNull(order, "filled_at"),
                null);
    }

    private String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) && !"null".equals(node.get(field).asText())
                ? node.get(field).asText() : null;
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        try {
            String raw = textOrNull(node, field);
            return raw == null ? null : new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Place a market sell for an exact quantity with an idempotency
     * client_order_id (persisted by the caller BEFORE this call — Alpaca
     * rejects duplicate client_order_ids, making double-submission impossible).
     */
    public AlpacaOrderResponse placeSellOrderWithQuantity(String accountId, String symbol,
            BigDecimal qty, String clientOrderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders";

            Map<String, Object> request = new HashMap<>();
            request.put("symbol", symbol);
            request.put("qty", qty.stripTrailingZeros().toPlainString());
            request.put("side", "sell");
            request.put("type", "market");
            request.put("time_in_force", "day");
            request.put("client_order_id", clientOrderId);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("[TLH] Placing sell order for account {} — {} x{} (client_order_id {})",
                    accountId, symbol, qty, clientOrderId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                return parseOrderJson(objectMapper.readTree(response.getBody()), symbol);
            }
            return new AlpacaOrderResponse(null, symbol, "FAILED", null, null, BigDecimal.ZERO, null,
                    null, "HTTP " + response.getStatusCode() + ": " + response.getBody());
        } catch (Exception e) {
            logger.error("[TLH] Error placing sell order for account {} symbol {}", accountId, symbol, e);
            return new AlpacaOrderResponse(null, symbol, "ERROR", null, null, BigDecimal.ZERO, null,
                    null, e.getMessage());
        }
    }

    /** Notional market buy carrying an idempotency client_order_id. */
    public AlpacaOrderResponse placeBuyOrderWithClientId(String accountId, String symbol,
            BigDecimal notionalAmount, String clientOrderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders";

            Map<String, Object> request = new HashMap<>();
            request.put("symbol", symbol);
            request.put("notional", notionalAmount.setScale(2, RoundingMode.DOWN).toPlainString());
            request.put("side", "buy");
            request.put("type", "market");
            request.put("time_in_force", "day");
            request.put("client_order_id", clientOrderId);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("[TLH] Placing buy order for account {} — {} ${} (client_order_id {})",
                    accountId, symbol, notionalAmount, clientOrderId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                return parseOrderJson(objectMapper.readTree(response.getBody()), symbol);
            }
            return new AlpacaOrderResponse(null, symbol, "FAILED", null, null, BigDecimal.ZERO, null,
                    null, "HTTP " + response.getStatusCode() + ": " + response.getBody());
        } catch (Exception e) {
            logger.error("[TLH] Error placing buy order for account {} symbol {}", accountId, symbol, e);
            return new AlpacaOrderResponse(null, symbol, "ERROR", null, null, BigDecimal.ZERO, null,
                    null, e.getMessage());
        }
    }

    /** Whole-share market buy carrying an idempotency client_order_id. */
    public AlpacaOrderResponse placeBuyOrderWithQuantityAndClientId(String accountId, String symbol,
            BigDecimal qty, String clientOrderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders";

            Map<String, Object> request = new HashMap<>();
            request.put("symbol", symbol);
            request.put("qty", qty.stripTrailingZeros().toPlainString());
            request.put("side", "buy");
            request.put("type", "market");
            request.put("time_in_force", "day");
            request.put("client_order_id", clientOrderId);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("[TLH] Placing whole-share buy for account {} — {} x{} (client_order_id {})",
                    accountId, symbol, qty, clientOrderId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                return parseOrderJson(objectMapper.readTree(response.getBody()), symbol);
            }
            return new AlpacaOrderResponse(null, symbol, "FAILED", null, null, BigDecimal.ZERO, null,
                    null, "HTTP " + response.getStatusCode() + ": " + response.getBody());
        } catch (Exception e) {
            logger.error("[TLH] Error placing whole-share buy for account {} symbol {}", accountId, symbol, e);
            return new AlpacaOrderResponse(null, symbol, "ERROR", null, null, BigDecimal.ZERO, null,
                    null, e.getMessage());
        }
    }

    /**
     * Crash-recovery lookup: find an order by the client_order_id we persisted
     * before submitting. Callers MUST distinguish confirmed-absent (safe to
     * fail the harvest) from lookup-failed (leave state for the poller).
     * Primary route is orders:by_client_order_id; falls back to listing
     * recent orders and matching client-side if the route is unavailable.
     */
    public OrderLookupResult getOrderByClientOrderId(String accountId, String clientOrderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId
                    + "/orders:by_client_order_id?client_order_id=" + clientOrderId;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode order = objectMapper.readTree(response.getBody());
                if (order.hasNonNull("id")) {
                    return OrderLookupResult.found(parseOrderJson(order, null));
                }
                return lookupByListing(accountId, clientOrderId);
            }
            return lookupByListing(accountId, clientOrderId);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // Route exists but no such order — still verify via listing before
            // declaring absence, since a 404 can also mean the route is unsupported.
            return lookupByListing(accountId, clientOrderId);
        } catch (Exception e) {
            logger.warn("[TLH] by_client_order_id lookup errored for {}: {}", clientOrderId, e.getMessage());
            return lookupByListing(accountId, clientOrderId);
        }
    }

    private OrderLookupResult lookupByListing(String accountId, String clientOrderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders?status=all&limit=100";
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode() != HttpStatus.OK) {
                return OrderLookupResult.lookupFailed("HTTP " + response.getStatusCode());
            }
            JsonNode orders = objectMapper.readTree(response.getBody());
            if (!orders.isArray()) {
                return OrderLookupResult.lookupFailed("Unexpected orders payload");
            }
            for (JsonNode order : orders) {
                if (order.hasNonNull("client_order_id")
                        && clientOrderId.equals(order.get("client_order_id").asText())) {
                    return OrderLookupResult.found(parseOrderJson(order, null));
                }
            }
            return OrderLookupResult.confirmedAbsent();
        } catch (Exception e) {
            logger.warn("[TLH] Order listing lookup failed for {}: {}", clientOrderId, e.getMessage());
            return OrderLookupResult.lookupFailed(e.getMessage());
        }
    }

    /** Open orders for one symbol — the post-claim race check before a harvest sell. */
    public List<Map<String, String>> getOpenOrders(String accountId, String symbol) {
        List<Map<String, String>> result = new ArrayList<>();
        String url = alpacaBaseUrl + "/trading/accounts/" + accountId
                + "/orders?status=open&limit=50&symbols=" + symbol;
        HttpHeaders headers = createAuthHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        try {
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode orders = objectMapper.readTree(response.getBody());
                if (orders.isArray()) {
                    for (JsonNode order : orders) {
                        Map<String, String> row = new HashMap<>();
                        row.put("id", textOrNull(order, "id"));
                        row.put("side", textOrNull(order, "side"));
                        row.put("symbol", textOrNull(order, "symbol"));
                        result.add(row);
                    }
                }
            }
        } catch (Exception e) {
            // Propagate as unchecked so callers treat "can't verify" as a race
            // (fail closed), never as "no open orders".
            throw new IllegalStateException("Open-orders parse failed: " + e.getMessage(), e);
        }
        return result;
    }

    /** Cancel an order; already-terminal responses are swallowed. */
    public void cancelOrder(String accountId, String orderId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders/" + orderId;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            logger.info("[TLH] Cancel requested for order {}", orderId);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND
                    || e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                logger.info("[TLH] Order {} already terminal on cancel ({})", orderId, e.getStatusCode());
            } else {
                logger.error("[TLH] Cancel failed for order {}: {}", orderId, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("[TLH] Cancel failed for order {}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Market clock — true only when Alpaca confirms the market is open.
     * ANY error returns false: TLH fails closed rather than queueing a market
     * order overnight (holidays, half-days, outages).
     */
    public boolean getMarketClock() {
        try {
            String url = alpacaBaseUrl + "/clock";
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode clock = objectMapper.readTree(response.getBody());
                return clock.hasNonNull("is_open") && clock.get("is_open").asBoolean(false);
            }
            logger.warn("[TLH] Clock check non-OK: {}", response.getStatusCode());
            return false;
        } catch (Exception e) {
            logger.warn("[TLH] Clock check failed (failing closed): {}", e.getMessage());
            return false;
        }
    }

    /**
     * One page of FILL activities (buys AND sells, all sources — scheduler,
     * DRIP, user manual, TLH). The ledger's authoritative feed. Caller pages
     * to exhaustion across ticks via the returned page token.
     */
    public FillActivityPage getFillActivities(String accountId, String after, String pageToken, int maxPages) {
        List<FillActivity> activities = new ArrayList<>();
        String nextToken = pageToken;
        boolean exhausted = false;
        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            for (int page = 0; page < Math.max(1, maxPages); page++) {
                StringBuilder urlBuilder = new StringBuilder(alpacaBaseUrl)
                        .append("/accounts/activities/FILL")
                        .append("?account_id=").append(accountId)
                        .append("&direction=asc")
                        .append("&page_size=100");
                if (after != null && !after.isBlank()) {
                    urlBuilder.append("&after=").append(after);
                }
                if (nextToken != null && !nextToken.isBlank()) {
                    urlBuilder.append("&page_token=").append(nextToken);
                }

                ResponseEntity<String> response = restTemplate.exchange(
                        urlBuilder.toString(), HttpMethod.GET, entity, String.class);
                if (response.getStatusCode() != HttpStatus.OK) {
                    logger.error("[TLH] Fill-activity fetch failed: {} {}",
                            response.getStatusCode(), response.getBody());
                    throw new IllegalStateException("Fill-activity fetch failed: " + response.getStatusCode());
                }

                JsonNode activityArray = objectMapper.readTree(response.getBody());
                if (!activityArray.isArray() || activityArray.size() == 0) {
                    exhausted = true;
                    break;
                }
                for (JsonNode activity : activityArray) {
                    FillActivity fill = new FillActivity(
                            textOrNull(activity, "id"),
                            textOrNull(activity, "order_id"),
                            textOrNull(activity, "side"),
                            textOrNull(activity, "symbol"),
                            decimalOrNull(activity, "qty"),
                            decimalOrNull(activity, "price"),
                            textOrNull(activity, "transaction_time"),
                            decimalOrNull(activity, "cum_qty"),
                            textOrNull(activity, "type"));
                    if (fill.id != null && fill.symbol != null && fill.qty != null && fill.price != null) {
                        activities.add(fill);
                    }
                }
                if (activityArray.size() < 100) {
                    exhausted = true;
                    nextToken = null;
                    break;
                }
                nextToken = activityArray.get(activityArray.size() - 1).get("id").asText();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[TLH] Error fetching fill activities for account {}: {}", accountId, e.getMessage());
            throw new IllegalStateException("Fill-activity fetch failed: " + e.getMessage(), e);
        }
        return new FillActivityPage(activities, nextToken, exhausted);
    }

    /** One FILL activity row (partial fills appear as separate activities). */
    public static class FillActivity {
        public final String id;               // activity id — ledger dedup key
        public final String orderId;
        public final String side;             // "buy" | "sell" | "sell_short"
        public final String symbol;
        public final BigDecimal qty;          // this fill's quantity
        public final BigDecimal price;        // per-share fill price
        public final String transactionTime;  // RFC3339
        public final BigDecimal cumQty;
        public final String fillType;         // "fill" | "partial_fill"

        public FillActivity(String id, String orderId, String side, String symbol, BigDecimal qty,
                BigDecimal price, String transactionTime, BigDecimal cumQty, String fillType) {
            this.id = id;
            this.orderId = orderId;
            this.side = side;
            this.symbol = symbol;
            this.qty = qty;
            this.price = price;
            this.transactionTime = transactionTime;
            this.cumQty = cumQty;
            this.fillType = fillType;
        }
    }

    /** A page of fill activities plus the cursor to continue from. */
    public static class FillActivityPage {
        public final List<FillActivity> activities;
        public final String nextPageToken; // null when exhausted
        public final boolean exhausted;

        public FillActivityPage(List<FillActivity> activities, String nextPageToken, boolean exhausted) {
            this.activities = activities;
            this.nextPageToken = nextPageToken;
            this.exhausted = exhausted;
        }
    }

    /** Adopt-or-fail lookup outcome — absence and lookup failure are NOT the same. */
    public static class OrderLookupResult {
        public final AlpacaOrderResponse order;   // non-null only when found
        public final boolean confirmedAbsent;
        public final boolean lookupFailed;
        public final String error;

        private OrderLookupResult(AlpacaOrderResponse order, boolean confirmedAbsent,
                boolean lookupFailed, String error) {
            this.order = order;
            this.confirmedAbsent = confirmedAbsent;
            this.lookupFailed = lookupFailed;
            this.error = error;
        }

        public static OrderLookupResult found(AlpacaOrderResponse order) {
            return new OrderLookupResult(order, false, false, null);
        }

        public static OrderLookupResult confirmedAbsent() {
            return new OrderLookupResult(null, true, false, null);
        }

        public static OrderLookupResult lookupFailed(String error) {
            return new OrderLookupResult(null, false, true, error);
        }
    }

    /**
     * Close an Alpaca brokerage account.
     * Prerequisites: all positions must be closed and all cash withdrawn.
     */
    public void closeAccount(String accountId) {
        try {
            String url = alpacaBaseUrl + "/accounts/" + accountId + "/actions/close";

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Closing Alpaca account: {}", accountId);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Successfully closed Alpaca account: {}", accountId);
            } else {
                String errorBody = response.getBody();
                logger.error("Failed to close Alpaca account {}: HTTP {} - {}", accountId, response.getStatusCode(), errorBody);
                throw new RuntimeException("Alpaca returned HTTP " + response.getStatusCode() + ": " + errorBody);
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            logger.error("HTTP error closing Alpaca account {}: {} - {}", accountId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to close Alpaca account: " + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error closing Alpaca account {}: {}", accountId, e.getMessage(), e);
            throw new RuntimeException("Failed to close Alpaca account", e);
        }
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("APCA-API-KEY-ID", alpacaApiKey);
        headers.set("APCA-API-SECRET-KEY", alpacaApiSecret);
        return headers;
    }

    // Response DTOs
    public static class AlpacaTransferResponse {
        public final String id;
        public final String status;
        public final BigDecimal amount;
        public final String createdAt;
        public final String updatedAt;
        public final String reason;
        public final String errorMessage;

        public AlpacaTransferResponse(String id, String status, BigDecimal amount, String createdAt,
                String errorMessage) {
            this(id, status, amount, createdAt, null, null, errorMessage);
        }

        public AlpacaTransferResponse(String id, String status, BigDecimal amount, String createdAt,
                String updatedAt, String reason, String errorMessage) {
            this.id = id;
            this.status = status;
            this.amount = amount;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.reason = reason;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return errorMessage == null && !"FAILED".equals(status) && !"ERROR".equals(status);
        }

        /**
         * Whether the transfer has reached a terminal completed state.
         * Maps from Alpaca's COMPLETE status.
         */
        public boolean isComplete() {
            return "COMPLETE".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
        }

        /**
         * Whether the transfer has reached a terminal failure state.
         * Alpaca statuses: REJECTED, CANCELED, RETURNED
         */
        public boolean isFailed() {
            return "REJECTED".equalsIgnoreCase(status) ||
                    "CANCELED".equalsIgnoreCase(status) ||
                    "RETURNED".equalsIgnoreCase(status);
        }

        /**
         * Whether the transfer is still in progress.
         * Alpaca statuses: QUEUED, APPROVAL_PENDING, PENDING, SENT_TO_CLEARING, APPROVED
         */
        public boolean isPending() {
            return "QUEUED".equalsIgnoreCase(status) ||
                    "APPROVAL_PENDING".equalsIgnoreCase(status) ||
                    "PENDING".equalsIgnoreCase(status) ||
                    "SENT_TO_CLEARING".equalsIgnoreCase(status) ||
                    "APPROVED".equalsIgnoreCase(status);
        }
    }

    public static class AlpacaOrderResponse {
        public final String id;
        public final String symbol;
        public final String status;
        public final BigDecimal quantity;
        public final BigDecimal notionalAmount;
        public final BigDecimal filledQuantity;
        public final BigDecimal filledAvgPrice;
        public final String submittedAt;
        public final String filledAt; // RFC3339 from Alpaca; null until filled
        public final String errorMessage;

        public AlpacaOrderResponse(String id, String symbol, String status, BigDecimal quantity,
                BigDecimal notionalAmount, BigDecimal filledQuantity,
                BigDecimal filledAvgPrice, String submittedAt, String errorMessage) {
            this(id, symbol, status, quantity, notionalAmount, filledQuantity, filledAvgPrice,
                    submittedAt, null, errorMessage);
        }

        public AlpacaOrderResponse(String id, String symbol, String status, BigDecimal quantity,
                BigDecimal notionalAmount, BigDecimal filledQuantity,
                BigDecimal filledAvgPrice, String submittedAt, String filledAt, String errorMessage) {
            this.id = id;
            this.symbol = symbol;
            this.status = status;
            this.quantity = quantity;
            this.notionalAmount = notionalAmount;
            this.filledQuantity = filledQuantity;
            this.filledAvgPrice = filledAvgPrice;
            this.submittedAt = submittedAt;
            this.filledAt = filledAt;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return errorMessage == null && !"FAILED".equals(status) && !"ERROR".equals(status);
        }

        public boolean isFilled() {
            return "filled".equalsIgnoreCase(status);
        }

        public boolean isPartiallyFilled() {
            return "partially_filled".equalsIgnoreCase(status);
        }
    }

    /**
     * Disable margin trading for an account to make it cash-only
     * This prevents users from borrowing money to buy stocks
     */
    public boolean disableMarginTrading(String accountId) {
        try {
            String url = alpacaBaseUrl + "/accounts/" + accountId + "/configurations";

            Map<String, Object> config = new HashMap<>();
            config.put("max_margin_multiplier", "1.0"); // Cash-only (no borrowing)
            config.put("pdt_check", "both"); // Pattern day trader protection

            HttpHeaders headers = createAuthHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(config, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.PATCH, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                logger.info("Successfully disabled margin trading for account: {}", accountId);
                return true;
            } else {
                logger.error("Failed to disable margin trading. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return false;
            }

        } catch (Exception e) {
            logger.error("Error disabling margin trading for account: {}", accountId, e);
            return false;
        }
    }

    /**
     * Configure account for cash-only trading during account creation
     * Call this immediately after creating a new Alpaca account
     */
    public void setupCashOnlyAccount(String accountId) {
        logger.info("Setting up cash-only trading for account: {}", accountId);

        // Wait a moment for account to be fully created
        try {
            Thread.sleep(2000); // 2 second delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean success = disableMarginTrading(accountId);
        if (!success) {
            logger.warn("Failed to disable margin trading for account: {}. Will retry later.", accountId);
            // Could implement retry logic here if needed
        }
    }

    /**
     * Update account beneficiaries using Alpaca PATCH API
     * 
     * @param accountId     Alpaca account ID
     * @param beneficiaries List of beneficiaries to set on the account
     * @return true if successful, false otherwise
     */
    public boolean updateAccountBeneficiaries(String accountId,
            java.util.List<com.investingapp.backend.model.Beneficiary> beneficiaries) {
        try {
            String url = alpacaBaseUrl + "/accounts/" + accountId;
            logger.info("Updating beneficiaries for account: {} with {} beneficiaries", accountId,
                    beneficiaries.size());

            // Transform internal beneficiaries to Alpaca format
            java.util.List<Map<String, Object>> alpacaBeneficiaries = new java.util.ArrayList<>();

            for (com.investingapp.backend.model.Beneficiary beneficiary : beneficiaries) {
                // Validate required fields before creating the Alpaca beneficiary object
                if (beneficiary.getFirstName() == null || beneficiary.getFirstName().trim().isEmpty()) {
                    logger.error("Beneficiary missing first name: {}", beneficiary.getId());
                    throw new IllegalArgumentException("Beneficiary first name is required");
                }
                if (beneficiary.getLastName() == null || beneficiary.getLastName().trim().isEmpty()) {
                    logger.error("Beneficiary missing last name: {}", beneficiary.getId());
                    throw new IllegalArgumentException("Beneficiary last name is required");
                }
                if (beneficiary.getDateOfBirth() == null) {
                    logger.error("Beneficiary missing date of birth: {}", beneficiary.getId());
                    throw new IllegalArgumentException("Beneficiary date of birth is required");
                }
                if (beneficiary.getSocialSecurityNumber() == null
                        || beneficiary.getSocialSecurityNumber().trim().isEmpty()) {
                    logger.error("Beneficiary missing SSN: {}", beneficiary.getId());
                    throw new IllegalArgumentException("Beneficiary Social Security Number is required");
                }
                if (beneficiary.getRelationship() == null) {
                    logger.error("Beneficiary missing relationship: {}", beneficiary.getId());
                    throw new IllegalArgumentException("Beneficiary relationship is required");
                }
                if (beneficiary.getPercentageAllocation() == null
                        || beneficiary.getPercentageAllocation().compareTo(BigDecimal.ZERO) <= 0) {
                    logger.error("Beneficiary missing or invalid percentage allocation: {}", beneficiary.getId());
                    throw new IllegalArgumentException("Beneficiary percentage allocation must be greater than 0");
                }

                Map<String, Object> alpacaBeneficiary = new HashMap<>();

                // Required fields according to Alpaca API
                alpacaBeneficiary.put("given_name", beneficiary.getFirstName());
                alpacaBeneficiary.put("family_name", beneficiary.getLastName());
                alpacaBeneficiary.put("date_of_birth", beneficiary.getDateOfBirth().toString()); // Format: YYYY-MM-DD
                alpacaBeneficiary.put("tax_id", beneficiary.getSocialSecurityNumber());
                alpacaBeneficiary.put("tax_id_type", "ssn"); // Assuming SSN for US users
                alpacaBeneficiary.put("relationship", beneficiary.getRelationship().name().toLowerCase());
                alpacaBeneficiary.put("share_pct", beneficiary.getPercentageAllocation().toString());

                // Set type based on beneficiary type
                String type = beneficiary
                        .getBeneficiaryType() == com.investingapp.backend.model.Beneficiary.BeneficiaryType.PRIMARY
                                ? "primary"
                                : "contingent";
                alpacaBeneficiary.put("type", type);

                // Add optional contact info
                if (beneficiary.getEmail() != null && !beneficiary.getEmail().trim().isEmpty()) {
                    alpacaBeneficiary.put("email_address", beneficiary.getEmail());
                }
                if (beneficiary.getPhone() != null && !beneficiary.getPhone().trim().isEmpty()) {
                    alpacaBeneficiary.put("phone_number", beneficiary.getPhone());
                }

                // Add optional address info
                if (beneficiary.getAddressLine1() != null && !beneficiary.getAddressLine1().trim().isEmpty()) {
                    Map<String, Object> address = new HashMap<>();
                    java.util.List<String> streetAddress = new java.util.ArrayList<>();
                    streetAddress.add(beneficiary.getAddressLine1());
                    
                    if (beneficiary.getAddressLine2() != null && !beneficiary.getAddressLine2().trim().isEmpty()) {
                        streetAddress.add(beneficiary.getAddressLine2());
                    }
                    
                    address.put("street_address", streetAddress);
                    address.put("city", beneficiary.getCity());
                    address.put("state", beneficiary.getState());
                    address.put("postal_code", beneficiary.getPostalCode());
                    address.put("country", beneficiary.getCountry() != null ? beneficiary.getCountry() : "US");
                    
                    alpacaBeneficiary.put("mailing_address", address);
                }

                alpacaBeneficiaries.add(alpacaBeneficiary);

                logger.debug("Added beneficiary to submission: {} {} ({}), DOB: {}, Allocation: {}%",
                        beneficiary.getFirstName(), beneficiary.getLastName(), type,
                        beneficiary.getDateOfBirth(), beneficiary.getPercentageAllocation());
            }

            // Build request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("beneficiaries", alpacaBeneficiaries);

            // Use WebClient for PATCH request (better PATCH support)
            String response = webClient.patch()
                    .uri(url)
                    .header("Authorization",
                            "Basic " + java.util.Base64.getEncoder()
                                    .encodeToString((alpacaApiKey + ":" + alpacaApiSecret).getBytes()))
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("Successfully updated beneficiaries for account: {}", accountId);
            return true;

        } catch (Exception e) {
            logger.error("Error updating beneficiaries for account: {}", accountId, e);
            return false;
        }
    }

    /**
     * Get current positions for an account
     */
    public String getCurrentPositions(String accountId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/positions";

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Getting positions for account {}", accountId);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                return response.getBody();
            } else {
                logger.error("Failed to get positions. Status: {}, Response: {}",
                        response.getStatusCode(), response.getBody());
                return null;
            }

        } catch (Exception e) {
            logger.error("Error getting positions for account {}", accountId, e);
            return null;
        }
    }

    /**
     * Place a sell order for a percentage of a position
     */
    public AlpacaOrderResponse placeSellOrderByPercentage(String accountId, String symbol, BigDecimal percentage) {
        try {
            // First get the current position to know how much to sell
            String positionUrl = alpacaBaseUrl + "/trading/accounts/" + accountId + "/positions/" + symbol;
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> posResponse = restTemplate.exchange(positionUrl, HttpMethod.GET, entity,
                    String.class);

            if (posResponse.getStatusCode() != HttpStatus.OK) {
                return new AlpacaOrderResponse(null, symbol, "FAILED", null, null,
                        BigDecimal.ZERO, null, null, "Could not fetch position for " + symbol);
            }

            JsonNode position = objectMapper.readTree(posResponse.getBody());
            BigDecimal currentQty = new BigDecimal(position.get("qty").asText());

            // Check if fractionable
            boolean isFractionable = isFractionable(symbol);

            // Calculate quantity to sell
            BigDecimal qtyToSell = currentQty.multiply(percentage).divide(new BigDecimal("100"));

            // If selling 100% or very close to it, just sell all
            if (percentage.compareTo(new BigDecimal("99.9")) >= 0) {
                qtyToSell = currentQty;
            } else if (!isFractionable) {
                // If not fractionable, floor to nearest whole number
                logger.info("Asset {} is not fractionable, flooring sell quantity", symbol);
                qtyToSell = qtyToSell.setScale(0, RoundingMode.DOWN);
                
                // If attempting to sell < 1 share of non-fractionable asset, fail
                if (qtyToSell.compareTo(BigDecimal.ZERO) == 0) {
                    return new AlpacaOrderResponse(null, symbol, "FAILED", null, null,
                        BigDecimal.ZERO, null, null, 
                        "Cannot sell fractional amount (" + percentage + "%) of non-fractionable asset " + symbol);
                }
            }

            // Place the sell order
            String orderUrl = alpacaBaseUrl + "/trading/accounts/" + accountId + "/orders";

            Map<String, Object> request = new HashMap<>();
            request.put("symbol", symbol);
            request.put("qty", qtyToSell.toString());
            request.put("side", "sell");
            request.put("type", "market");
            request.put("time_in_force", "day");

            HttpEntity<Map<String, Object>> orderEntity = new HttpEntity<>(request, headers);

            logger.info("Placing sell order for account {} - Symbol: {}, Qty: {} ({}%)",
                    accountId, symbol, qtyToSell, percentage);

            ResponseEntity<String> response = restTemplate.exchange(orderUrl, HttpMethod.POST, orderEntity,
                    String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                return new AlpacaOrderResponse(
                        jsonResponse.get("id").asText(),
                        symbol,
                        jsonResponse.get("status").asText(),
                        new BigDecimal(jsonResponse.get("qty").asText()),
                        null,
                        BigDecimal.ZERO,
                        null,
                        jsonResponse.get("submitted_at").asText(),
                        null);
            } else {
                return new AlpacaOrderResponse(null, symbol, "FAILED", null, null,
                        BigDecimal.ZERO, null, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error placing sell order for account {} - Symbol: {}", accountId, symbol, e);
            return new AlpacaOrderResponse(null, symbol, "FAILED", null, null,
                    BigDecimal.ZERO, null, null, e.getMessage());
        }
    }

    /**
     * Liquidate entire portfolio
     */
    public AlpacaOrderResponse liquidatePortfolio(String accountId) {
        try {
            String url = alpacaBaseUrl + "/trading/accounts/" + accountId + "/positions";

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            logger.info("Liquidating all positions for account {}", accountId);

            // DELETE request to positions endpoint liquidates all positions
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.MULTI_STATUS) {
                // Returns a list of orders, but we'll just return a summary success response
                return new AlpacaOrderResponse(
                        "liquidation-" + System.currentTimeMillis(),
                        "ALL",
                        "ACCEPTED",
                        null, null, BigDecimal.ZERO, null,
                        java.time.Instant.now().toString(),
                        null);
            } else {
                return new AlpacaOrderResponse(null, "ALL", "FAILED", null, null,
                        BigDecimal.ZERO, null, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error liquidating portfolio for account {}", accountId, e);
            return new AlpacaOrderResponse(null, "ALL", "FAILED", null, null,
                    BigDecimal.ZERO, null, null, e.getMessage());
        }
    }

    /**
     * Initiate withdrawal via ACH
     */
    public AlpacaTransferResponse initiateWithdrawal(String accountId, String relationshipId, BigDecimal amount) {
        try {
            String url = alpacaBaseUrl + "/accounts/" + accountId + "/transfers";

            Map<String, Object> request = new HashMap<>();
            request.put("transfer_type", "ach");
            request.put("relationship_id", relationshipId);
            request.put("amount", amount.toString());
            request.put("direction", "OUTGOING");

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("Initiating withdrawal for account {} with amount {}", accountId, amount);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                return new AlpacaTransferResponse(
                        jsonResponse.get("id").asText(),
                        jsonResponse.get("status").asText(),
                        amount,
                        jsonResponse.get("created_at").asText(),
                        null);
            } else {
                return new AlpacaTransferResponse(null, "FAILED", amount, null,
                        "HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (Exception e) {
            logger.error("Error initiating withdrawal for account {}", accountId, e);
            return new AlpacaTransferResponse(null, "FAILED", amount, null, e.getMessage());
        }
    }

    /**
     * Initiate an ACATS transfer
     */
    public String initiateAcatsTransfer(String accountId, String transferAccountId, String transferAccountType, String dtcNumber) {
        try {
            String url = alpacaBaseUrl + "/accounts/" + accountId + "/transfers";

            Map<String, Object> request = new HashMap<>();
            request.put("transfer_type", "ACAT");
            request.put("direction", "INCOMING");
            request.put("participant_code", dtcNumber);
            request.put("account_number", transferAccountId);

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            logger.info("Initiating ACAT transfer for account {} from external account {} (DTC: {})",
                    accountId, transferAccountId, dtcNumber);

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                if (jsonResponse.has("id") && !jsonResponse.get("id").isNull()) {
                    return jsonResponse.get("id").asText();
                }
                return "acat_initiated_" + java.util.UUID.randomUUID().toString();
            } else {
                throw new RuntimeException("Failed to initiate ACAT transfer: HTTP " + response.getStatusCode() + ": " + response.getBody());
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error initiating ACAT transfer for account {}", accountId, e);
            throw new RuntimeException("Failed to initiate ACAT transfer: " + e.getMessage());
        }
    }

    // ==================== DRIP (Dividend Reinvestment) ====================

    /**
     * Fetch cash dividend (CDIV) activities for an account from Alpaca.
     * 
     * API: GET /v1/accounts/activities/DIV?account_id={id}&after={after}&until={until}&direction=asc&page_size=100
     * 
     * Returns only executed CDIV activities. Handles pagination if >100 results.
     *
     * @param accountId Alpaca account UUID
     * @param after     ISO date string (e.g., "2026-01-01") — activities after this date
     * @param until     ISO date string (e.g., "2026-02-27") — activities up to this date
     * @return List of DividendActivity objects
     */
    public List<DividendActivity> getDividendActivities(String accountId, String after, String until) {
        List<DividendActivity> allActivities = new ArrayList<>();
        String pageToken = null;

        try {
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // Paginate through results
            for (int page = 0; page < 10; page++) { // Safety: max 10 pages (1000 activities)
                StringBuilder urlBuilder = new StringBuilder(alpacaBaseUrl)
                        .append("/accounts/activities/DIV")
                        .append("?account_id=").append(accountId)
                        .append("&after=").append(after)
                        .append("&until=").append(until)
                        .append("&direction=asc")
                        .append("&page_size=100");

                if (pageToken != null) {
                    urlBuilder.append("&page_token=").append(pageToken);
                }

                String url = urlBuilder.toString();
                logger.info("Fetching dividend activities for account {} (page {}): {}", accountId, page, url);

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode() != HttpStatus.OK) {
                    logger.error("Failed to fetch dividend activities. Status: {}, Response: {}",
                            response.getStatusCode(), response.getBody());
                    break;
                }

                JsonNode activitiesArray = objectMapper.readTree(response.getBody());
                if (!activitiesArray.isArray() || activitiesArray.size() == 0) {
                    break; // No more results
                }

                for (JsonNode activity : activitiesArray) {
                    // Only process CDIV (Cash Dividend) activities that are executed
                    String subType = activity.has("activity_sub_type") ? activity.get("activity_sub_type").asText() : "";
                    String status = activity.has("status") ? activity.get("status").asText() : "";

                    if ("CDIV".equals(subType) && "executed".equalsIgnoreCase(status)) {
                        DividendActivity div = new DividendActivity(
                                activity.get("id").asText(),
                                activity.has("activity_type") ? activity.get("activity_type").asText() : "DIV",
                                subType,
                                activity.has("date") ? activity.get("date").asText() : null,
                                activity.has("net_amount") ? new BigDecimal(activity.get("net_amount").asText()) : BigDecimal.ZERO,
                                activity.has("symbol") ? activity.get("symbol").asText() : null,
                                activity.has("qty") ? activity.get("qty").asText() : null,
                                activity.has("per_share_amount") ? activity.get("per_share_amount").asText() : null,
                                activity.has("description") ? activity.get("description").asText() : null,
                                status,
                                activity.has("account_id") ? activity.get("account_id").asText() : accountId,
                                activity.has("created_at") ? activity.get("created_at").asText() : null
                        );
                        allActivities.add(div);
                    }
                }

                // Pagination: if we got a full page, use the last activity's ID as page_token
                if (activitiesArray.size() < 100) {
                    break; // Last page
                }
                JsonNode lastActivity = activitiesArray.get(activitiesArray.size() - 1);
                pageToken = lastActivity.get("id").asText();
            }

            logger.info("Found {} executed CDIV activities for account {} between {} and {}",
                    allActivities.size(), accountId, after, until);

        } catch (Exception e) {
            logger.error("Error fetching dividend activities for account {}: {}", accountId, e.getMessage(), e);
        }

        return allActivities;
    }

    /**
     * DTO representing a Cash Dividend activity from Alpaca
     */
    public static class DividendActivity {
        public final String id;                // Unique activity ID (dedup key)
        public final String activityType;      // "DIV"
        public final String activitySubType;   // "CDIV"
        public final String date;              // Dividend date
        public final BigDecimal netAmount;     // Dollar amount credited to account
        public final String symbol;            // Stock that paid the dividend
        public final String qty;               // Position quantity on record date
        public final String perShareAmount;    // Dividend per share
        public final String description;       // e.g., "Cash DIV @ 0.54 Pos QTY:9.03..."
        public final String status;            // "executed"
        public final String accountId;         // Alpaca account UUID
        public final String createdAt;         // Timestamp

        public DividendActivity(String id, String activityType, String activitySubType,
                                String date, BigDecimal netAmount, String symbol, String qty,
                                String perShareAmount, String description, String status,
                                String accountId, String createdAt) {
            this.id = id;
            this.activityType = activityType;
            this.activitySubType = activitySubType;
            this.date = date;
            this.netAmount = netAmount;
            this.symbol = symbol;
            this.qty = qty;
            this.perShareAmount = perShareAmount;
            this.description = description;
            this.status = status;
            this.accountId = accountId;
            this.createdAt = createdAt;
        }
    }
}

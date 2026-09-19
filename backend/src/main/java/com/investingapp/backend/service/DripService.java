package com.investingapp.backend.service;

import com.investingapp.backend.model.DripExecution;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.DripExecutionRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AlpacaService.AlpacaOrderResponse;
import com.investingapp.backend.service.AlpacaService.DividendActivity;
import com.investingapp.backend.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service handling DRIP (Dividend Reinvestment Plan) logic.
 * 
 * Flow:
 * 1. Scan all DRIP-enabled users with Alpaca accounts
 * 2. For each user, query Alpaca for CDIV activities since last processed dividend
 * 3. For each new CDIV, place a notional buy order for the dividend amount into the same stock
 * 4. Track execution status and mark as FILLED when order completes
 * 
 * Scheduling: Runs Tuesday and Thursday at 10 AM ET via InvestmentScheduler.
 * Order status checks: Every 15 min during market hours (10 AM - 3 PM ET, Mon-Fri).
 */
@Service
public class DripService {

    private static final Logger logger = LoggerFactory.getLogger(DripService.class);

    /** Minimum dividend amount to reinvest. Alpaca requires at least $1 for notional orders. */
    private static final BigDecimal MIN_REINVESTMENT_AMOUNT = new BigDecimal("1.00");

    /** Default lookback period (in days) for users with no DRIP history */
    private static final int DEFAULT_LOOKBACK_DAYS = 90;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DripExecutionRepository dripExecutionRepository;

    @Autowired
    private AlpacaService alpacaService;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private WashSaleGuardService washSaleGuardService;

    // ==================== Main DRIP Processing ====================

    /**
     * Process dividends for ALL DRIP-enabled users.
     * Called by InvestmentScheduler on Tue/Thu at 10 AM ET.
     */
    public void processAllDividends() {
        logger.info("[DRIP] Starting dividend reinvestment scan for all users");

        List<User> allUsers = userRepository.findAll();
        int processedCount = 0;
        int dividendsFound = 0;
        int ordersPlaced = 0;

        for (User user : allUsers) {
            // Skip users that don't qualify for DRIP
            if (!isDripEligible(user)) {
                continue;
            }

            try {
                int[] result = processDividendsForUser(user);
                dividendsFound += result[0];
                ordersPlaced += result[1];
                processedCount++;
            } catch (Exception e) {
                logger.error("[DRIP] Error processing dividends for user {} (ID: {}): {}",
                        user.getEmail(), user.getId(), e.getMessage(), e);
            }
        }

        logger.info("[DRIP] Scan complete. Users processed: {}, Dividends found: {}, Orders placed: {}",
                processedCount, dividendsFound, ordersPlaced);
    }

    /**
     * Process dividends for a single user.
     * @return int[] where [0] = dividends found, [1] = orders placed
     */
    @Transactional
    public int[] processDividendsForUser(User user) {
        String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
        int dividendsFound = 0;
        int ordersPlaced = 0;

        // Determine the 'after' date: last processed dividend date for this user
        String afterDate = getLastProcessedDividendDate(user);
        String untilDate = LocalDate.now(ZoneId.of("America/New_York")).toString();

        logger.info("[DRIP] Checking dividends for user {}, window: {} to {}",
                user.getEmail(), afterDate, untilDate);

        // Fetch dividend activities from Alpaca
        List<DividendActivity> dividends = alpacaService.getDividendActivities(accountId, afterDate, untilDate);

        for (DividendActivity div : dividends) {
            dividendsFound++;

            // Dedup check: have we already processed this activity?
            if (dripExecutionRepository.existsByAlpacaActivityId(div.id)) {
                logger.debug("[DRIP] Skipping already-processed dividend: {} ({} {})",
                        div.id, div.symbol, div.netAmount);
                continue;
            }

            // Check minimum amount
            if (div.netAmount.compareTo(MIN_REINVESTMENT_AMOUNT) < 0) {
                logger.info("[DRIP] Skipping dividend {} for {} — amount ${} below minimum ${}",
                        div.id, div.symbol, div.netAmount, MIN_REINVESTMENT_AMOUNT);

                // Record as SKIPPED so we don't check it again
                DripExecution skipped = new DripExecution(
                        user, div.id, div.symbol, div.netAmount, div.date, div.description);
                skipped.setStatus(DripExecution.DripStatus.SKIPPED);
                skipped.setErrorMessage("Amount below minimum reinvestment threshold of $" + MIN_REINVESTMENT_AMOUNT);
                dripExecutionRepository.save(skipped);
                continue;
            }

            // Create DRIP execution record
            DripExecution dripExecution = new DripExecution(
                    user, div.id, div.symbol, div.netAmount, div.date, div.description);
            dripExecutionRepository.save(dripExecution);

            // TLH wash-sale guard: reinvesting a dividend into a symbol under
            // an active wash-sale window would wash the harvested loss. Default
            // policy parks the dividend as cash (row saved SKIPPED — the
            // activity-id dedup makes that permanent, a documented
            // conservatism); redirect policy buys the window's replacement
            // while the row keeps the ORIGINAL dividend symbol.
            String reinvestSymbol = div.symbol;
            WashSaleGuardService.GuardDecision guardDecision = washSaleGuardService.checkBuy(
                    user.getId(), div.symbol, WashSaleGuardService.TradeSource.DRIP,
                    LocalDate.now(ZoneId.of("America/New_York")));
            if (guardDecision.action() == WashSaleGuardService.GuardAction.SKIP) {
                dripExecution.setStatus(DripExecution.DripStatus.SKIPPED);
                dripExecution.setErrorMessage("TLH wash-sale window active until "
                        + guardDecision.window().getEndDate() + ". Dividend held as cash.");
                dripExecutionRepository.save(dripExecution);
                logger.info("[DRIP] Skipping reinvestment of {} for user {} — TLH wash-sale window active",
                        div.symbol, user.getEmail());
                continue;
            }
            if (guardDecision.action() == WashSaleGuardService.GuardAction.REDIRECT) {
                reinvestSymbol = guardDecision.redirectSymbol();
                dripExecution.setDescription((dripExecution.getDescription() == null ? ""
                        : dripExecution.getDescription())
                        + " | TLH wash-sale redirect: bought " + reinvestSymbol + " instead");
                dripExecutionRepository.save(dripExecution);
            }

            // Place the reinvestment buy order
            try {
                logger.info("[DRIP] Placing reinvestment order: Buy ${} of {} for user {}",
                        div.netAmount, reinvestSymbol, user.getEmail());

                AlpacaOrderResponse orderResponse = alpacaService.placeBuyOrder(
                        accountId, reinvestSymbol, div.netAmount);

                if (orderResponse != null && orderResponse.isSuccess()) {
                    dripExecution.setAlpacaOrderId(orderResponse.id);
                    dripExecution.setStatus(DripExecution.DripStatus.ORDER_PLACED);
                    dripExecutionRepository.save(dripExecution);
                    ordersPlaced++;

                    logger.info("[DRIP] Order placed successfully: {} — orderId: {} for ${} of {}",
                            div.id, orderResponse.id, div.netAmount, div.symbol);
                } else {
                    String error = orderResponse != null ? orderResponse.errorMessage : "Null response";
                    dripExecution.setStatus(DripExecution.DripStatus.FAILED);
                    dripExecution.setErrorMessage("Order placement failed: " + error);
                    dripExecutionRepository.save(dripExecution);

                    logger.error("[DRIP] Order placement failed for dividend {} ({} ${}): {}",
                            div.id, div.symbol, div.netAmount, error);
                }
            } catch (Exception e) {
                dripExecution.setStatus(DripExecution.DripStatus.FAILED);
                dripExecution.setErrorMessage("Exception during order placement: " + e.getMessage());
                dripExecutionRepository.save(dripExecution);

                logger.error("[DRIP] Exception placing order for dividend {} ({} ${}): {}",
                        div.id, div.symbol, div.netAmount, e.getMessage(), e);
            }
        }

        return new int[]{dividendsFound, ordersPlaced};
    }

    // ==================== Order Status Checking ====================

    /**
     * Check the status of all DRIP orders with ORDER_PLACED status.
     * Called every 15 min during market hours by InvestmentScheduler.
     */
    @Transactional
    public void checkDripOrderStatus() {
        List<DripExecution> pendingOrders = dripExecutionRepository.findByStatus(DripExecution.DripStatus.ORDER_PLACED);

        if (pendingOrders.isEmpty()) {
            logger.debug("[DRIP] No pending DRIP orders to check");
            return;
        }

        logger.info("[DRIP] Checking status of {} pending DRIP orders", pendingOrders.size());

        for (DripExecution dripExecution : pendingOrders) {
            try {
                User user = dripExecution.getUser();
                String encryptedAccountId = user.getAlpacaAccountId();
                String orderId = dripExecution.getAlpacaOrderId();

                if (encryptedAccountId == null || orderId == null) {
                    logger.warn("[DRIP] Skipping status check — missing accountId or orderId for DRIP #{}", dripExecution.getId());
                    continue;
                }

                String accountId = encryptionService.decrypt(encryptedAccountId);
                AlpacaOrderResponse orderStatus = alpacaService.checkOrderStatus(accountId, orderId);

                if (orderStatus == null) {
                    logger.warn("[DRIP] Null response checking order {} for DRIP #{}", orderId, dripExecution.getId());
                    continue;
                }

                String status = orderStatus.status;

                if ("filled".equalsIgnoreCase(status)) {
                    dripExecution.setStatus(DripExecution.DripStatus.FILLED);
                    dripExecution.setExecutedAt(LocalDateTime.now(ZoneId.of("America/New_York")));
                    dripExecutionRepository.save(dripExecution);

                    logger.info("[DRIP] Order FILLED for DRIP #{}: ${} of {} (order: {})",
                            dripExecution.getId(), dripExecution.getNetAmount(),
                            dripExecution.getSymbol(), orderId);

                } else if ("canceled".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)
                        || "rejected".equalsIgnoreCase(status)) {
                    dripExecution.setStatus(DripExecution.DripStatus.FAILED);
                    dripExecution.setErrorMessage("Order " + status + " by Alpaca");
                    dripExecutionRepository.save(dripExecution);

                    logger.warn("[DRIP] Order {} for DRIP #{}: ${} of {} — {}",
                            status, dripExecution.getId(), dripExecution.getNetAmount(),
                            dripExecution.getSymbol(), orderId);

                } else {
                    // Still in progress (new, partially_filled, pending_new, accepted, etc.)
                    logger.debug("[DRIP] Order still in progress ({}): DRIP #{} — {} {}",
                            status, dripExecution.getId(), dripExecution.getSymbol(), orderId);
                }

            } catch (Exception e) {
                logger.error("[DRIP] Error checking order status for DRIP #{}: {}",
                        dripExecution.getId(), e.getMessage(), e);
            }
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Determine if a user is eligible for DRIP processing.
     */
    private boolean isDripEligible(User user) {
        // Must have DRIP enabled (null treated as true for backwards compatibility)
        if (Boolean.FALSE.equals(user.getDripEnabled())) {
            return false;
        }

        // Must have an Alpaca account
        if (user.getAlpacaAccountId() == null || user.getAlpacaAccountId().isBlank()) {
            return false;
        }

        return true;
    }

    /**
     * Get the date to use as the 'after' parameter for the Alpaca dividend query.
     * Returns the dividend date of the most recently processed DRIP execution for this user,
     * or a default lookback date if no history exists.
     */
    private String getLastProcessedDividendDate(User user) {
        List<DripExecution> recent = dripExecutionRepository.findByUserOrderByDividendDateDesc(user);

        if (!recent.isEmpty()) {
            String lastDate = recent.get(0).getDividendDate();
            if (lastDate != null && !lastDate.isBlank()) {
                return lastDate;
            }
        }

        // No history — look back DEFAULT_LOOKBACK_DAYS days
        return LocalDate.now(ZoneId.of("America/New_York"))
                .minusDays(DEFAULT_LOOKBACK_DAYS)
                .toString();
    }
}

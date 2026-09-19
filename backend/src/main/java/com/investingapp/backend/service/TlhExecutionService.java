package com.investingapp.backend.service;

import com.investingapp.backend.model.TaxLot;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.TlhUserState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import com.investingapp.backend.repository.TlhUserStateRepository;
import com.investingapp.backend.repository.WashSaleWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TLH order lifecycle and crash recovery. Invariants:
 * - NO Alpaca call ever runs inside an open DB transaction — state is
 *   persisted in short TransactionTemplate transactions, then HTTP happens,
 *   then the outcome is persisted.
 * - Every order carries a client_order_id persisted BEFORE the HTTP call;
 *   recovery adopts-or-fails by client id, and absence vs lookup-failure are
 *   never conflated (unknown ⇒ leave state for the poller, never terminal).
 * - Completion, re-entry, and window enforcement are tier-blind: half-executed
 *   states are strictly worse than finishing, and windows protect losses the
 *   user already realized.
 */
@Service
public class TlhExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(TlhExecutionService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int SELL_PENDING_STALE_MINUTES = 10;
    private static final int REENTRY_ATTEMPTS_MAX = 3;
    private static final int CONSECUTIVE_FETCH_FAILURE_LIMIT = 3;
    private static final int BACKFILL_PAGES_PER_TICK = 10;

    private final TlhHarvestEventRepository eventRepository;
    private final WashSaleWindowRepository windowRepository;
    private final com.investingapp.backend.repository.LotDisposalRepository lotDisposalRepository;
    private final WashSaleGuardService guard;
    private final ReplacementSelectionService replacementSelectionService;
    private final TaxLotLedgerService ledger;
    private final TlhCatalog catalog;
    private final TlhUserStateRepository userStateRepository;
    private final com.investingapp.backend.repository.UserRepository userRepository;
    private final AlpacaService alpacaService;
    private final EncryptionService encryptionService;
    private final PortfolioDashboardService portfolioDashboardService;
    private final TransactionTemplate shortTx;
    private final String mode;
    private final boolean replaceOnPartial;
    private final int replacementAttemptsMax;
    private final boolean reentryEnabled;

    /** Consecutive order/account fetch failures per event (single-JVM, like the ledger locks). */
    private final Map<Long, Integer> pollFailures = new ConcurrentHashMap<>();

    /** Package-private so unit tests can zero the synchronous fill-check wait. */
    long syncFillCheckDelayMs = 10_000L;

    public TlhExecutionService(TlhHarvestEventRepository eventRepository,
            WashSaleWindowRepository windowRepository,
            com.investingapp.backend.repository.LotDisposalRepository lotDisposalRepository,
            WashSaleGuardService guard,
            ReplacementSelectionService replacementSelectionService,
            TaxLotLedgerService ledger,
            TlhCatalog catalog,
            TlhUserStateRepository userStateRepository,
            com.investingapp.backend.repository.UserRepository userRepository,
            AlpacaService alpacaService,
            EncryptionService encryptionService,
            PortfolioDashboardService portfolioDashboardService,
            PlatformTransactionManager transactionManager,
            @Value("${app.tlh.mode:off}") String mode,
            @Value("${app.tlh.replace-on-partial:true}") boolean replaceOnPartial,
            @Value("${app.tlh.replacement-attempts-max:3}") int replacementAttemptsMax,
            @Value("${app.tlh.reentry-enabled:true}") boolean reentryEnabled) {
        this.eventRepository = eventRepository;
        this.windowRepository = windowRepository;
        this.lotDisposalRepository = lotDisposalRepository;
        this.guard = guard;
        this.replacementSelectionService = replacementSelectionService;
        this.ledger = ledger;
        this.catalog = catalog;
        this.userStateRepository = userStateRepository;
        this.userRepository = userRepository;
        this.alpacaService = alpacaService;
        this.encryptionService = encryptionService;
        this.portfolioDashboardService = portfolioDashboardService;
        this.shortTx = new TransactionTemplate(transactionManager);
        this.shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mode = mode;
        this.replaceOnPartial = replaceOnPartial;
        this.replacementAttemptsMax = replacementAttemptsMax;
        this.reentryEnabled = reentryEnabled;
    }

    /** RFC3339 → ET LocalDateTime; null-safe. */
    public static LocalDateTime parseAlpacaTimestamp(String rfc3339) {
        if (rfc3339 == null || rfc3339.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).atZoneSameInstant(ET).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    private String accountIdOf(TlhHarvestEvent event) {
        return encryptionService.decrypt(event.getAlpacaAccountId());
    }

    private void save(TlhHarvestEvent event) {
        shortTx.executeWithoutResult(status -> eventRepository.save(event));
    }

    // ==================== Sell leg ====================

    /**
     * Submit the harvest sell for a freshly claimed event. The claim (event +
     * provisional window) is already COMMITTED — this method runs outside any
     * transaction.
     */
    public void submitSell(TlhHarvestEvent event, User user) {
        // Execution-moment tier re-check: tier lost between scan and submit
        User freshUser = userRepository.findById(user.getId()).orElse(null);
        if (freshUser == null || !"pro".equals(freshUser.getSelectedTier())) {
            veto(event, "TIER_LOST");
            return;
        }

        String accountId = accountIdOf(event);

        // Post-claim TOCTOU re-check: the window is committed and visible, but
        // a buy created BEFORE the claim could still be racing toward Alpaca.
        try {
            boolean pendingLocal = guard.hasPendingBuyActivity(event.getUserId(), freshUser, event.getSymbol());
            boolean openBuyAtBroker = alpacaService.getOpenOrders(accountId, event.getSymbol()).stream()
                    .anyMatch(order -> "buy".equalsIgnoreCase(order.get("side")));
            if (pendingLocal || openBuyAtBroker) {
                veto(event, "RACE_DETECTED");
                return;
            }
        } catch (Exception e) {
            // Can't verify ⇒ treat as a race (fail closed)
            logger.warn("[TLH] Race check failed for event {}: {}", event.getId(), e.getMessage());
            veto(event, "RACE_DETECTED");
            return;
        }

        AlpacaService.AlpacaOrderResponse response = alpacaService.placeSellOrderWithQuantity(
                accountId, event.getSymbol(), event.getQuantityTargeted(), event.getSellClientOrderId());

        if (response.isSuccess() && response.id != null) {
            event.setSellOrderId(response.id);
            event.setStatus(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
            event.setSellSubmittedAt(LocalDateTime.now(ET));
            save(event);
        } else {
            // Submission failed or timed out — the order may or may not exist
            AlpacaService.OrderLookupResult lookup =
                    alpacaService.getOrderByClientOrderId(accountId, event.getSellClientOrderId());
            if (lookup.order != null) {
                event.setSellOrderId(lookup.order.id);
                event.setStatus(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
                event.setSellSubmittedAt(LocalDateTime.now(ET));
                save(event);
            } else if (lookup.confirmedAbsent) {
                event.setStatus(TlhHarvestEvent.EventStatus.FAILED);
                event.setErrorMessage(truncate("Sell submit failed: " + response.errorMessage));
                save(event);
                guard.cancelWindow(event);
                return;
            } else {
                // Lookup itself failed — leave SELL_PENDING; the poller owns it
                logger.warn("[TLH] Sell state unknown for event {} — leaving SELL_PENDING", event.getId());
                return;
            }
        }

        // Short synchronous fill check so the replacement can submit same-cycle
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(syncFillCheckDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            AlpacaService.AlpacaOrderResponse orderNow =
                    alpacaService.checkOrderStatus(accountId, event.getSellOrderId());
            if (orderNow.isFilled()) {
                handleSellFill(event, orderNow);
                if (event.getStatus() == TlhHarvestEvent.EventStatus.SELL_FILLED) {
                    submitReplacement(event);
                }
                return;
            }
        }
    }

    private void veto(TlhHarvestEvent event, String reason) {
        event.setStatus(TlhHarvestEvent.EventStatus.VETOED);
        event.setVetoReason(reason);
        save(event);
        guard.cancelWindow(event);
        logger.warn("[TLH] Event {} vetoed post-claim: {}", event.getId(), reason);
    }

    // ==================== Poller ====================

    /** 15-minute lifecycle poller (offset :07 — never collides with the investment pollers). */
    public void pollOpenEvents() {
        List<TlhHarvestEvent> openEvents =
                eventRepository.findByStatusIn(TlhHarvestEvent.IN_FLIGHT_STATUSES);
        for (TlhHarvestEvent event : openEvents) {
            try {
                pollEvent(event);
                pollFailures.remove(event.getId());
            } catch (Exception e) {
                int failures = pollFailures.merge(event.getId(), 1, Integer::sum);
                logger.error("[TLH] Poll failed for event {} ({} consecutive): {}",
                        event.getId(), failures, e.getMessage());
                if (failures >= CONSECUTIVE_FETCH_FAILURE_LIMIT) {
                    // Account closed / liquidated mid-chain
                    event.setStatus(TlhHarvestEvent.EventStatus.CANCELED_ACCOUNT);
                    event.setErrorMessage(truncate("Repeated poll failures: " + e.getMessage()));
                    save(event);
                    guard.cancelWindow(event);
                    pollFailures.remove(event.getId());
                    logger.warn("TLH_ALERT event {} canceled after repeated fetch failures", event.getId());
                }
            }
        }
        runReentries();
        runBackfillChunks();
    }

    private void pollEvent(TlhHarvestEvent event) {
        String accountId = accountIdOf(event);
        switch (event.getStatus()) {
            case SELL_PENDING -> recoverSellPending(event, accountId);
            case SELL_SUBMITTED -> advanceSellSubmitted(event, accountId);
            case SELL_FILLED -> submitReplacement(event);
            case REPLACEMENT_SUBMITTED -> advanceReplacementSubmitted(event, accountId);
            default -> { /* terminal — nothing to do */ }
        }
    }

    /** Orphaned claim (crash between claim-commit and order response): adopt or fail. */
    private void recoverSellPending(TlhHarvestEvent event, String accountId) {
        LocalDateTime staleCutoff = LocalDateTime.now(ET).minusMinutes(SELL_PENDING_STALE_MINUTES);
        if (event.getUpdatedAt() != null && event.getUpdatedAt().isAfter(staleCutoff)) {
            return; // submitSell may still be in-band
        }
        AlpacaService.OrderLookupResult lookup =
                alpacaService.getOrderByClientOrderId(accountId, event.getSellClientOrderId());
        if (lookup.order != null) {
            event.setSellOrderId(lookup.order.id);
            event.setStatus(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
            event.setSellSubmittedAt(LocalDateTime.now(ET));
            save(event);
            logger.info("[TLH] Adopted orphaned sell for event {} (order {})", event.getId(), lookup.order.id);
        } else if (lookup.confirmedAbsent) {
            event.setStatus(TlhHarvestEvent.EventStatus.FAILED);
            event.setErrorMessage("Claim orphaned; no order at broker");
            save(event);
            guard.cancelWindow(event);
            logger.warn("[TLH] Orphaned claim {} failed cleanly (no order found)", event.getId());
        }
        // lookup failed ⇒ stay SELL_PENDING, retry next tick
    }

    private void advanceSellSubmitted(TlhHarvestEvent event, String accountId) {
        AlpacaService.AlpacaOrderResponse order =
                alpacaService.checkOrderStatus(accountId, event.getSellOrderId());
        if (!order.isSuccess() && !"UNKNOWN".equals(order.status)) {
            throw new IllegalStateException("Order fetch failed: " + order.errorMessage);
        }
        if (order.isFilled()) {
            handleSellFill(event, order);
            if (event.getStatus() == TlhHarvestEvent.EventStatus.SELL_FILLED) {
                submitReplacement(event);
            }
        } else if (isTerminalUnfilled(order.status)) {
            handlePartialOrUnfilled(event, order);
        }
        // new / accepted / partially_filled ⇒ wait for the next tick or EOD sweep
    }

    private boolean isTerminalUnfilled(String status) {
        return "canceled".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)
                || "rejected".equalsIgnoreCase(status) || "done_for_day".equalsIgnoreCase(status);
    }

    /** Full fill of the harvest sell: ledger it, then decide loss vs gain. */
    private void handleSellFill(TlhHarvestEvent event, AlpacaService.AlpacaOrderResponse order) {
        LocalDateTime filledAt = parseAlpacaTimestamp(order.filledAt);
        if (filledAt == null) {
            filledAt = LocalDateTime.now(ET); // defensive; Alpaca sends filled_at on filled orders
        }
        BigDecimal filledQty = order.filledQuantity == null ? BigDecimal.ZERO : order.filledQuantity;
        BigDecimal avgPrice = order.filledAvgPrice == null ? BigDecimal.ZERO : order.filledAvgPrice;

        ledger.applySyntheticOrderFill(event.getUserId(), event.getSellOrderId(), "sell",
                event.getSymbol(), filledQty, avgPrice, filledAt, TaxLot.LotSource.EXTERNAL);

        BigDecimal netPnl = sumEventPnl(event);
        event.setQuantitySold(filledQty);
        event.setActualProceeds(filledQty.multiply(avgPrice).setScale(2, RoundingMode.DOWN));
        event.setSellFilledAt(filledAt);
        event.setTaxYear(filledAt.getYear());

        if (netPnl.compareTo(BigDecimal.ZERO) >= 0) {
            // The fill realized a net GAIN (price popped between quote and fill)
            // — no loss to protect: cancel the window, flag, re-enter promptly.
            event.setNetGain(netPnl.setScale(2, RoundingMode.HALF_UP));
            event.setRealizedLoss(BigDecimal.ZERO);
            event.setAllowedLoss(BigDecimal.ZERO);
            event.setStatus(TlhHarvestEvent.EventStatus.COMPLETED_GAIN);
            event.setReentryStatus("PENDING");
            event.setReentryDueOn(LocalDate.now(ET));
            save(event);
            guard.cancelWindow(event);
            logger.warn("TLH_ALERT gain-at-fill for event {} ({} net {})",
                    event.getId(), event.getSymbol(), netPnl);
        } else {
            event.setRealizedLoss(netPnl.abs().setScale(2, RoundingMode.HALF_UP));
            event.setAllowedLoss(event.getRealizedLoss());
            event.setStatus(TlhHarvestEvent.EventStatus.SELL_FILLED);
            save(event);
            guard.confirmWindow(event, filledAt.toLocalDate());
        }
    }

    /** Signed net PnL of the event's ledgered disposals (negative = net loss). */
    private BigDecimal sumEventPnl(TlhHarvestEvent event) {
        BigDecimal sum = BigDecimal.ZERO;
        for (com.investingapp.backend.model.LotDisposal disposal
                : lotDisposalRepository.findByHarvestEventId(event.getId())) {
            sum = sum.add(disposal.getRealizedPnl());
        }
        return sum;
    }

    /** DAY order died: dispose whatever filled, keep the window, account the wash. */
    private void handlePartialOrUnfilled(TlhHarvestEvent event, AlpacaService.AlpacaOrderResponse order) {
        BigDecimal filledQty = order.filledQuantity == null ? BigDecimal.ZERO : order.filledQuantity;
        if (filledQty.compareTo(new BigDecimal("0.000001")) <= 0) {
            event.setStatus(TlhHarvestEvent.EventStatus.CANCELED_UNFILLED);
            save(event);
            guard.cancelWindow(event);
            return;
        }

        LocalDateTime filledAt = parseAlpacaTimestamp(order.filledAt);
        if (filledAt == null) {
            filledAt = LocalDateTime.now(ET);
        }
        BigDecimal avgPrice = order.filledAvgPrice == null ? BigDecimal.ZERO : order.filledAvgPrice;

        ledger.applySyntheticOrderFill(event.getUserId(), event.getSellOrderId(), "sell",
                event.getSymbol(), filledQty, avgPrice, filledAt, TaxLot.LotSource.EXTERNAL);

        BigDecimal netPnl = sumEventPnl(event);
        event.setQuantitySold(filledQty);
        event.setActualProceeds(filledQty.multiply(avgPrice).setScale(2, RoundingMode.DOWN));
        event.setSellFilledAt(filledAt);
        event.setTaxYear(filledAt.getYear());
        event.setRealizedLoss(netPnl.compareTo(BigDecimal.ZERO) < 0
                ? netPnl.abs().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        save(event);
        guard.confirmWindow(event, filledAt.toLocalDate());

        // The partial fill is the ONE case where recently-bought shares can
        // survive the sale — run §1091 matching explicitly, never hope.
        TaxLotLedgerService.DisallowanceResult disallowance = ledger.applyWashDisallowance(
                event, catalog.expandWithSiblings(event.getSymbol()));
        event.setDisallowedLoss(disallowance.totalDisallowed());
        event.setAllowedLoss(event.getRealizedLoss().subtract(disallowance.totalDisallowed())
                .max(BigDecimal.ZERO));
        event.setStatus(TlhHarvestEvent.EventStatus.COMPLETED_PARTIAL);
        save(event);

        if (replaceOnPartial && event.getReplacementPolicy() == TlhHarvestEvent.ReplacementPolicy.PAIR) {
            submitReplacementLeg(event);
        } else {
            maybeScheduleReentry(event);
        }
    }

    // ==================== Replacement leg ====================

    private void submitReplacement(TlhHarvestEvent event) {
        if (event.getStatus() != TlhHarvestEvent.EventStatus.SELL_FILLED) {
            return;
        }
        submitReplacementLeg(event);
    }

    private void submitReplacementLeg(TlhHarvestEvent event) {
        boolean fromPartial = event.getStatus() == TlhHarvestEvent.EventStatus.COMPLETED_PARTIAL;

        if (!"live".equalsIgnoreCase(mode)
                || event.getReplacementPolicy() != TlhHarvestEvent.ReplacementPolicy.PAIR
                || event.getReplacementSymbol() == null) {
            completeWithoutReplacement(event, fromPartial);
            return;
        }
        if (!alpacaService.getMarketClock()) {
            return; // market closed — next tick retries
        }
        if (event.getReplacementAttempts() >= replacementAttemptsMax) {
            logger.warn("TLH_ALERT replacement attempts exhausted for event {}", event.getId());
            completeWithoutReplacement(event, fromPartial);
            return;
        }

        // Re-vet at buy time: the world may have changed since selection
        LocalDate today = LocalDate.now(ET);
        if (guard.isBlockedForTlhBuy(event.getUserId(), event.getReplacementSymbol(), today)) {
            logger.info("[TLH] Replacement {} for event {} blocked at buy time",
                    event.getReplacementSymbol(), event.getId());
            completeWithoutReplacement(event, fromPartial);
            return;
        }

        String accountId = accountIdOf(event);
        BigDecimal buyingPower = alpacaService.getBuyingPower(accountId);
        BigDecimal notional = event.getActualProceeds().min(buyingPower).setScale(2, RoundingMode.DOWN);
        if (notional.compareTo(BigDecimal.ONE) < 0) {
            completeWithoutReplacement(event, fromPartial);
            return;
        }

        // Persist the per-attempt client id BEFORE the HTTP call
        int attempt = event.getReplacementAttempts() + 1;
        String clientOrderId = "tlh-b-" + event.getId() + "-a" + attempt;
        event.setReplacementAttempts(attempt);
        event.setReplacementClientOrderId(clientOrderId);
        event.setReplacementNotional(notional);
        save(event);

        AlpacaService.AssetInfo asset = alpacaService.getAssetInfo(event.getReplacementSymbol());
        AlpacaService.AlpacaOrderResponse response;
        if (asset.fractionable) {
            response = alpacaService.placeBuyOrderWithClientId(
                    accountId, event.getReplacementSymbol(), notional, clientOrderId);
        } else {
            BigDecimal price = portfolioDashboardService.getAskPrice(event.getReplacementSymbol());
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                price = portfolioDashboardService.getCurrentPrice(event.getReplacementSymbol());
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("[TLH] No price for non-fractionable replacement {} — retrying next tick",
                        event.getReplacementSymbol());
                return;
            }
            BigDecimal qty = notional.divide(price, 0, RoundingMode.DOWN);
            if (qty.compareTo(BigDecimal.ONE) < 0) {
                completeWithoutReplacement(event, fromPartial);
                return;
            }
            response = alpacaService.placeBuyOrderWithQuantityAndClientId(
                    accountId, event.getReplacementSymbol(), qty, clientOrderId);
        }

        if (response.isSuccess() && response.id != null) {
            event.setReplacementOrderId(response.id);
            if (!fromPartial) {
                event.setStatus(TlhHarvestEvent.EventStatus.REPLACEMENT_SUBMITTED);
            }
            save(event);
        } else {
            // Duplicate-reject or timeout: the order may exist — adopt it
            AlpacaService.OrderLookupResult lookup =
                    alpacaService.getOrderByClientOrderId(accountId, clientOrderId);
            if (lookup.order != null) {
                event.setReplacementOrderId(lookup.order.id);
                if (!fromPartial) {
                    event.setStatus(TlhHarvestEvent.EventStatus.REPLACEMENT_SUBMITTED);
                }
                save(event);
            } else {
                logger.warn("[TLH] Replacement submit failed for event {} (attempt {}): {}",
                        event.getId(), attempt, response.errorMessage);
                // stay SELL_FILLED (or COMPLETED_PARTIAL) — bounded by attempts
            }
        }
    }

    private void completeWithoutReplacement(TlhHarvestEvent event, boolean fromPartial) {
        if (!fromPartial) {
            event.setStatus(TlhHarvestEvent.EventStatus.COMPLETED_NO_REPLACEMENT);
        }
        save(event);
        maybeScheduleReentry(event);
    }

    private void maybeScheduleReentry(TlhHarvestEvent event) {
        if (!reentryEnabled || event.getReentryStatus() != null) {
            return;
        }
        boolean holdCash = event.getReplacementPolicy() == TlhHarvestEvent.ReplacementPolicy.HOLD_CASH;
        boolean degradedPair = event.getReplacementPolicy() == TlhHarvestEvent.ReplacementPolicy.PAIR
                && event.getStatus() == TlhHarvestEvent.EventStatus.COMPLETED_NO_REPLACEMENT;
        if (!holdCash && !degradedPair) {
            return;
        }
        LocalDate dueOn = windowRepository.findByHarvestEventId(event.getId())
                .map(WashSaleWindow::getEndDate)
                .map(endDate -> endDate.plusDays(1))
                .orElse(LocalDate.now(ET).plusDays(WashSaleGuardService.WASH_WINDOW_DAYS + 1L));
        event.setReentryStatus("PENDING");
        event.setReentryDueOn(dueOn);
        save(event);
    }

    private void advanceReplacementSubmitted(TlhHarvestEvent event, String accountId) {
        AlpacaService.AlpacaOrderResponse order =
                alpacaService.checkOrderStatus(accountId, event.getReplacementOrderId());
        if (!order.isSuccess() && !"UNKNOWN".equals(order.status)) {
            throw new IllegalStateException("Replacement fetch failed: " + order.errorMessage);
        }
        if (order.isFilled()) {
            recordReplacementFill(event, order);
            event.setStatus(TlhHarvestEvent.EventStatus.COMPLETED);
            save(event);
        } else if (isTerminalUnfilled(order.status)) {
            BigDecimal filledQty = order.filledQuantity == null ? BigDecimal.ZERO : order.filledQuantity;
            if (filledQty.compareTo(new BigDecimal("0.000001")) > 0) {
                recordReplacementFill(event, order);
                BigDecimal filledNotional = filledQty.multiply(
                        order.filledAvgPrice == null ? BigDecimal.ZERO : order.filledAvgPrice)
                        .setScale(2, RoundingMode.DOWN);
                BigDecimal remainder = event.getReplacementNotional().subtract(filledNotional);
                if (remainder.compareTo(BigDecimal.ONE) >= 0
                        && event.getReplacementAttempts() < replacementAttemptsMax) {
                    // Retry the REMAINDER only
                    event.setActualProceeds(remainder);
                    event.setStatus(TlhHarvestEvent.EventStatus.SELL_FILLED);
                    save(event);
                } else {
                    event.setStatus(TlhHarvestEvent.EventStatus.COMPLETED);
                    save(event);
                }
            } else {
                // Nothing filled — rejected/expired outright
                event.setStatus(TlhHarvestEvent.EventStatus.SELL_FILLED);
                save(event);
            }
        }
    }

    private void recordReplacementFill(TlhHarvestEvent event, AlpacaService.AlpacaOrderResponse order) {
        LocalDateTime filledAt = parseAlpacaTimestamp(order.filledAt);
        if (filledAt == null) {
            filledAt = LocalDateTime.now(ET);
        }
        BigDecimal filledQty = order.filledQuantity == null ? BigDecimal.ZERO : order.filledQuantity;
        BigDecimal avgPrice = order.filledAvgPrice == null ? BigDecimal.ZERO : order.filledAvgPrice;
        ledger.applySyntheticOrderFill(event.getUserId(), event.getReplacementOrderId(), "buy",
                event.getReplacementSymbol(), filledQty, avgPrice, filledAt, TaxLot.LotSource.TLH_REPLACEMENT);
        event.setReplacementFilledQty(filledQty);
        event.setReplacementFilledAvgPrice(avgPrice);
        event.setReplacementFilledAt(filledAt);
    }

    // ==================== Re-entry leg (tier-blind) ====================

    /**
     * Day-31 re-entry for hold-cash harvests (and prompt re-entry after a
     * gain-at-fill). Selected by reentryStatus + due date, NEVER by the
     * tier-gated user query — a downgraded user's cash must still re-enter.
     */
    private void runReentries() {
        // Advance submitted re-entries first
        for (TlhHarvestEvent event : eventRepository.findByReentryStatus("SUBMITTED")) {
            try {
                String accountId = accountIdOf(event);
                AlpacaService.AlpacaOrderResponse order =
                        alpacaService.checkOrderStatus(accountId, event.getReentryOrderId());
                if (order.isFilled()) {
                    LocalDateTime filledAt = parseAlpacaTimestamp(order.filledAt);
                    ledger.applySyntheticOrderFill(event.getUserId(), event.getReentryOrderId(), "buy",
                            event.getSymbol(),
                            order.filledQuantity == null ? BigDecimal.ZERO : order.filledQuantity,
                            order.filledAvgPrice == null ? BigDecimal.ZERO : order.filledAvgPrice,
                            filledAt == null ? LocalDateTime.now(ET) : filledAt,
                            TaxLot.LotSource.TLH_REENTRY);
                    event.setReentryStatus("FILLED");
                    event.setStatus(TlhHarvestEvent.EventStatus.REENTERED);
                    save(event);
                } else if (isTerminalUnfilled(order.status)) {
                    event.setReentryStatus("PENDING"); // retry next due day
                    save(event);
                }
            } catch (Exception e) {
                logger.error("[TLH] Re-entry status check failed for event {}: {}",
                        event.getId(), e.getMessage());
            }
        }

        if (!"live".equalsIgnoreCase(mode) || !alpacaService.getMarketClock()) {
            return; // defer — the runbook documents that mode=off pauses re-entries
        }
        LocalDate today = LocalDate.now(ET);
        for (TlhHarvestEvent event : eventRepository
                .findByReentryStatusAndReentryDueOnLessThanEqual("PENDING", today)) {
            try {
                if (guard.isBlockedForTlhBuy(event.getUserId(), event.getSymbol(), today)) {
                    continue; // another window covers the symbol — try next day
                }
                if (event.getReentryAttempts() >= REENTRY_ATTEMPTS_MAX) {
                    event.setReentryStatus("ABANDONED");
                    save(event);
                    logger.warn("TLH_ALERT re-entry abandoned for event {} after {} attempts",
                            event.getId(), REENTRY_ATTEMPTS_MAX);
                    continue;
                }
                String accountId = accountIdOf(event);
                BigDecimal buyingPower = alpacaService.getBuyingPower(accountId);
                BigDecimal notional = event.getActualProceeds() == null
                        ? BigDecimal.ZERO
                        : event.getActualProceeds().min(buyingPower).setScale(2, RoundingMode.DOWN);
                if (notional.compareTo(BigDecimal.ONE) < 0) {
                    event.setReentryStatus("ABANDONED");
                    save(event);
                    continue;
                }
                int attempt = event.getReentryAttempts() + 1;
                String clientOrderId = "tlh-r-" + event.getId() + "-a" + attempt;
                event.setReentryAttempts(attempt);
                event.setReentryClientOrderId(clientOrderId);
                save(event);

                AlpacaService.AlpacaOrderResponse response = alpacaService.placeBuyOrderWithClientId(
                        accountId, event.getSymbol(), notional, clientOrderId);
                if (response.isSuccess() && response.id != null) {
                    event.setReentryOrderId(response.id);
                    event.setReentryStatus("SUBMITTED");
                    save(event);
                } else {
                    AlpacaService.OrderLookupResult lookup =
                            alpacaService.getOrderByClientOrderId(accountId, clientOrderId);
                    if (lookup.order != null) {
                        event.setReentryOrderId(lookup.order.id);
                        event.setReentryStatus("SUBMITTED");
                        save(event);
                    }
                    // else: stay PENDING, bounded by attempts
                }
            } catch (Exception e) {
                logger.error("[TLH] Re-entry failed for event {}: {}", event.getId(), e.getMessage());
            }
        }
    }

    // ==================== Backfill ====================

    /** Ingest bounded chunks of historical fills per tick until exhaustion. */
    private void runBackfillChunks() {
        for (TlhUserState state : userStateRepository.findByLedgerStatus(TlhUserState.LedgerStatus.BACKFILLING)) {
            try {
                User user = userRepository.findById(state.getUserId()).orElse(null);
                if (user == null || user.getAlpacaAccountId() == null) {
                    continue;
                }
                String accountId = encryptionService.decrypt(user.getAlpacaAccountId());
                AlpacaService.FillActivityPage page = alpacaService.getFillActivities(
                        accountId, state.getCursorAfter(), state.getCursorActivityId(), BACKFILL_PAGES_PER_TICK);
                for (AlpacaService.FillActivity fill : page.activities) {
                    applyIngestedFill(state.getUserId(), fill);
                }
                state.setBackfillPagesDone(state.getBackfillPagesDone() + BACKFILL_PAGES_PER_TICK);
                state.setCursorActivityId(page.nextPageToken);
                state.setLastSyncAt(LocalDateTime.now(ET));
                if (page.exhausted) {
                    if (!page.activities.isEmpty()) {
                        AlpacaService.FillActivity last = page.activities.get(page.activities.size() - 1);
                        state.setCursorAfter(last.transactionTime);
                    }
                    state.setCursorActivityId(null);
                    state.setLedgerStatus(TlhUserState.LedgerStatus.READY);
                    logger.info("[TLH] Backfill complete for user {}", state.getUserId());
                }
                userStateRepository.save(state);
            } catch (Exception e) {
                logger.error("[TLH] Backfill chunk failed for user {}: {}", state.getUserId(), e.getMessage());
            }
        }
    }

    /** Shared ingestion mapping used by backfill, cycle sync, and nightly reconciliation. */
    public void applyIngestedFill(Long userId, AlpacaService.FillActivity fill) {
        LocalDateTime transactionTime = parseAlpacaTimestamp(fill.transactionTime);
        if (transactionTime == null || fill.side == null) {
            return;
        }
        boolean isBuy = fill.side.toLowerCase().startsWith("buy");
        ledger.applyFill(userId, new TaxLotLedgerService.FillRecord(
                fill.id, fill.orderId, isBuy ? "buy" : "sell", fill.symbol,
                fill.qty, fill.price, transactionTime, TaxLot.LotSource.BACKFILL));
    }

    // ==================== EOD sweep ====================

    /**
     * 16:40 ET: no TLH order rests overnight. Cancel-then-verify at the broker
     * — never terminalize from the local clock alone.
     */
    public void sweepEndOfDay(LocalDate today) {
        for (TlhHarvestEvent event : eventRepository.findByStatusIn(List.of(
                TlhHarvestEvent.EventStatus.SELL_SUBMITTED,
                TlhHarvestEvent.EventStatus.REPLACEMENT_SUBMITTED))) {
            try {
                String accountId = accountIdOf(event);
                boolean isSellLeg = event.getStatus() == TlhHarvestEvent.EventStatus.SELL_SUBMITTED;
                String orderId = isSellLeg ? event.getSellOrderId() : event.getReplacementOrderId();
                AlpacaService.AlpacaOrderResponse order = alpacaService.checkOrderStatus(accountId, orderId);
                if (order.isFilled()) {
                    pollEvent(event);
                    continue;
                }
                if (!isTerminalUnfilled(order.status)) {
                    alpacaService.cancelOrder(accountId, orderId);
                    order = alpacaService.checkOrderStatus(accountId, orderId);
                }
                if (order.isFilled()) {
                    pollEvent(event);
                } else if (isTerminalUnfilled(order.status)) {
                    if (isSellLeg) {
                        handlePartialOrUnfilled(event, order);
                    } else {
                        advanceReplacementSubmitted(event, accountId);
                    }
                }
            } catch (Exception e) {
                logger.error("[TLH] EOD sweep failed for event {}: {}", event.getId(), e.getMessage());
            }
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}

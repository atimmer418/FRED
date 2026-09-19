package com.investingapp.backend.service;

import com.investingapp.backend.model.DripExecution;
import com.investingapp.backend.model.InvestmentTrade;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.DripExecutionRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.repository.WashSaleWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * THE single buy-decision authority for wash-sale enforcement. Every
 * automatic buy path (scheduled contribution slices, DRIP reinvestment,
 * TLH's own replacement/re-entry buys, any future rebalancer) asks this
 * service before buying.
 *
 * Deliberately reads ONLY WashSaleWindow rows — no tier, mode, or enrollment
 * input — so enforcement survives subscription expiry, unenrollment, and the
 * kill switch: a window protects a loss the user already realized.
 *
 * Blocking contract (date-based; `active` is an index hint): a buy of symbol
 * S or any identity-group sibling of S is blocked iff an active-flagged
 * window row exists with startDate <= buyDate <= endDate.
 *
 * Statutory numbers are constants here, never config — the law isn't tunable.
 */
@Service
public class WashSaleGuardService {

    private static final Logger logger = LoggerFactory.getLogger(WashSaleGuardService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    public static final int WASH_WINDOW_DAYS = 30;
    public static final int PENDING_BUY_LOOKBACK_DAYS = 5;

    private final WashSaleWindowRepository windowRepository;
    private final InvestmentTradeRepository investmentTradeRepository;
    private final DripExecutionRepository dripExecutionRepository;
    private final TlhCatalog catalog;
    private final String blockedBuyPolicy;
    private final String dripBlockedPolicy;

    public WashSaleGuardService(WashSaleWindowRepository windowRepository,
            InvestmentTradeRepository investmentTradeRepository,
            DripExecutionRepository dripExecutionRepository,
            TlhCatalog catalog,
            @Value("${app.tlh.blocked-buy-policy:redirect}") String blockedBuyPolicy,
            @Value("${app.tlh.drip-blocked-policy:skip}") String dripBlockedPolicy) {
        this.windowRepository = windowRepository;
        this.investmentTradeRepository = investmentTradeRepository;
        this.dripExecutionRepository = dripExecutionRepository;
        this.catalog = catalog;
        this.blockedBuyPolicy = blockedBuyPolicy;
        this.dripBlockedPolicy = dripBlockedPolicy;
    }

    public enum TradeSource {
        SCHEDULED_BUY, DRIP, USER_LUMP_SUM, TLH_REPLACEMENT, TLH_REENTRY, REBALANCE
    }

    public enum GuardAction {
        ALLOW, REDIRECT, SKIP, BLOCK
    }

    public record GuardDecision(GuardAction action, String redirectSymbol, WashSaleWindow window) {
        public static GuardDecision allow() {
            return new GuardDecision(GuardAction.ALLOW, null, null);
        }
    }

    /**
     * May this buy happen today? Source-aware:
     * - SCHEDULED_BUY: redirect to the window's replacement (itself re-checked,
     *   max 2 hops) or skip to cash, per config.
     * - DRIP: skip (default) or redirect, per config.
     * - USER_LUMP_SUM: ALLOW — an explicit, already-funded user order is never
     *   stranded; the IRS disallows the loss, it doesn't forbid the trade.
     *   Nightly detection applies the disallowance accounting.
     * - TLH_REPLACEMENT / TLH_REENTRY / REBALANCE: hard BLOCK.
     */
    public GuardDecision checkBuy(Long userId, String symbol, TradeSource source, LocalDate buyDate) {
        WashSaleWindow window = findBlockingWindow(userId, symbol, buyDate);
        if (window == null) {
            return GuardDecision.allow();
        }
        switch (source) {
            case USER_LUMP_SUM:
                return GuardDecision.allow();
            case SCHEDULED_BUY:
                return resolveBlockedAutoBuy(userId, window, blockedBuyPolicy, buyDate);
            case DRIP:
                return resolveBlockedAutoBuy(userId, window, dripBlockedPolicy, buyDate);
            case TLH_REPLACEMENT:
            case TLH_REENTRY:
            case REBALANCE:
            default:
                return new GuardDecision(GuardAction.BLOCK, null, window);
        }
    }

    private GuardDecision resolveBlockedAutoBuy(Long userId, WashSaleWindow window,
            String policy, LocalDate buyDate) {
        if (!"redirect".equalsIgnoreCase(policy)) {
            return new GuardDecision(GuardAction.SKIP, null, window);
        }
        String target = window.getRedirectSymbol();
        // Null target (hold-cash harvests) or a target that is itself windowed
        // degrades to skip-to-cash — never an unguarded buy, never a loop.
        if (target == null || target.isBlank()) {
            return new GuardDecision(GuardAction.SKIP, null, window);
        }
        if (findBlockingWindow(userId, target, buyDate) != null) {
            return new GuardDecision(GuardAction.SKIP, null, window);
        }
        return new GuardDecision(GuardAction.REDIRECT, target, window);
    }

    /** Convenience for TLH's own buy legs: blocked when any window covers the symbol or a sibling. */
    public boolean isBlockedForTlhBuy(Long userId, String symbol, LocalDate buyDate) {
        return findBlockingWindow(userId, symbol, buyDate) != null;
    }

    /** The window (if any) that would block a buy of this symbol today — for callers that need its dates. */
    public WashSaleWindow blockingWindow(Long userId, String symbol, LocalDate buyDate) {
        return findBlockingWindow(userId, symbol, buyDate);
    }

    private WashSaleWindow findBlockingWindow(Long userId, String symbol, LocalDate buyDate) {
        Set<String> expanded = catalog.expandWithSiblings(symbol);
        List<WashSaleWindow> windows = windowRepository.findByUserIdAndSymbolInAndActiveTrue(userId, expanded);
        for (WashSaleWindow window : windows) {
            if (!buyDate.isBefore(window.getStartDate()) && !buyDate.isAfter(window.getEndDate())) {
                return window;
            }
        }
        return null;
    }

    /**
     * Before-window veto input: any in-flight local buy of the symbol or a
     * sibling (recent open InvestmentTrade, or a DRIP execution detected /
     * ordered). Time-bounded so stale SUBMITTED rows can't veto forever.
     */
    public boolean hasPendingBuyActivity(Long userId, User user, String symbol) {
        LocalDateTime cutoff = LocalDateTime.now(ET).minusDays(PENDING_BUY_LOOKBACK_DAYS);
        List<InvestmentTrade.TradeStatus> openTradeStatuses = List.of(
                InvestmentTrade.TradeStatus.PENDING,
                InvestmentTrade.TradeStatus.SUBMITTED,
                InvestmentTrade.TradeStatus.PARTIALLY_FILLED);
        List<DripExecution.DripStatus> openDripStatuses = List.of(
                DripExecution.DripStatus.DETECTED,
                DripExecution.DripStatus.ORDER_PLACED);
        for (String candidate : catalog.expandWithSiblings(symbol)) {
            if (!investmentTradeRepository
                    .findByUserIdAndSymbolAndStatusInAndCreatedAtAfter(userId, candidate, openTradeStatuses, cutoff)
                    .isEmpty()) {
                return true;
            }
            if (user != null && dripExecutionRepository
                    .existsByUserAndSymbolAndStatusIn(user, candidate, openDripStatuses)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Open the provisional window in the SAME transaction as the harvest
     * claim, BEFORE any order exists. Enforcement precedes the sale by
     * construction.
     */
    @Transactional
    public WashSaleWindow openProvisionalWindow(TlhHarvestEvent event, LocalDate today, String redirectSymbol) {
        WashSaleWindow window = new WashSaleWindow();
        window.setUserId(event.getUserId());
        window.setSymbol(event.getSymbol());
        window.setStartDate(today);
        window.setEndDate(today.plusDays(WASH_WINDOW_DAYS));
        window.setReason(WashSaleWindow.WindowReason.HARVEST_SALE);
        window.setHarvestEventId(event.getId());
        window.setRedirectSymbol(redirectSymbol);
        window.setActive(true);
        return windowRepository.save(window);
    }

    /** Anchor the window to the authoritative fill date. */
    @Transactional
    public void confirmWindow(TlhHarvestEvent event, LocalDate fillDate) {
        windowRepository.findByHarvestEventId(event.getId()).ifPresent(window -> {
            window.setStartDate(fillDate);
            window.setEndDate(fillDate.plusDays(WASH_WINDOW_DAYS));
            window.setActive(true);
            windowRepository.save(window);
        });
    }

    /** Dead window: the sell never happened (or realized a net gain). */
    @Transactional
    public void cancelWindow(TlhHarvestEvent event) {
        windowRepository.findByHarvestEventId(event.getId()).ifPresent(window -> {
            window.setActive(false);
            // Rewind endDate so the date-based contract agrees with the flag
            window.setEndDate(window.getStartDate().minusDays(1));
            windowRepository.save(window);
        });
    }

    /** Nightly sweep: retire windows past their end date. */
    @Transactional
    public int expireWindows(LocalDate today) {
        List<WashSaleWindow> expired = windowRepository.findByActiveTrueAndEndDateBefore(today);
        for (WashSaleWindow window : expired) {
            window.setActive(false);
            windowRepository.save(window);
        }
        if (!expired.isEmpty()) {
            logger.info("[TLH] Expired {} wash-sale windows", expired.size());
        }
        return expired.size();
    }
}

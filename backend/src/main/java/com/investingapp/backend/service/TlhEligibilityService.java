package com.investingapp.backend.service;

import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.TaxLot;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.TaxLotRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import com.investingapp.backend.service.TaxLotLedgerService.PositionSnapshot;
import com.investingapp.backend.service.TaxLotLedgerService.SalePreview;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Pure, rule-based harvest eligibility. Positions and ledger previews are
 * passed in (no Alpaca calls here); every rule is a global config threshold —
 * there is deliberately no per-user tuning surface.
 *
 * Portfolio-agnostic by construction: the evaluation runs over whatever
 * positions the account actually holds — default trio, custom ETFs, single
 * stocks — through identical machinery.
 */
@Service
public class TlhEligibilityService {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** Statuses that do NOT count against the cooldown (nothing was sold). */
    private static final List<TlhHarvestEvent.EventStatus> COOLDOWN_EXEMPT_STATUSES = List.of(
            TlhHarvestEvent.EventStatus.VETOED,
            TlhHarvestEvent.EventStatus.EXPIRED,
            TlhHarvestEvent.EventStatus.CANCELED_UNFILLED,
            TlhHarvestEvent.EventStatus.FAILED);

    public static final String VETO_LEDGER_NOT_READY = "LEDGER_NOT_READY";
    public static final String VETO_UNRECONCILED = "UNRECONCILED";
    public static final String VETO_WINDOW_ACTIVE = "WINDOW_ACTIVE";
    public static final String VETO_SIBLING_HELD = "SIBLING_HELD";
    public static final String VETO_SIBLING_RECENT_BUY = "SIBLING_RECENT_BUY";
    public static final String VETO_COOLDOWN = "COOLDOWN";
    public static final String VETO_BELOW_MIN_VALUE = "BELOW_MIN_VALUE";
    public static final String VETO_ABOVE_MAX_NOTIONAL = "ABOVE_MAX_NOTIONAL";
    public static final String VETO_BAD_BASIS = "BAD_BASIS";
    public static final String VETO_NOT_ALL_LOTS_UNDERWATER = "NOT_ALL_LOTS_UNDERWATER";
    public static final String VETO_BELOW_LOSS_THRESHOLDS = "BELOW_LOSS_THRESHOLDS";
    public static final String VETO_PENDING_BUYS = "PENDING_BUYS";
    public static final String VETO_SHARES_HELD = "SHARES_HELD";
    public static final String VETO_ASSET_INACTIVE = "ASSET_INACTIVE";

    private final TlhCatalog catalog;
    private final TlhHarvestEventRepository harvestEventRepository;
    private final TaxLotRepository taxLotRepository;
    private final WashSaleGuardService washSaleGuardService;
    private final BigDecimal minLossDollars;
    private final BigDecimal minLossPercent;
    private final BigDecimal minPositionValue;
    private final BigDecimal maxNotionalPerHarvest;
    private final int maxHarvestsPerUserPerCycle;

    public TlhEligibilityService(TlhCatalog catalog,
            TlhHarvestEventRepository harvestEventRepository,
            TaxLotRepository taxLotRepository,
            WashSaleGuardService washSaleGuardService,
            @Value("${app.tlh.min-loss-dollars:50.00}") BigDecimal minLossDollars,
            @Value("${app.tlh.min-loss-percent:5.0}") BigDecimal minLossPercent,
            @Value("${app.tlh.min-position-value:100.00}") BigDecimal minPositionValue,
            @Value("${app.tlh.max-notional-per-harvest:25000.00}") BigDecimal maxNotionalPerHarvest,
            @Value("${app.tlh.max-harvests-per-user-per-cycle:2}") int maxHarvestsPerUserPerCycle) {
        this.catalog = catalog;
        this.harvestEventRepository = harvestEventRepository;
        this.taxLotRepository = taxLotRepository;
        this.washSaleGuardService = washSaleGuardService;
        this.minLossDollars = minLossDollars;
        this.minLossPercent = minLossPercent;
        this.minPositionValue = minPositionValue;
        this.maxNotionalPerHarvest = maxNotionalPerHarvest;
        this.maxHarvestsPerUserPerCycle = maxHarvestsPerUserPerCycle;
    }

    public record HarvestCandidate(String symbol, BigDecimal qty, BigDecimal estLoss,
            BigDecimal estLossPct, SalePreview preview) {
    }

    public record Veto(String symbol, String reason) {
    }

    public record EvalResult(List<HarvestCandidate> candidates, List<Veto> vetoes) {
    }

    /**
     * Evaluate every position; first failing rule wins and is recorded. The
     * survivors are ranked by estimated loss (desc) and capped per user.
     */
    public EvalResult evaluate(User user, boolean ledgerReady, List<PositionSnapshot> positions,
            Map<String, SalePreview> previews, Set<String> unreconciledSymbols, LocalDate today) {
        List<HarvestCandidate> candidates = new ArrayList<>();
        List<Veto> vetoes = new ArrayList<>();

        Set<String> heldSymbols = new HashSet<>();
        for (PositionSnapshot position : positions) {
            heldSymbols.add(position.symbol().toUpperCase());
        }

        for (PositionSnapshot position : positions) {
            String symbol = position.symbol().toUpperCase();
            String veto = firstVeto(user, ledgerReady, position, previews.get(symbol),
                    unreconciledSymbols, heldSymbols, today);
            if (veto != null) {
                vetoes.add(new Veto(symbol, veto));
                continue;
            }
            SalePreview preview = previews.get(symbol);
            BigDecimal estLoss = position.unrealizedPl().abs();
            BigDecimal estLossPct = position.costBasis().compareTo(BigDecimal.ZERO) > 0
                    ? estLoss.multiply(new BigDecimal("100"))
                            .divide(position.costBasis(), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            candidates.add(new HarvestCandidate(symbol, position.qty(), estLoss, estLossPct, preview));
        }

        candidates.sort(Comparator.comparing(HarvestCandidate::estLoss).reversed());
        if (candidates.size() > maxHarvestsPerUserPerCycle) {
            candidates = new ArrayList<>(candidates.subList(0, maxHarvestsPerUserPerCycle));
        }
        return new EvalResult(candidates, vetoes);
    }

    private String firstVeto(User user, boolean ledgerReady, PositionSnapshot position,
            SalePreview preview, Set<String> unreconciledSymbols, Set<String> heldSymbols, LocalDate today) {
        String symbol = position.symbol().toUpperCase();
        Set<String> siblings = catalog.identitySiblings(symbol);
        Set<String> expanded = catalog.expandWithSiblings(symbol);

        if (!ledgerReady) {
            return VETO_LEDGER_NOT_READY;
        }
        if (unreconciledSymbols.contains(symbol) || preview == null || !preview.hasOpenLots()) {
            return VETO_UNRECONCILED;
        }
        // Selling during a window is legal (windows block buys), but a window
        // on the symbol means a loss was just harvested — repeat harvesting is
        // governed by the cooldown; here the window veto covers the odd case
        // of a live redirect target being harvested mid-window.
        if (washSaleGuardService.isBlockedForTlhBuy(user.getId(), symbol, today)) {
            return VETO_WINDOW_ACTIVE;
        }
        // Substantially-identical siblings: holding, recently buying, or about
        // to buy a sibling makes the sibling replacement shares under §1091.
        for (String sibling : siblings) {
            if (heldSymbols.contains(sibling)) {
                return VETO_SIBLING_HELD;
            }
        }
        LocalDateTime siblingBuyCutoff = LocalDateTime.now(ET).minusDays(31);
        if (!siblings.isEmpty() && taxLotRepository.existsByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
                user.getId(), siblings, TaxLot.LotStatus.OPEN, siblingBuyCutoff)) {
            return VETO_SIBLING_RECENT_BUY;
        }
        LocalDateTime cooldownCutoff = LocalDateTime.now(ET).minusDays(catalog.effectiveCooldownDays());
        if (harvestEventRepository.existsByUserIdAndSymbolInAndModeAndStatusNotInAndCreatedAtAfter(
                user.getId(), expanded, TlhHarvestEvent.HarvestMode.LIVE,
                COOLDOWN_EXEMPT_STATUSES, cooldownCutoff)) {
            return VETO_COOLDOWN;
        }
        if (position.marketValue() == null || position.marketValue().compareTo(minPositionValue) < 0) {
            return VETO_BELOW_MIN_VALUE;
        }
        if (position.marketValue().compareTo(maxNotionalPerHarvest) > 0) {
            // Bigger positions are SKIPPED, never partially sold — partial FIFO
            // sells can realize gains on old cheap lots.
            return VETO_ABOVE_MAX_NOTIONAL;
        }
        if (position.costBasis() == null || position.costBasis().compareTo(BigDecimal.ZERO) <= 0) {
            return VETO_BAD_BASIS;
        }
        if (!preview.allLotsUnderwater()) {
            return VETO_NOT_ALL_LOTS_UNDERWATER;
        }
        BigDecimal estLoss = position.unrealizedPl() == null ? BigDecimal.ZERO : position.unrealizedPl().negate();
        if (position.unrealizedPl() == null || position.unrealizedPl().compareTo(BigDecimal.ZERO) >= 0) {
            return VETO_BELOW_LOSS_THRESHOLDS;
        }
        BigDecimal estLossPct = estLoss.multiply(new BigDecimal("100"))
                .divide(position.costBasis(), 2, RoundingMode.HALF_UP);
        if (estLoss.compareTo(minLossDollars) < 0 || estLossPct.compareTo(minLossPercent) < 0) {
            return VETO_BELOW_LOSS_THRESHOLDS;
        }
        if (washSaleGuardService.hasPendingBuyActivity(user.getId(), user, symbol)) {
            return VETO_PENDING_BUYS;
        }
        if (position.qtyAvailable() == null
                || position.qtyAvailable().subtract(position.qty()).abs()
                        .compareTo(new BigDecimal("0.000001")) > 0) {
            // Shares tied up in open orders — full-position sell impossible
            return VETO_SHARES_HELD;
        }
        if (!position.assetActiveTradable()) {
            return VETO_ASSET_INACTIVE;
        }
        return null;
    }
}

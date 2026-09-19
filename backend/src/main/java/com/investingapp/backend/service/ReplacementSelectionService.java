package com.investingapp.backend.service;

import com.investingapp.backend.model.LotDisposal;
import com.investingapp.backend.model.TlhHarvestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * Generalized replacement selection — identical machinery for the default
 * trio and arbitrary symbols:
 *
 * Stage 1: curated pair map (TlhCatalog), walked in rank order with universal
 * vetting. Stage 2: identity groups are the negative space (a candidate that
 * is substantially identical to the harvested symbol or to anything else in
 * this cycle's plan is rejected). Stage 3: terminal policy for unmapped
 * symbols — skip the harvest (default, zero out-of-market risk) or
 * hold-cash-and-re-enter on day 31. Single stocks always land in Stage 3:
 * auto-picking a "similar" stock is either substantially identical or
 * personalized advice, both forbidden.
 */
@Service
public class ReplacementSelectionService {

    private static final Logger logger = LoggerFactory.getLogger(ReplacementSelectionService.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int RECENT_LOSS_SALE_DAYS = 30;

    private final TlhCatalog catalog;
    private final com.investingapp.backend.repository.LotDisposalRepository lotDisposalRepository;
    private final WashSaleGuardService washSaleGuardService;
    private final AlpacaService alpacaService;
    private final String unmappedFallback;

    public ReplacementSelectionService(TlhCatalog catalog,
            com.investingapp.backend.repository.LotDisposalRepository lotDisposalRepository,
            WashSaleGuardService washSaleGuardService,
            AlpacaService alpacaService,
            @Value("${app.tlh.unmapped-fallback:skip}") String unmappedFallback) {
        this.catalog = catalog;
        this.lotDisposalRepository = lotDisposalRepository;
        this.washSaleGuardService = washSaleGuardService;
        this.alpacaService = alpacaService;
        this.unmappedFallback = unmappedFallback;
    }

    public enum DecisionKind {
        BUY, HOLD_CASH, SKIP_HARVEST
    }

    public record ReplacementDecision(DecisionKind kind, String symbol, boolean fractionable,
            TlhHarvestEvent.ReplacementPolicy policy) {

        public static ReplacementDecision buy(String symbol, boolean fractionable) {
            return new ReplacementDecision(DecisionKind.BUY, symbol, fractionable,
                    TlhHarvestEvent.ReplacementPolicy.PAIR);
        }

        public static ReplacementDecision holdCash() {
            return new ReplacementDecision(DecisionKind.HOLD_CASH, null, false,
                    TlhHarvestEvent.ReplacementPolicy.HOLD_CASH);
        }

        public static ReplacementDecision skip() {
            return new ReplacementDecision(DecisionKind.SKIP_HARVEST, null, false,
                    TlhHarvestEvent.ReplacementPolicy.NONE);
        }
    }

    /**
     * Pick a replacement for a harvested symbol. sameCycleSymbols carries all
     * symbols already planned this cycle for this user (candidates AND their
     * chosen replacements) so the plan can't harvest a symbol while also
     * buying it as another harvest's replacement.
     */
    public ReplacementDecision select(Long userId, String harvestSymbol, LocalDate today,
            Set<String> sameCycleSymbols) {
        String harvested = harvestSymbol.toUpperCase();
        Set<String> harvestedSiblings = catalog.identitySiblings(harvested);

        for (String candidate : catalog.replacementCandidates(harvested)) {
            String reason = vetCandidate(userId, candidate, harvested, harvestedSiblings,
                    today, sameCycleSymbols);
            if (reason == null) {
                boolean fractionable = alpacaService.getAssetInfo(candidate).fractionable;
                return ReplacementDecision.buy(candidate, fractionable);
            }
            logger.info("[TLH] Replacement candidate {} for {} rejected: {}", candidate, harvested, reason);
        }

        if ("hold-cash".equalsIgnoreCase(unmappedFallback)) {
            return ReplacementDecision.holdCash();
        }
        return ReplacementDecision.skip();
    }

    /** Null when the candidate passes every check; otherwise the rejection reason. */
    private String vetCandidate(Long userId, String candidate, String harvested,
            Set<String> harvestedSiblings, LocalDate today, Set<String> sameCycleSymbols) {
        if (candidate.equals(harvested)) {
            return "IS_HARVESTED_SYMBOL";
        }
        if (harvestedSiblings.contains(candidate)) {
            return "SIBLING_OF_HARVESTED";
        }
        if (sameCycleSymbols != null) {
            for (String planned : sameCycleSymbols) {
                if (candidate.equals(planned) || catalog.identitySiblings(planned).contains(candidate)) {
                    return "SAME_CYCLE_CONFLICT";
                }
            }
        }
        // A window on the candidate (or its siblings) means buying it now
        // would wash a loss — its own, or one this user recently took.
        if (washSaleGuardService.isBlockedForTlhBuy(userId, candidate, today)) {
            return "CANDIDATE_WINDOWED";
        }
        // Ledger check: the user sold this candidate at a loss recently
        // (covers manual sells — the fill-activity ledger makes them visible)
        LocalDateTime cutoff = LocalDateTime.now(ET).minusDays(RECENT_LOSS_SALE_DAYS);
        List<LotDisposal> recentDisposals =
                lotDisposalRepository.findByUserIdAndSymbolAndDisposedAtAfter(userId, candidate, cutoff);
        for (LotDisposal disposal : recentDisposals) {
            if (disposal.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                return "RECENT_LOSS_SALE";
            }
        }
        AlpacaService.AssetInfo asset = alpacaService.getAssetInfo(candidate);
        if (!asset.exists || !asset.active || !asset.tradable) {
            return "ASSET_INACTIVE";
        }
        return null;
    }
}

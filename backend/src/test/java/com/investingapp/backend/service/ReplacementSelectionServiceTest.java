package com.investingapp.backend.service;

import com.investingapp.backend.model.LotDisposal;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.repository.LotDisposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplacementSelectionServiceTest {

    private LotDisposalRepository disposalRepository;
    private WashSaleGuardService guard;
    private AlpacaService alpacaService;
    private TlhCatalog catalog;

    private static final Long USER = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    @BeforeEach
    void setUp() {
        disposalRepository = mock(LotDisposalRepository.class);
        guard = mock(WashSaleGuardService.class);
        alpacaService = mock(AlpacaService.class);
        catalog = new TlhCatalog("", "", 45, new BigDecimal("0.5"));
        catalog.initialize();

        when(guard.isBlockedForTlhBuy(anyLong(), anyString(), any())).thenReturn(false);
        when(disposalRepository.findByUserIdAndSymbolAndDisposedAtAfter(anyLong(), anyString(), any()))
                .thenReturn(List.of());
        when(alpacaService.getAssetInfo(anyString()))
                .thenReturn(new AlpacaService.AssetInfo(true, true, true, true));
    }

    private ReplacementSelectionService service(String fallback) {
        return new ReplacementSelectionService(catalog, disposalRepository, guard, alpacaService, fallback);
    }

    @Test
    void curatedPairsWalkInRankOrder() {
        ReplacementSelectionService.ReplacementDecision decision =
                service("skip").select(USER, "VTI", TODAY, Set.of());

        assertEquals(ReplacementSelectionService.DecisionKind.BUY, decision.kind());
        assertEquals("ITOT", decision.symbol());
        assertEquals(TlhHarvestEvent.ReplacementPolicy.PAIR, decision.policy());
    }

    @Test
    void windowedCandidateFallsToNextRank() {
        when(guard.isBlockedForTlhBuy(USER, "ITOT", TODAY)).thenReturn(true);

        ReplacementSelectionService.ReplacementDecision decision =
                service("skip").select(USER, "VTI", TODAY, Set.of());

        assertEquals("SCHB", decision.symbol());
    }

    @Test
    void recentLossSaleOfCandidateRejectsIt() {
        // The user manually sold ITOT at a loss 10 days ago — buying it as a
        // replacement would wash THEIR loss. The fill-activity ledger makes
        // manual sells visible.
        LotDisposal lossSale = new LotDisposal();
        lossSale.setRealizedPnl(new BigDecimal("-25.00"));
        lossSale.setDisposedAt(LocalDateTime.of(2026, 7, 5, 11, 0));
        when(disposalRepository.findByUserIdAndSymbolAndDisposedAtAfter(eq(USER), eq("ITOT"), any()))
                .thenReturn(List.of(lossSale));

        ReplacementSelectionService.ReplacementDecision decision =
                service("skip").select(USER, "VTI", TODAY, Set.of());

        assertEquals("SCHB", decision.symbol());
    }

    @Test
    void inactiveAssetIsRejected() {
        when(alpacaService.getAssetInfo("ITOT"))
                .thenReturn(new AlpacaService.AssetInfo(true, false, false, true));

        ReplacementSelectionService.ReplacementDecision decision =
                service("skip").select(USER, "VTI", TODAY, Set.of());

        assertEquals("SCHB", decision.symbol());
    }

    @Test
    void sameCycleConflictRejectsCandidatesAndTheirSiblings() {
        // ITOT is already being harvested this cycle — VTI may not buy it,
        // nor its identity sibling SPTM.
        ReplacementSelectionService.ReplacementDecision direct =
                service("skip").select(USER, "VTI", TODAY, Set.of("ITOT"));
        assertEquals("SCHB", direct.symbol());

        ReplacementSelectionService.ReplacementDecision viaSibling =
                service("skip").select(USER, "VTI", TODAY, Set.of("SPTM"));
        assertEquals("SCHB", viaSibling.symbol());
    }

    @Test
    void unmappedSymbolFollowsTerminalPolicy() {
        // Single stocks always land here: no auto-picked "similar" stock ever
        ReplacementSelectionService.ReplacementDecision skip =
                service("skip").select(USER, "AAPL", TODAY, Set.of());
        assertEquals(ReplacementSelectionService.DecisionKind.SKIP_HARVEST, skip.kind());

        ReplacementSelectionService.ReplacementDecision holdCash =
                service("hold-cash").select(USER, "AAPL", TODAY, Set.of());
        assertEquals(ReplacementSelectionService.DecisionKind.HOLD_CASH, holdCash.kind());
        assertEquals(TlhHarvestEvent.ReplacementPolicy.HOLD_CASH, holdCash.policy());
        assertNull(holdCash.symbol());
    }

    @Test
    void allCandidatesRejectedFallsToTerminalPolicy() {
        when(guard.isBlockedForTlhBuy(anyLong(), anyString(), any())).thenReturn(true);

        ReplacementSelectionService.ReplacementDecision decision =
                service("skip").select(USER, "VTI", TODAY, Set.of());

        assertEquals(ReplacementSelectionService.DecisionKind.SKIP_HARVEST, decision.kind());
    }

    @Test
    void fractionabilityPropagatesToTheDecision() {
        when(alpacaService.getAssetInfo("ITOT"))
                .thenReturn(new AlpacaService.AssetInfo(true, true, true, false));

        ReplacementSelectionService.ReplacementDecision decision =
                service("skip").select(USER, "VTI", TODAY, Set.of());

        assertEquals("ITOT", decision.symbol());
        assertFalse(decision.fractionable());
    }
}

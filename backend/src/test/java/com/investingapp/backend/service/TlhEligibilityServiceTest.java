package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.TaxLotRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import com.investingapp.backend.service.TaxLotLedgerService.PositionSnapshot;
import com.investingapp.backend.service.TaxLotLedgerService.SalePreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TlhEligibilityServiceTest {

    private TlhHarvestEventRepository eventRepository;
    private TaxLotRepository taxLotRepository;
    private WashSaleGuardService guard;
    private TlhEligibilityService eligibility;
    private User user;

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    @BeforeEach
    void setUp() {
        eventRepository = mock(TlhHarvestEventRepository.class);
        taxLotRepository = mock(TaxLotRepository.class);
        guard = mock(WashSaleGuardService.class);
        TlhCatalog catalog = new TlhCatalog("", "", 45, new BigDecimal("0.5"));
        catalog.initialize();
        eligibility = new TlhEligibilityService(catalog, eventRepository, taxLotRepository, guard,
                new BigDecimal("50.00"), new BigDecimal("5.0"),
                new BigDecimal("100.00"), new BigDecimal("25000.00"), 2);

        user = new User();
        user.setId(7L);
        user.setSelectedTier("pro");

        when(guard.isBlockedForTlhBuy(anyLong(), anyString(), any())).thenReturn(false);
        when(guard.hasPendingBuyActivity(anyLong(), any(), anyString())).thenReturn(false);
        when(eventRepository.existsByUserIdAndSymbolInAndModeAndStatusNotInAndCreatedAtAfter(
                anyLong(), anyCollection(), any(), anyCollection(), any())).thenReturn(false);
        when(taxLotRepository.existsByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
                anyLong(), anyCollection(), any(), any())).thenReturn(false);
    }

    /** A losing position that passes every rule as constructed. */
    private static PositionSnapshot losingPosition(String symbol, String marketValue,
            String costBasis, String unrealizedPl) {
        BigDecimal qty = new BigDecimal("10");
        return new PositionSnapshot(symbol, qty, qty, new BigDecimal(marketValue),
                new BigDecimal(costBasis), new BigDecimal(unrealizedPl),
                new BigDecimal(marketValue).divide(qty), true);
    }

    private static SalePreview underwaterPreview() {
        return new SalePreview(new BigDecimal("10"), new BigDecimal("-100.00"), true, false, true);
    }

    private TlhEligibilityService.EvalResult evaluate(PositionSnapshot position, SalePreview preview) {
        return eligibility.evaluate(user, true, List.of(position),
                Map.of(position.symbol().toUpperCase(), preview), Set.of(), TODAY);
    }

    @Test
    void cleanLosingPositionIsACandidate() {
        TlhEligibilityService.EvalResult result =
                evaluate(losingPosition("VTI", "900.00", "1000.00", "-100.00"), underwaterPreview());

        assertEquals(1, result.candidates().size());
        assertEquals("VTI", result.candidates().get(0).symbol());
        assertEquals(0, result.candidates().get(0).estLoss().compareTo(new BigDecimal("100.00")));
        assertTrue(result.vetoes().isEmpty());
    }

    @Test
    void arbitrarySymbolsFlowThroughIdenticalMachinery() {
        // Portfolio-agnostic pin: a custom-portfolio stock evaluates exactly
        // like the default trio.
        TlhEligibilityService.EvalResult result =
                evaluate(losingPosition("AAPL", "900.00", "1000.00", "-100.00"), underwaterPreview());

        assertEquals(1, result.candidates().size());
        assertEquals("AAPL", result.candidates().get(0).symbol());
    }

    @Test
    void oneAboveWaterLotVetoesTheWholePosition() {
        SalePreview mixedLots = new SalePreview(new BigDecimal("10"),
                new BigDecimal("-100.00"), false, false, true);

        TlhEligibilityService.EvalResult result =
                evaluate(losingPosition("VTI", "900.00", "1000.00", "-100.00"), mixedLots);

        assertTrue(result.candidates().isEmpty());
        assertEquals(TlhEligibilityService.VETO_NOT_ALL_LOTS_UNDERWATER, result.vetoes().get(0).reason());
    }

    @Test
    void lossThresholdsApplyToBothDollarsAndPercent() {
        // $40 loss is under the $50 floor
        TlhEligibilityService.EvalResult smallDollar =
                evaluate(losingPosition("VTI", "960.00", "1000.00", "-40.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_BELOW_LOSS_THRESHOLDS, smallDollar.vetoes().get(0).reason());

        // $60 loss on a $10,000 basis = 0.6%, under the 5% floor
        TlhEligibilityService.EvalResult smallPercent =
                evaluate(losingPosition("VTI", "9940.00", "10000.00", "-60.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_BELOW_LOSS_THRESHOLDS, smallPercent.vetoes().get(0).reason());

        // A GAIN is never harvested
        TlhEligibilityService.EvalResult gain =
                evaluate(losingPosition("VTI", "1100.00", "1000.00", "100.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_BELOW_LOSS_THRESHOLDS, gain.vetoes().get(0).reason());
    }

    @Test
    void positionValueBoundsAreEnforced() {
        TlhEligibilityService.EvalResult tooSmall =
                evaluate(losingPosition("VTI", "80.00", "160.00", "-80.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_BELOW_MIN_VALUE, tooSmall.vetoes().get(0).reason());

        TlhEligibilityService.EvalResult tooBig =
                evaluate(losingPosition("VTI", "30000.00", "40000.00", "-10000.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_ABOVE_MAX_NOTIONAL, tooBig.vetoes().get(0).reason());
    }

    @Test
    void badBasisVetoesInsteadOfThrowing() {
        PositionSnapshot zeroBasis = new PositionSnapshot("VTI", new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal("900.00"), BigDecimal.ZERO,
                new BigDecimal("-100.00"), new BigDecimal("90"), true);

        TlhEligibilityService.EvalResult result = evaluate(zeroBasis, underwaterPreview());

        assertEquals(TlhEligibilityService.VETO_BAD_BASIS, result.vetoes().get(0).reason());
    }

    @Test
    void cooldownAndWindowAndPendingBuysVeto() {
        when(eventRepository.existsByUserIdAndSymbolInAndModeAndStatusNotInAndCreatedAtAfter(
                anyLong(), anyCollection(), any(), anyCollection(), any())).thenReturn(true);
        TlhEligibilityService.EvalResult cooldown =
                evaluate(losingPosition("VTI", "900.00", "1000.00", "-100.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_COOLDOWN, cooldown.vetoes().get(0).reason());

        reset(eventRepository);
        when(eventRepository.existsByUserIdAndSymbolInAndModeAndStatusNotInAndCreatedAtAfter(
                anyLong(), anyCollection(), any(), anyCollection(), any())).thenReturn(false);
        when(guard.isBlockedForTlhBuy(eq(7L), eq("VTI"), any())).thenReturn(true);
        TlhEligibilityService.EvalResult windowed =
                evaluate(losingPosition("VTI", "900.00", "1000.00", "-100.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_WINDOW_ACTIVE, windowed.vetoes().get(0).reason());

        when(guard.isBlockedForTlhBuy(anyLong(), anyString(), any())).thenReturn(false);
        when(guard.hasPendingBuyActivity(eq(7L), any(), eq("VTI"))).thenReturn(true);
        TlhEligibilityService.EvalResult pending =
                evaluate(losingPosition("VTI", "900.00", "1000.00", "-100.00"), underwaterPreview());
        assertEquals(TlhEligibilityService.VETO_PENDING_BUYS, pending.vetoes().get(0).reason());
    }

    @Test
    void holdingAnIdentitySiblingVetoesTheHarvest() {
        // Holding VOO makes an SPY harvest a §1091 trap — sibling shares are
        // replacement shares.
        PositionSnapshot spy = losingPosition("SPY", "900.00", "1000.00", "-100.00");
        PositionSnapshot voo = new PositionSnapshot("VOO", new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("500.00"), new BigDecimal("450.00"), new BigDecimal("50.00"),
                new BigDecimal("500"), true);

        TlhEligibilityService.EvalResult result = eligibility.evaluate(user, true,
                List.of(spy, voo),
                Map.of("SPY", underwaterPreview(), "VOO", underwaterPreview()),
                Set.of(), TODAY);

        assertTrue(result.vetoes().stream().anyMatch(v ->
                v.symbol().equals("SPY") && v.reason().equals(TlhEligibilityService.VETO_SIBLING_HELD)));
    }

    @Test
    void siblingBoughtWithinThirtyOneDaysVetoes() {
        when(taxLotRepository.existsByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
                anyLong(), anyCollection(), any(), any())).thenReturn(true);

        TlhEligibilityService.EvalResult result =
                evaluate(losingPosition("SPY", "900.00", "1000.00", "-100.00"), underwaterPreview());

        assertEquals(TlhEligibilityService.VETO_SIBLING_RECENT_BUY, result.vetoes().get(0).reason());
    }

    @Test
    void sharesTiedUpInOpenOrdersVeto() {
        PositionSnapshot held = new PositionSnapshot("VTI", new BigDecimal("10"),
                new BigDecimal("8"), new BigDecimal("900.00"), new BigDecimal("1000.00"),
                new BigDecimal("-100.00"), new BigDecimal("90"), true);

        TlhEligibilityService.EvalResult result = evaluate(held, underwaterPreview());

        assertEquals(TlhEligibilityService.VETO_SHARES_HELD, result.vetoes().get(0).reason());
    }

    @Test
    void unreconciledAndNotReadyLedgersVeto() {
        PositionSnapshot position = losingPosition("VTI", "900.00", "1000.00", "-100.00");

        TlhEligibilityService.EvalResult unreconciled = eligibility.evaluate(user, true,
                List.of(position), Map.of("VTI", underwaterPreview()), Set.of("VTI"), TODAY);
        assertEquals(TlhEligibilityService.VETO_UNRECONCILED, unreconciled.vetoes().get(0).reason());

        TlhEligibilityService.EvalResult notReady = eligibility.evaluate(user, false,
                List.of(position), Map.of("VTI", underwaterPreview()), Set.of(), TODAY);
        assertEquals(TlhEligibilityService.VETO_LEDGER_NOT_READY, notReady.vetoes().get(0).reason());
    }

    @Test
    void survivorsAreRankedByLossAndCappedPerUser() {
        PositionSnapshot small = losingPosition("VTI", "900.00", "1000.00", "-100.00");
        PositionSnapshot big = losingPosition("VXUS", "700.00", "1000.00", "-300.00");
        PositionSnapshot mid = losingPosition("VBR", "800.00", "1000.00", "-200.00");

        TlhEligibilityService.EvalResult result = eligibility.evaluate(user, true,
                List.of(small, big, mid),
                Map.of("VTI", underwaterPreview(), "VXUS", underwaterPreview(), "VBR", underwaterPreview()),
                Set.of(), TODAY);

        // max-per-user = 2: the two largest losses win, in order
        assertEquals(2, result.candidates().size());
        assertEquals("VXUS", result.candidates().get(0).symbol());
        assertEquals("VBR", result.candidates().get(1).symbol());
    }
}

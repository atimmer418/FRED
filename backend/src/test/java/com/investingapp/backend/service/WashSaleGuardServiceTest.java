package com.investingapp.backend.service;

import com.investingapp.backend.model.DripExecution;
import com.investingapp.backend.model.InvestmentTrade;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.DripExecutionRepository;
import com.investingapp.backend.repository.InvestmentTradeRepository;
import com.investingapp.backend.repository.WashSaleWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WashSaleGuardServiceTest {

    private WashSaleWindowRepository windowRepository;
    private InvestmentTradeRepository tradeRepository;
    private DripExecutionRepository dripRepository;
    private WashSaleGuardService guard;

    private static final Long USER = 7L;
    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = START.plusDays(30);

    @BeforeEach
    void setUp() {
        windowRepository = mock(WashSaleWindowRepository.class);
        tradeRepository = mock(InvestmentTradeRepository.class);
        dripRepository = mock(DripExecutionRepository.class);
        when(windowRepository.findByUserIdAndSymbolInAndActiveTrue(anyLong(), anyCollection()))
                .thenReturn(List.of());
        when(tradeRepository.findByUserIdAndSymbolAndStatusInAndCreatedAtAfter(
                anyLong(), anyString(), anyCollection(), any())).thenReturn(List.of());
        when(dripRepository.existsByUserAndSymbolAndStatusIn(any(), anyString(), anyCollection()))
                .thenReturn(false);
        TlhCatalog catalog = new TlhCatalog("", "", 45, new BigDecimal("0.5"));
        catalog.initialize();
        guard = new WashSaleGuardService(windowRepository, tradeRepository, dripRepository,
                catalog, "redirect", "skip");
    }

    private WashSaleWindow window(String symbol, String redirect) {
        WashSaleWindow window = new WashSaleWindow();
        window.setId(1L);
        window.setUserId(USER);
        window.setSymbol(symbol);
        window.setStartDate(START);
        window.setEndDate(END);
        window.setReason(WashSaleWindow.WindowReason.HARVEST_SALE);
        window.setRedirectSymbol(redirect);
        window.setActive(true);
        return window;
    }

    private void stubWindowFor(String coveredSymbol, WashSaleWindow window) {
        when(windowRepository.findByUserIdAndSymbolInAndActiveTrue(eq(USER),
                argThat((Collection<String> symbols) -> symbols != null && symbols.contains(coveredSymbol))))
                .thenReturn(List.of(window));
    }

    @Test
    void dateBoundaryContractBlockedThroughEndDateLegalDayAfter() {
        stubWindowFor("VTI", window("VTI", "ITOT"));

        assertNotEquals(WashSaleGuardService.GuardAction.ALLOW,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY, START).action());
        assertNotEquals(WashSaleGuardService.GuardAction.ALLOW,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY, END).action());
        assertEquals(WashSaleGuardService.GuardAction.ALLOW,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY,
                        END.plusDays(1)).action());
    }

    @Test
    void staleActiveFlaggedWindowPastEndDateDoesNotBlock() {
        // The `active` flag is only an index hint — date comparison wins
        WashSaleWindow stale = window("VTI", null);
        stubWindowFor("VTI", stale);

        assertEquals(WashSaleGuardService.GuardAction.ALLOW,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY,
                        END.plusDays(10)).action());
    }

    @Test
    void identityGroupExpansionBlocksSiblings() {
        // Window on VOO blocks a SPY buy — substantially identical
        stubWindowFor("VOO", window("VOO", null));

        WashSaleGuardService.GuardDecision decision = guard.checkBuy(
                USER, "SPY", WashSaleGuardService.TradeSource.SCHEDULED_BUY, START.plusDays(5));

        assertEquals(WashSaleGuardService.GuardAction.SKIP, decision.action());
    }

    @Test
    void scheduledBuyRedirectsToWindowTarget() {
        stubWindowFor("VTI", window("VTI", "ITOT"));

        WashSaleGuardService.GuardDecision decision = guard.checkBuy(
                USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY, START.plusDays(5));

        assertEquals(WashSaleGuardService.GuardAction.REDIRECT, decision.action());
        assertEquals("ITOT", decision.redirectSymbol());
    }

    @Test
    void redirectTargetItselfWindowedDegradesToSkip() {
        stubWindowFor("VTI", window("VTI", "ITOT"));
        stubWindowFor("ITOT", window("ITOT", null));

        WashSaleGuardService.GuardDecision decision = guard.checkBuy(
                USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY, START.plusDays(5));

        assertEquals(WashSaleGuardService.GuardAction.SKIP, decision.action());
    }

    @Test
    void nullRedirectTargetDegradesToSkip() {
        // Hold-cash harvests open windows with no redirect target
        stubWindowFor("AAPL", window("AAPL", null));

        WashSaleGuardService.GuardDecision decision = guard.checkBuy(
                USER, "AAPL", WashSaleGuardService.TradeSource.SCHEDULED_BUY, START.plusDays(5));

        assertEquals(WashSaleGuardService.GuardAction.SKIP, decision.action());
    }

    @Test
    void dripDefaultsToSkipAndLumpSumIsAlwaysAllowed() {
        stubWindowFor("VTI", window("VTI", "ITOT"));

        assertEquals(WashSaleGuardService.GuardAction.SKIP,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.DRIP, START.plusDays(5)).action());
        // Funded user intent is never stranded — nightly disallowance reconciles
        assertEquals(WashSaleGuardService.GuardAction.ALLOW,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.USER_LUMP_SUM,
                        START.plusDays(5)).action());
    }

    @Test
    void tlhOwnBuysAndFutureRebalancerAreHardBlocked() {
        stubWindowFor("VTI", window("VTI", "ITOT"));

        assertEquals(WashSaleGuardService.GuardAction.BLOCK,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.TLH_REPLACEMENT,
                        START.plusDays(5)).action());
        assertEquals(WashSaleGuardService.GuardAction.BLOCK,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.TLH_REENTRY,
                        START.plusDays(5)).action());
        assertEquals(WashSaleGuardService.GuardAction.BLOCK,
                guard.checkBuy(USER, "VTI", WashSaleGuardService.TradeSource.REBALANCE,
                        START.plusDays(5)).action());
    }

    @Test
    void guardReadsOnlyWindowRowsNoTierInput() {
        // Structural pin: enforcement survives tier expiry — checkBuy takes no
        // user/tier/mode input at all, only window rows.
        stubWindowFor("VTI", window("VTI", null));

        WashSaleGuardService.GuardDecision decision = guard.checkBuy(
                USER, "VTI", WashSaleGuardService.TradeSource.SCHEDULED_BUY, START.plusDays(5));

        assertEquals(WashSaleGuardService.GuardAction.SKIP, decision.action());
        verifyNoInteractions(dripRepository); // no enrollment/tier lookups anywhere
    }

    @Test
    void pendingBuyActivityCoversTradesAndDripAcrossSiblings() {
        User user = new User();
        user.setId(USER);

        // Open trade on a SIBLING (VOO) blocks SPY
        InvestmentTrade openTrade = new InvestmentTrade();
        when(tradeRepository.findByUserIdAndSymbolAndStatusInAndCreatedAtAfter(
                eq(USER), eq("VOO"), anyCollection(), any())).thenReturn(List.of(openTrade));
        assertTrue(guard.hasPendingBuyActivity(USER, user, "SPY"));

        // DRIP detected on the symbol itself
        reset(tradeRepository);
        when(tradeRepository.findByUserIdAndSymbolAndStatusInAndCreatedAtAfter(
                anyLong(), anyString(), anyCollection(), any())).thenReturn(List.of());
        when(dripRepository.existsByUserAndSymbolAndStatusIn(eq(user), eq("VTI"), anyCollection()))
                .thenReturn(true);
        assertTrue(guard.hasPendingBuyActivity(USER, user, "VTI"));

        // Nothing pending
        when(dripRepository.existsByUserAndSymbolAndStatusIn(any(), anyString(), anyCollection()))
                .thenReturn(false);
        assertFalse(guard.hasPendingBuyActivity(USER, user, "VXUS"));
    }

    @Test
    void cancelWindowRewindsEndDateSoDateContractAgrees() {
        WashSaleWindow window = window("VTI", "ITOT");
        TlhHarvestEvent event = new TlhHarvestEvent();
        event.setId(42L);
        when(windowRepository.findByHarvestEventId(42L)).thenReturn(Optional.of(window));

        guard.cancelWindow(event);

        assertFalse(window.getActive());
        assertTrue(window.getEndDate().isBefore(window.getStartDate()));
    }

    @Test
    void confirmWindowAnchorsToFillDate() {
        WashSaleWindow window = window("VTI", "ITOT");
        TlhHarvestEvent event = new TlhHarvestEvent();
        event.setId(43L);
        when(windowRepository.findByHarvestEventId(43L)).thenReturn(Optional.of(window));
        LocalDate fillDate = START.plusDays(1);

        guard.confirmWindow(event, fillDate);

        assertEquals(fillDate, window.getStartDate());
        assertEquals(fillDate.plusDays(30), window.getEndDate());
    }

    @Test
    void expireWindowsDeactivatesOnlyPastEndDateRows() {
        WashSaleWindow expired = window("VTI", null);
        when(windowRepository.findByActiveTrueAndEndDateBefore(END.plusDays(5)))
                .thenReturn(List.of(expired));

        int count = guard.expireWindows(END.plusDays(5));

        assertEquals(1, count);
        assertFalse(expired.getActive());
    }
}

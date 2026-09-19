package com.investingapp.backend.service;

import com.investingapp.backend.model.LotDisposal;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.LotDisposalRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import com.investingapp.backend.repository.TlhUserStateRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.WashSaleWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TlhExecutionServiceTest {

    private TlhHarvestEventRepository eventRepository;
    private WashSaleWindowRepository windowRepository;
    private LotDisposalRepository disposalRepository;
    private WashSaleGuardService guard;
    private ReplacementSelectionService replacementSelectionService;
    private TaxLotLedgerService ledger;
    private TlhUserStateRepository stateRepository;
    private UserRepository userRepository;
    private AlpacaService alpacaService;
    private EncryptionService encryptionService;
    private PortfolioDashboardService dashboardService;

    private static final Long USER = 7L;
    private static final String ACCOUNT = "acct-1";

    @BeforeEach
    void setUp() {
        eventRepository = mock(TlhHarvestEventRepository.class);
        windowRepository = mock(WashSaleWindowRepository.class);
        disposalRepository = mock(LotDisposalRepository.class);
        guard = mock(WashSaleGuardService.class);
        replacementSelectionService = mock(ReplacementSelectionService.class);
        ledger = mock(TaxLotLedgerService.class);
        stateRepository = mock(TlhUserStateRepository.class);
        userRepository = mock(UserRepository.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);
        dashboardService = mock(PortfolioDashboardService.class);

        when(encryptionService.decrypt("enc-acct")).thenReturn(ACCOUNT);
        when(eventRepository.save(any(TlhHarvestEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of());
        when(eventRepository.findByReentryStatus(anyString())).thenReturn(List.of());
        when(eventRepository.findByReentryStatusAndReentryDueOnLessThanEqual(anyString(), any()))
                .thenReturn(List.of());
        when(stateRepository.findByLedgerStatus(any())).thenReturn(List.of());
        when(disposalRepository.findByHarvestEventId(anyLong())).thenReturn(List.of());
        when(guard.hasPendingBuyActivity(anyLong(), any(), anyString())).thenReturn(false);
        when(guard.isBlockedForTlhBuy(anyLong(), anyString(), any())).thenReturn(false);
        when(alpacaService.getOpenOrders(anyString(), anyString())).thenReturn(List.of());
        when(alpacaService.getMarketClock()).thenReturn(true);
    }

    private TlhExecutionService service(String mode) {
        TlhCatalog catalog = new TlhCatalog("", "", 45, new BigDecimal("0.5"));
        catalog.initialize();
        TlhExecutionService service = new TlhExecutionService(eventRepository, windowRepository,
                disposalRepository, guard, replacementSelectionService, ledger, catalog,
                stateRepository, userRepository, alpacaService, encryptionService, dashboardService,
                mock(PlatformTransactionManager.class), mode, true, 3, true);
        service.syncFillCheckDelayMs = 0L;
        return service;
    }

    private User proUser() {
        User user = new User();
        user.setId(USER);
        user.setSelectedTier("pro");
        user.setTlhEnabled(true);
        return user;
    }

    private TlhHarvestEvent event(TlhHarvestEvent.EventStatus status) {
        TlhHarvestEvent event = new TlhHarvestEvent();
        event.setId(1L);
        event.setUserId(USER);
        event.setAlpacaAccountId("enc-acct");
        event.setSymbol("VTI");
        event.setReplacementSymbol("ITOT");
        event.setReplacementPolicy(TlhHarvestEvent.ReplacementPolicy.PAIR);
        event.setMode(TlhHarvestEvent.HarvestMode.LIVE);
        event.setStatus(status);
        event.setCycleDate(LocalDate.now(ZoneId.of("America/New_York")));
        event.setQuantityTargeted(new BigDecimal("10"));
        event.setSellClientOrderId("tlh-s-test");
        return event;
    }

    private static AlpacaService.AlpacaOrderResponse order(String id, String status,
            String filledQty, String filledAvgPrice, String filledAt) {
        return new AlpacaService.AlpacaOrderResponse(id, "VTI", status, new BigDecimal("10"), null,
                filledQty == null ? BigDecimal.ZERO : new BigDecimal(filledQty),
                filledAvgPrice == null ? null : new BigDecimal(filledAvgPrice),
                "2026-07-15T13:10:00Z", filledAt, null);
    }

    @Test
    void tierLostBetweenClaimAndSubmitVetoesAndCancelsWindow() {
        User downgraded = proUser();
        downgraded.setSelectedTier(null); // subscription expired mid-cycle
        when(userRepository.findById(USER)).thenReturn(Optional.of(downgraded));
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_PENDING);

        service("live").submitSell(event, downgraded);

        assertEquals(TlhHarvestEvent.EventStatus.VETOED, event.getStatus());
        assertEquals("TIER_LOST", event.getVetoReason());
        verify(guard).cancelWindow(event);
        verify(alpacaService, never()).placeSellOrderWithQuantity(anyString(), anyString(), any(), anyString());
    }

    @Test
    void openBuyOrderAtBrokerIsARaceVeto() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(proUser()));
        when(alpacaService.getOpenOrders(ACCOUNT, "VTI"))
                .thenReturn(List.of(Map.of("id", "o-1", "side", "buy", "symbol", "VTI")));
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_PENDING);

        service("live").submitSell(event, proUser());

        assertEquals(TlhHarvestEvent.EventStatus.VETOED, event.getStatus());
        assertEquals("RACE_DETECTED", event.getVetoReason());
        verify(guard).cancelWindow(event);
        verify(alpacaService, never()).placeSellOrderWithQuantity(anyString(), anyString(), any(), anyString());
    }

    @Test
    void submitFailureWithOrderFoundByClientIdIsAdopted() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(proUser()));
        when(alpacaService.placeSellOrderWithQuantity(eq(ACCOUNT), eq("VTI"), any(), eq("tlh-s-test")))
                .thenReturn(new AlpacaService.AlpacaOrderResponse(null, "VTI", "ERROR", null, null,
                        BigDecimal.ZERO, null, null, "timeout"));
        when(alpacaService.getOrderByClientOrderId(ACCOUNT, "tlh-s-test"))
                .thenReturn(AlpacaService.OrderLookupResult.found(order("alp-9", "accepted", null, null, null)));
        when(alpacaService.checkOrderStatus(eq(ACCOUNT), eq("alp-9")))
                .thenReturn(order("alp-9", "accepted", null, null, null));
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_PENDING);

        service("live").submitSell(event, proUser());

        assertEquals(TlhHarvestEvent.EventStatus.SELL_SUBMITTED, event.getStatus());
        assertEquals("alp-9", event.getSellOrderId());
    }

    @Test
    void submitFailureConfirmedAbsentFailsCleanlyAndCancelsWindow() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(proUser()));
        when(alpacaService.placeSellOrderWithQuantity(anyString(), anyString(), any(), anyString()))
                .thenReturn(new AlpacaService.AlpacaOrderResponse(null, "VTI", "FAILED", null, null,
                        BigDecimal.ZERO, null, null, "rejected"));
        when(alpacaService.getOrderByClientOrderId(ACCOUNT, "tlh-s-test"))
                .thenReturn(AlpacaService.OrderLookupResult.confirmedAbsent());
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_PENDING);

        service("live").submitSell(event, proUser());

        assertEquals(TlhHarvestEvent.EventStatus.FAILED, event.getStatus());
        verify(guard).cancelWindow(event);
    }

    @Test
    void submitFailureWithLookupFailureLeavesSellPendingForThePoller() {
        // Unknown is never terminal — the poller owns recovery
        when(userRepository.findById(USER)).thenReturn(Optional.of(proUser()));
        when(alpacaService.placeSellOrderWithQuantity(anyString(), anyString(), any(), anyString()))
                .thenReturn(new AlpacaService.AlpacaOrderResponse(null, "VTI", "ERROR", null, null,
                        BigDecimal.ZERO, null, null, "timeout"));
        when(alpacaService.getOrderByClientOrderId(ACCOUNT, "tlh-s-test"))
                .thenReturn(AlpacaService.OrderLookupResult.lookupFailed("network"));
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_PENDING);

        service("live").submitSell(event, proUser());

        assertEquals(TlhHarvestEvent.EventStatus.SELL_PENDING, event.getStatus());
        verify(guard, never()).cancelWindow(any());
    }

    @Test
    void orphanedSellPendingIsAdoptedOrFailedByThePoller() {
        TlhHarvestEvent orphan = event(TlhHarvestEvent.EventStatus.SELL_PENDING);
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(orphan));
        when(alpacaService.getOrderByClientOrderId(ACCOUNT, "tlh-s-test"))
                .thenReturn(AlpacaService.OrderLookupResult.confirmedAbsent());

        service("live").pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.FAILED, orphan.getStatus());
        verify(guard).cancelWindow(orphan);
    }

    @Test
    void netLossFillConfirmsWindowAndAnchorsOnAlpacaFilledAt() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
        event.setSellOrderId("alp-1");
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(alpacaService.checkOrderStatus(ACCOUNT, "alp-1"))
                .thenReturn(order("alp-1", "filled", "10", "90.00", "2026-07-15T14:30:00Z"));
        LotDisposal loss = new LotDisposal();
        loss.setRealizedPnl(new BigDecimal("-100.00"));
        when(disposalRepository.findByHarvestEventId(1L)).thenReturn(List.of(loss));
        when(alpacaService.getMarketClock()).thenReturn(false); // replacement deferred

        service("live").pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.SELL_FILLED, event.getStatus());
        assertEquals(0, event.getRealizedLoss().compareTo(new BigDecimal("100.00")));
        assertEquals(0, event.getAllowedLoss().compareTo(new BigDecimal("100.00")));
        assertEquals(2026, event.getTaxYear());
        assertEquals(LocalDate.of(2026, 7, 15), event.getSellFilledAt().toLocalDate());
        verify(guard).confirmWindow(event, LocalDate.of(2026, 7, 15));
        verify(ledger).applySyntheticOrderFill(eq(USER), eq("alp-1"), eq("sell"), eq("VTI"),
                any(), any(), any(), any());
    }

    @Test
    void gainAtFillCancelsWindowAndSchedulesPromptReentry() {
        // Price popped between quote and fill — no loss to protect, never
        // counted as savings.
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
        event.setSellOrderId("alp-2");
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(alpacaService.checkOrderStatus(ACCOUNT, "alp-2"))
                .thenReturn(order("alp-2", "filled", "10", "101.00", "2026-07-15T14:30:00Z"));
        LotDisposal gain = new LotDisposal();
        gain.setRealizedPnl(new BigDecimal("10.00"));
        when(disposalRepository.findByHarvestEventId(1L)).thenReturn(List.of(gain));

        service("live").pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.COMPLETED_GAIN, event.getStatus());
        assertEquals(0, event.getNetGain().compareTo(new BigDecimal("10.00")));
        assertEquals(0, event.getAllowedLoss().compareTo(BigDecimal.ZERO));
        assertEquals("PENDING", event.getReentryStatus());
        verify(guard).cancelWindow(event);
    }

    @Test
    void zeroFillCancellationCancelsTheWindow() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
        event.setSellOrderId("alp-3");
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(alpacaService.checkOrderStatus(ACCOUNT, "alp-3"))
                .thenReturn(order("alp-3", "canceled", "0", null, null));

        service("live").pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.CANCELED_UNFILLED, event.getStatus());
        verify(guard).cancelWindow(event);
    }

    @Test
    void replacementPersistsPerAttemptClientIdBeforeSubmitting() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_FILLED);
        event.setActualProceeds(new BigDecimal("500.00"));
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(alpacaService.getBuyingPower(ACCOUNT)).thenReturn(new BigDecimal("1000.00"));
        when(alpacaService.getAssetInfo("ITOT"))
                .thenReturn(new AlpacaService.AssetInfo(true, true, true, true));
        when(alpacaService.placeBuyOrderWithClientId(eq(ACCOUNT), eq("ITOT"), any(), anyString()))
                .thenReturn(order("alp-4", "accepted", null, null, null));

        service("live").pollOpenEvents();

        InOrder inOrder = inOrder(eventRepository, alpacaService);
        inOrder.verify(eventRepository).save(event); // per-attempt client id persisted first
        inOrder.verify(alpacaService).placeBuyOrderWithClientId(ACCOUNT, "ITOT",
                new BigDecimal("500.00"), "tlh-b-1-a1");
        assertEquals(TlhHarvestEvent.EventStatus.REPLACEMENT_SUBMITTED, event.getStatus());
        assertEquals(1, event.getReplacementAttempts());
        assertEquals("alp-4", event.getReplacementOrderId());
    }

    @Test
    void replacementCapsNotionalAtBuyingPower() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_FILLED);
        event.setActualProceeds(new BigDecimal("500.00"));
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(alpacaService.getBuyingPower(ACCOUNT)).thenReturn(new BigDecimal("321.55"));
        when(alpacaService.getAssetInfo("ITOT"))
                .thenReturn(new AlpacaService.AssetInfo(true, true, true, true));
        when(alpacaService.placeBuyOrderWithClientId(anyString(), anyString(), any(), anyString()))
                .thenReturn(order("alp-5", "accepted", null, null, null));

        service("live").pollOpenEvents();

        verify(alpacaService).placeBuyOrderWithClientId(ACCOUNT, "ITOT",
                new BigDecimal("321.55"), "tlh-b-1-a1");
    }

    @Test
    void replacementAttemptsExhaustedDegradesAndSchedulesReentry() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_FILLED);
        event.setActualProceeds(new BigDecimal("500.00"));
        event.setReplacementAttempts(3);
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        WashSaleWindow window = new WashSaleWindow();
        window.setEndDate(LocalDate.of(2026, 8, 14));
        when(windowRepository.findByHarvestEventId(1L)).thenReturn(Optional.of(window));

        service("live").pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.COMPLETED_NO_REPLACEMENT, event.getStatus());
        assertEquals("PENDING", event.getReentryStatus());
        assertEquals(LocalDate.of(2026, 8, 15), event.getReentryDueOn());
        verify(alpacaService, never()).placeBuyOrderWithClientId(anyString(), anyString(), any(), anyString());
    }

    @Test
    void replacementBlockedAtBuyTimeDegradesInsteadOfBuying() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_FILLED);
        event.setActualProceeds(new BigDecimal("500.00"));
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(guard.isBlockedForTlhBuy(USER, "ITOT", LocalDate.now(ZoneId.of("America/New_York"))))
                .thenReturn(true);
        when(windowRepository.findByHarvestEventId(1L)).thenReturn(Optional.empty());

        service("live").pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.COMPLETED_NO_REPLACEMENT, event.getStatus());
        verify(alpacaService, never()).placeBuyOrderWithClientId(anyString(), anyString(), any(), anyString());
    }

    @Test
    void reentrySweepIsTierBlindAndSubmitsWithClientId() {
        // Selected by reentryStatus + due date — never by the tier-gated user
        // query. A downgraded user's cash still re-enters.
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.COMPLETED_NO_REPLACEMENT);
        event.setActualProceeds(new BigDecimal("400.00"));
        event.setReentryStatus("PENDING");
        event.setReentryDueOn(LocalDate.now(ZoneId.of("America/New_York")).minusDays(1));
        when(eventRepository.findByReentryStatusAndReentryDueOnLessThanEqual(eq("PENDING"), any()))
                .thenReturn(List.of(event));
        when(alpacaService.getBuyingPower(ACCOUNT)).thenReturn(new BigDecimal("1000.00"));
        when(alpacaService.placeBuyOrderWithClientId(eq(ACCOUNT), eq("VTI"), any(), eq("tlh-r-1-a1")))
                .thenReturn(order("alp-6", "accepted", null, null, null));

        service("live").pollOpenEvents();

        assertEquals("SUBMITTED", event.getReentryStatus());
        assertEquals("alp-6", event.getReentryOrderId());
        verify(userRepository, never()).findById(anyLong()); // no tier lookup anywhere
    }

    @Test
    void reentryDefersWhenModeIsOff() {
        service("off").pollOpenEvents();

        verify(eventRepository, never())
                .findByReentryStatusAndReentryDueOnLessThanEqual(anyString(), any());
    }

    @Test
    void repeatedPollFailuresCancelTheAccountChain() {
        TlhHarvestEvent event = event(TlhHarvestEvent.EventStatus.SELL_SUBMITTED);
        event.setSellOrderId("alp-7");
        when(eventRepository.findByStatusIn(anyCollection())).thenReturn(List.of(event));
        when(alpacaService.checkOrderStatus(ACCOUNT, "alp-7"))
                .thenReturn(new AlpacaService.AlpacaOrderResponse("alp-7", null, "ERROR", null, null,
                        BigDecimal.ZERO, null, null, "account closed"));

        TlhExecutionService service = service("live");
        service.pollOpenEvents();
        service.pollOpenEvents();
        service.pollOpenEvents();

        assertEquals(TlhHarvestEvent.EventStatus.CANCELED_ACCOUNT, event.getStatus());
        verify(guard).cancelWindow(event);
    }
}

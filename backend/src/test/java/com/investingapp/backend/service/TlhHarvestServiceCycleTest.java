package com.investingapp.backend.service;

import com.investingapp.backend.model.TaxLot;
import com.investingapp.backend.model.TlhCycleRun;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.TlhUserState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.*;
import com.investingapp.backend.service.TaxLotLedgerService.SalePreview;
import com.investingapp.backend.service.TlhEligibilityService.EvalResult;
import com.investingapp.backend.service.TlhEligibilityService.HarvestCandidate;
import com.investingapp.backend.service.TlhEligibilityService.Veto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TlhHarvestServiceCycleTest {

    private UserRepository userRepository;
    private TlhHarvestEventRepository eventRepository;
    private TlhUserStateRepository stateRepository;
    private TlhCycleRunRepository cycleRunRepository;
    private WashSaleWindowRepository windowRepository;
    private TaxLotRepository taxLotRepository;
    private WashSaleGuardService guard;
    private TlhEligibilityService eligibilityService;
    private ReplacementSelectionService replacementSelectionService;
    private TaxLotLedgerService ledger;
    private TlhExecutionService executionService;
    private AlpacaService alpacaService;
    private EncryptionService encryptionService;

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final String POSITIONS_JSON = "[{\"symbol\":\"VTI\",\"qty\":\"10\","
            + "\"qty_available\":\"10\",\"market_value\":\"900.00\",\"cost_basis\":\"1000.00\","
            + "\"unrealized_pl\":\"-100.00\",\"current_price\":\"90.00\"}]";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        eventRepository = mock(TlhHarvestEventRepository.class);
        stateRepository = mock(TlhUserStateRepository.class);
        cycleRunRepository = mock(TlhCycleRunRepository.class);
        windowRepository = mock(WashSaleWindowRepository.class);
        taxLotRepository = mock(TaxLotRepository.class);
        guard = mock(WashSaleGuardService.class);
        eligibilityService = mock(TlhEligibilityService.class);
        replacementSelectionService = mock(ReplacementSelectionService.class);
        ledger = mock(TaxLotLedgerService.class);
        executionService = mock(TlhExecutionService.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);

        when(cycleRunRepository.save(any(TlhCycleRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.save(any(TlhHarvestEvent.class))).thenAnswer(inv -> {
            TlhHarvestEvent event = inv.getArgument(0);
            if (event.getId() == null) {
                event.setId(99L);
            }
            return event;
        });
        when(eventRepository.existsByUserIdAndStatusIn(anyLong(), anyCollection())).thenReturn(false);
        when(eventRepository.existsByUserIdAndSymbolAndVetoReasonAndCreatedAtAfter(
                anyLong(), anyString(), anyString(), any())).thenReturn(false);
        when(encryptionService.decrypt("enc-acct")).thenReturn("acct-1");
        when(alpacaService.getMarketClock()).thenReturn(true);
        when(alpacaService.getCurrentPositions("acct-1")).thenReturn(POSITIONS_JSON);
        when(alpacaService.getFillActivities(anyString(), any(), any(), anyInt()))
                .thenReturn(new AlpacaService.FillActivityPage(List.of(), null, true));
        when(alpacaService.getAssetInfo(anyString()))
                .thenReturn(new AlpacaService.AssetInfo(true, true, true, true));
        when(ledger.reconcile(anyLong(), anyList()))
                .thenReturn(new TaxLotLedgerService.ReconcileResult(List.of()));
        when(ledger.previewFifoSale(anyLong(), anyString(), any(), any()))
                .thenReturn(new SalePreview(new BigDecimal("10"), new BigDecimal("-100.00"), true, false, true));
        when(taxLotRepository.findUnreconciledSymbols(anyLong())).thenReturn(List.of());
        when(stateRepository.save(any(TlhUserState.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TlhHarvestService service(String mode, int failureHalt) {
        TlhCatalog catalog = new TlhCatalog("", "", 45, new BigDecimal("0.5"));
        catalog.initialize();
        return new TlhHarvestService(userRepository, eventRepository, stateRepository,
                cycleRunRepository, windowRepository, taxLotRepository, guard, eligibilityService,
                replacementSelectionService, ledger, executionService, alpacaService,
                encryptionService, catalog, mock(PlatformTransactionManager.class),
                mode, 25, 200, 0L, failureHalt);
    }

    private User proUser(long id) {
        User user = new User();
        user.setId(id);
        user.setSelectedTier("pro");
        user.setTlhEnabled(true);
        user.setAlpacaAccountId("enc-acct");
        user.setAccountStatus("ACTIVE");
        return user;
    }

    private void stubReadyState(long userId) {
        TlhUserState state = new TlhUserState(userId);
        state.setLedgerStatus(TlhUserState.LedgerStatus.READY);
        when(stateRepository.findByUserId(userId)).thenReturn(Optional.of(state));
    }

    private void stubOneCandidate() {
        HarvestCandidate candidate = new HarvestCandidate("VTI", new BigDecimal("10"),
                new BigDecimal("100.00"), new BigDecimal("10.00"),
                new SalePreview(new BigDecimal("10"), new BigDecimal("-100.00"), true, false, true));
        when(eligibilityService.evaluate(any(), anyBoolean(), anyList(), anyMap(), anySet(), any()))
                .thenReturn(new EvalResult(List.of(candidate), List.of()));
        when(replacementSelectionService.select(anyLong(), eq("VTI"), any(), anySet()))
                .thenReturn(ReplacementSelectionService.ReplacementDecision.buy("ITOT", true));
    }

    @Test
    void modeOffIsACompleteNoOp() {
        service("off", 5).runCycle(TODAY, "SCHEDULED");

        verifyNoInteractions(alpacaService, cycleRunRepository, userRepository, executionService);
    }

    @Test
    void closedOrUnreachableClockSkipsTheCycleFailClosed() {
        when(alpacaService.getMarketClock()).thenReturn(false);

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        verify(cycleRunRepository, never()).save(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void dryRunPersistsProposedWithNumbersAndZeroOrdersAndNoWindow() {
        // The go-live-gate test: dry-run must never gate a real user's buys
        // (no window row) and must never touch order endpoints.
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        stubReadyState(7L);
        stubOneCandidate();

        service("dry-run", 5).runCycle(TODAY, "SCHEDULED");

        ArgumentCaptor<TlhHarvestEvent> captor = ArgumentCaptor.forClass(TlhHarvestEvent.class);
        verify(eventRepository).save(captor.capture());
        TlhHarvestEvent proposed = captor.getValue();
        assertEquals(TlhHarvestEvent.EventStatus.PROPOSED, proposed.getStatus());
        assertEquals(TlhHarvestEvent.HarvestMode.DRY_RUN, proposed.getMode());
        assertEquals("ITOT", proposed.getReplacementSymbol());
        assertEquals(0, proposed.getEstimatedLoss().compareTo(new BigDecimal("100.00")));
        assertNotNull(proposed.getSellClientOrderId());

        verify(guard, never()).openProvisionalWindow(any(), any(), any());
        verify(executionService, never()).submitSell(any(), any());
        verify(alpacaService, never()).placeSellOrderWithQuantity(anyString(), anyString(), any(), anyString());
    }

    @Test
    void liveClaimCommitsWindowThenDelegatesToExecution() {
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        stubReadyState(7L);
        stubOneCandidate();

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        InOrder inOrder = inOrder(guard, executionService);
        inOrder.verify(guard).openProvisionalWindow(any(TlhHarvestEvent.class), eq(TODAY), eq("ITOT"));
        inOrder.verify(executionService).submitSell(any(TlhHarvestEvent.class), eq(user));
    }

    @Test
    void duplicateClaimIsAnIdempotentSkip() {
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        stubReadyState(7L);
        stubOneCandidate();
        when(eventRepository.save(any(TlhHarvestEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate claim"));

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        verify(executionService, never()).submitSell(any(), any());
    }

    @Test
    void midFlightEventSkipsTheUserEntirely() {
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        when(eventRepository.existsByUserIdAndStatusIn(eq(7L), anyCollection())).thenReturn(true);

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        verify(alpacaService, never()).getCurrentPositions(anyString());
    }

    @Test
    void backfillingLedgerSkipsTheUser() {
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        when(stateRepository.findByUserId(7L)).thenReturn(Optional.of(new TlhUserState(7L)));

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        verify(alpacaService, never()).getCurrentPositions(anyString());
    }

    @Test
    void truncatedFillScanNeverHarvests() {
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        stubReadyState(7L);
        when(alpacaService.getFillActivities(anyString(), any(), any(), anyInt()))
                .thenReturn(new AlpacaService.FillActivityPage(List.of(), "token", false));

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        verify(eventRepository, never()).save(any());
        verify(executionService, never()).submitSell(any(), any());
    }

    @Test
    void oneUserFailureNeverKillsTheBatch() {
        User failing = proUser(7L);
        User healthy = proUser(8L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(failing, healthy));
        stubReadyState(7L);
        stubReadyState(8L);
        when(alpacaService.getCurrentPositions("acct-1"))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(POSITIONS_JSON);
        when(eligibilityService.evaluate(any(), anyBoolean(), anyList(), anyMap(), anySet(), any()))
                .thenReturn(new EvalResult(List.of(), List.of()));

        service("live", 5).runCycle(TODAY, "SCHEDULED");

        // Both users' position fetches were attempted; the run row records one failure
        verify(alpacaService, times(2)).getCurrentPositions("acct-1");
        ArgumentCaptor<TlhCycleRun> run = ArgumentCaptor.forClass(TlhCycleRun.class);
        verify(cycleRunRepository, atLeastOnce()).save(run.capture());
        assertEquals(1, run.getValue().getFailed());
    }

    @Test
    void consecutiveFailuresHaltTheCycle() {
        User first = proUser(7L);
        User second = proUser(8L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(first, second));
        stubReadyState(7L);
        stubReadyState(8L);
        when(alpacaService.getCurrentPositions("acct-1")).thenThrow(new IllegalStateException("down"));

        service("live", 1).runCycle(TODAY, "SCHEDULED");

        // Halt after the first failure — the second user is never evaluated
        verify(alpacaService, times(1)).getCurrentPositions("acct-1");
    }

    @Test
    void vetoesArePersistedOnceWithSevenDayDedup() {
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        stubReadyState(7L);
        when(eligibilityService.evaluate(any(), anyBoolean(), anyList(), anyMap(), anySet(), any()))
                .thenReturn(new EvalResult(List.of(),
                        List.of(new Veto("VTI", "BELOW_LOSS_THRESHOLDS"))));

        service("dry-run", 5).runCycle(TODAY, "SCHEDULED");
        ArgumentCaptor<TlhHarvestEvent> captor = ArgumentCaptor.forClass(TlhHarvestEvent.class);
        verify(eventRepository).save(captor.capture());
        assertEquals(TlhHarvestEvent.EventStatus.VETOED, captor.getValue().getStatus());
        assertEquals("BELOW_LOSS_THRESHOLDS", captor.getValue().getVetoReason());

        // Second run inside the dedup horizon: no new row
        when(eventRepository.existsByUserIdAndSymbolAndVetoReasonAndCreatedAtAfter(
                eq(7L), eq("VTI"), eq("BELOW_LOSS_THRESHOLDS"), any())).thenReturn(true);
        service("dry-run", 5).runCycle(TODAY.plusDays(1), "SCHEDULED");
        verify(eventRepository, times(1)).save(any(TlhHarvestEvent.class));
    }

    @Test
    void sameCycleConflictDropsTheSmallerLossCandidate() {
        // ITOT is both a candidate and VTI's chosen replacement — VTI has the
        // larger loss, so the ITOT harvest is dropped.
        User user = proUser(7L);
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro")))
                .thenReturn(List.of(user));
        stubReadyState(7L);
        SalePreview preview = new SalePreview(new BigDecimal("10"), new BigDecimal("-100.00"), true, false, true);
        HarvestCandidate vti = new HarvestCandidate("VTI", new BigDecimal("10"),
                new BigDecimal("100.00"), new BigDecimal("10.00"), preview);
        HarvestCandidate itot = new HarvestCandidate("ITOT", new BigDecimal("5"),
                new BigDecimal("60.00"), new BigDecimal("8.00"), preview);
        when(eligibilityService.evaluate(any(), anyBoolean(), anyList(), anyMap(), anySet(), any()))
                .thenReturn(new EvalResult(List.of(vti, itot), List.of()));
        when(replacementSelectionService.select(anyLong(), eq("VTI"), any(), anySet()))
                .thenReturn(ReplacementSelectionService.ReplacementDecision.buy("ITOT", true));

        service("dry-run", 5).runCycle(TODAY, "SCHEDULED");

        ArgumentCaptor<TlhHarvestEvent> captor = ArgumentCaptor.forClass(TlhHarvestEvent.class);
        verify(eventRepository, times(2)).save(captor.capture());
        List<TlhHarvestEvent> saved = captor.getAllValues();
        assertEquals(TlhHarvestEvent.EventStatus.VETOED, saved.get(0).getStatus());
        assertEquals("SAME_CYCLE_CONFLICT", saved.get(0).getVetoReason());
        assertEquals("ITOT", saved.get(0).getSymbol());
        assertEquals(TlhHarvestEvent.EventStatus.PROPOSED, saved.get(1).getStatus());
        assertEquals("VTI", saved.get(1).getSymbol());
        // The replacement was never re-resolved for ITOT as a harvest
        verify(replacementSelectionService, never()).select(anyLong(), eq("ITOT"), any(), anySet());
    }

    @Test
    void nightlyScopeIsTheUnionIncludingDowngradedUsersWithWindows() {
        // A downgraded (tier=null) user with an active window still gets
        // violation detection — the summary must converge toward 1099 truth.
        when(userRepository.findBySelectedTierInAndTlhEnabledTrue(List.of("pro"))).thenReturn(List.of());
        when(windowRepository.findUserIdsWithActiveWindows()).thenReturn(List.of(9L));
        when(eventRepository.findUserIdsWithOpenWork(anyCollection())).thenReturn(List.of());
        User downgraded = proUser(9L);
        downgraded.setSelectedTier(null);
        when(userRepository.findById(9L)).thenReturn(Optional.of(downgraded));
        stubReadyState(9L);
        when(windowRepository.findByUserIdAndActiveTrue(9L)).thenReturn(List.of());
        when(taxLotRepository.findByUserIdAndStatus(9L, TaxLot.LotStatus.UNRECONCILED))
                .thenReturn(List.of());

        service("live", 5).reconcileNightly(TODAY);

        verify(alpacaService).getFillActivities(eq("acct-1"), any(), any(), anyInt());
        verify(guard).expireWindows(TODAY);
    }
}

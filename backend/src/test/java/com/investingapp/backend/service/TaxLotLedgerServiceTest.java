package com.investingapp.backend.service;

import com.investingapp.backend.model.LotDisposal;
import com.investingapp.backend.model.TaxLot;
import com.investingapp.backend.repository.LotDisposalRepository;
import com.investingapp.backend.repository.TaxLotRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaxLotLedgerServiceTest {

    private TaxLotRepository taxLotRepository;
    private LotDisposalRepository lotDisposalRepository;
    private TlhHarvestEventRepository eventRepository;
    private TaxLotLedgerService ledger;

    private static final Long USER = 7L;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 1, 10, 0);

    @BeforeEach
    void setUp() {
        taxLotRepository = mock(TaxLotRepository.class);
        lotDisposalRepository = mock(LotDisposalRepository.class);
        eventRepository = mock(TlhHarvestEventRepository.class);
        when(taxLotRepository.save(any(TaxLot.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lotDisposalRepository.save(any(LotDisposal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.findBySellOrderId(anyString())).thenReturn(Optional.empty());
        ledger = new TaxLotLedgerService(taxLotRepository, lotDisposalRepository, eventRepository);
    }

    private static TaxLot lot(long id, String symbol, String qty, String costPerShare, LocalDateTime acquiredAt) {
        TaxLot lot = new TaxLot();
        lot.setId(id);
        lot.setUserId(USER);
        lot.setSymbol(symbol);
        lot.setOriginalQuantity(new BigDecimal(qty));
        lot.setRemainingQuantity(new BigDecimal(qty));
        lot.setCostPerShare(new BigDecimal(costPerShare));
        lot.setAcquiredAt(acquiredAt);
        lot.setSource(TaxLot.LotSource.BACKFILL);
        lot.setStatus(TaxLot.LotStatus.OPEN);
        return lot;
    }

    @Test
    void buyFillCreatesOpenLot() {
        ledger.applyFill(USER, new TaxLotLedgerService.FillRecord(
                "act-1", "ord-1", "buy", "vti", new BigDecimal("2.5"),
                new BigDecimal("210.123456"), T0, TaxLot.LotSource.SCHEDULED_BUY));

        ArgumentCaptor<TaxLot> captor = ArgumentCaptor.forClass(TaxLot.class);
        verify(taxLotRepository).save(captor.capture());
        TaxLot saved = captor.getValue();
        assertEquals("VTI", saved.getSymbol());
        assertEquals(0, saved.getRemainingQuantity().compareTo(new BigDecimal("2.5")));
        assertEquals(0, saved.getCostPerShare().compareTo(new BigDecimal("210.123456")));
        assertEquals(T0, saved.getAcquiredAt());
        assertEquals("act-1", saved.getSourceActivityId());
        assertEquals("ord-1", saved.getSourceOrderId());
        assertEquals(TaxLot.LotStatus.OPEN, saved.getStatus());
    }

    @Test
    void sellRelievesStrictlyOldestFirstWithSignedPnl() {
        TaxLot oldLot = lot(1L, "VTI", "5", "100", T0.minusDays(60));
        TaxLot newLot = lot(2L, "VTI", "5", "120", T0.minusDays(5));
        when(taxLotRepository.findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(
                USER, "VTI", TaxLot.LotStatus.OPEN)).thenReturn(List.of(oldLot, newLot));

        // Sell 7 @ 110: old lot gains (110-100)*5 = +50, new lot loses (110-120)*2 = -20
        ledger.applyFill(USER, new TaxLotLedgerService.FillRecord(
                "act-2", "ord-2", "sell", "VTI", new BigDecimal("7"),
                new BigDecimal("110"), T0, TaxLot.LotSource.EXTERNAL));

        ArgumentCaptor<LotDisposal> captor = ArgumentCaptor.forClass(LotDisposal.class);
        verify(lotDisposalRepository, times(2)).save(captor.capture());
        List<LotDisposal> disposals = captor.getAllValues();

        assertEquals(1L, disposals.get(0).getTaxLotId());
        assertEquals(0, disposals.get(0).getQuantity().compareTo(new BigDecimal("5")));
        assertEquals(0, disposals.get(0).getRealizedPnl().compareTo(new BigDecimal("50.00")));

        assertEquals(2L, disposals.get(1).getTaxLotId());
        assertEquals(0, disposals.get(1).getQuantity().compareTo(new BigDecimal("2")));
        assertEquals(0, disposals.get(1).getRealizedPnl().compareTo(new BigDecimal("-20.00")));

        assertEquals(TaxLot.LotStatus.CLOSED, oldLot.getStatus());
        assertEquals(0, oldLot.getRemainingQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(TaxLot.LotStatus.OPEN, newLot.getStatus());
        assertEquals(0, newLot.getRemainingQuantity().compareTo(new BigDecimal("3")));
    }

    @Test
    void duplicateActivityIdIsNoOp() {
        when(taxLotRepository.existsBySourceActivityId("act-9")).thenReturn(true);

        ledger.applyFill(USER, new TaxLotLedgerService.FillRecord(
                "act-9", "ord-9", "buy", "VTI", BigDecimal.ONE,
                new BigDecimal("100"), T0, TaxLot.LotSource.BACKFILL));

        verify(taxLotRepository, never()).save(any());
        verify(lotDisposalRepository, never()).save(any());
    }

    @Test
    void activityWhoseOrderWasPollerAppliedIsSkipped() {
        // The single-source-of-truth pin: the poller applied this order
        // synthetically; nightly ingestion of its FILL activity must be a no-op
        // — without this, every live harvest double-disposes the same lots.
        when(taxLotRepository.existsBySourceActivityId("act-10")).thenReturn(false);
        when(lotDisposalRepository.existsBySellActivityId("act-10")).thenReturn(false);
        when(lotDisposalRepository.existsByUserIdAndSellOrderId(USER, "tlh-order-1")).thenReturn(true);

        ledger.applyFill(USER, new TaxLotLedgerService.FillRecord(
                "act-10", "tlh-order-1", "sell", "VTI", new BigDecimal("5"),
                new BigDecimal("100"), T0, TaxLot.LotSource.BACKFILL));

        verify(lotDisposalRepository, never()).save(any());
        verify(taxLotRepository, never()).save(any());
    }

    @Test
    void syntheticReapplicationIsSkipped() {
        when(taxLotRepository.existsByUserIdAndSourceOrderId(USER, "tlh-buy-1")).thenReturn(true);

        ledger.applySyntheticOrderFill(USER, "tlh-buy-1", "buy", "ITOT",
                new BigDecimal("3"), new BigDecimal("95"), T0, TaxLot.LotSource.TLH_REPLACEMENT);

        verify(taxLotRepository, never()).save(any());
    }

    @Test
    void oversellMarksSymbolUnreconciled() {
        TaxLot only = lot(1L, "VTI", "2", "100", T0.minusDays(30));
        when(taxLotRepository.findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(
                USER, "VTI", TaxLot.LotStatus.OPEN)).thenReturn(List.of(only));
        when(taxLotRepository.findByUserIdAndSymbol(USER, "VTI")).thenReturn(List.of(only));

        ledger.applyFill(USER, new TaxLotLedgerService.FillRecord(
                "act-3", "ord-3", "sell", "VTI", new BigDecimal("5"),
                new BigDecimal("90"), T0, TaxLot.LotSource.EXTERNAL));

        assertEquals(TaxLot.LotStatus.UNRECONCILED, only.getStatus());
    }

    @Test
    void reconcileFlagsQuantityMismatchAndPassesWithinTolerance() {
        TaxLot vti = lot(1L, "VTI", "10", "100", T0);
        TaxLot vxus = lot(2L, "VXUS", "4", "50", T0);
        when(taxLotRepository.findByUserIdAndStatus(USER, TaxLot.LotStatus.OPEN))
                .thenReturn(List.of(vti, vxus));
        when(taxLotRepository.findByUserIdAndSymbol(USER, "VTI")).thenReturn(List.of(vti));

        List<TaxLotLedgerService.PositionSnapshot> positions = List.of(
                new TaxLotLedgerService.PositionSnapshot("VTI", new BigDecimal("9"), new BigDecimal("9"),
                        new BigDecimal("900"), new BigDecimal("900"), new BigDecimal("-100"),
                        new BigDecimal("100"), true),
                new TaxLotLedgerService.PositionSnapshot("VXUS", new BigDecimal("4.0000005"), new BigDecimal("4"),
                        new BigDecimal("200"), new BigDecimal("200"), BigDecimal.ZERO,
                        new BigDecimal("50"), true));

        TaxLotLedgerService.ReconcileResult result = ledger.reconcile(USER, positions);

        assertEquals(List.of("VTI"), result.unreconciledSymbols());
        assertEquals(TaxLot.LotStatus.UNRECONCILED, vti.getStatus());
        assertEquals(TaxLot.LotStatus.OPEN, vxus.getStatus());
    }

    @Test
    void reconcileFlagsLedgerHoldingsAlpacaDoesNotHave() {
        TaxLot ghost = lot(1L, "GME", "3", "40", T0);
        when(taxLotRepository.findByUserIdAndStatus(USER, TaxLot.LotStatus.OPEN))
                .thenReturn(List.of(ghost));
        when(taxLotRepository.findByUserIdAndSymbol(USER, "GME")).thenReturn(List.of(ghost));

        TaxLotLedgerService.ReconcileResult result = ledger.reconcile(USER, List.of());

        assertEquals(List.of("GME"), result.unreconciledSymbols());
    }

    @Test
    void previewAllLotsUnderwaterHonorsBufferBoundary() {
        // basis 100, buffer 0.5% ⇒ threshold 99.5: price must be BELOW it
        TaxLot lot = lot(1L, "VTI", "10", "100", T0);
        when(taxLotRepository.findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(
                USER, "VTI", TaxLot.LotStatus.OPEN)).thenReturn(List.of(lot));

        TaxLotLedgerService.SalePreview above = ledger.previewFifoSale(
                USER, "VTI", new BigDecimal("99.60"), new BigDecimal("0.5"));
        TaxLotLedgerService.SalePreview below = ledger.previewFifoSale(
                USER, "VTI", new BigDecimal("99.40"), new BigDecimal("0.5"));

        assertFalse(above.allLotsUnderwater());
        assertTrue(below.allLotsUnderwater());
        assertTrue(below.hasOpenLots());
    }

    @Test
    void previewOneAboveWaterLotDefeatsTheWholePosition() {
        // The FIFO-gain trap pin: a single above-water lot vetoes the harvest
        TaxLot deepLoss = lot(1L, "VTI", "10", "150", T0.minusDays(90));
        TaxLot smallGain = lot(2L, "VTI", "1", "90", T0.minusDays(3));
        when(taxLotRepository.findByUserIdAndSymbolAndStatusOrderByAcquiredAtAsc(
                USER, "VTI", TaxLot.LotStatus.OPEN)).thenReturn(List.of(deepLoss, smallGain));

        TaxLotLedgerService.SalePreview preview = ledger.previewFifoSale(
                USER, "VTI", new BigDecimal("100"), new BigDecimal("0.5"));

        assertFalse(preview.allLotsUnderwater());
    }

    @Test
    void rebaselineCollapsesToSingleRebasedLot() {
        TaxLot stale = lot(1L, "VTI", "10", "100", T0);
        stale.setStatus(TaxLot.LotStatus.UNRECONCILED);
        when(taxLotRepository.findByUserIdAndSymbol(USER, "VTI")).thenReturn(List.of(stale));

        ledger.rebaselineSymbol(USER, "VTI", new TaxLotLedgerService.PositionSnapshot(
                "VTI", new BigDecimal("20"), new BigDecimal("20"), new BigDecimal("1900"),
                new BigDecimal("2000"), new BigDecimal("-100"), new BigDecimal("95"), true));

        assertEquals(TaxLot.LotStatus.CLOSED, stale.getStatus());
        ArgumentCaptor<TaxLot> captor = ArgumentCaptor.forClass(TaxLot.class);
        verify(taxLotRepository, atLeastOnce()).save(captor.capture());
        TaxLot rebased = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(TaxLot.LotSource.REBASED, rebased.getSource());
        assertEquals(0, rebased.getRemainingQuantity().compareTo(new BigDecimal("20")));
        assertEquals(0, rebased.getCostPerShare().compareTo(new BigDecimal("100.000000")));
    }

    @Test
    void washDisallowanceMatchesEarliestSharesAndBumpsReplacementBasis() {
        com.investingapp.backend.model.TlhHarvestEvent event = new com.investingapp.backend.model.TlhHarvestEvent();
        event.setId(55L);
        event.setUserId(USER);
        event.setSymbol("VTI");
        event.setSellFilledAt(T0);

        LotDisposal loss = new LotDisposal();
        loss.setId(9L);
        loss.setUserId(USER);
        loss.setTaxLotId(1L);
        loss.setSymbol("VTI");
        loss.setQuantity(new BigDecimal("10"));
        loss.setProceedsPerShare(new BigDecimal("95"));
        loss.setRealizedPnl(new BigDecimal("-50.00")); // $5/share loss
        loss.setDisposedAt(T0);
        when(lotDisposalRepository.findByHarvestEventId(55L)).thenReturn(List.of(loss));

        TaxLot survivor = lot(3L, "VTI", "4", "98", T0.minusDays(10)); // bought 10d before the sale, survived
        when(taxLotRepository.findByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
                eq(USER), anyCollection(), eq(TaxLot.LotStatus.OPEN), any()))
                .thenReturn(List.of(survivor));
        when(taxLotRepository.findById(3L)).thenReturn(Optional.of(survivor));

        TaxLotLedgerService.DisallowanceResult result =
                ledger.applyWashDisallowance(event, Set.of("VTI"));

        // 4 of 10 loss shares matched ⇒ 4 × $5 = $20 disallowed
        assertEquals(0, result.totalDisallowed().compareTo(new BigDecimal("20.00")));
        assertEquals(0, loss.getDisallowedLoss().compareTo(new BigDecimal("20.00")));
        assertTrue(loss.getWashSale());
        assertEquals(3L, loss.getReplacementLotId());
        // survivor basis bumped by 20/4 = $5/share
        assertEquals(0, survivor.getWashAdjustedCostPerShare().compareTo(new BigDecimal("103.000000")));
        assertEquals(T0, survivor.getHoldingPeriodTackedFrom());
    }

    @Test
    void washDisallowanceIsIdempotentAcrossReruns() {
        com.investingapp.backend.model.TlhHarvestEvent event = new com.investingapp.backend.model.TlhHarvestEvent();
        event.setId(56L);
        event.setUserId(USER);
        event.setSymbol("VTI");
        event.setSellFilledAt(T0);

        LotDisposal loss = new LotDisposal();
        loss.setId(10L);
        loss.setUserId(USER);
        loss.setTaxLotId(1L);
        loss.setSymbol("VTI");
        loss.setQuantity(new BigDecimal("10"));
        loss.setProceedsPerShare(new BigDecimal("95"));
        loss.setRealizedPnl(new BigDecimal("-50.00"));
        loss.setDisposedAt(T0);
        when(lotDisposalRepository.findByHarvestEventId(56L)).thenReturn(List.of(loss));

        TaxLot survivor = lot(4L, "VTI", "4", "98", T0.minusDays(10));
        when(taxLotRepository.findByUserIdAndSymbolInAndStatusAndAcquiredAtAfter(
                eq(USER), anyCollection(), eq(TaxLot.LotStatus.OPEN), any()))
                .thenReturn(List.of(survivor));
        when(taxLotRepository.findById(4L)).thenReturn(Optional.of(survivor));

        ledger.applyWashDisallowance(event, Set.of("VTI"));
        TaxLotLedgerService.DisallowanceResult second =
                ledger.applyWashDisallowance(event, Set.of("VTI"));

        // SET-not-accumulate: the re-run produces the same $20, not $40
        assertEquals(0, second.totalDisallowed().compareTo(new BigDecimal("20.00")));
        assertEquals(0, loss.getDisallowedLoss().compareTo(new BigDecimal("20.00")));
        // basis bump recomputed from base cost, not compounded
        assertEquals(0, survivor.getWashAdjustedCostPerShare().compareTo(new BigDecimal("103.000000")));
    }
}

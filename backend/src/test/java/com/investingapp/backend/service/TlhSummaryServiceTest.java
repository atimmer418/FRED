package com.investingapp.backend.service;

import com.investingapp.backend.dto.TlhStatusResponse;
import com.investingapp.backend.dto.TlhSummaryResponse;
import com.investingapp.backend.model.TlhHarvestEvent;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.TaxLotRepository;
import com.investingapp.backend.repository.TlhHarvestEventRepository;
import com.investingapp.backend.repository.TlhUserStateRepository;
import com.investingapp.backend.repository.WashSaleWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TlhSummaryServiceTest {

    private TlhHarvestEventRepository eventRepository;
    private WashSaleWindowRepository windowRepository;
    private TlhUserStateRepository stateRepository;
    private TaxLotRepository taxLotRepository;
    private TlhSummaryService service;
    private User user;

    @BeforeEach
    void setUp() {
        eventRepository = mock(TlhHarvestEventRepository.class);
        windowRepository = mock(WashSaleWindowRepository.class);
        stateRepository = mock(TlhUserStateRepository.class);
        taxLotRepository = mock(TaxLotRepository.class);
        service = new TlhSummaryService(eventRepository, windowRepository, stateRepository,
                taxLotRepository, new BigDecimal("0.24"), "live");
        user = new User();
        user.setId(7L);
        user.setTlhEnabled(true);
    }

    @Test
    void savingsAreAllowedLossTimesRateHalfUp() {
        when(eventRepository.sumAllowedLossForYear(7L, 2026)).thenReturn(new BigDecimal("333.33"));
        when(eventRepository.sumDisallowedLossForYear(7L, 2026)).thenReturn(new BigDecimal("10.00"));
        when(eventRepository.countByUserIdAndModeAndTaxYear(7L, TlhHarvestEvent.HarvestMode.LIVE, 2026))
                .thenReturn(3);

        TlhSummaryResponse summary = service.getSummary(user, 2026);

        // 333.33 × 0.24 = 79.9992 → 80.00
        assertEquals(0, summary.estimatedSavings().compareTo(new BigDecimal("80.00")));
        assertEquals(0, summary.harvestedLossYtd().compareTo(new BigDecimal("333.33")));
        assertEquals(0, summary.disallowedYtd().compareTo(new BigDecimal("10.00")));
        assertEquals(3, summary.eventCount());
        assertNotNull(summary.disclaimer());
    }

    @Test
    void disclaimerAvoidsComplianceBlockedPhrasing() {
        // MarketNarrativeValidator blocks "rebalance your" — TLH copy must
        // never trip it if quoted by a narrative surface later.
        assertFalse(TlhSummaryService.DISCLAIMER.toLowerCase().contains("rebalance your"));
        assertTrue(TlhSummaryService.DISCLAIMER.contains("1099-B"));
        assertTrue(TlhSummaryService.DISCLAIMER.contains("not tax advice"));
    }

    @Test
    void statusReportsOnlyDateValidWindows() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        WashSaleWindow current = new WashSaleWindow();
        current.setSymbol("VTI");
        current.setStartDate(today.minusDays(5));
        current.setEndDate(today.plusDays(25));
        current.setRedirectSymbol("ITOT");
        current.setActive(true);
        WashSaleWindow stale = new WashSaleWindow();
        stale.setSymbol("VXUS");
        stale.setStartDate(today.minusDays(60));
        stale.setEndDate(today.minusDays(30));
        stale.setActive(true); // stale flag — date comparison must win
        when(windowRepository.findByUserIdAndActiveTrue(7L)).thenReturn(List.of(current, stale));
        when(stateRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(taxLotRepository.findUnreconciledSymbols(7L)).thenReturn(List.of("GME"));
        when(eventRepository.countByUserIdAndStatusIn(eq(7L), anyCollection())).thenReturn(1);

        TlhStatusResponse status = service.getStatus(user, true);

        assertTrue(status.enrolled());
        assertEquals(1, status.activeWindows().size());
        assertEquals("VTI", status.activeWindows().get(0).symbol());
        assertEquals("ITOT", status.activeWindows().get(0).redirectSymbol());
        assertEquals(List.of("GME"), status.unreconciledSymbols());
        assertEquals("NONE", status.ledgerStatus());
        assertEquals(1, status.inFlightEvents());
    }

    @Test
    void historyMeansEventsOrActiveWindows() {
        when(eventRepository.existsByUserId(7L)).thenReturn(false);
        when(windowRepository.existsByUserIdAndActiveTrue(7L)).thenReturn(true);
        assertTrue(service.hasTlhHistory(user));

        when(windowRepository.existsByUserIdAndActiveTrue(7L)).thenReturn(false);
        assertFalse(service.hasTlhHistory(user));
    }
}

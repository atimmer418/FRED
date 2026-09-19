package com.investingapp.backend.service;

import com.investingapp.backend.model.DripExecution;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.DripExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DripServiceTlhGuardTest {

    private DripService service;
    private DripExecutionRepository dripRepository;
    private AlpacaService alpacaService;
    private EncryptionService encryptionService;
    private WashSaleGuardService guard;
    private User user;

    @BeforeEach
    void setUp() {
        service = new DripService();
        dripRepository = mock(DripExecutionRepository.class);
        alpacaService = mock(AlpacaService.class);
        encryptionService = mock(EncryptionService.class);
        guard = mock(WashSaleGuardService.class);
        ReflectionTestUtils.setField(service, "dripExecutionRepository", dripRepository);
        ReflectionTestUtils.setField(service, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(service, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(service, "washSaleGuardService", guard);

        user = new User();
        user.setId(7L);
        user.setEmail("andy@fredvested.com");
        user.setAlpacaAccountId("enc-acct");
        when(encryptionService.decrypt("enc-acct")).thenReturn("acct-1");
        when(dripRepository.findByUserOrderByDividendDateDesc(user)).thenReturn(List.of());
        when(dripRepository.existsByAlpacaActivityId(anyString())).thenReturn(false);
        when(dripRepository.save(any(DripExecution.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubDividend(String symbol, String amount) {
        AlpacaService.DividendActivity dividend = new AlpacaService.DividendActivity(
                "div-1", "DIV", "CDIV", "2026-07-10", new BigDecimal(amount), symbol,
                "10", "0.50", "Cash DIV", "executed", "acct-1", "2026-07-10T12:00:00Z");
        when(alpacaService.getDividendActivities(eq("acct-1"), anyString(), anyString()))
                .thenReturn(List.of(dividend));
    }

    @Test
    void windowedDividendIsParkedAsCashWithExplanation() {
        stubDividend("VTI", "5.00");
        WashSaleWindow window = new WashSaleWindow();
        window.setSymbol("VTI");
        window.setEndDate(LocalDate.of(2026, 7, 31));
        when(guard.checkBuy(eq(7L), eq("VTI"), eq(WashSaleGuardService.TradeSource.DRIP), any()))
                .thenReturn(new WashSaleGuardService.GuardDecision(
                        WashSaleGuardService.GuardAction.SKIP, null, window));

        service.processDividendsForUser(user);

        verify(alpacaService, never()).placeBuyOrder(anyString(), anyString(), any());
        ArgumentCaptor<DripExecution> captor = ArgumentCaptor.forClass(DripExecution.class);
        verify(dripRepository, atLeastOnce()).save(captor.capture());
        DripExecution saved = captor.getValue();
        assertEquals(DripExecution.DripStatus.SKIPPED, saved.getStatus());
        assertTrue(saved.getErrorMessage().contains("2026-07-31"));
        assertEquals("VTI", saved.getSymbol());
    }

    @Test
    void redirectPolicyBuysTheReplacementKeepingTheOriginalSymbolOnTheRow() {
        stubDividend("VTI", "5.00");
        when(guard.checkBuy(eq(7L), eq("VTI"), eq(WashSaleGuardService.TradeSource.DRIP), any()))
                .thenReturn(new WashSaleGuardService.GuardDecision(
                        WashSaleGuardService.GuardAction.REDIRECT, "ITOT", new WashSaleWindow()));
        when(alpacaService.placeBuyOrder("acct-1", "ITOT", new BigDecimal("5.00")))
                .thenReturn(new AlpacaService.AlpacaOrderResponse("o-1", "ITOT", "accepted",
                        null, new BigDecimal("5.00"), BigDecimal.ZERO, null, "2026-07-15T14:00:00Z", null));

        service.processDividendsForUser(user);

        verify(alpacaService).placeBuyOrder("acct-1", "ITOT", new BigDecimal("5.00"));
        ArgumentCaptor<DripExecution> captor = ArgumentCaptor.forClass(DripExecution.class);
        verify(dripRepository, atLeastOnce()).save(captor.capture());
        DripExecution saved = captor.getValue();
        assertEquals("VTI", saved.getSymbol()); // dividend row keeps the true source
        assertTrue(saved.getDescription().contains("ITOT"));
        assertEquals(DripExecution.DripStatus.ORDER_PLACED, saved.getStatus());
    }

    @Test
    void unwindowedDividendReinvestsUnchanged() {
        stubDividend("VXUS", "5.00");
        when(guard.checkBuy(anyLong(), anyString(), any(), any()))
                .thenReturn(WashSaleGuardService.GuardDecision.allow());
        when(alpacaService.placeBuyOrder("acct-1", "VXUS", new BigDecimal("5.00")))
                .thenReturn(new AlpacaService.AlpacaOrderResponse("o-2", "VXUS", "accepted",
                        null, new BigDecimal("5.00"), BigDecimal.ZERO, null, "2026-07-15T14:00:00Z", null));

        service.processDividendsForUser(user);

        verify(alpacaService).placeBuyOrder("acct-1", "VXUS", new BigDecimal("5.00"));
    }
}

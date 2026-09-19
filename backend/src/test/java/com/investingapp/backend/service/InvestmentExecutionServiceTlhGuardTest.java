package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.WashSaleWindow;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Scope: ONLY the TLH request-time guard hook on the lump-sum entry point.
 * The slice-level redirect/skip decisions are covered by
 * WashSaleGuardServiceTest; this pins the money path (reject BEFORE any ACH
 * pull, with a dated message) and that non-stock flows are untouched.
 */
class InvestmentExecutionServiceTlhGuardTest {

    private InvestmentExecutionService service;
    private InvestmentExecutionRepository executionRepository;
    private WashSaleGuardService guard;
    private User user;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        executionRepository = mock(InvestmentExecutionRepository.class);
        guard = mock(WashSaleGuardService.class);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(service, "washSaleGuardService", guard);
        when(executionRepository.save(any(InvestmentExecution.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        user = new User();
        user.setId(7L);
    }

    @Test
    void stockBuyInsideWashWindowIsRejectedBeforeAnyMoneyMoves() {
        WashSaleWindow window = new WashSaleWindow();
        window.setSymbol("VTI");
        window.setStartDate(LocalDate.of(2026, 7, 1));
        window.setEndDate(LocalDate.of(2026, 7, 31));
        when(guard.blockingWindow(eq(7L), eq("VTI"), any())).thenReturn(window);

        InvestmentExecution execution = new InvestmentExecution(
                user, LocalDateTime.now(), new BigDecimal("100.00"), "stock", "VTI", "bank");

        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
                () -> service.processInvestmentExecutionImmediately(execution));

        // Dated, user-comprehensible message; nothing queued, no ACH
        assertTrue(rejection.getMessage().contains("2026-08-01"));
        assertTrue(rejection.getMessage().contains("VTI"));
        verify(executionRepository, never()).save(any());
    }

    @Test
    void stockBuyWithNoWindowQueuesForEndOfDayUnchanged() {
        when(guard.blockingWindow(anyLong(), anyString(), any())).thenReturn(null);

        InvestmentExecution execution = new InvestmentExecution(
                user, LocalDateTime.now(), new BigDecimal("100.00"), "stock", "VTI", "bank");
        service.processInvestmentExecutionImmediately(execution);

        ArgumentCaptor<InvestmentExecution> captor = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository).save(captor.capture());
        assertEquals(InvestmentExecution.ExecutionStatus.SCHEDULED, captor.getValue().getStatus());
        // Load-bearing workflow marker string must never be clobbered
        assertEquals("Queued for end-of-day batch processing", captor.getValue().getErrorMessage());
    }

    @Test
    void portfolioLumpSumsNeverConsultTheStockGuard() {
        InvestmentExecution execution = new InvestmentExecution(
                user, LocalDateTime.now(), new BigDecimal("100.00"), "portfolio", null, "bank");
        service.processInvestmentExecutionImmediately(execution);

        verify(guard, never()).blockingWindow(anyLong(), anyString(), any());
        verify(executionRepository).save(any());
    }
}

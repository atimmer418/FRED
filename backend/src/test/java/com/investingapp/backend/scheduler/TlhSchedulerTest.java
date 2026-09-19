package com.investingapp.backend.scheduler;

import com.investingapp.backend.service.TlhExecutionService;
import com.investingapp.backend.service.TlhHarvestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TlhSchedulerTest {

    private TlhHarvestService harvestService;
    private TlhExecutionService executionService;
    private TlhScheduler scheduler;

    @BeforeEach
    void setUp() {
        harvestService = mock(TlhHarvestService.class);
        executionService = mock(TlhExecutionService.class);
        scheduler = new TlhScheduler(harvestService, executionService);
    }

    private static Scheduled scheduledOf(String method) throws Exception {
        Method m = TlhScheduler.class.getMethod(method);
        Scheduled annotation = m.getAnnotation(Scheduled.class);
        assertNotNull(annotation, method + " must be @Scheduled");
        return annotation;
    }

    @Test
    void cronOffsetsAvoidTheInvestmentPollerMinutes() throws Exception {
        // Load-bearing offsets: funding fires :00/:30, trading :00/:15/:30/:45,
        // DRIP at 10:00 — TLH must never share a minute with them.
        assertEquals("0 7 13 * * MON-FRI", scheduledOf("runDailyCycleJob").cron());
        assertEquals("0 7/15 9-16 * * MON-FRI", scheduledOf("pollJob").cron());
        assertEquals("0 40 16 * * MON-FRI", scheduledOf("eodSweepJob").cron());
        assertEquals("0 15 22 * * *", scheduledOf("nightlyReconcileJob").cron());
        assertEquals("America/New_York", scheduledOf("runDailyCycleJob").zone());
        assertEquals("America/New_York", scheduledOf("pollJob").zone());
        assertEquals("America/New_York", scheduledOf("eodSweepJob").zone());
        assertEquals("America/New_York", scheduledOf("nightlyReconcileJob").zone());
    }

    @Test
    void jobsDelegateAndNeverRethrow() {
        doThrow(new IllegalStateException("boom")).when(harvestService).runCycle(any(), anyString());
        doThrow(new IllegalStateException("boom")).when(executionService).pollOpenEvents();
        doThrow(new IllegalStateException("boom")).when(executionService).sweepEndOfDay(any());
        doThrow(new IllegalStateException("boom")).when(harvestService).reconcileNightly(any());

        assertDoesNotThrow(scheduler::runDailyCycleJob);
        assertDoesNotThrow(scheduler::pollJob);
        assertDoesNotThrow(scheduler::eodSweepJob);
        assertDoesNotThrow(scheduler::nightlyReconcileJob);

        verify(harvestService).runCycle(any(), eq("SCHEDULED"));
        verify(executionService).pollOpenEvents();
        verify(executionService).sweepEndOfDay(any());
        verify(harvestService).reconcileNightly(any());
    }
}

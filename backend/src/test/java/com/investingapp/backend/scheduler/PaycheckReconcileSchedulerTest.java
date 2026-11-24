package com.investingapp.backend.scheduler;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.service.PaycheckReconcileService;
import com.investingapp.backend.service.PaycheckReconcileService.ReconcileReport;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The 22:30 ET cron wrapper: gate, master flag, its own 60-minute lock, and the run and throw alerts. */
class PaycheckReconcileSchedulerTest {

    private PaycheckReconcileService reconcileService;
    private PaycheckAutopilotProperties props;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;
    private OpsAlertService opsAlertService;
    private PaycheckReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        reconcileService = mock(PaycheckReconcileService.class);
        props = mock(PaycheckAutopilotProperties.class);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        opsAlertService = mock(OpsAlertService.class);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(props.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
        when(reconcileService.run(any(), any())).thenReturn(ReconcileReport.empty());
        scheduler = new PaycheckReconcileScheduler(reconcileService, props, schedulerGate, jobLockService, opsAlertService);
    }

    /** A JVM default whose calendar day differs from Eastern right now (as InvestmentSchedulerTest). */
    private static TimeZone aZoneWhoseDayDiffersFromEastern() {
        int easternHour = LocalTime.now(EasternTime.ZONE).getHour();
        return TimeZone.getTimeZone(easternHour < 6 ? ZoneId.of("-12:00") : ZoneId.of("+14:00"));
    }

    @Test
    void cronPinned2230NewYork() throws Exception {
        Scheduled scheduled = PaycheckReconcileScheduler.class.getMethod("run").getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "run must be @Scheduled");
        assertEquals("0 30 22 * * *", scheduled.cron());
        assertEquals("America/New_York", scheduled.zone());
    }

    @Test
    void honorsSchedulerEnabledFalse() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        scheduler.run();

        verifyNoInteractions(jobLockService, reconcileService, opsAlertService);
    }

    @Test
    void disabledFlagReturnsBeforeTheLock() {
        when(props.isEnabled()).thenReturn(false);

        scheduler.run();

        verifyNoInteractions(jobLockService, reconcileService, opsAlertService);
    }

    @Test
    void runsUnderItsOwnLockAndRaisesRan() {
        ReconcileReport report = new ReconcileReport(3, 1, 2, 0, 3, 1, 0, 0, 1, 0, false, false);
        when(reconcileService.run(any(), any())).thenReturn(report);

        scheduler.run();

        InOrder order = inOrder(jobLockService, reconcileService, opsAlertService);
        order.verify(jobLockService).tryAcquire(JobLocks.PAYCHECK_RECONCILE, Duration.ofMinutes(60));
        order.verify(reconcileService).run(any(LocalDate.class), any(LocalDateTime.class));
        order.verify(opsAlertService).raise(eq(OpsAlerts.RECONCILE_RAN), eq("nightly"), anyString(), eq(report.toContext()));
        order.verify(jobLockService).release(JobLocks.PAYCHECK_RECONCILE);
        verify(opsAlertService, never()).raise(eq(OpsAlerts.RECONCILE_THREW), anyString(), anyString(), any());
    }

    @Test
    void threwRaisesThrewRaisesRanAndReleases() {
        when(reconcileService.run(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(scheduler::run);

        verify(opsAlertService).raise(OpsAlerts.RECONCILE_THREW, "nightly", "nightly reconcile threw",
                Map.of("exception", "IllegalStateException"));
        verify(opsAlertService).raise(OpsAlerts.RECONCILE_RAN, "nightly", "nightly reconcile did not complete",
                Map.of("completed", false));
        verify(jobLockService).release(JobLocks.PAYCHECK_RECONCILE);
    }

    @Test
    void lockHeldElsewhereRunsNothing() {
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(false);

        scheduler.run();

        verifyNoInteractions(reconcileService, opsAlertService);
        verify(jobLockService, never()).release(anyString());
    }

    @Test
    void passesOneEasternReading() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDate todayET = EasternTime.today();
            assertNotEquals(todayET, LocalDate.now(),
                    "precondition: the JVM default day must differ from Eastern for this test to bite");

            scheduler.run();

            ArgumentCaptor<LocalDateTime> nowET = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(reconcileService).run(eq(todayET), nowET.capture());
            assertEquals(todayET, nowET.getValue().toLocalDate(), "todayET and nowET come from one Eastern reading");
        } finally {
            TimeZone.setDefault(saved);
        }
    }
}

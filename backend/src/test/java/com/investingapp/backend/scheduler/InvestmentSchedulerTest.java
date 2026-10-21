package com.investingapp.backend.scheduler;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.DripService;
import com.investingapp.backend.service.InvestmentExecutionService;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.service.PaydayNotificationService;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The scheduler computes todayET once per job and hands it down (spec 5.8). The JVM default is
 * moved to a zone whose calendar day differs from Eastern right now, so a bare LocalDate.now()
 * anywhere on the path is caught.
 */
class InvestmentSchedulerTest {

    private InvestmentExecutionService investmentExecutionService;
    private DripService dripService;
    private PaydayNotificationService paydayNotificationService;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;
    private InvestmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        investmentExecutionService = mock(InvestmentExecutionService.class);
        dripService = mock(DripService.class);
        paydayNotificationService = mock(PaydayNotificationService.class);
        scheduler = new InvestmentScheduler();
        ReflectionTestUtils.setField(scheduler, "investmentExecutionService", investmentExecutionService);
        ReflectionTestUtils.setField(scheduler, "dripService", dripService);
        ReflectionTestUtils.setField(scheduler, "paydayNotificationService", paydayNotificationService);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(anyString(), any())).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "schedulerGate", schedulerGate);
        ReflectionTestUtils.setField(scheduler, "jobLockService", jobLockService);
    }

    private static Scheduled scheduledOf(String method) throws Exception {
        Method m = InvestmentScheduler.class.getMethod(method);
        Scheduled annotation = m.getAnnotation(Scheduled.class);
        assertNotNull(annotation, method + " must be @Scheduled");
        return annotation;
    }

    /** A JVM default whose calendar day differs from Eastern at this moment. */
    private static TimeZone aZoneWhoseDayDiffersFromEastern() {
        int easternHour = LocalTime.now(EasternTime.ZONE).getHour();
        return TimeZone.getTimeZone(easternHour < 6 ? ZoneId.of("-12:00") : ZoneId.of("+14:00"));
    }

    @Test
    void moneyAndNotificationCronsRunOnTheEasternClock() throws Exception {
        assertEquals("0 55 23 * * *", scheduledOf("processScheduledInvestments").cron());
        assertEquals("0 0 7 * * *", scheduledOf("sendDayBeforePaydayNotifications").cron());
        assertEquals("0 0 0 * * *", scheduledOf("sendPaydayNotifications").cron());
        assertEquals("0 0 8 * * *", scheduledOf("sendDayAfterPaydayNotifications").cron());
        for (String job : new String[] {"processScheduledInvestments", "sendDayBeforePaydayNotifications",
                "sendPaydayNotifications", "sendDayAfterPaydayNotifications"}) {
            assertEquals("America/New_York", scheduledOf(job).zone(), job);
        }
    }

    @Test
    void everyMoneyAndNotificationJobPassesTheEasternDay() {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(aZoneWhoseDayDiffersFromEastern());
            LocalDate todayET = EasternTime.today();
            assertNotEquals(todayET, LocalDate.now(),
                    "precondition: the JVM default day must differ from Eastern for this test to bite");

            scheduler.processScheduledInvestments();
            scheduler.sendDayBeforePaydayNotifications();
            scheduler.sendPaydayNotifications();
            scheduler.sendDayAfterPaydayNotifications();

            verify(investmentExecutionService).processScheduledInvestments(todayET);
            verify(paydayNotificationService).sendDayBeforeNotifications(todayET);
            verify(paydayNotificationService).sendPaydayNotifications(todayET.minusDays(1));
            verify(paydayNotificationService).sendDayAfterNotifications(todayET);
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    void jobsDelegateAndNeverRethrow() {
        doThrow(new IllegalStateException("boom")).when(investmentExecutionService).processScheduledInvestments(any());
        doThrow(new IllegalStateException("boom")).when(paydayNotificationService).sendDayBeforeNotifications(any());
        doThrow(new IllegalStateException("boom")).when(paydayNotificationService).sendPaydayNotifications(any());
        doThrow(new IllegalStateException("boom")).when(paydayNotificationService).sendDayAfterNotifications(any());

        assertDoesNotThrow(scheduler::processScheduledInvestments);
        assertDoesNotThrow(scheduler::sendDayBeforePaydayNotifications);
        assertDoesNotThrow(scheduler::sendPaydayNotifications);
        assertDoesNotThrow(scheduler::sendDayAfterPaydayNotifications);

        verify(investmentExecutionService).processScheduledInvestments(any());
        verify(paydayNotificationService).sendDayBeforeNotifications(any());
        verify(paydayNotificationService).sendPaydayNotifications(any());
        verify(paydayNotificationService).sendDayAfterNotifications(any());
    }

    private void runEveryJob() {
        scheduler.processScheduledInvestments();
        scheduler.checkFundingStatus();
        scheduler.checkTradingStatus();
        scheduler.processDripDividends();
        scheduler.checkDripOrderStatus();
        scheduler.sendDayBeforePaydayNotifications();
        scheduler.sendPaydayNotifications();
        scheduler.sendDayAfterPaydayNotifications();
    }

    @Test
    void closedGateSkipsEveryJob() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        runEveryJob();

        verifyNoInteractions(investmentExecutionService, dripService, paydayNotificationService, jobLockService);
    }

    @Test
    void onlyTheElevenFiftyFiveJobTakesALock() {
        runEveryJob();

        verify(jobLockService, times(1)).tryAcquire(anyString(), any());
        verify(jobLockService).tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10));
    }

    @Test
    void elevenFiftyFiveTakesItsLockRunsThenReleasesEvenWhenTheServiceThrows() {
        scheduler.processScheduledInvestments();

        InOrder inOrder = inOrder(jobLockService, investmentExecutionService);
        inOrder.verify(jobLockService).tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10));
        inOrder.verify(investmentExecutionService).processScheduledInvestments(any(LocalDate.class));
        inOrder.verify(jobLockService).release(JobLocks.PROCESS_SCHEDULED);

        doThrow(new IllegalStateException("boom")).when(investmentExecutionService).processScheduledInvestments(any());
        assertDoesNotThrow(scheduler::processScheduledInvestments);
        verify(jobLockService, times(2)).release(JobLocks.PROCESS_SCHEDULED);
    }

    @Test
    void elevenFiftyFiveSkipsWhenTheLockIsNotTaken() {
        when(jobLockService.tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10))).thenReturn(false);

        scheduler.processScheduledInvestments();

        verify(investmentExecutionService, never()).processScheduledInvestments(any());
        verify(jobLockService, never()).release(anyString());
    }
}

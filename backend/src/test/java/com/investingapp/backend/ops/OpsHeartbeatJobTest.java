package com.investingapp.backend.ops;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OpsHeartbeatJobTest {

    private final SchedulerGate gate = mock(SchedulerGate.class);
    private final JobLockService locks = mock(JobLockService.class);
    private final OpsGaugeService gauges = mock(OpsGaugeService.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private OpsHeartbeatJob job;

    @BeforeEach
    void setUp() {
        job = new OpsHeartbeatJob(gate, locks, gauges, alerts, props);
        when(gate.isEnabled()).thenReturn(true);
        when(locks.tryAcquire(eq(JobLocks.OPS_HEARTBEAT), any())).thenReturn(true);
        when(props.isEnabled()).thenReturn(true);
    }

    private static OpsGaugeSnapshot snap(long inboxFailed, long oldestAgeSec, long billable, long webhooks24h,
                                         long searchingOver24h, long manualReviewOpen, boolean ranYesterday) {
        return new OpsGaugeSnapshot(Map.of("SEARCHING", 2L), Map.of("OK", 3L), billable,
                Map.of("MANUAL_REVIEW", manualReviewOpen), 0, inboxFailed, oldestAgeSec, webhooks24h, 1, 4, 2,
                searchingOver24h, manualReviewOpen, ranYesterday);
    }

    private static OpsGaugeSnapshot quiet() {
        return snap(0, 0, 3, 5, 0, 0, true);
    }

    private static OpsGaugeSnapshot busy() {
        return snap(2, 1801, 3, 0, 1, 1, false);
    }

    @Test
    @DisplayName("the heartbeat fires at 09:07 New York time")
    void cronPinned0907NewYork() throws Exception {
        Scheduled scheduled = OpsHeartbeatJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 7 9 * * *");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("investment.scheduler.enabled=false stops the job before the lock")
    void honorsSchedulerEnabledFalse() {
        when(gate.isEnabled()).thenReturn(false);

        job.run();

        verifyNoInteractions(locks, gauges, alerts);
    }

    @Test
    @DisplayName("a lease held elsewhere means no snapshot and no alert")
    void lockNotTakenRaisesNothing() {
        when(locks.tryAcquire(eq(JobLocks.OPS_HEARTBEAT), any())).thenReturn(false);

        job.run();

        verifyNoInteractions(gauges, alerts);
        verify(locks, never()).release(any());
    }

    @Test
    @DisplayName("the snapshot is taken once, on the Eastern clock, and the lock is released")
    void snapshotIsTakenOnTheEasternClock() {
        when(gauges.snapshot(any())).thenReturn(quiet());
        LocalDateTime before = EasternTime.now();

        job.run();

        ArgumentCaptor<LocalDateTime> clock = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gauges, times(1)).snapshot(clock.capture());
        assertThat(clock.getValue()).isBetween(before, EasternTime.now());
        verify(locks).tryAcquire(JobLocks.OPS_HEARTBEAT, OpsHeartbeatJob.LOCK_LEASE);
        verify(locks).release(JobLocks.OPS_HEARTBEAT);
    }

    @Test
    @DisplayName("the digest is raised first and carries every gauge")
    @SuppressWarnings("unchecked")
    void heartbeatCarriesEveryGauge() {
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(alerts);
        order.verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), anyString(),
                context.capture());
        assertThat(new ArrayList<>(context.getValue().keySet())).containsExactlyElementsOf(OpsGaugeSnapshot.GAUGE_KEYS);
        assertThat(context.getValue())
                .containsEntry("plaid.items.billable", 3L)
                .containsEntry("inbox.failed", 2L)
                .containsEntry("inbox.oldest_age_sec", 1801L)
                .containsEntry("reconcile.ran_yesterday", false);
    }

    @Test
    @DisplayName("a quiet morning raises the digest and nothing else")
    void quietDayRaisesOnlyTheHeartbeat() {
        when(gauges.snapshot(any())).thenReturn(quiet());

        job.run();

        verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), anyString(), anyMap());
        verifyNoMoreInteractions(alerts);
    }

    @Test
    @DisplayName("every crossed threshold raises its own alert once, after the digest")
    void everyThresholdRaisesItsAlertOnce() {
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        InOrder order = inOrder(alerts);
        order.verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.INBOX_FAILED), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.INBOX_OLDEST_AGE), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.WEBHOOKS_NONE_24H), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.SEARCHING_24H), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.HOLDS_MANUAL_REVIEW_PRESENT), eq(OpsAlertService.GLOBAL_SUBJECT),
                anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.RECONCILE_LATE), eq(OpsHeartbeatJob.SUBJECT_NIGHTLY), anyString(), anyMap());
        verifyNoMoreInteractions(alerts);
    }

    @Test
    @DisplayName("an inbox exactly 1800 seconds old is not late; 1801 is")
    void inboxAgeAt1800IsNotAlerted() {
        when(gauges.snapshot(any())).thenReturn(snap(0, 1800, 3, 5, 0, 0, true));
        job.run();
        verify(alerts, never()).raise(eq(OpsAlerts.INBOX_OLDEST_AGE), anyString(), anyString(), anyMap());

        when(gauges.snapshot(any())).thenReturn(snap(0, 1801, 3, 5, 0, 0, true));
        job.run();
        verify(alerts, times(1)).raise(eq(OpsAlerts.INBOX_OLDEST_AGE), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("with the pipeline off, only the digest, inbox failures and review holds are raised")
    void disabledAutopilotSkipsPipelineAlerts() {
        when(props.isEnabled()).thenReturn(false);
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        InOrder order = inOrder(alerts);
        order.verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), anyString(), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.INBOX_FAILED), anyString(), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.HOLDS_MANUAL_REVIEW_PRESENT), anyString(), anyString(), anyMap());
        verifyNoMoreInteractions(alerts);
    }

    @Test
    @DisplayName("no reconcile marker for yesterday is a late reconcile")
    void reconcileLateWhenNoRanRow() {
        when(gauges.snapshot(any())).thenReturn(snap(0, 0, 3, 5, 0, 0, false));

        job.run();

        verify(alerts).raise(eq(OpsAlerts.RECONCILE_LATE), eq(OpsHeartbeatJob.SUBJECT_NIGHTLY), anyString(), anyMap());
    }

    @Test
    @DisplayName("a failed snapshot still raises the digest, says so, and releases the lock")
    @SuppressWarnings("unchecked")
    void snapshotFailureStillRaisesHeartbeatAndReleases() {
        when(gauges.snapshot(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        job.run();

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), eq("heartbeat snapshot=failed"),
                context.capture());
        assertThat(context.getValue()).containsExactly(Map.entry("snapshot", "failed"));
        verifyNoMoreInteractions(alerts);
        verify(locks).release(JobLocks.OPS_HEARTBEAT);
    }

    @Test
    @DisplayName("alert messages and context keys carry counts and fixed words only")
    @SuppressWarnings("unchecked")
    void messagesCarryCountsOnly() {
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts, times(7)).raise(anyString(), anyString(), message.capture(), context.capture());
        for (String text : message.getAllValues()) {
            assertThat(text).matches("[a-z0-9_.= ]+");
        }
        for (Map<String, Object> map : context.getAllValues()) {
            for (String key : map.keySet()) {
                assertThat(key).doesNotContain("amount", "date", "descriptor", "name", "token", "cursor", "email");
            }
        }
    }
}

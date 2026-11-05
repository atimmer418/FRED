package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.service.PlaidWebhookDispatcher.DispatchOutcome;
import com.investingapp.backend.service.PlaidWebhookProcessor.ReplayResult;
import com.investingapp.backend.support.RecordingTransactionManager;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlaidWebhookProcessorTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 3, 9, 0);
    private static final Clock CLOCK = Clock.fixed(START.atZone(EasternTime.ZONE).toInstant(), EasternTime.ZONE);
    private static final Long USER_ID = 42L;
    private static final String SYNC = "SYNC_UPDATES_AVAILABLE";

    private final Map<Long, PlaidWebhookEvent> rows = new LinkedHashMap<>();
    private final PlaidWebhookEventRepository repo = mock(PlaidWebhookEventRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PlaidItemStateService itemState = mock(PlaidItemStateService.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final JobLockService locks = mock(JobLockService.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(locks.tryAcquire(eq(JobLocks.PLAID_WEBHOOK_INBOX), any())).thenReturn(true);
        when(repo.claimReceivedIds(PlaidWebhookProcessor.BATCH_SIZE)).thenAnswer(i -> rows.values().stream()
                .filter(r -> r.getStatus() == PlaidWebhookEventStatus.RECEIVED).map(PlaidWebhookEvent::getId).toList());
        when(repo.markProcessing(any(), any())).thenAnswer(i -> {
            List<Long> ids = i.getArgument(0);
            ids.forEach(id -> rows.get(id).setStatus(PlaidWebhookEventStatus.PROCESSING));
            return ids.size();
        });
        when(repo.findAllById(any())).thenAnswer(i -> {
            List<PlaidWebhookEvent> found = new ArrayList<>();
            ((Iterable<Long>) i.getArgument(0)).forEach(id -> found.add(rows.get(id)));
            return found;
        });
        when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(rows.get(i.<Long>getArgument(0))));
        when(repo.save(any(PlaidWebhookEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(pipeline.run(any(), any(), any())).thenReturn(synced(SyncStatus.APPLIED));
    }

    private PlaidWebhookProcessor processor(SchedulerGate gate, PlatformTransactionManager tx, PlaidWebhookDispatcher dispatcher) {
        PlaidWebhookProcessor processor = new PlaidWebhookProcessor(repo, dispatcher, props, gate, locks, alerts, tx, Runnable::run);
        ReflectionTestUtils.setField(processor, "clock", CLOCK);
        return processor;
    }

    private PlaidWebhookProcessor processor() {
        return processor(new SchedulerGate(true), mock(PlatformTransactionManager.class), new PlaidWebhookDispatcher(pipeline, itemState));
    }

    private PlaidWebhookEvent add(long id, String item, String code, int attempts) {
        String type = code.equals(SYNC) || code.startsWith("RECURRING") ? "TRANSACTIONS" : "ITEM";
        PlaidWebhookEvent row = new PlaidWebhookEvent();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setPlaidItemId(item);
        row.setWebhookType(type);
        row.setWebhookCode(code);
        row.setStatus(PlaidWebhookEventStatus.RECEIVED);
        row.setAttempts((short) attempts);
        row.setJwtIat(1_700_000_000L + id);
        row.setReceivedAt(START.minusMinutes(60 - id));
        row.setPayloadJson("{\"webhook_type\":\"" + type + "\",\"webhook_code\":\"" + code + "\",\"item_id\":\"" + item + "\"}");
        rows.put(id, row);
        return row;
    }

    private static PipelineOutcome synced(SyncStatus status) {
        return new PipelineOutcome(new SyncOutcome(status, false, null, null), null, null, null);
    }

    @Test
    void identicalSyncBodiesBothEndProcessedAfterOneSync() {
        PlaidWebhookEvent first = add(1, "item-a", SYNC, 0);
        PlaidWebhookEvent second = add(2, "item-a", SYNC, 0);
        assertThat(first.getPayloadJson()).isEqualTo(second.getPayloadJson());
        assertThat(first.getJwtIat()).isNotEqualTo(second.getJwtIat());

        processor().drainInbox();

        verify(pipeline, times(1)).run(USER_ID, "item-a", SyncReason.WEBHOOK);
        assertThat(rows.values()).extracting(PlaidWebhookEvent::getStatus).containsOnly(PlaidWebhookEventStatus.PROCESSED);
        verify(locks).release(JobLocks.PLAID_WEBHOOK_INBOX);
    }

    @Test
    void coalescesSyncAndRecurringPerItem() {
        add(1, "item-a", SYNC, 0);
        add(2, "item-a", "RECURRING_TRANSACTIONS_UPDATE", 0);
        add(3, "item-b", SYNC, 0);
        add(4, "item-a", SYNC, 0);
        add(5, "item-a", "RECURRING_TRANSACTIONS_UPDATE", 0);
        when(pipeline.streamsChanged(any(), any())).thenReturn(new PipelineOutcome(null,
                new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null), null, null));

        processor().drainInbox();

        verify(pipeline, times(1)).run(USER_ID, "item-a", SyncReason.WEBHOOK);
        verify(pipeline, times(1)).run(USER_ID, "item-b", SyncReason.WEBHOOK);
        verify(pipeline, times(1)).streamsChanged(USER_ID, "item-a");
        assertThat(rows.values()).extracting(PlaidWebhookEvent::getStatus).containsOnly(PlaidWebhookEventStatus.PROCESSED);
    }

    @Test
    void thirdFailureMarksFailedAndAlerts() {
        add(1, "item-a", SYNC, 2);
        when(pipeline.run(USER_ID, "item-a", SyncReason.WEBHOOK)).thenReturn(synced(SyncStatus.RETRY_LATER));
        processor().drainInbox();
        PlaidWebhookEvent row = rows.get(1L);
        assertThat(row.getStatus()).isEqualTo(PlaidWebhookEventStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo((short) 3);
        assertThat(row.getProcessedAt()).isEqualTo(START);
        verify(alerts).raise(eq(OpsAlerts.INBOX_FAILED), eq("1"), anyString(),
                eq(Map.<String, Object>of("code", SYNC, "attempts", (short) 3)));
    }

    @Test
    void unstartedGroupsReturnToReceived() {
        add(1, "item-a", SYNC, 0);
        add(2, "item-b", SYNC, 0);
        PlaidWebhookProcessor processor = processor();
        when(pipeline.run(USER_ID, "item-a", SyncReason.WEBHOOK)).thenAnswer(i -> {
            ReflectionTestUtils.setField(processor, "clock", Clock.offset(CLOCK, PlaidWebhookProcessor.DISPATCH_BUDGET.plusSeconds(1)));
            return synced(SyncStatus.APPLIED);
        });

        processor.drainInbox();

        assertThat(rows.get(1L).getStatus()).isEqualTo(PlaidWebhookEventStatus.PROCESSED);
        PlaidWebhookEvent unstarted = rows.get(2L);
        assertThat(unstarted.getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
        assertThat(unstarted.getAttempts()).isEqualTo((short) 0);
        assertThat(unstarted.getProcessedAt()).isNull();
        verify(pipeline, never()).run(eq(USER_ID), eq("item-b"), any());
    }

    @Test
    void handlerRunsOutsideAnyTransaction() {
        add(1, "item-a", SYNC, 0);
        RecordingTransactionManager tx = new RecordingTransactionManager();
        PlaidWebhookDispatcher dispatcher = mock(PlaidWebhookDispatcher.class);
        AtomicBoolean insideTransaction = new AtomicBoolean(true);
        when(dispatcher.dispatch(any())).thenAnswer(i -> {
            insideTransaction.set(tx.open || TransactionSynchronizationManager.isActualTransactionActive());
            return DispatchOutcome.PROCESSED;
        });

        processor(new SchedulerGate(true), tx, dispatcher).drainInbox();

        verify(dispatcher).dispatch(any());
        assertThat(insideTransaction).as("Plaid work never holds a claim transaction open").isFalse();
        assertThat(tx.begins.get()).isPositive();
        assertThat(rows.get(1L).getStatus()).isEqualTo(PlaidWebhookEventStatus.PROCESSED);
    }

    @Test
    void retryReturnsRowToReceivedAndCountsTheAttempt() {
        add(1, "item-a", SYNC, 0);
        when(pipeline.run(USER_ID, "item-a", SyncReason.WEBHOOK)).thenReturn(synced(SyncStatus.RETRY_LATER));

        processor().drainInbox();

        PlaidWebhookEvent row = rows.get(1L);
        assertThat(row.getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getLastError()).isEqualTo("retry_requested");
        assertThat(row.getProcessedAt()).isNull();
        verifyNoInteractions(alerts);
    }

    @Test
    void replayResetsAFailedRowAndRefusesInFlightOrUnknown() {
        PlaidWebhookEvent failed = add(1, "item-a", SYNC, 3);
        failed.setStatus(PlaidWebhookEventStatus.FAILED);
        failed.setLastError("retry_requested");
        failed.setProcessedAt(START);
        add(2, "item-b", SYNC, 1).setStatus(PlaidWebhookEventStatus.PROCESSING);
        PlaidWebhookProcessor processor = processor();

        assertThat(processor.replay(1L)).isEqualTo(ReplayResult.REQUEUED);
        assertThat(failed.getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
        assertThat(failed.getAttempts()).isEqualTo((short) 0);
        assertThat(failed.getLastError()).isNull();
        assertThat(failed.getProcessedAt()).isNull();

        assertThat(processor.replay(2L)).isEqualTo(ReplayResult.IN_FLIGHT);
        assertThat(rows.get(2L).getStatus()).isEqualTo(PlaidWebhookEventStatus.PROCESSING);

        assertThat(processor.replay(99L)).isEqualTo(ReplayResult.NOT_FOUND);
    }

    @Test
    void honorsSchedulerEnabledFalse() {
        add(1, "item-a", SYNC, 0);

        processor(new SchedulerGate(false), mock(PlatformTransactionManager.class), new PlaidWebhookDispatcher(pipeline, itemState))
                .drainInbox();

        verifyNoInteractions(repo, locks, pipeline);
        assertThat(rows.get(1L).getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
    }

    @Test
    void disabledLeavesRowsReceivedAndCallsNothing() {
        add(1, "item-a", SYNC, 0);
        when(props.isEnabled()).thenReturn(false);

        processor().drainInbox();

        verifyNoInteractions(repo, locks, pipeline, itemState, alerts);
        assertThat(rows.get(1L).getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
    }

    @Test
    void lockHeldElsewhereClaimsNothing() {
        add(1, "item-a", SYNC, 0);
        when(locks.tryAcquire(eq(JobLocks.PLAID_WEBHOOK_INBOX), any())).thenReturn(false);

        processor().drainInbox();

        verifyNoInteractions(repo, pipeline);
        verify(locks, never()).release(any());
    }

    @Test
    void dispatchExceptionCountsAsARetry() {
        add(1, "item-a", SYNC, 0);
        when(pipeline.run(USER_ID, "item-a", SyncReason.WEBHOOK)).thenThrow(new IllegalStateException("boom"));

        processor().drainInbox();

        PlaidWebhookEvent row = rows.get(1L);
        assertThat(row.getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
        assertThat(row.getAttempts()).isEqualTo((short) 1);
        assertThat(row.getLastError()).isEqualTo("IllegalStateException");
    }

    @Test
    void staleItemRowEndsIgnored() {
        add(1, "item-old", SYNC, 0);
        when(pipeline.run(USER_ID, "item-old", SyncReason.WEBHOOK)).thenReturn(synced(SyncStatus.REFUSED_STALE_ITEM));

        processor().drainInbox();

        PlaidWebhookEvent row = rows.get(1L);
        assertThat(row.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(row.getAttempts()).isEqualTo((short) 0);
    }

    @Test
    void staleProcessingRowsAreReclaimedBeforeTheClaim() {
        add(1, "item-a", SYNC, 0);

        processor().drainInbox();

        InOrder order = inOrder(repo);
        order.verify(repo).reclaimStale(START.minus(PlaidWebhookProcessor.STALE_CLAIM_AGE));
        order.verify(repo).claimReceivedIds(PlaidWebhookProcessor.BATCH_SIZE);
        order.verify(repo).markProcessing(List.of(1L), START);
    }

    @Test
    void drainsFifteenSecondsAfterTheLastRunEnds() throws NoSuchMethodException {
        assertThat(PlaidWebhookProcessor.class.getMethod("drainInbox").getAnnotation(Scheduled.class).fixedDelay())
                .isEqualTo(15000L);
    }
}

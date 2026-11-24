# Task 14 appendix: full code for the compressed steps

This file completes `task-14-sync-pipeline-and-webhook-processor.md`; read both. The main file left out every `import` line and gave 26 test methods as arrange / act / assert table rows. This file gives the complete import block for every class the task creates (Steps 2, 3, 4, 5, 6, 8, 10, 11), every table row written out as a full test method (Steps 2, 4, 6, 10), the whole `PlaidWebhookEventRepository` file after the Step 7 edit, and the Verification commands the main file wrote as "same form for" (Verification). Nothing here changes behaviour. Where a section gives an import block, it goes between the `package` line and the first annotation or `class` line of the code the main file shows. Where a section gives methods, they go inside that class, after its last shown method and before its closing brace. `T/` is `backend/src/test/java/com/investingapp/backend/` and `.../` is `backend/src/main/java/com/investingapp/backend/`, as in the main file.

## Step 2: PaycheckSyncPipelineTest

### Import block

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.service.PaycheckEventService.ApplyResult;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
```

### The six table methods (add inside `PaycheckSyncPipelineTest`, after `itemErrorRoutedByClass`)

```java
    @Test
    void historyCompletionReadsStreamsThenEvaluatesFull() {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(applied(true, List.of(7L)));
        StreamsReadOutcome streams = new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null);
        when(recurringService.readStreams(USER_ID, ITEM)).thenReturn(streams);

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        InOrder order = inOrder(syncService, recurringService, detectionService);
        order.verify(syncService).syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);
        order.verify(recurringService).readStreams(USER_ID, ITEM);
        order.verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.FULL), any(LocalDate.class));
        verify(detectionService, never()).evaluateAndApply(any(), any(), any());
        assertThat(outcome.streams()).isSameAs(streams);
        assertThat(outcome.evaluation()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = SyncStatus.class, names = {"NOT_INITIALIZED", "REFUSED_CONSENT", "REFUSED_STALE_ITEM",
            "REFUSED_ITEM_STATUS", "RETRY_LATER", "FAILED"})
    void nonAppliedSyncStopsThePipeline(SyncStatus status) {
        User loginRequired = eligibleUser();
        loginRequired.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED); // ITEM_STATUS must not refuse at entry
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(loginRequired));
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.INIT)).thenReturn(new SyncOutcome(status, false, null, null));

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.INIT);

        assertThat(outcome.sync().status()).isEqualTo(status);
        assertThat(outcome.evaluation()).isNull();
        verifyNoInteractions(recurringService, itemStateService);
        verify(detectionService, never()).evaluateAndApply(any(), any(), any());
    }

    @Test
    void transientStreamsFailureAtHistoryCompletionStillEvaluatesFull() {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(applied(true, List.of()));
        when(recurringService.readStreams(USER_ID, ITEM)).thenReturn(new StreamsReadOutcome(StreamsReadStatus.ITEM_ERROR,
                0, 0, 0, error("INTERNAL_SERVER_ERROR", PlaidErrorClass.TRANSIENT)));

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.evaluation()).isNotNull();
        verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.FULL), any(LocalDate.class));
        verifyNoInteractions(itemStateService);
    }

    @Test
    void streamsChangedReadsThenEvaluatesStreamsChanged() {
        when(recurringService.readStreams(USER_ID, ITEM)).thenReturn(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 2, 1, 1, null));

        PipelineOutcome outcome = pipeline.streamsChanged(USER_ID, ITEM);

        InOrder order = inOrder(recurringService, detectionService);
        order.verify(recurringService).readStreams(USER_ID, ITEM);
        order.verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.STREAMS_CHANGED), any(LocalDate.class));
        verifyNoInteractions(syncService);
        assertThat(outcome.sync()).isNull();
    }

    @Test
    void missingUserIsStaleAndCallsNothing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.sync().status()).isEqualTo(SyncStatus.REFUSED_STALE_ITEM);
        verifyNoInteractions(syncService, recurringService, itemStateService);
    }

    @Test
    void refusesToRunInsideACallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> pipeline.run(USER_ID, ITEM, SyncReason.MANUAL)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> pipeline.streamsChanged(USER_ID, ITEM)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(syncService, recurringService);
    }
```

Count: the main file's 16 shown cases plus 1 + 6 + 1 + 1 + 1 + 1 = 27, as Step 3 expects.

## Step 3: PaycheckSyncPipeline import block

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.util.EasternTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
```

`PlaidErrorClass` is not imported: `routeItemError` names its constants only as `switch` case labels, which need no import.

## Step 4: PlaidWebhookDispatcherTest

### Import block

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.service.PlaidWebhookDispatcher.DispatchOutcome;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
```

### The ten table methods (add inside `PlaidWebhookDispatcherTest`, after `pipelineOutcomesMapToRowOutcomes`)

```java
    @Test
    void syncUpdatesAvailableRunsOneWebhookSync() {
        when(pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(sync(SyncStatus.APPLIED, null));

        assertThat(dispatcher.dispatch(row("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verify(pipeline).run(USER_ID, ITEM, SyncReason.WEBHOOK);
        verifyNoInteractions(itemState);
    }

    @Test
    void loginRepairedFromLoginRequiredThenSyncs() {
        when(itemState.onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)).thenReturn(ItemStateResult.APPLIED);
        when(pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK)).thenReturn(sync(SyncStatus.APPLIED, null));

        assertThat(dispatcher.dispatch(row("ITEM", "LOGIN_REPAIRED", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        InOrder order = inOrder(itemState, pipeline);
        order.verify(itemState).onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED);
        order.verify(pipeline).run(USER_ID, ITEM, SyncReason.WEBHOOK);
    }

    @Test
    void recurringUpdateRereadsStreams() {
        when(pipeline.streamsChanged(USER_ID, ITEM)).thenReturn(new PipelineOutcome(null,
                new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null), null, null));

        assertThat(dispatcher.dispatch(row("TRANSACTIONS", "RECURRING_TRANSACTIONS_UPDATE", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verify(pipeline).streamsChanged(USER_ID, ITEM);
        verify(pipeline, never()).run(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INITIAL_UPDATE", "HISTORICAL_UPDATE", "DEFAULT_UPDATE", "TRANSACTIONS_REMOVED"})
    void legacyTransactionsCodesAreProcessedWithoutAction(String code) {
        assertThat(dispatcher.dispatch(row("TRANSACTIONS", code, ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verifyNoInteractions(pipeline, itemState);
    }

    @ParameterizedTest
    @CsvSource({"ITEM,NEW_ACCOUNTS_AVAILABLE", "AUTH,AUTOMATICALLY_VERIFIED"})
    void anythingElseIsProcessedAndLogged(String type, String code) {
        assertThat(dispatcher.dispatch(row(type, code, ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verifyNoInteractions(pipeline, itemState);
    }

    @Test
    void malformedItemPayloadsFail() {
        assertThat(dispatcher.dispatch(row("ITEM", "ERROR", ",\"error\":{}"))).isEqualTo(DispatchOutcome.FAILED);
        assertThat(dispatcher.dispatch(row("ITEM", "USER_ACCOUNT_REVOKED", ""))).isEqualTo(DispatchOutcome.FAILED);

        verifyNoInteractions(pipeline, itemState);
    }

    @Test
    void loginRepairedFromAnyOtherStatusWritesNothingMore() {
        when(itemState.onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)).thenReturn(ItemStateResult.NO_OP);

        assertThat(dispatcher.dispatch(row("ITEM", "LOGIN_REPAIRED", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verifyNoInteractions(pipeline);
    }

    @Test
    void revocationsAndAckReachTheirHandlers() {
        when(itemState.onRevoked(USER_ID, ITEM, "USER_PERMISSION_REVOKED")).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onAccountRevoked(USER_ID, ITEM, "acc-9")).thenReturn(ItemStateResult.APPLIED);
        when(itemState.onWebhookAcked(USER_ID, ITEM)).thenReturn(ItemStateResult.APPLIED);

        assertThat(dispatcher.dispatch(row("ITEM", "USER_PERMISSION_REVOKED", ""))).isEqualTo(DispatchOutcome.PROCESSED);
        assertThat(dispatcher.dispatch(row("ITEM", "USER_ACCOUNT_REVOKED", ",\"account_id\":\"acc-9\"")))
                .isEqualTo(DispatchOutcome.PROCESSED);
        assertThat(dispatcher.dispatch(row("ITEM", "WEBHOOK_UPDATE_ACKNOWLEDGED", ""))).isEqualTo(DispatchOutcome.PROCESSED);

        verify(itemState, times(1)).onRevoked(USER_ID, ITEM, "USER_PERMISSION_REVOKED");
        verify(itemState, times(1)).onAccountRevoked(USER_ID, ITEM, "acc-9");
        verify(itemState, times(1)).onWebhookAcked(USER_ID, ITEM);
        verifyNoInteractions(pipeline);
    }

    @Test
    void staleItemEventIsIgnored() {
        when(itemState.onWebhookAcked(USER_ID, ITEM)).thenReturn(ItemStateResult.STALE_ITEM);

        assertThat(dispatcher.dispatch(row("ITEM", "WEBHOOK_UPDATE_ACKNOWLEDGED", ""))).isEqualTo(DispatchOutcome.IGNORED_STALE);
    }

    @Test
    void unparsableOrUnresolvedRowsCallNothing() {
        PlaidWebhookEvent unparsable = row("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "");
        unparsable.setPayloadJson("not json");
        PlaidWebhookEvent unresolved = row("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "");
        unresolved.setUserId(null);

        assertThat(dispatcher.dispatch(unparsable)).isEqualTo(DispatchOutcome.FAILED);
        assertThat(dispatcher.dispatch(unresolved)).isEqualTo(DispatchOutcome.IGNORED_STALE);

        verifyNoInteractions(pipeline, itemState);
    }
```

Count: the main file's 19 shown cases plus 1 + 1 + 1 + 4 + 2 + 1 + 1 + 1 + 1 + 1 = 33, as Step 5 expects.

## Step 5: PlaidWebhookDispatcher import block

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.util.EasternTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
```

`StreamsReadStatus` is not imported: `fromPipeline` names its constants only as `switch` case labels. The initialized `objectMapper` field is final with an initializer, so `@RequiredArgsConstructor` leaves it out and the constructor stays `(PaycheckSyncPipeline, PlaidItemStateService)`, the order the test passes.

## Step 6: PlaidWebhookClaimDatabaseTest

### Import block

```java
package com.investingapp.backend.service;

import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
```

### The second test (add inside `PlaidWebhookClaimDatabaseTest`, after `concurrentClaimersGetDisjointIds`)

Each repository call runs in its own `transactions.execute(...)`, so each commits before the JDBC reads that follow it. `processed_at` is checked for presence only (raw JDBC and Hibernate binding can differ by the session offset); `reclaimStale` binds through Hibernate the same way `markProcessing` did, so its comparison is consistent.

```java
    @Test
    void markProcessingStampsTheClaimAndReclaimStaleReturnsOnlyOldClaims() {
        LocalDateTime claimedAt = LocalDateTime.of(2026, 11, 3, 9, 0);

        Integer marked = transactions.execute(status -> repository.markProcessing(ours, claimedAt));

        assertThat(marked).isEqualTo(4);
        for (Long id : ours) {
            assertThat(statusOf(id)).isEqualTo("PROCESSING");
            assertThat(jdbcTemplate.queryForObject("SELECT processed_at IS NOT NULL FROM plaid_webhook_events WHERE id = ?",
                    Boolean.class, id)).isTrue();
        }

        transactions.execute(status -> repository.reclaimStale(claimedAt));
        for (Long id : ours) {
            assertThat(statusOf(id)).as("a claim stamped exactly at the cut-off is not stale").isEqualTo("PROCESSING");
        }

        transactions.execute(status -> repository.reclaimStale(claimedAt.plusMinutes(10)));
        for (Long id : ours) {
            assertThat(statusOf(id)).isEqualTo("RECEIVED");
        }
    }
```

Count: 2 tests, as Step 7 expects (`tests="2" skipped="0" failures="0"`).

## Step 7: PlaidWebhookEventRepository after the edit (whole file)

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEvent, Long> {

    Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);

    /** RECEIVED rows, oldest first, locked until the caller's transaction ends; rows another claimer holds are skipped (MySQL 8.0). */
    @Query(value = "SELECT id FROM plaid_webhook_events WHERE status = 'RECEIVED' ORDER BY received_at "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Long> claimReceivedIds(@Param("limit") int limit);

    /** processed_at doubles as the claim stamp while PROCESSING (V6 has no claim column). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE plaid_webhook_events SET status = 'PROCESSING', processed_at = :claimedAt WHERE id IN (:ids)",
            nativeQuery = true)
    int markProcessing(@Param("ids") List<Long> ids, @Param("claimedAt") LocalDateTime claimedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE plaid_webhook_events SET status = 'RECEIVED' WHERE status = 'PROCESSING' AND processed_at < :before",
            nativeQuery = true)
    int reclaimStale(@Param("before") LocalDateTime before);

    /** For the admin GET /webhooks?status=FAILED verb (T18). */
    List<PlaidWebhookEvent> findByStatusOrderByReceivedAtAsc(PlaidWebhookEventStatus status);

    /** Used by UserDeletionService (T11). A derived delete needs a read-write transaction. */
    @Transactional
    long deleteByUserId(Long userId);
}
```

The HEAD lines are kept as they are (`findByDedupeKey` at `:13`, then `deleteByUserId` with its javadoc and `@Transactional`); the new block sits between them, exactly where the main file says ("insert after `:13`").

## Step 8: SchedulingConfigTest (whole file)

```java
package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void plaidExecutorRunsFourItemsAtOnce() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().plaidExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
    }
}
```

## Step 10: PlaidWebhookProcessorTest

### Import block

```java
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
```

### The nine table methods (add inside `PlaidWebhookProcessorTest`, after `handlerRunsOutsideAnyTransaction`)

Stubs written inside a test override the `setUp` stub for the same arguments (Mockito uses the last matching stubbing); stubbing calls are not counted by `verifyNoInteractions`.

```java
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
```

Count: the main file's 5 shown tests plus these 9 = 14, as Step 11 expects.

## Step 11: PlaidWebhookProcessor import block

The main file names only the "extra" imports (`Qualifier`, `Scheduled`, `PlatformTransactionManager`, `TransactionDefinition`); this is the whole block the shown code needs.

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.service.PlaidWebhookDispatcher.DispatchOutcome;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
```

`JobLocks` and `JobLockService` are in the same package (`service`) and need no import.

## Verification: the commands the table abbreviated

### "same form for" the three pre-existing test classes

Before Step 2, record each class's count from its XML (the "pre-task counts"); after Step 11, run the same three lines again and compare. `failures` must be 0 both times.

```
cd backend
./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml
./gradlew test --tests 'com.investingapp.backend.AlertSitesRoutedTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.AlertSitesRoutedTest.xml
./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookInboxServiceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PlaidWebhookInboxServiceTest.xml
```

Expected: the same `tests` value as the pre-task run for each class, `failures="0"`.

### The whole suite on a scratch MySQL (the Step 7 block with the two lines swapped)

`read -rs` prompts for the MySQL root password without echoing it; never put it on a command line or in a file.

```
cd backend
read -rs MYSQL_PWD && export MYSQL_PWD
mysql -h 127.0.0.1 -u root -e "CREATE DATABASE fred_t14_scratch"
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t14_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$MYSQL_PWD" \
PLAID_WEBHOOK_URL=https://ci.invalid/api/plaid/webhook PLAID_REDIRECT_URI=https://ci.invalid/plaid/oauth PLAID_LINK_CUSTOMIZATION=default \
./gradlew cleanTest test --console=plain
awk -F'"' '/<testsuite /{t+=$4; s+=$6; f+=$8} END {print "tests=" t " skipped=" s " failures=" f}' build/test-results/test/*.xml
mysql -h 127.0.0.1 -u root -e "DROP DATABASE fred_t14_scratch"
unset MYSQL_PWD
```

Expected: `skipped=0 failures=0`, and `tests` equal to the post-T13 total plus 77 (27 + 33 + 2 + 1 + 14). Read the awk line, never the console: a run that reached no database still says `BUILD SUCCESSFUL` with skips.

## Disagreements between the main file's tables and its code

None found. Every table row was checked against the shown production code: each arrange produces the asserted outcome through the shown branches, and the stated counts (27, 33, 2, 1, 14, total 77) match the shown cases plus the table rows. One note that is not a disagreement: the design entry calls the reclaim test `staleProcessingRowsAreReclaimed`; the main file's name, `staleProcessingRowsAreReclaimedBeforeTheClaim`, is the one used here.

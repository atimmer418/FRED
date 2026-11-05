### Task 14: sync-pipeline-and-webhook-processor

Full code for the steps this file gives as tables or patterns is in task-14-sync-pipeline-and-webhook-processor-appendix.md; read both.

The one pipeline every Phase 1 entry point uses to turn "this Item has news" into detection, plus the inbox drainer (spec 2.5 step 5, 2.6, 2.8). `PaycheckSyncPipeline.run` refuses on the flag and R8 with no Plaid call, runs `syncItem` (T08), routes an Item error to T13 by `PlaidErrorClass` (T06), then reads streams (T10) and evaluates FULL (T11) at history completion, else applies new deposits INCREMENTAL under one user lock (T12). `PlaidWebhookDispatcher` maps a row per the 2.8 table; `PlaidWebhookProcessor.drainInbox` claims with `SKIP LOCKED`, coalesces per Item, runs four Items at once, retries three times, then FAILED plus alert. T15, T16, T18, T19 call this pipeline; nothing else calls `syncItem` or `readStreams`.

Facts relied on:

- Phase 1 is `enabled=true, shadow=true, pulls.enabled=false`; `enabled=false` stores rows and runs nothing else (spec 12.1, Decision 28).
- R8, Decision 29: REJECTED, opted-out and token-less users get `REFUSED_EXCLUDED` at entry (no billed subscription); a broken Item is refused by `syncItem`.
- No Plaid HTTP inside a transaction: the pipeline throws inside one; the drainer claims and records in REQUIRES_NEW and dispatches between.
- No write to `InvestmentSchedule.status` (PENDING_BANK / ACTIVE / PAUSED_BY_USER), `mode` or `hold_reason`; no analytics event; `isReadyForInvestment` untouched. Holds are written only in T13 (R2).
- INFO logs carry FRED user and inbox row ids, codes, states, counts; never a Plaid id, descriptor, payer, amount, date, cursor or token. The alert subject is the inbox row id; context `{code, attempts}`.
- The dedupe key includes the JWT `iat`, so retries and the identical daily `SYNC_UPDATES_AVAILABLE` are separate rows; handlers are idempotent.

`.../` = `backend/src/main/java/com/investingapp/backend/`; `T/` = `backend/src/test/java/com/investingapp/backend/`. Lines are HEAD 137998c; T04 and T05 edit `SchedulingConfig.java` and `JobLocks.java` first, so those anchors are quoted text.

**Two mechanical omissions (75,000-character limit).** (1) Java blocks omit `import` lines: import each type from its package under Consumes (nested types as `Outer.Nested`); Spring's `Transactional` is `org.springframework.transaction.annotation`; loggers are `org.slf4j`; `lombok.RequiredArgsConstructor`; Jackson `JsonNode`/`ObjectMapper` (`databind`), `JsonProcessingException` (`core`); tests statically import `org.assertj.core.api.Assertions.assertThat` / `assertThatThrownBy` and `org.mockito.Mockito.*`. (2) Test methods that only vary a shown pattern are exact arrange / act / assert rows; write each as its own method in the same class, in the shown style.

**Files:**
- Create: `.../service/PaycheckSyncPipeline.java`, `.../service/PlaidWebhookDispatcher.java`, `.../service/PlaidWebhookProcessor.java`
- Modify: `.../repository/PlaidWebhookEventRepository.java` (after `:13`, `Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);`); `.../service/JobLocks.java` (after `public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";`, HEAD `:9`); `.../config/SchedulingConfig.java` (`executor.setCorePoolSize(2);` and `executor.setMaxPoolSize(4);` in `plaidExecutor()`, HEAD `:34-35`); `.../model/PlaidWebhookEventStatus.java:3`
- Test: create `T/service/PaycheckSyncPipelineTest.java`, `T/service/PlaidWebhookDispatcherTest.java`, `T/service/PlaidWebhookProcessorTest.java`, `T/service/PlaidWebhookClaimDatabaseTest.java` (`@RequiresCiDatabase`), `T/config/SchedulingConfigTest.java` (T03's invariance walk already covers `service/Paycheck*`, `service/Plaid*`).

No property is added, so `application-local.properties` needs no edit (the drainer runs locally only with T03's `paycheck.autopilot.enabled=true` there).

**Interfaces:**
- Consumes (`com.investingapp.backend.` omitted; a nested type lives in the one file its task creates):
  - T03 `config.PaycheckAutopilotProperties.isEnabled()`; `static Optional<ExclusionReason> service.DetectionEligibility.exclusion(User, PaycheckAutopilotProperties)` with `ExclusionReason {DISABLED, ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN, ITEM_STATUS}`. T04/T05 `ops.OpsAlertService.raise(String, String, String, Map<String, Object>)`, `ops.OpsAlerts.INBOX_FAILED`.
  - T06 `plaid.PlaidErrorClass` (TRANSIENT, RATE_LIMIT, CONSENT, ITEM_BROKEN, NOT_SUPPORTED, SYNC_LIMIT, MUTATION, PERMANENT), `classify(String, int)`; `plaid.PlaidApiError(errorType, errorCode, errorCodeReason, requestId, int httpStatus, errorClass)`; test `support.RecordingTransactionManager` (public `boolean open`).
  - T07 `service.PaycheckEventService.ApplyResult(List<Long> newPostedInflowEventIds, List<Long>, List<Long>, int)`. T08 `service.PlaidTransactionsSyncService.SyncReason`, `.SyncStatus`, `.SyncOutcome(status, boolean historyJustCompleted, ApplyResult applied, PlaidApiError error)`, `syncItem(Long, String, SyncReason)`. T10 `service.PlaidRecurringService.StreamsReadStatus`, `.StreamsReadOutcome(status, int, int, int, PlaidApiError error)`, `readStreams(Long, String)`.
  - T11/T12 `paycheck.EvaluationTrigger`, `paycheck.EvaluationOutcome(DetectionState, Long, boolean, boolean, Optional<ExclusionReason>)`, `@Transactional service.PaycheckDetectionService.evaluate(Long, EvaluationTrigger, LocalDate)` and `evaluateAndApply(Long, List<Long>, LocalDate)`; `model.DetectionState`.
  - T13 `service.PlaidItemStateService.ItemStateResult {APPLIED, STALE_ITEM, NO_OP}`, `.RepairPath`; handlers `(Long userId, String itemId, ...)`: `onLoginRequired(.., String errorCode)`, `onRevoked(.., String source)`, `onAccountRevoked(.., String accountId)`, `onConsentRequired`, `onSyncLimit`, `onNotSupported`, `onLoginRepaired(.., RepairPath)`, `onPendingDisconnect(.., OffsetDateTime)`, `onWebhookAcked`.
  - Phase 0 `model.{User, PlaidItemStatus, PlaidWebhookEvent, PlaidWebhookEventStatus}`, `config.SchedulerGate(boolean)`, `service.JobLockService`, `util.EasternTime`, `repository.{UserRepository, PlaidWebhookEventRepository}`, test `support.RequiresCiDatabase`.
- Produces: see the end.

- [ ] **Step 1: Confirm the upstream contracts are in the tree**

```
cd backend/src
grep -rn -e 'enum SyncReason' -e 'record SyncOutcome' -e 'record StreamsReadOutcome' -e 'EvaluationOutcome evaluate' -e 'enum ItemStateResult' -e 'public ItemStateResult on' -e 'Optional<ExclusionReason> exclusion' -e 'INBOX_FAILED' main/java/com/investingapp/backend/service main/java/com/investingapp/backend/ops
grep -n 'public .*boolean open' test/java/com/investingapp/backend/support/RecordingTransactionManager.java
```

Expected: hits for every pattern, `evaluate(` and `evaluateAndApply(`, and T13's eleven handlers, each `(Long userId, String itemId, ...`. Otherwise stop and report the upstream task; do not adapt this one.

- [ ] **Step 2: Failing pipeline test**

Create `T/service/PaycheckSyncPipelineTest.java`:

```java
package com.investingapp.backend.service;

class PaycheckSyncPipelineTest {

    private static final Long USER_ID = 42L;
    private static final String ITEM = "item-current";

    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PlaidTransactionsSyncService syncService = mock(PlaidTransactionsSyncService.class);
    private final PlaidRecurringService recurringService = mock(PlaidRecurringService.class);
    private final PaycheckDetectionService detectionService = mock(PaycheckDetectionService.class);
    private final PlaidItemStateService itemStateService = mock(PlaidItemStateService.class);
    private final PaycheckSyncPipeline pipeline = new PaycheckSyncPipeline(props, userRepository, syncService,
            recurringService, detectionService, itemStateService);

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(eligibleUser()));
        when(detectionService.evaluate(any(), any(), any())).thenReturn(evaluation());
        when(detectionService.evaluateAndApply(any(), any(), any())).thenReturn(evaluation());
    }

    private static User eligibleUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidItemId(ITEM);
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPaycheckAutopilotOptOut(false);
        return user;
    }

    private static SyncOutcome applied(boolean historyJustCompleted, List<Long> newIds) {
        return new SyncOutcome(SyncStatus.APPLIED, historyJustCompleted, new ApplyResult(newIds, List.of(), List.of(), 0), null);
    }

    private static PlaidApiError error(String code, PlaidErrorClass errorClass) {
        return new PlaidApiError("ITEM_ERROR", code, null, "req-1", 400, errorClass);
    }

    private static EvaluationOutcome evaluation() {
        return new EvaluationOutcome(DetectionState.SEARCHING, null, false, false, Optional.empty());
    }

    @Test
    void disabledPipelineRefusesBeforeAnyCall() {
        when(props.isEnabled()).thenReturn(false);
        assertThat(pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK).sync().status()).isEqualTo(SyncStatus.REFUSED_DISABLED);
        assertThat(pipeline.streamsChanged(USER_ID, ITEM).sync().status()).isEqualTo(SyncStatus.REFUSED_DISABLED);
        verifyNoInteractions(userRepository, syncService, recurringService, itemStateService);
        verify(detectionService, never()).evaluate(any(), any(), any());
    }

    static Stream<Arguments> excludedUsers() {
        User rejected = eligibleUser();
        rejected.setAccountStatus("REJECTED");
        User optedOut = eligibleUser();
        optedOut.setPaycheckAutopilotOptOut(true);
        User noToken = eligibleUser();
        noToken.setPlaidAccessToken(null);
        return Stream.of(Arguments.of(ExclusionReason.ACCOUNT_REJECTED, rejected),
                Arguments.of(ExclusionReason.OPTED_OUT, optedOut), Arguments.of(ExclusionReason.NO_TOKEN, noToken));
    }

    // syncItem and readStreams are the only PlaidGateway callers here, so no call to them is no Plaid call.
    @ParameterizedTest(name = "{0}")
    @MethodSource("excludedUsers")
    void excludedUserNeverReachesPlaid(ExclusionReason reason, User user) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThat(DetectionEligibility.exclusion(user, props)).as("fixture triggers this reason").contains(reason);
        for (SyncReason syncReason : SyncReason.values()) {
            assertThat(pipeline.run(USER_ID, ITEM, syncReason).sync().status()).isEqualTo(SyncStatus.REFUSED_EXCLUDED);
        }
        assertThat(pipeline.streamsChanged(USER_ID, ITEM).sync().status()).isEqualTo(SyncStatus.REFUSED_EXCLUDED);
        verifyNoInteractions(syncService, recurringService, itemStateService);
        verify(detectionService, never()).evaluate(any(), any(), any());
    }

    @Test
    void incrementalPathAppliesDepositsInsideOneTransaction() throws NoSuchMethodException {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.RECONCILE)).thenReturn(applied(false, List.of(11L, 12L)));

        assertThat(pipeline.run(USER_ID, ITEM, SyncReason.RECONCILE).evaluation()).isNotNull();

        verify(detectionService, times(1)).evaluateAndApply(eq(USER_ID), eq(List.of(11L, 12L)), any(LocalDate.class));
        verify(detectionService, never()).evaluate(any(), any(), any());
        verifyNoInteractions(recurringService, itemStateService);
        assertThat(PaycheckDetectionService.class.getMethod("evaluateAndApply", Long.class, List.class, LocalDate.class)
                .getAnnotation(Transactional.class)).as("deposits and evaluation commit together (T12)").isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ADDITIONAL_CONSENT_REQUIRED,CONSENT,consent", "ACCESS_NOT_GRANTED,CONSENT,consent",
            "ITEM_LOGIN_REQUIRED,ITEM_BROKEN,login", "NO_ACCOUNTS,ITEM_BROKEN,login", "ITEM_NOT_FOUND,ITEM_BROKEN,revoked",
            "USER_PERMISSION_REVOKED,ITEM_BROKEN,revoked", "PRODUCTS_NOT_SUPPORTED,NOT_SUPPORTED,not_supported",
            "TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED,SYNC_LIMIT,sync_limit", "INSTITUTION_DOWN,TRANSIENT,none",
            "RATE_LIMIT_EXCEEDED,RATE_LIMIT,none", "INSTITUTION_NO_LONGER_SUPPORTED,PERMANENT,none"})
    void itemErrorRoutedByClass(String code, PlaidErrorClass errorClass, String handler) {
        when(syncService.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK))
                .thenReturn(new SyncOutcome(SyncStatus.ITEM_ERROR, false, null, error(code, errorClass)));
        when(itemStateService.onConsentRequired(any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onLoginRequired(any(), any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onRevoked(any(), any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onNotSupported(any(), any())).thenReturn(ItemStateResult.APPLIED);
        when(itemStateService.onSyncLimit(any(), any())).thenReturn(ItemStateResult.APPLIED);

        PipelineOutcome outcome = pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK);

        switch (handler) {
            case "consent" -> verify(itemStateService).onConsentRequired(USER_ID, ITEM);
            case "login" -> verify(itemStateService).onLoginRequired(USER_ID, ITEM, code);
            case "revoked" -> verify(itemStateService).onRevoked(USER_ID, ITEM, code);
            case "not_supported" -> verify(itemStateService).onNotSupported(USER_ID, ITEM);
            case "sync_limit" -> verify(itemStateService).onSyncLimit(USER_ID, ITEM);
            default -> { }
        }
        verifyNoMoreInteractions(itemStateService);
        verifyNoInteractions(recurringService);
        assertThat(outcome.itemState()).isEqualTo("none".equals(handler) ? null : ItemStateResult.APPLIED);
        assertThat(outcome.evaluation()).isNull();
    }
}
```

Add these methods to the class:

| Method | Arrange (beyond `setUp`) | Act | Assert |
|---|---|---|---|
| `historyCompletionReadsStreamsThenEvaluatesFull()` | `syncItem(USER_ID, ITEM, SyncReason.WEBHOOK)` returns `applied(true, List.of(7L))`; `readStreams(USER_ID, ITEM)` returns `streams = new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null)` | `run(USER_ID, ITEM, SyncReason.WEBHOOK)` | `inOrder(syncService, recurringService, detectionService)`: `syncItem`, `readStreams(USER_ID, ITEM)`, `evaluate(eq(USER_ID), eq(EvaluationTrigger.FULL), any(LocalDate.class))`; `evaluateAndApply` never; `streams()` is `streams`; `evaluation()` not null |
| `@ParameterizedTest @EnumSource(value = SyncStatus.class, names = {"NOT_INITIALIZED", "REFUSED_CONSENT", "REFUSED_STALE_ITEM", "REFUSED_ITEM_STATUS", "RETRY_LATER", "FAILED"}) nonAppliedSyncStopsThePipeline(SyncStatus status)` | `findById(USER_ID)` returns `eligibleUser()` after `setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED)` (ITEM_STATUS must not refuse at entry); `syncItem(USER_ID, ITEM, SyncReason.INIT)` returns `new SyncOutcome(status, false, null, null)` | `pipeline.run(USER_ID, ITEM, SyncReason.INIT)` | `sync().status()` is `status`; `evaluation()` null; `verifyNoInteractions(recurringService, itemStateService)`; `verify(detectionService, never()).evaluateAndApply(any(), any(), any())` |
| `transientStreamsFailureAtHistoryCompletionStillEvaluatesFull()` | `syncItem(USER_ID, ITEM, SyncReason.WEBHOOK)` returns `applied(true, List.of())`; `readStreams(USER_ID, ITEM)` returns `new StreamsReadOutcome(StreamsReadStatus.ITEM_ERROR, 0, 0, 0, error("INTERNAL_SERVER_ERROR", PlaidErrorClass.TRANSIENT))` | `run(USER_ID, ITEM, SyncReason.WEBHOOK)` | `evaluation()` not null; `verify(detectionService).evaluate(eq(USER_ID), eq(EvaluationTrigger.FULL), any(LocalDate.class))`; `verifyNoInteractions(itemStateService)` |
| `streamsChangedReadsThenEvaluatesStreamsChanged()` | `readStreams(USER_ID, ITEM)` returns `new StreamsReadOutcome(StreamsReadStatus.APPLIED, 2, 1, 1, null)` | `pipeline.streamsChanged(USER_ID, ITEM)` | `inOrder(recurringService, detectionService)`: `readStreams(USER_ID, ITEM)` then `evaluate(eq(USER_ID), eq(EvaluationTrigger.STREAMS_CHANGED), any(LocalDate.class))`; `verifyNoInteractions(syncService)`; `sync()` null |
| `missingUserIsStaleAndCallsNothing()` | `findById(USER_ID)` returns `Optional.empty()` | `run(USER_ID, ITEM, SyncReason.WEBHOOK)` | status `REFUSED_STALE_ITEM`; `verifyNoInteractions(syncService, recurringService, itemStateService)` |
| `refusesToRunInsideACallerTransaction()` | `TransactionSynchronizationManager.setActualTransactionActive(true)`, reset to `false` in a `finally` | `run(USER_ID, ITEM, SyncReason.MANUAL)` and `streamsChanged(USER_ID, ITEM)` | both `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class)`; `verifyNoInteractions(syncService, recurringService)` |

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckSyncPipelineTest' --console=plain`. Expected: compilation fails with `cannot find symbol` / `class PaycheckSyncPipeline`.

- [ ] **Step 3: Create PaycheckSyncPipeline**

Create `.../service/PaycheckSyncPipeline.java` (`@RequiredArgsConstructor` builds the constructor in field order, the order the test passes):

```java
package com.investingapp.backend.service;

@Service
@RequiredArgsConstructor
public class PaycheckSyncPipeline {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckSyncPipeline.class);

    /** ITEM_BROKEN codes that end the Item instead of waiting for a login repair (spec 5.7, second row). */
    static final Set<String> REVOKING_CODES = Set.of("ITEM_NOT_FOUND", "USER_PERMISSION_REVOKED");

    /** Any part is null when the pipeline stopped before reaching it; an entry refusal is always in sync. */
    public record PipelineOutcome(SyncOutcome sync, StreamsReadOutcome streams, EvaluationOutcome evaluation,
                                  ItemStateResult itemState) {
    }

    private final PaycheckAutopilotProperties props;
    private final UserRepository userRepository;
    private final PlaidTransactionsSyncService syncService;
    private final PlaidRecurringService recurringService;
    private final PaycheckDetectionService detectionService;
    private final PlaidItemStateService itemStateService;

    public PipelineOutcome run(Long userId, String itemId, SyncReason reason) {
        requireNoCallerTransaction("run");
        Optional<SyncStatus> refused = entryRefusal(userId);
        if (refused.isPresent()) {
            return refusedOutcome(userId, "run", refused.get());
        }
        SyncOutcome sync = syncService.syncItem(userId, itemId, reason);
        logger.info("paycheck.pipeline.sync user_id={} reason={} status={} history_completed={}",
                userId, reason, sync.status(), sync.historyJustCompleted());
        if (sync.status() == SyncStatus.ITEM_ERROR) {
            return new PipelineOutcome(sync, null, null, routeItemError(userId, itemId, sync.error()));
        }
        if (sync.status() != SyncStatus.APPLIED) {
            return new PipelineOutcome(sync, null, null, null);
        }
        LocalDate todayET = EasternTime.today();
        if (sync.historyJustCompleted()) {
            StreamsReadOutcome streams = recurringService.readStreams(userId, itemId);
            logStreams(userId, streams);
            ItemStateResult itemState = streams.status() == StreamsReadStatus.ITEM_ERROR
                    ? routeItemError(userId, itemId, streams.error()) : null;
            if (itemState != null || streams.status() == StreamsReadStatus.REFUSED_STALE_ITEM
                    || streams.status() == StreamsReadStatus.REFUSED_ITEM_STATUS) {
                return new PipelineOutcome(sync, streams, null, itemState);
            }
            // Streams are catalog only (spec 2.6): a transient read failure must not cost the one FULL evaluation.
            return new PipelineOutcome(sync, streams, detectionService.evaluate(userId, EvaluationTrigger.FULL, todayET), null);
        }
        List<Long> newDeposits = sync.applied() == null ? List.of() : sync.applied().newPostedInflowEventIds();
        return new PipelineOutcome(sync, null, detectionService.evaluateAndApply(userId, newDeposits, todayET), null);
    }

    /** RECURRING_TRANSACTIONS_UPDATE and the reconcile's 24-hour re-read. */
    public PipelineOutcome streamsChanged(Long userId, String itemId) {
        requireNoCallerTransaction("streamsChanged");
        Optional<SyncStatus> refused = entryRefusal(userId);
        if (refused.isPresent()) {
            return refusedOutcome(userId, "streamsChanged", refused.get());
        }
        StreamsReadOutcome streams = recurringService.readStreams(userId, itemId);
        logStreams(userId, streams);
        return switch (streams.status()) {
            case APPLIED -> new PipelineOutcome(null, streams,
                    detectionService.evaluate(userId, EvaluationTrigger.STREAMS_CHANGED, EasternTime.today()), null);
            case ITEM_ERROR -> new PipelineOutcome(null, streams, null, routeItemError(userId, itemId, streams.error()));
            default -> new PipelineOutcome(null, streams, null, null);
        };
    }

    /** Spec 2.1 and 5.7 by class; null for TRANSIENT, RATE_LIMIT, MUTATION, PERMANENT. Also used by the ERROR webhook and T15. */
    public ItemStateResult routeItemError(Long userId, String itemId, PlaidApiError error) {
        if (error == null || error.errorClass() == null) {
            return null;
        }
        String code = error.errorCode();
        ItemStateResult result = switch (error.errorClass()) {
            case CONSENT -> itemStateService.onConsentRequired(userId, itemId);
            case ITEM_BROKEN -> REVOKING_CODES.contains(code)
                    ? itemStateService.onRevoked(userId, itemId, code)
                    : itemStateService.onLoginRequired(userId, itemId, code);
            case NOT_SUPPORTED -> itemStateService.onNotSupported(userId, itemId);
            case SYNC_LIMIT -> itemStateService.onSyncLimit(userId, itemId);
            case TRANSIENT, RATE_LIMIT, MUTATION, PERMANENT -> null;
        };
        logger.info("paycheck.pipeline.item_error user_id={} class={} code={} routed={}", userId, error.errorClass(), code, result);
        return result;
    }

    private Optional<SyncStatus> entryRefusal(Long userId) {
        if (!props.isEnabled()) {
            return Optional.of(SyncStatus.REFUSED_DISABLED);
        }
        Optional<User> user = userId == null ? Optional.empty() : userRepository.findById(userId);
        if (user.isEmpty()) {
            logger.info("plaid.stale_item_event user_id={} cause=user_missing", userId);
            return Optional.of(SyncStatus.REFUSED_STALE_ITEM);
        }
        Optional<ExclusionReason> exclusion = DetectionEligibility.exclusion(user.get(), props);
        if (exclusion.isEmpty()) {
            return Optional.empty();
        }
        return switch (exclusion.get()) {
            case ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN -> Optional.of(SyncStatus.REFUSED_EXCLUDED);
            case DISABLED -> Optional.of(SyncStatus.REFUSED_DISABLED);
            case ITEM_STATUS -> Optional.empty(); // syncItem and readStreams refuse it with no Plaid call
        };
    }

    private static PipelineOutcome refusedOutcome(Long userId, String entry, SyncStatus status) {
        logger.info("paycheck.pipeline.refused user_id={} entry={} status={}", userId, entry, status);
        return new PipelineOutcome(new SyncOutcome(status, false, null, null), null, null, null);
    }

    private static void logStreams(Long userId, StreamsReadOutcome streams) {
        logger.info("paycheck.pipeline.streams user_id={} status={} inflow={} matched={} seeded={}",
                userId, streams.status(), streams.inflowStreams(), streams.matched(), streams.seeded());
    }

    private static void requireNoCallerTransaction(String method) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PaycheckSyncPipeline." + method + " calls Plaid and must not run inside a transaction");
        }
    }
}
```

Run the Step 2 command. Expected: `BUILD SUCCESSFUL`, 27 tests pass (shown 1 + 3 + 1 + 11, table 1 + 6 + 1 + 1 + 1 + 1).

- [ ] **Step 4: Failing dispatcher test**

Create `T/service/PlaidWebhookDispatcherTest.java`:

```java
package com.investingapp.backend.service;

class PlaidWebhookDispatcherTest {

    private static final Long USER_ID = 42L;
    private static final String ITEM = "item-current";
    private static final LocalDateTime RECEIVED_AT = LocalDateTime.of(2026, 11, 3, 9, 0);

    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PlaidItemStateService itemState = mock(PlaidItemStateService.class);
    private final PlaidWebhookDispatcher dispatcher = new PlaidWebhookDispatcher(pipeline, itemState);

    private static PlaidWebhookEvent row(String type, String code, String extraJson) {
        PlaidWebhookEvent row = new PlaidWebhookEvent();
        row.setId(7L);
        row.setUserId(USER_ID);
        row.setPlaidItemId(ITEM);
        row.setWebhookType(type);
        row.setWebhookCode(code);
        row.setReceivedAt(RECEIVED_AT);
        row.setPayloadJson("{\"webhook_type\":\"" + type + "\",\"webhook_code\":\"" + code + "\",\"item_id\":\"" + ITEM + "\"" + extraJson + "}");
        return row;
    }

    private static PipelineOutcome sync(SyncStatus status, PlaidErrorClass errorClass) {
        PlaidApiError error = errorClass == null ? null : new PlaidApiError("API_ERROR", "CODE", null, "req-1", 400, errorClass);
        return new PipelineOutcome(new SyncOutcome(status, false, null, error), null, null, null);
    }

    // Uses T06's real classify, so it also pins that ERROR is classified on error.error_code (Decision 24).
    @ParameterizedTest
    @CsvSource({"ITEM_LOGIN_REQUIRED,ITEM_BROKEN", "ACCESS_NOT_GRANTED,CONSENT", "ITEM_NOT_FOUND,ITEM_BROKEN", "INSTITUTION_DOWN,TRANSIENT"})
    void itemErrorWebhookIsClassifiedOnItsCode(String code, PlaidErrorClass expectedClass) {
        when(pipeline.routeItemError(eq(USER_ID), eq(ITEM), any())).thenReturn(ItemStateResult.APPLIED);
        String error = ",\"error\":{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"" + code + "\",\"error_code_reason\":null}";

        assertThat(dispatcher.dispatch(row("ITEM", "ERROR", error))).isEqualTo(DispatchOutcome.PROCESSED);

        ArgumentCaptor<PlaidApiError> routed = ArgumentCaptor.forClass(PlaidApiError.class);
        verify(pipeline).routeItemError(eq(USER_ID), eq(ITEM), routed.capture());
        assertThat(routed.getValue().errorCode()).isEqualTo(code);
        assertThat(routed.getValue().errorClass()).isEqualTo(expectedClass);
        verify(pipeline, never()).run(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PENDING_DISCONNECT|,\"disconnect_time\":\"2026-11-10T14:00:00Z\"|2026-11-10T14:00:00Z",
            "PENDING_EXPIRATION|,\"consent_expiration_time\":\"2026-11-12T00:00:00Z\"|2026-11-12T00:00:00Z",
            "PENDING_DISCONNECT|,\"reason\":\"INSTITUTION_MIGRATION\"|"})
    void pendingDisconnectPassesPlaidsTimeElseTheSevenDayLead(String code, String extraJson, String expected) {
        when(itemState.onPendingDisconnect(any(), any(), any())).thenReturn(ItemStateResult.APPLIED);
        OffsetDateTime at = expected == null
                ? RECEIVED_AT.plusDays(7).atZone(EasternTime.ZONE).toOffsetDateTime() : OffsetDateTime.parse(expected);

        assertThat(dispatcher.dispatch(row("ITEM", code, extraJson))).isEqualTo(DispatchOutcome.PROCESSED);
        verify(itemState).onPendingDisconnect(USER_ID, ITEM, at);
    }

    @ParameterizedTest
    @CsvSource({"APPLIED,,PROCESSED", "NOT_INITIALIZED,,PROCESSED", "REFUSED_CONSENT,,PROCESSED",
            "REFUSED_STALE_ITEM,,IGNORED_STALE", "REFUSED_ITEM_STATUS,,PROCESSED", "REFUSED_DISABLED,,PROCESSED",
            "REFUSED_EXCLUDED,,PROCESSED", "RETRY_LATER,,RETRY", "FAILED,,RETRY", "ITEM_ERROR,TRANSIENT,RETRY",
            "ITEM_ERROR,RATE_LIMIT,RETRY", "ITEM_ERROR,CONSENT,PROCESSED"})
    void pipelineOutcomesMapToRowOutcomes(SyncStatus status, PlaidErrorClass errorClass, DispatchOutcome expected) {
        assertThat(PlaidWebhookDispatcher.fromPipeline(sync(status, errorClass))).isEqualTo(expected);
    }
}
```

Add these methods ("dispatch (a, b, c)" is `dispatcher.dispatch(row(a, b, c))`):

| Method | Arrange | Act | Assert |
|---|---|---|---|
| `syncUpdatesAvailableRunsOneWebhookSync()` | `pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK)` returns `sync(SyncStatus.APPLIED, null)` | dispatch `("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", "")` | `PROCESSED`; `verify(pipeline).run(USER_ID, ITEM, SyncReason.WEBHOOK)`; `verifyNoInteractions(itemState)` |
| `loginRepairedFromLoginRequiredThenSyncs()` | `itemState.onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)` returns `APPLIED`; `pipeline.run(USER_ID, ITEM, SyncReason.WEBHOOK)` returns `sync(SyncStatus.APPLIED, null)` | dispatch `("ITEM", "LOGIN_REPAIRED", "")` | `PROCESSED`; `inOrder(itemState, pipeline)`: `onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)` then `run(USER_ID, ITEM, SyncReason.WEBHOOK)` |
| `recurringUpdateRereadsStreams()` | `pipeline.streamsChanged(USER_ID, ITEM)` returns `new PipelineOutcome(null, new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 1, 0, null), null, null)` | dispatch `("TRANSACTIONS", "RECURRING_TRANSACTIONS_UPDATE", "")` | `PROCESSED`; `verify(pipeline).streamsChanged(USER_ID, ITEM)`; `verify(pipeline, never()).run(any(), any(), any())` |
| `@ParameterizedTest @ValueSource(strings = {"INITIAL_UPDATE", "HISTORICAL_UPDATE", "DEFAULT_UPDATE", "TRANSACTIONS_REMOVED"}) legacyTransactionsCodesAreProcessedWithoutAction(String code)` | none | dispatch `("TRANSACTIONS", code, "")` | `PROCESSED`; `verifyNoInteractions(pipeline, itemState)` |
| `@ParameterizedTest @CsvSource({"ITEM,NEW_ACCOUNTS_AVAILABLE", "AUTH,AUTOMATICALLY_VERIFIED"}) anythingElseIsProcessedAndLogged(String type, String code)` | none | dispatch `(type, code, "")` | `PROCESSED`; `verifyNoInteractions(pipeline, itemState)` |
| `malformedItemPayloadsFail()` | none | dispatch `("ITEM", "ERROR", ",\"error\":{}")` and `("ITEM", "USER_ACCOUNT_REVOKED", "")` | both `FAILED`; `verifyNoInteractions(pipeline, itemState)` |
| `loginRepairedFromAnyOtherStatusWritesNothingMore()` | `itemState.onLoginRepaired(USER_ID, ITEM, RepairPath.LOGIN_REPAIRED)` returns `NO_OP` | dispatch `("ITEM", "LOGIN_REPAIRED", "")` | `PROCESSED`; `verifyNoInteractions(pipeline)` |
| `revocationsAndAckReachTheirHandlers()` | `onRevoked(USER_ID, ITEM, "USER_PERMISSION_REVOKED")`, `onAccountRevoked(USER_ID, ITEM, "acc-9")`, `onWebhookAcked(USER_ID, ITEM)` return `APPLIED` | dispatch `("ITEM", "USER_PERMISSION_REVOKED", "")`, `("ITEM", "USER_ACCOUNT_REVOKED", ",\"account_id\":\"acc-9\"")`, `("ITEM", "WEBHOOK_UPDATE_ACKNOWLEDGED", "")` | each `PROCESSED`; each handler verified once with those arguments; `verifyNoInteractions(pipeline)` |
| `staleItemEventIsIgnored()` | `onWebhookAcked(USER_ID, ITEM)` returns `STALE_ITEM` | dispatch `("ITEM", "WEBHOOK_UPDATE_ACKNOWLEDGED", "")` | `IGNORED_STALE` |
| `unparsableOrUnresolvedRowsCallNothing()` | one SYNC row after `setPayloadJson("not json")`, another after `setUserId(null)` | dispatch both | `FAILED`, then `IGNORED_STALE`; `verifyNoInteractions(pipeline, itemState)` |

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookDispatcherTest' --console=plain`. Expected: compilation fails with `cannot find symbol` / `class PlaidWebhookDispatcher`.

- [ ] **Step 5: Create PlaidWebhookDispatcher**

Create `.../service/PlaidWebhookDispatcher.java`. ITEM rows go straight to `PlaidItemStateService` (state, not Plaid calls; each handler applies the item guard); payloads are read as `JsonNode` strings, never SDK enums.

```java
package com.investingapp.backend.service;

@Service
@RequiredArgsConstructor
public class PlaidWebhookDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookDispatcher.class);

    public enum DispatchOutcome { PROCESSED, IGNORED_STALE, RETRY, FAILED }

    /** Legacy TRANSACTIONS codes Plaid still sends; the cursor sync makes them redundant (spec 2.8). */
    static final Set<String> LEGACY_TRANSACTIONS_CODES =
            Set.of("INITIAL_UPDATE", "HISTORICAL_UPDATE", "DEFAULT_UPDATE", "TRANSACTIONS_REMOVED");

    /** Plaid sends PENDING_DISCONNECT seven days ahead (spec 2.8); used only when the payload names no time. */
    static final Duration PENDING_DISCONNECT_LEAD = Duration.ofDays(7);

    private final PaycheckSyncPipeline pipeline;
    private final PlaidItemStateService itemStateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DispatchOutcome dispatch(PlaidWebhookEvent row) {
        Long userId = row.getUserId();
        String itemId = row.getPlaidItemId();
        if (userId == null || itemId == null) {
            logger.info("plaid.stale_item_event id={} cause=unresolved_row", row.getId());
            return DispatchOutcome.IGNORED_STALE;
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(row.getPayloadJson());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            logger.warn("plaid.webhook.unparsable id={}", row.getId());
            return DispatchOutcome.FAILED;
        }
        String type = row.getWebhookType();
        String code = row.getWebhookCode();
        DispatchOutcome outcome = switch (type) {
            case "TRANSACTIONS" -> transactions(row, code, userId, itemId);
            case "ITEM" -> item(row, code, payload, userId, itemId);
            default -> noAction(row, "unhandled_type");
        };
        logger.info("plaid.webhook.dispatched id={} user_id={} type={} code={} outcome={}", row.getId(), userId, type, code, outcome);
        return outcome;
    }

    private DispatchOutcome transactions(PlaidWebhookEvent row, String code, Long userId, String itemId) {
        if ("SYNC_UPDATES_AVAILABLE".equals(code)) {
            return fromPipeline(pipeline.run(userId, itemId, SyncReason.WEBHOOK));
        }
        if ("RECURRING_TRANSACTIONS_UPDATE".equals(code)) {
            return fromPipeline(pipeline.streamsChanged(userId, itemId));
        }
        return noAction(row, LEGACY_TRANSACTIONS_CODES.contains(code) ? "legacy_code" : "unhandled_code");
    }

    private DispatchOutcome item(PlaidWebhookEvent row, String code, JsonNode payload, Long userId, String itemId) {
        return switch (code) {
            case "ERROR" -> itemError(row, payload, userId, itemId);
            case "LOGIN_REPAIRED" -> loginRepaired(userId, itemId);
            case "PENDING_DISCONNECT", "PENDING_EXPIRATION" ->
                    fromItemState(itemStateService.onPendingDisconnect(userId, itemId, disconnectAt(row, payload)));
            case "USER_PERMISSION_REVOKED" -> fromItemState(itemStateService.onRevoked(userId, itemId, code));
            case "USER_ACCOUNT_REVOKED" -> accountRevoked(row, payload, userId, itemId);
            case "WEBHOOK_UPDATE_ACKNOWLEDGED" -> fromItemState(itemStateService.onWebhookAcked(userId, itemId));
            default -> noAction(row, "unhandled_code");
        };
    }

    // ITEM_LOGIN_REQUIRED is an error_code inside ERROR (spec 5.7), classified like an API error.
    private DispatchOutcome itemError(PlaidWebhookEvent row, JsonNode payload, Long userId, String itemId) {
        JsonNode error = payload.path("error");
        String errorCode = text(error, "error_code");
        if (errorCode == null) {
            logger.warn("plaid.webhook.error_without_code id={}", row.getId());
            return DispatchOutcome.FAILED;
        }
        PlaidErrorClass errorClass = PlaidErrorClass.classify(errorCode, 0); // a webhook has no HTTP status; the code decides
        if (!"ITEM_LOGIN_REQUIRED".equals(errorCode)) {
            logger.warn("plaid.webhook.item_error id={} user_id={} code={} class={}", row.getId(), userId, errorCode, errorClass);
        }
        PlaidApiError apiError = new PlaidApiError(text(error, "error_type"), errorCode, text(error, "error_code_reason"), null, 0, errorClass);
        return fromItemState(pipeline.routeItemError(userId, itemId, apiError));
    }

    // Spec 5.7: only from LOGIN_REQUIRED (else NO_OP), then sync and INCREMENTAL.
    private DispatchOutcome loginRepaired(Long userId, String itemId) {
        ItemStateResult repaired = itemStateService.onLoginRepaired(userId, itemId, RepairPath.LOGIN_REPAIRED);
        return repaired == ItemStateResult.APPLIED
                ? fromPipeline(pipeline.run(userId, itemId, SyncReason.WEBHOOK)) : fromItemState(repaired);
    }

    private DispatchOutcome accountRevoked(PlaidWebhookEvent row, JsonNode payload, Long userId, String itemId) {
        String accountId = text(payload, "account_id");
        if (accountId == null) {
            logger.warn("plaid.webhook.account_revoked_without_account id={}", row.getId());
            return DispatchOutcome.FAILED;
        }
        return fromItemState(itemStateService.onAccountRevoked(userId, itemId, accountId));
    }

    private OffsetDateTime disconnectAt(PlaidWebhookEvent row, JsonNode payload) {
        for (String field : List.of("disconnect_time", "consent_expiration_time")) {
            String value = text(payload, field);
            if (value != null) {
                try {
                    return OffsetDateTime.parse(value);
                } catch (DateTimeParseException e) {
                    logger.warn("plaid.webhook.unparsable_time id={} field={}", row.getId(), field);
                }
            }
        }
        return row.getReceivedAt().plus(PENDING_DISCONNECT_LEAD).atZone(EasternTime.ZONE).toOffsetDateTime();
    }

    /** RETRY only for what a later attempt can fix: RETRY_LATER, FAILED, and TRANSIENT or RATE_LIMIT errors. */
    static DispatchOutcome fromPipeline(PipelineOutcome outcome) {
        if (outcome.itemState() == ItemStateResult.STALE_ITEM) {
            return DispatchOutcome.IGNORED_STALE;
        }
        SyncOutcome sync = outcome.sync();
        if (sync != null && sync.status() != SyncStatus.APPLIED) {
            return switch (sync.status()) {
                case REFUSED_STALE_ITEM -> DispatchOutcome.IGNORED_STALE;
                case RETRY_LATER, FAILED -> DispatchOutcome.RETRY;
                case ITEM_ERROR -> retryable(sync.error()) ? DispatchOutcome.RETRY : DispatchOutcome.PROCESSED;
                default -> DispatchOutcome.PROCESSED;
            };
        }
        StreamsReadOutcome streams = outcome.streams();
        if (sync == null && streams != null) {
            return switch (streams.status()) {
                case REFUSED_STALE_ITEM -> DispatchOutcome.IGNORED_STALE;
                case FAILED -> DispatchOutcome.RETRY;
                case ITEM_ERROR -> retryable(streams.error()) ? DispatchOutcome.RETRY : DispatchOutcome.PROCESSED;
                default -> DispatchOutcome.PROCESSED;
            };
        }
        return DispatchOutcome.PROCESSED;
    }

    private static boolean retryable(PlaidApiError error) {
        return error != null && (error.errorClass() == PlaidErrorClass.TRANSIENT || error.errorClass() == PlaidErrorClass.RATE_LIMIT);
    }

    private static DispatchOutcome fromItemState(ItemStateResult result) {
        return result == ItemStateResult.STALE_ITEM ? DispatchOutcome.IGNORED_STALE : DispatchOutcome.PROCESSED;
    }

    private static DispatchOutcome noAction(PlaidWebhookEvent row, String why) {
        logger.info("plaid.webhook.no_action id={} code={} why={}", row.getId(), row.getWebhookCode(), why);
        return DispatchOutcome.PROCESSED;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
```

Run the Step 4 command. Expected: `BUILD SUCCESSFUL`, 33 tests pass (shown 4 + 3 + 12, table 1 + 1 + 1 + 4 + 2 + 1 + 1 + 1 + 1 + 1).

- [ ] **Step 6: Failing database test for the claim primitives**

Create `T/service/PlaidWebhookClaimDatabaseTest.java`. SKIP LOCKED only shows while claimer A holds its transaction open on a latch as claimer B claims; a sequential pair would pass without it. Not `@Transactional`; under ci the drainer never runs.

```java
package com.investingapp.backend.service;

@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class PlaidWebhookClaimDatabaseTest {

    @Autowired
    private PlaidWebhookEventRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactions;
    private final List<Long> ours = new ArrayList<>();

    // Four RECEIVED rows dated 2000-01-01 (a string literal: no driver zone conversion), so every claim sees them first.
    @BeforeEach
    void insertFourReceivedRows() {
        transactions = new TransactionTemplate(transactionManager);
        String run = UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < 4; i++) {
            String key = "t14claim" + run + i;
            jdbcTemplate.update("INSERT INTO plaid_webhook_events (dedupe_key, webhook_type, webhook_code, plaid_item_id, user_id, "
                            + "environment, payload_json, status, attempts, jwt_iat, received_at) "
                            + "VALUES (?, 'TRANSACTIONS', 'SYNC_UPDATES_AVAILABLE', ?, NULL, 'sandbox', '{}', 'RECEIVED', 0, ?, ?)",
                    key, "item-" + run, 1_700_000_000L + i, "2000-01-01 00:00:0" + i);
            ours.add(jdbcTemplate.queryForObject("SELECT id FROM plaid_webhook_events WHERE dedupe_key = ?", Long.class, key));
        }
    }

    @AfterEach
    void deleteOurRows() {
        ours.forEach(id -> jdbcTemplate.update("DELETE FROM plaid_webhook_events WHERE id = ?", id));
    }

    @Test
    void concurrentClaimersGetDisjointIds() throws Exception {
        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> a = pool.submit(() -> transactions.execute(status -> {
                List<Long> ids = repository.claimReceivedIds(2);
                aClaimed.countDown();
                awaitQuietly(aMayCommit);
                return ids;
            }));
            assertThat(aClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            // Without SKIP LOCKED, B blocks on A's locks and this get() times out.
            Future<List<Long>> b = pool.submit(() -> transactions.execute(status -> repository.claimReceivedIds(50)));
            List<Long> bIds = b.get(10, TimeUnit.SECONDS);
            aMayCommit.countDown();
            List<Long> aIds = a.get(10, TimeUnit.SECONDS);

            assertThat(aIds).containsExactlyElementsOf(ours.subList(0, 2));
            assertThat(bIds).doesNotContainAnyElementsOf(aIds).containsAll(ours.subList(2, 4));
        } finally {
            aMayCommit.countDown();
            pool.shutdownNow();
        }
    }

    private String statusOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM plaid_webhook_events WHERE id = ?", String.class, id);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("claimer B never finished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

Add `markProcessingStampsTheClaimAndReclaimStaleReturnsOnlyOldClaims()`: with `claimedAt = LocalDateTime.of(2026, 11, 3, 9, 0)` and each repository call in its own `transactions.execute(status -> ...)`, `markProcessing(ours, claimedAt)` returns 4 and every id in `ours` has `statusOf(id)` `"PROCESSING"` and `SELECT processed_at IS NOT NULL FROM plaid_webhook_events WHERE id = ?` (`Boolean.class`) true; `reclaimStale(claimedAt)` leaves all four PROCESSING; `reclaimStale(claimedAt.plusMinutes(10))` makes all four `"RECEIVED"`. (Presence only: raw JDBC and Hibernate binding can differ by the session offset.)

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookClaimDatabaseTest' --console=plain`. Expected: compilation fails with `cannot find symbol` / `method claimReceivedIds(int)`.

- [ ] **Step 7: Claim primitives on the repository, then the database test on a scratch MySQL**

In `.../repository/PlaidWebhookEventRepository.java` add imports for `PlaidWebhookEventStatus`, Spring Data `Modifying`, `Query`, `Param`, `LocalDateTime`, `List`; insert after `:13`:

```java

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
```

Run it against a scratch schema. `read -rs` prompts for the MySQL root password without echoing it; never put it on a command line or in a file:

```
cd backend
read -rs MYSQL_PWD && export MYSQL_PWD
mysql -h 127.0.0.1 -u root -e "CREATE DATABASE fred_t14_scratch"
SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t14_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$MYSQL_PWD" \
PLAID_WEBHOOK_URL=https://ci.invalid/api/plaid/webhook PLAID_REDIRECT_URI=https://ci.invalid/plaid/oauth PLAID_LINK_CUSTOMIZATION=default \
./gradlew cleanTest test --tests 'com.investingapp.backend.service.PlaidWebhookClaimDatabaseTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PlaidWebhookClaimDatabaseTest.xml
mysql -h 127.0.0.1 -u root -e "DROP DATABASE fred_t14_scratch"
unset MYSQL_PWD
```

Expected: `tests="2" skipped="0" failures="0"`. Read the XML, never the console (a skipped run also says `BUILD SUCCESSFUL`); `skipped="2"` means no database was reached.

- [ ] **Step 8: Failing pool-size test**

Create `T/config/SchedulingConfigTest.java` (`ThreadPoolTaskExecutor` is `org.springframework.scheduling.concurrent`):

```java
package com.investingapp.backend.config;

class SchedulingConfigTest {

    @Test
    void plaidExecutorRunsFourItemsAtOnce() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().plaidExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.SchedulingConfigTest' --console=plain`. Expected: 1 test fails with `expected: 4` / `but was: 2`.

- [ ] **Step 9: Pool size, job lock name, status javadoc**

`.../config/SchedulingConfig.java`, in `plaidExecutor()`, replace `executor.setCorePoolSize(2);` and the following `executor.setMaxPoolSize(4);` with:

```java
        // Core = max: threads past core start only on a full queue, so core 2 capped the drainer at two Items.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
```

`.../service/JobLocks.java`, after `public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";` add `    public static final String PLAID_WEBHOOK_INBOX = "plaid.webhook-inbox";`.

`.../model/PlaidWebhookEventStatus.java:3`: replace `/** plaid_webhook_events.status (spec 3.6). Phase 0 writes RECEIVED and IGNORED only. */` with `/** plaid_webhook_events.status (spec 3.6). The inbox writes RECEIVED or IGNORED; PlaidWebhookProcessor claims RECEIVED as PROCESSING, then writes PROCESSED, IGNORED (stale item), FAILED, or RECEIVED again for a retry. */`.

Run the Step 8 command. Expected: 1 test passes.

- [ ] **Step 10: Failing processor test**

Create `T/service/PlaidWebhookProcessorTest.java` (in-memory inbox behind a mocked repository, real dispatcher over a mocked pipeline, synchronous executor, fixed Eastern clock):

```java
package com.investingapp.backend.service;

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
}
```

Add these methods (each starts with `add(1, "item-a", SYNC, 0)` unless stated; "drain" is `processor().drainInbox()`):

| Method | Arrange | Act | Assert |
|---|---|---|---|
| `retryReturnsRowToReceivedAndCountsTheAttempt()` | `pipeline.run(USER_ID, "item-a", SyncReason.WEBHOOK)` returns `synced(SyncStatus.RETRY_LATER)` | drain | row 1 RECEIVED, attempts `(short) 1`, lastError `"retry_requested"`, processedAt null; `verifyNoInteractions(alerts)` |
| `replayResetsAFailedRowAndRefusesInFlightOrUnknown()` | row 1 is `add(1, "item-a", SYNC, 3)` then `setStatus(FAILED)`, `setLastError("retry_requested")`, `setProcessedAt(START)`; `add(2, "item-b", SYNC, 1).setStatus(PROCESSING)` | `processor().replay(1L)`, `replay(2L)`, `replay(99L)` | `REQUEUED` with row 1 RECEIVED, attempts 0, lastError and processedAt null; `IN_FLIGHT` with row 2 still PROCESSING; `NOT_FOUND` |
| `honorsSchedulerEnabledFalse()` | none extra | `processor(new SchedulerGate(false), mock(PlatformTransactionManager.class), new PlaidWebhookDispatcher(pipeline, itemState)).drainInbox()` | `verifyNoInteractions(repo, locks, pipeline)`; row 1 RECEIVED |
| `disabledLeavesRowsReceivedAndCallsNothing()` | `when(props.isEnabled()).thenReturn(false)` | drain | `verifyNoInteractions(repo, locks, pipeline, itemState, alerts)`; row 1 RECEIVED |
| `lockHeldElsewhereClaimsNothing()` | `when(locks.tryAcquire(eq(JobLocks.PLAID_WEBHOOK_INBOX), any())).thenReturn(false)` | drain | `verifyNoInteractions(repo, pipeline)`; `verify(locks, never()).release(any())` |
| `dispatchExceptionCountsAsARetry()` | `pipeline.run(USER_ID, "item-a", SyncReason.WEBHOOK)` throws `new IllegalStateException("boom")` | drain | row 1 RECEIVED, attempts `(short) 1`, lastError `"IllegalStateException"` |
| `staleItemRowEndsIgnored()` | row is `add(1, "item-old", SYNC, 0)`; `pipeline.run(USER_ID, "item-old", SyncReason.WEBHOOK)` returns `synced(SyncStatus.REFUSED_STALE_ITEM)` | drain | row 1 IGNORED, attempts `(short) 0` |
| `staleProcessingRowsAreReclaimedBeforeTheClaim()` | none extra | drain | `inOrder(repo)` verifies `reclaimStale(START.minus(PlaidWebhookProcessor.STALE_CLAIM_AGE))`, `claimReceivedIds(PlaidWebhookProcessor.BATCH_SIZE)`, `markProcessing(List.of(1L), START)` |
| `drainsFifteenSecondsAfterTheLastRunEnds()` | no row | `PlaidWebhookProcessor.class.getMethod("drainInbox").getAnnotation(Scheduled.class).fixedDelay()` | `15000L` |

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookProcessorTest' --console=plain`. Expected: compilation fails with `cannot find symbol` / `class PlaidWebhookProcessor`.

- [ ] **Step 11: Create PlaidWebhookProcessor**

Create `.../service/PlaidWebhookProcessor.java` (extra imports: `Qualifier`, `Scheduled`, `PlatformTransactionManager`, `TransactionDefinition`):

```java
package com.investingapp.backend.service;

@Service
public class PlaidWebhookProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookProcessor.class);

    static final int BATCH_SIZE = 50;
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_CONCURRENT_ITEMS = 4;
    static final int LAST_ERROR_MAX = 512;
    static final Duration DISPATCH_BUDGET = Duration.ofSeconds(150);
    static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    static final Duration STALE_CLAIM_AGE = Duration.ofMinutes(10);
    static final Duration PERMIT_WAIT = Duration.ofSeconds(90);
    static final Set<String> COALESCED = Set.of("TRANSACTIONS:SYNC_UPDATES_AVAILABLE", "TRANSACTIONS:RECURRING_TRANSACTIONS_UPDATE");

    public enum ReplayResult { REQUEUED, NOT_FOUND, IN_FLIGHT }

    private final PlaidWebhookEventRepository eventRepository;
    private final PlaidWebhookDispatcher dispatcher;
    private final PaycheckAutopilotProperties props;
    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final OpsAlertService opsAlertService;
    private final TransactionTemplate requiresNew;
    private final Executor plaidExecutor;
    private Clock clock = Clock.system(EasternTime.ZONE); // tests replace it with ReflectionTestUtils.setField

    public PlaidWebhookProcessor(PlaidWebhookEventRepository eventRepository, PlaidWebhookDispatcher dispatcher,
                                 PaycheckAutopilotProperties props, SchedulerGate schedulerGate, JobLockService jobLockService,
                                 OpsAlertService opsAlertService, PlatformTransactionManager transactionManager,
                                 @Qualifier("plaidExecutor") Executor plaidExecutor) {
        this.eventRepository = eventRepository;
        this.dispatcher = dispatcher;
        this.props = props;
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.opsAlertService = opsAlertService;
        this.plaidExecutor = plaidExecutor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 15000)
    public void drainInbox() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!props.isEnabled()) {
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.PLAID_WEBHOOK_INBOX, LOCK_LEASE)) {
            return;
        }
        try {
            LocalDateTime startET = LocalDateTime.now(clock);
            Integer reclaimed = requiresNew.execute(status -> eventRepository.reclaimStale(startET.minus(STALE_CLAIM_AGE)));
            if (reclaimed != null && reclaimed > 0) {
                logger.warn("plaid.webhook.reclaimed rows={}", reclaimed);
            }
            List<PlaidWebhookEvent> claimed = claim(startET);
            if (!claimed.isEmpty()) {
                int unstarted = dispatchGroups(groupByItem(claimed), startET.plus(DISPATCH_BUDGET));
                logger.info("plaid.webhook.drain claimed={} unstarted={}", claimed.size(), unstarted);
            }
        } finally {
            jobLockService.release(JobLocks.PLAID_WEBHOOK_INBOX);
        }
    }

    /** Ops verb (T18): back to RECEIVED with attempts 0. A PROCESSING row is mid-dispatch and is left alone. */
    public ReplayResult replay(Long eventId) {
        ReplayResult result = requiresNew.execute(status -> eventRepository.findById(eventId).map(row -> {
            if (row.getStatus() == PlaidWebhookEventStatus.PROCESSING) {
                return ReplayResult.IN_FLIGHT;
            }
            row.setStatus(PlaidWebhookEventStatus.RECEIVED);
            row.setAttempts((short) 0);
            row.setLastError(null);
            row.setProcessedAt(null);
            eventRepository.save(row);
            return ReplayResult.REQUEUED;
        }).orElse(ReplayResult.NOT_FOUND));
        logger.info("plaid.webhook.replay id={} result={}", eventId, result);
        return result;
    }

    private List<PlaidWebhookEvent> claim(LocalDateTime claimedAt) {
        List<PlaidWebhookEvent> rows = requiresNew.execute(status -> {
            List<Long> ids = eventRepository.claimReceivedIds(BATCH_SIZE);
            if (ids.isEmpty()) {
                return List.<PlaidWebhookEvent>of();
            }
            eventRepository.markProcessing(ids, claimedAt);
            return eventRepository.findAllById(ids);
        });
        List<PlaidWebhookEvent> sorted = new ArrayList<>(rows == null ? List.of() : rows);
        sorted.sort(Comparator.comparing(PlaidWebhookEvent::getReceivedAt).thenComparing(PlaidWebhookEvent::getId));
        return sorted;
    }

    private static Collection<List<PlaidWebhookEvent>> groupByItem(List<PlaidWebhookEvent> rows) {
        Map<String, List<PlaidWebhookEvent>> groups = new LinkedHashMap<>();
        for (PlaidWebhookEvent row : rows) {
            String key = row.getPlaidItemId() != null ? row.getPlaidItemId() : "row-" + row.getId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return groups.values();
    }

    /** Returns how many rows went back to RECEIVED unstarted. */
    private int dispatchGroups(Collection<List<PlaidWebhookEvent>> groups, LocalDateTime deadline) {
        Semaphore permits = new Semaphore(MAX_CONCURRENT_ITEMS);
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();
        List<Long> unstarted = new ArrayList<>();
        for (List<PlaidWebhookEvent> group : groups) {
            if (LocalDateTime.now(clock).isAfter(deadline) || !acquire(permits)) {
                group.forEach(row -> unstarted.add(row.getId()));
                continue;
            }
            try {
                inFlight.add(CompletableFuture.runAsync(() -> {
                    try {
                        processGroup(group);
                    } finally {
                        permits.release();
                    }
                }, plaidExecutor));
            } catch (RejectedExecutionException e) {
                permits.release();
                logger.warn("plaid.webhook.executor_full rows={}", group.size());
                group.forEach(row -> unstarted.add(row.getId()));
            }
        }
        try {
            CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            logger.warn("plaid.webhook.group_crashed cause={}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        }
        if (!unstarted.isEmpty()) {
            returnToReceived(unstarted);
        }
        return unstarted.size();
    }

    private void processGroup(List<PlaidWebhookEvent> group) {
        Map<String, DispatchOutcome> coalesced = new HashMap<>();
        for (PlaidWebhookEvent row : group) {
            try {
                String key = row.getWebhookType() + ":" + row.getWebhookCode();
                DispatchOutcome outcome = coalesced.get(key); // a coalesced row reuses its Item's first outcome
                String cause = null;
                if (outcome == null) {
                    try {
                        outcome = dispatcher.dispatch(row);
                    } catch (RuntimeException e) {
                        outcome = DispatchOutcome.RETRY;
                        cause = e.getClass().getSimpleName();
                        logger.warn("plaid.webhook.dispatch_threw id={} cause={}", row.getId(), cause);
                    }
                    if (COALESCED.contains(key)) {
                        coalesced.put(key, outcome);
                    }
                }
                finish(row, outcome, cause);
            } catch (RuntimeException e) {
                // The row stays PROCESSING; the next drain reclaims it after STALE_CLAIM_AGE.
                logger.warn("plaid.webhook.finish_failed id={} cause={}", row.getId(), e.getClass().getSimpleName());
            }
        }
    }

    private void finish(PlaidWebhookEvent claimed, DispatchOutcome outcome, String cause) {
        LocalDateTime now = LocalDateTime.now(clock);
        Short failedAttempts = requiresNew.execute(status -> eventRepository.findById(claimed.getId()).map(row -> {
            switch (outcome) {
                case PROCESSED -> close(row, PlaidWebhookEventStatus.PROCESSED, now);
                case IGNORED_STALE -> close(row, PlaidWebhookEventStatus.IGNORED, now);
                case RETRY, FAILED -> {
                    row.setAttempts((short) (row.getAttempts() + 1));
                    String detail = cause != null ? cause : outcome == DispatchOutcome.RETRY ? "retry_requested" : "dispatch_failed";
                    row.setLastError(detail.length() <= LAST_ERROR_MAX ? detail : detail.substring(0, LAST_ERROR_MAX));
                    if (outcome == DispatchOutcome.FAILED || row.getAttempts() >= MAX_ATTEMPTS) {
                        close(row, PlaidWebhookEventStatus.FAILED, now);
                    } else {
                        row.setStatus(PlaidWebhookEventStatus.RECEIVED);
                        row.setProcessedAt(null);
                    }
                }
            }
            eventRepository.save(row);
            return row.getStatus() == PlaidWebhookEventStatus.FAILED ? row.getAttempts() : null;
        }).orElse(null));
        if (failedAttempts != null) {
            logger.error("alert.plaid.webhook.inbox_failed id={} code={} attempts={}", claimed.getId(), claimed.getWebhookCode(), failedAttempts);
            opsAlertService.raise(OpsAlerts.INBOX_FAILED, String.valueOf(claimed.getId()),
                    "Plaid webhook inbox row failed after " + failedAttempts + " attempts",
                    Map.of("code", claimed.getWebhookCode(), "attempts", failedAttempts));
        }
    }

    private static void close(PlaidWebhookEvent row, PlaidWebhookEventStatus status, LocalDateTime now) {
        row.setStatus(status);
        row.setProcessedAt(now);
    }

    private void returnToReceived(List<Long> ids) {
        requiresNew.executeWithoutResult(status -> eventRepository.findAllById(ids).forEach(row -> {
            row.setStatus(PlaidWebhookEventStatus.RECEIVED);
            row.setProcessedAt(null);
            eventRepository.save(row);
        }));
    }

    private static boolean acquire(Semaphore permits) {
        try {
            return permits.tryAcquire(PERMIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
```

T04's `AlertSitesRoutedTest` accepts the alert site (`raise(` on the next line, no `amount`/`error`-named argument); T03's invariance regex does not match `setStatus(PlaidWebhookEventStatus.`.

Run the Step 10 command. Expected: `BUILD SUCCESSFUL`, 14 tests pass (5 shown, 9 from the table).

- [ ] **Step 12: Run everything this task touches**

Run every command in the Verification section, in order, and compare with its expected column. Then `git diff HEAD -- backend/src | perl -CS -ne 'print if /^\+.*\x{2014}/'` and, for the new untracked files, `perl -CSD -ne 'print if /\x{2014}/' $(git ls-files -o --exclude-standard backend/src)` must print nothing.

- [ ] **Step 13: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (concurrent sessions edit this checkout; stage nothing you did not write), then:

```
cd backend/src
git add main/java/com/investingapp/backend/service/{PaycheckSyncPipeline,PlaidWebhookDispatcher,PlaidWebhookProcessor,JobLocks}.java main/java/com/investingapp/backend/repository/PlaidWebhookEventRepository.java main/java/com/investingapp/backend/config/SchedulingConfig.java main/java/com/investingapp/backend/model/PlaidWebhookEventStatus.java test/java/com/investingapp/backend/service/{PaycheckSyncPipelineTest,PlaidWebhookDispatcherTest,PlaidWebhookProcessorTest,PlaidWebhookClaimDatabaseTest}.java test/java/com/investingapp/backend/config/SchedulingConfigTest.java
git commit -m "feat(paycheck): sync pipeline, webhook dispatcher and inbox drainer"
```

Add any attribution trailers your session requires as further `-m` arguments; never stage `application-local.properties`.

### Verification

| Command | Expected |
|---|---|
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckSyncPipelineTest' --console=plain` | `BUILD SUCCESSFUL`, 27 tests, 0 failures |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookDispatcherTest' --console=plain` | 33 tests, 0 failures |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookProcessorTest' --console=plain` | 14 tests, 0 failures |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.SchedulingConfigTest' --console=plain` | 1 test, 0 failures |
| same form for `com.investingapp.backend.PhaseOneMoneyInvarianceTest`, `com.investingapp.backend.AlertSitesRoutedTest`, `com.investingapp.backend.service.PlaidWebhookInboxServiceTest` | pre-task counts, 0 failures |
| Step 7 scratch-MySQL block (XML counts) | `tests="2" skipped="0" failures="0"` |
| Step 7 block with gradle line `./gradlew cleanTest test --console=plain` and, for the grep line, `awk -F'"' '/<testsuite /{t+=$4; s+=$6; f+=$8} END {print "tests=" t " skipped=" s " failures=" f}' build/test-results/test/*.xml` | `skipped=0 failures=0`; `tests` = post-T13 total + 77 (27 + 33 + 2 + 1 + 14) |

### Produces

- `PaycheckSyncPipeline`: `record PipelineOutcome(SyncOutcome sync, StreamsReadOutcome streams, EvaluationOutcome evaluation, ItemStateResult itemState)`, `run(Long userId, String itemId, SyncReason reason)`, `streamsChanged(Long userId, String itemId)`, `ItemStateResult routeItemError(Long, String, PlaidApiError)`, `REVOKING_CODES`.
- `PlaidWebhookDispatcher`: `enum DispatchOutcome {PROCESSED, IGNORED_STALE, RETRY, FAILED}`, `dispatch(PlaidWebhookEvent)`, `static fromPipeline(PipelineOutcome)`.
- `PlaidWebhookProcessor`: `@Scheduled(fixedDelay = 15000) drainInbox()`, `enum ReplayResult {REQUEUED, NOT_FOUND, IN_FLIGHT}`, `replay(Long eventId)`, `BATCH_SIZE = 50`, `MAX_ATTEMPTS = 3`, `MAX_CONCURRENT_ITEMS = 4`, `DISPATCH_BUDGET` 150 s, `LOCK_LEASE` 5 min, `STALE_CLAIM_AGE` 10 min, `PERMIT_WAIT` 90 s.
- `PlaidWebhookEventRepository`: `claimReceivedIds(int)`, `markProcessing(List<Long>, LocalDateTime)`, `reclaimStale(LocalDateTime)`, `findByStatusOrderByReceivedAtAsc(PlaidWebhookEventStatus)`.
- `JobLocks.PLAID_WEBHOOK_INBOX = "plaid.webhook-inbox"`; `plaidExecutor` core 4, max 4, queue 100.

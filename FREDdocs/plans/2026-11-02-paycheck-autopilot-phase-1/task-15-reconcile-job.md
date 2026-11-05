### Task 15: reconcile-job

The 22:30 ET paycheck reconcile (spec 5.6, 6.4, 7.9, 11; rulings R4b, R6, R8), in two phases. Phase A is database only: overdue OPEN cycles become MISSED, stale CONFIRMED cycles close (vacuous in Phase 1: no CONFIRMED writer), 120-day dormant streams retire, `paycheck_events` older than 24 months are deleted, and each REJECTED applicant's Item is queued for `/item/remove` once and marked REMOVED. Every per-user write runs in its own `TransactionTemplate` under `userRepository.findByIdForUpdate(id)`, so it never races `evaluate` from the inbox drainer. Phase B calls Plaid one user at a time until the deadline (23:20 ET nightly, spec 11) or a tripped outage breaker: the `/item/get` fallback (an Item error goes to `PlaidItemStateService`; `consent_expiration_time` goes into `users.plaid_disconnect_at`), the cursor sync through `PaycheckSyncPipeline` (INIT for a consented-or-unknown OK Item with no cursor, schedule row or not, R4b; RECONCILE otherwise), and a streams re-read only when a read exists and is older than 24 hours (the first read belongs to history completion in T14, spec decision 7).

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests go under `backend/src/test/java/com/investingapp/backend/`. Line numbers are HEAD 137998c, re-verified. `JobLocks.java` is edited by T05 and T14 first and `ops/OpsAlerts.java` is created by T04 and extended by T05, so those anchors are quoted text.

**Facts this task depends on (restated, so nothing else needs opening):**

- A schedule's lifecycle is `InvestmentSchedule.status` (PENDING_BANK, ACTIVE, PAUSED_BY_USER); the deprecated `getIsPaused()` is never read. `hold_reason` is the system's stop, orthogonal to `status`; `BLOCKING_HOLDS` = ACH_RETURN_UNAUTHORIZED, ACH_RETURN_ACCOUNT, MANUAL_REVIEW. The runnable rule lives only in `InvestmentSchedule.isReadyForInvestment(todayET)`. This task writes no `status`, `mode` or `holdReason`, touches no runnable rule and no Alpaca code; its only schedule write is the R4b `detection_started_at` stamp.
- Shadow mode: no detection-originated hold on a MANUAL row (R2). Item-state holds (spec 5.7) are written only by T13's `PlaidItemStateService`, which this task calls. Dormancy is stream and cycle state only (R6): T12's `markMissesForUser` writes MISSED, `missed_count`, DORMANT and the `dormant_since` mirror, never a hold.
- R8: skip REJECTED users, `paycheck_autopilot_opt_out = true`, no `plaid_access_token`, and Items not OK or PENDING_DISCONNECT. A REJECTED user's Item is queued for `/item/remove` once here, because nothing else removes it (handoff 2.1: the row stays, with a MANUAL_REVIEW hold).
- Spec 12.1: `paycheck.autopilot.enabled=false` means nothing but the inbox runs, so the reconcile returns before phase A. Spec 11: stop dispatching at 23:20 ET; abort after 5 consecutive TRANSIENT or RATE_LIMIT results (T06's `PlaidOutageBreaker` counts them and raises `plaid.outage`).
- No Plaid HTTP inside a transaction: call, then a short template under `findByIdForUpdate` that re-checks `plaidItemId`, then the pipeline with no transaction open (T14).
- No schedule transition happens here, so no analytics event is recorded. INFO lines carry counts and closed words only; per-user lines are DEBUG or WARN with the internal user id, never a Plaid item id, token, cursor, descriptor, amount or date. Ops alerts carry counts and closed words (subject `nightly`). Every `logger.error("alert.` line is followed within 3 lines by `opsAlertService.raise(`, whose arguments never name `total`, `amount`, `getLastError`, `getMessage` or `error` (T04's `AlertSitesRoutedTest`).

**No property is added**; `paycheck.autopilot.enabled` is already local (T03's note).

**Files:**
- Create: `.../service/PaycheckReconcileService.java`, `.../scheduler/PaycheckReconcileScheduler.java`
- Modify: `.../service/JobLocks.java` (above the quoted line `    private JobLocks() {`, line 11 at HEAD); `.../repository/UserRepository.java` (after the quoted line `    List<User> findByPlaidAccessTokenIsNotNull();`, line 46 at HEAD, untouched by earlier Phase 1 tasks); `.../ops/OpsAlerts.java` (after T05's line defining `RECONCILE_RAN`; the contract says these two are "added here")
- Test: create `backend/src/test/java/com/investingapp/backend/service/PaycheckReconcileServiceTest.java` (15 tests), `backend/src/test/java/com/investingapp/backend/scheduler/PaycheckReconcileSchedulerTest.java` (7 tests)

**Interfaces:**
- Consumes (exact names from the earlier tasks' Produces blocks):
  - T02: `User.getPlaidSyncCursor()`, `getPlaidStreamsReadAt()`, `getPaycheckAutopilotOptOut()`, `setPlaidDisconnectAt(LocalDateTime)`; `InvestmentSchedule.get/setDetectionStartedAt`; `long PaycheckEventRepository.deleteByPostedDateBefore(LocalDate)`.
  - HEAD: `User.get/setPlaidItemStatus`, `getPlaidTransactionsConsented()` (tri-state `Boolean`), `getPlaidAccessToken()` (`ENC:...`), `getPlaidItemId()`, `getAccountStatus()`; `PlaidItemLifecycleService.enqueueRemoval(User)` (MANDATORY, `:87-104`); `EncryptionService.decrypt(String)`; `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(User)` (`Optional`, `:19`); `UserRepository.findByIdForUpdate(Long)`; `PlaidApi.itemGet(ItemGetRequest)`; `ItemWithConsentFields.getItemId()`, `getError()` (`PlaidError.getErrorCode()`, `Integer getStatus()`), `OffsetDateTime getConsentExpirationTime()` (all confirmed with `javap` on plaid-java 46.0.0).
  - T03: `PaycheckAutopilotProperties.isEnabled()`; `static Optional<ExclusionReason> DetectionEligibility.exclusion(User, PaycheckAutopilotProperties)`, `ExclusionReason` nested, checked in declaration order (DISABLED, ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN, ITEM_STATUS).
  - T04, T05: `OpsAlertService.raise(String, String, String, Map<String, Object>)` (never throws); `OpsAlerts.RECONCILE_RAN`.
  - T06: `<T> PlaidCallResult<T> PlaidGateway.call(String op, Call<T>)` (`body()`, `error()`, `ok()`); `PlaidApiError(errorType, errorCode, errorCodeReason, requestId, httpStatus, errorClass)`; `PlaidErrorClass.classify(String, int)`; `PlaidOutageBreaker.reset()`, `tripped()`, `THRESHOLD`.
  - T08, nested in `PlaidTransactionsSyncService`: `SyncReason`, `SyncStatus`, `SyncOutcome(status, historyJustCompleted, applied, error)`.
  - T12, `PaycheckCycleService` (MANDATORY): `usersWithOverdueOpenCycles(LocalDate)`, `markMissesForUser(Long, LocalDate)`, `usersWithStaleConfirmed(LocalDateTime)`, `markStaleForUser(Long, LocalDateTime)`, `usersWithDormantStreamsOver(LocalDate)`, `retireDormantForUser(Long, LocalDate)`.
  - T13, `PlaidItemStateService` (each locks, guards the item and sends RECONNECT after commit): `onLoginRequired(Long, String itemId, String errorCode)`, `onRevoked(Long, String, String source)`, `onConsentRequired(Long, String)`, `onNotSupported(Long, String)`, `onSyncLimit(Long, String)`.
  - T14: `PaycheckSyncPipeline.run(Long, String itemId, SyncReason)` returning nested `PipelineOutcome(sync, streams, evaluation, itemState)`; `streamsChanged(Long, String)` (return ignored).
  - Existing: `SchedulerGate.isEnabled()`, `JobLockService.tryAcquire/release`, `EasternTime.now()`, `EasternTime.ZONE`.
- Produces: exactly the names in the Produces recap at the end of this file. T18's `reconcile` verb and T19's dev `?date=` verb call `runForUser`.

**Deadline rule (one rule for every entry point).** `deadline = startET + 50 min`, cut to `todayET` 23:20 when the run starts before 23:20 and the budget would pass it. The nightly 22:30 start gives 23:20 (spec 11). A run started after 23:20 (admin or dev `runForUser`) gets its 50 minutes instead of stopping at once; one started between 22:30 and 23:20 stops at 23:20.

- [ ] **Step 1: Record the neighbour baselines, then add the lock name, the alert names and the two repository methods**

First run the Step 10 commands (T03's and T04's source-walk guards, which this task's files fall under) and write down both `tests=` numbers; expect `skipped="0" failures="0" errors="0"`.

`.../service/JobLocks.java`: directly above the quoted line `    private JobLocks() {` insert:

```java
    public static final String PAYCHECK_RECONCILE = "paycheck.reconcile";

```

`.../ops/OpsAlerts.java`: directly after the line that defines `RECONCILE_RAN = "paycheck.reconcile.ran"` insert:

```java
    /** Spec 12.3 "reconcile truncated". */
    public static final String RECONCILE_TRUNCATED = "paycheck.reconcile.truncated";
    /** Spec 12.3 "reconcile throwing". */
    public static final String RECONCILE_THREW = "paycheck.reconcile.threw";
```

`.../repository/UserRepository.java`: after the quoted line `    List<User> findByPlaidAccessTokenIsNotNull();` insert:

```java

    // Paycheck reconcile, REJECTED-applicant pass (R8); the caller skips REMOVED Items in Java.
    List<User> findByPlaidAccessTokenIsNotNullAndAccountStatus(String accountStatus);

    // Paycheck reconcile, phase B: ids only; consent and Item status are filtered in Java.
    @Query("SELECT u.id FROM User u WHERE u.plaidAccessToken IS NOT NULL")
    List<Long> findIdsWithPlaidToken();
```

(`Query` and `List` are already imported at `UserRepository.java:9` and `:15`.)

Run `cd backend && ./gradlew compileJava --console=plain`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Failing tests for the reconcile service**

Create `backend/src/test/java/com/investingapp/backend/service/PaycheckReconcileServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.plaid.PlaidOutageBreaker;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PaycheckReconcileService.ReconcileReport;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.PlaidError;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import retrofit2.Call;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The 22:30 reconcile. Plaid, the pipeline and the handlers are mocks; the transaction manager only
 * flips a flag, so "inside a transaction" and "outside any transaction" are observable.
 */
class PaycheckReconcileServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final LocalDateTime START = TODAY.atTime(22, 30);

    /** A fake transaction whose open flag a test can read. */
    static final class OpenFlagTransactionManager extends AbstractPlatformTransactionManager {
        boolean open;
        int begins;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            open = true;
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            open = false;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            open = false;
        }
    }

    private final Map<Long, User> users = new LinkedHashMap<>();
    private final Map<Long, InvestmentSchedule> schedules = new HashMap<>();
    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>(START);
    private final OpenFlagTransactionManager txManager = new OpenFlagTransactionManager();

    private UserRepository userRepository;
    private InvestmentScheduleRepository scheduleRepository;
    private PaycheckEventRepository eventRepository;
    private PaycheckCycleService cycleService;
    private PaycheckSyncPipeline pipeline;
    private PlaidItemStateService itemStateService;
    private PlaidItemLifecycleService lifecycleService;
    private PlaidGateway gateway;
    private PlaidOutageBreaker breaker;
    private PlaidApi plaidApi;
    private EncryptionService encryptionService;
    private OpsAlertService opsAlertService;
    private PaycheckAutopilotProperties props;
    private PaycheckReconcileService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        scheduleRepository = mock(InvestmentScheduleRepository.class);
        eventRepository = mock(PaycheckEventRepository.class);
        cycleService = mock(PaycheckCycleService.class);
        pipeline = mock(PaycheckSyncPipeline.class);
        itemStateService = mock(PlaidItemStateService.class);
        lifecycleService = mock(PlaidItemLifecycleService.class);
        gateway = mock(PlaidGateway.class);
        breaker = mock(PlaidOutageBreaker.class);
        plaidApi = mock(PlaidApi.class);
        encryptionService = mock(EncryptionService.class);
        opsAlertService = mock(OpsAlertService.class);
        props = mock(PaycheckAutopilotProperties.class);

        when(props.isEnabled()).thenReturn(true);
        when(encryptionService.decrypt(anyString())).thenReturn("access-sandbox-token");
        when(plaidApi.itemGet(any())).thenReturn(mock(Call.class));
        doReturn(itemGetOk(null)).when(gateway).call(eq("item.get"), any());
        when(userRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.getArgument(0))));
        when(userRepository.findByIdForUpdate(anyLong())).thenAnswer(inv -> Optional.ofNullable(users.get(inv.getArgument(0))));
        when(userRepository.findIdsWithPlaidToken()).thenAnswer(inv -> users.values().stream()
                .filter(u -> u.getPlaidAccessToken() != null).map(User::getId).toList());
        when(userRepository.findByPlaidAccessTokenIsNotNullAndAccountStatus("REJECTED")).thenAnswer(inv -> users.values().stream()
                .filter(u -> u.getPlaidAccessToken() != null && "REJECTED".equals(u.getAccountStatus())).toList());
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(any())).thenAnswer(inv ->
                Optional.ofNullable(schedules.get(((User) inv.getArgument(0)).getId())));
        when(pipeline.run(anyLong(), anyString(), any())).thenReturn(outcome(SyncStatus.APPLIED));

        service = new PaycheckReconcileService(userRepository, scheduleRepository, eventRepository, cycleService,
                pipeline, itemStateService, lifecycleService, gateway, breaker, plaidApi, encryptionService,
                opsAlertService, props, txManager);
        ReflectionTestUtils.setField(service, "clock", (Supplier<LocalDateTime>) clock::get);
    }

    /** An eligible user: token, OK Item, cursor. */
    private User user(long id) {
        User u = new User();
        u.setId(id);
        u.setAccountStatus("ACTIVE");
        u.setPlaidAccessToken("ENC:token-" + id);
        u.setPlaidItemId("item-" + id);
        u.setPlaidItemStatus(PlaidItemStatus.OK);
        u.setPaycheckAutopilotOptOut(false);
        u.setPlaidSyncCursor("cursor-" + id);
        users.put(id, u);
        return u;
    }

    private InvestmentSchedule schedule(long userId, LocalDateTime detectionStartedAt) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setDetectionStartedAt(detectionStartedAt);
        schedules.put(userId, s);
        return s;
    }

    private static PlaidCallResult<ItemGetResponse> itemGetOk(OffsetDateTime consentExpiration) {
        return new PlaidCallResult<>(new ItemGetResponse().item(new ItemWithConsentFields()
                .itemId("item").consentExpirationTime(consentExpiration)), null);
    }

    private static PipelineOutcome outcome(SyncStatus status) {
        return new PipelineOutcome(new SyncOutcome(status, false, null, null), null, null, null);
    }

    private ReconcileReport runNightly() {
        return service.run(TODAY, START);
    }

    @Test
    void disabledRunsNeitherPhase() {
        when(props.isEnabled()).thenReturn(false);
        user(1).setPlaidSyncCursor(null);

        assertEquals(ReconcileReport.empty(), service.run(TODAY, START));
        assertEquals(ReconcileReport.empty(), service.runForUser(1L, TODAY));

        verifyNoInteractions(userRepository, scheduleRepository, eventRepository, cycleService, pipeline,
                gateway, plaidApi, breaker, lifecycleService, itemStateService, opsAlertService);
        assertEquals(0, txManager.begins, "spec 12.1: off means nothing runs, not even a read");
    }

    @Test
    void phaseAIsDatabaseOnly() {
        user(1).setPlaidAccessToken(null);
        user(2);
        when(cycleService.usersWithOverdueOpenCycles(TODAY)).thenReturn(List.of(1L));
        when(cycleService.usersWithStaleConfirmed(START)).thenReturn(List.of(1L));
        when(cycleService.usersWithDormantStreamsOver(TODAY)).thenReturn(List.of(1L));
        when(cycleService.markMissesForUser(1L, TODAY)).thenReturn(1);
        when(cycleService.retireDormantForUser(1L, TODAY)).thenReturn(1);
        when(eventRepository.deleteByPostedDateBefore(LocalDate.of(2024, 11, 4))).thenReturn(3L);

        ReconcileReport report = runNightly();

        InOrder order = inOrder(breaker, cycleService, eventRepository, gateway, pipeline);
        order.verify(breaker).reset();
        order.verify(cycleService).markMissesForUser(1L, TODAY);
        order.verify(cycleService).markStaleForUser(1L, START);
        order.verify(cycleService).retireDormantForUser(1L, TODAY);
        order.verify(eventRepository).deleteByPostedDateBefore(LocalDate.of(2024, 11, 4));
        order.verify(gateway).call(eq("item.get"), any());
        order.verify(pipeline).run(2L, "item-2", SyncReason.RECONCILE);
        verify(gateway, times(1)).call(anyString(), any());
        assertEquals(1, report.misses());
        assertEquals(1, report.retired());
        assertEquals(3, report.eventsPurged());
        assertEquals(1, report.candidates());
        assertEquals(1, report.synced());
    }

    @Test
    void phaseATakesUserRowLockPerUser() {
        user(1).setPlaidAccessToken(null);
        user(3).setPlaidAccessToken(null);
        Map<Long, Integer> lockedInTx = new HashMap<>();
        when(userRepository.findByIdForUpdate(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            assertTrue(txManager.open, "the row lock is taken inside the per-user transaction");
            lockedInTx.put(id, txManager.begins);
            return Optional.ofNullable(users.get(id));
        });
        when(cycleService.usersWithOverdueOpenCycles(TODAY)).thenReturn(List.of(1L, 3L));
        when(cycleService.markMissesForUser(anyLong(), eq(TODAY))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            assertTrue(txManager.open, "the miss pass writes inside a transaction");
            assertEquals(Integer.valueOf(txManager.begins), lockedInTx.get(id),
                    "and in the same transaction that locked user " + id);
            return 1;
        });

        assertEquals(2, runNightly().misses());
        assertNotEquals(lockedInTx.get(1L), lockedInTx.get(3L), "each user gets a transaction of its own");
    }

    @Test
    void phaseASkipsRejectedAndOptedOut() {
        user(1).setAccountStatus("REJECTED");
        user(2).setPaycheckAutopilotOptOut(true);
        user(3).setPlaidAccessToken(null);
        List<Long> all = List.of(1L, 2L, 3L);
        when(cycleService.usersWithOverdueOpenCycles(TODAY)).thenReturn(all);
        when(cycleService.usersWithStaleConfirmed(START)).thenReturn(all);
        when(cycleService.usersWithDormantStreamsOver(TODAY)).thenReturn(all);

        runNightly();

        verify(cycleService).markMissesForUser(3L, TODAY);
        verify(cycleService).markStaleForUser(3L, START);
        verify(cycleService).retireDormantForUser(3L, TODAY);
        for (long skipped : new long[] {1L, 2L}) {
            verify(cycleService, never()).markMissesForUser(skipped, TODAY);
            verify(cycleService, never()).markStaleForUser(skipped, START);
            verify(cycleService, never()).retireDormantForUser(skipped, TODAY);
        }
        verify(gateway, never()).call(anyString(), any());
    }

    @Test
    void rejectedUserItemQueuedOnceAndMarkedRemoved() {
        User rejected = user(1);
        rejected.setAccountStatus("REJECTED");
        User alreadyRemoved = user(2);
        alreadyRemoved.setAccountStatus("REJECTED");
        alreadyRemoved.setPlaidItemStatus(PlaidItemStatus.REMOVED);
        doAnswer(inv -> {
            assertTrue(txManager.open, "enqueueRemoval is MANDATORY: it runs in the per-user template");
            return null;
        }).when(lifecycleService).enqueueRemoval(any());

        assertEquals(1, runNightly().rejectedItemsQueued());
        assertEquals(0, runNightly().rejectedItemsQueued(), "a second night finds the Item REMOVED and queues nothing");

        verify(lifecycleService, times(1)).enqueueRemoval(rejected);
        verify(lifecycleService, never()).enqueueRemoval(alreadyRemoved);
        assertEquals(PlaidItemStatus.REMOVED, rejected.getPlaidItemStatus(), "the Item leaves the billable gauge");
        verify(gateway, never()).call(anyString(), any());
        verifyNoInteractions(pipeline);
    }

    @Test
    void initializesOnlyConsentedOrUnknownOkItemsWithoutCursor() {
        user(1).setPlaidSyncCursor(null);
        User consented = user(2);
        consented.setPlaidSyncCursor(null);
        consented.setPlaidTransactionsConsented(true);
        User refused = user(3);
        refused.setPlaidSyncCursor(null);
        refused.setPlaidTransactionsConsented(false);
        User pendingDisconnect = user(4);
        pendingDisconnect.setPlaidSyncCursor(null);
        pendingDisconnect.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        user(5);
        User broken = user(6);
        broken.setPlaidSyncCursor(null);
        broken.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);

        ReconcileReport report = runNightly();

        verify(pipeline).run(1L, "item-1", SyncReason.INIT);
        verify(pipeline).run(2L, "item-2", SyncReason.INIT);
        verify(pipeline).run(5L, "item-5", SyncReason.RECONCILE);
        verify(pipeline, times(3)).run(anyLong(), anyString(), any());
        verify(gateway, times(5)).call(eq("item.get"), any());
        assertEquals(5, report.candidates(), "the broken Item is excluded before any Plaid call");
        assertEquals(2, report.initialized());
        assertEquals(1, report.synced());
    }

    @Test
    void reconcileInitStampsDetectionStartedAt() {
        user(1).setPlaidSyncCursor(null);
        InvestmentSchedule unstamped = schedule(1, null);
        user(2).setPlaidSyncCursor(null);
        LocalDateTime earlier = START.minusDays(3);
        InvestmentSchedule stamped = schedule(2, earlier);
        user(3);
        InvestmentSchedule reconciled = schedule(3, null);
        when(scheduleRepository.save(any())).thenAnswer(inv -> {
            assertTrue(txManager.open, "the stamp is written in the per-user template");
            return inv.getArgument(0);
        });
        when(pipeline.run(eq(1L), anyString(), eq(SyncReason.INIT))).thenAnswer(inv -> {
            assertEquals(START, unstamped.getDetectionStartedAt(), "stamped before the INIT sync starts");
            assertFalse(txManager.open, "and the stamp's transaction has committed");
            return outcome(SyncStatus.NOT_INITIALIZED);
        });

        runNightly();

        assertEquals(START, unstamped.getDetectionStartedAt());
        assertEquals(earlier, stamped.getDetectionStartedAt(), "an existing stamp is never moved");
        assertNull(reconciled.getDetectionStartedAt(), "a RECONCILE sync never stamps");
        verify(scheduleRepository, times(1)).save(any());
    }

    @Test
    void neverFirstReadsStreamsFromReconcile() {
        user(1);
        user(2).setPlaidStreamsReadAt(START.minusHours(25));
        user(3).setPlaidStreamsReadAt(START.minusHours(2));
        user(4).setPlaidStreamsReadAt(START.minusHours(25));
        when(pipeline.run(eq(4L), anyString(), any())).thenReturn(outcome(SyncStatus.RETRY_LATER));

        ReconcileReport report = runNightly();

        verify(pipeline).streamsChanged(2L, "item-2");
        verify(pipeline, times(1)).streamsChanged(anyLong(), anyString());
        assertEquals(1, report.streamsReread());
    }

    @Test
    void itemGetErrorIsRoutedAndConsentExpiryStored() {
        user(1);
        user(2);
        user(3);
        User renewing = user(4);
        doReturn(
                new PlaidCallResult<>(new ItemGetResponse().item(new ItemWithConsentFields().itemId("item-1")
                        .error(new PlaidError().errorCode("ITEM_LOGIN_REQUIRED").status(400))), null),
                new PlaidCallResult<>(null, new PlaidApiError("ITEM_ERROR", "ITEM_NOT_FOUND", null, "req-2", 400,
                        PlaidErrorClass.ITEM_BROKEN)),
                new PlaidCallResult<>(null, new PlaidApiError("INVALID_INPUT", "ADDITIONAL_CONSENT_REQUIRED", null,
                        "req-3", 400, PlaidErrorClass.CONSENT)),
                itemGetOk(OffsetDateTime.parse("2027-03-01T17:00:00Z")))
                .when(gateway).call(eq("item.get"), any());

        runNightly();

        verify(itemStateService).onLoginRequired(1L, "item-1", "ITEM_LOGIN_REQUIRED");
        verify(itemStateService).onRevoked(2L, "item-2", "ITEM_NOT_FOUND");
        verify(itemStateService).onConsentRequired(3L, "item-3");
        verify(pipeline).run(4L, "item-4", SyncReason.RECONCILE);
        verify(pipeline, times(1)).run(anyLong(), anyString(), any());
        assertEquals(LocalDateTime.of(2027, 3, 1, 12, 0), renewing.getPlaidDisconnectAt(),
                "consent_expiration_time is stored as Eastern wall time");
    }

    @Test
    void plaidCallsRunOutsideAnyTransaction() {
        user(1).setPlaidSyncCursor(null);
        user(2).setPlaidStreamsReadAt(START.minusHours(30));
        doAnswer(inv -> {
            assertFalse(txManager.open, "/item/get never runs inside a transaction");
            return itemGetOk(null);
        }).when(gateway).call(eq("item.get"), any());
        when(pipeline.run(anyLong(), anyString(), any())).thenAnswer(inv -> {
            assertFalse(txManager.open, "the pipeline is never called inside a transaction");
            return outcome(SyncStatus.APPLIED);
        });
        doAnswer(inv -> {
            assertFalse(txManager.open, "nor is the streams re-read");
            return null;
        }).when(pipeline).streamsChanged(anyLong(), anyString());

        ReconcileReport report = runNightly();

        assertEquals(1, report.initialized());
        assertEquals(1, report.synced());
        assertEquals(1, report.streamsReread());
    }

    @Test
    void stopsDispatchingAt2320() {
        for (long id = 1; id <= 4; id++) {
            user(id);
        }
        when(pipeline.run(anyLong(), anyString(), any())).thenAnswer(inv -> {
            clock.set(clock.get().plusMinutes(30));
            return outcome(SyncStatus.APPLIED);
        });

        ReconcileReport report = service.run(TODAY, START);

        verify(pipeline).run(1L, "item-1", SyncReason.RECONCILE);
        verify(pipeline).run(2L, "item-2", SyncReason.RECONCILE);
        verify(pipeline, times(2)).run(anyLong(), anyString(), any());
        verify(gateway, times(2)).call(eq("item.get"), any());
        assertTrue(report.truncated());
        verify(opsAlertService).raise(eq(OpsAlerts.RECONCILE_TRUNCATED), eq("nightly"), anyString(),
                argThat(context -> Integer.valueOf(2).equals(context.get("remaining"))));
    }

    @Test
    void manualRunUsesItsOwnDeadline() {
        for (long id = 1; id <= 3; id++) {
            user(id);
        }
        LocalDateTime lateStart = TODAY.atTime(23, 30);
        clock.set(lateStart);
        when(pipeline.run(anyLong(), anyString(), any())).thenAnswer(inv -> {
            clock.set(clock.get().plusMinutes(10));
            return outcome(SyncStatus.APPLIED);
        });

        ReconcileReport late = service.run(TODAY, lateStart);

        assertFalse(late.truncated(), "a run started after 23:20 gets its 50 minutes");
        verify(pipeline, times(3)).run(anyLong(), anyString(), any());

        clock.set(TODAY.atTime(23, 45));
        ReconcileReport single = service.runForUser(2L, TODAY);

        assertEquals(1, single.synced());
        verify(pipeline, times(2)).run(2L, "item-2", SyncReason.RECONCILE);
        verify(breaker, times(1)).reset();
    }

    @Test
    void deadlineIsTheCutoffInsideTheNightlyWindowElseTheBudget() {
        assertEquals(TODAY.atTime(23, 20), PaycheckReconcileService.deadline(TODAY, TODAY.atTime(22, 30)));
        assertEquals(TODAY.atTime(23, 20), PaycheckReconcileService.deadline(TODAY, TODAY.atTime(22, 45)));
        assertEquals(TODAY.plusDays(1).atTime(0, 20), PaycheckReconcileService.deadline(TODAY, TODAY.atTime(23, 30)));
        assertEquals(TODAY.atTime(14, 50), PaycheckReconcileService.deadline(TODAY, TODAY.atTime(14, 0)));
    }

    @Test
    void circuitBreaksAfterFiveTransient() {
        for (long id = 1; id <= 7; id++) {
            user(id);
        }
        AtomicInteger transients = new AtomicInteger();
        doAnswer(inv -> {
            transients.incrementAndGet();
            return new PlaidCallResult<>(null, new PlaidApiError("INSTITUTION_ERROR", "INSTITUTION_DOWN", null,
                    "req", 400, PlaidErrorClass.TRANSIENT));
        }).when(gateway).call(eq("item.get"), any());
        when(breaker.tripped()).thenAnswer(inv -> transients.get() >= PlaidOutageBreaker.THRESHOLD);

        ReconcileReport report = runNightly();

        assertTrue(report.aborted());
        verify(gateway, times(5)).call(eq("item.get"), any());
        verifyNoInteractions(pipeline, itemStateService);
        InOrder order = inOrder(breaker, gateway);
        order.verify(breaker).reset();
        order.verify(gateway).call(eq("item.get"), any());
    }

    @Test
    void reconcileSourcesNeverWriteStatusModeOrHold() throws Exception {
        for (String path : List.of(
                "src/main/java/com/investingapp/backend/service/PaycheckReconcileService.java",
                "src/main/java/com/investingapp/backend/scheduler/PaycheckReconcileScheduler.java")) {
            String source = Files.readString(Path.of(path));
            for (String forbidden : List.of("setStatus(", "setMode(", "setHoldReason(", "getIsPaused(",
                    "isPausedForAnyReason(", "isReadyForInvestment(", "AlpacaApiService", "InvestmentExecution",
                    "AnalyticsOutboxService")) {
                assertFalse(source.contains(forbidden), path + " must not contain " + forbidden);
            }
        }
    }
}
```

Fixture notes: `doReturn(a, b, c, d)` answers the `/item/get` calls in `findIdsWithPlaidToken()` order, which the `LinkedHashMap` keeps as insertion order; `classify("ITEM_LOGIN_REQUIRED", 400)` is ITEM_BROKEN (spec 2.1, T06); `2027-03-01T17:00:00Z` is 12:00 EST; unstubbed Mockito `List` methods answer an empty list and `long` methods 0.

- [ ] **Step 3: Run it and watch it fail**

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckReconcileServiceTest' --console=plain`.

Expected: `compileTestJava` fails with `error: cannot find symbol` naming `class PaycheckReconcileService` and `package com.investingapp.backend.service.PaycheckReconcileService does not exist` (the nested import), then `BUILD FAILED`.

- [ ] **Step 4: The reconcile service**

Create `backend/src/main/java/com/investingapp/backend/service/PaycheckReconcileService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.plaid.PlaidOutageBreaker;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.ItemGetRequest;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.PlaidError;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The 22:30 ET paycheck reconcile (spec 5.6, 6.4, 7.9, 11; rulings R4b, R6, R8). Phase A is database
 * only, per user under the user row lock; phase B calls Plaid one user at a time until the deadline
 * or a tripped breaker. No Plaid call runs inside a transaction. Nothing here moves money or writes a
 * schedule's lifecycle, mode or hold (PlaidItemStateService owns Item-state holds), and no analytics
 * event is recorded, because no schedule transition happens here.
 */
@Service
public class PaycheckReconcileService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckReconcileService.class);

    /** Spec 11: stop dispatching at 23:20 ET. */
    public static final LocalTime CUTOFF = LocalTime.of(23, 20);
    /** A run's own budget; from 22:30 it ends at CUTOFF. */
    public static final Duration MANUAL_RUN_BUDGET = Duration.ofMinutes(50);
    /** Spec 2.6: streams are re-read when the last read is older than this. */
    public static final Duration STREAMS_MAX_AGE = Duration.ofHours(24);
    /** Spec 7.9: events older than this many months are deleted. */
    public static final int EVENT_RETENTION_MONTHS = 24;
    /** Ops alert subject for every reconcile alert: one row per type per night. */
    public static final String ALERT_SUBJECT = "nightly";

    private static final String REJECTED = "REJECTED";
    /** ITEM_BROKEN codes that mean the Item is gone rather than needing a login (spec 5.7 row 2). */
    private static final Set<String> REVOKED_CODES = Set.of("ITEM_NOT_FOUND", "USER_PERMISSION_REVOKED");

    /** initialized and synced count INIT and RECONCILE runs dispatched; truncated = deadline, aborted = breaker. */
    public record ReconcileReport(int candidates, int initialized, int synced, int streamsReread, int evaluated,
            int misses, int stale, int retired, int rejectedItemsQueued, int eventsPurged,
            boolean truncated, boolean aborted) {

        public static ReconcileReport empty() {
            return new ReconcileReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);
        }

        /** Counts and flags only, for the RECONCILE_RAN alert context. */
        public Map<String, Object> toContext() {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("completed", true);
            context.put("candidates", candidates);
            context.put("initialized", initialized);
            context.put("synced", synced);
            context.put("streams_reread", streamsReread);
            context.put("evaluated", evaluated);
            context.put("misses", misses);
            context.put("stale", stale);
            context.put("retired", retired);
            context.put("rejected_items_queued", rejectedItemsQueued);
            context.put("events_purged", eventsPurged);
            context.put("truncated", truncated);
            context.put("aborted", aborted);
            return context;
        }
    }

    /** Mutable counters for one run; the report is built once at the end. */
    private static final class Tally {
        int candidates, initialized, synced, streamsReread, evaluated;
        int misses, stale, retired, rejectedItemsQueued, eventsPurged;
        boolean truncated, aborted;

        ReconcileReport report() {
            return new ReconcileReport(candidates, initialized, synced, streamsReread, evaluated,
                    misses, stale, retired, rejectedItemsQueued, eventsPurged, truncated, aborted);
        }
    }

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckEventRepository eventRepository;
    private final PaycheckCycleService cycleService;
    private final PaycheckSyncPipeline pipeline;
    private final PlaidItemStateService itemStateService;
    private final PlaidItemLifecycleService lifecycleService;
    private final PlaidGateway gateway;
    private final PlaidOutageBreaker breaker;
    private final PlaidApi plaidApi;
    private final EncryptionService encryptionService;
    private final OpsAlertService opsAlertService;
    private final PaycheckAutopilotProperties props;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    /** Eastern wall clock for the deadline checks; tests replace it by reflection. */
    private Supplier<LocalDateTime> clock = EasternTime::now;

    public PaycheckReconcileService(UserRepository userRepository,
            InvestmentScheduleRepository scheduleRepository,
            PaycheckEventRepository eventRepository,
            PaycheckCycleService cycleService,
            PaycheckSyncPipeline pipeline,
            PlaidItemStateService itemStateService,
            PlaidItemLifecycleService lifecycleService,
            PlaidGateway gateway,
            PlaidOutageBreaker breaker,
            PlaidApi plaidApi,
            EncryptionService encryptionService,
            OpsAlertService opsAlertService,
            PaycheckAutopilotProperties props,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.cycleService = cycleService;
        this.pipeline = pipeline;
        this.itemStateService = itemStateService;
        this.lifecycleService = lifecycleService;
        this.gateway = gateway;
        this.breaker = breaker;
        this.plaidApi = plaidApi;
        this.encryptionService = encryptionService;
        this.opsAlertService = opsAlertService;
        this.props = props;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** Start plus MANUAL_RUN_BUDGET, cut to CUTOFF when the run starts before it and the budget passes it. */
    static LocalDateTime deadline(LocalDate todayET, LocalDateTime startET) {
        LocalDateTime budget = startET.plus(MANUAL_RUN_BUDGET);
        LocalDateTime cutoff = todayET.atTime(CUTOFF);
        return startET.isBefore(cutoff) && cutoff.isBefore(budget) ? cutoff : budget;
    }

    /** The whole reconcile: phase A for every affected user, then phase B until the deadline. */
    public ReconcileReport run(LocalDate todayET, LocalDateTime nowET) {
        if (!props.isEnabled()) {
            logger.info("paycheck.reconcile.skipped reason=disabled");
            return ReconcileReport.empty();
        }
        breaker.reset();
        LocalDateTime deadline = deadline(todayET, nowET);
        Tally tally = new Tally();

        for (Long id : idsFrom(() -> cycleService.usersWithOverdueOpenCycles(todayET))) {
            tally.misses += phaseAForUser(id, user -> cycleService.markMissesForUser(user.getId(), todayET));
        }
        for (Long id : idsFrom(() -> cycleService.usersWithStaleConfirmed(nowET))) {
            tally.stale += phaseAForUser(id, user -> cycleService.markStaleForUser(user.getId(), nowET));
        }
        for (Long id : idsFrom(() -> cycleService.usersWithDormantStreamsOver(todayET))) {
            tally.retired += phaseAForUser(id, user -> cycleService.retireDormantForUser(user.getId(), todayET));
        }
        tally.eventsPurged = purgeExpiredEvents(todayET);
        for (User candidate : userRepository.findByPlaidAccessTokenIsNotNullAndAccountStatus(REJECTED)) {
            if (candidate.getPlaidItemStatus() != PlaidItemStatus.REMOVED) {
                tally.rejectedItemsQueued += retireRejectedItem(candidate.getId());
            }
        }
        logger.info("paycheck.reconcile.phase_a misses={} stale={} retired={} events_purged={} rejected_items_queued={}",
                tally.misses, tally.stale, tally.retired, tally.eventsPurged, tally.rejectedItemsQueued);

        List<Long> ids = userRepository.findIdsWithPlaidToken();
        int remaining = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (!clock.get().isBefore(deadline)) {
                tally.truncated = true;
                remaining = ids.size() - i;
                break;
            }
            if (breaker.tripped()) {
                tally.aborted = true;
                remaining = ids.size() - i;
                break;
            }
            reconcileUser(ids.get(i), nowET, tally);
        }
        if (tally.truncated) {
            logger.warn("paycheck.reconcile.truncated remaining={} candidates={}", remaining, tally.candidates);
            opsAlertService.raise(OpsAlerts.RECONCILE_TRUNCATED, ALERT_SUBJECT,
                    "reconcile stopped at its deadline with users left",
                    Map.of("remaining", remaining, "candidates", tally.candidates));
        }
        if (tally.aborted) {
            // The breaker itself raised plaid.outage.
            logger.warn("paycheck.reconcile.aborted reason=plaid_outage remaining={}", remaining);
        }
        logger.info("paycheck.reconcile.phase_b candidates={} initialized={} synced={} streams_reread={} evaluated={} truncated={} aborted={}",
                tally.candidates, tally.initialized, tally.synced, tally.streamsReread, tally.evaluated,
                tally.truncated, tally.aborted);
        return tally.report();
    }

    /** One user (T18's admin verb): phase A and the REJECTED pass for this user, then phase B. No purge, no reset. */
    public ReconcileReport runForUser(Long userId, LocalDate todayET) {
        if (!props.isEnabled()) {
            logger.info("paycheck.reconcile.skipped reason=disabled");
            return ReconcileReport.empty();
        }
        LocalDateTime nowET = clock.get();
        LocalDateTime deadline = deadline(todayET, nowET);
        Tally tally = new Tally();
        tally.misses = phaseAForUser(userId, user -> cycleService.markMissesForUser(user.getId(), todayET));
        tally.stale = phaseAForUser(userId, user -> cycleService.markStaleForUser(user.getId(), nowET));
        tally.retired = phaseAForUser(userId, user -> cycleService.retireDormantForUser(user.getId(), todayET));
        tally.rejectedItemsQueued = retireRejectedItem(userId);
        if (!clock.get().isBefore(deadline)) {
            tally.truncated = true;
        } else if (breaker.tripped()) {
            tally.aborted = true;
        } else {
            reconcileUser(userId, nowET, tally);
        }
        return tally.report();
    }

    // ---- phase A: database only ---------------------------------------------------------------

    private List<Long> idsFrom(Supplier<List<Long>> finder) {
        List<Long> ids = readTx.execute(status -> finder.get());
        return ids == null ? List.of() : ids;
    }

    /** R8: phase A leaves REJECTED and opted-out users alone; a broken or missing Item does not stop it. */
    private boolean skippedInPhaseA(User user) {
        Optional<ExclusionReason> reason = DetectionEligibility.exclusion(user, props);
        return reason.isPresent()
                && (reason.get() == ExclusionReason.ACCOUNT_REJECTED || reason.get() == ExclusionReason.OPTED_OUT);
    }

    /** One pass for one user, in its own transaction under the user row lock. Never throws. */
    private int phaseAForUser(Long userId, ToIntFunction<User> pass) {
        try {
            Integer changed = writeTx.execute(status -> userRepository.findByIdForUpdate(userId)
                    .filter(user -> !skippedInPhaseA(user))
                    .map(pass::applyAsInt)
                    .orElse(0));
            return changed == null ? 0 : changed;
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.phase_a_failed user={} exception={}", userId, e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Spec 7.9: events older than 24 months go. Events only; cycles keep their row (event_id SET NULL). */
    private int purgeExpiredEvents(LocalDate todayET) {
        LocalDate before = todayET.minusMonths(EVENT_RETENTION_MONTHS);
        try {
            Long deleted = writeTx.execute(status -> eventRepository.deleteByPostedDateBefore(before));
            return deleted == null ? 0 : (int) Math.min(deleted, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.purge_failed exception={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** R8: queue a REJECTED applicant's Item once and mark it REMOVED (off the billable gauge). */
    private int retireRejectedItem(Long userId) {
        try {
            Boolean queued = writeTx.execute(status -> userRepository.findByIdForUpdate(userId).map(user -> {
                if (!REJECTED.equals(user.getAccountStatus()) || user.getPlaidAccessToken() == null
                        || user.getPlaidItemStatus() == PlaidItemStatus.REMOVED) {
                    return false;
                }
                lifecycleService.enqueueRemoval(user);
                user.setPlaidItemStatus(PlaidItemStatus.REMOVED);
                userRepository.save(user);
                logger.debug("paycheck.reconcile.rejected_item_queued user={}", userId);
                return true;
            }).orElse(false));
            return Boolean.TRUE.equals(queued) ? 1 : 0;
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.rejected_item_failed user={} exception={}", userId, e.getClass().getSimpleName());
            return 0;
        }
    }

    // ---- phase B: Plaid, one user at a time ---------------------------------------------------

    /** /item/get, then one short template, then the pipeline, then the streams re-read. Never throws. */
    private void reconcileUser(Long userId, LocalDateTime nowET, Tally tally) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || DetectionEligibility.exclusion(user, props).isPresent()) {
                return;
            }
            String itemId = user.getPlaidItemId();
            if (itemId == null || itemId.isBlank()) {
                return;
            }
            tally.candidates++;

            // 1. The /item/get fallback (spec 5.6), on this thread and outside any transaction.
            PlaidCallResult<ItemGetResponse> got = gateway.call("item.get", plaidApi.itemGet(
                    new ItemGetRequest().accessToken(encryptionService.decrypt(user.getPlaidAccessToken()))));
            if (!got.ok()) {
                PlaidApiError failure = got.error();
                if (failure != null) {
                    routeItemError(userId, itemId, failure.errorCode(), failure.errorClass());
                }
                return;
            }
            ItemWithConsentFields item = got.body() == null ? null : got.body().getItem();
            if (item == null) {
                return;
            }
            PlaidError itemState = item.getError();
            if (itemState != null && itemState.getErrorCode() != null && !itemState.getErrorCode().isBlank()) {
                int httpStatus = itemState.getStatus() == null ? 400 : itemState.getStatus();
                routeItemError(userId, itemId, itemState.getErrorCode(),
                        PlaidErrorClass.classify(itemState.getErrorCode(), httpStatus));
                return;
            }
            LocalDateTime consentExpiresAt = item.getConsentExpirationTime() == null ? null
                    : item.getConsentExpirationTime().atZoneSameInstant(EasternTime.ZONE).toLocalDateTime();

            // 2. One per-user template: consent expiry, the sync reason, and the R4b stamp.
            SyncReason reason = writeTx.execute(status -> prepare(userId, itemId, consentExpiresAt, nowET));
            if (reason == null) {
                return;
            }

            // 3. The cursor sync, with no transaction open (the pipeline takes its own).
            if (breaker.tripped()) {
                tally.aborted = true;
                return;
            }
            PipelineOutcome outcome = pipeline.run(userId, itemId, reason);
            if (reason == SyncReason.INIT) {
                tally.initialized++;
            } else {
                tally.synced++;
            }
            if (outcome != null && outcome.evaluation() != null) {
                tally.evaluated++;
            }

            // 4. Streams: re-read only an old read. The first read belongs to history completion (T14).
            boolean applied = outcome != null && outcome.sync() != null && outcome.sync().status() == SyncStatus.APPLIED;
            if (!applied || breaker.tripped()) {
                return;
            }
            LocalDateTime readAt = userRepository.findById(userId).map(User::getPlaidStreamsReadAt).orElse(null);
            if (readAt != null && readAt.isBefore(nowET.minus(STREAMS_MAX_AGE))) {
                pipeline.streamsChanged(userId, itemId);
                tally.streamsReread++;
            }
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.user_failed user={} exception={}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Under the row lock: item guard, consent expiry when given (an absent one never erases a
     * PENDING_DISCONNECT date), sync reason, and the INIT stamp for SEARCHING_24H. Null = no sync.
     */
    private SyncReason prepare(Long userId, String itemId, LocalDateTime consentExpiresAt, LocalDateTime nowET) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || !itemId.equals(user.getPlaidItemId())) {
            logger.debug("plaid.stale_item_event source=reconcile user={}", userId);
            return null;
        }
        if (consentExpiresAt != null) {
            user.setPlaidDisconnectAt(consentExpiresAt);
        }
        SyncReason reason = syncReasonFor(user);
        if (reason == SyncReason.INIT) {
            scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)
                    .filter(schedule -> schedule.getDetectionStartedAt() == null)
                    .ifPresent(schedule -> {
                        schedule.setDetectionStartedAt(nowET);
                        scheduleRepository.save(schedule);
                    });
        }
        userRepository.save(user);
        return reason;
    }

    /** R4b: INIT for a consented-or-unknown OK Item with no cursor; RECONCILE for one with a cursor. */
    private static SyncReason syncReasonFor(User user) {
        if (Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            return null;
        }
        String cursor = user.getPlaidSyncCursor();
        if (cursor == null || cursor.isBlank()) {
            return user.getPlaidItemStatus() == PlaidItemStatus.OK ? SyncReason.INIT : null;
        }
        return SyncReason.RECONCILE;
    }

    /** Routed as T14 routes an ITEM_ERROR (spec 2.1 to 5.7); the other classes write nothing tonight. */
    private void routeItemError(Long userId, String itemId, String errorCode, PlaidErrorClass errorClass) {
        logger.debug("paycheck.reconcile.item_get user={} class={} code={}", userId, errorClass, errorCode);
        switch (errorClass) {
            case CONSENT -> itemStateService.onConsentRequired(userId, itemId);
            case ITEM_BROKEN -> {
                if (REVOKED_CODES.contains(errorCode)) {
                    itemStateService.onRevoked(userId, itemId, errorCode);
                } else {
                    itemStateService.onLoginRequired(userId, itemId, errorCode);
                }
            }
            case NOT_SUPPORTED -> itemStateService.onNotSupported(userId, itemId);
            case SYNC_LIMIT -> itemStateService.onSyncLimit(userId, itemId);
            default -> {
                // Nothing to write; the user is retried tomorrow night.
            }
        }
    }
}
```

Keep: no class-level `@Transactional`; finders in a read-only template (T12 is MANDATORY); an `/item/get` error ends that user's night; consent FALSE gets the read (spec 5.6) but no sync (spec 2.5 step 1); `catch (RuntimeException e)` only, so a test's `AssertionError` is never swallowed.

- [ ] **Step 5: Run the service tests**

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckReconcileServiceTest' --console=plain`, then, still in `backend/`, read the counts from the XML (never the console):

```
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PaycheckReconcileServiceTest.xml
```

Expected: `BUILD FAILED` and `tests="15" skipped="0" failures="0" errors="1"`: the one error is `reconcileSourcesNeverWriteStatusModeOrHold`, a `NoSuchFileException` on `PaycheckReconcileScheduler.java`, which Step 8 creates (Step 9 re-runs this class at 0 errors).

- [ ] **Step 6: Failing tests for the scheduler**

Create `backend/src/test/java/com/investingapp/backend/scheduler/PaycheckReconcileSchedulerTest.java`:

```java
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
```

- [ ] **Step 7: Run it and watch it fail**

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.PaycheckReconcileSchedulerTest' --console=plain`.

Expected: `compileTestJava` fails with `error: cannot find symbol` naming `class PaycheckReconcileScheduler`, then `BUILD FAILED`.

- [ ] **Step 8: The scheduler**

Create `backend/src/main/java/com/investingapp/backend/scheduler/PaycheckReconcileScheduler.java`:

```java
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The 22:30 ET reconcile cron (spec 5.6, 11): scheduler gate, master flag (spec 12.1), own lock, one
 * Eastern reading. RECONCILE_RAN on every run that took the lock feeds T05's RECONCILE_LATE check.
 */
@Component
public class PaycheckReconcileScheduler {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckReconcileScheduler.class);

    /** Longer than the 50-minute dispatch window plus phase A. */
    static final Duration LEASE = Duration.ofMinutes(60);

    private final PaycheckReconcileService reconcileService;
    private final PaycheckAutopilotProperties props;
    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final OpsAlertService opsAlertService;

    public PaycheckReconcileScheduler(PaycheckReconcileService reconcileService,
            PaycheckAutopilotProperties props,
            SchedulerGate schedulerGate,
            JobLockService jobLockService,
            OpsAlertService opsAlertService) {
        this.reconcileService = reconcileService;
        this.props = props;
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.opsAlertService = opsAlertService;
    }

    @Scheduled(cron = "0 30 22 * * *", zone = "America/New_York")
    public void run() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!props.isEnabled()) {
            logger.info("paycheck.reconcile.skipped reason=disabled");
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.PAYCHECK_RECONCILE, LEASE)) {
            logger.warn("paycheck.reconcile.skipped reason=lock_not_taken lock={}", JobLocks.PAYCHECK_RECONCILE);
            return;
        }
        LocalDateTime nowET = EasternTime.now();
        LocalDate todayET = nowET.toLocalDate();
        ReconcileReport report = null;
        try {
            report = reconcileService.run(todayET, nowET);
            logger.info("paycheck.reconcile.done {}", report);
        } catch (RuntimeException e) {
            logger.error("alert.paycheck.reconcile.threw exception={}", e.getClass().getSimpleName(), e);
            opsAlertService.raise(OpsAlerts.RECONCILE_THREW, PaycheckReconcileService.ALERT_SUBJECT, "nightly reconcile threw", Map.of("exception", e.getClass().getSimpleName()));
        } finally {
            if (report != null) {
                opsAlertService.raise(OpsAlerts.RECONCILE_RAN, PaycheckReconcileService.ALERT_SUBJECT,
                        "nightly reconcile completed", report.toContext());
            } else {
                opsAlertService.raise(OpsAlerts.RECONCILE_RAN, PaycheckReconcileService.ALERT_SUBJECT,
                        "nightly reconcile did not complete", Map.of("completed", false));
            }
            jobLockService.release(JobLocks.PAYCHECK_RECONCILE);
        }
    }
}
```

The `raise(` sits on the line right after `logger.error("alert.`, on one line, for `AlertSitesRoutedTest`. The stack trace goes to the ERROR log only, never into the alert; `ReconcileReport.toString()` is counts and flags only.

- [ ] **Step 9: Run the scheduler tests**

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.PaycheckReconcileSchedulerTest' --console=plain`, then, still in `backend/`:

```
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.scheduler.PaycheckReconcileSchedulerTest.xml
```

Expected: `BUILD SUCCESSFUL` and `tests="7" skipped="0" failures="0" errors="0"`.

Then re-run the service class (Step 5 command; its `reconcileSourcesNeverWriteStatusModeOrHold` now reads the scheduler source too). Expected: `tests="15" skipped="0" failures="0" errors="0"`.

- [ ] **Step 10: The two source-walk guards still pass**

From the repository root, one class at a time:

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml
(cd backend && ./gradlew test --tests 'com.investingapp.backend.AlertSitesRoutedTest' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.AlertSitesRoutedTest.xml
```

Expected: the Step 1 `tests=` numbers with `skipped="0" failures="0" errors="0"`. A failure here means a hold, status or mode write crept into this task's code: remove it, never widen an allowlist.

- [ ] **Step 11: Boot the context and run the whole suite on a scratch MySQL (the bean wiring of a 14-argument constructor)**

`BackendApplicationTests` is `@RequiresCiDatabase`: without a database it SKIPS under a green console, so read the XML. Run the block as ONE non-interactive shell invocation from the repository root. It reads the MySQL user and password from the gitignored `application-local.properties` into variables without printing them (never echo, cat or log them) and uses a throwaway schema, never `investing_dev`:

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_scratch_t15; CREATE DATABASE fred_scratch_t15"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_scratch_t15?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER" SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook' PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth' PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t, "skipped="s, "failures="f, "errors="e}'
db -e "DROP DATABASE fred_scratch_t15"
```

Expected: `tests="1" skipped="0" failures="0" errors="0"` for `BackendApplicationTests` (`skipped="1"` means the database was not reached and proves nothing; a failure naming either new class is a missing bean from an earlier task), then a total with `skipped=0 failures=0 errors=0` (this task's two classes add 22 tests). `cleanTest`: Gradle does not treat environment variables as test inputs.

- [ ] **Step 12: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (stage nothing you did not write; never `application-local.properties` or `ITPM/agent-memory/*`), then:

```
git add backend/src/main/java/com/investingapp/backend/service/PaycheckReconcileService.java backend/src/main/java/com/investingapp/backend/scheduler/PaycheckReconcileScheduler.java backend/src/main/java/com/investingapp/backend/service/JobLocks.java backend/src/main/java/com/investingapp/backend/repository/UserRepository.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java backend/src/test/java/com/investingapp/backend/service/PaycheckReconcileServiceTest.java backend/src/test/java/com/investingapp/backend/scheduler/PaycheckReconcileSchedulerTest.java
git commit -m "feat(paycheck): 22:30 ET reconcile with misses, retention, rejected Items and cursor init"
```

### Verification

Run each class on its own and read the XML (never the console):

| Command | Expected XML line |
|---|---|
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckReconcileServiceTest' --console=plain` | `tests="15" skipped="0" failures="0" errors="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.scheduler.PaycheckReconcileSchedulerTest' --console=plain` | `tests="7" skipped="0" failures="0" errors="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain` | the Step 1 `tests=` number, `skipped="0" failures="0" errors="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.AlertSitesRoutedTest' --console=plain` | the Step 1 `tests=` number, `skipped="0" failures="0" errors="0"` |
| Step 11 scratch-MySQL run of `com.investingapp.backend.BackendApplicationTests` | `tests="1" skipped="0" failures="0" errors="0"` |
| Step 11 whole-suite total on the same schema | `skipped=0 failures=0 errors=0` |

The 13 tests the contract names are present by name; 9 more pin item-error routing, transaction boundaries, the deadline, the source scan and the scheduler's lock and alerts.

### Produces

- `JobLocks.PAYCHECK_RECONCILE = "paycheck.reconcile"` (lease `Duration.ofMinutes(60)`, `PaycheckReconcileScheduler.LEASE`).
- `OpsAlerts.RECONCILE_TRUNCATED = "paycheck.reconcile.truncated"`, `OpsAlerts.RECONCILE_THREW = "paycheck.reconcile.threw"`; this task also raises T05's `OpsAlerts.RECONCILE_RAN` (subject `nightly`) on every scheduled run that took the lock.
- `UserRepository.findByPlaidAccessTokenIsNotNullAndAccountStatus(String)` (`List<User>`), `UserRepository.findIdsWithPlaidToken()` (`List<Long>`, `@Query("SELECT u.id FROM User u WHERE u.plaidAccessToken IS NOT NULL")`).
- `PaycheckReconcileScheduler(PaycheckReconcileService, PaycheckAutopilotProperties, SchedulerGate, JobLockService, OpsAlertService)`, `run()` with `@Scheduled(cron = "0 30 22 * * *", zone = "America/New_York")`: scheduler gate, returns before phase A when `!props.isEnabled()`, `tryAcquire(JobLocks.PAYCHECK_RECONCILE, Duration.ofMinutes(60))`, one `EasternTime.now()` reading for `nowET` and `todayET`, `RECONCILE_THREW` on a throw, `RECONCILE_RAN` on every run that took the lock, `release` in `finally`.
- `PaycheckReconcileService(UserRepository, InvestmentScheduleRepository, PaycheckEventRepository, PaycheckCycleService, PaycheckSyncPipeline, PlaidItemStateService, PlaidItemLifecycleService, PlaidGateway, PlaidOutageBreaker, PlaidApi, EncryptionService, OpsAlertService, PaycheckAutopilotProperties, PlatformTransactionManager)`.
- `PaycheckReconcileService.ReconcileReport(int candidates, int initialized, int synced, int streamsReread, int evaluated, int misses, int stale, int retired, int rejectedItemsQueued, int eventsPurged, boolean truncated, boolean aborted)` with `empty()` and `toContext()`.
- `PaycheckReconcileService.run(LocalDate todayET, LocalDateTime nowET)` and `PaycheckReconcileService.runForUser(Long userId, LocalDate todayET)`, both returning `ReconcileReport` and both returning `ReconcileReport.empty()` with no reads when `paycheck.autopilot.enabled=false`.
- `PaycheckReconcileService.CUTOFF = LocalTime.of(23, 20)`, `MANUAL_RUN_BUDGET = Duration.ofMinutes(50)`, `STREAMS_MAX_AGE = Duration.ofHours(24)`, `EVENT_RETENTION_MONTHS = 24`, `ALERT_SUBJECT = "nightly"`; package-private `static LocalDateTime deadline(LocalDate todayET, LocalDateTime startET)`.

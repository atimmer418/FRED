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

package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.plaid.PlaidOutageBreaker;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PaycheckEventService.ApplyResult;
import com.investingapp.backend.service.PaycheckEventService.SyncBatch;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.support.RecordingTransactionManager;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.model.TransactionsUpdateStatus;
import com.plaid.client.request.PlaidApi;
import jakarta.persistence.EntityManager;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlaidTransactionsSyncService: the spec 2.5 cursor protocol. Plaid is a mocked PlaidApi behind the
 * REAL PlaidGateway (T06), so error classification is the production classifier. Every
 * /transactions/sync call returns one shared mocked Call whose execute() pops the next queued
 * Response and records whether a transaction was open at that moment. The transaction manager is a
 * RecordingTransactionManager (T06): a Mockito transaction manager binds nothing, so an "outside a
 * transaction" assertion against it could never fail.
 */
class PlaidTransactionsSyncServiceTest {

    private static final Long USER_ID = 7L;
    private static final String ITEM = "item-A";
    private static final String MUTATION = "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PlaidApi plaidApi = mock(PlaidApi.class);
    private final PaycheckEventService eventService = mock(PaycheckEventService.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RecordingTransactionManager txManager = new RecordingTransactionManager();
    @SuppressWarnings("unchecked")
    private final Call<TransactionsSyncResponse> syncCall = mock(Call.class);

    private final Deque<Response<TransactionsSyncResponse>> responses = new ArrayDeque<>();
    private final List<TransactionsSyncRequest> requests = new ArrayList<>();
    private final List<Boolean> txOpenDuringPlaidCall = new ArrayList<>();
    private final List<Boolean> txOpenDuringApply = new ArrayList<>();

    private User user;
    private PlaidTransactionsSyncService service;

    @BeforeEach
    void setUp() throws IOException {
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.decrypt(any())).thenAnswer(i -> "raw-" + i.getArgument(0));
        when(props.getDaysRequested()).thenReturn(365);
        PlaidGateway gateway = new PlaidGateway(plaidApi, mock(PlaidOutageBreaker.class), mock(OpsAlertService.class));
        service = new PlaidTransactionsSyncService(userRepository, plaidApi, gateway, encryption, eventService, props, txManager);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);

        user = row(ITEM, null);
        // Same instance from both reads, as under open-in-view on a request thread.
        when(userRepository.findById(USER_ID)).thenAnswer(i -> Optional.of(user));
        when(userRepository.findByIdForUpdate(USER_ID)).thenAnswer(i -> Optional.of(user));
        when(plaidApi.transactionsSync(any())).thenAnswer(i -> {
            requests.add(i.getArgument(0));
            return syncCall;
        });
        when(syncCall.execute()).thenAnswer(i -> {
            txOpenDuringPlaidCall.add(txManager.open);
            Response<TransactionsSyncResponse> next = responses.poll();
            if (next == null) {
                throw new AssertionError("unexpected extra /transactions/sync call");
            }
            return next;
        });
        when(eventService.applySyncBatch(eq(USER_ID), any(), any())).thenAnswer(i -> {
            txOpenDuringApply.add(txManager.open);
            return new ApplyResult(List.of(), List.of(), List.of(), 0);
        });
    }

    private static User row(String itemId, String cursor) {
        User row = new User();
        row.setId(USER_ID);
        row.setPlaidItemId(itemId);
        row.setPlaidAccessToken("ENC:token");
        row.setPlaidItemStatus(PlaidItemStatus.OK);
        row.setPlaidSyncCursor(cursor);
        return row;
    }

    private static TransactionsSyncResponse page(String nextCursor, boolean hasMore, TransactionsUpdateStatus status,
            Transaction... added) {
        return new TransactionsSyncResponse()
                .added(new ArrayList<>(Arrays.asList(added)))
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .transactionsUpdateStatus(status)
                .requestId("req-ok");
    }

    private static Transaction deposit(String transactionId, String pendingTransactionId) {
        return new Transaction().transactionId(transactionId).pendingTransactionId(pendingTransactionId)
                .pending(false).accountId("acc-1").amount(-2418.0).date(LocalDate.of(2026, 10, 30)).name("ACME PAYROLL");
    }

    private void ok(TransactionsSyncResponse body) {
        responses.add(Response.success(body));
    }

    private void plaidError(String errorCode) {
        responses.add(Response.error(400, ResponseBody.create(
                "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"" + errorCode + "\",\"request_id\":\"req-err\"}",
                MediaType.parse("application/json"))));
    }

    private SyncBatch appliedBatch() {
        ArgumentCaptor<SyncBatch> captor = ArgumentCaptor.forClass(SyncBatch.class);
        verify(eventService).applySyncBatch(eq(USER_ID), captor.capture(), any());
        return captor.getValue();
    }

    private void assertNothingPersisted(String expectedCursor) {
        verify(eventService, never()).applySyncBatch(any(), any(), any());
        verify(userRepository, never()).save(any());
        assertThat(user.getPlaidSyncCursor()).isEqualTo(expectedCursor);
        assertThat(user.getPlaidLastSyncAt()).isNull();
        assertThat(user.getPlaidTransactionsUpdateStatus()).isNull();
        assertThat(user.getPlaidHistoryComplete()).isFalse();
    }

    @Test
    void staleItemNotFoundIgnored() {
        assertThat(service.syncItem(USER_ID, "item-OLD", SyncReason.WEBHOOK).status()).isEqualTo(SyncStatus.REFUSED_STALE_ITEM);

        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.syncItem(99L, ITEM, SyncReason.WEBHOOK).status()).isEqualTo(SyncStatus.REFUSED_STALE_ITEM);

        user.setPlaidAccessToken(null);
        assertThat(service.syncItem(USER_ID, ITEM, SyncReason.RECONCILE).status()).isEqualTo(SyncStatus.REFUSED_STALE_ITEM);

        assertThat(requests).isEmpty();
        verify(userRepository, never()).findByIdForUpdate(any());
        assertNothingPersisted(null);
    }

    @Test
    void refusesConsentFalseAndBrokenItemStatusesWithoutCallingPlaid() {
        user.setPlaidTransactionsConsented(false);
        assertThat(service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK).status()).isEqualTo(SyncStatus.REFUSED_CONSENT);

        user.setPlaidTransactionsConsented(null);
        for (PlaidItemStatus broken : List.of(PlaidItemStatus.LOGIN_REQUIRED, PlaidItemStatus.REVOKED,
                PlaidItemStatus.REMOVED, PlaidItemStatus.SYNC_LIMIT, PlaidItemStatus.NOT_SUPPORTED)) {
            user.setPlaidItemStatus(broken);
            assertThat(service.syncItem(USER_ID, ITEM, SyncReason.RECONCILE).status())
                    .as(broken.name()).isEqualTo(SyncStatus.REFUSED_ITEM_STATUS);
        }

        assertThat(requests).isEmpty();
        assertNothingPersisted(null);
    }

    @Test
    void pendingDisconnectStillSyncs() {
        user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        ok(page("c1", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE));

        assertThat(service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK).status()).isEqualTo(SyncStatus.APPLIED);
        assertThat(user.getPlaidSyncCursor()).isEqualTo("c1");
    }

    @Test
    void firstSyncOnAnyPathSendsDaysRequested() {
        for (SyncReason reason : SyncReason.values()) {
            ok(page("", false, TransactionsUpdateStatus.NOT_READY));
            service.syncItem(USER_ID, ITEM, reason);
        }

        assertThat(requests).hasSize(SyncReason.values().length).allSatisfy(request -> {
            assertThat(request.getAccessToken()).isEqualTo("raw-ENC:token");
            assertThat(request.getCursor()).isNull();
            assertThat(request.getCount()).isEqualTo(500);
            assertThat(request.getOptions().getDaysRequested()).isEqualTo(365);
            assertThat(request.getOptions().getIncludeOriginalDescription()).isTrue();
            assertThat(request.getOptions().getPersonalFinanceCategoryVersion()).isEqualTo(PersonalFinanceCategoryVersion.V2);
            assertThat(request.getOptions().getAccountId()).isNull(); // never scoped to one account (spec 2.5)
        });
    }

    @Test
    void emptyFirstCallPersistsNoCursor() {
        ok(page("", false, TransactionsUpdateStatus.NOT_READY));

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.INIT);

        assertThat(outcome).isEqualTo(SyncOutcome.of(SyncStatus.NOT_INITIALIZED));
        verify(userRepository, never()).findByIdForUpdate(any());
        assertNothingPersisted(null);
    }

    @Test
    void appliesWholeBufferOnceAndPersistsSyncState() {
        user.setPlaidSyncCursor("c0");
        ok(page("c1", true, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));
        ok(page("c2", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t2", null)));
        ApplyResult result = new ApplyResult(List.of(11L, 12L), List.of(), List.of(), 0);
        when(eventService.applySyncBatch(eq(USER_ID), any(), any())).thenReturn(result);

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome).isEqualTo(new SyncOutcome(SyncStatus.APPLIED, false, result, null));
        assertThat(requests).extracting(TransactionsSyncRequest::getCursor).containsExactly("c0", "c1");
        assertThat(requests).allSatisfy(r -> assertThat(r.getOptions().getDaysRequested()).isEqualTo(365));
        assertThat(appliedBatch().added()).extracting(Transaction::getTransactionId).containsExactly("t1", "t2");
        assertThat(user.getPlaidSyncCursor()).isEqualTo("c2");
        assertThat(user.getPlaidLastSyncAt()).isNotNull();
        assertThat(user.getPlaidTransactionsUpdateStatus()).isEqualTo("INITIAL_UPDATE_COMPLETE");
        assertThat(user.getPlaidHistoryComplete()).isFalse();
        verify(entityManager).refresh(user);
        verify(userRepository).save(user);
    }

    @Test
    void historyJustCompletedFlipsOnlyOnce() {
        ok(page("c1", false, TransactionsUpdateStatus.HISTORICAL_UPDATE_COMPLETE));
        SyncOutcome first = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);
        ok(page("c2", false, TransactionsUpdateStatus.HISTORICAL_UPDATE_COMPLETE));
        SyncOutcome second = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(first.status()).isEqualTo(SyncStatus.APPLIED);
        assertThat(first.historyJustCompleted()).isTrue();
        assertThat(second.status()).isEqualTo(SyncStatus.APPLIED);
        assertThat(second.historyJustCompleted()).isFalse();
        assertThat(user.getPlaidHistoryComplete()).isTrue();
        assertThat(user.getPlaidSyncCursor()).isEqualTo("c2");
    }

    @Test
    void postedWithoutPendingIdStillConfirms() {
        // Confirming the cycle is T12's; this task's part is handing the posted row on unfiltered.
        ok(page("c1", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t-posted", null)));
        ApplyResult result = new ApplyResult(List.of(41L), List.of(), List.of(), 0);
        when(eventService.applySyncBatch(eq(USER_ID), any(), any())).thenReturn(result);

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        Transaction handedOn = appliedBatch().added().get(0);
        assertThat(handedOn.getPendingTransactionId()).isNull();
        assertThat(handedOn.getPending()).isFalse();
        assertThat(outcome.applied().newPostedInflowEventIds()).containsExactly(41L);
    }

    @Test
    void plaidCallRunsOutsideTransaction() {
        user.setPlaidSyncCursor("c0");
        ok(page("c1", true, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));
        ok(page("c2", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t2", null)));

        assertThat(service.syncItem(USER_ID, ITEM, SyncReason.RECONCILE).status()).isEqualTo(SyncStatus.APPLIED);

        assertThat(txOpenDuringPlaidCall).containsExactly(false, false);
        assertThat(txOpenDuringApply).containsExactly(true);
        assertThat(txManager.open).isFalse();
    }

    @Test
    void applyFailureReturnsFailedAndWritesNoSyncState() {
        ok(page("c1", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));
        when(eventService.applySyncBatch(eq(USER_ID), any(), any()))
                .thenThrow(new DataIntegrityViolationException("Data too long for column 'raw_descriptor'"));

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome).isEqualTo(SyncOutcome.of(SyncStatus.FAILED));
        assertThat(txManager.open).isFalse();
        assertThat(user.getPlaidSyncCursor()).isNull();
        assertThat(user.getPlaidLastSyncAt()).isNull();
        verify(userRepository, never()).save(any());
    }

    @Test
    void mutationRestartsFromOriginalCursor() {
        user.setPlaidSyncCursor("c0");
        ok(page("c1", true, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t-discarded", null)));
        plaidError(MUTATION);
        ok(page("c2", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t-kept", null)));

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.status()).isEqualTo(SyncStatus.APPLIED);
        assertThat(requests).extracting(TransactionsSyncRequest::getCursor).containsExactly("c0", "c1", "c0");
        assertThat(appliedBatch().added()).extracting(Transaction::getTransactionId).containsExactly("t-kept");
        assertThat(user.getPlaidSyncCursor()).isEqualTo("c2");
    }

    @Test
    void fourthMutationReturnsRetryLater() {
        user.setPlaidSyncCursor("c0");
        for (int i = 0; i <= PlaidTransactionsSyncService.MAX_RESTARTS; i++) {
            plaidError(MUTATION);
        }

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.status()).isEqualTo(SyncStatus.RETRY_LATER);
        assertThat(outcome.error().errorClass()).isEqualTo(PlaidErrorClass.MUTATION);
        assertThat(requests).hasSize(4).extracting(TransactionsSyncRequest::getCursor).containsOnly("c0");
        verify(userRepository, never()).findByIdForUpdate(any());
        assertNothingPersisted("c0");
    }

    @Test
    void itemLimitReturnsItemErrorWritesNoHold() {
        plaidError("TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED");

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.RECONCILE);

        assertThat(outcome.status()).isEqualTo(SyncStatus.ITEM_ERROR);
        assertThat(outcome.error().errorCode()).isEqualTo("TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED");
        assertThat(outcome.error().errorClass()).isEqualTo(PlaidErrorClass.SYNC_LIMIT);
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        verify(userRepository, never()).findByIdForUpdate(any());
        assertNothingPersisted(null);
        // R2: this class cannot write a hold, Item state or an event; T13 owns SYNC_LIMIT.
        assertThat(Arrays.stream(PlaidTransactionsSyncService.class.getDeclaredFields())
                .map(Field::getType).map(Class::getSimpleName))
                .doesNotContain("InvestmentScheduleRepository", "PlaidItemStateService", "AnalyticsOutboxService");
    }

    @Test
    void consentErrorWritesNothing() {
        plaidError("ADDITIONAL_CONSENT_REQUIRED");

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.INIT);

        assertThat(outcome.status()).isEqualTo(SyncStatus.ITEM_ERROR);
        assertThat(outcome.error().errorClass()).isEqualTo(PlaidErrorClass.CONSENT);
        assertThat(user.getPlaidTransactionsConsented()).isNull(); // T13's onConsentRequired writes FALSE
        verify(userRepository, never()).findByIdForUpdate(any());
        assertNothingPersisted(null);
    }

    @Test
    void institution400IsTransient() {
        plaidError("INSTITUTION_DOWN");

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.status()).isEqualTo(SyncStatus.ITEM_ERROR);
        assertThat(outcome.error().errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(outcome.error().httpStatus()).isEqualTo(400);
        assertNothingPersisted(null);
    }

    @Test
    void errorOnSecondPageDiscardsTheBuffer() {
        user.setPlaidSyncCursor("c0");
        ok(page("c1", true, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));
        plaidError("ITEM_LOGIN_REQUIRED");

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome.status()).isEqualTo(SyncStatus.ITEM_ERROR);
        assertThat(outcome.error().errorClass()).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertNothingPersisted("c0");
    }

    @Test
    void cursorNotPersistedWhenItemChangedMidSync() {
        user.setPlaidSyncCursor("c0");
        User relinked = row("item-B", null);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(relinked));
        ok(page("c1", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.WEBHOOK);

        assertThat(outcome).isEqualTo(SyncOutcome.of(SyncStatus.REFUSED_STALE_ITEM));
        assertThat(relinked.getPlaidSyncCursor()).isNull();
        assertThat(relinked.getPlaidLastSyncAt()).isNull();
        assertNothingPersisted("c0");
    }

    @Test
    void lockedRowIsRefreshedBeforeTheItemCheck() {
        // Open-in-view: the locked read returns the instance findById already loaded; only refresh
        // shows that a relink committed in between.
        doAnswer(i -> {
            user.setPlaidItemId("item-B");
            return null;
        }).when(entityManager).refresh(user);
        ok(page("c1", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.MANUAL);

        assertThat(outcome).isEqualTo(SyncOutcome.of(SyncStatus.REFUSED_STALE_ITEM));
        assertNothingPersisted(null);
    }

    @Test
    void cursorMovedByAnotherSyncDropsThisBuffer() {
        user.setPlaidSyncCursor("c0");
        User advanced = row(ITEM, "c9");
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(advanced));
        ok(page("c1", false, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE, deposit("t1", null)));

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.RECONCILE);

        assertThat(outcome).isEqualTo(SyncOutcome.of(SyncStatus.RETRY_LATER));
        assertThat(advanced.getPlaidSyncCursor()).isEqualTo("c9");
        assertNothingPersisted("c0");
    }

    @Test
    void runawayPaginationStopsAtThePageCap() {
        for (int i = 0; i < PlaidTransactionsSyncService.MAX_PAGES; i++) {
            ok(page("c" + i, true, TransactionsUpdateStatus.INITIAL_UPDATE_COMPLETE));
        }

        SyncOutcome outcome = service.syncItem(USER_ID, ITEM, SyncReason.INIT);

        assertThat(outcome).isEqualTo(SyncOutcome.of(SyncStatus.FAILED));
        assertThat(requests).hasSize(PlaidTransactionsSyncService.MAX_PAGES);
        assertNothingPersisted(null);
    }
}

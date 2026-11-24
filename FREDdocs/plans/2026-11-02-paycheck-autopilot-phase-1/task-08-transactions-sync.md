### Task 08: transactions-sync

Builds `PlaidTransactionsSyncService.syncItem`, the only caller of Plaid `/transactions/sync` (spec 2.5). It refuses a stale Item, a broken Item and a refused consent without calling Plaid; pages through every update since the stored cursor with `count 500`, `include_original_description`, PFC `V2` and `days_requested` on every request; restarts from the original cursor on `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION` at most three times; and applies the whole buffer in ONE `TransactionTemplate` under the user row lock, storing the new cursor, `plaid_last_sync_at`, `plaid_transactions_update_status` and `plaid_history_complete` on the locked managed `User` in the same commit. No Plaid HTTP runs inside that transaction. It reports Plaid errors as `ITEM_ERROR` with the parsed `PlaidApiError` and writes nothing on that path: not Item state, not the consent flag, not a hold. `PaycheckSyncPipeline` (T14) is its only production caller and routes the error by class to `PlaidItemStateService` (T13).

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests go in `backend/src/test/java/com/investingapp/backend/service/`. Line numbers are HEAD 137998c and are given only for files no Phase 1 task before this one edits (`UserRepository.java`, `EncryptionService.java`, `EasternTime.java`, `PlaidService.java`), plus one reference into `PlaidItemLifecycleService.java`, which T04 edits (it injects `OpsAlertService` for the `:161` alert site), so that reference is found by its method name `recordConsentAfterLink`. Every line reference here is read-only context; this task edits none of these files. `User.java` is edited by T02, so its anchors are field names.

**Facts this task depends on (restated from the handoff, the rulings and the contract):**

- Nothing in this task moves money, reads or writes an `InvestmentSchedule`, or writes a hold. `InvestmentSchedule.status` (`PENDING_BANK | ACTIVE | PAUSED_BY_USER`) and `hold_reason` are not touched; the deprecated `getIsPaused()` is never called. The class has no `InvestmentScheduleRepository`, `PlaidItemStateService` or `AnalyticsOutboxService` dependency, and a test pins that. It emits no analytics event.
- R2: `TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED` must never become a MANUAL_REVIEW hold in Phase 1. Here it is an `ITEM_ERROR` carrying `PlaidErrorClass.SYNC_LIMIT`; T13's `onSyncLimit` owns the state write and writes no hold.
- Decision 64: the consent flag FALSE is T13's write (`onConsentRequired`), reached through T14's routing. A CONSENT-class error here writes nothing.
- No Plaid HTTP inside a database transaction. The model is `PlaidItemLifecycleService.recordConsentAfterLink` (lines 111-141 at HEAD; shifted by T04, so find it by name): call Plaid first, then a `TransactionTemplate` that takes `userRepository.findByIdForUpdate(userId)` (`UserRepository.java:81-83`) and re-checks `plaidItemId` before writing.
- Open-in-view is on in this app (the comment above `PlaidService.java:172`, `entityManager.refresh(appUser);`). On a request thread (T18's admin `sync`, T19's dev sandbox verb) the outside `findById` leaves the `User` managed in the request's `EntityManager`, and Hibernate takes the row lock on a managed instance without re-reading it. So the locked row is refreshed before the item and cursor checks, exactly as the exchange does.
- `ddl-auto=validate`: this task adds no column, no migration and no property. It reads `paycheck.autopilot.days-requested` through T03's `PaycheckAutopilotProperties.getDaysRequested()` (default 365), whose local-properties note T03 already gave. **No local-properties edit is needed for this task.**
- Logs at INFO and above carry ids, codes, closed-vocabulary states and counts. Never the access token, the cursor, a descriptor, a payer name, an amount, a date, `getMessage()` of an exception (a JDBC "Data too long" message can quote a descriptor) or a `toString()` of a Plaid model (it prints every field).
- The runnable rule stays in `InvestmentSchedule.isReadyForInvestment(todayET)` and this task does not touch it; T03's `PhaseOneMoneyInvarianceTest.phaseOneSourcesNeverCallSetStatusOrSetMode` already walks `service/Plaid*`, which includes this file.

**Files:**
- Create: `.../service/PlaidTransactionsSyncService.java`
- Test: create `backend/src/test/java/com/investingapp/backend/service/PlaidTransactionsSyncServiceTest.java`
- Modify: none. No `UserRepository` change (contract), no property file, no migration, no analytics file.

**Interfaces:**
- Consumes:
  - Phase 0, at HEAD: `User.getPlaidItemId()`, `getPlaidAccessToken()`, `getPlaidItemStatus()`, `getPlaidTransactionsConsented()` (Lombok `@Getter @Setter` on the class); `PlaidItemStatus {OK, LOGIN_REQUIRED, PENDING_DISCONNECT, REVOKED, REMOVED, SYNC_LIMIT, NOT_SUPPORTED}`; `UserRepository.findById(Long)` (JpaRepository) and `Optional<User> findByIdForUpdate(Long)` (`:81-83`); `EncryptionService.decrypt(String)` (`EncryptionService.java:106`); `EasternTime.now()` (`EasternTime.java:28`); `PlaidApi.transactionsSync(TransactionsSyncRequest): Call<TransactionsSyncResponse>` (plaid-java 46.0.0).
  - T02: `User.getPlaidSyncCursor()/setPlaidSyncCursor(String)`, `getPlaidHistoryComplete()/setPlaidHistoryComplete(Boolean)` (defaults to `false`), `getPlaidTransactionsUpdateStatus()/setPlaidTransactionsUpdateStatus(String)` (VARCHAR 40), `getPlaidLastSyncAt()/setPlaidLastSyncAt(LocalDateTime)`; `util/ColumnText.fit(String value, int width)`.
  - T03: `config/PaycheckAutopilotProperties.getDaysRequested()` (`int`).
  - T04 (test only, mocked into the real gateway): `ops/OpsAlertService`.
  - T06: `plaid/PlaidGateway(PlaidApi, PlaidOutageBreaker, OpsAlertService)` and `<T> PlaidCallResult<T> call(String op, Call<T> call)`; `record PlaidCallResult<T>(T body, PlaidApiError error)` with `ok()`; `record PlaidApiError(String errorType, String errorCode, String errorCodeReason, String requestId, int httpStatus, PlaidErrorClass errorClass)`; `enum PlaidErrorClass {TRANSIENT, RATE_LIMIT, CONSENT, ITEM_BROKEN, NOT_SUPPORTED, SYNC_LIMIT, MUTATION, PERMANENT}`; `plaid/PlaidOutageBreaker` (mocked); test support `support/RecordingTransactionManager` (no-arg constructor, public `boolean open`).
  - T07: nested in `service/PaycheckEventService` (T07 creates no other file that could hold them): `record SyncBatch(List<Transaction> added, List<Transaction> modified, List<RemovedTransaction> removed, List<AccountBase> accounts)`, `record ApplyResult(List<Long> newPostedInflowEventIds, List<Long> removedEventIds, List<Long> modifiedEventIds, int ignored)`, `@Transactional(propagation = MANDATORY) ApplyResult applySyncBatch(Long userId, SyncBatch batch, LocalDateTime nowET)`.
- Produces (all in `com.investingapp.backend.service.PlaidTransactionsSyncService`):
  - `public PlaidTransactionsSyncService(UserRepository userRepository, PlaidApi plaidApi, PlaidGateway plaidGateway, EncryptionService encryptionService, PaycheckEventService paycheckEventService, PaycheckAutopilotProperties props, PlatformTransactionManager transactionManager)`, plus a `@PersistenceContext private EntityManager entityManager` field (the `PlaidService` style; tests set it with `ReflectionTestUtils`).
  - `public enum SyncReason {INIT, WEBHOOK, RECONCILE, MANUAL}`
  - `public enum SyncStatus {APPLIED, NOT_INITIALIZED, REFUSED_CONSENT, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS, REFUSED_DISABLED, REFUSED_EXCLUDED, RETRY_LATER, ITEM_ERROR, FAILED}` (`REFUSED_DISABLED` and `REFUSED_EXCLUDED` are returned by T14's pipeline, never by `syncItem`)
  - `public record SyncOutcome(SyncStatus status, boolean historyJustCompleted, PaycheckEventService.ApplyResult applied, PlaidApiError error)` with `public static SyncOutcome of(SyncStatus)` and `public static SyncOutcome withError(SyncStatus, PlaidApiError)`
  - `public SyncOutcome syncItem(Long userId, String itemId, SyncReason reason)` (must be called outside any transaction)
  - package-private constants `OP = "transactions.sync"`, `PAGE_SIZE = 500`, `MAX_RESTARTS = 3`, `MAX_PAGES = 100`

What each status means, for T14, T15, T16 and T18:

| status | returned when | Plaid called | writes | `applied` | `error` |
|---|---|---|---|---|---|
| `REFUSED_STALE_ITEM` | user gone, no token, `itemId != users.plaid_item_id` at entry, or the same mismatch on the locked re-read | only in the re-read case | none | null | null |
| `REFUSED_ITEM_STATUS` | `plaidItemStatus` is not `OK` and not `PENDING_DISCONNECT` | no | none | null | null |
| `REFUSED_CONSENT` | `plaidTransactionsConsented == FALSE` (NULL is allowed: one sync is the authoritative test, spec 2.3) | no | none | null | null |
| `NOT_INITIALIZED` | the last page's `next_cursor` is null or empty | yes | none | null | null |
| `APPLIED` | the whole buffer was applied and the sync fields stored in one commit | yes | events (T07), the four `users` fields | the event store's result | null |
| `RETRY_LATER` | a fourth `MUTATION` result, or another sync moved the stored cursor while this one paged | yes | none | null | the last `MUTATION` error, or null |
| `ITEM_ERROR` | any non-`MUTATION` Plaid error, on any page | yes | none | null | the parsed error |
| `FAILED` | the token would not decrypt, a 2xx came without a body, the page cap was hit, or the apply transaction threw | maybe | none (rolled back) | null | null |

`historyJustCompleted` is true only on the `APPLIED` sync whose last page said `HISTORICAL_UPDATE_COMPLETE` while `plaidHistoryComplete` was still false; the flag is sticky (only T13's `resetForNewItem` clears it).

- [ ] **Step 1: Pre-flight: confirm the consumed names exist**

This task depends on T02, T03, T04, T06 and T07. From the repository root:

```bash
cd backend
test ! -e src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java && echo "no T08 file yet"
grep -n 'record SyncBatch\|record ApplyResult\|ApplyResult applySyncBatch' src/main/java/com/investingapp/backend/service/PaycheckEventService.java
grep -n 'public PlaidGateway(\|PlaidCallResult<T> call(' src/main/java/com/investingapp/backend/plaid/PlaidGateway.java
grep -n 'boolean ok()' src/main/java/com/investingapp/backend/plaid/PlaidCallResult.java
grep -n 'MUTATION\|SYNC_LIMIT\|CONSENT\|TRANSIENT\|ITEM_BROKEN' src/main/java/com/investingapp/backend/plaid/PlaidErrorClass.java
grep -n 'int getDaysRequested()' src/main/java/com/investingapp/backend/config/PaycheckAutopilotProperties.java
grep -n 'plaidSyncCursor;\|plaidHistoryComplete\|plaidTransactionsUpdateStatus;\|plaidLastSyncAt;' src/main/java/com/investingapp/backend/model/User.java
grep -n 'static String fit(String' src/main/java/com/investingapp/backend/util/ColumnText.java
grep -n 'public .*boolean open' src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java
ls src/main/java/com/investingapp/backend/ops/OpsAlertService.java src/main/java/com/investingapp/backend/plaid/PlaidOutageBreaker.java src/main/java/com/investingapp/backend/plaid/PlaidApiError.java
```

Expected: the first line prints `no T08 file yet`, every grep prints at least one line (`PlaidErrorClass` prints all five names; `User.java` prints four fields), and the `ls` lists all three files (T04's `OpsAlertService` and T06's `PlaidOutageBreaker` are mocked into the real gateway by the test; `PlaidApiError` is in `SyncOutcome`). If any grep prints nothing, stop and report which name is missing: an earlier task did not deliver its contract, and guessing a name here would break T14. If `open` exists but is not `public`, also stop and report: the test lives in the `service` package and must read it.

- [ ] **Step 2: Failing tests, part 1: harness, refusals and the request shape**

Create `backend/src/test/java/com/investingapp/backend/service/PlaidTransactionsSyncServiceTest.java`:

```java
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
}
```

The `Call` mock stubs only `execute()`, which is the one method T06's gateway calls. `assertNothingPersisted` asserts on the very `User` instance both repository reads return, so any field write on any path would show.

- [ ] **Step 3: Failing tests, part 2: the apply path**

Insert these methods before the final closing brace of `PlaidTransactionsSyncServiceTest`:

```java
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
```

- [ ] **Step 4: Failing tests, part 3: errors, restarts and the staleness guard**

Insert these methods before the final closing brace of `PlaidTransactionsSyncServiceTest`:

```java
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
```

The class now holds 20 tests: the spec 13.1 sync cases (`emptyFirstCallPersistsNoCursor`, `mutationRestartsFromOriginalCursor`, `itemLimitReturnsItemErrorWritesNoHold` in place of `itemLimitHoldsManualReview`, `institution400IsTransient`, `postedWithoutPendingIdStillConfirms`, `firstSyncOnAnyPathSendsDaysRequested`, `staleItemNotFoundIgnored`, `cursorNotPersistedWhenItemChangedMidSync`), the contract's `consentErrorWritesNothing` and `plaidCallRunsOutsideTransaction`, and ten more that pin the refusals, the apply path, the restart limit and the three guards. Spec 13.1's `consentRequiredFlipsFlag` is not here: this task writes no consent flag (Decision 64), and the flip is T13's `onConsentRequired` case.

- [ ] **Step 5: Run the tests and watch them fail**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidTransactionsSyncServiceTest' --console=plain
```

Expected: `> Task :compileTestJava FAILED`, with `error: package com.investingapp.backend.service.PlaidTransactionsSyncService does not exist` on the three nested imports (`SyncOutcome`, `SyncReason`, `SyncStatus`; javac reads the missing outer class as a package) and `error: cannot find symbol` with `symbol:   class PlaidTransactionsSyncService` in the class body. Any other compile error means a consumed name drifted; go back to Step 1.

- [ ] **Step 6: Create PlaidTransactionsSyncService**

Create `backend/src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.model.TransactionsUpdateStatus;
import com.plaid.client.request.PlaidApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The only caller of Plaid /transactions/sync (spec 2.5). One call pages through every update since
 * the stored cursor, buffers it, and applies the whole buffer in ONE transaction under the user row
 * lock, storing the new cursor in the same commit, so no page is ever persisted on its own.
 *
 * <p>No Plaid HTTP runs inside a database transaction: every page is fetched first, on the caller's
 * thread, and only then does the write transaction open. This class reports Plaid errors and never
 * writes Item state, the consent flag or a hold, and it never reads or writes an investment
 * schedule; PaycheckSyncPipeline routes an ITEM_ERROR by its PlaidErrorClass to
 * PlaidItemStateService.
 *
 * <p>Logs carry ids, codes, states and counts only: never the access token, the cursor, a
 * descriptor, a payer name, an amount or an exception message (spec 12.3).
 */
@Service
public class PlaidTransactionsSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidTransactionsSyncService.class);

    static final String OP = "transactions.sync";
    /** Plaid's maximum page size. */
    static final int PAGE_SIZE = 500;
    /** Spec 2.5: restart from the original cursor at most this many times, then retry later. */
    static final int MAX_RESTARTS = 3;
    /** Runaway-loop stop: 100 pages of 500 rows is far beyond 365 days of any real account. */
    static final int MAX_PAGES = 100;
    /** users.plaid_transactions_update_status is VARCHAR(40) in V6. */
    private static final int UPDATE_STATUS_WIDTH = 40;

    /** Why a sync ran. INIT is an Item's first sync; MANUAL is an admin, dev or update-mode run. */
    public enum SyncReason { INIT, WEBHOOK, RECONCILE, MANUAL }

    /**
     * What a sync did. REFUSED_DISABLED and REFUSED_EXCLUDED are returned by PaycheckSyncPipeline
     * before it calls syncItem; syncItem itself never returns them.
     */
    public enum SyncStatus {
        APPLIED, NOT_INITIALIZED, REFUSED_CONSENT, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS,
        REFUSED_DISABLED, REFUSED_EXCLUDED, RETRY_LATER, ITEM_ERROR, FAILED
    }

    /**
     * @param historyJustCompleted true only on the sync that first stored HISTORICAL_UPDATE_COMPLETE
     * @param applied the event store's result; non-null exactly when status is APPLIED
     * @param error the parsed Plaid error on ITEM_ERROR, and on a RETRY_LATER after mutation restarts
     */
    public record SyncOutcome(SyncStatus status, boolean historyJustCompleted,
            PaycheckEventService.ApplyResult applied, PlaidApiError error) {

        public static SyncOutcome of(SyncStatus status) {
            return new SyncOutcome(status, false, null, null);
        }

        public static SyncOutcome withError(SyncStatus status, PlaidApiError error) {
            return new SyncOutcome(status, false, null, error);
        }
    }

    /** One pagination run: the whole buffer with the last page's cursor and status, or what stopped it. */
    private record Fetched(SyncOutcome stopped, PaycheckEventService.SyncBatch batch, String nextCursor,
            TransactionsUpdateStatus updateStatus, int pages, int restarts) {

        static Fetched stop(SyncOutcome outcome, int pages, int restarts) {
            return new Fetched(outcome, null, null, null, pages, restarts);
        }
    }

    private final UserRepository userRepository;
    private final PlaidApi plaidApi;
    private final PlaidGateway plaidGateway;
    private final EncryptionService encryptionService;
    private final PaycheckEventService paycheckEventService;
    private final PaycheckAutopilotProperties props;
    private final TransactionTemplate writeTx;

    @PersistenceContext
    private EntityManager entityManager;

    public PlaidTransactionsSyncService(UserRepository userRepository,
            PlaidApi plaidApi,
            PlaidGateway plaidGateway,
            EncryptionService encryptionService,
            PaycheckEventService paycheckEventService,
            PaycheckAutopilotProperties props,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.plaidApi = plaidApi;
        this.plaidGateway = plaidGateway;
        this.encryptionService = encryptionService;
        this.paycheckEventService = paycheckEventService;
        this.props = props;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Sync one Item. Call it outside any transaction: it calls Plaid on this thread and then opens
     * its own write transaction.
     */
    public SyncOutcome syncItem(Long userId, String itemId, SyncReason reason) {
        User user = userRepository.findById(userId).orElse(null);
        Optional<SyncStatus> refused = refusal(user, itemId);
        if (refused.isPresent()) {
            if (refused.get() == SyncStatus.REFUSED_STALE_ITEM) {
                logger.info("plaid.stale_item_event source=sync user_id={} item_id={} reason={}", userId, itemId, reason);
            } else {
                logger.info("plaid.sync.refused user_id={} item_id={} reason={} status={}",
                        userId, itemId, reason, refused.get());
            }
            return SyncOutcome.of(refused.get());
        }
        String originalCursor = user.getPlaidSyncCursor();
        Fetched fetched;
        try {
            fetched = fetch(userId, encryptionService.decrypt(user.getPlaidAccessToken()), originalCursor);
        } catch (RuntimeException e) {
            logger.error("plaid.error op={} user_id={} item_id={} reason={} exception={}",
                    OP, userId, itemId, reason, e.getClass().getSimpleName());
            return SyncOutcome.of(SyncStatus.FAILED);
        }
        if (fetched.stopped() != null) {
            logStop(userId, itemId, reason, fetched);
            return fetched.stopped();
        }
        if (fetched.nextCursor() == null || fetched.nextCursor().isEmpty()) {
            // Spec 2.5: an empty next_cursor means Transactions is not initialized yet; persist nothing.
            logger.info("plaid.sync.not_initialized user_id={} item_id={} reason={} update_status={}",
                    userId, itemId, reason, fetched.updateStatus());
            return SyncOutcome.of(SyncStatus.NOT_INITIALIZED);
        }
        return apply(userId, itemId, reason, originalCursor, fetched);
    }

    /** Spec 2.5 step 1, checked against the row as read before any Plaid call. */
    private static Optional<SyncStatus> refusal(User user, String itemId) {
        if (user == null || itemId == null || !itemId.equals(user.getPlaidItemId())
                || user.getPlaidAccessToken() == null || user.getPlaidAccessToken().isBlank()) {
            return Optional.of(SyncStatus.REFUSED_STALE_ITEM);
        }
        PlaidItemStatus status = user.getPlaidItemStatus();
        if (status != PlaidItemStatus.OK && status != PlaidItemStatus.PENDING_DISCONNECT) {
            return Optional.of(SyncStatus.REFUSED_ITEM_STATUS);
        }
        if (Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            return Optional.of(SyncStatus.REFUSED_CONSENT);
        }
        return Optional.empty();
    }

    /**
     * Page until has_more is false. A MUTATION result discards the buffer and restarts from the
     * cursor this sync started with (the first page of this update, never an empty cursor).
     */
    private Fetched fetch(Long userId, String accessToken, String originalCursor) {
        int pages = 0;
        int restarts = 0;
        while (true) {
            List<Transaction> added = new ArrayList<>();
            List<Transaction> modified = new ArrayList<>();
            List<RemovedTransaction> removed = new ArrayList<>();
            Map<String, AccountBase> accounts = new LinkedHashMap<>();
            String cursor = originalCursor;
            TransactionsUpdateStatus updateStatus = null;
            boolean hasMore = true;
            boolean restart = false;
            while (hasMore) {
                if (pages >= MAX_PAGES) {
                    logger.error("plaid.sync.page_cap user_id={} pages={}", userId, pages);
                    return Fetched.stop(SyncOutcome.of(SyncStatus.FAILED), pages, restarts);
                }
                pages++;
                PlaidCallResult<TransactionsSyncResponse> result =
                        plaidGateway.call(OP, plaidApi.transactionsSync(request(accessToken, cursor)));
                if (!result.ok() || result.body() == null) {
                    PlaidApiError error = result.error();
                    if (error == null) {
                        return Fetched.stop(SyncOutcome.of(SyncStatus.FAILED), pages, restarts);
                    }
                    if (error.errorClass() != PlaidErrorClass.MUTATION) {
                        return Fetched.stop(SyncOutcome.withError(SyncStatus.ITEM_ERROR, error), pages, restarts);
                    }
                    if (restarts >= MAX_RESTARTS) {
                        return Fetched.stop(SyncOutcome.withError(SyncStatus.RETRY_LATER, error), pages, restarts);
                    }
                    restarts++;
                    restart = true;
                    break;
                }
                TransactionsSyncResponse page = result.body();
                added.addAll(orEmpty(page.getAdded()));
                modified.addAll(orEmpty(page.getModified()));
                removed.addAll(orEmpty(page.getRemoved()));
                for (AccountBase account : orEmpty(page.getAccounts())) {
                    accounts.put(account.getAccountId(), account);
                }
                updateStatus = page.getTransactionsUpdateStatus();
                cursor = page.getNextCursor();
                hasMore = Boolean.TRUE.equals(page.getHasMore());
            }
            if (!restart) {
                PaycheckEventService.SyncBatch batch = new PaycheckEventService.SyncBatch(
                        added, modified, removed, new ArrayList<>(accounts.values()));
                return new Fetched(null, batch, cursor, updateStatus, pages, restarts);
            }
        }
    }

    /** days_requested goes on EVERY request on every path: whichever call first succeeds locks the depth. */
    private TransactionsSyncRequest request(String accessToken, String cursor) {
        TransactionsSyncRequest request = new TransactionsSyncRequest()
                .accessToken(accessToken)
                .count(PAGE_SIZE)
                .options(new TransactionsSyncRequestOptions()
                        .includeOriginalDescription(true)
                        .personalFinanceCategoryVersion(PersonalFinanceCategoryVersion.V2)
                        .daysRequested(props.getDaysRequested()));
        if (cursor != null && !cursor.isEmpty()) {
            request.cursor(cursor);
        }
        return request;
    }

    private SyncOutcome apply(Long userId, String itemId, SyncReason reason, String originalCursor, Fetched fetched) {
        LocalDateTime nowET = EasternTime.now();
        SyncOutcome outcome;
        try {
            outcome = writeTx.execute(status -> applyLocked(userId, itemId, reason, originalCursor, fetched, nowET));
        } catch (RuntimeException e) {
            // The class name only: a JDBC message can quote the descriptor that failed to fit.
            logger.error("plaid.sync.apply_failed user_id={} item_id={} reason={} exception={}",
                    userId, itemId, reason, e.getClass().getSimpleName());
            return SyncOutcome.of(SyncStatus.FAILED);
        }
        if (outcome == null) {
            return SyncOutcome.of(SyncStatus.FAILED);
        }
        if (outcome.status() == SyncStatus.APPLIED) {
            PaycheckEventService.SyncBatch batch = fetched.batch();
            logger.info("plaid.sync.applied user_id={} item_id={} reason={} pages={} restarts={} added={} modified={}"
                            + " removed={} new_inflows={} update_status={} history_just_completed={}",
                    userId, itemId, reason, fetched.pages(), fetched.restarts(), batch.added().size(),
                    batch.modified().size(), batch.removed().size(),
                    outcome.applied() == null ? 0 : outcome.applied().newPostedInflowEventIds().size(),
                    fetched.updateStatus(), outcome.historyJustCompleted());
        }
        return outcome;
    }

    /** Runs inside writeTx: lock, re-read, re-check the Item and the cursor, apply, store. */
    private SyncOutcome applyLocked(Long userId, String itemId, SyncReason reason, String originalCursor,
            Fetched fetched, LocalDateTime nowET) {
        User locked = userRepository.findByIdForUpdate(userId).orElse(null);
        if (locked != null) {
            // Open-in-view is on: on a request thread findById left this User managed, and Hibernate
            // locks a managed instance without re-reading it (PlaidService refreshes for the same reason).
            entityManager.refresh(locked);
        }
        if (locked == null || !itemId.equals(locked.getPlaidItemId())) {
            logger.info("plaid.stale_item_event source=sync_apply user_id={} item_id={} reason={}", userId, itemId, reason);
            return SyncOutcome.of(SyncStatus.REFUSED_STALE_ITEM);
        }
        if (!Objects.equals(originalCursor, locked.getPlaidSyncCursor())) {
            // Another sync of this Item committed while this one paged. Its cursor stands; this buffer
            // is dropped, and the retry pages on from the newer cursor.
            logger.info("plaid.sync.cursor_moved user_id={} item_id={} reason={}", userId, itemId, reason);
            return SyncOutcome.of(SyncStatus.RETRY_LATER);
        }
        PaycheckEventService.ApplyResult applied = paycheckEventService.applySyncBatch(userId, fetched.batch(), nowET);
        boolean wasComplete = Boolean.TRUE.equals(locked.getPlaidHistoryComplete());
        boolean completeNow = fetched.updateStatus() == TransactionsUpdateStatus.HISTORICAL_UPDATE_COMPLETE;
        // Set on the locked managed entity, never through a @Modifying query, which a later flush of
        // this same entity would silently undo.
        locked.setPlaidSyncCursor(fetched.nextCursor());
        locked.setPlaidLastSyncAt(nowET);
        locked.setPlaidTransactionsUpdateStatus(fetched.updateStatus() == null
                ? null
                : ColumnText.fit(fetched.updateStatus().getValue(), UPDATE_STATUS_WIDTH));
        locked.setPlaidHistoryComplete(wasComplete || completeNow);
        userRepository.save(locked);
        return new SyncOutcome(SyncStatus.APPLIED, !wasComplete && completeNow, applied, null);
    }

    private static void logStop(Long userId, String itemId, SyncReason reason, Fetched fetched) {
        PlaidApiError error = fetched.stopped().error();
        logger.warn("plaid.error op={} user_id={} item_id={} reason={} status={} code={} class={} request_id={}"
                        + " pages={} restarts={}",
                OP, userId, itemId, reason, fetched.stopped().status(),
                error == null ? null : error.errorCode(),
                error == null ? null : error.errorClass(),
                error == null ? null : error.requestId(),
                fetched.pages(), fetched.restarts());
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
```

Notes for the implementer:

- Imports are explicit, never `com.plaid.client.model.*`, so no FRED class name can collide with an SDK model (`PlaidService.java:18` is the wildcard that bites).
- `applySyncBatch` is `MANDATORY` (T07), so it only ever runs inside `writeTx`; the `plaidCallRunsOutsideTransaction` test pins both halves (`false, false` during the two Plaid calls, `true` during the apply).
- The fields are set after `applySyncBatch` returns, so a rollback leaves the in-memory `User` untouched as well as the row.
- `TransactionsUpdateStatus.getValue()` is the wire string (`NOT_READY`, `INITIAL_UPDATE_COMPLETE`, `HISTORICAL_UPDATE_COMPLETE`, `TRANSACTIONS_UPDATE_STATUS_UNKNOWN`, checked with `javap -c` against plaid-java 46.0.0); `ColumnText.fit` covers anything longer Plaid adds.
- The compare-and-set on `plaid_sync_cursor` under the lock is what makes "one commit per sync" safe when the drainer and the 22:30 reconcile sync the same Item at once: without it, a slower sync that started from an older cursor could commit after a faster one and move the cursor backwards over a newer `modified` row.

- [ ] **Step 7: Run the tests and watch them pass**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidTransactionsSyncServiceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PlaidTransactionsSyncServiceTest.xml
```

Expected: `BUILD SUCCESSFUL`, and the grep prints `tests="20" skipped="0" failures="0" errors="0"`. Read the count from the XML, never the console. If `plaidCallRunsOutsideTransaction` fails with `txOpenDuringApply` empty, T07's mock answer was replaced by a `thenReturn` in that test; it is not, so look at whether `applySyncBatch` is reached at all (a `REFUSED_STALE_ITEM` there means the setUp row lost its item id).

- [ ] **Step 8: Invariance, privacy and em-dash checks**

```bash
cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml
grep -c 'logger\.' src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java
perl -0ne 'while (/logger\.\w+\((.*?)\);/sg) { print "$1\n" if $1 =~ /accessToken|Cursor\(\)|getMessage|getAmount|getName|Description|toString|batch\(\)\.added\(\)\.get/ }' src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java
grep -n 'setStatus(\|setMode(\|setHoldReason(\|getIsPaused(\|isPausedForAnyReason(\|setPlaidItemStatus(\|setPlaidTransactionsConsented(' src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java
perl -CSD -ne 'print "$ARGV:$.: $_" if /\x{2014}/' src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java src/test/java/com/investingapp/backend/service/PlaidTransactionsSyncServiceTest.java
```

Expected:
- `PhaseOneMoneyInvarianceTest`: `BUILD SUCCESSFUL`, `skipped="0" failures="0"` and the same `tests=` count T03 left (this task adds no case; its walk over `service/Plaid*` now includes the new file and finds nothing).
- `grep -c 'logger\.'` prints `10` (refused, stale at entry, decrypt or fetch exception, not initialized, page cap, `logStop`, apply failed, applied, stale under the lock, cursor moved).
- The `perl -0ne` scan prints nothing: no logger call passes the token, a cursor, an exception message, an amount, a name, a descriptor or a model's `toString()`.
- The `grep -n` for state writers prints nothing: this file writes no schedule status, mode or hold, no Item status and no consent flag.
- The em-dash scan prints nothing.

- [ ] **Step 9: Commit**

Both files are new, so `git diff HEAD` shows nothing for them: check with `git status --short` that they are the only two `??` entries this task created and that no tracked file is modified by this task (the tree is shared with other sessions; stage nothing you did not write; never stage `application-local.properties` or `ITPM/agent-memory/*`; never `git checkout`, `git restore`, `git stash` or `git reset`), then:

```bash
git add backend/src/main/java/com/investingapp/backend/service/PlaidTransactionsSyncService.java backend/src/test/java/com/investingapp/backend/service/PlaidTransactionsSyncServiceTest.java
git commit -m "feat(plaid): transactions sync with the cursor protocol and one commit per sync"
```

#### Verification

1. The task's class, from the repository root:

   ```bash
   cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidTransactionsSyncServiceTest' --console=plain
   grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PlaidTransactionsSyncServiceTest.xml
   ```

   Expected: `tests="20" skipped="0" failures="0" errors="0"`.

2. The invariance walk: `cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain`, expected `skipped="0" failures="0"` in `build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml`, count unchanged from T03.

3. The whole backend suite against a scratch MySQL, so the `@RequiresCiDatabase` classes run instead of skipping (this task adds none, but it must not break the boot under `ddl-auto=validate` that `BackendApplicationTests` checks, and the new `@Service` must wire). Run the whole block as ONE shell invocation from the worktree root (variables and the `db` function do not survive between invocations; nothing here prompts, so it runs in a non-interactive agent shell). It reads the local MySQL user and password from the gitignored `application-local.properties` into shell variables without printing them (key names only; never echo, cat or log the values), creates a throwaway schema (never `investing_dev`), runs the suite and drops the schema:

   ```bash
   MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
   MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
   db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
   db -e "DROP DATABASE IF EXISTS fred_scratch_t08; CREATE DATABASE fred_scratch_t08"
   export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_scratch_t08?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
   export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
   export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
   export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
   export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
   export PLAID_LINK_CUSTOMIZATION=default
   (cd backend && ./gradlew cleanTest test --console=plain)
   grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6} END {print "tests=" t, "skipped=" s, "failures=" f}'
   grep -l 'errors="[1-9]' backend/build/test-results/test/*.xml
   grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.PlaidTransactionsSyncServiceTest.xml backend/build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
   db -e "DROP DATABASE fred_scratch_t08"
   unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_USER MYSQL_PW
   ```

   Expected: the awk line prints `skipped=0 failures=0` (the `tests=` total is recorded for the next task; no earlier task recorded a whole-suite total, so there is no delta to check), the `errors=` grep prints no file name, and the last grep prints `tests="20" skipped="0" failures="0" errors="0"` then `tests="1" skipped="0" failures="0" errors="0"`. Read the totals from the XML, never the console. `skipped="1"` on `BackendApplicationTests` means the database was not reached: fix the connection and re-run; a skipped boot is not a pass. If the boot fails with `Could not resolve placeholder`, a property from an earlier task is missing from the ci profile; that is the earlier task's defect, not this one's.

No property was added, so there is no `application-local.properties` edit for this task.

#### Produces

- `com.investingapp.backend.service.PlaidTransactionsSyncService`, a `@Service` with constructor `(UserRepository, PlaidApi, PlaidGateway, EncryptionService, PaycheckEventService, PaycheckAutopilotProperties, PlatformTransactionManager)` and a `@PersistenceContext EntityManager entityManager` field
- `PlaidTransactionsSyncService.SyncReason {INIT, WEBHOOK, RECONCILE, MANUAL}`
- `PlaidTransactionsSyncService.SyncStatus {APPLIED, NOT_INITIALIZED, REFUSED_CONSENT, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS, REFUSED_DISABLED, REFUSED_EXCLUDED, RETRY_LATER, ITEM_ERROR, FAILED}`
- `PlaidTransactionsSyncService.SyncOutcome(SyncStatus status, boolean historyJustCompleted, PaycheckEventService.ApplyResult applied, PlaidApiError error)`, with `SyncOutcome.of(SyncStatus)` and `SyncOutcome.withError(SyncStatus, PlaidApiError)`
- `SyncOutcome syncItem(Long userId, String itemId, SyncReason reason)`: called outside any transaction; refuses per spec 2.5 step 1; `count 500`, `includeOriginalDescription(true)`, PFC `V2`, `daysRequested(props.getDaysRequested())` on every request, never `accountId`; `MUTATION` restarts from the original cursor at most `MAX_RESTARTS = 3`, then `RETRY_LATER`; any other Plaid error is `ITEM_ERROR` with the `PlaidApiError` and no write (CONSENT and SYNC_LIMIT included); an empty `next_cursor` is `NOT_INITIALIZED` and persists nothing; otherwise one `TransactionTemplate` under `findByIdForUpdate`, refreshed, re-checked on `plaidItemId` (else `REFUSED_STALE_ITEM`) and on the stored cursor (else `RETRY_LATER`), applies the buffer through `applySyncBatch` and sets `plaidSyncCursor`, `plaidLastSyncAt`, `plaidTransactionsUpdateStatus` and the sticky `plaidHistoryComplete` on the locked managed `User`; `historyJustCompleted` is the false-to-true flip
- package-private constants `OP = "transactions.sync"`, `PAGE_SIZE = 500`, `MAX_RESTARTS = 3`, `MAX_PAGES = 100`
- `PlaidTransactionsSyncServiceTest` (20 tests)

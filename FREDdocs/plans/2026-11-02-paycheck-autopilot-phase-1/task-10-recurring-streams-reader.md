### Task 10: recurring-streams-reader

`PlaidRecurringService.readStreams(userId, itemId)` becomes the only caller of `/transactions/recurring/get` (spec 2.6). It sends `{access_token, options: {personal_finance_category_version: "v2"}}` with no `account_ids`, reads `inflow_streams` only (inflow is decided by the array, never by the sign of `average_amount.amount`), and turns each inflow stream into catalog data on `paycheck_streams`: the eight `plaid_*` columns plus the raw object in `plaid_snapshot_json`. A stream is matched to a FRED row by `(plaid_account_id, payer token)`, else by `transaction_ids` overlap, else it seeds a new row in state CANDIDATE with role NONE. It stamps `users.plaid_streams_read_at`. An empty `inflow_streams` is a valid read: it stamps the time and writes no stream. The catalog is a tiebreaker for the detector (spec 4.2 gate 3, the 4.5 Plaid-status points, the 4.4 cadence tiebreak), never a trigger and never money.

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests go in `backend/src/test/java/com/investingapp/backend/service/`. Anchors are HEAD 137998c. This task creates two files and modifies none, so no anchor in this file can drift.

Facts from the handoff, the rulings and the contract that this task depends on (restated so this file stands alone):

1. **No Plaid HTTP inside a database transaction.** The house shape is `PlaidItemLifecycleService.recordConsentAfterLink` (`.../service/PlaidItemLifecycleService.java:111-141`): call Plaid first with no transaction open, then one `TransactionTemplate` that takes `userRepository.findByIdForUpdate(id)` (`.../repository/UserRepository.java:81-83`, `PESSIMISTIC_WRITE`) and re-checks that `users.plaid_item_id` still equals the Item that was read before writing anything. A re-read showing another Item writes nothing. `readStreams` also refuses to start when the caller already holds a transaction, because the Plaid call would then sit inside it.
2. **This task is invisible to money.** It writes no `investment_schedules` column (no `status`, no `mode`, no `hold_reason`, no mirror field), no `paycheck_cycles` or `paycheck_events` row, no analytics outbox row and no ops alert. `InvestmentSchedule.isReadyForInvestment(todayET)` is untouched. T03's `PhaseOneMoneyInvarianceTest` walks every `service/Plaid*` file, so this one is covered by it.
3. **Logging rule.** INFO, WARN and ERROR lines carry the read status, the Item state, counts, Plaid error codes and exception class names only: never a payer name, descriptor, amount, date, token or any id. The FRED user id and the Item id go on a DEBUG line. Never log a Plaid model: `toString()` on `TransactionStream` prints every field, descriptions and amounts included.
4. **Plaid strings are cut to their V6 width** with `ColumnText.fit` (T02). MySQL strict mode rejects an over-long value, the rollback repeats on every read, and the Item would be stuck. Widths, from `backend/src/main/resources/db/migration/V6__paycheck_autopilot.sql:20-63`: `plaid_item_id`, `plaid_account_id`, `plaid_stream_id` 80; `plaid_status`, `plaid_frequency`, `plaid_pfc_detailed`, `plaid_pfc_confidence` 32; `payer_token` 64; `payer_display_name` 120; `raw_descriptor` 255; `plaid_snapshot_json` TEXT. `created_at` is `NOT NULL` with no default, so a seeded row sets it.
5. **Readable Item states** are `OK` and `PENDING_DISCONNECT` (spec 2.5 step 1; `.../model/PlaidItemStatus.java:4`). Anything else answers `REFUSED_ITEM_STATUS` with no Plaid call.
6. **Callers** arrive later: T14 calls `readStreams` at history completion and on `RECURRING_TRANSACTIONS_UPDATE` (coalesced per Item), and T15 calls it from the reconcile when `plaid_streams_read_at` is older than 24 hours. This task has no caller in its own commit. T11's `evaluate` later upserts streams by the same `(user_id, plaid_account_id, payer_token)` key, so a row this task seeds is the row `evaluate` finds.
7. **`users.plaid_access_token` is encrypted at rest** (`.../model/User.java:144-145`, `ENC:` prefix); `EncryptionService.decrypt(String)` (`.../service/EncryptionService.java:106`) returns the raw token.

Decisions this task makes where the contract is silent (each is pinned by a test):

- `matched` counts inflow streams that matched a row existing when the stream was considered, including a row seeded earlier in the same read and including a thinner split stream that the next rule then skips; `seeded` counts rows created; `inflowStreams - matched - seeded` streams were skipped before matching (no account id, or no description and no merchant name, or an empty payer token).
- When Plaid splits one payer into two streams that land on the same row, the stream with more `transaction_ids` writes the row; a later stream with fewer ids is skipped.
- `TransactionStreamStatus.ENUM_UNKNOWN` and `RecurringTransactionFrequency.ENUM_UNKNOWN` (values Plaid added after 46.0.0) and a null value are stored as `UNKNOWN`, so T09's hard gate rejects them exactly as it rejects Plaid's own `UNKNOWN`. Every other value is stored as its wire string from `getValue()`, never `name()`.
- Account ids and payer tokens are compared case-insensitively, matching the `utf8mb4_0900_ai_ci` collation of the V6 unique key `uq_paycheck_streams_payer`.
- The snapshot is built by hand with Jackson in Plaid's snake_case wire names (the SDK serializes with Gson, which is not a FRED dependency, and `toString()` is not JSON). A snapshot over 16,000 characters is stored as null and logged with its length only.
- A row whose Plaid stream disappears from a later read keeps its last `plaid_*` values. Clearing them on a transiently empty read would remove the MATURE tiebreaker and flip states back and forth; the snapshot keeps what Plaid last said.

No property is added, so the gitignored `application-local.properties` needs no edit for this task.

**Files:**
- Create: `.../service/PlaidRecurringService.java` (with the nested `StreamsReadStatus` and `StreamsReadOutcome`, since the contract names this one file)
- Test: create `backend/src/test/java/com/investingapp/backend/service/PlaidRecurringServiceTest.java`
- Modify: none

**Interfaces:**
- Consumes (earlier tasks, exact contract names):
  - T02: entity `com.investingapp.backend.model.PaycheckStream` (Lombok setters and getters for `id`, `userId`, `plaidItemId`, `plaidAccountId`, `plaidStreamId`, `payerToken`, `payerDisplayName`, `rawDescriptor`, `state`, `role`, `plaidStatus`, `plaidFrequency`, `plaidPfcDetailed`, `plaidPfcConfidence`, `plaidIsActive` (`Boolean`), `plaidPredictedNextDate` (`LocalDate`), `plaidSnapshotJson`, `createdAt`, `updatedAt` (`LocalDateTime`)); entity `PaycheckEvent` (`userId`, `streamId`, `plaidAccountId`, `plaidTransactionId`); enums `PaycheckStreamState.CANDIDATE`, `PaycheckStreamRole.NONE`, `PaycheckEventStatus.ACTIVE`; `PaycheckStreamRepository.findByUserIdOrderByIdAsc(Long): List<PaycheckStream>`; `PaycheckEventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(Long, PaycheckEventStatus): List<PaycheckEvent>`; `User.setPlaidStreamsReadAt(LocalDateTime)` / `getPlaidStreamsReadAt()`; `static String ColumnText.fit(String value, int width)` (null-safe).
  - T06: `com.investingapp.backend.plaid.PlaidGateway` with `<T> PlaidCallResult<T> call(String op, Call<T> call)` (runs on the caller's thread, turns an `IOException` into a TRANSIENT error, logs the request id); `record PlaidCallResult<T>(T body, PlaidApiError error)`; `record PlaidApiError(String errorType, String errorCode, String errorCodeReason, String requestId, int httpStatus, PlaidErrorClass errorClass)`; `PlaidErrorClass.ITEM_BROKEN` (test only); test support `com.investingapp.backend.support.RecordingTransactionManager` (a real `AbstractPlatformTransactionManager`, no-arg constructor). The test reads no field of it: it asserts through `TransactionSynchronizationManager.isActualTransactionActive()`, which a real manager binds and a Mockito `PlatformTransactionManager` never does, so the outside-a-transaction assertions can fail.
  - T09: `static String PayerTokenizer.token(String originalDescription, String name)`; `static String PayerTokenizer.displayName(String counterpartyName, String counterpartyType, String merchantName, String token)` (package `com.investingapp.backend.paycheck.detect`).
  - HEAD: `UserRepository.findById(Long)` (JpaRepository) and `findByIdForUpdate(Long)` (`UserRepository.java:81-83`); `EncryptionService.decrypt(String)` (`:106`); `EasternTime.now()` (`.../util/EasternTime.java:28`); `PlaidItemStatus` (`.../model/PlaidItemStatus.java:4`); `User.getPlaidAccessToken()`, `getPlaidItemId()`, `getPlaidItemStatus()` (`User.java:145`, `148`, `188`); plaid-java 46.0.0 `PlaidApi.transactionsRecurringGet(TransactionsRecurringGetRequest): Call<TransactionsRecurringGetResponse>`, `TransactionsRecurringGetRequestOptions.personalFinanceCategoryVersion(PersonalFinanceCategoryVersion)`, `TransactionStream` getters (`getAccountId`, `getStreamId`, `getDescription`, `getMerchantName`, `getFirstDate`, `getLastDate`, `getPredictedNextDate`, `getFrequency`, `getTransactionIds`, `getAverageAmount`, `getLastAmount`, `getIsActive`, `getStatus`, `getPersonalFinanceCategory`), `TransactionStreamAmount.getAmount()` (`Double`), `PersonalFinanceCategory.getPrimary/getDetailed/getConfidenceLevel` (`String`), all re-read with `javap` against the 46.0.0 jar for this task.
- Produces:
  - `@Service public class PlaidRecurringService` with constructor `(UserRepository userRepository, PaycheckStreamRepository streamRepository, PaycheckEventRepository eventRepository, PlaidApi plaidApi, PlaidGateway plaidGateway, EncryptionService encryptionService, PlatformTransactionManager transactionManager)`.
  - `public enum PlaidRecurringService.StreamsReadStatus { APPLIED, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS, ITEM_ERROR, FAILED }`.
  - `public record PlaidRecurringService.StreamsReadOutcome(StreamsReadStatus status, int inflowStreams, int matched, int seeded, PlaidApiError error)` (`error` set only for ITEM_ERROR).
  - `public StreamsReadOutcome readStreams(Long userId, String itemId)`: never throws; REFUSED_STALE_ITEM when the user is gone, has no token, or `itemId` is not `users.plaid_item_id` (before the call, and again under the lock); REFUSED_ITEM_STATUS when the Item is not OK or PENDING_DISCONNECT (before the call, and again under the lock); ITEM_ERROR with the gateway's `PlaidApiError` and no write at all; FAILED when called inside a transaction, when the body is missing, or on any runtime exception (the template rolls back); APPLIED otherwise.
  - Package-private `OP = "transactions_recurring_get"`, `SNAPSHOT_MAX_CHARS = 16_000`, `statusWire(TransactionStreamStatus)`, `frequencyWire(RecurringTransactionFrequency)`.

- [ ] **Step 1: Confirm the consumed names exist (read only)**

From the repository root:

```
grep -n "static String token(\|static String displayName(" backend/src/main/java/com/investingapp/backend/paycheck/detect/PayerTokenizer.java
grep -n "PlaidCallResult<T> call(String" backend/src/main/java/com/investingapp/backend/plaid/PlaidGateway.java
grep -n "findByUserIdOrderByIdAsc\|findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc" backend/src/main/java/com/investingapp/backend/repository/PaycheckStreamRepository.java backend/src/main/java/com/investingapp/backend/repository/PaycheckEventRepository.java
grep -n "plaidStreamsReadAt" backend/src/main/java/com/investingapp/backend/model/User.java
grep -n "static String fit(String" backend/src/main/java/com/investingapp/backend/util/ColumnText.java
grep -n "class RecordingTransactionManager" backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java
grep -n -A2 "doGetTransaction" backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java
```

Expected: two lines from the first grep, one line from each of the others (two from the repository grep, one per file). If any is missing, stop: T02, T06 or T09 did not ship its contract, and this task must not invent the name. The last grep must show `doGetTransaction` returning a non-null object (for example `return new Object();`): Spring binds `isActualTransactionActive()` from `status.hasTransaction()`, which is false for a null transaction object, so with a null return `plaidCallRunsOutsideTransaction` and `refusesToRunInsideACallerTransaction` cannot pass. If it returns null, stop and report it against T06.

- [ ] **Step 2: Failing test, fixtures and the five contract cases**

Create `backend/src/test/java/com/investingapp/backend/service/PlaidRecurringServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.PayerTokenizer;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.support.RecordingTransactionManager;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionStreamStatus;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlaidRecurringService: the /transactions/recurring/get reader (spec 2.6). Plaid is a mocked
 * PlaidGateway in front of a mocked PlaidApi; the transaction manager is T06's real
 * RecordingTransactionManager, so TransactionSynchronizationManager reports truthfully whether a
 * transaction is open at the Plaid call and at the row lock.
 */
class PlaidRecurringServiceTest {

    private static final Long USER_ID = 1L;
    private static final String ITEM = "item-1";
    private static final String ACME = "ACME CORP PAYROLL 2418 PPD";

    private UserRepository userRepository;
    private PaycheckStreamRepository streamRepository;
    private PaycheckEventRepository eventRepository;
    private PlaidApi plaidApi;
    private PlaidGateway gateway;
    private RecordingTransactionManager txManager;
    private Call<TransactionsRecurringGetResponse> recurringCall;
    private PlaidRecurringService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userRepository = mock(UserRepository.class);
        streamRepository = mock(PaycheckStreamRepository.class);
        eventRepository = mock(PaycheckEventRepository.class);
        plaidApi = mock(PlaidApi.class);
        gateway = mock(PlaidGateway.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.decrypt(any())).thenAnswer(i -> "raw-of-" + i.getArgument(0));
        txManager = new RecordingTransactionManager();
        service = new PlaidRecurringService(userRepository, streamRepository, eventRepository, plaidApi, gateway,
                encryptionService, txManager);

        recurringCall = mock(Call.class);
        when(plaidApi.transactionsRecurringGet(any())).thenReturn(recurringCall);
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(new ArrayList<>());
        when(eventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(USER_ID, PaycheckEventStatus.ACTIVE))
                .thenReturn(List.of());
        when(streamRepository.save(any(PaycheckStream.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static User user(String itemId, PlaidItemStatus status) {
        User user = new User();
        user.setId(USER_ID);
        user.setPlaidItemId(itemId);
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidItemStatus(status);
        return user;
    }

    /** findById (before the call) and findByIdForUpdate (under the lock) return separate instances, as two persistence contexts would. */
    private User givenUser(String itemId, PlaidItemStatus status) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(itemId, status)));
        User locked = user(itemId, status);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(locked));
        return locked;
    }

    private void plaidReturns(TransactionsRecurringGetResponse body) {
        when(gateway.call(eq(PlaidRecurringService.OP), any())).thenAnswer(i -> new PlaidCallResult<>(body, null));
    }

    private static TransactionStream stream(String streamId, String accountId, String description, double amount,
                                            String... transactionIds) {
        return new TransactionStream()
                .streamId(streamId)
                .accountId(accountId)
                .description(description)
                .frequency(RecurringTransactionFrequency.BIWEEKLY)
                .status(TransactionStreamStatus.MATURE)
                .isActive(true)
                .firstDate(LocalDate.of(2026, 6, 5))
                .lastDate(LocalDate.of(2026, 10, 23))
                .predictedNextDate(LocalDate.of(2026, 11, 6))
                .transactionIds(new ArrayList<>(List.of(transactionIds)))
                .averageAmount(new TransactionStreamAmount().amount(amount).isoCurrencyCode("USD"))
                .lastAmount(new TransactionStreamAmount().amount(amount).isoCurrencyCode("USD"))
                .personalFinanceCategory(new PersonalFinanceCategory()
                        .primary("INCOME").detailed("INCOME_SALARY").confidenceLevel("VERY_HIGH"));
    }

    private static TransactionsRecurringGetResponse inflow(TransactionStream... streams) {
        return new TransactionsRecurringGetResponse()
                .inflowStreams(new ArrayList<>(List.of(streams)))
                .outflowStreams(new ArrayList<>());
    }

    private static PaycheckStream row(Long id, String accountId, String token) {
        PaycheckStream row = new PaycheckStream();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setPlaidAccountId(accountId);
        row.setPayerToken(token);
        row.setPayerDisplayName("Existing payer");
        row.setState(PaycheckStreamState.PROVISIONAL);
        row.setRole(PaycheckStreamRole.PRIMARY);
        return row;
    }

    private List<PaycheckStream> savedStreams() {
        ArgumentCaptor<PaycheckStream> saved = ArgumentCaptor.forClass(PaycheckStream.class);
        verify(streamRepository, atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    private static PaycheckStream withStreamId(List<PaycheckStream> rows, String plaidStreamId) {
        return rows.stream().filter(r -> plaidStreamId.equals(r.getPlaidStreamId())).findFirst().orElseThrow();
    }

    @Test
    void inflowArrayOnlyNeverSignOfAmount() {
        givenUser(ITEM, PlaidItemStatus.OK);
        // The inflow stream carries a positive amount (Plaid's money-out sign) and the outflow stream a
        // negative one: the array alone decides, so the first is stored and the second never is.
        TransactionStream in = stream("s-in", "acc-1", ACME, 25.00, "t-1");
        TransactionStream out = stream("s-out", "acc-1", "GLOBEX PAYROLL", -900.00, "t-2");
        plaidReturns(inflow(in).outflowStreams(new ArrayList<>(List.of(out))));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 1, 0, 1, null));
        assertThat(savedStreams()).extracting(PaycheckStream::getPlaidStreamId).containsExactly("s-in");
    }

    @Test
    void emptyInflowIsValidReadAndStampsReadAt() {
        User locked = givenUser(ITEM, PlaidItemStatus.OK);
        plaidReturns(inflow());

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 0, 0, 0, null));
        assertThat(locked.getPlaidStreamsReadAt()).isNotNull();
        verify(userRepository).save(locked);
        verify(streamRepository, never()).save(any());
    }

    @Test
    void matchesByPayerTokenThenTransactionIds() {
        givenUser(ITEM, PlaidItemStatus.OK);
        String globexToken = PayerTokenizer.token("GLOBEX PAYROLL", null);
        PaycheckStream acme = row(10L, "acc-1", PayerTokenizer.token(ACME, null));
        PaycheckStream globex = row(11L, "acc-1", globexToken);
        PaycheckStream acmeOtherAccount = row(12L, "acc-2", PayerTokenizer.token(ACME, null));
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID))
                .thenReturn(new ArrayList<>(List.of(acme, globex, acmeOtherAccount)));
        PaycheckEvent globexDeposit = new PaycheckEvent();
        globexDeposit.setUserId(USER_ID);
        globexDeposit.setPlaidAccountId("acc-1");
        globexDeposit.setPlaidTransactionId("t-g1");
        globexDeposit.setStreamId(11L);
        when(eventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(USER_ID, PaycheckEventStatus.ACTIVE))
                .thenReturn(List.of(globexDeposit));
        plaidReturns(inflow(
                stream("s-acme", "acc-1", ACME, -2418.00, "t-a1"),
                // Plaid describes Globex differently from FRED's events, so only the shared transaction id links them.
                stream("s-glbx", "acc-1", "GLBX INDUSTRIES", -1200.00, "t-g1", "t-g9"),
                stream("s-init", "acc-1", "INITECH LLC DIR DEP", -800.00, "t-i1")));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 3, 2, 1, null));
        assertThat(acme.getPlaidStreamId()).isEqualTo("s-acme");
        assertThat(acme.getState()).as("the catalog never moves a stream's state").isEqualTo(PaycheckStreamState.PROVISIONAL);
        assertThat(acme.getRole()).isEqualTo(PaycheckStreamRole.PRIMARY);
        assertThat(globex.getPlaidStreamId()).isEqualTo("s-glbx");
        assertThat(globex.getPayerToken()).as("an overlap match never re-keys the row").isEqualTo(globexToken);
        assertThat(acmeOtherAccount.getPlaidStreamId()).as("the same payer on another account is another row").isNull();

        PaycheckStream seeded = withStreamId(savedStreams(), "s-init");
        assertThat(seeded.getId()).isNull();
        assertThat(seeded.getUserId()).isEqualTo(USER_ID);
        assertThat(seeded.getPlaidAccountId()).isEqualTo("acc-1");
        assertThat(seeded.getPayerToken()).isEqualTo(PayerTokenizer.token("INITECH LLC DIR DEP", null));
        assertThat(seeded.getPayerDisplayName()).isNotBlank();
        assertThat(seeded.getRawDescriptor()).isEqualTo("INITECH LLC DIR DEP");
        assertThat(seeded.getState()).isEqualTo(PaycheckStreamState.CANDIDATE);
        assertThat(seeded.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        assertThat(seeded.getCreatedAt()).isNotNull();
    }

    @Test
    void overlongPlaidStringsAreFittedNotRejected() {
        String longItem = "i".repeat(90);
        givenUser(longItem, PlaidItemStatus.OK);
        TransactionStream overlong = stream("s".repeat(100), "acc-1", "ACME " + "X".repeat(295), -2418.00, "t-1")
                .merchantName("M".repeat(200))
                .personalFinanceCategory(new PersonalFinanceCategory()
                        .primary("INCOME").detailed("D".repeat(50)).confidenceLevel("C".repeat(40)));
        plaidReturns(inflow(overlong));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, longItem);

        assertThat(outcome.status()).isEqualTo(StreamsReadStatus.APPLIED);
        PaycheckStream saved = savedStreams().get(0);
        assertThat(saved.getRawDescriptor()).hasSize(255);
        assertThat(saved.getPayerToken()).isNotBlank().hasSizeLessThanOrEqualTo(64);
        assertThat(saved.getPayerDisplayName()).isNotBlank().hasSizeLessThanOrEqualTo(120);
        assertThat(saved.getPlaidStreamId()).hasSize(80);
        assertThat(saved.getPlaidItemId()).hasSize(80);
        assertThat(saved.getPlaidPfcDetailed()).hasSize(32);
        assertThat(saved.getPlaidPfcConfidence()).hasSize(32);
    }

    @Test
    void plaidCallRunsOutsideTransaction() {
        AtomicBoolean plaidSawTransaction = new AtomicBoolean(true);
        AtomicBoolean lockSawTransaction = new AtomicBoolean(false);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(ITEM, PlaidItemStatus.OK)));
        User locked = user(ITEM, PlaidItemStatus.OK);
        when(userRepository.findByIdForUpdate(USER_ID)).thenAnswer(i -> {
            lockSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of(locked);
        });
        TransactionsRecurringGetResponse body = inflow(stream("s-1", "acc-1", ACME, -2418.00, "t-1"));
        when(gateway.call(eq(PlaidRecurringService.OP), any())).thenAnswer(i -> {
            plaidSawTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new PlaidCallResult<>(body, null);
        });

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome.status()).isEqualTo(StreamsReadStatus.APPLIED);
        assertThat(plaidSawTransaction.get()).as("the Plaid call ran inside a transaction").isFalse();
        assertThat(lockSawTransaction.get()).as("the row lock and the writes ran outside the template").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
}
```

`PayerTokenizer` is used in the test on purpose: the expected tokens come from T09's own function, so this test pins the matching rule and not the tokenizer's word list.

- [ ] **Step 3: Failing test, the guard and catalog cases**

In the same file, insert before the final `}` of the class:

```java
    @Test
    void requestCarriesPfcV2AndNoAccountIds() {
        givenUser(ITEM, PlaidItemStatus.OK);
        plaidReturns(inflow());

        service.readStreams(USER_ID, ITEM);

        ArgumentCaptor<TransactionsRecurringGetRequest> request = ArgumentCaptor.forClass(TransactionsRecurringGetRequest.class);
        verify(plaidApi).transactionsRecurringGet(request.capture());
        assertThat(request.getValue().getAccessToken()).isEqualTo("raw-of-ENC:token");
        assertThat(request.getValue().getAccountIds()).isNullOrEmpty();
        assertThat(request.getValue().getOptions().getPersonalFinanceCategoryVersion())
                .isEqualTo(PersonalFinanceCategoryVersion.V2);
        verify(gateway).call(eq(PlaidRecurringService.OP), same(recurringCall));
    }

    @Test
    void nullInflowListIsAnEmptyRead() {
        User locked = givenUser(ITEM, PlaidItemStatus.OK);
        plaidReturns(new TransactionsRecurringGetResponse().inflowStreams(null));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 0, 0, 0, null));
        assertThat(locked.getPlaidStreamsReadAt()).isNotNull();
    }

    @Test
    void staleItemRefusedBeforeAnyPlaidCall() {
        givenUser("item-2", PlaidItemStatus.OK);
        when(userRepository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.readStreams(USER_ID, ITEM).status()).isEqualTo(StreamsReadStatus.REFUSED_STALE_ITEM);
        assertThat(service.readStreams(2L, ITEM).status()).isEqualTo(StreamsReadStatus.REFUSED_STALE_ITEM);
        assertThat(service.readStreams(USER_ID, null).status()).isEqualTo(StreamsReadStatus.REFUSED_STALE_ITEM);
        User noToken = user(ITEM, PlaidItemStatus.OK);
        noToken.setPlaidAccessToken(null);
        when(userRepository.findById(3L)).thenReturn(Optional.of(noToken));
        assertThat(service.readStreams(3L, ITEM).status()).as("no token").isEqualTo(StreamsReadStatus.REFUSED_STALE_ITEM);

        verify(plaidApi, never()).transactionsRecurringGet(any());
        verify(gateway, never()).call(any(), any());
        verify(userRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void brokenItemStatusRefusedPendingDisconnectAllowed() {
        for (PlaidItemStatus broken : List.of(PlaidItemStatus.LOGIN_REQUIRED, PlaidItemStatus.REVOKED,
                PlaidItemStatus.REMOVED, PlaidItemStatus.SYNC_LIMIT, PlaidItemStatus.NOT_SUPPORTED)) {
            givenUser(ITEM, broken);
            assertThat(service.readStreams(USER_ID, ITEM).status()).as(broken.name())
                    .isEqualTo(StreamsReadStatus.REFUSED_ITEM_STATUS);
        }
        verify(gateway, never()).call(any(), any());

        givenUser(ITEM, PlaidItemStatus.PENDING_DISCONNECT);
        plaidReturns(inflow());
        assertThat(service.readStreams(USER_ID, ITEM).status()).isEqualTo(StreamsReadStatus.APPLIED);
        verify(gateway, times(1)).call(eq(PlaidRecurringService.OP), any());
    }

    @Test
    void plaidErrorIsItemErrorAndWritesNothing() {
        User locked = givenUser(ITEM, PlaidItemStatus.OK);
        PlaidApiError error = new PlaidApiError("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", null, "req-1", 400,
                PlaidErrorClass.ITEM_BROKEN);
        when(gateway.call(eq(PlaidRecurringService.OP), any()))
                .thenAnswer(i -> new PlaidCallResult<TransactionsRecurringGetResponse>(null, error));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.ITEM_ERROR, 0, 0, 0, error));
        verify(userRepository, never()).findByIdForUpdate(any());
        verify(streamRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(locked.getPlaidStreamsReadAt()).isNull();
    }

    @Test
    void itemChangedDuringCallWritesNothing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(ITEM, PlaidItemStatus.OK)));
        User relinked = user("item-2", PlaidItemStatus.OK);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(relinked));
        plaidReturns(inflow(stream("s-1", "acc-1", ACME, -2418.00, "t-1")));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome.status()).isEqualTo(StreamsReadStatus.REFUSED_STALE_ITEM);
        verify(streamRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(relinked.getPlaidStreamsReadAt()).isNull();

        // Same Item, but it broke while Plaid answered: the re-check under the lock refuses on state too.
        User broke = user(ITEM, PlaidItemStatus.LOGIN_REQUIRED);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(broke));
        assertThat(service.readStreams(USER_ID, ITEM).status()).isEqualTo(StreamsReadStatus.REFUSED_ITEM_STATUS);
        verify(streamRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(broke.getPlaidStreamsReadAt()).isNull();
    }

    @Test
    void refusesToRunInsideACallerTransaction() {
        givenUser(ITEM, PlaidItemStatus.OK);
        plaidReturns(inflow());

        StreamsReadOutcome outcome = new TransactionTemplate(txManager).execute(s -> service.readStreams(USER_ID, ITEM));

        assertThat(outcome.status()).isEqualTo(StreamsReadStatus.FAILED);
        verify(plaidApi, never()).transactionsRecurringGet(any());
        verify(gateway, never()).call(any(), any());
    }

    @Test
    void twoPlaidStreamsOnOneRowKeepTheRicherOne() {
        givenUser(ITEM, PlaidItemStatus.OK);
        PaycheckStream acme = row(10L, "acc-1", PayerTokenizer.token(ACME, null));
        when(streamRepository.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(new ArrayList<>(List.of(acme)));
        plaidReturns(inflow(
                stream("s-rich", "acc-1", ACME, -2418.00, "t-1", "t-2", "t-3", "t-4", "t-5"),
                stream("s-thin", "acc-1", ACME, -2418.00, "t-6", "t-7")));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 2, 2, 0, null));
        assertThat(acme.getPlaidStreamId()).isEqualTo("s-rich");
        verify(streamRepository, times(1)).save(acme);
    }

    @Test
    void catalogColumnsAndSnapshotUseWireValues() throws Exception {
        givenUser(ITEM, PlaidItemStatus.OK);
        TransactionStream known = stream("s-1", "acc-1", ACME, -2418.00, "t-1", "t-2");
        TransactionStream unknown = stream("s-2", "acc-1", "INITECH LLC DIR DEP", -500.00, "t-3")
                .status(TransactionStreamStatus.ENUM_UNKNOWN)
                .frequency(RecurringTransactionFrequency.ENUM_UNKNOWN);
        plaidReturns(inflow(known, unknown));

        service.readStreams(USER_ID, ITEM);

        List<PaycheckStream> saved = savedStreams();
        PaycheckStream first = withStreamId(saved, "s-1");
        assertThat(first.getPlaidItemId()).isEqualTo(ITEM);
        assertThat(first.getPlaidStatus()).isEqualTo("MATURE");
        assertThat(first.getPlaidFrequency()).isEqualTo("BIWEEKLY");
        assertThat(first.getPlaidPfcDetailed()).isEqualTo("INCOME_SALARY");
        assertThat(first.getPlaidPfcConfidence()).isEqualTo("VERY_HIGH");
        assertThat(first.getPlaidIsActive()).isTrue();
        assertThat(first.getPlaidPredictedNextDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(first.getUpdatedAt()).isNotNull();

        JsonNode snapshot = new ObjectMapper().readTree(first.getPlaidSnapshotJson());
        assertThat(snapshot.path("stream_id").asText()).isEqualTo("s-1");
        assertThat(snapshot.path("account_id").asText()).isEqualTo("acc-1");
        assertThat(snapshot.path("transaction_ids").size()).isEqualTo(2);
        assertThat(snapshot.path("average_amount").path("amount").asDouble()).isEqualTo(-2418.00);
        assertThat(snapshot.path("frequency").asText()).isEqualTo("BIWEEKLY");
        assertThat(snapshot.path("status").asText()).isEqualTo("MATURE");
        assertThat(snapshot.path("last_date").asText()).isEqualTo("2026-10-23");
        assertThat(snapshot.path("personal_finance_category").path("detailed").asText()).isEqualTo("INCOME_SALARY");

        PaycheckStream second = withStreamId(saved, "s-2");
        assertThat(second.getPlaidStatus()).isEqualTo("UNKNOWN");
        assertThat(second.getPlaidFrequency()).isEqualTo("UNKNOWN");
    }
```

- [ ] **Step 4: Run the test and watch it fail**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidRecurringServiceTest' --console=plain
cd ..
```

Expected: `compileTestJava` fails with `error: cannot find symbol` naming `class PlaidRecurringService` (and its nested `StreamsReadOutcome` and `StreamsReadStatus` in the imports). No other symbol may be missing; if one is, Step 1 was skipped or an earlier task deviated.

- [ ] **Step 5: Create the service, part 1 (types, constructor, readStreams)**

Create `backend/src/main/java/com/investingapp/backend/service/PlaidRecurringService.java`:

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.PayerTokenizer;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.RecurringTransactionFrequency;
import com.plaid.client.model.TransactionStream;
import com.plaid.client.model.TransactionStreamAmount;
import com.plaid.client.model.TransactionStreamStatus;
import com.plaid.client.model.TransactionsRecurringGetRequest;
import com.plaid.client.model.TransactionsRecurringGetRequestOptions;
import com.plaid.client.model.TransactionsRecurringGetResponse;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only caller of /transactions/recurring/get (spec 2.6).
 *
 * <p>Plaid's inflow streams become a catalog on paycheck_streams: the eight plaid_* columns the
 * detector reads as a tiebreaker (spec 4.2 gate 3, the 4.5 Plaid-status points, the 4.4 cadence
 * tiebreak) and the raw object in plaid_snapshot_json. A stream is never a trigger and never money:
 * this class writes no schedule column, no hold, no cycle, no event row, no analytics and no alert.
 *
 * <p>Shape (PlaidItemLifecycleService.recordConsentAfterLink): read the user, call Plaid with no
 * transaction open, then one TransactionTemplate that locks the user row with findByIdForUpdate,
 * re-checks that the Item and its state are unchanged, and writes everything or nothing.
 *
 * <p>Matching, per inflow stream: the row with the same (plaid_account_id, payer token); else the
 * row on the same account that owns the most of the stream's transaction_ids through
 * paycheck_events.stream_id; else a new CANDIDATE row with role NONE. Plaid's stream_id is not a
 * key because it is unstable (spec 3.3).
 *
 * <p>Logging: INFO and above carry statuses, counts, Plaid error codes and exception class names
 * only. User and Item ids go to DEBUG. Never log a Plaid model: its toString prints descriptions
 * and amounts.
 */
@Service
public class PlaidRecurringService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidRecurringService.class);

    /** Operation name the gateway logs beside Plaid's request_id. */
    static final String OP = "transactions_recurring_get";

    // V6 widths (V6__paycheck_autopilot.sql:20-63). MySQL strict mode rejects an over-long value and
    // the rollback would repeat on every read, so every Plaid string is cut to fit.
    static final int ID_WIDTH = 80;
    static final int PLAID_CODE_WIDTH = 32;
    static final int PAYER_TOKEN_WIDTH = 64;
    static final int DISPLAY_NAME_WIDTH = 120;
    static final int DESCRIPTOR_WIDTH = 255;
    /** plaid_snapshot_json is TEXT (65,535 bytes); 16,000 characters fit even at 4 bytes each. */
    static final int SNAPSHOT_MAX_CHARS = 16_000;

    private static final String UNKNOWN = "UNKNOWN";

    public enum StreamsReadStatus { APPLIED, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS, ITEM_ERROR, FAILED }

    /**
     * inflowStreams is the size of Plaid's inflow array; matched counts streams written onto a row
     * that existed when the stream was considered (a row seeded earlier in the same read included);
     * seeded counts rows created; the rest were skipped (no account id, no description and no
     * merchant name, or an empty payer token). error is set only for ITEM_ERROR.
     */
    public record StreamsReadOutcome(StreamsReadStatus status, int inflowStreams, int matched, int seeded,
                                     PlaidApiError error) {
    }

    private final UserRepository userRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final PlaidApi plaidApi;
    private final PlaidGateway plaidGateway;
    private final EncryptionService encryptionService;
    private final TransactionTemplate writeTx;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlaidRecurringService(UserRepository userRepository,
            PaycheckStreamRepository streamRepository,
            PaycheckEventRepository eventRepository,
            PlaidApi plaidApi,
            PlaidGateway plaidGateway,
            EncryptionService encryptionService,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.plaidApi = plaidApi;
        this.plaidGateway = plaidGateway;
        this.encryptionService = encryptionService;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Read the Item's recurring streams and store the inflow catalog. Never throws. Must be called
     * with no transaction open: the Plaid call would otherwise sit inside the caller's transaction.
     */
    public StreamsReadOutcome readStreams(Long userId, String itemId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            logger.error("plaid.recurring.read status=FAILED cause=caller_transaction");
            return outcome(StreamsReadStatus.FAILED);
        }
        try {
            Optional<User> found = userRepository.findById(userId);
            if (found.isEmpty() || itemId == null || !itemId.equals(found.get().getPlaidItemId())
                    || isBlank(found.get().getPlaidAccessToken())) {
                logger.info("plaid.recurring.read status=REFUSED_STALE_ITEM");
                logger.debug("plaid.recurring.read refused stale user={} item={}", userId, itemId);
                return outcome(StreamsReadStatus.REFUSED_STALE_ITEM);
            }
            User user = found.get();
            if (!readable(user.getPlaidItemStatus())) {
                logger.info("plaid.recurring.read status=REFUSED_ITEM_STATUS item_status={}", user.getPlaidItemStatus());
                logger.debug("plaid.recurring.read refused item status user={} item={}", userId, itemId);
                return outcome(StreamsReadStatus.REFUSED_ITEM_STATUS);
            }

            TransactionsRecurringGetRequest request = new TransactionsRecurringGetRequest()
                    .accessToken(encryptionService.decrypt(user.getPlaidAccessToken()))
                    .options(new TransactionsRecurringGetRequestOptions()
                            .personalFinanceCategoryVersion(PersonalFinanceCategoryVersion.V2));
            PlaidCallResult<TransactionsRecurringGetResponse> result =
                    plaidGateway.call(OP, plaidApi.transactionsRecurringGet(request));
            if (result.error() != null) {
                logger.info("plaid.recurring.read status=ITEM_ERROR code={} class={}",
                        result.error().errorCode(), result.error().errorClass());
                logger.debug("plaid.recurring.read item error user={} item={}", userId, itemId);
                return new StreamsReadOutcome(StreamsReadStatus.ITEM_ERROR, 0, 0, 0, result.error());
            }
            if (result.body() == null) {
                logger.error("plaid.recurring.read status=FAILED cause=empty_body");
                return outcome(StreamsReadStatus.FAILED);
            }
            List<TransactionStream> inflow = result.body().getInflowStreams() == null
                    ? List.of()
                    : result.body().getInflowStreams();
            LocalDateTime nowET = EasternTime.now();

            StreamsReadOutcome applied = writeTx.execute(status -> apply(userId, itemId, inflow, nowET));
            logger.info("plaid.recurring.read status={} inflow={} matched={} seeded={}",
                    applied.status(), applied.inflowStreams(), applied.matched(), applied.seeded());
            logger.debug("plaid.recurring.read done user={} item={}", userId, itemId);
            return applied;
        } catch (RuntimeException e) {
            // Class name only: a persistence exception message can quote the row's values.
            logger.error("plaid.recurring.read status=FAILED cause={}", e.getClass().getSimpleName());
            return outcome(StreamsReadStatus.FAILED);
        }
    }

    private static StreamsReadOutcome outcome(StreamsReadStatus status) {
        return new StreamsReadOutcome(status, 0, 0, 0, null);
    }

    private static boolean readable(PlaidItemStatus status) {
        return status == PlaidItemStatus.OK || status == PlaidItemStatus.PENDING_DISCONNECT;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 6: Create the service, part 2 (matching, catalog, snapshot)**

In `PlaidRecurringService.java`, insert after the closing `}` of `readStreams` (before `private static StreamsReadOutcome outcome(`):

```java
    /** Runs inside writeTx: lock, re-check, match, write. */
    private StreamsReadOutcome apply(Long userId, String itemId, List<TransactionStream> inflow, LocalDateTime nowET) {
        Optional<User> lockedUser = userRepository.findByIdForUpdate(userId);
        if (lockedUser.isEmpty() || !itemId.equals(lockedUser.get().getPlaidItemId())
                || isBlank(lockedUser.get().getPlaidAccessToken())) {
            // A relink won the race while Plaid answered: this read belongs to an Item the user no longer has.
            return outcome(StreamsReadStatus.REFUSED_STALE_ITEM);
        }
        User user = lockedUser.get();
        if (!readable(user.getPlaidItemStatus())) {
            // The Item broke or was revoked while Plaid answered; writing now would resurrect purged data.
            return outcome(StreamsReadStatus.REFUSED_ITEM_STATUS);
        }

        List<PaycheckStream> rows = new ArrayList<>(streamRepository.findByUserIdOrderByIdAsc(userId));
        Map<String, Long> streamIdByTransaction = new HashMap<>();
        for (PaycheckEvent event : eventRepository.findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(
                userId, PaycheckEventStatus.ACTIVE)) {
            if (event.getStreamId() != null && event.getPlaidTransactionId() != null) {
                streamIdByTransaction.put(event.getPlaidTransactionId(), event.getStreamId());
            }
        }

        Map<PaycheckStream, Integer> writtenWith = new IdentityHashMap<>();
        int matched = 0;
        int seeded = 0;
        for (TransactionStream stream : inflow) {
            String accountId = ColumnText.fit(stream.getAccountId(), ID_WIDTH);
            if (isBlank(accountId) || (isBlank(stream.getDescription()) && isBlank(stream.getMerchantName()))) {
                continue;
            }
            String token = ColumnText.fit(PayerTokenizer.token(stream.getDescription(), stream.getMerchantName()),
                    PAYER_TOKEN_WIDTH);
            if (isBlank(token)) {
                continue;
            }
            List<String> transactionIds = stream.getTransactionIds() == null ? List.of() : stream.getTransactionIds();
            Optional<PaycheckStream> existing = byPayerToken(rows, accountId, token)
                    .or(() -> byTransactionIds(rows, accountId, transactionIds, streamIdByTransaction));

            PaycheckStream row;
            if (existing.isPresent()) {
                row = existing.get();
                matched++;
                Integer previous = writtenWith.get(row);
                if (previous != null && previous >= transactionIds.size()) {
                    continue; // Plaid split one payer in two; the stream with more history already wrote this row
                }
            } else {
                row = seed(userId, accountId, token, stream, nowET);
                rows.add(row);
                seeded++;
            }
            writeCatalog(row, itemId, stream, nowET);
            writtenWith.put(row, transactionIds.size());
            streamRepository.save(row);
        }

        user.setPlaidStreamsReadAt(nowET);
        userRepository.save(user);
        return new StreamsReadOutcome(StreamsReadStatus.APPLIED, inflow.size(), matched, seeded, null);
    }

    /** Case-insensitive, as the utf8mb4_0900_ai_ci unique key uq_paycheck_streams_payer compares. */
    private static Optional<PaycheckStream> byPayerToken(List<PaycheckStream> rows, String accountId, String token) {
        return rows.stream()
                .filter(r -> accountId.equalsIgnoreCase(r.getPlaidAccountId()) && token.equalsIgnoreCase(r.getPayerToken()))
                .findFirst();
    }

    /** The row on the same account owning the most of these transaction ids; rows are id-ascending, so a tie keeps the older row. */
    private static Optional<PaycheckStream> byTransactionIds(List<PaycheckStream> rows, String accountId,
            List<String> transactionIds, Map<String, Long> streamIdByTransaction) {
        Map<Long, Integer> overlap = new HashMap<>();
        for (String transactionId : transactionIds) {
            Long streamId = streamIdByTransaction.get(transactionId);
            if (streamId != null) {
                overlap.merge(streamId, 1, Integer::sum);
            }
        }
        if (overlap.isEmpty()) {
            return Optional.empty();
        }
        PaycheckStream best = null;
        int bestCount = 0;
        for (PaycheckStream row : rows) {
            if (row.getId() == null || !accountId.equalsIgnoreCase(row.getPlaidAccountId())) {
                continue;
            }
            int count = overlap.getOrDefault(row.getId(), 0);
            if (count > bestCount) {
                best = row;
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }

    /** A new catalog row: CANDIDATE, role NONE; evaluate (T11) decides everything else. */
    private static PaycheckStream seed(Long userId, String accountId, String token, TransactionStream stream,
            LocalDateTime nowET) {
        String displayName = PayerTokenizer.displayName(null, null, stream.getMerchantName(), token);
        PaycheckStream row = new PaycheckStream();
        row.setUserId(userId);
        row.setPlaidAccountId(accountId);
        row.setPayerToken(token);
        row.setPayerDisplayName(ColumnText.fit(isBlank(displayName) ? token : displayName, DISPLAY_NAME_WIDTH));
        row.setRawDescriptor(ColumnText.fit(stream.getDescription(), DESCRIPTOR_WIDTH));
        row.setState(PaycheckStreamState.CANDIDATE);
        row.setRole(PaycheckStreamRole.NONE);
        row.setCreatedAt(nowET);
        return row;
    }

    /** The eight plaid_* columns plus the snapshot. Never touches state, role, token, cadence, counters or amounts. */
    private void writeCatalog(PaycheckStream row, String itemId, TransactionStream stream, LocalDateTime nowET) {
        PersonalFinanceCategory pfc = stream.getPersonalFinanceCategory();
        row.setPlaidItemId(ColumnText.fit(itemId, ID_WIDTH));
        row.setPlaidStreamId(ColumnText.fit(stream.getStreamId(), ID_WIDTH));
        row.setPlaidStatus(ColumnText.fit(statusWire(stream.getStatus()), PLAID_CODE_WIDTH));
        row.setPlaidFrequency(ColumnText.fit(frequencyWire(stream.getFrequency()), PLAID_CODE_WIDTH));
        row.setPlaidPfcDetailed(pfc == null ? null : ColumnText.fit(pfc.getDetailed(), PLAID_CODE_WIDTH));
        row.setPlaidPfcConfidence(pfc == null ? null : ColumnText.fit(pfc.getConfidenceLevel(), PLAID_CODE_WIDTH));
        row.setPlaidIsActive(stream.getIsActive());
        row.setPlaidPredictedNextDate(stream.getPredictedNextDate());
        row.setPlaidSnapshotJson(snapshotJson(stream));
        row.setUpdatedAt(nowET);
    }

    /** Wire string; a value newer than plaid-java 46.0.0 (ENUM_UNKNOWN) or a missing one is UNKNOWN, which the hard gate rejects. */
    static String statusWire(TransactionStreamStatus status) {
        return status == null || status == TransactionStreamStatus.ENUM_UNKNOWN ? UNKNOWN : status.getValue();
    }

    static String frequencyWire(RecurringTransactionFrequency frequency) {
        return frequency == null || frequency == RecurringTransactionFrequency.ENUM_UNKNOWN ? UNKNOWN : frequency.getValue();
    }

    /** The spec 2.11 stream fields in Plaid's snake_case wire names, amounts with Plaid's own sign. */
    String snapshotJson(TransactionStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("stream_id", stream.getStreamId());
        node.put("account_id", stream.getAccountId());
        node.put("description", stream.getDescription());
        node.put("merchant_name", stream.getMerchantName());
        node.put("frequency", frequencyWire(stream.getFrequency()));
        node.put("status", statusWire(stream.getStatus()));
        node.put("is_active", stream.getIsActive());
        node.put("first_date", iso(stream.getFirstDate()));
        node.put("last_date", iso(stream.getLastDate()));
        node.put("predicted_next_date", iso(stream.getPredictedNextDate()));
        ArrayNode ids = node.putArray("transaction_ids");
        if (stream.getTransactionIds() != null) {
            for (String id : stream.getTransactionIds()) {
                ids.add(id);
            }
        }
        putAmount(node, "average_amount", stream.getAverageAmount());
        putAmount(node, "last_amount", stream.getLastAmount());
        PersonalFinanceCategory pfc = stream.getPersonalFinanceCategory();
        if (pfc == null) {
            node.putNull("personal_finance_category");
        } else {
            ObjectNode category = node.putObject("personal_finance_category");
            category.put("primary", pfc.getPrimary());
            category.put("detailed", pfc.getDetailed());
            category.put("confidence_level", pfc.getConfidenceLevel());
        }
        String json = node.toString();
        if (json.length() > SNAPSHOT_MAX_CHARS) {
            logger.info("plaid.recurring.snapshot_dropped chars={}", json.length());
            return null;
        }
        return json;
    }

    private static void putAmount(ObjectNode node, String field, TransactionStreamAmount amount) {
        if (amount == null) {
            node.putNull(field);
            return;
        }
        ObjectNode value = node.putObject(field);
        value.put("amount", amount.getAmount());
        value.put("iso_currency_code", amount.getIsoCurrencyCode());
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }
```

Jackson's `ObjectNode.put(String, String)`, `put(String, Boolean)` and `put(String, Double)` write a JSON null for a null argument, so no field needs its own null check.

- [ ] **Step 7: Run the test and watch it pass**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidRecurringServiceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.PlaidRecurringServiceTest.xml
cd ..
```

Expected: `BUILD SUCCESSFUL`, and the grep prints `tests="14" skipped="0" failures="0"`. Read the count from the XML, never the console, which does not total the cases. If `matchesByPayerTokenThenTransactionIds` fails on the seeded token, print `PayerTokenizer.token("INITECH LLC DIR DEP", null)` in a scratch test: T09's tokenizer is the authority, and the service must call it, never reimplement it.

- [ ] **Step 8: Run the neighbours and the static checks**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml
cd ..
grep -nE 'setStatus\(|setMode\(|setHoldReason\(|getIsPaused\(|isPausedForAnyReason\(|analyticsOutboxService|opsAlertService|InvestmentSchedule' backend/src/main/java/com/investingapp/backend/service/PlaidRecurringService.java
grep -nE 'logger\.(info|warn|error)\(' backend/src/main/java/com/investingapp/backend/service/PlaidRecurringService.java
perl -CSD -ne 'print "$ARGV:$.: $_" if /\x{2014}/; close ARGV if eof' backend/src/main/java/com/investingapp/backend/service/PlaidRecurringService.java backend/src/test/java/com/investingapp/backend/service/PlaidRecurringServiceTest.java
```

Expected: the invariance test passes with `failures="0"` and `skipped="0"` (its walk over `service/Plaid*` now includes this file and finds no `setStatus(...Status.`, `setMode(`, `getIsPaused(` or `isPausedForAnyReason(`); the first grep prints nothing (the class touches no schedule, emits no event and raises no alert); the second grep prints exactly eight lines, and every placeholder in them binds a status, a count, a Plaid error code, an `item_status` or an exception class name, never `userId`, `itemId`, a token, a descriptor or an amount; the perl line prints nothing (no em dash).

- [ ] **Step 9: Whole backend suite against a scratch MySQL**

This task adds no `@RequiresCiDatabase` class, but the new `@Service` must wire in the ci context (`BackendApplicationTests`), and only a database run proves it. From the repository root:

```
read -rs MYSQL_PW   # the scratch MySQL root password; never echoed, never written to a file
mysql -u root -p"$MYSQL_PW" -e "CREATE DATABASE fred_scratch_t10"
cd backend
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_scratch_t10?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL=https://local.fredvested.com/api/plaid/webhook
export PLAID_REDIRECT_URI=https://local.fredvested.com/link-bank
export PLAID_LINK_CUSTOMIZATION=default
./gradlew cleanTest test --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6} END {print "tests=" t, "skipped=" s, "failures=" f}'
grep -l '<skipped' build/test-results/test/*.xml
mysql -u root -p"$MYSQL_PW" -e "DROP DATABASE fred_scratch_t10"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
cd ..
```

Expected: `BUILD SUCCESSFUL`; the awk line prints `skipped=0 failures=0` with `tests=` exactly 14 more than the same total before this task; the `grep -l '<skipped'` line prints nothing. A run with any skip proves nothing about the database-gated classes; fix the connection and re-run.

- [ ] **Step 10: Commit**

Hunk-verify both files with `git status --short` and `git diff HEAD --stat` (shared tree; stage nothing you did not write), then:

```
git add backend/src/main/java/com/investingapp/backend/service/PlaidRecurringService.java backend/src/test/java/com/investingapp/backend/service/PlaidRecurringServiceTest.java
git commit -m "feat(paycheck): read Plaid recurring inflow streams into the stream catalog"
```

## Verification

| Command | Expected |
|---|---|
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidRecurringServiceTest' --console=plain`, then the XML grep of Step 7 | `tests="14" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain`, then its XML grep | `skipped="0" failures="0"` |
| The schedule, analytics and alert grep of Step 8 | prints nothing |
| The `logger.(info\|warn\|error)` grep of Step 8 | eight lines, no id, token, descriptor or amount bound |
| The perl em dash check of Step 8 | prints nothing |
| Step 9 scratch-MySQL suite, totals from `build/test-results/test/*.xml` | `skipped=0 failures=0`, 14 more tests than before this task, no file with `<skipped` |

The 14 cases: `inflowArrayOnlyNeverSignOfAmount`, `emptyInflowIsValidReadAndStampsReadAt`, `matchesByPayerTokenThenTransactionIds`, `overlongPlaidStringsAreFittedNotRejected`, `plaidCallRunsOutsideTransaction` (the five contract cases), `requestCarriesPfcV2AndNoAccountIds`, `nullInflowListIsAnEmptyRead`, `staleItemRefusedBeforeAnyPlaidCall`, `brokenItemStatusRefusedPendingDisconnectAllowed`, `plaidErrorIsItemErrorAndWritesNothing`, `itemChangedDuringCallWritesNothing`, `refusesToRunInsideACallerTransaction`, `twoPlaidStreamsOnOneRowKeepTheRicherOne`, `catalogColumnsAndSnapshotUseWireValues`.

## Produces

- `com.investingapp.backend.service.PlaidRecurringService` (`@Service`), constructor `(UserRepository, PaycheckStreamRepository, PaycheckEventRepository, PlaidApi, PlaidGateway, EncryptionService, PlatformTransactionManager)`.
- `public enum PlaidRecurringService.StreamsReadStatus { APPLIED, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS, ITEM_ERROR, FAILED }`.
- `public record PlaidRecurringService.StreamsReadOutcome(StreamsReadStatus status, int inflowStreams, int matched, int seeded, PlaidApiError error)`.
- `public StreamsReadOutcome readStreams(Long userId, String itemId)`: the only `/transactions/recurring/get` caller; Plaid call outside any transaction; one `TransactionTemplate` under `userRepository.findByIdForUpdate(userId)` with the Item and Item-state re-check; matches by `(plaid_account_id, PayerTokenizer.token(description, merchantName))`, else by `transaction_ids` overlap through `paycheck_events.stream_id`, else seeds CANDIDATE with role NONE; writes `plaid_item_id`, `plaid_stream_id`, `plaid_status`, `plaid_frequency`, `plaid_pfc_detailed`, `plaid_pfc_confidence`, `plaid_is_active`, `plaid_predicted_next_date` and `plaid_snapshot_json`, every string through `ColumnText.fit` at its V6 width; stamps `users.plaid_streams_read_at` (also on an empty read).
- Package-private: `OP`, `SNAPSHOT_MAX_CHARS`, `statusWire(TransactionStreamStatus)`, `frequencyWire(RecurringTransactionFrequency)` (`ENUM_UNKNOWN` and null stored as `UNKNOWN`).
- For T14: `StreamsReadOutcome.error()` carries the `PlaidApiError` that its ITEM_ERROR routing classifies; `readStreams` writes no Item state, so CONSENT and ITEM_BROKEN errors reach T13 only through T14.

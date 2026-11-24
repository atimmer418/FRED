### Task 07: paycheck-event-store

`PaycheckEventService.applySyncBatch` turns one `/transactions/sync` buffer (the `added`, `modified` and `removed` arrays plus the `accounts` array, accumulated by T08 across every page of one sync) into `paycheck_events` rows: spec 4.8 without the Phase 2 cycle voids. Only inflow candidates are stored (Plaid `amount < 0` on an account whose type is `depository`); every write is replay-safe on `plaid_transaction_id`; a posted row whose `pending_transaction_id` names a stored pending event marks that pending event SUPERSEDED; `modified` updates amount, date, pending, descriptor and `modified_at`; `removed` sets REMOVED and `removed_at`. Every Plaid string is cut to its V6 column width before it reaches MySQL, and a counterparty whose confidence is LOW is never stored. This task makes no Plaid call, opens no transaction of its own (the method is `MANDATORY`; T08 calls it inside its `TransactionTemplate`), emits no analytics event, raises no ops alert, adds no property and writes nothing on `investment_schedules`.

Paths: `.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it under `backend/src/test/java/com/investingapp/backend/`. Anchors are HEAD `137998c`. This task only creates files, so no anchor in a file it edits can drift.

Facts this task depends on (restated so this file stands alone):

- **Data shape.** `paycheck_events` was created by `V6__paycheck_autopilot.sql:65-97` and has no migration change in Phase 1 (the only Phase 1 migration is V10, owned by T01; this task writes none). Widths: `plaid_transaction_id`, `pending_transaction_id`, `plaid_account_id` `varchar(80)`; `raw_descriptor` 255; `merchant_name`, `counterparty_name` 120; `counterparty_type` 32; `pfc_primary`, `pfc_detailed`, `pfc_confidence` 40; `payment_channel` 12; `ppd_id` 64; `amount`, `raw_amount` `decimal(12,2) NOT NULL`; `posted_date DATE NOT NULL`; `pending tinyint(1) NOT NULL`; `status varchar(10) NOT NULL`; `first_seen_at datetime(6) NOT NULL`. `UNIQUE uq_paycheck_events_plaid_transaction (plaid_transaction_id)` is global, not per user, so an insert of an id another user already holds would throw and roll back the whole sync.
- **Why widths matter.** MySQL runs in strict mode: an over-long string is an error, not a silent cut. The error would roll back T08's one-commit-per-sync transaction, the cursor would not advance, and the Item would fail on the same page every night. So every free-text Plaid string goes through `ColumnText.fit` (T02). Plaid ids are never cut, because a cut id could collide on the unique key: a transaction or account id longer than 80 is ignored, and an over-long `pending_transaction_id` is stored as null (spec 4.8: it is never required for matching).
- **Signs.** Plaid's `amount` is a `Double`, positive for money out and negative for money in. `raw_amount` keeps Plaid's value unmodified; `amount = -raw_amount` (spec 3.4, 4.1). Convert with `BigDecimal.valueOf(double)`, never `new BigDecimal(double)`, which carries binary noise (`-2418.37` becomes `-2418.3699999...`).
- **Pending to posted.** Plaid delivers the transition as the pending id in `removed[]` plus a new posted transaction in `added[]`, not as a modification; the posted row's `pending_transaction_id` is set only when Plaid matched the pair and is null at institutions without pending data (spec 4.8). This service applies `added`, then `modified`, then `removed`, so a posted twin supersedes its pending row before `removed[]` reaches it, and the pending row ends SUPERSEDED (with `removed_at` stamped), not REMOVED.
- **No Plaid HTTP inside a database transaction** (plan rule). This service has no Plaid client at all; T08 calls Plaid first, buffers the pages, then calls this method inside one `TransactionTemplate` under `userRepository.findByIdForUpdate`.
- **Money invariance.** Nothing here moves money, writes `InvestmentSchedule.status` or `mode`, writes a hold, or touches `InvestmentSchedule.isReadyForInvestment`. T03's `phaseOneSourcesNeverCallSetStatusOrSetMode` walks `service/Paycheck*`, so this file is in its scope: it fails on `setStatus(` followed by `Status.` or `InvestmentSchedule.Status.`. Always write `setStatus(PaycheckEventStatus.X)` with the qualified enum name (which that regex does not match), never a static import.
- **Privacy (spec 12.3, handoff 8.2).** These rows are payroll-deposit data: employer descriptor, amounts, dates. No log line at INFO or above, no ops alert and no analytics property may carry a descriptor, payer or counterparty name, amount, Plaid id or date. This service logs one WARN with a count only, and a DEBUG summary with the FRED user id and counts. `toString()` on any Plaid model prints every field; never log a model.
- **Spec 4.3 confidence rule at storage.** `counterparty_name` and `counterparty_type` come from `counterparties[0]` only, and only when its `confidence_level` is not `LOW`; otherwise both stay null. The confidence itself is not persisted, so T09's `PayerTokenizer.displayName` can trust whatever is stored.
- **Statuses.** `PaycheckEventStatus` is ACTIVE, REMOVED, MODIFIED, SUPERSEDED (spec 3.4). A modification keeps the row's status (ACTIVE): spec 4.1 reads ACTIVE rows and a restated paycheck is still a paycheck; `modified_at` records the change and `modifiedEventIds` hands it to Phase 2. MODIFIED is not written in Phase 1.
- **Phase 2 seam.** `ApplyResult.removedEventIds` and `modifiedEventIds` are what Phase 2 uses to void a CONFIRMED cycle (spec 4.8). Phase 1 has no CONFIRMED cycles, so no cycle is touched here.

**Files:**
- Create: `.../service/PaycheckEventService.java` (with the nested records `SyncBatch` and `ApplyResult`), `.../util/PlaidAmounts.java`
- Test: create `backend/src/test/java/com/investingapp/backend/service/PaycheckEventServiceTest.java`, `backend/src/test/java/com/investingapp/backend/util/PlaidAmountsTest.java`
- Modify: none. No property is added, so there is no `application-local.properties` note for this task.

**Interfaces:**
- Consumes (T02): `com.investingapp.backend.model.PaycheckEvent` (`@Getter @Setter @NoArgsConstructor`; fields `Long id`, `Long userId`, `Long streamId`, `String plaidTransactionId`, `String pendingTransactionId`, `String plaidAccountId`, `BigDecimal amount`, `BigDecimal rawAmount`, `LocalDate postedDate`, `LocalDate authorizedDate`, `boolean pending` (so `isPending()` / `setPending(boolean)`), `PaycheckEventStatus status`, `LocalDateTime firstSeenAt`, `LocalDateTime removedAt`, `LocalDateTime modifiedAt`, `String rawDescriptor`, `String merchantName`, `String counterpartyName`, `String counterpartyType`, `String pfcPrimary`, `String pfcDetailed`, `String pfcConfidence`, `String paymentChannel`, `String ppdId`); `com.investingapp.backend.model.PaycheckEventStatus {ACTIVE, REMOVED, MODIFIED, SUPERSEDED}`; `PaycheckEventRepository extends JpaRepository<PaycheckEvent, Long>` with `Optional<PaycheckEvent> findByPlaidTransactionId(String)`; `com.investingapp.backend.util.ColumnText.fit(String value, int width)` (null-safe). Plaid SDK 46.0.0 (verified with `javap` against the cached jar): `Transaction` (`getTransactionId`, `getPendingTransactionId`, `getAccountId`, `getAmount(): Double`, `getDate()`, `getAuthorizedDate()`, `getPending(): Boolean`, `getOriginalDescription`, `getName`, `getMerchantName`, `getCounterparties(): List<TransactionCounterparty>`, `getPersonalFinanceCategory()`, `getPaymentChannel(): Transaction.PaymentChannelEnum` with constants `ONLINE("online")`, `IN_STORE("in store")`, `OTHER("other")`, `getPaymentMeta(): PaymentMeta`); `TransactionCounterparty` (`getName`, `getType(): CounterpartyType`, `getConfidenceLevel(): String`); `CounterpartyType.INCOME_SOURCE.getValue()` is `"income_source"`; `PersonalFinanceCategory` (`getPrimary`, `getDetailed`, `getConfidenceLevel`, all `String`); `PaymentMeta.getPpdId()`; `RemovedTransaction` (`getTransactionId`, `getAccountId`); `AccountBase` (`getAccountId`, `getType(): AccountType`); top-level enum `com.plaid.client.model.AccountType` with `DEPOSITORY`.
- Produces: `PaycheckEventService(PaycheckEventRepository)` (`@Service`, no class-level `@Transactional`); `public record PaycheckEventService.SyncBatch(List<Transaction> added, List<Transaction> modified, List<RemovedTransaction> removed, List<AccountBase> accounts)` (null lists become empty); `public record PaycheckEventService.ApplyResult(List<Long> newPostedInflowEventIds, List<Long> removedEventIds, List<Long> modifiedEventIds, int ignored)`; `@Transactional(propagation = Propagation.MANDATORY) public ApplyResult applySyncBatch(Long userId, SyncBatch batch, LocalDateTime nowET)`; `public static PaycheckEvent fromTransaction(Long userId, Transaction t, LocalDateTime nowET)`; `com.investingapp.backend.util.PlaidAmounts.inflow(Double): BigDecimal`. T08 imports the records as `com.investingapp.backend.service.PaycheckEventService.SyncBatch` and `...ApplyResult`.

`ApplyResult` meaning, exactly: `newPostedInflowEventIds` = ids of rows inserted by this batch with `pending = false` (all inserted rows are inflows), plus stored pending rows that a `modified` entry flipped to posted while still an inflow (spec 4.7 acts on every newly posted ACTIVE event; T14 passes this list to `evaluateAndApply`); `removedEventIds` = rows this batch moved to REMOVED; `modifiedEventIds` = stored rows this batch updated from `modified`; `ignored` = every entry that produced no write (a duplicate `added`, an outflow, a zero amount, a non-depository or unknown account, a missing date, an over-long id, a `modified` on a REMOVED or SUPERSEDED row, a removal of an unknown or already removed id, and any entry whose stored row belongs to another user).

- [ ] **Step 0: Confirm T02 is in the tree (2 minutes)**

From the worktree root:

```
grep -c -E 'private (Long|String|BigDecimal|LocalDate|LocalDateTime|boolean|PaycheckEventStatus) (id|userId|streamId|plaidTransactionId|pendingTransactionId|plaidAccountId|amount|rawAmount|postedDate|authorizedDate|pending|status|firstSeenAt|removedAt|modifiedAt|rawDescriptor|merchantName|counterpartyName|counterpartyType|pfcPrimary|pfcDetailed|pfcConfidence|paymentChannel|ppdId)[ ;=]' backend/src/main/java/com/investingapp/backend/model/PaycheckEvent.java
grep -n 'Optional<PaycheckEvent> findByPlaidTransactionId(String' backend/src/main/java/com/investingapp/backend/repository/PaycheckEventRepository.java
grep -o -E 'ACTIVE|REMOVED|MODIFIED|SUPERSEDED' backend/src/main/java/com/investingapp/backend/model/PaycheckEventStatus.java | sort -u | wc -l
grep -n 'public static String fit(String value, int width)' backend/src/main/java/com/investingapp/backend/util/ColumnText.java
```

Expected: `24`; one line; `4`; one line. If any count differs, stop: T02 has not landed or named a field differently, and the code below must follow T02's names, not the other way round.

- [ ] **Step 1: Failing test for PlaidAmounts (2 minutes)**

Create `backend/src/test/java/com/investingapp/backend/util/PlaidAmountsTest.java`:

```java
package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plaid sends money as a Double with its own sign: positive is money out, negative is money in.
 * A paycheck of 2418.37 arrives as -2418.37 and must be stored as exactly 2418.37.
 */
class PlaidAmountsTest {

    @Test
    void inflowNegatesPlaidSignExactly() {
        assertThat(PlaidAmounts.inflow(-2418.37).toPlainString()).isEqualTo("2418.37");
        assertThat(PlaidAmounts.inflow(54.10)).isEqualByComparingTo("-54.10");
        // Why valueOf: the double constructor keeps the binary expansion of the literal.
        assertThat(new BigDecimal(-2418.37).negate().toPlainString()).isNotEqualTo("2418.37");
    }

    @Test
    void nullStaysNull() {
        assertThat(PlaidAmounts.inflow(null)).isNull();
    }
}
```

From the worktree root (every command in this task runs from there; `backend` is only ever entered inside a subshell), run `(cd backend && ./gradlew test --tests 'com.investingapp.backend.util.PlaidAmountsTest' --console=plain)`. Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   variable PlaidAmounts`.

- [ ] **Step 2: PlaidAmounts (2 minutes)**

Create `backend/src/main/java/com/investingapp/backend/util/PlaidAmounts.java`:

```java
package com.investingapp.backend.util;

import java.math.BigDecimal;

/**
 * Plaid money arrives as a Double with Plaid's sign: positive is money out, negative is money in
 * (a paycheck is negative). FRED stores Plaid's value as raw_amount and the inflow as amount.
 */
public final class PlaidAmounts {

    private PlaidAmounts() {
    }

    /**
     * The inflow for a Plaid amount: -2418.37 becomes 2418.37. Null stays null.
     * BigDecimal.valueOf goes through Double.toString, so the cents survive exactly; the
     * BigDecimal(double) constructor would carry the binary expansion into a decimal(12,2) column.
     */
    public static BigDecimal inflow(Double plaidAmount) {
        return plaidAmount == null ? null : BigDecimal.valueOf(plaidAmount).negate();
    }
}
```

Run the Step 1 command. Expected: `BUILD SUCCESSFUL`. Read the count from the XML, not the console: `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.util.PlaidAmountsTest.xml` prints `tests="2" skipped="0" failures="0" errors="0"`.

- [ ] **Step 3: Failing tests for PaycheckEventService (5 minutes)**

The repository is a Mockito mock backed by an in-memory map, so a lookup after a save sees the saved row, as Hibernate's auto-flush before a query does inside one transaction.

Create `backend/src/test/java/com/investingapp/backend/service/PaycheckEventServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.service.PaycheckEventService.ApplyResult;
import com.investingapp.backend.service.PaycheckEventService.SyncBatch;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.CounterpartyType;
import com.plaid.client.model.PaymentMeta;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionCounterparty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 4.8 without the Phase 2 cycle voids: inflow candidates on depository accounts only,
 * replay-safe on the Plaid id, pending rows superseded by their posted twin, every Plaid string
 * cut to its V6 width so strict MySQL never rolls back a sync.
 */
class PaycheckEventServiceTest {

    private static final Long USER = 7L;
    private static final Long OTHER_USER = 8L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 22, 30);
    private static final LocalDate D1 = LocalDate.of(2026, 10, 30);
    private static final LocalDate D2 = LocalDate.of(2026, 11, 13);
    private static final String CHECKING = "acct-checking";
    private static final String CARD = "acct-card";

    private final Map<Long, PaycheckEvent> store = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(100);
    private PaycheckEventRepository repository;
    private PaycheckEventService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaycheckEventRepository.class);
        when(repository.findByPlaidTransactionId(anyString())).thenAnswer(inv -> {
            String plaidId = inv.getArgument(0);
            return store.values().stream().filter(e -> plaidId.equals(e.getPlaidTransactionId())).findFirst();
        });
        when(repository.save(any(PaycheckEvent.class))).thenAnswer(inv -> {
            PaycheckEvent e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.incrementAndGet());
            }
            store.put(e.getId(), e);
            return e;
        });
        service = new PaycheckEventService(repository);
    }

    private static List<AccountBase> accounts() {
        return List.of(new AccountBase().accountId(CHECKING).type(AccountType.DEPOSITORY),
                new AccountBase().accountId(CARD).type(AccountType.CREDIT));
    }

    private static Transaction deposit(String plaidId, double plaidAmount, LocalDate date) {
        return new Transaction().transactionId(plaidId).accountId(CHECKING).amount(plaidAmount).date(date)
                .pending(false).originalDescription("ACME CORP PAYROLL 2418 PPD").name("ACME CORP");
    }

    private static SyncBatch added(Transaction... transactions) {
        return new SyncBatch(List.of(transactions), List.of(), List.of(), accounts());
    }

    private static SyncBatch modified(Transaction... transactions) {
        return new SyncBatch(List.of(), List.of(transactions), List.of(), accounts());
    }

    private static RemovedTransaction removal(String plaidId) {
        return new RemovedTransaction().transactionId(plaidId).accountId(CHECKING);
    }

    private PaycheckEvent byPlaidId(String plaidId) {
        return store.values().stream().filter(e -> plaidId.equals(e.getPlaidTransactionId())).findFirst().orElseThrow();
    }

    @Test
    void addedDuplicateIsNoOp() {
        ApplyResult first = service.applySyncBatch(USER, added(deposit("tx-1", -2418.37, D1)), NOW);
        ApplyResult replay = service.applySyncBatch(USER, added(deposit("tx-1", -2418.37, D1)), NOW.plusHours(1));

        assertThat(store).hasSize(1);
        assertThat(first.newPostedInflowEventIds()).hasSize(1);
        assertThat(replay.newPostedInflowEventIds()).isEmpty();
        assertThat(replay.ignored()).isEqualTo(1);
        assertThat(byPlaidId("tx-1").getFirstSeenAt()).isEqualTo(NOW);
        verify(repository, times(1)).save(any());
    }

    @Test
    void postedWithoutPendingIdStillStored() {
        ApplyResult result = service.applySyncBatch(USER, added(deposit("tx-1", -2418.37, D1)), NOW);

        PaycheckEvent row = byPlaidId("tx-1");
        assertThat(row.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(row.isPending()).isFalse();
        assertThat(row.getPendingTransactionId()).isNull();
        assertThat(row.getAmount()).isEqualByComparingTo("2418.37");
        assertThat(row.getRawAmount()).isEqualByComparingTo("-2418.37");
        assertThat(result.newPostedInflowEventIds()).containsExactly(row.getId());
        assertThat(result.ignored()).isZero();
        // Only the replay lookup: no pending twin is searched for when Plaid gave no pending id.
        verify(repository, times(1)).findByPlaidTransactionId(anyString());
    }

    @Test
    void postedWithPendingIdSupersedesPending() {
        ApplyResult first = service.applySyncBatch(USER, added(deposit("tx-pending", -2418.37, D1).pending(true)), NOW);
        assertThat(first.newPostedInflowEventIds()).isEmpty();

        // Plaid's real shape: the pending id in removed[] and its posted twin in added[], one batch.
        Transaction posted = deposit("tx-posted", -2418.37, D2).pendingTransactionId("tx-pending");
        SyncBatch transition = new SyncBatch(List.of(posted), List.of(), List.of(removal("tx-pending")), accounts());
        ApplyResult second = service.applySyncBatch(USER, transition, NOW.plusDays(1));

        PaycheckEvent pendingRow = byPlaidId("tx-pending");
        PaycheckEvent postedRow = byPlaidId("tx-posted");
        assertThat(pendingRow.getStatus()).isEqualTo(PaycheckEventStatus.SUPERSEDED);
        assertThat(pendingRow.getRemovedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(postedRow.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(postedRow.getPendingTransactionId()).isEqualTo("tx-pending");
        assertThat(second.newPostedInflowEventIds()).containsExactly(postedRow.getId());
        assertThat(second.removedEventIds()).isEmpty();
        assertThat(second.ignored()).isZero();
    }

    @Test
    void outflowAndNonDepositoryIgnored() {
        Transaction outflow = deposit("tx-out", 54.10, D1);
        Transaction cardCredit = deposit("tx-card", -80.00, D1).accountId(CARD);
        Transaction unknownAccount = deposit("tx-unknown", -900.00, D1).accountId("acct-not-in-batch");
        Transaction zero = deposit("tx-zero", 0.0, D1);
        Transaction noDate = deposit("tx-nodate", -900.00, D1).date(null);

        ApplyResult result = service.applySyncBatch(USER, added(outflow, cardCredit, unknownAccount, zero, noDate), NOW);

        assertThat(result.ignored()).isEqualTo(5);
        assertThat(result.newPostedInflowEventIds()).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void lowConfidenceCounterpartyNotStored() {
        Transaction low = deposit("tx-low", -2418.37, D1).counterparties(List.of(
                new TransactionCounterparty().name("Acme Corp").type(CounterpartyType.INCOME_SOURCE).confidenceLevel("LOW")));
        Transaction high = deposit("tx-high", -2418.37, D2).counterparties(List.of(
                new TransactionCounterparty().name("Acme Corp").type(CounterpartyType.INCOME_SOURCE).confidenceLevel("HIGH"),
                new TransactionCounterparty().name("Second Bank").type(CounterpartyType.FINANCIAL_INSTITUTION).confidenceLevel("VERY_HIGH")));

        service.applySyncBatch(USER, added(low, high), NOW);

        assertThat(byPlaidId("tx-low").getCounterpartyName()).isNull();
        assertThat(byPlaidId("tx-low").getCounterpartyType()).isNull();
        assertThat(byPlaidId("tx-high").getCounterpartyName()).isEqualTo("Acme Corp");
        assertThat(byPlaidId("tx-high").getCounterpartyType()).isEqualTo("income_source");
    }

    @Test
    void overlongPlaidStringsAreFittedNotRejected() {
        String longText = "X".repeat(400);
        Transaction t = deposit("tx-long", -2418.37, D1)
                .originalDescription(longText)
                .merchantName(longText)
                .counterparties(List.of(new TransactionCounterparty().name(longText)
                        .type(CounterpartyType.INCOME_SOURCE).confidenceLevel("HIGH")))
                .personalFinanceCategory(new PersonalFinanceCategory().primary(longText).detailed(longText).confidenceLevel(longText))
                .paymentMeta(new PaymentMeta().ppdId(longText))
                .pendingTransactionId("p".repeat(81));
        Transaction longId = deposit("t".repeat(81), -2418.37, D1);

        ApplyResult result = service.applySyncBatch(USER, added(t, longId), NOW);

        PaycheckEvent row = byPlaidId("tx-long");
        assertThat(row.getRawDescriptor()).hasSize(255);
        assertThat(row.getMerchantName()).hasSize(120);
        assertThat(row.getCounterpartyName()).hasSize(120);
        assertThat(row.getPfcPrimary()).hasSize(40);
        assertThat(row.getPfcDetailed()).hasSize(40);
        assertThat(row.getPfcConfidence()).hasSize(40);
        assertThat(row.getPpdId()).hasSize(64);
        assertThat(row.getPendingTransactionId()).isNull(); // ids are never cut; an over-long pending id is dropped
        assertThat(result.newPostedInflowEventIds()).containsExactly(row.getId());
        assertThat(result.ignored()).isEqualTo(1); // the over-long transaction id
        assertThat(store).hasSize(1);
    }

    @Test
    void requiresCallerTransaction() throws Exception {
        Method method = PaycheckEventService.class.getMethod("applySyncBatch", Long.class, SyncBatch.class, LocalDateTime.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(PaycheckEventService.class.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void modifiedUpdatesAmountDatePendingDescriptor() {
        service.applySyncBatch(USER, added(deposit("tx-1", -2418.37, D1).pending(true)), NOW);

        Transaction restated = deposit("tx-1", -2400.00, D2).originalDescription("ACME CORP DIR DEP");
        ApplyResult result = service.applySyncBatch(USER, modified(restated), NOW.plusDays(1));

        PaycheckEvent row = byPlaidId("tx-1");
        assertThat(row.getAmount()).isEqualByComparingTo("2400.00");
        assertThat(row.getRawAmount()).isEqualByComparingTo("-2400.00");
        assertThat(row.getPostedDate()).isEqualTo(D2);
        assertThat(row.isPending()).isFalse();
        assertThat(row.getRawDescriptor()).isEqualTo("ACME CORP DIR DEP");
        assertThat(row.getModifiedAt()).isEqualTo(NOW.plusDays(1));
        assertThat(row.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(row.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(result.modifiedEventIds()).containsExactly(row.getId());
        // The flip from pending to posted is a newly posted deposit for spec 4.7.
        assertThat(result.newPostedInflowEventIds()).containsExactly(row.getId());
        assertThat(result.ignored()).isZero();
    }

    @Test
    void modifiedFirstSeenInflowIsInserted() {
        ApplyResult result = service.applySyncBatch(USER,
                modified(deposit("tx-new", -1500.00, D1), deposit("tx-out", 25.00, D1)), NOW);

        PaycheckEvent row = byPlaidId("tx-new");
        assertThat(row.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(row.getModifiedAt()).isNull();
        assertThat(result.newPostedInflowEventIds()).containsExactly(row.getId());
        assertThat(result.modifiedEventIds()).isEmpty();
        assertThat(result.ignored()).isEqualTo(1);
        assertThat(store).hasSize(1);
    }

    @Test
    void removedMarksRemovedOnceAndReplayIsNoOp() {
        service.applySyncBatch(USER, added(deposit("tx-1", -2418.37, D1)), NOW);
        SyncBatch removals = new SyncBatch(List.of(), List.of(), List.of(removal("tx-1"), removal("tx-never-stored")), accounts());

        ApplyResult result = service.applySyncBatch(USER, removals, NOW.plusHours(2));
        PaycheckEvent row = byPlaidId("tx-1");
        assertThat(row.getStatus()).isEqualTo(PaycheckEventStatus.REMOVED);
        assertThat(row.getRemovedAt()).isEqualTo(NOW.plusHours(2));
        assertThat(result.removedEventIds()).containsExactly(row.getId());
        assertThat(result.ignored()).isEqualTo(1);

        ApplyResult replay = service.applySyncBatch(USER, removals, NOW.plusHours(3));
        assertThat(replay.removedEventIds()).isEmpty();
        assertThat(replay.ignored()).isEqualTo(2);
        assertThat(byPlaidId("tx-1").getRemovedAt()).isEqualTo(NOW.plusHours(2));
    }

    @Test
    void anotherUsersRowIsNeverTouched() {
        service.applySyncBatch(OTHER_USER, added(deposit("tx-shared", -1000.00, D1)), NOW);

        SyncBatch batch = new SyncBatch(List.of(deposit("tx-shared", -1000.00, D1)),
                List.of(deposit("tx-shared", -5.00, D2)), List.of(removal("tx-shared")), accounts());
        ApplyResult result = service.applySyncBatch(USER, batch, NOW.plusHours(1));

        PaycheckEvent row = byPlaidId("tx-shared");
        assertThat(row.getUserId()).isEqualTo(OTHER_USER);
        assertThat(row.getAmount()).isEqualByComparingTo("1000.00");
        assertThat(row.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(row.getModifiedAt()).isNull();
        assertThat(row.getRemovedAt()).isNull();
        assertThat(result.ignored()).isEqualTo(3);
        assertThat(result.newPostedInflowEventIds()).isEmpty();
        assertThat(result.modifiedEventIds()).isEmpty();
        assertThat(result.removedEventIds()).isEmpty();
    }

    @Test
    void fromTransactionMapsEveryField() {
        Transaction t = new Transaction().transactionId("tx-9").pendingTransactionId("tx-8").accountId(CHECKING)
                .amount(-1523.5).date(D2).authorizedDate(D1).pending(false)
                .originalDescription("  ").name("GUSTO PAYROLL").merchantName("Gusto")
                .personalFinanceCategory(new PersonalFinanceCategory().primary("INCOME").detailed("INCOME_SALARY").confidenceLevel("VERY_HIGH"))
                .paymentChannel(Transaction.PaymentChannelEnum.IN_STORE)
                .paymentMeta(new PaymentMeta().ppdId("9101112131"));

        PaycheckEvent e = PaycheckEventService.fromTransaction(USER, t, NOW);

        assertThat(e.getId()).isNull();
        assertThat(e.getStreamId()).isNull();
        assertThat(e.getUserId()).isEqualTo(USER);
        assertThat(e.getPlaidTransactionId()).isEqualTo("tx-9");
        assertThat(e.getPendingTransactionId()).isEqualTo("tx-8");
        assertThat(e.getPlaidAccountId()).isEqualTo(CHECKING);
        assertThat(e.getRawAmount()).isEqualByComparingTo("-1523.5");
        assertThat(e.getAmount()).isEqualByComparingTo("1523.5");
        assertThat(e.getPostedDate()).isEqualTo(D2);
        assertThat(e.getAuthorizedDate()).isEqualTo(D1);
        assertThat(e.isPending()).isFalse();
        assertThat(e.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(e.getFirstSeenAt()).isEqualTo(NOW);
        assertThat(e.getRemovedAt()).isNull();
        assertThat(e.getModifiedAt()).isNull();
        assertThat(e.getRawDescriptor()).isEqualTo("GUSTO PAYROLL"); // blank original_description falls back to name
        assertThat(e.getMerchantName()).isEqualTo("Gusto");
        assertThat(e.getCounterpartyName()).isNull();
        assertThat(e.getCounterpartyType()).isNull();
        assertThat(e.getPfcPrimary()).isEqualTo("INCOME");
        assertThat(e.getPfcDetailed()).isEqualTo("INCOME_SALARY");
        assertThat(e.getPfcConfidence()).isEqualTo("VERY_HIGH");
        assertThat(e.getPaymentChannel()).isEqualTo("in store"); // the wire value, not the enum name
        assertThat(e.getPpdId()).isEqualTo("9101112131");
    }
}
```

Run `(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckEventServiceTest' --console=plain)`. Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class PaycheckEventService` (the field and the `new PaycheckEventService(...)` lines), plus `error: package com.investingapp.backend.service.PaycheckEventService does not exist` on the two nested-record import lines.

- [ ] **Step 4: PaycheckEventService (5 minutes)**

Create `backend/src/main/java/com/investingapp/backend/service/PaycheckEventService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.PlaidAmounts;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.PaymentMeta;
import com.plaid.client.model.PersonalFinanceCategory;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionCounterparty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns one /transactions/sync buffer into paycheck_events rows (spec 3.4, 4.8; Phase 1 has no
 * cycle voids, so removedEventIds and modifiedEventIds are handed back for Phase 2).
 *
 * <p>Only inflow candidates are stored: Plaid amount below zero on a depository account. Pending
 * inflows are stored too, so their posted twin can supersede them. Every write is replay-safe on
 * plaid_transaction_id, which is unique across the whole table. The order is added, then modified,
 * then removed, so a posted twin supersedes its pending row before removed[] reaches it.
 *
 * <p>MANDATORY: the caller (PlaidTransactionsSyncService) holds the user row lock in its own transaction and makes every
 * Plaid call before opening it. These rows are payroll data: nothing here logs a descriptor,
 * name, amount, Plaid id or date.
 */
@Service
public class PaycheckEventService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckEventService.class);

    /** Column widths of paycheck_events as V6 built them (V6__paycheck_autopilot.sql:65-97). */
    static final int ID_WIDTH = 80;
    static final int RAW_DESCRIPTOR_WIDTH = 255;
    static final int MERCHANT_NAME_WIDTH = 120;
    static final int COUNTERPARTY_NAME_WIDTH = 120;
    static final int COUNTERPARTY_TYPE_WIDTH = 32;
    static final int PFC_WIDTH = 40;
    static final int PAYMENT_CHANNEL_WIDTH = 12;
    static final int PPD_ID_WIDTH = 64;

    /** Plaid's counterparty confidence below which the name is not trusted (spec 4.3). */
    static final String LOW_CONFIDENCE = "LOW";

    private final PaycheckEventRepository eventRepository;

    public PaycheckEventService(PaycheckEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** Everything one sync returned, across all its pages. Null lists become empty. */
    public record SyncBatch(List<Transaction> added, List<Transaction> modified,
                            List<RemovedTransaction> removed, List<AccountBase> accounts) {
        public SyncBatch {
            added = copy(added);
            modified = copy(modified);
            removed = copy(removed);
            accounts = copy(accounts);
        }

        private static <T> List<T> copy(List<T> list) {
            return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    /**
     * newPostedInflowEventIds: rows inserted posted, plus stored pending rows a modification turned
     * posted (spec 4.7 acts on both). removedEventIds: rows moved to REMOVED. modifiedEventIds:
     * stored rows updated from modified[]. ignored: entries that produced no write.
     */
    public record ApplyResult(List<Long> newPostedInflowEventIds, List<Long> removedEventIds,
                              List<Long> modifiedEventIds, int ignored) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ApplyResult applySyncBatch(Long userId, SyncBatch batch, LocalDateTime nowET) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(batch, "batch");
        Map<String, AccountType> accountTypes = accountTypes(batch.accounts());
        Tally tally = new Tally();

        for (Transaction t : batch.added()) {
            if (!usableId(t)) {
                tally.ignored++;
                continue;
            }
            Optional<PaycheckEvent> stored = eventRepository.findByPlaidTransactionId(t.getTransactionId());
            if (stored.isPresent()) {
                // Replay (a cursor restart re-sends added[]): the first row stands.
                if (!userId.equals(stored.get().getUserId())) {
                    tally.foreign++;
                }
                tally.ignored++;
            } else if (!insertCandidate(userId, t, accountTypes, nowET, tally)) {
                tally.ignored++;
            }
        }

        for (Transaction t : batch.modified()) {
            if (!usableId(t)) {
                tally.ignored++;
                continue;
            }
            Optional<PaycheckEvent> stored = eventRepository.findByPlaidTransactionId(t.getTransactionId());
            boolean written = stored.isPresent()
                    ? applyModification(userId, stored.get(), t, nowET, tally)
                    // A transaction ignored when added (an outflow, say) can come back as an inflow.
                    : insertCandidate(userId, t, accountTypes, nowET, tally);
            if (!written) {
                tally.ignored++;
            }
        }

        for (RemovedTransaction r : batch.removed()) {
            if (r == null || r.getTransactionId() == null) {
                tally.ignored++;
                continue;
            }
            Optional<PaycheckEvent> stored = eventRepository.findByPlaidTransactionId(r.getTransactionId());
            if (stored.isEmpty() || !applyRemoval(userId, stored.get(), nowET, tally)) {
                tally.ignored++;
            }
        }

        if (tally.foreign > 0) {
            logger.warn("paycheck.events.foreign_rows count={}", tally.foreign);
        }
        logger.debug("paycheck.events.applied user={} posted={} modified={} removed={} ignored={}",
                userId, tally.posted.size(), tally.modified.size(), tally.removed.size(), tally.ignored);
        return new ApplyResult(List.copyOf(tally.posted), List.copyOf(tally.removed),
                List.copyOf(tally.modified), tally.ignored);
    }

    /**
     * One Plaid transaction as a new ACTIVE row. Descriptor is original_description, else name;
     * the counterparty is counterparties[0] unless its confidence is LOW (then both fields stay
     * null); enum-backed strings are the wire value (getValue()); every string is cut to its V6
     * width. Ids are never cut: an over-long pending id is dropped, since matching never needs it.
     */
    public static PaycheckEvent fromTransaction(Long userId, Transaction t, LocalDateTime nowET) {
        PaycheckEvent e = new PaycheckEvent();
        e.setUserId(userId);
        e.setPlaidTransactionId(t.getTransactionId());
        e.setPendingTransactionId(idOrNull(t.getPendingTransactionId()));
        e.setPlaidAccountId(t.getAccountId());
        e.setRawAmount(t.getAmount() == null ? null : BigDecimal.valueOf(t.getAmount()));
        e.setAmount(PlaidAmounts.inflow(t.getAmount()));
        e.setPostedDate(t.getDate());
        e.setAuthorizedDate(t.getAuthorizedDate());
        e.setPending(Boolean.TRUE.equals(t.getPending()));
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(nowET);
        e.setRawDescriptor(ColumnText.fit(descriptor(t), RAW_DESCRIPTOR_WIDTH));
        e.setMerchantName(ColumnText.fit(t.getMerchantName(), MERCHANT_NAME_WIDTH));

        TransactionCounterparty counterparty = trustedCounterparty(t);
        if (counterparty != null) {
            e.setCounterpartyName(ColumnText.fit(counterparty.getName(), COUNTERPARTY_NAME_WIDTH));
            e.setCounterpartyType(ColumnText.fit(
                    counterparty.getType() == null ? null : counterparty.getType().getValue(), COUNTERPARTY_TYPE_WIDTH));
        }

        PersonalFinanceCategory pfc = t.getPersonalFinanceCategory();
        if (pfc != null) {
            e.setPfcPrimary(ColumnText.fit(pfc.getPrimary(), PFC_WIDTH));
            e.setPfcDetailed(ColumnText.fit(pfc.getDetailed(), PFC_WIDTH));
            e.setPfcConfidence(ColumnText.fit(pfc.getConfidenceLevel(), PFC_WIDTH));
        }

        if (t.getPaymentChannel() != null) {
            e.setPaymentChannel(ColumnText.fit(t.getPaymentChannel().getValue(), PAYMENT_CHANNEL_WIDTH));
        }

        PaymentMeta meta = t.getPaymentMeta();
        if (meta != null) {
            e.setPpdId(ColumnText.fit(meta.getPpdId(), PPD_ID_WIDTH));
        }
        return e;
    }

    /** Spec 4.2 gates (1) and (2) at storage: money in, on a depository account, with a date. */
    static boolean isInflowCandidate(Transaction t, Map<String, AccountType> accountTypes) {
        return t.getAmount() != null && t.getAmount() < 0
                && t.getDate() != null
                && t.getAccountId() != null && t.getAccountId().length() <= ID_WIDTH
                && accountTypes.get(t.getAccountId()) == AccountType.DEPOSITORY;
    }

    static String descriptor(Transaction t) {
        String original = t.getOriginalDescription();
        return original != null && !original.isBlank() ? original : t.getName();
    }

    static TransactionCounterparty trustedCounterparty(Transaction t) {
        List<TransactionCounterparty> parties = t.getCounterparties();
        if (parties == null || parties.isEmpty() || parties.get(0) == null) {
            return null;
        }
        TransactionCounterparty first = parties.get(0);
        return LOW_CONFIDENCE.equalsIgnoreCase(first.getConfidenceLevel()) ? null : first;
    }

    private boolean insertCandidate(Long userId, Transaction t, Map<String, AccountType> accountTypes,
                                    LocalDateTime nowET, Tally tally) {
        if (!isInflowCandidate(t, accountTypes)) {
            return false;
        }
        PaycheckEvent saved = eventRepository.save(fromTransaction(userId, t, nowET));
        if (!saved.isPending()) {
            tally.posted.add(saved.getId());
            supersedePendingTwin(userId, saved);
        }
        return true;
    }

    /** The posted row's pending_transaction_id is the link; the pending row is marked SUPERSEDED. */
    private void supersedePendingTwin(Long userId, PaycheckEvent posted) {
        String pendingId = posted.getPendingTransactionId();
        if (pendingId == null) {
            return; // institutions without pending data: nothing to link (spec 4.8)
        }
        eventRepository.findByPlaidTransactionId(pendingId)
                .filter(p -> userId.equals(p.getUserId()))
                .filter(p -> p.isPending() && p.getStatus() != PaycheckEventStatus.SUPERSEDED)
                .ifPresent(p -> {
                    p.setStatus(PaycheckEventStatus.SUPERSEDED);
                    eventRepository.save(p);
                });
    }

    /**
     * Spec 4.8 modified: amount, date, pending, descriptor and modified_at. The status is kept
     * (MODIFIED is not written in Phase 1); amount stays -raw_amount, so a restatement to money out
     * reads negative and spec 4.1's raw_amount filter drops it.
     */
    private boolean applyModification(Long userId, PaycheckEvent e, Transaction t, LocalDateTime nowET, Tally tally) {
        if (!userId.equals(e.getUserId())) {
            tally.foreign++;
            return false;
        }
        if (e.getStatus() == PaycheckEventStatus.REMOVED || e.getStatus() == PaycheckEventStatus.SUPERSEDED) {
            return false;
        }
        if (t.getAmount() == null || t.getDate() == null) {
            return false;
        }
        boolean wasPending = e.isPending();
        e.setRawAmount(BigDecimal.valueOf(t.getAmount()));
        e.setAmount(PlaidAmounts.inflow(t.getAmount()));
        e.setPostedDate(t.getDate());
        e.setPending(Boolean.TRUE.equals(t.getPending()));
        e.setRawDescriptor(ColumnText.fit(descriptor(t), RAW_DESCRIPTOR_WIDTH));
        e.setModifiedAt(nowET);
        eventRepository.save(e);
        tally.modified.add(e.getId());
        if (wasPending && !e.isPending() && e.getRawAmount().signum() < 0) {
            tally.posted.add(e.getId());
        }
        return true;
    }

    /**
     * Spec 4.8 removed: REMOVED with removed_at, once. A SUPERSEDED pending row keeps its status
     * (its posted twin carries on) and only gets removed_at; it never confirmed anything.
     */
    private boolean applyRemoval(Long userId, PaycheckEvent e, LocalDateTime nowET, Tally tally) {
        if (!userId.equals(e.getUserId())) {
            tally.foreign++;
            return false;
        }
        if (e.getRemovedAt() != null) {
            return false; // replay
        }
        e.setRemovedAt(nowET);
        if (e.getStatus() != PaycheckEventStatus.SUPERSEDED) {
            e.setStatus(PaycheckEventStatus.REMOVED);
            tally.removed.add(e.getId());
        }
        eventRepository.save(e);
        return true;
    }

    private static boolean usableId(Transaction t) {
        return t != null && t.getTransactionId() != null && t.getTransactionId().length() <= ID_WIDTH;
    }

    private static String idOrNull(String id) {
        return id != null && id.length() <= ID_WIDTH ? id : null;
    }

    private static Map<String, AccountType> accountTypes(List<AccountBase> accounts) {
        Map<String, AccountType> types = new HashMap<>();
        for (AccountBase account : accounts) {
            if (account != null && account.getAccountId() != null && account.getType() != null) {
                types.put(account.getAccountId(), account.getType());
            }
        }
        return types;
    }

    private static final class Tally {
        final List<Long> posted = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final List<Long> modified = new ArrayList<>();
        int ignored;
        int foreign;
    }
}
```

Notes for the implementer:
- Every Plaid model class is imported by name. Do not use `com.plaid.client.model.*` here: `PlaidService.java:18` already shows how that wildcard makes FRED names ambiguous.
- `setStatus(PaycheckEventStatus.X)` is always written qualified (see the money-invariance fact above).
- Called without a transaction, Spring throws `IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'`. That is the intended failure for a caller that forgot its template; T08's test covers it through its own template.

- [ ] **Step 5: Run the service tests (2 minutes)**

Run the Step 3 command. Expected: `BUILD SUCCESSFUL`, and `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.service.PaycheckEventServiceTest.xml` prints `tests="12" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Context boot on a scratch MySQL (5 minutes)**

The new `@Service` must resolve in the full context (`PaycheckEventRepository` from T02 is its only dependency). `BackendApplicationTests` is `@RequiresCiDatabase`, so without a database it skips and proves nothing. Run the whole block below as ONE shell invocation from the worktree root (variables and the `db` function do not survive between invocations). It reads the local MySQL user and password from the gitignored `application-local.properties` into shell variables without printing them (key names only; never echo, cat or log the values), creates a throwaway schema (never `investing_dev`), runs the boot, and drops the schema:

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_scratch_t07; CREATE DATABASE fred_scratch_t07"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_scratch_t07?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
db -e "DROP DATABASE fred_scratch_t07"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_USER MYSQL_PW
```

Expected: `BUILD SUCCESSFUL` and `tests="1" skipped="0" failures="0" errors="0"`. Read the XML, never the console. `skipped="1"` means the database was not reached (the XML's `<skipped message=...>` gives the cause): fix the connection and re-run; a skipped boot is not a pass. A `Schema-validation:` failure names a T02 mapping, not this task: stop and report it.

- [ ] **Step 7: Hygiene checks (2 minutes)**

From the worktree root:

```
grep -n $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/service/PaycheckEventService.java backend/src/main/java/com/investingapp/backend/util/PlaidAmounts.java backend/src/test/java/com/investingapp/backend/service/PaycheckEventServiceTest.java backend/src/test/java/com/investingapp/backend/util/PlaidAmountsTest.java
grep -n -E 'logger\.(info|warn|error)' backend/src/main/java/com/investingapp/backend/service/PaycheckEventService.java
grep -n -E 'analyticsOutboxService|opsAlertService|setHoldReason|setMode\(|getIsPaused|isPausedForAnyReason|PlaidApi' backend/src/main/java/com/investingapp/backend/service/PaycheckEventService.java
test -f backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java && (cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain)
```

Expected: the first grep prints nothing (no em dash); the second prints exactly one line, the `logger.warn("paycheck.events.foreign_rows count={}", ...)` call, which carries a count only; the third prints nothing (no analytics, no alert, no hold or mode write, no Plaid client); the last command prints nothing when T03 has not landed yet, and otherwise ends `BUILD SUCCESSFUL` with `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml` showing `failures="0"` and `skipped="0"` (this file is inside its `service/Paycheck*` walk).

- [ ] **Step 8: Commit (2 minutes)**

Hunk-verify with `git status --short` that the four files below are the only ones this task created (the tree is shared with other sessions; stage nothing you did not write, never `ITPM/agent-memory/*` or `application-local.properties`; never `git checkout`, `git restore`, `git stash` or `git reset`), then:

```
git add backend/src/main/java/com/investingapp/backend/service/PaycheckEventService.java backend/src/main/java/com/investingapp/backend/util/PlaidAmounts.java backend/src/test/java/com/investingapp/backend/service/PaycheckEventServiceTest.java backend/src/test/java/com/investingapp/backend/util/PlaidAmountsTest.java
git commit -m "feat(paycheck): store sync batches as paycheck events, replay-safe on the Plaid id"
```

### Verification

```
(cd backend && ./gradlew test --tests 'com.investingapp.backend.util.PlaidAmountsTest' --console=plain)
(cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckEventServiceTest' --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.util.PlaidAmountsTest.xml backend/build/test-results/test/TEST-com.investingapp.backend.service.PaycheckEventServiceTest.xml
```

Run from the worktree root. Expected: `BUILD SUCCESSFUL` twice; `tests="2" skipped="0" failures="0" errors="0"` (PlaidAmountsTest: `inflowNegatesPlaidSignExactly`, `nullStaysNull`) and `tests="12" skipped="0" failures="0" errors="0"` (PaycheckEventServiceTest: the seven contract cases `addedDuplicateIsNoOp`, `postedWithoutPendingIdStillStored`, `postedWithPendingIdSupersedesPending`, `outflowAndNonDepositoryIgnored`, `lowConfidenceCounterpartyNotStored`, `overlongPlaidStringsAreFittedNotRejected`, `requiresCallerTransaction`, plus `modifiedUpdatesAmountDatePendingDescriptor`, `modifiedFirstSeenInflowIsInserted`, `removedMarksRemovedOnceAndReplayIsNoOp`, `anotherUsersRowIsNeverTouched`, `fromTransactionMapsEveryField`). Step 6's scratch-MySQL boot shows `tests="1" skipped="0" failures="0" errors="0"` for `BackendApplicationTests`. Step 7's greps print what Step 7 states. No migration, property, analytics event, ops alert or frontend file is part of this task.

### Produces

- `com.investingapp.backend.service.PaycheckEventService` (`@Service`, constructor `PaycheckEventService(PaycheckEventRepository)`, no class-level transaction)
- `public record PaycheckEventService.SyncBatch(List<Transaction> added, List<Transaction> modified, List<RemovedTransaction> removed, List<AccountBase> accounts)`
- `public record PaycheckEventService.ApplyResult(List<Long> newPostedInflowEventIds, List<Long> removedEventIds, List<Long> modifiedEventIds, int ignored)`
- `@Transactional(propagation = Propagation.MANDATORY) public ApplyResult applySyncBatch(Long userId, SyncBatch batch, LocalDateTime nowET)`
- `public static PaycheckEvent fromTransaction(Long userId, Transaction t, LocalDateTime nowET)`
- `com.investingapp.backend.util.PlaidAmounts.inflow(Double): BigDecimal`
- Phase 2 seam: `ApplyResult.removedEventIds` and `ApplyResult.modifiedEventIds` (voiding CONFIRMED cycles, spec 4.8)

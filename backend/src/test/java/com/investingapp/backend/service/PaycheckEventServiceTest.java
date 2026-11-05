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

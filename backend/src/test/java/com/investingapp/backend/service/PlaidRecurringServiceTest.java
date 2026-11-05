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
        assertThat(second.getPlaidStatus()).isNull();
        assertThat(second.getPlaidFrequency()).isEqualTo("UNKNOWN");
    }

    @Test
    void unrecognizedOrMissingStatusIsStoredAsNoStatus() throws Exception {
        givenUser(ITEM, PlaidItemStatus.OK);
        TransactionStream newer = stream("s-new", "acc-1", ACME, -2418.00, "t-1")
                .status(TransactionStreamStatus.ENUM_UNKNOWN);
        TransactionStream missing = stream("s-none", "acc-1", "GLOBEX PAYROLL", -900.00, "t-2").status(null);
        TransactionStream real = stream("s-real", "acc-1", "INITECH LLC DIR DEP", -500.00, "t-3")
                .status(TransactionStreamStatus.UNKNOWN);
        plaidReturns(inflow(newer, missing, real));

        service.readStreams(USER_ID, ITEM);

        List<PaycheckStream> saved = savedStreams();
        PaycheckStream newerRow = withStreamId(saved, "s-new");
        PaycheckStream missingRow = withStreamId(saved, "s-none");
        assertThat(newerRow.getPlaidStatus()).isNull();
        assertThat(missingRow.getPlaidStatus()).isNull();
        assertThat(withStreamId(saved, "s-real").getPlaidStatus()).isEqualTo("UNKNOWN");
        assertThat(new ObjectMapper().readTree(newerRow.getPlaidSnapshotJson()).get("status").isNull()).isTrue();
        assertThat(new ObjectMapper().readTree(missingRow.getPlaidSnapshotJson()).get("status").isNull()).isTrue();
    }

    @Test
    void streamWithoutAUsablePayerTokenIsSkipped() {
        givenUser(ITEM, PlaidItemStatus.OK);
        plaidReturns(inflow(
                stream("s-digits-1", "acc-1", "12345678", -100.00, "t-1"),
                stream("s-digits-2", "acc-1", "98765432", -200.00, "t-2"),
                stream("s-ok", "acc-1", ACME, -2418.00, "t-3")));

        StreamsReadOutcome outcome = service.readStreams(USER_ID, ITEM);

        assertThat(outcome).isEqualTo(new StreamsReadOutcome(StreamsReadStatus.APPLIED, 3, 0, 1, null));
        assertThat(savedStreams()).extracting(PaycheckStream::getPlaidStreamId).containsExactly("s-ok");
    }

}

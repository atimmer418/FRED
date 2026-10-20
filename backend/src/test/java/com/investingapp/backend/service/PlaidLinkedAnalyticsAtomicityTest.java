package com.investingapp.backend.service;

import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Atomicity for plaid_linked, against the real database.
 *
 * The unit test proves the right event with the right properties is recorded. It cannot prove the
 * thing that actually matters: that the row and the access token share one transaction, so a
 * failure after the link cannot leave an event claiming a bank was linked when none was. That needs
 * a real transaction manager and a real table, which is what this is.
 *
 * It is skipped, with its reason, wherever the ci database is not reachable (see
 * {@link RequiresCiDatabase}). The annotation is applied directly rather than as
 * {@code @ExtendWith(RequiresCiDatabase.class)}: RequiresCiDatabase is an annotation type that
 * meta-annotates its own {@code Condition} extension, so the class itself is not an Extension and
 * cannot be named in @ExtendWith. This is the form the repository already uses in
 * BackendApplicationTests.
 *
 * Plaid is mocked, not stubbed: the ci profile's PlaidApi points nowhere on purpose, so the success
 * path cannot be driven through it. AccountStatusService is mocked for the same reason, since the
 * real one reaches Alpaca to create an ACH relationship. Everything on the FRED side of the line
 * (PlaidService, AnalyticsOutboxService, the repositories, the transaction manager) is real.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class PlaidLinkedAnalyticsAtomicityTest {

    private static final String PUBLIC_TOKEN = "public-sandbox-token";
    private static final String RAW_ACCESS_TOKEN = "access-sandbox-raw-token";
    private static final String ITEM_ID = "item-id-for-the-atomicity-test";

    @Autowired
    private PlaidService plaidService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AnalyticsOutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The ci PlaidApi bean is a dead offline stub; the success path needs an answer. */
    @MockitoBean
    private PlaidApi plaidApi;

    /** Keeps the schedule reset and the Alpaca ACH creation out of this test entirely. */
    @MockitoBean
    private AccountStatusService accountStatusService;

    private TransactionTemplate transactions;
    private Long userId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        transactions = new TransactionTemplate(transactionManager);

        ItemPublicTokenExchangeResponse body = new ItemPublicTokenExchangeResponse()
                .accessToken(RAW_ACCESS_TOKEN)
                .itemId(ITEM_ID);
        Call<ItemPublicTokenExchangeResponse> exchange = mock(Call.class);
        when(exchange.execute()).thenReturn(Response.success(body));
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class)))
                .thenReturn(exchange);

        // Account enrichment is offline here. PlaidService already swallows a failure on that call,
        // and the event does not depend on it.
        Call<AccountsGetResponse> accounts = mock(Call.class);
        when(accounts.execute()).thenThrow(new IOException("offline in this test"));
        when(plaidApi.accountsGet(any())).thenReturn(accounts);

        User user = new User("plaid-atomicity+" + UUID.randomUUID() + "@fredvested.com");
        userId = userRepository.save(user).getId();
    }

    @AfterEach
    void tearDown() {
        // Production never deletes outbox rows. A test cleans up after itself so a second run on the
        // same schema is not reading the first run's rows.
        List<AnalyticsOutboxEntry> mine = rowsForThisUser();
        if (!mine.isEmpty()) {
            outboxRepository.deleteAll(mine);
        }
        userRepository.findById(userId).ifPresent(userRepository::delete);
    }

    private List<AnalyticsOutboxEntry> rowsForThisUser() {
        return outboxRepository.findAll().stream()
                .filter(entry -> userId.equals(entry.getUserId()))
                .toList();
    }

    private User detachedUser() {
        // Loaded outside a transaction and passed in, exactly as PlaidController does it.
        return userRepository.findById(userId).orElseThrow();
    }

    @Test
    @DisplayName("the event and the access token commit together")
    void successPathWritesExactlyOneRow() throws IOException {
        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, detachedUser());

        List<AnalyticsOutboxEntry> rows = rowsForThisUser();
        assertThat(rows).hasSize(1);
        AnalyticsOutboxEntry row = rows.get(0);
        assertThat(row.getEventName()).isEqualTo(ServerAnalyticsEvent.PLAID_LINKED);
        assertThat(row.getEventId()).isNotNull();
        assertThat(row.getStatus()).isEqualTo(AnalyticsOutboxEntry.STATUS_PENDING);
        assertThat(row.getProperties()).isEqualTo("{\"is_first_link\":true}");
        assertThat(outboxRepository.countByUserIdAndEventName(userId, ServerAnalyticsEvent.PLAID_LINKED))
                .isEqualTo(1);

        // And the fact it describes is really there.
        assertThat(userRepository.findById(userId).orElseThrow().getPlaidItemId()).isEqualTo(ITEM_ID);
    }

    @Test
    @DisplayName("a rollback after the link takes the event with it, leaving no row")
    void rollbackLeavesNoRow() {
        transactions.execute(status -> {
            try {
                plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, detachedUser());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }

            // Inside the transaction the row is already there: the insert happens on save(), because
            // the id is IDENTITY-generated. So what follows is a genuine rollback of a real row and
            // not a test that simply never wrote one.
            assertThat(outboxRepository.countByUserIdAndEventName(
                    userId, ServerAnalyticsEvent.PLAID_LINKED)).isEqualTo(1);

            // Whatever fails after the link: the ACH creation, the schedule reset, the commit itself.
            status.setRollbackOnly();
            return null;
        });

        assertThat(rowsForThisUser()).isEmpty();
        assertThat(outboxRepository.countByUserIdAndEventName(userId, ServerAnalyticsEvent.PLAID_LINKED))
                .isZero();

        // The state change rolled back too, which is the whole point: the two cannot disagree.
        assertThat(userRepository.findById(userId).orElseThrow().getPlaidItemId()).isNull();
        assertThat(userRepository.findById(userId).orElseThrow().getPlaidAccessToken()).isNull();
    }
}

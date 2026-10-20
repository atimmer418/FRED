package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.model.UserProgress;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * plaid_linked, emitted where the public-token exchange persists the item and the access token.
 *
 * Two things are being pinned here. The first is the property: ONE endpoint serves the first link,
 * a relink and a deliberate bank change, so is_first_link is the only thing that tells those apart,
 * and it has to be read before the new token overwrites the old one. The second is what the event
 * must NOT carry. Plaid hands us the institution, the item id, the access token and the account's
 * name and subtype on this very path, and all of them are one careless props.put away from leaving
 * the building. The event says a bank was linked and nothing about which one.
 *
 * PlaidService uses field injection, so the collaborators go in by reflection, as in
 * AnalyticsConsentEndpointTest.
 */
class PlaidLinkedAnalyticsTest {

    private static final String PUBLIC_TOKEN = "public-sandbox-token";
    private static final String RAW_ACCESS_TOKEN = "access-sandbox-raw-token";
    private static final String ITEM_ID = "item-id-abcdef123456";

    private PlaidService plaidService;
    private PlaidApi plaidApi;
    private UserRepository userRepository;
    private EncryptionService encryptionService;
    private AccountStatusService accountStatusService;
    private AnalyticsOutboxService analyticsOutboxService;

    @BeforeEach
    void setUp() {
        plaidService = new PlaidService();
        plaidApi = mock(PlaidApi.class);
        userRepository = mock(UserRepository.class);
        encryptionService = mock(EncryptionService.class);
        accountStatusService = mock(AccountStatusService.class);
        analyticsOutboxService = mock(AnalyticsOutboxService.class);

        ReflectionTestUtils.setField(plaidService, "plaidApi", plaidApi);
        ReflectionTestUtils.setField(plaidService, "userRepository", userRepository);
        ReflectionTestUtils.setField(plaidService, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(plaidService, "accountStatusService", accountStatusService);
        ReflectionTestUtils.setField(plaidService, "analyticsOutboxService", analyticsOutboxService);
        ReflectionTestUtils.setField(plaidService, "plaidItemLifecycleService", mock(PlaidItemLifecycleService.class));
        ReflectionTestUtils.setField(plaidService, "plaidExecutor", (Executor) runnable -> { });
        ReflectionTestUtils.setField(plaidService, "entityManager", mock(EntityManager.class));

        when(encryptionService.encrypt(RAW_ACCESS_TOKEN)).thenReturn("ENC:encrypted-access-token");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User user() {
        User user = new User("plaid-linked@fredvested.com");
        user.setId(99L);
        user.setPublicId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        user.setUserProgress(new UserProgress());
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(user));
        return user;
    }

    /** The exchange succeeds and hands back an access token and an item id, as Plaid does. */
    @SuppressWarnings("unchecked")
    private void exchangeSucceeds() {
        ItemPublicTokenExchangeResponse body = new ItemPublicTokenExchangeResponse()
                .accessToken(RAW_ACCESS_TOKEN)
                .itemId(ITEM_ID);
        Call<ItemPublicTokenExchangeResponse> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(Response.success(body));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class))).thenReturn(call);
    }

    /**
     * Account enrichment is deliberately offline here. The service already swallows a failure on
     * that call ("user can still use the app"), and leaving it unreachable proves the event does not
     * depend on it.
     */
    @SuppressWarnings("unchecked")
    private void accountDetailsUnavailable() {
        Call<AccountsGetResponse> call = mock(Call.class);
        try {
            when(call.execute()).thenThrow(new IOException("offline in this test"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(plaidApi.accountsGet(any())).thenReturn(call);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedProperties(User user) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(analyticsOutboxService, times(1))
                .record(eq(ServerAnalyticsEvent.PLAID_LINKED), eq(user), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a user with no prior Plaid data gets is_first_link true, and nothing else")
    void firstLink() throws IOException {
        exchangeSucceeds();
        accountDetailsUnavailable();
        User user = user();

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        // The WHOLE map, not a subset: an extra property here is an extra property in PostHog.
        assertThat(capturedProperties(user)).isEqualTo(Map.of("is_first_link", true));
    }

    @Test
    @DisplayName("the same endpoint used again is a relink, not a different event")
    void relinkWhenAnAccessTokenAlreadyExists() throws IOException {
        exchangeSucceeds();
        accountDetailsUnavailable();
        User user = user();
        user.setPlaidAccessToken("ENC:the-previous-token");

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        assertThat(capturedProperties(user)).isEqualTo(Map.of("is_first_link", false));
    }

    @Test
    @DisplayName("an item id on its own is also a prior link")
    void relinkWhenOnlyAnItemIdAlreadyExists() throws IOException {
        exchangeSucceeds();
        accountDetailsUnavailable();
        User user = user();
        user.setPlaidItemId("item-id-from-before");

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        assertThat(capturedProperties(user)).isEqualTo(Map.of("is_first_link", false));
    }

    @Test
    @DisplayName("is_first_link is read before the new token overwrites the old one")
    void firstLinkIsReadBeforeTheWrite() throws IOException {
        exchangeSucceeds();
        accountDetailsUnavailable();
        User user = user();

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        // By the time record() is called the user already carries the new token, so a naive
        // implementation that read it at emit time would report every first link as a relink.
        assertThat(user.getPlaidAccessToken()).isEqualTo("ENC:encrypted-access-token");
        assertThat(capturedProperties(user)).isEqualTo(Map.of("is_first_link", true));
    }

    @Test
    @DisplayName("the event carries no bank, no token and no identifier of any kind")
    void carriesNothingIdentifying() throws IOException {
        exchangeSucceeds();
        accountDetailsUnavailable();
        User user = user();

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        Map<String, Object> properties = capturedProperties(user);
        assertThat(properties).containsOnlyKeys("is_first_link");
        assertThat(properties.values()).containsExactly(true);
        String serialised = String.valueOf(properties);
        assertThat(serialised).doesNotContain(RAW_ACCESS_TOKEN);
        assertThat(serialised).doesNotContain(ITEM_ID);
        assertThat(serialised).doesNotContain(PUBLIC_TOKEN);
        assertThat(serialised).doesNotContain("ENC:");
        assertThat(serialised).doesNotContain(user.getEmail());
        assertThat(serialised).doesNotContain(String.valueOf(user.getId()));
    }

    @Test
    @DisplayName("the event is written after the token it describes, in the same transaction")
    void recordedAfterTheSave() throws IOException {
        exchangeSucceeds();
        accountDetailsUnavailable();
        User user = user();

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        InOrder order = inOrder(userRepository, analyticsOutboxService);
        order.verify(userRepository).save(user);
        order.verify(analyticsOutboxService).record(eq(ServerAnalyticsEvent.PLAID_LINKED), eq(user), any());
    }

    @Test
    @DisplayName("Plaid rejecting the exchange records nothing: no link happened")
    @SuppressWarnings("unchecked")
    void plaidRejectsTheExchange() {
        Call<ItemPublicTokenExchangeResponse> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(Response.error(400,
                    ResponseBody.create("{\"error_code\":\"INVALID_PUBLIC_TOKEN\"}",
                            MediaType.parse("application/json"))));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class))).thenReturn(call);
        User user = user();

        assertThatThrownBy(() -> plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user))
                .isInstanceOf(IOException.class);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("a dead network records nothing either")
    @SuppressWarnings("unchecked")
    void plaidUnreachable() {
        Call<ItemPublicTokenExchangeResponse> call = mock(Call.class);
        try {
            when(call.execute()).thenThrow(new IOException("connection refused"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class))).thenReturn(call);
        User user = user();

        assertThatThrownBy(() -> plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user))
                .isInstanceOf(IOException.class);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }
}

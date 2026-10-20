package com.investingapp.backend.service;

import com.investingapp.backend.controller.PlaidController;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.request.PlaidApi;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 2.4 steps 1, 2 and 6 and the 60 second LINK_IN_PROGRESS guard. Same reflection wiring as
 * PlaidLinkedAnalyticsTest; the plaidExecutor is a list so the test decides when the after-commit
 * consent read runs.
 */
class PlaidServiceExchangeLifecycleTest {

    private static final String PUBLIC_TOKEN = "public-sandbox-token";
    private static final String RAW_ACCESS_TOKEN = "access-sandbox-raw-token";
    private static final String ITEM_ID = "item-id-new";

    private PlaidService plaidService;
    private PlaidApi plaidApi;
    private UserRepository userRepository;
    private PlaidItemLifecycleService lifecycleService;
    private final List<Runnable> submitted = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        plaidService = new PlaidService();
        plaidApi = mock(PlaidApi.class);
        userRepository = mock(UserRepository.class);
        lifecycleService = mock(PlaidItemLifecycleService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);

        ReflectionTestUtils.setField(plaidService, "plaidApi", plaidApi);
        ReflectionTestUtils.setField(plaidService, "userRepository", userRepository);
        ReflectionTestUtils.setField(plaidService, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(plaidService, "accountStatusService", mock(AccountStatusService.class));
        ReflectionTestUtils.setField(plaidService, "analyticsOutboxService", mock(AnalyticsOutboxService.class));
        ReflectionTestUtils.setField(plaidService, "plaidItemLifecycleService", lifecycleService);
        ReflectionTestUtils.setField(plaidService, "plaidExecutor", (Executor) submitted::add);
        ReflectionTestUtils.setField(plaidService, "entityManager", mock(EntityManager.class));

        when(encryptionService.encrypt(RAW_ACCESS_TOKEN)).thenReturn("ENC:new");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        Call<ItemPublicTokenExchangeResponse> exchange = mock(Call.class);
        when(exchange.execute()).thenReturn(Response.success(
                new ItemPublicTokenExchangeResponse().accessToken(RAW_ACCESS_TOKEN).itemId(ITEM_ID)));
        when(plaidApi.itemPublicTokenExchange(any(ItemPublicTokenExchangeRequest.class))).thenReturn(exchange);

        // One account, no subtype and no item: the service picks accounts.get(0) and swallows the
        // institution lookup failure, which is enough to prove the mask is stored.
        Call<AccountsGetResponse> accounts = mock(Call.class);
        when(accounts.execute()).thenReturn(Response.success(new AccountsGetResponse()
                .accounts(List.of(new AccountBase().accountId("acc-1").mask("4321")))));
        when(plaidApi.accountsGet(any())).thenReturn(accounts);
    }

    private User lockedUser() {
        User user = new User("exchange@fredvested.com");
        user.setId(99L);
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void secondExchangeWithin60sIs409ThenALaterLinkStoresMaskAndResetsItemState() throws IOException {
        User user = lockedUser();
        user.setPlaidItemLinkedAt(EasternTime.now().minusSeconds(10));

        assertThatThrownBy(() -> plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user))
                .isInstanceOf(LinkInProgressException.class);
        verify(plaidApi, never()).itemPublicTokenExchange(any());
        verify(lifecycleService, never()).enqueueRemoval(any());

        user.setPlaidItemLinkedAt(EasternTime.now().minusSeconds(61));
        user.setPreviousAchRelationshipId("ENC:rel-older");
        user.setPlaidTransactionsConsented(Boolean.FALSE);
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        user.setPlaidItemErrorCode("ITEM_LOGIN_REQUIRED");

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        assertThat(user.getPlaidItemId()).isEqualTo(ITEM_ID);
        assertThat(user.getPlaidItemLinkedAt()).isAfter(EasternTime.now().minusSeconds(5));
        assertThat(user.getPreviousAchRelationshipId()).isEqualTo("ENC:rel-older"); // no ACH id to overwrite it with
        assertThat(user.getPlaidAccountMask()).isEqualTo("4321");
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(user.getPlaidItemErrorCode()).isNull();
        assertThat(user.getPlaidTransactionsConsented()).isNull();
    }

    @Test
    void relinkQueuesOldItemBeforeOverwriteRemembersAchIdAndHandsConsentReadToTheExecutor() throws IOException {
        User user = lockedUser();
        user.setPlaidAccessToken("ENC:old");
        user.setPlaidItemId("item-old");
        user.setAlpacaAchRelationshipId("ENC:rel-old");
        AtomicReference<String> tokenAtEnqueue = new AtomicReference<>();
        doAnswer(i -> {
            tokenAtEnqueue.set(((User) i.getArgument(0)).getPlaidAccessToken());
            return null;
        }).when(lifecycleService).enqueueRemoval(user);

        plaidService.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user);

        assertThat(tokenAtEnqueue.get()).isEqualTo("ENC:old");
        assertThat(user.getPreviousAchRelationshipId()).isEqualTo("ENC:rel-old");
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(user.getPlaidAccessToken()).isEqualTo("ENC:new");
        verify(plaidApi, never()).itemGet(any());
        verify(lifecycleService, never()).recordConsentAfterLink(any(), any());

        assertThat(submitted).hasSize(1);
        submitted.get(0).run();
        verify(lifecycleService).recordConsentAfterLink(99L, RAW_ACCESS_TOKEN);
    }

    @Test
    void controllerMapsLinkInProgressTo409WithTheErrorCode() throws IOException {
        PlaidController controller = new PlaidController();
        PlaidService service = mock(PlaidService.class);
        ReflectionTestUtils.setField(controller, "plaidService", service);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        User user = new User("conflict@fredvested.com");
        user.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(service.exchangePublicTokenAndLinkUser(PUBLIC_TOKEN, user)).thenThrow(new LinkInProgressException());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserDetailsImpl(7L, user.getEmail(), List.of()), null, List.of()));
        try {
            ResponseEntity<?> response = controller.exchangePublicTokenAuthenticated(Map.of("public_token", PUBLIC_TOKEN));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody())
                    .isEqualTo(new ErrorCodeResponse("LINK_IN_PROGRESS", "A bank link is already in progress"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

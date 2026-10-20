package com.investingapp.backend.service;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.model.PlaidItemRemoval;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidItemRemovalRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.ItemGetRequest;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemRemoveRequest;
import com.plaid.client.model.ItemRemoveResponse;
import com.plaid.client.model.ItemWebhookUpdateRequest;
import com.plaid.client.model.ItemWebhookUpdateResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlaidItemLifecycleService: the removal queue, the after-commit consent read and the one-off
 * webhook backfill. Plaid is a mocked PlaidApi (retrofit Call -> Response), the transaction manager
 * is a mock (TransactionTemplate still runs the callback, as in TlhHarvestServiceCycleTest).
 */
class PlaidItemLifecycleServiceTest {

    private static final String WEBHOOK = "https://api-test.fredvested.com/api/plaid/webhook";

    private PlaidItemRemovalRepository removalRepository;
    private UserRepository userRepository;
    private PlaidApi plaidApi;
    private SchedulerGate schedulerGate;
    private JobLockService jobLockService;
    private PlaidItemLifecycleService service;

    @BeforeEach
    void setUp() {
        removalRepository = mock(PlaidItemRemovalRepository.class);
        userRepository = mock(UserRepository.class);
        plaidApi = mock(PlaidApi.class);
        schedulerGate = mock(SchedulerGate.class);
        jobLockService = mock(JobLockService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.decrypt(any())).thenAnswer(i -> "raw-of-" + i.getArgument(0));

        service = new PlaidItemLifecycleService(removalRepository, userRepository, plaidApi, encryptionService,
                schedulerGate, jobLockService, mock(PlatformTransactionManager.class), WEBHOOK);

        when(schedulerGate.isEnabled()).thenReturn(true);
        when(jobLockService.tryAcquire(eq(JobLocks.PLAID_ITEM_REMOVALS), any())).thenReturn(true);
        when(removalRepository.save(any(PlaidItemRemoval.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** status 0 = Response.success(body); status > 0 = a Plaid error body with that code; status -1 = IOException. */
    @SuppressWarnings("unchecked")
    private <T> Call<T> call(int status, T body, String errorCode) {
        Call<T> mocked = mock(Call.class);
        try {
            if (status < 0) {
                when(mocked.execute()).thenThrow(new IOException("connection refused"));
            } else if (status == 0) {
                when(mocked.execute()).thenReturn(Response.success(body));
            } else {
                when(mocked.execute()).thenReturn(Response.error(status, ResponseBody.create(
                        "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"" + errorCode + "\"}",
                        MediaType.parse("application/json"))));
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return mocked;
    }

    private User user(String token, String itemId) {
        User user = new User("lifecycle@fredvested.com");
        user.setId(99L);
        user.setPlaidAccessToken(token);
        user.setPlaidItemId(itemId);
        return user;
    }

    private PlaidItemRemoval pending(LocalDateTime requestedAt) {
        PlaidItemRemoval row = new PlaidItemRemoval();
        row.setUserId(99L);
        row.setItemId("item-old");
        row.setAccessTokenEnc("ENC:old");
        row.setRequestedAt(requestedAt);
        row.setAttempts(0);
        return row;
    }

    @Test
    void enqueueRemovalCopiesTokenAndItemId() {
        service.enqueueRemoval(user("ENC:old", "item-old"));

        ArgumentCaptor<PlaidItemRemoval> captor = ArgumentCaptor.forClass(PlaidItemRemoval.class);
        verify(removalRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(99L);
        assertThat(captor.getValue().getItemId()).isEqualTo("item-old");
        assertThat(captor.getValue().getAccessTokenEnc()).isEqualTo("ENC:old");
        assertThat(captor.getValue().getRequestedAt()).isNotNull();
        assertThat(captor.getValue().getRemovedAt()).isNull();
        assertThat(captor.getValue().getAttempts()).isZero();
    }

    @Test
    void enqueueRemovalSkipsAUserWithoutATokenAndFallsBackWhenOnlyTheItemIdIsMissing() {
        service.enqueueRemoval(user(null, "item-old"));
        service.enqueueRemoval(user("  ", "item-old"));
        verify(removalRepository, never()).save(any(PlaidItemRemoval.class));

        service.enqueueRemoval(user("ENC:old", null));
        ArgumentCaptor<PlaidItemRemoval> captor = ArgumentCaptor.forClass(PlaidItemRemoval.class);
        verify(removalRepository).save(captor.capture());
        assertThat(captor.getValue().getItemId()).isEqualTo("unknown");
        assertThat(captor.getValue().getAccessTokenEnc()).isEqualTo("ENC:old");
    }

    @Test
    void drainMarksSuccessAndItemNotFoundRemovedAndCountsOtherErrors() {
        LocalDateTime recent = EasternTime.now().minusHours(1);
        PlaidItemRemoval ok = pending(recent);
        PlaidItemRemoval gone = pending(recent);
        PlaidItemRemoval failing = pending(recent);
        when(removalRepository.findByRemovedAtIsNullAndRequestedAtAfterOrderByRequestedAtAsc(any()))
                .thenReturn(List.of(ok, gone, failing));
        // Each call(...) stub must be fully built before the outer when(...).thenReturn(...) starts:
        // call() runs its own when(mocked.execute())...thenReturn(...) internally, and Mockito's
        // single-threaded "stubbing in progress" tracking breaks (UnfinishedStubbingException) if a
        // nested when() runs while an outer one is still being argument-evaluated.
        Call<ItemRemoveResponse> removeOk = call(0, new ItemRemoveResponse(), null);
        Call<ItemRemoveResponse> removeGone = this.<ItemRemoveResponse>call(400, null, "ITEM_NOT_FOUND");
        Call<ItemRemoveResponse> removeFailing = this.<ItemRemoveResponse>call(500, null, "INTERNAL_SERVER_ERROR");
        when(plaidApi.itemRemove(any(ItemRemoveRequest.class))).thenReturn(removeOk, removeGone, removeFailing);

        service.drainRemovals();

        assertThat(ok.getRemovedAt()).isNotNull();
        assertThat(gone.getRemovedAt()).isNotNull();
        assertThat(failing.getRemovedAt()).isNull();
        assertThat(failing.getAttempts()).isEqualTo(1);
        assertThat(failing.getLastError()).contains("INTERNAL_SERVER_ERROR");
        verify(removalRepository, times(3)).save(any(PlaidItemRemoval.class));
        ArgumentCaptor<ItemRemoveRequest> captor = ArgumentCaptor.forClass(ItemRemoveRequest.class);
        verify(plaidApi, times(3)).itemRemove(captor.capture());
        assertThat(captor.getAllValues()).extracting(ItemRemoveRequest::getAccessToken).containsOnly("raw-of-ENC:old");
        verify(jobLockService).release(JobLocks.PLAID_ITEM_REMOVALS);
    }

    @Test
    void drainAlertsOnRowsOlderThanSevenDaysInsteadOfCallingPlaid() {
        PlaidItemRemoval stale = pending(EasternTime.now().minusDays(8));
        stale.setAttempts(600);
        when(removalRepository.findByRemovedAtIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(any()))
                .thenReturn(List.of(stale));

        service.drainRemovals();

        verify(plaidApi, never()).itemRemove(any(ItemRemoveRequest.class));
        verify(removalRepository, never()).save(any(PlaidItemRemoval.class));
        assertThat(stale.getRemovedAt()).isNull();
        verify(jobLockService).release(JobLocks.PLAID_ITEM_REMOVALS);
    }

    @Test
    void drainDoesNothingWhenTheSchedulerGateIsOff() {
        when(schedulerGate.isEnabled()).thenReturn(false);

        service.drainRemovals();

        verify(jobLockService, never()).tryAcquire(any(), any());
        verify(jobLockService, never()).release(any());
        verify(removalRepository, never()).findByRemovedAtIsNullAndRequestedAtAfterOrderByRequestedAtAsc(any());
    }

    private User consentUser(String storedItemId, String plaidItemId, Products... consented) {
        User user = user("ENC:new", storedItemId);
        when(userRepository.findById(99L)).thenReturn(Optional.of(user));
        ItemGetResponse body = new ItemGetResponse()
                .item(new ItemWithConsentFields().itemId(plaidItemId).consentedProducts(List.of(consented)));
        Call<ItemGetResponse> response = call(0, body, null);
        when(plaidApi.itemGet(any(ItemGetRequest.class))).thenReturn(response);
        return user;
    }

    @Test
    void recordConsentStoresTrueOnlyForTransactionsAndSkipsAForeignItem() {
        User user = consentUser("item-new", "item-new", Products.AUTH, Products.TRANSACTIONS);
        service.recordConsentAfterLink(99L, "access-new");
        assertThat(user.getPlaidTransactionsConsented()).isTrue();

        User again = consentUser("item-new", "item-new", Products.AUTH);
        again.setPlaidTransactionsConsented(Boolean.FALSE);
        service.recordConsentAfterLink(99L, "access-new");
        assertThat(again.getPlaidTransactionsConsented()).isNull();

        User foreign = consentUser("item-newer", "item-new", Products.TRANSACTIONS);
        service.recordConsentAfterLink(99L, "access-new");
        assertThat(foreign.getPlaidTransactionsConsented()).isNull();

        verify(userRepository, times(2)).save(any(User.class));
        ArgumentCaptor<ItemGetRequest> captor = ArgumentCaptor.forClass(ItemGetRequest.class);
        verify(plaidApi, times(3)).itemGet(captor.capture());
        assertThat(captor.getAllValues()).extracting(ItemGetRequest::getAccessToken).containsOnly("access-new");
    }

    @Test
    void recordConsentSwallowsAPlaidFailureAndWritesNothing() {
        when(userRepository.findById(99L)).thenReturn(Optional.of(user("ENC:new", "item-new")));
        Call<ItemGetResponse> failing = this.<ItemGetResponse>call(-1, null, null);
        when(plaidApi.itemGet(any(ItemGetRequest.class))).thenReturn(failing);

        service.recordConsentAfterLink(99L, "access-new");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void backfillCountsEveryTokenAndSendsTheWebhookUrl() {
        when(userRepository.findByPlaidAccessTokenIsNotNull())
                .thenReturn(List.of(user("ENC:a", "item-a"), user("ENC:b", "item-b")));
        Call<ItemWebhookUpdateResponse> succeeding = call(0, new ItemWebhookUpdateResponse(), null);
        Call<ItemWebhookUpdateResponse> failing = this.<ItemWebhookUpdateResponse>call(-1, null, null);
        when(plaidApi.itemWebhookUpdate(any(ItemWebhookUpdateRequest.class))).thenReturn(succeeding, failing);

        PlaidItemLifecycleService.BackfillReport report = service.backfillWebhookUrl();

        assertThat(report).isEqualTo(new PlaidItemLifecycleService.BackfillReport(2, 1, 1));
        ArgumentCaptor<ItemWebhookUpdateRequest> captor = ArgumentCaptor.forClass(ItemWebhookUpdateRequest.class);
        verify(plaidApi, times(2)).itemWebhookUpdate(captor.capture());
        assertThat(captor.getAllValues()).extracting(ItemWebhookUpdateRequest::getWebhook).containsOnly(WEBHOOK);
        assertThat(captor.getAllValues()).extracting(ItemWebhookUpdateRequest::getAccessToken)
                .containsExactly("raw-of-ENC:a", "raw-of-ENC:b");
    }
}

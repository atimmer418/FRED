### Task 9: exchange-and-item-lifecycle

Serialize the exchange per user, queue the old Item for `/item/remove`, remember the old ACH id, store the mask, read consent after commit, drain removals every 15 minutes, expose the webhook backfill. Spec 2.4 (steps 1, 2, 6), 2.9 first paragraph, 3.1, 3.9, 8.8 (exchange bullet). `$M` = `backend/src/main/java/com/investingapp/backend`, `$T` = `backend/src/test/java/com/investingapp/backend`.

Two design notes the implementer must not "fix":
- `plaid_item_linked_at` is stamped inside the transaction (under the row lock), because the 60 second guard reads it under that same lock. The after-commit `/item/get` writes only `plaid_transactions_consented`.
- `spring.jpa.open-in-view` is not set in any profile, so it is on (Spring Boot default). The controller's `findById` instance is therefore already managed by the request's EntityManager when the service runs, and Hibernate upgrades the lock on an already-managed instance WITHOUT re-reading its state. The service refreshes the entity after taking the lock so a second exchange sees what the first one committed.

Line numbers below are HEAD numbers. T08 edits `PlaidService.java:47-80` and `PlaidController.java:44-66` before this task, so every insertion is anchored on the quoted line of code, not the number.

**Files:**
- Create: `$M/service/PlaidItemLifecycleService.java`, `$M/service/LinkInProgressException.java`, `$M/repository/PlaidItemRemovalRepository.java`, `$M/controller/DevPlaidController.java`
- Modify: `$M/service/PlaidService.java` (imports after `:7` and `:16`; fields after `:57`; four insertions inside `exchangePublicTokenAndLinkUser` after the lines at `:85`, `:102`, `:108`, `:141`; one line after `:182`); `$M/controller/PlaidController.java` (imports after `:3`; a `catch` before the `} catch (IOException e) {` at `:97`); `$M/repository/UserRepository.java:40` (one finder); `$M/config/DevToolsProdGuard.java:3` (import) and `:43` (one `addIfPresent` line)
- Test: create `$T/service/PlaidItemLifecycleServiceTest.java`, `$T/service/PlaidServiceExchangeLifecycleTest.java`; modify `$T/service/PlaidLinkedAnalyticsTest.java:23,75,86` and `$T/config/DevToolsGatingTest.java:3,135`

**Interfaces:**
- Consumes: `UserRepository.findByIdForUpdate(Long): Optional<User>` (UserRepository.java:75-77); `jakarta.persistence.EntityManager.refresh(Object)`; `EasternTime.now()` (T02); `SchedulerGate.isEnabled()`, `JobLockService.tryAcquire(String, Duration): boolean`, `JobLockService.release(String)`, `JobLocks.PLAID_ITEM_REMOVALS`, bean `plaidExecutor` (T05); entity `PlaidItemRemoval` with accessors `getId/getUserId/setUserId/getItemId/setItemId/getAccessTokenEnc/setAccessTokenEnc/getRequestedAt/setRequestedAt/getRemovedAt/setRemovedAt/getAttempts():int/setAttempts(int)/getLastError/setLastError` and the User Phase 0 accessors `get/setPlaidAccountMask`, `get/setPlaidTransactionsConsented`, `get/setPlaidItemStatus`, `get/setPlaidItemErrorCode`, `get/setPlaidItemLinkedAt`, `get/setPreviousAchRelationshipId` (T04, Lombok); `PlaidItemStatus.OK` (T04); `ErrorCodeResponse(String code, String message)` (T08); property `plaid.webhook.url` (T07); plaid-java 46 `PlaidApi.itemGet/itemRemove/itemWebhookUpdate`, `ItemGetResponse.getItem(): ItemWithConsentFields`, `ItemWithConsentFields.getItemId()/getConsentedProducts(): List<Products>` (T01).
- Produces: `public class LinkInProgressException extends RuntimeException` (service package); `PlaidService.exchangePublicTokenAndLinkUser(String, User)` keeps its signature and now throws `LinkInProgressException` inside the 60 s window; new `PlaidService` fields `plaidItemLifecycleService`, `plaidExecutor` (`java.util.concurrent.Executor`, qualifier `plaidExecutor`), `entityManager` (any later test that builds `new PlaidService()` must set all three by reflection and stub `userRepository.findByIdForUpdate`); `@Service PlaidItemLifecycleService` with `@Transactional(MANDATORY) void enqueueRemoval(User)`, `void recordConsentAfterLink(Long userId, String rawAccessToken)`, `@Scheduled(cron = "0 */15 * * * *", zone = "America/New_York") void drainRemovals()`, `BackfillReport backfillWebhookUrl()`, `public record BackfillReport(int attempted, int succeeded, int failed)`; `PlaidItemRemovalRepository` with `findByRemovedAtIsNullAndRequestedAtAfterOrderByRequestedAtAsc(LocalDateTime)`, `findByRemovedAtIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(LocalDateTime)`, `long deleteByUserId(Long)`; `UserRepository.findByPlaidAccessTokenIsNotNull(): List<User>`; `POST /api/dev/plaid/backfill-webhook-update` -> `BackfillReport` (profiles local/dev/test/ci, needs `X-Dev-Tools-Key`, refused by `DevToolsProdGuard` in prod); `POST /api/plaid/exchange_public_token` -> 409 `ErrorCodeResponse("LINK_IN_PROGRESS", "A bank link is already in progress")`.

- [ ] **Step 1: Write the failing lifecycle service test**

Create `$T/service/PlaidItemLifecycleServiceTest.java`:

```java
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
        when(plaidApi.itemRemove(any(ItemRemoveRequest.class))).thenReturn(
                call(0, new ItemRemoveResponse(), null),
                this.<ItemRemoveResponse>call(400, null, "ITEM_NOT_FOUND"),
                this.<ItemRemoveResponse>call(500, null, "INTERNAL_SERVER_ERROR"));

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
        when(plaidApi.itemGet(any(ItemGetRequest.class))).thenReturn(call(0, new ItemGetResponse()
                .item(new ItemWithConsentFields().itemId(plaidItemId).consentedProducts(List.of(consented))), null));
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
        when(plaidApi.itemGet(any(ItemGetRequest.class)))
                .thenReturn(this.<ItemGetResponse>call(-1, null, null));

        service.recordConsentAfterLink(99L, "access-new");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void backfillCountsEveryTokenAndSendsTheWebhookUrl() {
        when(userRepository.findByPlaidAccessTokenIsNotNull())
                .thenReturn(List.of(user("ENC:a", "item-a"), user("ENC:b", "item-b")));
        when(plaidApi.itemWebhookUpdate(any(ItemWebhookUpdateRequest.class))).thenReturn(
                call(0, new ItemWebhookUpdateResponse(), null),
                this.<ItemWebhookUpdateResponse>call(-1, null, null));

        PlaidItemLifecycleService.BackfillReport report = service.backfillWebhookUrl();

        assertThat(report).isEqualTo(new PlaidItemLifecycleService.BackfillReport(2, 1, 1));
        ArgumentCaptor<ItemWebhookUpdateRequest> captor = ArgumentCaptor.forClass(ItemWebhookUpdateRequest.class);
        verify(plaidApi, times(2)).itemWebhookUpdate(captor.capture());
        assertThat(captor.getAllValues()).extracting(ItemWebhookUpdateRequest::getWebhook).containsOnly(WEBHOOK);
        assertThat(captor.getAllValues()).extracting(ItemWebhookUpdateRequest::getAccessToken)
                .containsExactly("raw-of-ENC:a", "raw-of-ENC:b");
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidItemLifecycleServiceTest'`
Expected: `compileTestJava FAILED` with `error: cannot find symbol` for `PlaidItemLifecycleService` and `PlaidItemRemovalRepository`.

- [ ] **Step 3: Add the removal repository and the backfill finder**

Create `$M/repository/PlaidItemRemovalRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PlaidItemRemoval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** plaid_item_removals: the queue PlaidItemLifecycleService.drainRemovals works through. */
@Repository
public interface PlaidItemRemovalRepository extends JpaRepository<PlaidItemRemoval, Long> {

    /** Still to retry: not removed yet and requested after the given floor (now minus 7 days). */
    List<PlaidItemRemoval> findByRemovedAtIsNullAndRequestedAtAfterOrderByRequestedAtAsc(LocalDateTime since);

    /** Exhausted: not removed and requested before the floor; alerted on, never retried. */
    List<PlaidItemRemoval> findByRemovedAtIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(LocalDateTime cutoff);

    /** Used by UserDeletionService (T11) inside its own transaction. */
    long deleteByUserId(Long userId);
}
```

In `$M/repository/UserRepository.java` insert directly after line 40 (`User findByPlaidItemId(String plaidItemId);`):

```java

    // Every user holding a Plaid access token: the one-off /item/webhook/update backfill
    // (PlaidItemLifecycleService.backfillWebhookUrl, exposed by DevPlaidController)
    List<User> findByPlaidAccessTokenIsNotNull();
```

(`java.util.List` is already imported at line 13.)

- [ ] **Step 4: Add the lifecycle service**

Create `$M/service/PlaidItemLifecycleService.java`. No Plaid call runs inside a database transaction: the consent write uses a `TransactionTemplate` built from the injected `PlatformTransactionManager` (the TlhHarvestService.java:118 idiom; a mocked manager still runs the callback), the drainer calls Plaid per row and saves each row in its own short repository transaction, the backfill never writes.

```java
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Response;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Plaid Item lifecycle outside the exchange transaction (spec 2.4 step 6, 2.9).
 *
 * <p>Plaid bills Transactions per Item until {@code /item/remove}, so a relink queues the old Item
 * here (its own encrypted token copy, because the relink overwrites users.plaid_access_token) and
 * {@link #drainRemovals()} removes it. {@code ITEM_NOT_FOUND} counts as done; other errors retry
 * every 15 minutes for 7 days and then alert on every pass until a person resolves the row.
 *
 * <p>The consent read runs after the exchange commits, on the Plaid pool, and writes only when the
 * Item it read is still the one on the user row.
 */
@Service
public class PlaidItemLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidItemLifecycleService.class);

    static final Duration RETRY_WINDOW = Duration.ofDays(7);
    static final Duration LOCK_LEASE = Duration.ofMinutes(10);
    private static final int LAST_ERROR_MAX = 500;

    private final PlaidItemRemovalRepository removalRepository;
    private final UserRepository userRepository;
    private final PlaidApi plaidApi;
    private final EncryptionService encryptionService;
    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final TransactionTemplate writeTx;
    private final String plaidWebhookUrl;

    public PlaidItemLifecycleService(PlaidItemRemovalRepository removalRepository,
            UserRepository userRepository,
            PlaidApi plaidApi,
            EncryptionService encryptionService,
            SchedulerGate schedulerGate,
            JobLockService jobLockService,
            PlatformTransactionManager transactionManager,
            @Value("${plaid.webhook.url}") String plaidWebhookUrl) {
        this.removalRepository = removalRepository;
        this.userRepository = userRepository;
        this.plaidApi = plaidApi;
        this.encryptionService = encryptionService;
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.plaidWebhookUrl = plaidWebhookUrl;
    }

    public record BackfillReport(int attempted, int succeeded, int failed) {
    }

    /**
     * Queue the user's CURRENT Item for removal. Call before the new token overwrites the old one,
     * inside the caller's transaction (MANDATORY: there is no sensible standalone use).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueRemoval(User user) {
        String token = user.getPlaidAccessToken();
        if (token == null || token.isBlank()) {
            return;
        }
        String itemId = user.getPlaidItemId() != null && !user.getPlaidItemId().isBlank()
                ? user.getPlaidItemId()
                : "unknown"; // item_id is NOT NULL; /item/remove only needs the token
        PlaidItemRemoval row = new PlaidItemRemoval();
        row.setUserId(user.getId());
        row.setItemId(itemId);
        row.setAccessTokenEnc(token);
        row.setRequestedAt(EasternTime.now());
        row.setAttempts(0);
        removalRepository.save(row);
        logger.info("Queued Plaid item {} of user {} for removal", itemId, user.getId());
    }

    /**
     * After-commit half of the exchange: /item/get, then store plaid_transactions_consented
     * (TRUE when transactions is consented, else NULL = unknown). Skipped when Plaid is unreachable
     * or the Item is no longer the one on the user row (a later relink won the race).
     */
    public void recordConsentAfterLink(Long userId, String rawAccessToken) {
        ItemWithConsentFields item;
        try {
            Response<ItemGetResponse> response =
                    plaidApi.itemGet(new ItemGetRequest().accessToken(rawAccessToken)).execute();
            item = response.isSuccessful() && response.body() != null ? response.body().getItem() : null;
        } catch (Exception e) {
            logger.warn("recordConsentAfterLink: /item/get failed for user {}: {}", userId, e.getMessage());
            item = null;
        }
        if (item == null || item.getItemId() == null) {
            return;
        }
        String itemId = item.getItemId();
        List<Products> consentedProducts = item.getConsentedProducts();
        Boolean consented = consentedProducts != null && consentedProducts.contains(Products.TRANSACTIONS)
                ? Boolean.TRUE
                : null;
        writeTx.executeWithoutResult(status -> userRepository.findById(userId).ifPresent(user -> {
            if (!itemId.equals(user.getPlaidItemId())) {
                logger.info("recordConsentAfterLink: user {} has moved on from item {}, skipping", userId, itemId);
                return;
            }
            user.setPlaidTransactionsConsented(consented);
            userRepository.save(user);
        }));
    }

    /** Every 15 minutes: /item/remove for each queued Item, retry for 7 days, then alert. */
    @Scheduled(cron = "0 */15 * * * *", zone = "America/New_York")
    public void drainRemovals() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.PLAID_ITEM_REMOVALS, LOCK_LEASE)) {
            return;
        }
        try {
            LocalDateTime now = EasternTime.now();
            LocalDateTime retryFloor = now.minus(RETRY_WINDOW);
            for (PlaidItemRemoval row : removalRepository
                    .findByRemovedAtIsNullAndRequestedAtAfterOrderByRequestedAtAsc(retryFloor)) {
                attemptRemoval(row, now);
            }
            for (PlaidItemRemoval row : removalRepository
                    .findByRemovedAtIsNullAndRequestedAtBeforeOrderByRequestedAtAsc(retryFloor)) {
                logger.error("alert.plaid.item_remove.exhausted id={} item={} attempts={} lastError={}",
                        row.getId(), row.getItemId(), row.getAttempts(), row.getLastError());
            }
        } finally {
            jobLockService.release(JobLocks.PLAID_ITEM_REMOVALS);
        }
    }

    private void attemptRemoval(PlaidItemRemoval row, LocalDateTime now) {
        String error;
        try {
            Response<ItemRemoveResponse> response = plaidApi.itemRemove(new ItemRemoveRequest()
                    .accessToken(encryptionService.decrypt(row.getAccessTokenEnc()))).execute();
            String body = response.errorBody() != null ? response.errorBody().string() : "";
            error = response.isSuccessful() || body.contains("ITEM_NOT_FOUND")
                    ? null
                    : "HTTP " + response.code() + " " + body;
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (error == null) {
            row.setRemovedAt(now);
            row.setLastError(null);
            logger.info("Removed Plaid item {} (row {})", row.getItemId(), row.getId());
        } else {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(error.length() <= LAST_ERROR_MAX ? error : error.substring(0, LAST_ERROR_MAX));
            logger.warn("Plaid item {} removal attempt {} failed: {}", row.getItemId(), row.getAttempts(), error);
        }
        removalRepository.save(row);
    }

    /** Phase 0 backfill: /item/webhook/update for every stored token. Reads only; no transaction. */
    public BackfillReport backfillWebhookUrl() {
        int attempted = 0;
        int succeeded = 0;
        for (User user : userRepository.findByPlaidAccessTokenIsNotNull()) {
            if (user.getPlaidAccessToken().isBlank()) {
                continue;
            }
            attempted++;
            try {
                Response<ItemWebhookUpdateResponse> response = plaidApi.itemWebhookUpdate(new ItemWebhookUpdateRequest()
                        .accessToken(encryptionService.decrypt(user.getPlaidAccessToken()))
                        .webhook(plaidWebhookUrl)).execute();
                if (response.isSuccessful()) {
                    succeeded++;
                } else {
                    logger.warn("Webhook backfill: Plaid answered {} for user {}", response.code(), user.getId());
                }
            } catch (Exception e) {
                logger.warn("Webhook backfill failed for user {}: {}", user.getId(), e.getMessage());
            }
        }
        BackfillReport report = new BackfillReport(attempted, succeeded, attempted - succeeded);
        logger.info("Webhook backfill done: {}", report);
        return report;
    }
}
```

- [ ] **Step 5: Run the lifecycle test to green**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidItemLifecycleServiceTest'`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 6: Write the failing exchange test and wire the new collaborators into the existing analytics test**

Create `$T/service/PlaidServiceExchangeLifecycleTest.java`:

```java
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
```

Edit `$T/service/PlaidLinkedAnalyticsTest.java` (8 tests; all keep passing). Directly after line 23 (`import java.util.Map;`) add:

```java
import java.util.Optional;
import java.util.concurrent.Executor;
import jakarta.persistence.EntityManager;
```

Directly after line 75 (`ReflectionTestUtils.setField(plaidService, "analyticsOutboxService", analyticsOutboxService);`) add:

```java
        ReflectionTestUtils.setField(plaidService, "plaidItemLifecycleService", mock(PlaidItemLifecycleService.class));
        ReflectionTestUtils.setField(plaidService, "plaidExecutor", (Executor) runnable -> { });
        ReflectionTestUtils.setField(plaidService, "entityManager", mock(EntityManager.class));
```

In `user()` directly before `return user;` (line 86) add:

```java
        when(userRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(user));
```

- [ ] **Step 7: Run both exchange tests and watch the new one fail**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest' --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsTest'`
Expected: `compileTestJava FAILED`, `error: cannot find symbol` for `LinkInProgressException`, and `ReflectionTestUtils` would also fail at runtime on the missing `plaidItemLifecycleService` field.

- [ ] **Step 8: Implement the serialized exchange**

Create `$M/service/LinkInProgressException.java`:

```java
package com.investingapp.backend.service;

/** A second public-token exchange for the same user inside the 60 second window. Maps to 409. */
public class LinkInProgressException extends RuntimeException {

    public LinkInProgressException() {
        super("A bank link is already in progress");
    }
}
```

In `$M/service/PlaidService.java` add directly after line 7 (`import com.investingapp.backend.util.ServerAnalyticsEvent;`):

```java
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
```

and directly after line 16 (`import org.springframework.transaction.annotation.Transactional; // Import Transactional`):

```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.Executor;
```

Add directly after line 57 (`private AnalyticsOutboxService analyticsOutboxService;`):

```java

    @Autowired
    private PlaidItemLifecycleService plaidItemLifecycleService;

    /** Bounded Plaid pool (SchedulingConfig.plaidExecutor); the TaskScheduler is also an Executor, hence the qualifier. */
    @Autowired
    @Qualifier("plaidExecutor")
    private Executor plaidExecutor;

    @PersistenceContext
    private EntityManager entityManager;
```

Inside `exchangePublicTokenAndLinkUser` make five insertions, each anchored on the quoted existing line. Directly after `logger.info("Exchanging public token for user ID: {}", appUser.getId());` (line 85):

```java
        // Serialize per user: SELECT ... FOR UPDATE blocks a second exchange until the first commits.
        // The controller's instance is usually already managed by the request's EntityManager
        // (open-in-view is on), and Hibernate upgrades the lock on a managed instance without
        // re-reading it, so refresh after the lock to see what the other exchange committed.
        appUser = userRepository.findByIdForUpdate(appUser.getId()).orElseThrow();
        entityManager.refresh(appUser);
        LocalDateTime linkedAt = EasternTime.now();
        if (appUser.getPlaidItemLinkedAt() != null
                && appUser.getPlaidItemLinkedAt().isAfter(linkedAt.minusSeconds(60))) {
            throw new LinkInProgressException();
        }
```

Directly after `String rawAccessToken = exchangeResponse.getAccessToken(); // Raw token from Plaid` (line 102):

```java

        // Spec 2.4 step 1, before the new token overwrites the old one: queue the old Item for
        // /item/remove (Plaid bills per Item until then) and remember which Alpaca relationship
        // belonged to the old bank so AchRelationshipService.adopt refuses to re-adopt it.
        if (isPresent(appUser.getPlaidAccessToken())) {
            plaidItemLifecycleService.enqueueRemoval(appUser);
        }
        if (isPresent(appUser.getAlpacaAchRelationshipId())) {
            appUser.setPreviousAchRelationshipId(appUser.getAlpacaAchRelationshipId());
        }
```

Directly after `appUser.setPlaidItemId(exchangeResponse.getItemId());` (line 108):

```java
        appUser.setPlaidItemLinkedAt(linkedAt);
        appUser.setPlaidItemStatus(PlaidItemStatus.OK);
        appUser.setPlaidItemErrorCode(null);
        appUser.setPlaidTransactionsConsented(null); // unknown until the after-commit /item/get answers
        appUser.setPlaidAccountMask(null); // stays null when enrichment fails; adopt then skips the mask check
```

Directly after `accountStatusService.handleBankAccountChange(appUser);` (line 141):

```java

        // Spec 2.4 step 6: /item/get runs after commit on the Plaid pool, never inside this
        // transaction. With no synchronization active (plain unit tests) it goes to the pool now.
        Long userId = appUser.getId();
        Runnable consentRead = () -> plaidItemLifecycleService.recordConsentAfterLink(userId, rawAccessToken);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    plaidExecutor.execute(consentRead);
                }
            });
        } else {
            plaidExecutor.execute(consentRead);
        }
```

Inside `populateAccountAndInstitutionDetails`, directly after `appUser.setPlaidAccountId(primaryAccount.getAccountId());` (line 182):

```java
            appUser.setPlaidAccountMask(primaryAccount.getMask());
```

(`appUser` is reassigned, so it is not captured by the lambda; `userId` and `rawAccessToken` are. `LocalDateTime` is already imported at line 22.)

- [ ] **Step 9: Map the exception to 409 in the controller**

In `$M/controller/PlaidController.java` add directly after line 3 (`import com.investingapp.backend.dto.MessageResponse;`):

```java
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.service.LinkInProgressException;
```

In `exchangePublicTokenAuthenticated`, directly before its `} catch (IOException e) {` (line 97, the catch whose log reads "Error exchanging Plaid public token") insert:

```java
        } catch (LinkInProgressException e) {
            logger.info("/exchange_public_token: user {} already has a link in progress", appUser.getId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("LINK_IN_PROGRESS", "A bank link is already in progress"));
```

- [ ] **Step 10: Run the exchange tests to green**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest' --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsTest' --tests 'com.investingapp.backend.service.PlaidItemLifecycleServiceTest'`
Expected: `BUILD SUCCESSFUL`; 3 + 8 + 8 tests pass.

- [ ] **Step 11: Add the dev backfill controller and refuse it in prod**

Create `$M/controller/DevPlaidController.java` (profile-gated as DevAuthController.java:31-35; `/api/dev/*` already needs `X-Dev-Tools-Key` via DevToolsConfig.java:53 and is permitAll at SecurityConfig.java:118):

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.service.PlaidItemLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-profile Plaid tooling. Registered only in local/dev/test/ci, guarded by DevToolsKeyFilter
 * (X-Dev-Tools-Key) and refused at startup in prod by DevToolsProdGuard.
 */
@RestController
@RequestMapping("/api/dev/plaid")
@CrossOrigin(origins = "*")
@Profile({"local", "dev", "test", "ci"})
public class DevPlaidController {

    private final PlaidItemLifecycleService lifecycleService;

    public DevPlaidController(PlaidItemLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /** Phase 0 backfill: /item/webhook/update for every stored token. Run once per environment. */
    @PostMapping("/backfill-webhook-update")
    public ResponseEntity<PlaidItemLifecycleService.BackfillReport> backfill() {
        return ResponseEntity.ok(lifecycleService.backfillWebhookUrl());
    }
}
```

In `$M/config/DevToolsProdGuard.java` add directly after line 3 (`import com.investingapp.backend.controller.DevAuthController;`):

```java
import com.investingapp.backend.controller.DevPlaidController;
```

and directly after line 43 (`addIfPresent(beanFactory, DevAuthController.class, offenders);`):

```java
        addIfPresent(beanFactory, DevPlaidController.class, offenders);
```

In `$T/config/DevToolsGatingTest.java` add directly after line 3 (`import com.investingapp.backend.controller.DevAuthController;`):

```java
import com.investingapp.backend.controller.DevPlaidController;
```

and directly before the final closing `}` of the class (after `prodGuardAllowsDevProfile`, line 135) add:

```java

    @Test
    @DisplayName("prod refuses to start when the dev Plaid controller is registered anyway")
    void prodGuardFailsStartupWhenDevPlaidControllerPresent() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("devPlaidController", new RootBeanDefinition(DevPlaidController.class));

        DevToolsProdGuard guard = new DevToolsProdGuard();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles(DevToolsProdGuard.PROD_PROFILE);
        guard.setEnvironment(prod);

        assertThatThrownBy(() -> guard.postProcessBeanFactory(beanFactory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("devPlaidController");
    }
```

- [ ] **Step 12: Run the guard test**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.config.DevToolsGatingTest'`
Expected: `BUILD SUCCESSFUL`, 8 tests passed (7 existing + the new one).

- [ ] **Step 13: Run the full T09 set plus the ci gate**

`cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidServiceExchangeLifecycleTest' --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsTest' --tests 'com.investingapp.backend.service.PlaidItemLifecycleServiceTest' --tests 'com.investingapp.backend.config.DevToolsGatingTest' --tests 'com.investingapp.backend.BackendApplicationTests' --tests 'com.investingapp.backend.service.PlaidLinkedAnalyticsAtomicityTest'`
Expected: `BUILD SUCCESSFUL`. The last two need the ci MySQL (T04's scratch schema; without it they are SKIPPED, which proves nothing): `contextLoads` proves the new beans wire (`plaid.webhook.url` from T07's ci properties, `plaidExecutor` from T05, `DevPlaidController` under ci); the atomicity test proves the row lock, the refresh and the `afterCommit` registration under a real transaction (its `@MockitoBean PlaidApi` returns `null` from `itemGet`, which `recordConsentAfterLink` catches and logs as a warning).

Then confirm no bare clock slipped in: `grep -n "LocalDateTime.now()\|LocalDate.now()" backend/src/main/java/com/investingapp/backend/service/PlaidItemLifecycleService.java backend/src/main/java/com/investingapp/backend/service/PlaidService.java` (expect no hits).

- [ ] **Step 14: Commit**

Hunk-verify every modified file with `git diff HEAD -- <file>` (shared dirty tree; stage nothing you did not write), then:

```
M=backend/src/main/java/com/investingapp/backend; T=backend/src/test/java/com/investingapp/backend
git add $M/service/PlaidItemLifecycleService.java $M/service/LinkInProgressException.java $M/service/PlaidService.java $M/repository/PlaidItemRemovalRepository.java $M/repository/UserRepository.java $M/controller/DevPlaidController.java $M/controller/PlaidController.java $M/config/DevToolsProdGuard.java $T/service/PlaidItemLifecycleServiceTest.java $T/service/PlaidServiceExchangeLifecycleTest.java $T/service/PlaidLinkedAnalyticsTest.java $T/config/DevToolsGatingTest.java
git commit -m "feat(plaid): serialize the exchange, queue old Item removal and read consent after commit"
```
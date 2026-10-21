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
        // Row lock before the read, not just before the write: without it this is a classic
        // read-then-blind-write race. A concurrent relink's findByIdForUpdate (in the exchange)
        // could commit a brand new token/item/mask between this method's read and its save, and
        // Hibernate's full-column UPDATE from this stale snapshot would revert that commit.
        writeTx.executeWithoutResult(status -> userRepository.findByIdForUpdate(userId).ifPresent(user -> {
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

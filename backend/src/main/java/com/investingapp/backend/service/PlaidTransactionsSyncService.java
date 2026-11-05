package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.PersonalFinanceCategoryVersion;
import com.plaid.client.model.RemovedTransaction;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsSyncRequest;
import com.plaid.client.model.TransactionsSyncRequestOptions;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.model.TransactionsUpdateStatus;
import com.plaid.client.request.PlaidApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The only caller of Plaid /transactions/sync (spec 2.5). One call pages through every update since
 * the stored cursor, buffers it, and applies the whole buffer in ONE transaction under the user row
 * lock, storing the new cursor in the same commit, so no page is ever persisted on its own.
 *
 * <p>No Plaid HTTP runs inside a database transaction: every page is fetched first, on the caller's
 * thread, and only then does the write transaction open. This class reports Plaid errors and never
 * writes Item state, the consent flag or a hold, and it never reads or writes an investment
 * schedule; PaycheckSyncPipeline routes an ITEM_ERROR by its PlaidErrorClass to
 * PlaidItemStateService.
 *
 * <p>Logs carry ids, codes, states and counts only: never the access token, the cursor, a
 * descriptor, a payer name, an amount or an exception message (spec 12.3).
 */
@Service
public class PlaidTransactionsSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidTransactionsSyncService.class);

    static final String OP = "transactions.sync";
    /** Plaid's maximum page size. */
    static final int PAGE_SIZE = 500;
    /** Spec 2.5: restart from the original cursor at most this many times, then retry later. */
    static final int MAX_RESTARTS = 3;
    /** Runaway-loop stop: 100 pages of 500 rows is far beyond 365 days of any real account. */
    static final int MAX_PAGES = 100;
    /** users.plaid_transactions_update_status is VARCHAR(40) in V6. */
    private static final int UPDATE_STATUS_WIDTH = 40;

    /** Why a sync ran. INIT is an Item's first sync; MANUAL is an admin, dev or update-mode run. */
    public enum SyncReason { INIT, WEBHOOK, RECONCILE, MANUAL }

    /**
     * What a sync did. REFUSED_DISABLED and REFUSED_EXCLUDED are returned by PaycheckSyncPipeline
     * before it calls syncItem; syncItem itself never returns them.
     */
    public enum SyncStatus {
        APPLIED, NOT_INITIALIZED, REFUSED_CONSENT, REFUSED_STALE_ITEM, REFUSED_ITEM_STATUS,
        REFUSED_DISABLED, REFUSED_EXCLUDED, RETRY_LATER, ITEM_ERROR, FAILED
    }

    /**
     * @param historyJustCompleted true only on the sync that first stored HISTORICAL_UPDATE_COMPLETE
     * @param applied the event store's result; non-null exactly when status is APPLIED
     * @param error the parsed Plaid error on ITEM_ERROR, and on a RETRY_LATER after mutation restarts
     */
    public record SyncOutcome(SyncStatus status, boolean historyJustCompleted,
            PaycheckEventService.ApplyResult applied, PlaidApiError error) {

        public static SyncOutcome of(SyncStatus status) {
            return new SyncOutcome(status, false, null, null);
        }

        public static SyncOutcome withError(SyncStatus status, PlaidApiError error) {
            return new SyncOutcome(status, false, null, error);
        }
    }

    /** One pagination run: the whole buffer with the last page's cursor and status, or what stopped it. */
    private record Fetched(SyncOutcome stopped, PaycheckEventService.SyncBatch batch, String nextCursor,
            TransactionsUpdateStatus updateStatus, int pages, int restarts) {

        static Fetched stop(SyncOutcome outcome, int pages, int restarts) {
            return new Fetched(outcome, null, null, null, pages, restarts);
        }
    }

    private final UserRepository userRepository;
    private final PlaidApi plaidApi;
    private final PlaidGateway plaidGateway;
    private final EncryptionService encryptionService;
    private final PaycheckEventService paycheckEventService;
    private final PaycheckAutopilotProperties props;
    private final TransactionTemplate writeTx;

    @PersistenceContext
    private EntityManager entityManager;

    public PlaidTransactionsSyncService(UserRepository userRepository,
            PlaidApi plaidApi,
            PlaidGateway plaidGateway,
            EncryptionService encryptionService,
            PaycheckEventService paycheckEventService,
            PaycheckAutopilotProperties props,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.plaidApi = plaidApi;
        this.plaidGateway = plaidGateway;
        this.encryptionService = encryptionService;
        this.paycheckEventService = paycheckEventService;
        this.props = props;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Sync one Item. Call it outside any transaction: it calls Plaid on this thread and then opens
     * its own write transaction.
     */
    public SyncOutcome syncItem(Long userId, String itemId, SyncReason reason) {
        User user = userRepository.findById(userId).orElse(null);
        Optional<SyncStatus> refused = refusal(user, itemId);
        if (refused.isPresent()) {
            if (refused.get() == SyncStatus.REFUSED_STALE_ITEM) {
                logger.info("plaid.stale_item_event source=sync user_id={} item_id={} reason={}", userId, itemId, reason);
            } else {
                logger.info("plaid.sync.refused user_id={} item_id={} reason={} status={}",
                        userId, itemId, reason, refused.get());
            }
            return SyncOutcome.of(refused.get());
        }
        String originalCursor = user.getPlaidSyncCursor();
        Fetched fetched;
        try {
            fetched = fetch(userId, encryptionService.decrypt(user.getPlaidAccessToken()), originalCursor);
        } catch (RuntimeException e) {
            logger.error("plaid.error op={} user_id={} item_id={} reason={} exception={}",
                    OP, userId, itemId, reason, e.getClass().getSimpleName());
            return SyncOutcome.of(SyncStatus.FAILED);
        }
        if (fetched.stopped() != null) {
            logStop(userId, itemId, reason, fetched);
            return fetched.stopped();
        }
        if (fetched.nextCursor() == null || fetched.nextCursor().isEmpty()) {
            // Spec 2.5: an empty next_cursor means Transactions is not initialized yet; persist nothing.
            logger.info("plaid.sync.not_initialized user_id={} item_id={} reason={} update_status={}",
                    userId, itemId, reason, fetched.updateStatus());
            return SyncOutcome.of(SyncStatus.NOT_INITIALIZED);
        }
        return apply(userId, itemId, reason, originalCursor, fetched);
    }

    /** Spec 2.5 step 1, checked against the row as read before any Plaid call. */
    private static Optional<SyncStatus> refusal(User user, String itemId) {
        if (user == null || itemId == null || !itemId.equals(user.getPlaidItemId())
                || user.getPlaidAccessToken() == null || user.getPlaidAccessToken().isBlank()) {
            return Optional.of(SyncStatus.REFUSED_STALE_ITEM);
        }
        PlaidItemStatus status = user.getPlaidItemStatus();
        if (status != PlaidItemStatus.OK && status != PlaidItemStatus.PENDING_DISCONNECT) {
            return Optional.of(SyncStatus.REFUSED_ITEM_STATUS);
        }
        if (Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            return Optional.of(SyncStatus.REFUSED_CONSENT);
        }
        return Optional.empty();
    }

    /**
     * Page until has_more is false. A MUTATION result discards the buffer and restarts from the
     * cursor this sync started with (the first page of this update, never an empty cursor).
     */
    private Fetched fetch(Long userId, String accessToken, String originalCursor) {
        int pages = 0;
        int restarts = 0;
        while (true) {
            List<Transaction> added = new ArrayList<>();
            List<Transaction> modified = new ArrayList<>();
            List<RemovedTransaction> removed = new ArrayList<>();
            Map<String, AccountBase> accounts = new LinkedHashMap<>();
            String cursor = originalCursor;
            TransactionsUpdateStatus updateStatus = null;
            boolean hasMore = true;
            boolean restart = false;
            while (hasMore) {
                if (pages >= MAX_PAGES) {
                    logger.error("plaid.sync.page_cap user_id={} pages={}", userId, pages);
                    return Fetched.stop(SyncOutcome.of(SyncStatus.FAILED), pages, restarts);
                }
                pages++;
                PlaidCallResult<TransactionsSyncResponse> result =
                        plaidGateway.call(OP, plaidApi.transactionsSync(request(accessToken, cursor)));
                if (!result.ok() || result.body() == null) {
                    PlaidApiError error = result.error();
                    if (error == null) {
                        return Fetched.stop(SyncOutcome.of(SyncStatus.FAILED), pages, restarts);
                    }
                    if (error.errorClass() != PlaidErrorClass.MUTATION) {
                        return Fetched.stop(SyncOutcome.withError(SyncStatus.ITEM_ERROR, error), pages, restarts);
                    }
                    if (restarts >= MAX_RESTARTS) {
                        return Fetched.stop(SyncOutcome.withError(SyncStatus.RETRY_LATER, error), pages, restarts);
                    }
                    restarts++;
                    restart = true;
                    break;
                }
                TransactionsSyncResponse page = result.body();
                added.addAll(orEmpty(page.getAdded()));
                modified.addAll(orEmpty(page.getModified()));
                removed.addAll(orEmpty(page.getRemoved()));
                for (AccountBase account : orEmpty(page.getAccounts())) {
                    accounts.put(account.getAccountId(), account);
                }
                updateStatus = page.getTransactionsUpdateStatus();
                cursor = page.getNextCursor();
                hasMore = Boolean.TRUE.equals(page.getHasMore());
            }
            if (!restart) {
                PaycheckEventService.SyncBatch batch = new PaycheckEventService.SyncBatch(
                        added, modified, removed, new ArrayList<>(accounts.values()));
                return new Fetched(null, batch, cursor, updateStatus, pages, restarts);
            }
        }
    }

    /** days_requested goes on EVERY request on every path: whichever call first succeeds locks the depth. */
    private TransactionsSyncRequest request(String accessToken, String cursor) {
        TransactionsSyncRequest request = new TransactionsSyncRequest()
                .accessToken(accessToken)
                .count(PAGE_SIZE)
                .options(new TransactionsSyncRequestOptions()
                        .includeOriginalDescription(true)
                        .personalFinanceCategoryVersion(PersonalFinanceCategoryVersion.V2)
                        .daysRequested(props.getDaysRequested()));
        if (cursor != null && !cursor.isEmpty()) {
            request.cursor(cursor);
        }
        return request;
    }

    private SyncOutcome apply(Long userId, String itemId, SyncReason reason, String originalCursor, Fetched fetched) {
        LocalDateTime nowET = EasternTime.now();
        SyncOutcome outcome;
        try {
            outcome = writeTx.execute(status -> applyLocked(userId, itemId, reason, originalCursor, fetched, nowET));
        } catch (RuntimeException e) {
            // The class name only: a JDBC message can quote the descriptor that failed to fit.
            logger.error("plaid.sync.apply_failed user_id={} item_id={} reason={} exception={}",
                    userId, itemId, reason, e.getClass().getSimpleName());
            return SyncOutcome.of(SyncStatus.FAILED);
        }
        if (outcome == null) {
            return SyncOutcome.of(SyncStatus.FAILED);
        }
        if (outcome.status() == SyncStatus.APPLIED) {
            PaycheckEventService.SyncBatch batch = fetched.batch();
            logger.info("plaid.sync.applied user_id={} item_id={} reason={} pages={} restarts={} added={} modified={}"
                            + " removed={} new_inflows={} update_status={} history_just_completed={}",
                    userId, itemId, reason, fetched.pages(), fetched.restarts(), batch.added().size(),
                    batch.modified().size(), batch.removed().size(),
                    outcome.applied() == null ? 0 : outcome.applied().newPostedInflowEventIds().size(),
                    fetched.updateStatus(), outcome.historyJustCompleted());
        }
        return outcome;
    }

    /** Runs inside writeTx: lock, re-read, re-check the Item and the cursor, apply, store. */
    private SyncOutcome applyLocked(Long userId, String itemId, SyncReason reason, String originalCursor,
            Fetched fetched, LocalDateTime nowET) {
        User locked = userRepository.findByIdForUpdate(userId).orElse(null);
        if (locked != null) {
            // Open-in-view is on: on a request thread findById left this User managed, and Hibernate
            // locks a managed instance without re-reading it (PlaidService refreshes for the same reason).
            entityManager.refresh(locked);
        }
        if (locked == null || !itemId.equals(locked.getPlaidItemId())) {
            logger.info("plaid.stale_item_event source=sync_apply user_id={} item_id={} reason={}", userId, itemId, reason);
            return SyncOutcome.of(SyncStatus.REFUSED_STALE_ITEM);
        }
        if (!Objects.equals(originalCursor, locked.getPlaidSyncCursor())) {
            // Another sync of this Item committed while this one paged. Its cursor stands; this buffer
            // is dropped, and the retry pages on from the newer cursor.
            logger.info("plaid.sync.cursor_moved user_id={} item_id={} reason={}", userId, itemId, reason);
            return SyncOutcome.of(SyncStatus.RETRY_LATER);
        }
        PaycheckEventService.ApplyResult applied = paycheckEventService.applySyncBatch(userId, fetched.batch(), nowET);
        boolean wasComplete = Boolean.TRUE.equals(locked.getPlaidHistoryComplete());
        boolean completeNow = fetched.updateStatus() == TransactionsUpdateStatus.HISTORICAL_UPDATE_COMPLETE;
        // Set on the locked managed entity, never through a @Modifying query, which a later flush of
        // this same entity would silently undo.
        locked.setPlaidSyncCursor(fetched.nextCursor());
        locked.setPlaidLastSyncAt(nowET);
        locked.setPlaidTransactionsUpdateStatus(fetched.updateStatus() == null
                ? null
                : ColumnText.fit(fetched.updateStatus().getValue(), UPDATE_STATUS_WIDTH));
        locked.setPlaidHistoryComplete(wasComplete || completeNow);
        userRepository.save(locked);
        return new SyncOutcome(SyncStatus.APPLIED, !wasComplete && completeNow, applied, null);
    }

    private static void logStop(Long userId, String itemId, SyncReason reason, Fetched fetched) {
        PlaidApiError error = fetched.stopped().error();
        logger.warn("plaid.error op={} user_id={} item_id={} reason={} status={} code={} class={} request_id={}"
                        + " pages={} restarts={}",
                OP, userId, itemId, reason, fetched.stopped().status(),
                error == null ? null : error.errorCode(),
                error == null ? null : error.errorClass(),
                error == null ? null : error.requestId(),
                fetched.pages(), fetched.restarts());
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}

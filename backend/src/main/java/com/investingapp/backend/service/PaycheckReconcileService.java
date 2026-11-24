package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.plaid.PlaidOutageBreaker;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.ItemGetRequest;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.PlaidError;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * The 22:30 ET paycheck reconcile (spec 5.6, 6.4, 7.9, 11; rulings R4b, R6, R8). Phase A is database
 * only, per user under the user row lock; phase B calls Plaid one user at a time until the deadline
 * or a tripped breaker. No Plaid call runs inside a transaction. Nothing here moves money or writes a
 * schedule's lifecycle, mode or hold (PlaidItemStateService owns Item-state holds), and no analytics
 * event is recorded, because no schedule transition happens here.
 */
@Service
public class PaycheckReconcileService {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckReconcileService.class);

    /** Spec 11: stop dispatching at 23:20 ET. */
    public static final LocalTime CUTOFF = LocalTime.of(23, 20);
    /** A run's own budget; from 22:30 it ends at CUTOFF. */
    public static final Duration MANUAL_RUN_BUDGET = Duration.ofMinutes(50);
    /** Spec 2.6: streams are re-read when the last read is older than this. */
    public static final Duration STREAMS_MAX_AGE = Duration.ofHours(24);
    /** Spec 7.9: events older than this many months are deleted. */
    public static final int EVENT_RETENTION_MONTHS = 24;
    /** Ops alert subject for every reconcile alert: one row per type per night. */
    public static final String ALERT_SUBJECT = "nightly";

    private static final String REJECTED = "REJECTED";
    /** ITEM_BROKEN codes that mean the Item is gone rather than needing a login (spec 5.7 row 2). */
    private static final Set<String> REVOKED_CODES = Set.of("ITEM_NOT_FOUND", "USER_PERMISSION_REVOKED");

    /** initialized and synced count INIT and RECONCILE runs dispatched; truncated = deadline, aborted = breaker. */
    public record ReconcileReport(int candidates, int initialized, int synced, int streamsReread, int evaluated,
            int misses, int stale, int retired, int rejectedItemsQueued, int eventsPurged,
            boolean truncated, boolean aborted) {

        public static ReconcileReport empty() {
            return new ReconcileReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);
        }

        /** Counts and flags only, for the RECONCILE_RAN alert context. */
        public Map<String, Object> toContext() {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("completed", true);
            context.put("candidates", candidates);
            context.put("initialized", initialized);
            context.put("synced", synced);
            context.put("streams_reread", streamsReread);
            context.put("evaluated", evaluated);
            context.put("misses", misses);
            context.put("stale", stale);
            context.put("retired", retired);
            context.put("rejected_items_queued", rejectedItemsQueued);
            context.put("events_purged", eventsPurged);
            context.put("truncated", truncated);
            context.put("aborted", aborted);
            return context;
        }
    }

    /** Mutable counters for one run; the report is built once at the end. */
    private static final class Tally {
        int candidates, initialized, synced, streamsReread, evaluated;
        int misses, stale, retired, rejectedItemsQueued, eventsPurged;
        boolean truncated, aborted;

        ReconcileReport report() {
            return new ReconcileReport(candidates, initialized, synced, streamsReread, evaluated,
                    misses, stale, retired, rejectedItemsQueued, eventsPurged, truncated, aborted);
        }
    }

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckEventRepository eventRepository;
    private final PaycheckCycleService cycleService;
    private final PaycheckSyncPipeline pipeline;
    private final PlaidItemStateService itemStateService;
    private final PlaidItemLifecycleService lifecycleService;
    private final PlaidGateway gateway;
    private final PlaidOutageBreaker breaker;
    private final PlaidApi plaidApi;
    private final EncryptionService encryptionService;
    private final OpsAlertService opsAlertService;
    private final PaycheckAutopilotProperties props;
    private final UserRowLock userRowLock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    /** Eastern wall clock for the deadline checks; tests replace it by reflection. */
    private Supplier<LocalDateTime> clock = EasternTime::now;

    public PaycheckReconcileService(UserRepository userRepository,
            InvestmentScheduleRepository scheduleRepository,
            PaycheckEventRepository eventRepository,
            PaycheckCycleService cycleService,
            PaycheckSyncPipeline pipeline,
            PlaidItemStateService itemStateService,
            PlaidItemLifecycleService lifecycleService,
            PlaidGateway gateway,
            PlaidOutageBreaker breaker,
            PlaidApi plaidApi,
            EncryptionService encryptionService,
            OpsAlertService opsAlertService,
            PaycheckAutopilotProperties props,
            UserRowLock userRowLock,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.cycleService = cycleService;
        this.pipeline = pipeline;
        this.itemStateService = itemStateService;
        this.lifecycleService = lifecycleService;
        this.gateway = gateway;
        this.breaker = breaker;
        this.plaidApi = plaidApi;
        this.encryptionService = encryptionService;
        this.opsAlertService = opsAlertService;
        this.props = props;
        this.userRowLock = userRowLock;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    /** Start plus MANUAL_RUN_BUDGET, cut to CUTOFF when the run starts before it and the budget passes it. */
    static LocalDateTime deadline(LocalDate todayET, LocalDateTime startET) {
        LocalDateTime budget = startET.plus(MANUAL_RUN_BUDGET);
        LocalDateTime cutoff = todayET.atTime(CUTOFF);
        return startET.isBefore(cutoff) && cutoff.isBefore(budget) ? cutoff : budget;
    }

    /** The whole reconcile: phase A for every affected user, then phase B until the deadline. */
    public ReconcileReport run(LocalDate todayET, LocalDateTime nowET) {
        if (!props.isEnabled()) {
            logger.info("paycheck.reconcile.skipped reason=disabled");
            return ReconcileReport.empty();
        }
        breaker.reset();
        LocalDateTime deadline = deadline(todayET, nowET);
        Tally tally = new Tally();

        for (Long id : idsFrom(() -> cycleService.usersWithOverdueOpenCycles(todayET))) {
            tally.misses += phaseAForUser(id, user -> cycleService.markMissesForUser(user.getId(), todayET));
        }
        for (Long id : idsFrom(() -> cycleService.usersWithStaleConfirmed(nowET))) {
            tally.stale += phaseAForUser(id, user -> cycleService.markStaleForUser(user.getId(), nowET));
        }
        for (Long id : idsFrom(() -> cycleService.usersWithDormantStreamsOver(todayET))) {
            tally.retired += phaseAForUser(id, user -> cycleService.retireDormantForUser(user.getId(), todayET));
        }
        tally.eventsPurged = purgeExpiredEvents(todayET);
        for (User candidate : userRepository.findByPlaidAccessTokenIsNotNullAndAccountStatus(REJECTED)) {
            if (candidate.getPlaidItemStatus() != PlaidItemStatus.REMOVED) {
                tally.rejectedItemsQueued += retireRejectedItem(candidate.getId());
            }
        }
        logger.info("paycheck.reconcile.phase_a misses={} stale={} retired={} events_purged={} rejected_items_queued={}",
                tally.misses, tally.stale, tally.retired, tally.eventsPurged, tally.rejectedItemsQueued);

        List<Long> ids = userRepository.findIdsWithPlaidToken();
        int remaining = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (!clock.get().isBefore(deadline)) {
                tally.truncated = true;
                remaining = ids.size() - i;
                break;
            }
            if (breaker.tripped()) {
                tally.aborted = true;
                remaining = ids.size() - i;
                break;
            }
            reconcileUser(ids.get(i), nowET, tally);
        }
        if (tally.truncated) {
            logger.warn("paycheck.reconcile.truncated remaining={} candidates={}", remaining, tally.candidates);
            opsAlertService.raise(OpsAlerts.RECONCILE_TRUNCATED, ALERT_SUBJECT,
                    "reconcile stopped at its deadline with users left",
                    Map.of("remaining", remaining, "candidates", tally.candidates));
        }
        if (tally.aborted) {
            // The breaker itself raised plaid.outage.
            logger.warn("paycheck.reconcile.aborted reason=plaid_outage remaining={}", remaining);
        }
        logger.info("paycheck.reconcile.phase_b candidates={} initialized={} synced={} streams_reread={} evaluated={} truncated={} aborted={}",
                tally.candidates, tally.initialized, tally.synced, tally.streamsReread, tally.evaluated,
                tally.truncated, tally.aborted);
        return tally.report();
    }

    /** One user (T18's admin verb): phase A and the REJECTED pass for this user, then phase B. No purge, no reset. */
    public ReconcileReport runForUser(Long userId, LocalDate todayET) {
        if (!props.isEnabled()) {
            logger.info("paycheck.reconcile.skipped reason=disabled");
            return ReconcileReport.empty();
        }
        LocalDateTime nowET = clock.get();
        LocalDateTime deadline = deadline(todayET, nowET);
        Tally tally = new Tally();
        tally.misses = phaseAForUser(userId, user -> cycleService.markMissesForUser(user.getId(), todayET));
        tally.stale = phaseAForUser(userId, user -> cycleService.markStaleForUser(user.getId(), nowET));
        tally.retired = phaseAForUser(userId, user -> cycleService.retireDormantForUser(user.getId(), todayET));
        tally.rejectedItemsQueued = retireRejectedItem(userId);
        if (!clock.get().isBefore(deadline)) {
            tally.truncated = true;
        } else if (breaker.tripped()) {
            tally.aborted = true;
        } else {
            reconcileUser(userId, nowET, tally);
        }
        return tally.report();
    }

    // ---- phase A: database only ---------------------------------------------------------------

    private List<Long> idsFrom(Supplier<List<Long>> finder) {
        List<Long> ids = readTx.execute(status -> finder.get());
        return ids == null ? List.of() : ids;
    }

    /**
     * R8 and spec 5.6: phase A leaves REJECTED and opted-out users alone, and runs no miss pass or
     * 120-day retirement while the Item is not OK (a break is not a missed paycheck; final review
     * DC-3). PENDING_DISCONNECT is still OK there. A missing token does not stop it.
     */
    private boolean skippedInPhaseA(User user) {
        Optional<ExclusionReason> reason = DetectionEligibility.exclusion(user, props);
        return reason.isPresent()
                && (reason.get() == ExclusionReason.ACCOUNT_REJECTED || reason.get() == ExclusionReason.OPTED_OUT
                        || reason.get() == ExclusionReason.ITEM_STATUS);
    }

    /** One pass for one user, in its own transaction under the user row lock. Never throws. */
    private int phaseAForUser(Long userId, ToIntFunction<User> pass) {
        try {
            Integer changed = writeTx.execute(status -> userRowLock.lockFresh(userId)
                    .filter(user -> !skippedInPhaseA(user))
                    .map(pass::applyAsInt)
                    .orElse(0));
            return changed == null ? 0 : changed;
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.phase_a_failed user={} exception={}", userId, e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Spec 7.9: events older than 24 months go. Events only; cycles keep their row (event_id SET NULL). */
    private int purgeExpiredEvents(LocalDate todayET) {
        LocalDate before = todayET.minusMonths(EVENT_RETENTION_MONTHS);
        try {
            Long deleted = writeTx.execute(status -> eventRepository.deleteByPostedDateBefore(before));
            return deleted == null ? 0 : (int) Math.min(deleted, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.purge_failed exception={}", e.getClass().getSimpleName());
            return 0;
        }
    }

    /** R8: queue a REJECTED applicant's Item once and mark it REMOVED (off the billable gauge). */
    private int retireRejectedItem(Long userId) {
        try {
            Boolean queued = writeTx.execute(status -> userRowLock.lockFresh(userId).map(user -> {
                if (!REJECTED.equals(user.getAccountStatus()) || user.getPlaidAccessToken() == null
                        || user.getPlaidItemStatus() == PlaidItemStatus.REMOVED) {
                    return false;
                }
                lifecycleService.enqueueRemoval(user);
                user.setPlaidItemStatus(PlaidItemStatus.REMOVED);
                userRepository.save(user);
                logger.debug("paycheck.reconcile.rejected_item_queued user={}", userId);
                return true;
            }).orElse(false));
            return Boolean.TRUE.equals(queued) ? 1 : 0;
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.rejected_item_failed user={} exception={}", userId, e.getClass().getSimpleName());
            return 0;
        }
    }

    // ---- phase B: Plaid, one user at a time ---------------------------------------------------

    /** /item/get, then one short template, then the pipeline, then the streams re-read. Never throws. */
    private void reconcileUser(Long userId, LocalDateTime nowET, Tally tally) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || DetectionEligibility.exclusion(user, props).isPresent()) {
                return;
            }
            String itemId = user.getPlaidItemId();
            if (itemId == null || itemId.isBlank()) {
                return;
            }
            tally.candidates++;

            // 1. The /item/get fallback (spec 5.6), on this thread and outside any transaction.
            PlaidCallResult<ItemGetResponse> got = gateway.call("item.get", plaidApi.itemGet(
                    new ItemGetRequest().accessToken(encryptionService.decrypt(user.getPlaidAccessToken()))));
            if (!got.ok()) {
                PlaidApiError failure = got.error();
                if (failure != null) {
                    routeItemError(userId, itemId, failure.errorCode(), failure.errorClass());
                }
                return;
            }
            ItemWithConsentFields item = got.body() == null ? null : got.body().getItem();
            if (item == null) {
                return;
            }
            PlaidError itemState = item.getError();
            if (itemState != null && itemState.getErrorCode() != null && !itemState.getErrorCode().isBlank()) {
                int httpStatus = itemState.getStatus() == null ? 400 : itemState.getStatus();
                routeItemError(userId, itemId, itemState.getErrorCode(),
                        PlaidErrorClass.classify(itemState.getErrorCode(), httpStatus));
                return;
            }
            LocalDateTime consentExpiresAt = item.getConsentExpirationTime() == null ? null
                    : item.getConsentExpirationTime().atZoneSameInstant(EasternTime.ZONE).toLocalDateTime();

            // 2. One per-user template: consent expiry, the sync reason, and the R4b stamp.
            SyncReason reason = writeTx.execute(status -> prepare(userId, itemId, consentExpiresAt, nowET));
            if (reason == null) {
                return;
            }

            // 3. The cursor sync, with no transaction open (the pipeline takes its own).
            if (breaker.tripped()) {
                tally.aborted = true;
                return;
            }
            PipelineOutcome outcome = pipeline.run(userId, itemId, reason);
            if (reason == SyncReason.INIT) {
                tally.initialized++;
            } else {
                tally.synced++;
            }
            if (outcome != null && outcome.evaluation() != null) {
                tally.evaluated++;
            }

            // 4. Streams: re-read only an old read. The first read belongs to history completion (T14).
            boolean applied = outcome != null && outcome.sync() != null && outcome.sync().status() == SyncStatus.APPLIED;
            if (!applied || breaker.tripped()) {
                return;
            }
            LocalDateTime readAt = userRepository.findById(userId).map(User::getPlaidStreamsReadAt).orElse(null);
            if (readAt != null && readAt.isBefore(nowET.minus(STREAMS_MAX_AGE))) {
                pipeline.streamsChanged(userId, itemId);
                tally.streamsReread++;
            }
        } catch (RuntimeException e) {
            logger.warn("paycheck.reconcile.user_failed user={} exception={}", userId, e.getClass().getSimpleName());
        }
    }

    /**
     * Under the row lock: item guard, consent expiry when given (an absent one never erases a
     * PENDING_DISCONNECT date), sync reason, and the INIT stamp for SEARCHING_24H. Null = no sync.
     */
    private SyncReason prepare(Long userId, String itemId, LocalDateTime consentExpiresAt, LocalDateTime nowET) {
        // Locked and re-read: the admin verb runs this on a request thread (MS-2).
        User user = userRowLock.lockFresh(userId).orElse(null);
        if (user == null || !itemId.equals(user.getPlaidItemId())) {
            logger.debug("plaid.stale_item_event source=reconcile user={}", userId);
            return null;
        }
        if (consentExpiresAt != null) {
            user.setPlaidDisconnectAt(consentExpiresAt);
        }
        SyncReason reason = syncReasonFor(user);
        if (reason == SyncReason.INIT) {
            scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)
                    .filter(schedule -> schedule.getDetectionStartedAt() == null)
                    .ifPresent(schedule -> {
                        schedule.setDetectionStartedAt(nowET);
                        scheduleRepository.save(schedule);
                    });
        }
        userRepository.save(user);
        return reason;
    }

    /** R4b: INIT for a consented-or-unknown OK Item with no cursor; RECONCILE for one with a cursor. */
    private static SyncReason syncReasonFor(User user) {
        if (Boolean.FALSE.equals(user.getPlaidTransactionsConsented())) {
            return null;
        }
        String cursor = user.getPlaidSyncCursor();
        if (cursor == null || cursor.isBlank()) {
            return user.getPlaidItemStatus() == PlaidItemStatus.OK ? SyncReason.INIT : null;
        }
        return SyncReason.RECONCILE;
    }

    /** Routed as T14 routes an ITEM_ERROR (spec 2.1 to 5.7); the other classes write nothing tonight. */
    private void routeItemError(Long userId, String itemId, String errorCode, PlaidErrorClass errorClass) {
        logger.debug("paycheck.reconcile.item_get user={} class={} code={}", userId, errorClass, errorCode);
        switch (errorClass) {
            case CONSENT -> itemStateService.onConsentRequired(userId, itemId);
            case ITEM_BROKEN -> {
                if (REVOKED_CODES.contains(errorCode)) {
                    itemStateService.onRevoked(userId, itemId, errorCode);
                } else {
                    itemStateService.onLoginRequired(userId, itemId, errorCode);
                }
            }
            case NOT_SUPPORTED -> itemStateService.onNotSupported(userId, itemId);
            case SYNC_LIMIT -> itemStateService.onSyncLimit(userId, itemId);
            default -> {
                // Nothing to write; the user is retried tomorrow night.
            }
        }
    }
}

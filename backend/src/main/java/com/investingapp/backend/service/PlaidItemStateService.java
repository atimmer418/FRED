package com.investingapp.backend.service;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every webhook- and error-driven write of Plaid Item state (spec 5.7, both modes), the three
 * Item-state holds, the update-mode consent writes (spec 2.3) and the relink reset (spec 2.4 step 3,
 * 7.9).
 *
 * <p>One shape per handler: a TransactionTemplate under the user row lock, the item guard (else
 * plaid.stale_item_event, no write), the writes, commit, then the RECONNECT notice or ops alert.
 * No Plaid or Alpaca call. Hold rule (decision 30): an Item-state hold is written only over no
 * hold and a repair clears only its own, so MANUAL_REVIEW and ACH return holds keep blocking. The
 * three holds are non-blocking (R2) and emit no event (R3). Never writes status or mode.
 */
@Service
public class PlaidItemStateService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidItemStateService.class);

    public enum ItemStateResult { APPLIED, STALE_ITEM, NO_OP }

    public enum RepairPath { LOGIN_REPAIRED, UPDATE_MODE }

    /** users.plaid_item_error_code is VARCHAR(64). */
    static final int ERROR_CODE_WIDTH = 64;

    /** Items FRED is finished with: removal is queued, so a late webhook must not revive them. */
    private static final Set<PlaidItemStatus> TERMINAL = EnumSet.of(PlaidItemStatus.REVOKED, PlaidItemStatus.REMOVED);

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckEventRepository eventRepository;
    private final PaycheckCycleRepository cycleRepository;
    private final PlaidItemLifecycleService plaidItemLifecycleService;
    private final NotificationService notificationService;
    private final OpsAlertService opsAlertService;
    private final UserRowLock userRowLock;
    private final TransactionTemplate writeTx;

    public PlaidItemStateService(UserRepository userRepository,
            InvestmentScheduleRepository scheduleRepository,
            PaycheckStreamRepository streamRepository,
            PaycheckEventRepository eventRepository,
            PaycheckCycleRepository cycleRepository,
            PlaidItemLifecycleService plaidItemLifecycleService,
            NotificationService notificationService,
            OpsAlertService opsAlertService,
            UserRowLock userRowLock,
            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.cycleRepository = cycleRepository;
        this.plaidItemLifecycleService = plaidItemLifecycleService;
        this.notificationService = notificationService;
        this.opsAlertService = opsAlertService;
        this.userRowLock = userRowLock;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /** What one handler did inside its transaction, and what it owes once that commits. */
    private record Outcome(ItemStateResult result, User user, boolean reconnectDue, boolean syncLimitAlertDue) {
        static Outcome of(ItemStateResult result) {
            return new Outcome(result, null, false, false);
        }

        static Outcome applied(User user) {
            return new Outcome(ItemStateResult.APPLIED, user, false, false);
        }
    }

    @FunctionalInterface
    private interface Writes {
        Outcome apply(User user, Optional<InvestmentSchedule> schedule);
    }

    /** 5.7 row 1: ITEM_LOGIN_REQUIRED (webhook ERROR or API error), ACCESS_NOT_GRANTED, NO_ACCOUNTS. */
    public ItemStateResult onLoginRequired(Long userId, String itemId, String errorCode) {
        return run("login_required", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = user.getPlaidItemStatus() != PlaidItemStatus.LOGIN_REQUIRED;
            user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
            user.setPlaidItemErrorCode(ColumnText.fit(errorCode, ERROR_CODE_WIDTH));
            boolean holdWritten = schedule.map(s -> writeItemHold(s, HoldReason.ITEM_LOGIN_REQUIRED)).orElse(false);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.ITEM_BROKEN));
            // CONFIRMED cycles are skipped for the hold. Phase 1 has no CONFIRMED writer, so this
            // finds nothing today; Phase 2 inherits it. OPEN cycles are left open (decision 39).
            for (PaycheckCycle cycle : cycleRepository.findByUserIdAndState(user.getId(), PaycheckCycleState.CONFIRMED)) {
                cycle.setState(PaycheckCycleState.SKIPPED);
                cycle.setSkipReason(PaycheckSkipReason.HOLD);
                cycleRepository.save(cycle);
            }
            // Spec 7.9: events go and descriptors are nulled when an Item breaks. The stream skeleton
            // (token, cadence, anchors, median) stays so a repair does not restart detection.
            eventRepository.deleteByUserId(user.getId());
            for (PaycheckStream stream : streamRepository.findByUserIdOrderByIdAsc(user.getId())) {
                stream.setRawDescriptor(null);
                stream.setPlaidSnapshotJson(null); // the raw Plaid object holds the description
                streamRepository.save(stream);
            }
            logApplied("login_required", user, holdWritten);
            return new Outcome(ItemStateResult.APPLIED, user, newIncident && reconnectAllowed(user, schedule), false);
        });
    }

    /** 5.7 row 2: USER_PERMISSION_REVOKED, ITEM_NOT_FOUND. {@code source} is that code, a closed word. */
    public ItemStateResult onRevoked(Long userId, String itemId, String source) {
        return run("revoked", userId, itemId, true, (user, schedule) -> revoke(user, schedule, source));
    }

    /**
     * USER_ACCOUNT_REVOKED: the funding account is a full revoke; another account drops its events
     * (spec 2.8) and, as when an Item breaks (spec 7.9), its streams lose the bank's description and
     * the raw Plaid record (final review PAL-1). The rest of those streams stays until a relink,
     * a full revoke or account deletion.
     */
    public ItemStateResult onAccountRevoked(Long userId, String itemId, String accountId) {
        return run("account_revoked", userId, itemId, true, (user, schedule) -> {
            if (accountId != null && accountId.equals(user.getPlaidAccountId())) {
                return revoke(user, schedule, "USER_ACCOUNT_REVOKED");
            }
            eventRepository.deleteByUserIdAndPlaidAccountId(user.getId(), accountId);
            for (PaycheckStream stream : streamRepository.findByUserIdOrderByIdAsc(user.getId())) {
                if (accountId != null && accountId.equals(stream.getPlaidAccountId())) {
                    stream.setRawDescriptor(null);
                    stream.setPlaidSnapshotJson(null);
                    streamRepository.save(stream);
                }
            }
            logApplied("account_revoked_other", user, false);
            return Outcome.applied(user);
        });
    }

    /** 5.7 row 4: ADDITIONAL_CONSENT_REQUIRED or ACCESS_NOT_GRANTED on a sync (class CONSENT). */
    public ItemStateResult onConsentRequired(Long userId, String itemId) {
        return run("consent_required", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = !Boolean.FALSE.equals(user.getPlaidTransactionsConsented());
            user.setPlaidTransactionsConsented(Boolean.FALSE);
            boolean holdWritten = schedule.map(s -> writeItemHold(s, HoldReason.CONSENT_REQUIRED)).orElse(false);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.NEEDS_CONSENT));
            logApplied("consent_required", user, holdWritten);
            return new Outcome(ItemStateResult.APPLIED, user, newIncident && reconnectAllowed(user, schedule), false);
        });
    }

    /** Update mode added the Transactions consent (T16). The first sync is left to the reconcile or the status endpoint. */
    public ItemStateResult onConsentGranted(Long userId, String itemId) {
        return run("consent_granted", userId, itemId, true, (user, schedule) -> {
            user.setPlaidTransactionsConsented(Boolean.TRUE);
            user.setPlaidDisconnectAt(null);
            schedule.ifPresent(s -> {
                clearItemHold(s, HoldReason.CONSENT_REQUIRED);
                if (s.getDetectionState() == DetectionState.NEEDS_CONSENT) {
                    s.setDetectionState(DetectionState.SEARCHING);
                }
            });
            logApplied("consent_granted", user, false);
            return Outcome.applied(user);
        });
    }

    /**
     * Every successful update-mode completion (T16, spec 2.3): store /item/get's
     * consent_expiration_time as Eastern wall time (null when Plaid sends none) and clear a
     * PENDING_DISCONNECT, so the reconnect card goes away.
     */
    public ItemStateResult onConsentRenewed(Long userId, String itemId, OffsetDateTime consentExpiration) {
        return run("consent_renewed", userId, itemId, true, (user, schedule) -> {
            user.setPlaidDisconnectAt(toEastern(consentExpiration));
            if (user.getPlaidItemStatus() == PlaidItemStatus.PENDING_DISCONNECT) {
                user.setPlaidItemStatus(PlaidItemStatus.OK);
            }
            logApplied("consent_renewed", user, false);
            return Outcome.applied(user);
        });
    }

    /**
     * 5.7 row 4: TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED. No hold in any mode in Phase 1 (R2, decision 33).
     * Phase 2 seam: the MANUAL_REVIEW hold, gated on !props.isShadow(), with
     * schedule_paused(system, a new reason) in this same transaction.
     */
    public ItemStateResult onSyncLimit(Long userId, String itemId) {
        return run("sync_limit", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = user.getPlaidItemStatus() != PlaidItemStatus.SYNC_LIMIT;
            user.setPlaidItemStatus(PlaidItemStatus.SYNC_LIMIT);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.UNDETECTABLE));
            logApplied("sync_limit", user, false);
            return new Outcome(ItemStateResult.APPLIED, user, false, newIncident);
        });
    }

    /** Spec 2.1 NOT_SUPPORTED: no hold, no reconnect card, no notice, off the reconcile list. */
    public ItemStateResult onNotSupported(Long userId, String itemId) {
        return run("not_supported", userId, itemId, true, (user, schedule) -> {
            user.setPlaidItemStatus(PlaidItemStatus.NOT_SUPPORTED);
            schedule.ifPresent(s -> s.setDetectionState(DetectionState.UNDETECTABLE));
            logApplied("not_supported", user, false);
            return Outcome.applied(user);
        });
    }

    /** 5.7 row 3: LOGIN_REPAIRED webhook or update-mode success, only from LOGIN_REQUIRED. */
    public ItemStateResult onLoginRepaired(Long userId, String itemId, RepairPath path) {
        return run("login_repaired", userId, itemId, true, (user, schedule) -> {
            if (user.getPlaidItemStatus() != PlaidItemStatus.LOGIN_REQUIRED) {
                logger.warn("plaid.login_repaired.ignored user_id={} path={} item_status={}",
                        user.getId(), path, user.getPlaidItemStatus());
                return Outcome.of(ItemStateResult.NO_OP);
            }
            user.setPlaidItemStatus(PlaidItemStatus.OK);
            user.setPlaidItemErrorCode(null);
            schedule.ifPresent(s -> {
                clearItemHold(s, HoldReason.ITEM_LOGIN_REQUIRED);
                if (s.getDetectionState() == DetectionState.ITEM_BROKEN) {
                    s.setDetectionState(stateAfterRepair(s));
                }
            });
            logger.info("plaid.item_state handler=login_repaired user_id={} path={}", user.getId(), path);
            return Outcome.applied(user);
        });
    }

    /** PENDING_DISCONNECT or PENDING_EXPIRATION: the date and a RECONNECT, no hold until an error arrives. */
    public ItemStateResult onPendingDisconnect(Long userId, String itemId, OffsetDateTime disconnectAt) {
        return run("pending_disconnect", userId, itemId, true, (user, schedule) -> {
            boolean newIncident = user.getPlaidItemStatus() == PlaidItemStatus.OK;
            if (newIncident) {
                user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
            }
            if (disconnectAt != null) {
                user.setPlaidDisconnectAt(toEastern(disconnectAt));
            }
            logApplied("pending_disconnect", user, false);
            return new Outcome(ItemStateResult.APPLIED, user, newIncident && reconnectAllowed(user, schedule), false);
        });
    }

    /** WEBHOOK_UPDATE_ACKNOWLEDGED, any Item status. */
    public ItemStateResult onWebhookAcked(Long userId, String itemId) {
        return run("webhook_acked", userId, itemId, false, (user, schedule) -> {
            user.setPlaidWebhookAckedAt(EasternTime.now());
            return Outcome.applied(user);
        });
    }

    /**
     * Spec 2.4 step 3 and 7.9, called by PlaidService.exchangePublicTokenAndLinkUser inside its own
     * transaction: the new Item starts with no cursor and no detection data. Never status, mode or
     * hold_reason (the exchange's resetForBankChange owns the hold).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resetForNewItem(User user) {
        user.setPlaidSyncCursor(null);
        user.setPlaidTransactionsUpdateStatus(null);
        user.setPlaidLastSyncAt(null);
        user.setPlaidStreamsReadAt(null);
        user.setPlaidDisconnectAt(null);
        user.setPlaidHistoryComplete(false);
        scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).ifPresent(s -> {
            s.setDetectionState(null);
            s.setPrimaryStreamId(null);
            s.setNextExpectedDate(null);
            s.setDormantSince(null);
            s.setDetectionStartedAt(null);
            s.setMissedCount((short) 0);
            scheduleRepository.saveAndFlush(s); // release the stream before its delete
        });
        cycleRepository.deleteByUserId(user.getId());
        eventRepository.deleteByUserId(user.getId());
        streamRepository.deleteByUserId(user.getId());
        logger.info("plaid.item_state handler=reset_for_new_item user_id={}", user.getId());
    }

    private Outcome revoke(User user, Optional<InvestmentSchedule> schedule, String source) {
        user.setPlaidItemStatus(PlaidItemStatus.REVOKED);
        user.setPlaidItemErrorCode(ColumnText.fit(source, ERROR_CODE_WIDTH));
        boolean holdWritten = false;
        if (schedule.isPresent()) {
            InvestmentSchedule s = schedule.get();
            holdWritten = writeItemHold(s, HoldReason.PERMISSION_REVOKED);
            // Decision 70: clear the mirror and flush it BEFORE the purge, so the schedule never
            // points at a stream row that is about to be deleted.
            s.setPrimaryStreamId(null);
            s.setNextExpectedDate(null);
            s.setDetectionState(DetectionState.REVOKED);
            scheduleRepository.saveAndFlush(s);
        }
        cycleRepository.deleteByUserId(user.getId());
        eventRepository.deleteByUserId(user.getId());
        streamRepository.deleteByUserId(user.getId());
        user.setPlaidSyncCursor(null);
        user.setPlaidHistoryComplete(false);
        // Plaid bills until /item/remove; repair is a fresh link (spec 5.7).
        plaidItemLifecycleService.enqueueRemoval(user);
        logApplied("revoked", user, holdWritten);
        return Outcome.applied(user);
    }

    private ItemStateResult run(String handler, Long userId, String itemId, boolean refuseTerminal, Writes writes) {
        Outcome outcome = writeTx.execute(status -> {
            // Locked and re-read: the guards and the save below must see the committed row (MS-2).
            Optional<User> locked = userRowLock.lockFresh(userId);
            if (locked.isEmpty() || itemId == null || !itemId.equals(locked.get().getPlaidItemId())) {
                logger.info("plaid.stale_item_event handler={} user_id={}", handler, userId);
                return Outcome.of(ItemStateResult.STALE_ITEM);
            }
            User user = locked.get();
            if (refuseTerminal && TERMINAL.contains(user.getPlaidItemStatus())) {
                logger.warn("plaid.item_state.ignored handler={} user_id={} item_status={}",
                        handler, userId, user.getPlaidItemStatus());
                return Outcome.of(ItemStateResult.NO_OP);
            }
            Optional<InvestmentSchedule> schedule = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
            // The Phase 0 MANUAL_REVIEW writers do not take the user lock: re-read the schedule under
            // its own lock so writeItemHold never writes over a hold committed after a request's read (MS-1).
            schedule.ifPresent(userRowLock::refreshLocked);
            Outcome result = writes.apply(user, schedule);
            if (result.result() == ItemStateResult.APPLIED) {
                userRepository.save(user);
                schedule.ifPresent(scheduleRepository::save);
            }
            return result;
        });
        followUpAfterCommit(handler, userId, outcome);
        return outcome.result();
    }

    /** Email and alert I/O never sit inside a DB transaction (decision 18). */
    private void followUpAfterCommit(String handler, Long userId, Outcome outcome) {
        if (!outcome.reconnectDue() && !outcome.syncLimitAlertDue()) {
            return;
        }
        Runnable followUp = () -> {
            if (outcome.syncLimitAlertDue()) {
                opsAlertService.raise(OpsAlerts.PLAID_SYNC_ITEM_LIMIT, String.valueOf(userId),
                        "Plaid refused transactions sync (TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED); detection stopped for this user",
                        Map.of("item_status", PlaidItemStatus.SYNC_LIMIT.name()));
            }
            if (outcome.reconnectDue()) {
                try {
                    notificationService.sendReconnect(outcome.user(), EasternTime.today());
                } catch (RuntimeException e) {
                    logger.warn("paycheck.notice.reconnect_failed handler={} user_id={} error_class={}",
                            handler, userId, e.getClass().getSimpleName());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Inside an outer transaction: wait for its commit.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    followUp.run();
                }
            });
        } else {
            followUp.run();
        }
    }

    /**
     * R18: RECONNECT is for users who can invest. A rejected applicant and a user with no schedule
     * row (still onboarding) get none. Read directly: DetectionEligibility.exclusion reports one
     * reason, which can be DISABLED or ITEM_STATUS for a REJECTED account.
     */
    private static boolean reconnectAllowed(User user, Optional<InvestmentSchedule> schedule) {
        return schedule.isPresent() && !"REJECTED".equals(user.getAccountStatus());
    }

    /** Decision 30: write over no hold only. Returns whether the hold was written. */
    private static boolean writeItemHold(InvestmentSchedule schedule, HoldReason hold) {
        if (schedule.getHoldReason() != null) {
            return false;
        }
        schedule.setHoldReason(hold);
        return true;
    }

    /** Decision 30: a repair or consent path clears only the hold it writes. */
    private static void clearItemHold(InvestmentSchedule schedule, HoldReason hold) {
        if (schedule.getHoldReason() == hold) {
            schedule.setHoldReason(null);
        }
    }

    /** Spec 5.7 row 3: back to the primary stream's state, or SEARCHING when there is none. */
    private DetectionState stateAfterRepair(InvestmentSchedule schedule) {
        if (schedule.getPrimaryStreamId() == null) {
            return DetectionState.SEARCHING;
        }
        return streamRepository.findById(schedule.getPrimaryStreamId())
                .map(stream -> switch (stream.getState()) {
                    case PROVISIONAL -> DetectionState.PROVISIONAL;
                    case CONFIRMED -> DetectionState.CONFIRMED;
                    case DORMANT -> DetectionState.DORMANT;
                    default -> DetectionState.SEARCHING;
                })
                .orElse(DetectionState.SEARCHING);
    }

    private static LocalDateTime toEastern(OffsetDateTime instant) {
        return instant == null ? null : instant.atZoneSameInstant(EasternTime.ZONE).toLocalDateTime();
    }

    private static void logApplied(String handler, User user, boolean holdWritten) {
        logger.info("plaid.item_state handler={} user_id={} item_status={} hold_written={}",
                handler, user.getId(), user.getPlaidItemStatus(), holdWritten);
    }
}
